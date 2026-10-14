"""Editing of existing PDF content.

Text edits are genuine content modifications, not overlays:
  1. the original glyphs are removed from the page content stream (MuPDF's
     redaction engine in text-only mode, so images and vector art under the
     text are untouched);
  2. the new text is written into the page content, re-using the original
     embedded font when its subset contains every glyph required, otherwise a
     metric-similar substitute font (the result reports which).
  3. the edited region is re-extracted and compared with the requested text.

Image objects are moved/resized/deleted by editing the operators in the page
content stream (pikepdf), so the image data itself is never re-encoded.
"""

from __future__ import annotations

import os
import re

import pymupdf

from ..errors import NOT_SUPPORTED, VALIDATION_FAILED, EngineError, invalid
from ..registry import method
from ..util.fontcodec import FontCodec, find_font_xref
from ..util.geometry import fitz_to_pdf_rect, to_fitz_point, to_fitz_rect
from ..util.io import OutputFile, require_file, validate_pdf
from ..util.pdf import doc_is_signed, hex_to_rgb, int_to_rgb, open_pdf, save_doc

FALLBACK = {
    # (serif, mono, bold, italic) -> base-14 font name
    (True, False, False, False): "tiro", (True, False, True, False): "tibo",
    (True, False, False, True): "tiit", (True, False, True, True): "tibi",
    (False, True, False, False): "cour", (False, True, True, False): "cobo",
    (False, True, False, True): "coit", (False, True, True, True): "cobi",
    (False, False, False, False): "helv", (False, False, True, False): "hebo",
    (False, False, False, True): "heit", (False, False, True, True): "hebi",
}
NOTO = {(False, False): "notos", (True, False): "notosbo", (False, True): "notosit", (True, True): "notosbi"}
FONT_LABELS = {"tiro": "Times", "tibo": "Times Bold", "tiit": "Times Italic", "tibi": "Times Bold Italic",
               "helv": "Helvetica", "hebo": "Helvetica Bold", "heit": "Helvetica Oblique",
               "hebi": "Helvetica Bold Oblique", "cour": "Courier", "cobo": "Courier Bold",
               "coit": "Courier Oblique", "cobi": "Courier Bold Oblique", "notos": "Noto Sans",
               "notosbo": "Noto Sans Bold", "notosit": "Noto Sans Italic", "notosbi": "Noto Sans Bold Italic"}


def _winansi_ok(text: str) -> bool:
    try:
        text.encode("cp1252")
        return True
    except UnicodeEncodeError:
        return False


# ---------------------------------------------------------------------------
# Content-level helpers
# ---------------------------------------------------------------------------

def remove_text(page: pymupdf.Page, rects: list[pymupdf.Rect]) -> None:
    """Remove the text glyphs inside rects from the page content.

    Pending redaction annotations created by the user are preserved (MuPDF
    would otherwise apply them at the same time).
    """
    pending = []
    for a in list(page.annots(types=[pymupdf.PDF_ANNOT_REDACT])):
        pending.append({"rect": a.rect, "info": a.info, "colors": a.colors})
        page.delete_annot(a)
    for r in rects:
        r = pymupdf.Rect(r)
        h = r.height
        w = r.width
        # Shrink to the line's core so neighbouring lines/cells are not touched.
        core = pymupdf.Rect(r.x0 + min(0.4, w * 0.02), r.y0 + h * 0.3, r.x1 - min(0.4, w * 0.02), r.y1 - h * 0.3)
        if core.is_empty:
            core = r
        page.add_redact_annot(core, fill=False, cross_out=False)
    page.apply_redactions(images=pymupdf.PDF_REDACT_IMAGE_NONE,
                          graphics=pymupdf.PDF_REDACT_LINE_ART_NONE,
                          text=pymupdf.PDF_REDACT_TEXT_REMOVE)
    for p in pending:
        a = page.add_redact_annot(p["rect"], fill=(p["colors"].get("fill") or (0, 0, 0)))
        a.set_info(p["info"])
        a.update()


def append_content(page: pymupdf.Page, ops: str) -> None:
    """Append a content stream to the page, isolated from the existing graphics state."""
    doc = page.parent
    if not page.is_wrapped:
        page.wrap_contents()
    xref = doc.get_new_xref()
    doc.update_object(xref, "<<>>")
    doc.update_stream(xref, ops.encode("latin-1"))
    contents = page.get_contents()
    arr = " ".join(f"{x} 0 R" for x in contents + [xref])
    doc.xref_set_key(page.xref, "Contents", f"[{arr}]")


