"""Create PDFs from images, text and mixed sources; assemble several files."""

from __future__ import annotations

import io
import os

import pymupdf

from ..errors import EngineError, UNSUPPORTED_FORMAT, invalid
from ..registry import method
from ..util.io import OutputFile, os_path, require_file, validate_pdf
from ..util.pdf import open_pdf, paper_rect, is_pdf_file

IMAGE_EXT = {".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".gif", ".webp", ".jp2", ".jfif"}
TEXT_EXT = {".txt", ".text", ".log", ".md", ".csv"}


def _image_frames(path: str):
    """Yield (bytes, width_px, height_px, dpi) per frame (multi-page TIFF supported)."""
    from PIL import Image, ImageOps

    try:
        im = Image.open(os_path(path))
    except Exception as e:  # noqa: BLE001
        raise EngineError(UNSUPPORTED_FORMAT, f"{os.path.basename(path)} is not a readable image ({e}).")
    n = getattr(im, "n_frames", 1)
    for k in range(n):
        im.seek(k)
        frame = ImageOps.exif_transpose(im.copy()) if k == 0 else im.copy()
        dpi = frame.info.get("dpi", (0, 0))[0] or 0
        ext = os.path.splitext(path)[1].lower()
        if n == 1 and ext in (".jpg", ".jpeg", ".jfif") and frame.mode in ("RGB", "L", "CMYK") \
                and (im.getexif().get(0x0112, 1) in (1, None)):
            with open(os_path(path), "rb") as fh:
                yield fh.read(), frame.width, frame.height, dpi
            continue
        buf = io.BytesIO()
        if frame.mode in ("P", "LA", "RGBA"):
            frame = frame.convert("RGBA")
        elif frame.mode not in ("RGB", "L", "1"):
            frame = frame.convert("RGB")
        frame.save(buf, "PNG")
        yield buf.getvalue(), frame.width, frame.height, dpi


def _recompress(data: bytes, quality: str) -> bytes:
    if quality == "original":
        return data
    from PIL import Image

    im = Image.open(io.BytesIO(data))
    if im.mode in ("RGBA", "LA", "P"):
        return data  # keep transparency lossless
    q = {"high": 90, "medium": 75, "low": 55}.get(quality, 85)
    maxpx = {"high": 3500, "medium": 2400, "low": 1600}.get(quality, 3500)
    if max(im.size) > maxpx:
        r = maxpx / max(im.size)
        im = im.resize((int(im.width * r), int(im.height * r)), Image.LANCZOS)
    buf = io.BytesIO()
    im.convert("L" if im.mode in ("L", "1") else "RGB").save(buf, "JPEG", quality=q, optimize=True)
    return buf.getvalue() if len(buf.getvalue()) < len(data) else data


def _place(page: pymupdf.Page, data: bytes, w: int, h: int, margin: float, fit: str, dpi_hint: float):
    box = page.rect + (margin, margin, -margin, -margin)
    if fit == "fill":
        s = max(box.width / w, box.height / h)
    elif fit == "actual":
        dpi = dpi_hint or 96
        s = 72.0 / dpi
        if w * s > box.width or h * s > box.height:
            s = min(box.width / w, box.height / h)
    else:
        s = min(box.width / w, box.height / h)
    iw, ih = w * s, h * s
    r = pymupdf.Rect(box.x0 + (box.width - iw) / 2, box.y0 + (box.height - ih) / 2,
                     box.x0 + (box.width + iw) / 2, box.y0 + (box.height + ih) / 2)
    if fit == "fill":
        page.insert_image(r, stream=data, keep_proportion=True)
    else:
        page.insert_image(r, stream=data, keep_proportion=True)


def images_into(ctx, doc: pymupdf.Document, images: list[str], pageSize: str, orientation: str, margin: float,
                fit: str, quality: str):
    for k, path in enumerate(images):
        ctx.progress(k, len(images), os.path.basename(path))
        require_file(path)
        for data, w, h, dpi in _image_frames(path):
            data = _recompress(data, quality)
            if pageSize == "fit":
                dpi_ = dpi or 96
                pw, ph = w * 72.0 / dpi_, h * 72.0 / dpi_
                page = doc.new_page(width=pw + 2 * margin, height=ph + 2 * margin)
                page.insert_image(page.rect + (margin, margin, -margin, -margin), stream=data)
                continue
            orient = orientation
            if orient == "auto":
                orient = "landscape" if w > h else "portrait"
            r = paper_rect(pageSize, orient)
            page = doc.new_page(width=r.width, height=r.height)
            _place(page, data, w, h, margin, fit, dpi)


@method("create.fromImages")
def from_images(ctx, images: list, output: str, pageSize: str = "A4", orientation: str = "auto",
                margin: float = 0, fit: str = "fit", quality: str = "original", overwrite: bool = False,
                ocr: bool = False, languages: list | None = None):
    if not images:
        raise invalid("Select at least one image.")
    doc = pymupdf.open()
    images_into(ctx, doc, images, pageSize, orientation, float(margin), fit, quality)
    n = doc.page_count
    out = OutputFile(ctx, output, overwrite, sources=images)
    if ocr:
        from .ocr import _check_langs, ocr_document

        lang = _check_langs(languages or ["eng"])
        res = ocr_document(ctx, doc, list(range(n)), lang, 300, False, True)
        review = [p for p, st in res["pages"].items() if st["review"]]
        if review:
            ctx.warn(f"Low OCR confidence on page(s) {', '.join(map(str, review))}.")
    doc.save(out.temp, garbage=3, deflate=True)
    doc.close()
    final = out.commit(validate_pdf(expected_pages=n))
    return {"output": final, "pageCount": n, "searchable": bool(ocr)}


@method("create.fromText")
def from_text(ctx, path: str, output: str, pageSize: str = "A4", orientation: str = "portrait",
              margin: float = 54, fontSize: float = 10, monospace: bool = True, overwrite: bool = False):
    require_file(path)
    raw = open(os_path(path), "rb").read()
    for enc in ("utf-8-sig", "utf-16", "cp1252", "latin-1"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    text = text.replace("\r\n", "\n").replace("\t", "    ")
    doc = pymupdf.open()
    r = paper_rect(pageSize, orientation)
    font = "cour" if monospace else "helv"
    try:
        text.encode("cp1252")
    except UnicodeEncodeError:
        font = "notos"
    fnt = pymupdf.Font(font)
    lh = float(fontSize) * 1.3
    width = r.width - 2 * margin
    lines_per_page = int((r.height - 2 * margin) // lh)
    out_lines: list[str] = []
    for para in text.split("\n"):
        if para == "":
            out_lines.append("")
            continue
        cur = ""
        for ch_word in _split_keep(para):
            cand = cur + ch_word
            if fnt.text_length(cand, fontsize=float(fontSize)) <= width or not cur:
                cur = cand
            else:
                out_lines.append(cur.rstrip())
                cur = ch_word.lstrip()
        out_lines.append(cur.rstrip())
    if not out_lines:
        out_lines = [""]
    for start in range(0, len(out_lines), lines_per_page):
        page = doc.new_page(width=r.width, height=r.height)
        if font == "notos":
            page.insert_font(fontname="notos", fontbuffer=fnt.buffer)
        y = margin + float(fontSize)
        for line in out_lines[start:start + lines_per_page]:
            if line:
                page.insert_text((margin, y), line, fontsize=float(fontSize), fontname=font)
            y += lh
    n = doc.page_count
    out = OutputFile(ctx, output, overwrite, sources=[path])
    doc.save(out.temp, garbage=3, deflate=True)
    doc.close()
    final = out.commit(validate_pdf(expected_pages=n))
    return {"output": final, "pageCount": n}


def _split_keep(s: str):
    word = ""
    for ch in s:
        word += ch
        if ch == " ":
            yield word
            word = ""
    if word:
        yield word


@method("create.blank")
def blank(ctx, output: str, pages: int = 1, pageSize: str = "A4", orientation: str = "portrait",
          overwrite: bool = False):
    doc = pymupdf.open()
    r = paper_rect(pageSize, orientation)
    for _ in range(max(1, int(pages))):
        doc.new_page(width=r.width, height=r.height)
    out = OutputFile(ctx, output, overwrite)
    doc.save(out.temp)
    doc.close()
    return {"output": out.commit(validate_pdf(expected_pages=max(1, int(pages))))}


@method("create.assemble")
def assemble(ctx, files: list, output: str, pageSize: str = "A4", orientation: str = "auto", margin: float = 0,
             fit: str = "fit", quality: str = "original", bookmarks: bool = True, overwrite: bool = False,
             singlePageSheets: bool = False):
    """Combine PDFs, images, Office documents and text files into one PDF, in the given order."""
    from .convert import OFFICE_EXT, office_to_pdf_file
    from .pages import _fix_toc_levels

    if not files:
        raise invalid("Add at least one file.")
    result = pymupdf.open()
    toc = []
    for k, f in enumerate(files):
        path = f["path"] if isinstance(f, dict) else f
        password = f.get("password") if isinstance(f, dict) else None
        ctx.progress(k, len(files), os.path.basename(path))
        require_file(path)
        ext = os.path.splitext(path)[1].lower()
        start = result.page_count
        if ext == ".pdf" or is_pdf_file(path):
            src = open_pdf(path, password, require=["assemble"])
            result.insert_pdf(src)
            for lvl, title, pno in src.get_toc(simple=True):
                toc.append([lvl + (1 if bookmarks else 0), title, pno + start])
            src.close()
        elif ext in IMAGE_EXT:
            images_into(ctx, result, [path], pageSize, orientation, float(margin), fit, quality)
        elif ext in OFFICE_EXT and ext not in TEXT_EXT:
            tmp = ctx.tmp_path(".pdf")
            office_to_pdf_file(ctx, path, tmp, singlePageSheets)
            src = pymupdf.open(tmp)
            result.insert_pdf(src)
            src.close()
        elif ext in TEXT_EXT:
            tmp = ctx.tmp_path(".pdf")
            from_text(ctx, path, tmp, pageSize if pageSize != "fit" else "A4",
                      "portrait" if orientation == "auto" else orientation, overwrite=True)
            src = pymupdf.open(tmp)
            result.insert_pdf(src)
            src.close()
        else:
            raise EngineError(UNSUPPORTED_FORMAT, f"{os.path.basename(path)}: this file type cannot be added to a PDF.")
        if bookmarks and result.page_count > start:
            toc.append([1, os.path.splitext(os.path.basename(path))[0], start + 1])
    toc.sort(key=lambda t: (t[2], t[0]))
    if toc:
        result.set_toc(_fix_toc_levels(toc))
    n = result.page_count
    out = OutputFile(ctx, output, overwrite, sources=[f["path"] if isinstance(f, dict) else f for f in files])
    result.save(out.temp, garbage=3, deflate=True)
    result.close()
    final = out.commit(validate_pdf(expected_pages=n))
    return {"output": final, "pageCount": n, "files": len(files)}
