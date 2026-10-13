"""Document inspection: properties, page sizes, text spans, words, images, fonts."""

from __future__ import annotations

import os

import pymupdf

from ..errors import EngineError, PASSWORD_INVALID, PASSWORD_REQUIRED
from ..registry import method
from ..util.geometry import fitz_to_pdf_rect
from ..util.io import file_size, os_path, require_file
from ..util.pdf import (
    PERM_BITS,
    doc_is_signed,
    encryption_method,
    int_to_rgb,
    open_pdf,
    owner_authenticated,
    paper_name,
    rgb_to_hex,
)


def _permissions(doc) -> dict:
    perms = doc.permissions
    enc = encryption_method(doc)
    out = {k: bool(perms & bit) for k, bit in PERM_BITS.items()}
    out["accessibility"] = bool(perms & pymupdf.PDF_PERM_ACCESSIBILITY)
    out["printHighQuality"] = bool(perms & pymupdf.PDF_PERM_PRINT_HQ)
    out["restricted"] = bool(enc) and not all(out[k] for k in PERM_BITS)
    return out


@method("doc.probe")
def probe(ctx, path: str, password: str | None = None):
    """Cheap check used when opening a file: is it a PDF, does it need a password?"""
    require_file(path)
    try:
        doc = pymupdf.open(os_path(path), filetype="pdf")
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "code": "INVALID_PDF", "message": f"Cannot open this PDF: {e}"}
    try:
        if doc.needs_pass:
            if not password:
                return {"ok": False, "code": PASSWORD_REQUIRED, "needsPassword": True}
            if not doc.authenticate(password):
                return {"ok": False, "code": PASSWORD_INVALID, "needsPassword": True}
        return {"ok": True, "pages": doc.page_count, "repaired": bool(doc.is_repaired),
                "encrypted": bool(encryption_method(doc))}
    finally:
        doc.close()


@method("doc.info")
def info(ctx, path: str, password: str | None = None):
    doc = open_pdf(path, password)
    try:
        meta = doc.metadata or {}
        sizes: dict[str, int] = {}
        first = doc[0].rect
        for p in doc:
            r = p.rect
            key = f"{paper_name(r.width, r.height)} {round(r.width)}x{round(r.height)}"
            sizes[key] = sizes.get(key, 0) + 1
        annots = 0
        widgets = 0
        for p in doc:
            annots += sum(1 for _ in p.annots())
            widgets += sum(1 for _ in p.widgets())
        try:
            embedded = doc.embfile_count()
        except Exception:  # noqa: BLE001
            embedded = 0
        try:
            labels = bool(doc.get_page_labels())
        except Exception:  # noqa: BLE001
            labels = False
        text_pages = 0
        sample = range(min(doc.page_count, 30))
        for i in sample:
            if doc[i].get_text("text").strip():
                text_pages += 1
        return {
            "path": path,
            "fileName": os.path.basename(path),
            "fileSize": file_size(path),
            "pageCount": doc.page_count,
            "pdfVersion": meta.get("format", ""),
            "metadata": {k: meta.get(k, "") for k in ("title", "author", "subject", "keywords", "creator",
                                                      "producer", "creationDate", "modDate", "trapped")},
            "encryption": encryption_method(doc),
            "ownerAccess": owner_authenticated(doc),
            "permissions": _permissions(doc),
            "signed": doc_is_signed(doc),
            "isFormPdf": bool(doc.is_form_pdf),
            "formFields": widgets,
            "annotations": annots,
            "bookmarks": len(doc.get_toc(simple=True)),
            "attachments": embedded,
            "pageLabels": labels,
            "repaired": bool(doc.is_repaired),
            "firstPageSize": {"width": first.width, "height": first.height, "name": paper_name(first.width, first.height)},
            "pageSizes": sizes,
            "likelyScanned": text_pages == 0,
            "textSampled": {"pagesWithText": text_pages, "sampled": len(sample)},
            "hasXmp": bool(doc.get_xml_metadata()),
        }
    finally:
        doc.close()


@method("doc.pageSizes")
def page_sizes(ctx, path: str, password: str | None = None):
    doc = open_pdf(path, password)
    try:
        out = []
        for p in doc:
            r = p.rect
            out.append({"page": p.number + 1, "width": round(r.width, 2), "height": round(r.height, 2),
                        "widthMm": round(r.width / 72 * 25.4, 1), "heightMm": round(r.height / 72 * 25.4, 1),
                        "rotation": p.rotation, "name": paper_name(r.width, r.height)})
        return {"pages": out}
    finally:
        doc.close()


def _span_dict(s, page=None) -> dict:
    return {
        "text": s["text"],
        "bbox": list(s["bbox"]),
        "pdfBbox": fitz_to_pdf_rect(page, s["bbox"]) if page is not None else None,
        "origin": list(s["origin"]),
        "font": s["font"],
        "size": round(s["size"], 2),
        "color": rgb_to_hex(int_to_rgb(s["color"])),
        "bold": bool(s["flags"] & 16) or "bold" in s["font"].lower(),
        "italic": bool(s["flags"] & 2) or "italic" in s["font"].lower() or "oblique" in s["font"].lower(),
        "serif": bool(s["flags"] & 4),
        "mono": bool(s["flags"] & 8),
    }