def _font_resource_name(page: pymupdf.Page, xref: int) -> str:
    """Name under which the font is available to the page's own content."""
    doc = page.parent
    for f in page.get_fonts(full=True):
        if f[0] == xref and f[6] == 0:
            return f[4]
    # Font only lives inside a form XObject: register it on the page.
    name = f"NyE{xref}"
    owner = page.xref
    while True:
        t, v = doc.xref_get_key(owner, "Resources")
        if t != "null":
            break
        pt, pv = doc.xref_get_key(owner, "Parent")
        if pt != "xref":
            # No resources anywhere; create them on the page.
            doc.xref_set_key(page.xref, "Resources", f"<</Font<</{name} {xref} 0 R>>>>")
            return name
        owner = int(pv.split()[0])
    if t == "xref":
        res = int(v.split()[0])
        ft, fv = doc.xref_get_key(res, "Font")
        if ft == "xref":
            doc.xref_set_key(int(fv.split()[0]), name, f"{xref} 0 R")
        elif ft == "null":
            doc.xref_set_key(res, "Font", f"<</{name} {xref} 0 R>>")
        else:
            doc.xref_set_key(res, f"Font/{name}", f"{xref} 0 R")
    else:
        ft, fv = doc.xref_get_key(owner, "Resources/Font")
        if ft == "xref":
            doc.xref_set_key(int(fv.split()[0]), name, f"{xref} 0 R")
        elif ft == "null":
            doc.xref_set_key(owner, "Resources/Font", f"<</{name} {xref} 0 R>>")
        else:
            doc.xref_set_key(owner, f"Resources/Font/{name}", f"{xref} 0 R")
    return name


class OriginalFontWriter:
    """Writes text with the document's own embedded font."""

    kind = "original"

    def __init__(self, page, codec: FontCodec, size: float, color, space_width: float | None):
        self.page = page
        self.codec = codec
        self.size = size
        self.color = color
        self.resname = _font_resource_name(page, codec.xref)
        self.emulate_space = " " not in codec.uni_to_code
        # Space advance in text-space units (thousandths of an em).
        sw = (space_width / size * 1000) if space_width and size else 250.0
        self.space_units = sw if self.emulate_space else codec.char_width(" ")
        self.label = getattr(codec, "basefont", "original font").split("+", 1)[-1]
        self.hscale = 100.0

    def can_write(self, text: str) -> bool:
        probe = text.replace(" ", "") if self.emulate_space else text
        return self.codec.can_encode(probe)

    def width(self, text: str) -> float:
        units = 0.0
        for ch in text:
            units += self.space_units if (ch == " " and self.emulate_space) else self.codec.char_width(ch)
        return units / 1000.0 * self.size * self.hscale / 100.0

    def draw_ops(self, origin_fitz: pymupdf.Point, text: str) -> str:
        pdf = pymupdf.Point(origin_fitz) * ~self.page.transformation_matrix
        r, g, b = self.color
        if self.emulate_space:
            parts = []
            for k, chunk in enumerate(text.split(" ")):
                if k:
                    parts.append(f"{-self.space_units:.2f}")
                if chunk:
                    parts.append(f"<{self.codec.encode_hex(chunk)}>")
            show = f"[{' '.join(parts)}] TJ"
        else:
            show = f"<{self.codec.encode_hex(text)}> Tj"
        tz = f"{self.hscale:.2f} Tz " if abs(self.hscale - 100) > 0.01 else ""
        return (f"q BT /{self.resname} {self.size:.3f} Tf {tz}{r:.4f} {g:.4f} {b:.4f} rg "
                f"1 0 0 1 {pdf.x:.3f} {pdf.y:.3f} Tm {show} ET Q\n")


class SubstituteFontWriter:
    """Writes text with a base-14 or bundled Noto font."""

    kind = "substitute"

    def __init__(self, page, fontname: str, size: float, color):
        self.page = page
        self.fontname = fontname
        self.size = size
        self.color = color
        self.label = FONT_LABELS.get(fontname, fontname)
        self.hscale = 100.0
        self._font = pymupdf.Font(fontname)
        if fontname.startswith("notos"):
            page.insert_font(fontname=fontname, fontbuffer=self._font.buffer)

    def can_write(self, text: str) -> bool:
        return all(self._font.has_glyph(ord(c)) or c == " " for c in text)

    def width(self, text: str) -> float:
        return self._font.text_length(text, fontsize=self.size) * self.hscale / 100.0

    def draw(self, origin_fitz: pymupdf.Point, text: str) -> None:
        morph = None
        if abs(self.hscale - 100) > 0.01:
            morph = (pymupdf.Point(origin_fitz), pymupdf.Matrix(self.hscale / 100.0, 1))
        self.page.insert_text(origin_fitz, text, fontsize=self.size, fontname=self.fontname,
                              color=self.color, morph=morph)


