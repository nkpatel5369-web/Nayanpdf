"""Annotations and review markup.

Annotations are real PDF annotation objects (editable/deletable in NayanPDF,
Acrobat and other viewers). Flattening burns them into the page content and
removes their independent editability; the UI warns before doing so.
"""

from __future__ import annotations

import datetime as _dt
import io

import pymupdf

from ..errors import EngineError, invalid
from ..registry import method
from ..util.geometry import fitz_to_pdf_rect, to_fitz_point, to_fitz_rect
from ..util.io import OutputFile, require_file, validate_pdf
from ..util.pdf import doc_is_signed, hex_to_rgb, open_pdf, parse_pages, rgb_to_hex, save_doc

TYPE_NAMES = {
    pymupdf.PDF_ANNOT_TEXT: "note", pymupdf.PDF_ANNOT_FREE_TEXT: "freetext", pymupdf.PDF_ANNOT_LINE: "line",
    pymupdf.PDF_ANNOT_SQUARE: "rect", pymupdf.PDF_ANNOT_CIRCLE: "circle", pymupdf.PDF_ANNOT_POLYGON: "polygon",
    pymupdf.PDF_ANNOT_POLY_LINE: "polyline", pymupdf.PDF_ANNOT_HIGHLIGHT: "highlight",
    pymupdf.PDF_ANNOT_UNDERLINE: "underline", pymupdf.PDF_ANNOT_SQUIGGLY: "squiggly",
    pymupdf.PDF_ANNOT_STRIKE_OUT: "strikeout", pymupdf.PDF_ANNOT_STAMP: "stamp", pymupdf.PDF_ANNOT_INK: "ink",
    pymupdf.PDF_ANNOT_FILE_ATTACHMENT: "attachment", pymupdf.PDF_ANNOT_REDACT: "redact",
    pymupdf.PDF_ANNOT_LINK: "link", pymupdf.PDF_ANNOT_WIDGET: "widget", pymupdf.PDF_ANNOT_CARET: "caret",
    pymupdf.PDF_ANNOT_POPUP: "popup",
}
STANDARD_STAMPS = {
    "Approved": pymupdf.STAMP_Approved, "AsIs": pymupdf.STAMP_AsIs, "Confidential": pymupdf.STAMP_Confidential,
    "Departmental": pymupdf.STAMP_Departmental, "Draft": pymupdf.STAMP_Draft,
    "Experimental": pymupdf.STAMP_Experimental, "Expired": pymupdf.STAMP_Expired, "Final": pymupdf.STAMP_Final,
    "ForComment": pymupdf.STAMP_ForComment, "ForPublicRelease": pymupdf.STAMP_ForPublicRelease,
    "NotApproved": pymupdf.STAMP_NotApproved, "NotForPublicRelease": pymupdf.STAMP_NotForPublicRelease,
    "Sold": pymupdf.STAMP_Sold, "TopSecret": pymupdf.STAMP_TopSecret,
}
LINE_ENDS = {"none": pymupdf.PDF_ANNOT_LE_NONE, "arrow": pymupdf.PDF_ANNOT_LE_OPEN_ARROW,
             "closedArrow": pymupdf.PDF_ANNOT_LE_CLOSED_ARROW, "circle": pymupdf.PDF_ANNOT_LE_CIRCLE,
             "square": pymupdf.PDF_ANNOT_LE_SQUARE, "diamond": pymupdf.PDF_ANNOT_LE_DIAMOND,
             "butt": pymupdf.PDF_ANNOT_LE_BUTT, "slash": pymupdf.PDF_ANNOT_LE_SLASH}


def _now_pdf() -> str:
    return pymupdf.get_pdf_now()


def _annot_dict(page: pymupdf.Page, a: pymupdf.Annot) -> dict:
    info = a.info
    colors = a.colors or {}
    d = {
        "id": a.xref,
        "page": page.number + 1,
        "type": TYPE_NAMES.get(a.type[0], a.type[1].lower()),
        "typeName": a.type[1],
        "rect": fitz_to_pdf_rect(page, a.rect),
        "stroke": rgb_to_hex(colors.get("stroke")) if colors.get("stroke") else None,
        "fill": rgb_to_hex(colors.get("fill")) if colors.get("fill") else None,
        "opacity": a.opacity if a.opacity is not None and a.opacity >= 0 else 1.0,
        "width": (a.border or {}).get("width", 1),
        "contents": info.get("content", ""),
        "author": info.get("title", ""),
        "subject": info.get("subject", ""),
        "created": info.get("creationDate", ""),
        "modified": info.get("modDate", ""),
        "name": info.get("name", ""),
    }
    return d