@method("page.textLines")
def text_lines(ctx, path: str, page: int, password: str | None = None):
    """Lines and spans with geometry and style, for the Edit Text tool."""
    doc = open_pdf(path, password)
    try:
        if page < 1 or page > doc.page_count:
            raise EngineError("INVALID_ARGUMENT", "Page out of range.")
        p = doc[page - 1]
        data = p.get_text("dict", flags=pymupdf.TEXTFLAGS_DICT & ~pymupdf.TEXT_PRESERVE_IMAGES)
        blocks = []
        for bi, b in enumerate(data["blocks"]):
            if b.get("type") != 0:
                continue
            lines = []
            for li, ln in enumerate(b["lines"]):
                spans = [_span_dict(s, p) for s in ln["spans"] if s["text"].strip() != "" or len(ln["spans"]) == 1]
                if not spans:
                    continue
                text = "".join(s["text"] for s in ln["spans"])
                if not text.strip():
                    continue
                lines.append({"id": f"{bi}-{li}", "bbox": list(ln["bbox"]), "pdfBbox": fitz_to_pdf_rect(p, ln["bbox"]),
                              "editable": abs(ln["dir"][1]) < 0.01 and ln["dir"][0] > 0, "dir": list(ln["dir"]),
                              "wmode": ln["wmode"], "text": text, "spans": spans})
            if lines:
                blocks.append({"id": str(bi), "bbox": list(b["bbox"]), "pdfBbox": fitz_to_pdf_rect(p, b["bbox"]), "lines": lines})
        return {"page": page, "width": p.rect.width, "height": p.rect.height, "rotation": p.rotation,
                "blocks": blocks, "hasText": bool(blocks)}
    finally:
        doc.close()


@method("page.words")
def words(ctx, path: str, page: int, password: str | None = None):
    doc = open_pdf(path, password)
    try:
        p = doc[page - 1]
        return {"words": [list(w[:5]) + [w[5], w[6], w[7]] for w in p.get_text("words")]}
    finally:
        doc.close()


@method("page.images")
def images(ctx, path: str, page: int, password: str | None = None):
    """Image placements on a page (for the Edit Objects tool)."""
    doc = open_pdf(path, password)
    try:
        p = doc[page - 1]
        out = []
        for i, im in enumerate(p.get_image_info(xrefs=True)):
            bbox = pymupdf.Rect(im["bbox"])
            if bbox.is_empty or bbox.is_infinite:
                continue
            out.append({"index": i, "xref": im.get("xref", 0), "bbox": list(bbox),
                        "width": im.get("width"), "height": im.get("height"),
                        "inline": im.get("xref", 0) == 0})
        return {"page": page, "images": out}
    finally:
        doc.close()


@method("doc.fonts")
def fonts(ctx, path: str, password: str | None = None):
    doc = open_pdf(path, password)
    try:
        seen = {}
        for p in doc:
            for f in p.get_fonts(full=True):
                xref, ext, ftype, basefont, name, enc = f[:6]
                key = xref
                if key in seen:
                    seen[key]["pages"] += 1
                    continue
                seen[key] = {"xref": xref, "name": basefont, "type": ftype, "embedded": ext not in ("n/a", ""),
                             "subset": "+" in basefont[:8], "encoding": enc, "pages": 1}
        return {"fonts": list(seen.values())}
    finally:
        doc.close()


@method("doc.search")
def search(ctx, path: str, term: str, password: str | None = None, matchCase: bool = False,
           pages=None, limit: int = 5000):
    from ..util.pdf import parse_pages

    doc = open_pdf(path, password, require=["copy"])
    try:
        hits = []
        for i in parse_pages(pages, doc.page_count):
            p = doc[i]
            for r in p.search_for(term):
                if matchCase:
                    found = p.get_textbox(r + (-1, -1, 1, 1))
                    if term not in found:
                        continue
                hits.append({"page": i + 1, "rect": list(r)})
                if len(hits) >= limit:
                    return {"hits": hits, "truncated": True}
        return {"hits": hits, "truncated": False}
    finally:
        doc.close()


@method("doc.text")
def doc_text(ctx, path: str, password: str | None = None, pages=None):
    from ..util.pdf import parse_pages

    doc = open_pdf(path, password, require=["copy"])
    try:
        out = []
        for i in parse_pages(pages, doc.page_count):
            out.append({"page": i + 1, "text": doc[i].get_text("text", sort=True)})
        return {"pages": out}
    finally:
        doc.close()


@method("engine.capabilities")
def capabilities(ctx):
    from ..util import tools

    return {
        "pymupdf": pymupdf.VersionBind,
        "mupdf": pymupdf.VersionFitz,
        "tesseract": bool(tools.tesseract_exe()),
        "ocrLanguages": tools.ocr_languages(),
        "libreoffice": bool(tools.soffice_exe()),
        "ghostscript": bool(tools.ghostscript_exe()),
    }