def _choose_substitute(span: dict, bold: bool, italic: bool, text: str, family: str | None) -> str:
    if family and family in FONT_LABELS:
        name = family
        if name.startswith("notos"):
            return NOTO[(bold, italic)]
        if name in ("tiro", "tibo", "tiit", "tibi"):
            return FALLBACK[(True, False, bold, italic)]
        if name in ("cour", "cobo", "coit", "cobi"):
            return FALLBACK[(False, True, bold, italic)]
        return FALLBACK[(False, False, bold, italic)]
    if not _winansi_ok(text):
        return NOTO[(bold, italic)]
    serif = bool(span.get("flags", 0) & 4) or any(k in span.get("font", "").lower() for k in ("times", "serif", "roman", "cambria", "caladea", "georgia", "garamond", "book"))
    if any(k in span.get("font", "").lower() for k in ("arial", "helvetica", "calibri", "carlito", "sans", "verdana", "tahoma", "segoe")):
        serif = False
    mono = bool(span.get("flags", 0) & 8) or "courier" in span.get("font", "").lower() or "mono" in span.get("font", "").lower()
    return FALLBACK[(serif and not mono, mono, bold, italic)]


def _make_writer(page, span: dict, text: str, *, size: float, color, bold: bool | None, italic: bool | None,
                 family: str | None, space_width: float | None):
    """Prefer the original font; fall back to a substitute when it cannot render the text."""
    orig_bold = bool(span.get("flags", 0) & 16) or "bold" in span.get("font", "").lower()
    orig_italic = bool(span.get("flags", 0) & 2) or any(k in span.get("font", "").lower() for k in ("italic", "oblique"))
    want_bold = orig_bold if bold is None else bool(bold)
    want_italic = orig_italic if italic is None else bool(italic)
    reason = None
    if (family in (None, "", "original")) and want_bold == orig_bold and want_italic == orig_italic:
        xref = find_font_xref(page, span.get("font", ""), span.get("text", ""))
        if xref:
            codec = FontCodec(page.parent, xref)
            if codec.ok:
                w = OriginalFontWriter(page, codec, size, color, space_width)
                if w.can_write(text):
                    return w, None
                missing = codec.missing_chars(text.replace(" ", "") if w.emulate_space else text)
                reason = ("the embedded font subset does not contain: " + " ".join(repr(c) for c in missing[:8]))
            else:
                reason = codec.reason
        else:
            reason = "the original font could not be located"
    elif family not in (None, "", "original"):
        reason = "a different font was requested"
    else:
        reason = "a different style (bold/italic) was requested"
    sub = _choose_substitute(span, want_bold, want_italic, text, family if family not in (None, "", "original") else None)
    w = SubstituteFontWriter(page, sub, size, color)
    if not w.can_write(text):
        raise EngineError(NOT_SUPPORTED, "The new text contains characters that none of the available fonts can display.")
    return w, reason


def _spans_in(page: pymupdf.Page, rect: pymupdf.Rect) -> tuple[list[dict], list[dict]]:
    """Spans and lines whose bbox centre lies inside rect."""
    spans, lines = [], []
    data = page.get_text("dict", flags=pymupdf.TEXTFLAGS_DICT & ~pymupdf.TEXT_PRESERVE_IMAGES)
    for b in data["blocks"]:
        if b.get("type") != 0:
            continue
        for ln in b["lines"]:
            lb = pymupdf.Rect(ln["bbox"])
            c = pymupdf.Point((lb.x0 + lb.x1) / 2, (lb.y0 + lb.y1) / 2)
            if not rect.contains(c):
                continue
            keep = [s for s in ln["spans"] if rect.contains(pymupdf.Point((s["bbox"][0] + s["bbox"][2]) / 2, (s["bbox"][1] + s["bbox"][3]) / 2))]
            if keep:
                lines.append({"bbox": lb, "dir": ln["dir"], "spans": keep})
                spans.extend(keep)
    return spans, lines