@method("annot.list")
def list_annots(ctx, path: str, password: str | None = None, page: int | None = None):
    doc = open_pdf(path, password)
    try:
        out = []
        pages = [page - 1] if page else range(doc.page_count)
        for i in pages:
            p = doc[i]
            for a in p.annots():
                if a.type[0] in (pymupdf.PDF_ANNOT_POPUP, pymupdf.PDF_ANNOT_LINK, pymupdf.PDF_ANNOT_WIDGET):
                    continue
                out.append(_annot_dict(p, a))
        return {"annotations": out}
    finally:
        doc.close()


def _words_quads(page: pymupdf.Page, area: pymupdf.Rect) -> list[pymupdf.Quad]:
    """Snap a dragged area to whole words, line by line, like a text selection."""
    words = page.get_text("words", sort=True)
    hits = [w for w in words if pymupdf.Rect(w[:4]).intersects(area)
            and (pymupdf.Rect(w[:4]) & area).get_area() >= 0.25 * pymupdf.Rect(w[:4]).get_area()]
    lines: dict[tuple, pymupdf.Rect] = {}
    for w in hits:
        key = (w[5], w[6])  # block, line
        r = pymupdf.Rect(w[:4])
        lines[key] = lines[key] | r if key in lines else r
    return [r.quad for _, r in sorted(lines.items())]


def _stamp_image(text: str, color: str, date_text: str | None, border: bool = True) -> bytes:
    from PIL import Image, ImageDraw, ImageFont

    rgb = tuple(int(c * 255) for c in hex_to_rgb(color))
    try:
        buf = pymupdf.Font("notosbo").buffer
        big = ImageFont.truetype(io.BytesIO(buf), 96)
        small = ImageFont.truetype(io.BytesIO(buf), 40)
    except Exception:  # noqa: BLE001
        big = ImageFont.load_default(size=96)
        small = ImageFont.load_default(size=40)
    tmp = Image.new("RGBA", (10, 10))
    dr = ImageDraw.Draw(tmp)
    tb = dr.textbbox((0, 0), text, font=big)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    db = dr.textbbox((0, 0), date_text, font=small) if date_text else (0, 0, 0, 0)
    dw, dh = db[2] - db[0], db[3] - db[1]
    pad = 36
    w = max(tw, dw) + pad * 2
    h = th + (dh + 18 if date_text else 0) + pad * 2
    im = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    d = ImageDraw.Draw(im)
    if border:
        d.rounded_rectangle((6, 6, w - 6, h - 6), radius=24, outline=rgb + (235,), width=10)
    d.text(((w - tw) / 2 - tb[0], pad - tb[1]), text, font=big, fill=rgb + (235,))
    if date_text:
        d.text(((w - dw) / 2 - db[0], pad + th + 18 - db[1]), date_text, font=small, fill=rgb + (235,))
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return buf.getvalue()


def _apply_style(a: pymupdf.Annot, op: dict, author: str | None):
    stroke = op.get("color") or op.get("stroke")
    fill = op.get("fill")
    colors = {}
    if stroke:
        colors["stroke"] = hex_to_rgb(stroke)
    if fill:
        colors["fill"] = hex_to_rgb(fill)
    if colors:
        try:
            a.set_colors(**colors)
        except Exception:  # noqa: BLE001
            pass
    if op.get("width") is not None:
        try:
            a.set_border(width=float(op["width"]), dashes=op.get("dashes"))
        except Exception:  # noqa: BLE001
            pass
    if op.get("opacity") is not None:
        a.set_opacity(max(0.05, min(1.0, float(op["opacity"]))))
    info = {}
    if op.get("contents") is not None:
        info["content"] = op["contents"]
    if author:
        info["title"] = author
    if op.get("subject"):
        info["subject"] = op["subject"]
    if info:
        a.set_info(**info)
    a.update()


