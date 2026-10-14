"""Permanent redaction.

Redaction removes content, it does not cover it:
  * text glyphs under each area are deleted from the content stream (visible
    and invisible text such as OCR layers);
  * image pixels under each area are blanked inside the image data;
  * vector graphics under the area are removed;
  * annotations and form fields that overlap an area are deleted;
  * the file is rewritten without incremental history (old objects dropped);
  * optional sanitisation removes metadata, XMP, attachments, JavaScript and
    thumbnails.
Afterwards the output is reopened and verified: the redacted strings must
not be found by text extraction, by search, in annotations, bookmarks,
metadata or in any decompressed stream, and each area is rendered to check
it is visibly covered.
"""

from __future__ import annotations

import re

import pymupdf

from ..errors import EngineError, VALIDATION_FAILED, invalid
from ..registry import method
from ..util.geometry import fitz_to_pdf_rect, to_fitz_rect
from ..util.io import OutputFile, validate_pdf
from ..util.pdf import doc_is_signed, hex_to_rgb, open_pdf, parse_pages

PATTERNS = {
    # Also matches a PAN embedded in a GSTIN (27ABCDE1234F1Z5), which discloses it too.
    "pan": (r"(?<![A-Z])[A-Z]{5}[0-9]{4}[A-Z](?![A-Z])", "PAN (India)"),
    "gstin": (r"\b\d{2}[A-Z]{5}\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\b", "GSTIN (India)"),
    "aadhaar": (r"\b\d{4}[ -]?\d{4}[ -]?\d{4}\b", "Aadhaar-like 12-digit number"),
    "ifsc": (r"\b[A-Z]{4}0[A-Z0-9]{6}\b", "IFSC code"),
    "email": (r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b", "Email address"),
    "phone": (r"(?<!\d)(?:\+?91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}(?!\d)", "Indian mobile number"),
    "account": (r"(?<![\d.,])\d{9,18}(?![\d.,])", "Bank account-like number (9-18 digits)"),
    "card": (r"\b(?:\d{4}[ -]?){3}\d{4}\b", "Card-like 16-digit number"),
    "date": (r"\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\b", "Dates (dd/mm/yyyy)"),
}


def _page_chars(page: pymupdf.Page, join_wraps: bool = False):
    """Page text with per-character boxes.

    join_wraps=False: lines separated by newlines.
    join_wraps=True : lines inside a block are concatenated without a separator
                      when the line does not end in whitespace, so identifiers
                      wrapped inside narrow table cells are still matched.
    """
    chars: list[tuple[str, pymupdf.Rect]] = []
    raw = page.get_text("rawdict", flags=pymupdf.TEXTFLAGS_RAWDICT & ~pymupdf.TEXT_PRESERVE_IMAGES)
    for b in raw["blocks"]:
        lines = b.get("lines", [])
        for k, ln in enumerate(lines):
            line_chars = [(c["c"], pymupdf.Rect(c["bbox"])) for s in ln["spans"] for c in s["chars"]]
            chars.extend(line_chars)
            last = line_chars[-1][0] if line_chars else " "
            if join_wraps and k + 1 < len(lines) and not last.isspace():
                continue
            chars.append(("\n", pymupdf.Rect()))
        chars.append(("\n", pymupdf.Rect()))
    text = "".join(c for c, _ in chars)
    return text, chars


def _match_rects(chars, start: int, end: int) -> list[pymupdf.Rect]:
    rects: list[pymupdf.Rect] = []
    cur = None
    for c, r in chars[start:end]:
        if c == "\n" or r.is_empty:
            if cur is not None:
                rects.append(cur)
                cur = None
            continue
        cur = r if cur is None else cur | r
    if cur is not None:
        rects.append(cur)
    return rects


@method("redact.search")
def search(ctx, path: str, terms: list | None = None, patterns: list | None = None, customRegex: str | None = None,
           matchCase: bool = False, wholeWord: bool = False, pages=None, password: str | None = None):
    """Find redaction candidates. Returns areas in PDF user space for preview."""
    doc = open_pdf(path, password)
    try:
        regs = []
        flags = 0 if matchCase else re.IGNORECASE
        for t in terms or []:
            t = str(t).strip()
            if not t:
                continue
            esc = re.escape(t).replace(r"\ ", r"\s+")
            regs.append((re.compile(rf"\b{esc}\b" if wholeWord else esc, flags), t))
        for p in patterns or []:
            if p not in PATTERNS:
                raise invalid(f"Unknown pattern: {p}")
            regs.append((re.compile(PATTERNS[p][0]), PATTERNS[p][1]))
        if customRegex:
            try:
                regs.append((re.compile(customRegex, flags), "custom pattern"))
            except re.error as e:
                raise invalid(f"Invalid regular expression: {e}")
        if not regs:
            raise invalid("Enter text to find or choose a pattern.")
        hits = []
        sel = parse_pages(pages, doc.page_count)
        for k, i in enumerate(sel):
            if k % 10 == 0:
                ctx.progress(k, len(sel))
            page = doc[i]
            seen = set()
            for join in (False, True):
                text, chars = _page_chars(page, join_wraps=join)
                for rx, label in regs:
                    for m in rx.finditer(text):
                        for r in _match_rects(chars, m.start(), m.end()):
                            key = (round(r.x0, 1), round(r.y0, 1), round(r.x1, 1), round(r.y1, 1))
                            if key in seen:
                                continue
                            seen.add(key)
                            hits.append({"page": i + 1, "rect": fitz_to_pdf_rect(page, r + (-0.5, -0.5, 0.5, 0.5)),
                                         "text": m.group(0), "label": label})
        no_text_pages = [i + 1 for i in sel if not doc[i].get_text("text").strip()]
        return {"hits": hits, "count": len(hits), "pagesWithoutText": no_text_pages[:200]}
    finally:
        doc.close()


@method("redact.patterns")
def list_patterns(ctx):
    return {"patterns": [{"id": k, "label": v[1]} for k, v in PATTERNS.items()]}


def _norm(s: str) -> str:
    return re.sub(r"\s+", "", s).lower()


def _verify(path: str, password: str | None, secrets: list[str], areas_by_page: dict, fill) -> dict:
    """Independent post-check on the written file."""
    doc = pymupdf.open(path)
    if doc.needs_pass:
        doc.authenticate(password or "")
    report = {"textFound": [], "annotationsFound": [], "metadataFound": [], "bookmarksFound": [],
              "streamsFound": 0, "areasNotCovered": []}
    secrets_n = [_norm(s) for s in secrets if _norm(s)]
    try:
        for p in doc:
            t = _norm(p.get_text("text"))
            for s, sn in zip(secrets, secrets_n):
                if sn in t:
                    report["textFound"].append({"page": p.number + 1, "text": s})
            for a in p.annots():
                c = _norm((a.info or {}).get("content", ""))
                for s, sn in zip(secrets, secrets_n):
                    if sn in c:
                        report["annotationsFound"].append({"page": p.number + 1, "text": s})
        meta = _norm(" ".join(str(v) for v in (doc.metadata or {}).values() if v) + (doc.get_xml_metadata() or ""))
        for s, sn in zip(secrets, secrets_n):
            if sn in meta:
                report["metadataFound"].append(s)
        toc = _norm(" ".join(t[1] for t in doc.get_toc()))
        for s, sn in zip(secrets, secrets_n):
            if sn in toc:
                report["bookmarksFound"].append(s)
        # Raw search in every decompressed stream (catches e.g. hidden text objects).
        needles = []
        for s in secrets:
            s = s.strip()
            if len(s) >= 4:
                needles.append(s.encode("latin-1", "ignore"))
                needles.append(s.encode("utf-16-be", "ignore"))
        if needles:
            for xref in range(1, doc.xref_length()):
                try:
                    if not doc.xref_is_stream(xref):
                        continue
                    data = doc.xref_stream(xref) or b""
                except Exception:  # noqa: BLE001
                    continue
                if any(n and n in data for n in needles):
                    report["streamsFound"] += 1
        # Visual check: each area must render as (almost) uniformly the fill colour.
        if fill is not None:
            target = tuple(int(c * 255) for c in fill)
            for pno, rects in areas_by_page.items():
                page = doc[pno]
                for r in rects:
                    clip = pymupdf.Rect(r) + (1, 1, -1, -1)
                    if clip.is_empty or clip.width < 2 or clip.height < 2:
                        continue
                    pix = page.get_pixmap(clip=clip, dpi=72, alpha=False, colorspace=pymupdf.csRGB)
                    samples = pix.samples
                    n = pix.width * pix.height
                    if n == 0:
                        continue
                    off = 0
                    for k in range(0, len(samples), 3):
                        if abs(samples[k] - target[0]) + abs(samples[k + 1] - target[1]) + abs(samples[k + 2] - target[2]) > 60:
                            off += 1
                    if off / n > 0.05:
                        report["areasNotCovered"].append({"page": pno + 1, "rect": list(r)})
    finally:
        doc.close()
    report["passed"] = not (report["textFound"] or report["annotationsFound"] or report["metadataFound"]
                            or report["bookmarksFound"] or report["streamsFound"] or report["areasNotCovered"])
    return report


@method("redact.apply")
def apply(ctx, path: str, output: str, areas: list, password: str | None = None, overwrite: bool = False,
          fillColor: str | None = "#000000", overlayText: str = "", images: str = "pixels",
          removeGraphics: bool = True, verifyTerms: list | None = None, sanitizeMetadata: bool = True,
          sanitizeAttachments: bool = True, sanitizeJavaScript: bool = True, sanitizeThumbnails: bool = True,
          removeOverlappingAnnotations: bool = True, removeOverlappingFields: bool = True, space: str = "pdf"):
    """areas: [{"page": 1, "rect": [x0, y0, x1, y1], "text": "optional matched text"}]"""
    if not areas:
        raise invalid("Mark at least one area to redact.")
    doc = open_pdf(path, password, require=["modify"])
    signed = doc_is_signed(doc)
    secrets = [str(t) for t in (verifyTerms or []) if str(t).strip()]
    for a in areas:
        if a.get("text"):
            secrets.append(str(a["text"]))
    fill = hex_to_rgb(fillColor) if fillColor else None
    img_mode = {"pixels": pymupdf.PDF_REDACT_IMAGE_PIXELS, "remove": pymupdf.PDF_REDACT_IMAGE_REMOVE,
                "none": pymupdf.PDF_REDACT_IMAGE_NONE}.get(images, pymupdf.PDF_REDACT_IMAGE_PIXELS)
    by_page: dict[int, list[pymupdf.Rect]] = {}
    for a in areas:
        pno = int(a["page"]) - 1
        if pno < 0 or pno >= doc.page_count:
            raise invalid("Redaction area refers to a page that does not exist.")
        by_page.setdefault(pno, []).append(to_fitz_rect(doc[pno], a["rect"], space))
    removed_annots = 0
    removed_fields = 0
    total = len(by_page)
    for k, (pno, rects) in enumerate(sorted(by_page.items())):
        ctx.progress(k, total, f"Redacting page {pno + 1}")
        page = doc[pno]
        if removeOverlappingFields:
            for w in list(page.widgets()):
                if any(w.rect.intersects(r) for r in rects):
                    page.delete_widget(w)
                    removed_fields += 1
        if removeOverlappingAnnotations:
            for an in list(page.annots()):
                if an.type[0] in (pymupdf.PDF_ANNOT_REDACT, pymupdf.PDF_ANNOT_WIDGET):
                    continue
                if any(an.rect.intersects(r) for r in rects):
                    page.delete_annot(an)
                    removed_annots += 1
        for r in rects:
            page.add_redact_annot(r, text=overlayText or None, fill=fill if fill is not None else False,
                                  text_color=(1, 1, 1) if fill and sum(fill) < 1.5 else (0, 0, 0), fontsize=8,
                                  cross_out=False)
        page.apply_redactions(images=img_mode,
                              graphics=pymupdf.PDF_REDACT_LINE_ART_REMOVE_IF_TOUCHED if removeGraphics else pymupdf.PDF_REDACT_LINE_ART_NONE,
                              text=pymupdf.PDF_REDACT_TEXT_REMOVE)
    # Comments elsewhere that quote the secret text are removed too.
    if secrets:
        sn = [_norm(s) for s in secrets]
        for page in doc:
            for an in list(page.annots()):
                c = _norm((an.info or {}).get("content", ""))
                if any(x and x in c for x in sn):
                    page.delete_annot(an)
                    removed_annots += 1
        toc = doc.get_toc(simple=False)
        new_toc = [t for t in toc if not any(x and x in _norm(t[1]) for x in sn)]
        if len(new_toc) != len(toc):
            ctx.warn(f"{len(toc) - len(new_toc)} bookmark(s) containing redacted text were removed.")
            from .pages import _fix_toc_levels

            doc.set_toc(_fix_toc_levels([t[:3] for t in new_toc]))
    doc.scrub(attached_files=sanitizeAttachments, clean_pages=True, embedded_files=sanitizeAttachments,
              hidden_text=False, javascript=sanitizeJavaScript, metadata=sanitizeMetadata, redactions=False,
              remove_links=False, reset_fields=False, reset_responses=False, thumbnails=sanitizeThumbnails,
              xml_metadata=sanitizeMetadata)
    n = doc.page_count
    out = OutputFile(ctx, output, overwrite, sources=[path])
    # Full rewrite: garbage collection drops the old content streams; never incremental.
    doc.save(out.temp, garbage=4, clean=True, deflate=True, use_objstms=1)
    doc.close()
    ctx.status("Verifying redaction")
    report = _verify(out.temp, password, secrets, by_page, fill)
    if not report["passed"]:
        raise EngineError(VALIDATION_FAILED,
                          "Verification found redacted content still present in the output. The file was not saved.",
                          {"report": report})
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    if signed:
        ctx.warn("The original document was digitally signed. Redaction invalidates the signature(s).")
    if not secrets:
        ctx.warn("Areas were redacted without specific search text, so verification checked visual coverage only. Add the sensitive text to 'verify' for a full text check.")
    return {"output": final, "areas": sum(len(v) for v in by_page.values()), "pages": len(by_page),
            "removedAnnotations": removed_annots, "removedFields": removed_fields, "verification": report}