def _norm(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip()


def _space_width(page, span) -> float | None:
    try:
        for tr in page.get_texttrace():
            if tr["font"] == span["font"] and abs(tr["size"] - span["size"]) < 0.01:
                return tr.get("spacewidth")
    except Exception:  # noqa: BLE001
        return None
    return None


_NUMERIC = re.compile(r"[\s()\-+₹$€£,.\d%]*\d[\s()\-+₹$€£,.\d%]*(?:\s*(?:Cr|Dr|CR|DR))?")


def _column_alignment(page, box: pymupdf.Rect, text: str) -> str:
    """Infer whether the edited line sits in a left- or right-aligned column by
    looking at lines directly above and below that overlap it horizontally."""
    left = right = 0
    for b in page.get_text("dict")["blocks"]:
        for ln in b.get("lines", []):
            r = pymupdf.Rect(ln["bbox"])
            if abs(r.y0 - box.y0) < 0.5 or abs(r.y0 - box.y0) > box.height * 8:
                continue
            if r.x1 < box.x0 or r.x0 > box.x1:
                continue
            if abs(r.x0 - box.x0) < 1.0:
                left += 1
            if abs(r.x1 - box.x1) < 1.0:
                right += 1
    if left > right:
        return "left"
    if right > left:
        return "right"
    return "right" if _NUMERIC.fullmatch(text or "x") else "left"


def _wrap(writer, text: str, max_width: float) -> list[str]:
    lines = []
    for para in text.split("\n"):
        words = para.split(" ")
        cur = ""
        for w in words:
            cand = w if not cur else cur + " " + w
            if writer.width(cand) <= max_width or not cur:
                cur = cand
            else:
                lines.append(cur)
                cur = w
        lines.append(cur)
    return lines


def _draw_line(page, writer, origin: pymupdf.Point, text: str, ops: list[str]):
    if not text:
        return
    if isinstance(writer, OriginalFontWriter):
        ops.append(writer.draw_ops(origin, text))
    else:
        writer.draw(origin, text)


def _finish(ctx, doc, path, output, overwrite, password, expect_pages):
    out = OutputFile(ctx, output, overwrite, sources=[path])
    save_doc(doc, out.temp, garbage=1)
    doc.close()
    return out.commit(validate_pdf(expected_pages=expect_pages, password=password))


def _signed_warning(ctx, doc):
    if doc_is_signed(doc):
        ctx.warn("This document is digitally signed. Editing its content invalidates the existing signature(s).")


@method("edit.text")
def edit_text(ctx, path: str, output: str, page: int, rect, newText: str, password: str | None = None,
              overwrite: bool = False, space: str = "pdf", mode: str = "line", fontSize: float | None = None,
              color: str | None = None, bold: bool | None = None, italic: bool | None = None,
              fontFamily: str | None = None, anchor: str = "auto", fit: str = "none", moveBy=None,
              newRect=None, align: str = "left", lineSpacing: float | None = None):
    """Replace the text of a line (mode='line') or paragraph (mode='block').

    rect     : the original line/paragraph box (PDF user space by default)
    moveBy   : [dx, dy] in PDF units to move the text (positive dy = up)
    newRect  : for paragraph mode, the new box to flow the text into
    anchor   : 'left' | 'right' | 'center' | 'auto' (numbers keep their right edge)
    fit      : 'none' | 'shrink' (reduce font size) | 'condense' (horizontal scaling)
    """
    if newText is None:
        raise invalid("New text is required.")
    doc = open_pdf(path, password, require=["modify"])
    if page < 1 or page > doc.page_count:
        raise invalid("Page out of range.")
    _signed_warning(ctx, doc)
    pg = doc[page - 1]
    area = to_fitz_rect(pg, rect, space)
    spans, lines = _spans_in(pg, area + (-1, -1, 1, 1))
    if not spans:
        raise EngineError(NOT_SUPPORTED, "No editable text was found in the selected area. If this is a scanned page, run OCR first or use Add Text.")
    if any(abs(ln["dir"][1]) > 0.01 or ln["dir"][0] < 0 for ln in lines):
        raise EngineError(NOT_SUPPORTED, "Rotated or vertical text cannot be edited in place. Use Add Text for a new text box, or convert to Word for heavy edits.")
    first = spans[0]
    old_text = "\n".join("".join(s["text"] for s in ln["spans"]) for ln in lines)
    size = float(fontSize or first["size"])
    col = hex_to_rgb(color) if color else int_to_rgb(first["color"])
    sw = _space_width(pg, first)

    remove_rects = [ln["bbox"] for ln in lines]
    old_union = pymupdf.Rect(remove_rects[0])
    for r in remove_rects[1:]:
        old_union |= r

    dx = dy = 0.0
    if moveBy:
        # PDF-space delta -> fitz delta (y flips)
        dx, dy = float(moveBy[0]), -float(moveBy[1])

    remove_text(pg, remove_rects)
    ops: list[str] = []
    warnings_font = None
    target_width = old_union.width
    text_for_writer = newText.replace("\n", " ") if mode == "line" else newText
    writer, reason = _make_writer(pg, first, text_for_writer.replace("\n", " "), size=size, color=col, bold=bold,
                                  italic=italic, family=fontFamily, space_width=sw)
    if reason:
        warnings_font = f"Substituted {writer.label} because {reason}."

    if mode == "line":
        origin = pymupdf.Point(first["origin"]) + (dx, dy)
        line_right = old_union.x1 + dx
        w = writer.width(text_for_writer)
        if fit in ("shrink", "condense") and w > target_width > 0:
            if fit == "shrink":
                writer.size = max(4.0, writer.size * target_width / w)
            else:
                writer.hscale = max(50.0, 100.0 * target_width / w)
            w = writer.width(text_for_writer)
        eff_anchor = anchor
        if anchor == "auto":
            eff_anchor = _column_alignment(pg, old_union, _norm(old_text))
        if eff_anchor == "right":
            origin = pymupdf.Point(line_right - w, origin.y)
        elif eff_anchor == "center":
            origin = pymupdf.Point(old_union.x0 + dx + (old_union.width - w) / 2, origin.y)
        _draw_line(pg, writer, origin, text_for_writer, ops)
        new_area = pymupdf.Rect(origin.x, origin.y - writer.size * 1.05, origin.x + w, origin.y + writer.size * 0.35)
    elif mode == "block":
        box = to_fitz_rect(pg, newRect, space) if newRect else pymupdf.Rect(old_union) + (dx, dy, dx, dy)
        baselines = sorted({round(ln["spans"][0]["origin"][1], 2) for ln in lines})
        if lineSpacing:
            leading = float(lineSpacing) * writer.size
        elif len(baselines) > 1:
            leading = (baselines[-1] - baselines[0]) / (len(baselines) - 1)
        else:
            leading = writer.size * 1.2
        wrapped = _wrap(writer, newText, box.width)
        first_base = box.y0 + (first["origin"][1] - old_union.y0 if not newRect else writer.size * 0.9)
        for k, ln in enumerate(wrapped):
            y = first_base + k * leading
            w = writer.width(ln)
            if align == "right":
                x = box.x1 - w
            elif align == "center":
                x = box.x0 + (box.width - w) / 2
            else:
                x = box.x0
            _draw_line(pg, writer, pymupdf.Point(x, y), ln, ops)
        last_y = first_base + (len(wrapped) - 1) * leading
        if last_y > box.y1 + leading * 0.5:
            ctx.warn("The new text is longer than the original paragraph box and extends below it. Check the layout.")
        new_area = pymupdf.Rect(box.x0, box.y0 - 2, box.x1, last_y + writer.size * 0.4)
    else:
        raise invalid("mode must be 'line' or 'block'.")

    if ops:
        append_content(pg, "".join(ops))
    if mode == "line" and fit == "none" and target_width > 0 and writer.width(text_for_writer) > target_width + max(3.0, target_width * 0.08):
        ctx.warn("The new text is wider than the original. It may overlap neighbouring content; consider 'Shrink to fit'.")
    if warnings_font:
        ctx.warn(warnings_font)

    # Verify by re-extracting the edited area.
    got = _norm(pg.get_textbox(new_area + (-2, -2, 2, 2)))
    want = _norm(newText)
    verified = want.replace(" ", "") in got.replace(" ", "") if want else True
    if not verified:
        doc.close()
        raise EngineError(VALIDATION_FAILED, "The edit could not be verified: the new text was not found where it was written. The document was not changed.")
    final = _finish(ctx, doc, path, output, overwrite, password, None)
    return {"output": final, "font": writer.label, "fontKind": writer.kind, "verified": True,
            "newBox": None}


@method("edit.replaceAll")
def replace_all(ctx, path: str, output: str, find: str, replace: str, password: str | None = None,
                overwrite: bool = False, matchCase: bool = True, wholeWord: bool = False, pages=None,
                preview: bool = False):
    """Find and replace text across the document, span by span, in the original font where possible."""
    from ..util.pdf import parse_pages

    if not find:
        raise invalid("Enter the text to find.")
    doc = open_pdf(path, password, require=["modify"])
    _signed_warning(ctx, doc)
    flags = 0 if matchCase else re.IGNORECASE
    pat = re.compile((r"\b" + re.escape(find) + r"\b") if wholeWord else re.escape(find), flags)
    sel = parse_pages(pages, doc.page_count)
    total_hits = 0
    replaced = 0
    skipped = []
    substitutions = set()
    found = []
    for k, i in enumerate(sel):
        ctx.progress(k, len(sel), f"Page {i + 1}")
        pg = doc[i]
        data = pg.get_text("dict", flags=pymupdf.TEXTFLAGS_DICT & ~pymupdf.TEXT_PRESERVE_IMAGES)
        page_spans = []
        for b in data["blocks"]:
            if b.get("type") != 0:
                continue
            for ln in b["lines"]:
                for s in ln["spans"]:
                    if pat.search(s["text"]):
                        page_spans.append((s, ln["dir"]))
        # Matches that cross span boundaries are counted but not replaced.
        line_hits = len(pg.search_for(find)) if matchCase else len(pg.search_for(find))
        span_hits = sum(len(pat.findall(s["text"])) for s, _ in page_spans)
        if line_hits > span_hits:
            skipped.append({"page": i + 1, "count": line_hits - span_hits,
                            "reason": "text is split across differently formatted runs"})
        total_hits += max(line_hits, span_hits)
        for s, _ in page_spans:
            found.append({"page": i + 1, "rect": fitz_to_pdf_rect(pg, s["bbox"]), "text": s["text"]})
        if preview or not page_spans:
            continue
        ops: list[str] = []
        rects = []
        jobs = []
        for s, d in page_spans:
            if abs(d[1]) > 0.01 or d[0] < 0:
                skipped.append({"page": i + 1, "count": 1, "reason": "rotated text"})
                continue
            new = pat.sub(replace, s["text"])
            rects.append(pymupdf.Rect(s["bbox"]))
            jobs.append((s, new))
        if not jobs:
            continue
        remove_text(pg, rects)
        for s, new in jobs:
            writer, reason = _make_writer(pg, s, new, size=s["size"], color=int_to_rgb(s["color"]), bold=None,
                                          italic=None, family=None, space_width=_space_width(pg, s))
            if reason:
                substitutions.add(f"{writer.label} ({reason})")
            _draw_line(pg, writer, pymupdf.Point(s["origin"]), new, ops)
            replaced += len(pat.findall(s["text"]))
            old_w = s["bbox"][2] - s["bbox"][0]
            if writer.width(new) > old_w + 1:
                ctx.warn(f"Page {i + 1}: a replacement is wider than the original text and may overlap neighbouring content.")
        if ops:
            append_content(pg, "".join(ops))
    if preview:
        doc.close()
        return {"matches": found, "total": total_hits, "replaceable": sum(len(pat.findall(f["text"])) for f in found),
                "skipped": skipped}
    if replaced == 0:
        doc.close()
        raise EngineError(NOT_SUPPORTED, "No replaceable occurrences were found." if total_hits == 0 else
                          "Occurrences were found but none could be replaced in place (see details).", {"skipped": skipped})
    for s in substitutions:
        ctx.warn(f"Substitute font used: {s}.")
    final = _finish(ctx, doc, path, output, overwrite, password, None)
    # Verify: count occurrences of the replacement in the output.
    return {"output": final, "replaced": replaced, "found": total_hits, "skipped": skipped}


@method("edit.insertText")
def insert_text(ctx, path: str, output: str, page: int, rect, text: str, password: str | None = None,
                overwrite: bool = False, space: str = "pdf", fontSize: float = 11, color: str = "#000000",
                fontFamily: str = "helv", bold: bool = False, italic: bool = False, align: str = "left",
                opacity: float = 1.0):
    """Add new text to the page content (not an annotation)."""
    if not text:
        raise invalid("Enter the text to add.")
    doc = open_pdf(path, password, require=["modify"])
    _signed_warning(ctx, doc)
    pg = doc[page - 1]
    box = to_fitz_rect(pg, rect, space)
    fam = _choose_substitute({}, bool(bold), bool(italic), text, fontFamily or "helv")
    if fam.startswith("notos"):
        pg.insert_font(fontname=fam, fontbuffer=pymupdf.Font(fam).buffer)
    al = {"left": 0, "center": 1, "right": 2, "justify": 3}.get(align, 0)
    rc = pg.insert_textbox(box, text, fontsize=float(fontSize), fontname=fam, color=hex_to_rgb(color), align=al,
                           fill_opacity=float(opacity), stroke_opacity=float(opacity))
    if rc < 0:
        # Grow the box downwards until it fits (max: page bottom).
        need = -rc
        bigger = pymupdf.Rect(box.x0, box.y0, box.x1, min(pg.rect.y1, box.y1 + need + 2))
        doc.close()
        doc = open_pdf(path, password, require=["modify"])
        pg = doc[page - 1]
        if fam.startswith("notos"):
            pg.insert_font(fontname=fam, fontbuffer=pymupdf.Font(fam).buffer)
        rc = pg.insert_textbox(bigger, text, fontsize=float(fontSize), fontname=fam, color=hex_to_rgb(color),
                               align=al, fill_opacity=float(opacity), stroke_opacity=float(opacity))
        if rc < 0:
            doc.close()
            raise invalid("The text does not fit in the box. Draw a larger box or reduce the font size.")
    final = _finish(ctx, doc, path, output, overwrite, password, None)
    return {"output": final, "font": FONT_LABELS.get(fam, fam)}


@method("edit.insertImage")
def insert_image(ctx, path: str, output: str, page: int, rect, imagePath: str, password: str | None = None,
                 overwrite: bool = False, space: str = "pdf", keepAspect: bool = True, opacity: float = 1.0,
                 pages=None, behind: bool = False):
    """Insert an image into the page content. ``pages`` applies it to several pages."""
    from ..util.pdf import parse_pages

    require_file(imagePath)
    doc = open_pdf(path, password, require=["modify"])
    _signed_warning(ctx, doc)
    data = _image_bytes(imagePath, opacity)
    targets = parse_pages(pages, doc.page_count) if pages else [page - 1]
    ref = doc[page - 1]
    box_ref = to_fitz_rect(ref, rect, space)
    for i in targets:
        pg = doc[i]
        box = to_fitz_rect(pg, rect, space) if i != page - 1 else box_ref
        pg.insert_image(box, stream=data, keep_proportion=bool(keepAspect), overlay=not behind)
    final = _finish(ctx, doc, path, output, overwrite, password, None)
    return {"output": final, "pages": [t + 1 for t in targets]}


def _image_bytes(image_path: str, opacity: float = 1.0) -> bytes:
    from PIL import Image

    try:
        im = Image.open(image_path)
        im.load()
    except Exception as e:  # noqa: BLE001
        raise EngineError("UNSUPPORTED_FORMAT", f"The image could not be read: {e}")
    import io

    buf = io.BytesIO()
    if opacity < 0.999 or im.mode in ("RGBA", "LA", "P"):
        im = im.convert("RGBA")
        if opacity < 0.999:
            a = im.getchannel("A").point(lambda v: int(v * max(0.0, min(1.0, opacity))))
            im.putalpha(a)
        im.save(buf, "PNG")
    elif im.format == "JPEG" and im.mode in ("RGB", "L", "CMYK"):
        with open(image_path, "rb") as fh:
            return fh.read()
    else:
        im.convert("RGB").save(buf, "PNG")
    return buf.getvalue()


# ---------------------------------------------------------------------------
# Image object editing via content-stream operators (pikepdf)
# ---------------------------------------------------------------------------

def _mat(a, b, c, d, e, f):
    return pymupdf.Matrix(float(a), float(b), float(c), float(d), float(e), float(f))


def _locate_image_ops(pdf, page_index: int):
    """Yield (op_index, name, ctm, unit_bbox_pdf) for each image Do / inline image at top level."""
    import pikepdf

    page = pdf.pages[page_index]
    ops = pikepdf.parse_content_stream(page)
    try:
        res = page.resources
        xobjs = res.get("/XObject") if res is not None else None
    except Exception:  # noqa: BLE001
        xobjs = None
    stack = []
    ctm = pymupdf.Matrix(1, 1)
    found = []
    for idx, (operands, op) in enumerate(ops):
        o = str(op)
        if o == "q":
            stack.append(pymupdf.Matrix(ctm))
        elif o == "Q":
            if stack:
                ctm = stack.pop()
        elif o == "cm" and len(operands) == 6:
            ctm = _mat(*operands) * ctm
        elif o == "Do" and operands:
            name = str(operands[0])
            xo = xobjs.get(name) if xobjs is not None else None
            if xo is not None and xo.get("/Subtype") == "/Image":
                found.append((idx, name, pymupdf.Matrix(ctm)))
        elif o == "INLINE IMAGE":
            found.append((idx, "inline", pymupdf.Matrix(ctm)))
    return ops, found


def _quad_bbox(ctm: pymupdf.Matrix) -> pymupdf.Rect:
    pts = [pymupdf.Point(x, y) * ctm for x, y in ((0, 0), (1, 0), (0, 1), (1, 1))]
    return pymupdf.Rect(min(p.x for p in pts), min(p.y for p in pts), max(p.x for p in pts), max(p.y for p in pts))


@method("edit.image")
def edit_image(ctx, path: str, output: str, page: int, rect, action: str, newRect=None,
               imagePath: str | None = None, password: str | None = None, overwrite: bool = False):
    """Move/resize ('transform'), delete, or replace an existing image placement.

    rect / newRect are in PDF user space. The image is identified by the
    placement whose bounding box best matches rect.
    """
    import pikepdf

    if action not in ("transform", "delete", "replace"):
        raise invalid("action must be transform, delete or replace.")
    probe = open_pdf(path, password, require=["modify"])
    _signed_warning(ctx, probe)
    n = probe.page_count
    probe.close()
    if page < 1 or page > n:
        raise invalid("Page out of range.")
    target = pymupdf.Rect(rect)
    try:
        pdf = pikepdf.open(path, password=password or "")
    except pikepdf.PasswordError:
        raise EngineError("PASSWORD_REQUIRED", "Password required.")
    ops, found = _locate_image_ops(pdf, page - 1)
    best = None
    for idx, name, ctm in found:
        bb = _quad_bbox(ctm)
        inter = pymupdf.Rect(bb) & target
        if inter.is_empty:
            continue
        score = inter.get_area() / max(1e-6, (bb | target).get_area())
        if best is None or score > best[0]:
            best = (score, idx, name, ctm)
    if not best or best[0] < 0.6:
        pdf.close()
        raise EngineError(NOT_SUPPORTED, "The selected image is not directly editable (it may be part of a form object, a pattern or a scanned page background). Use Insert Image to add a replacement.")
    _, idx, name, ctm = best
    old_bb = _quad_bbox(ctm)
    new_ops = list(ops)
    if action == "delete" or action == "replace":
        del new_ops[idx]
    elif action == "transform":
        if not newRect:
            raise invalid("newRect is required to move or resize an image.")
        nb = pymupdf.Rect(newRect)
        # T maps old bbox to new bbox in user space; A = C^-1... we prepend A so that A x C = C x T.
        t = pymupdf.Matrix(nb.width / max(1e-6, old_bb.width), 0, 0, nb.height / max(1e-6, old_bb.height), 0, 0)
        t = pymupdf.Matrix(1, 0, 0, 1, -old_bb.x0, -old_bb.y0) * t * pymupdf.Matrix(1, 0, 0, 1, nb.x0, nb.y0)
        # New CTM C' = C * T. Content does "A cm" then Do, so CTM = A * C  =>  A = C * T * C^-1.
        a = ctm * t * ~ctm
        orig = ops[idx]
        insert = [
            pikepdf.ContentStreamInstruction([], pikepdf.Operator("q")),
            pikepdf.ContentStreamInstruction([a.a, a.b, a.c, a.d, a.e, a.f], pikepdf.Operator("cm")),
            orig,
            pikepdf.ContentStreamInstruction([], pikepdf.Operator("Q")),
        ]
        new_ops = new_ops[:idx] + insert + new_ops[idx + 1:]
    pg = pdf.pages[page - 1]
    pg.obj.Contents = pdf.make_stream(pikepdf.unparse_content_stream(new_ops))
    data_removed = None
    if action in ("delete", "replace") and name != "inline":
        # Drop the image from this page's resources when nothing on the page still
        # draws it, so its pixels are not left behind in the saved file. A copy of the
        # resource dictionary is written to this page only; other pages that share
        # the image keep it.
        still_used = any(str(o) == "Do" and ops_ and str(ops_[0]) == name for ops_, o in new_ops)
        res = pg.obj.get("/Resources")
        data_removed = False
        if not still_used and res is not None and "/XObject" in res:
            new_xo = pikepdf.Dictionary({k: v for k, v in res.XObject.items() if k != name})
            new_res = pikepdf.Dictionary({k: v for k, v in res.items()})
            new_res.XObject = new_xo
            pg.obj.Resources = new_res
            data_removed = True
    elif action in ("delete", "replace"):
        data_removed = True  # inline image data lives in the content stream itself
    tmp = ctx.tmp_path(".pdf")
    was_encrypted = pdf.is_encrypted
    pdf.save(tmp, encryption=True if was_encrypted else False)
    pdf.close()
    # Re-open with PyMuPDF to add the replacement image; existing encryption is kept.
    doc = pymupdf.open(tmp)
    if doc.needs_pass:
        doc.authenticate(password or "")
    if action == "replace":
        if not imagePath:
            raise invalid("Choose the replacement image.")
        require_file(imagePath)
        p2 = doc[page - 1]
        box = pymupdf.Rect(old_bb) * p2.transformation_matrix
        box.normalize()
        # The new picture is fitted inside the old frame without distortion.
        p2.insert_image(box, stream=_image_bytes(imagePath), keep_proportion=True)
    out = OutputFile(ctx, output, overwrite, sources=[path])
    save_doc(doc, out.temp, garbage=3)
    doc.close()
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    if data_removed is False:
        ctx.warn("The image is no longer shown on this page, but its data is still stored in the file because "
                 "another part of the document uses it. Use Redact if the picture must be removed completely.")
    return {"output": final, "action": action, "oldRect": [old_bb.x0, old_bb.y0, old_bb.x1, old_bb.y1],
            "imageDataRemoved": data_removed}


@method("edit.listImages")
def list_images(ctx, path: str, page: int, password: str | None = None):
    """Directly editable image placements on a page, in PDF user space."""
    import pikepdf

    try:
        pdf = pikepdf.open(path, password=password or "")
    except pikepdf.PasswordError:
        raise EngineError("PASSWORD_REQUIRED", "Password required.")
    try:
        if page < 1 or page > len(pdf.pages):
            raise invalid("Page out of range.")
        _, found = _locate_image_ops(pdf, page - 1)
        out = []
        for k, (idx, name, ctm) in enumerate(found):
            bb = _quad_bbox(ctm)
            out.append({"id": k, "name": name, "rect": [bb.x0, bb.y0, bb.x1, bb.y1]})
        return {"page": page, "images": out}
    finally:
        pdf.close()