def _add(page: pymupdf.Page, op: dict, author: str | None, space: str) -> pymupdf.Annot:
    kind = op.get("kind")
    rect = to_fitz_rect(page, op["rect"], space) if op.get("rect") is not None else None
    if kind in ("highlight", "underline", "strikeout", "squiggly"):
        quads = None
        if op.get("quads"):
            quads = [to_fitz_rect(page, q, space).quad for q in op["quads"]]
        elif rect is not None:
            quads = _words_quads(page, rect)
        if not quads:
            raise EngineError("NOT_SUPPORTED", "No text found under the selection. Scanned pages need OCR before text can be highlighted; use a rectangle instead.")
        fn = {"highlight": page.add_highlight_annot, "underline": page.add_underline_annot,
              "strikeout": page.add_strikeout_annot, "squiggly": page.add_squiggly_annot}[kind]
        a = fn(quads)
        if not op.get("color"):
            op = dict(op, color={"highlight": "#ffe066", "underline": "#1c7ed6", "strikeout": "#e03131",
                                 "squiggly": "#e8590c"}[kind])
    elif kind == "note":
        pt = to_fitz_point(page, op.get("point") or [op["rect"][0], op["rect"][3]], space)
        a = page.add_text_annot(pt, op.get("contents", ""), icon=op.get("icon", "Note"))
        if not op.get("color"):
            op = dict(op, color="#fab005")
    elif kind in ("freetext", "callout", "initials", "textstamp"):
        if rect is None:
            raise invalid("A box is required for a text comment.")
        text = op.get("text") or op.get("contents") or ""
        if kind == "initials":
            date = op.get("date") or _dt.date.today().strftime("%d/%m/%Y")
            text = f"{op.get('initials', '').strip()}  {date}".strip()
        callout = None
        if kind == "callout" and op.get("calloutPoints"):
            pts = [to_fitz_point(page, p, space) for p in op["calloutPoints"]]
            callout = pts[:3]
        border_color = hex_to_rgb(op["borderColor"]) if op.get("borderColor") else (
            hex_to_rgb(op.get("color")) if kind in ("callout", "initials") and op.get("color") else
            ((0.79, 0.16, 0.16) if kind in ("callout", "initials") else None))
        rich = border_color is not None
        a = page.add_freetext_annot(
            rect, text, fontsize=float(op.get("fontSize", 11)), fontname=op.get("fontName", "helv"),
            text_color=hex_to_rgb(op.get("textColor") or op.get("color") or "#000000"),
            fill_color=hex_to_rgb(op["fill"]) if op.get("fill") else None,
            border_color=border_color,
            border_width=float(op.get("borderWidth", 1 if border_color is not None else 0)),
            callout=callout, line_end=LINE_ENDS.get(op.get("lineEnd", "arrow"), pymupdf.PDF_ANNOT_LE_OPEN_ARROW),
            opacity=float(op.get("opacity", 1)), align={"left": 0, "center": 1, "right": 2}.get(op.get("align", "left"), 0),
            richtext=rich,
        )
        info = {"content": text}
        if author:
            info["title"] = author
        a.set_info(**info)
        a.update()
        return a
    elif kind in ("line", "arrow"):
        p1 = to_fitz_point(page, op["p1"], space)
        p2 = to_fitz_point(page, op["p2"], space)
        a = page.add_line_annot(p1, p2)
        if kind == "arrow":
            a.set_line_ends(pymupdf.PDF_ANNOT_LE_NONE, LINE_ENDS.get(op.get("lineEnd", "closedArrow"), pymupdf.PDF_ANNOT_LE_CLOSED_ARROW))
        if not op.get("color"):
            op = dict(op, color="#e03131")
    elif kind == "rect":
        a = page.add_rect_annot(rect)
        if not op.get("color"):
            op = dict(op, color="#e03131")
    elif kind == "circle":
        a = page.add_circle_annot(rect)
        if not op.get("color"):
            op = dict(op, color="#e03131")
    elif kind == "ink":
        strokes = [[tuple(to_fitz_point(page, p, space)) for p in s] for s in op.get("strokes", []) if len(s) > 1]
        if not strokes:
            raise invalid("The drawing is empty.")
        a = page.add_ink_annot(strokes)
        if not op.get("color"):
            op = dict(op, color="#1c7ed6")
    elif kind == "stamp":
        if rect is None:
            raise invalid("A box is required for a stamp.")
        if op.get("imagePath"):
            require_file(op["imagePath"])
            a = page.add_stamp_annot(rect, stamp=open(op["imagePath"], "rb").read())
        elif op.get("stamp") in STANDARD_STAMPS:
            a = page.add_stamp_annot(rect, stamp=STANDARD_STAMPS[op["stamp"]])
        else:
            label = (op.get("text") or op.get("stamp") or "APPROVED").upper()
            date_text = None
            if op.get("withDate"):
                date_text = op.get("date") or _dt.date.today().strftime("%d %b %Y")
            img = _stamp_image(label, op.get("color") or "#c92a2a", date_text)
            a = page.add_stamp_annot(rect, stamp=img)
            a.set_info(content=label + (f" {date_text}" if date_text else ""))
        if author:
            a.set_info(title=author)
        if op.get("opacity") is not None:
            a.set_opacity(float(op["opacity"]))
        a.update()
        return a
    else:
        raise invalid(f"Unknown annotation type: {kind}")
    _apply_style(a, op, author)
    return a


@method("annot.apply")
def apply(ctx, path: str, output: str, ops: list, password: str | None = None, overwrite: bool = False,
          author: str | None = None, space: str = "pdf", incremental: bool = False):
    """Apply a list of annotation operations: add / update / delete.

    add:    {"op": "add", "page": 1, "kind": "highlight", "rect": [...], "color": "#ffe066", ...}
    update: {"op": "update", "page": 1, "id": <xref>, "contents": "...", "color": "...", "rect": [...]}
    delete: {"op": "delete", "page": 1, "id": <xref>}
    """
    if not ops:
        raise invalid("No annotation changes to apply.")
    doc = open_pdf(path, password, require=["annotate"])
    signed = doc_is_signed(doc)
    created = []
    try:
        for op in ops:
            pno = int(op.get("page", 0))
            if pno < 1 or pno > doc.page_count:
                raise invalid("Page out of range.")
            page = doc[pno - 1]
            kind = op.get("op", "add")
            if kind == "add":
                a = _add(page, op, author, space)
                created.append({"page": pno, "id": a.xref})
            elif kind in ("update", "delete"):
                a = page.load_annot(int(op["id"]))
                if a is None:
                    raise invalid("The annotation no longer exists.")
                if kind == "delete":
                    page.delete_annot(a)
                else:
                    if op.get("rect") is not None:
                        a.set_rect(to_fitz_rect(page, op["rect"], space))
                    _apply_style(a, op, None)
                    a.set_info(modDate=_now_pdf())
                    a.update()
            else:
                raise invalid(f"Unknown annotation operation: {kind}")
        out = OutputFile(ctx, output, overwrite, sources=[path])
        if signed:
            ctx.warn("This document is digitally signed. Adding comments after signing may be reported as a change by signature validators.")
        save_doc(doc, out.temp, garbage=1)
    finally:
        doc.close()
    final = out.commit(validate_pdf(password=password))
    return {"output": final, "created": created}


@method("annot.flatten")
def flatten(ctx, path: str, output: str, password: str | None = None, overwrite: bool = False,
            annotations: bool = True, forms: bool = False):
    """Burn annotations (and optionally form fields) into the page content."""
    doc = open_pdf(path, password, require=["modify"])
    try:
        if doc_is_signed(doc):
            ctx.warn("This document is digitally signed. Flattening rewrites the content and invalidates the signature(s).")
        n = doc.page_count
        doc.bake(annots=bool(annotations), widgets=bool(forms))
        out = OutputFile(ctx, output, overwrite, sources=[path])
        save_doc(doc, out.temp, garbage=3)
    finally:
        doc.close()
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    # Verify nothing flattenable remains.
    chk = pymupdf.open(final)
    if chk.needs_pass and password:
        chk.authenticate(password)
    remaining = 0
    for p in chk:
        for a in p.annots():
            if a.type[0] not in (pymupdf.PDF_ANNOT_LINK, pymupdf.PDF_ANNOT_POPUP):
                remaining += 1
    chk.close()
    return {"output": final, "remainingAnnotations": remaining}
