"""Watermarks, headers/footers, page numbers, Bates numbering, backgrounds and
borders.

Everything is drawn on an overlay page laid out in the page's *viewed*
orientation and placed onto the target page as a Form XObject (rotated to
match /Rotate), so stamps appear upright on rotated pages too. Stamps are
page content (not annotations).
"""

from __future__ import annotations

import datetime as _dt
import os

import pymupdf

from ..errors import invalid
from ..registry import method
from ..util.io import OutputFile, require_file, validate_pdf
from ..util.pdf import doc_is_signed, hex_to_rgb, open_pdf, parse_pages, save_doc

FONTS = {"helv": "helv", "hebo": "hebo", "tiro": "tiro", "tibo": "tibo", "cour": "cour", "cobo": "cobo",
         "notos": "notos", "notosbo": "notosbo"}
PRESET_LABELS = ["CONFIDENTIAL", "DRAFT", "FOR REVIEW", "FINAL", "SAMPLE", "COPY", "PAID", "VERIFIED"]


def _roman(n: int) -> str:
    vals = [(1000, "m"), (900, "cm"), (500, "d"), (400, "cd"), (100, "c"), (90, "xc"), (50, "l"), (40, "xl"),
            (10, "x"), (9, "ix"), (5, "v"), (4, "iv"), (1, "i")]
    out = ""
    for v, s in vals:
        while n >= v:
            out += s
            n -= v
    return out


def _font(name: str, text: str) -> str:
    name = FONTS.get(name or "helv", "helv")
    try:
        text.encode("cp1252")
    except UnicodeEncodeError:
        if not name.startswith("notos"):
            name = "notosbo" if name.endswith("bo") else "notos"
    return name


def _text_width(text: str, fontname: str, size: float) -> float:
    return pymupdf.Font(fontname).text_length(text, fontsize=size)


def _draw_text(op: pymupdf.Page, text: str, *, x: float, y: float, size: float, fontname: str, color, opacity: float,
               angle: float = 0.0, anchor: str = "left"):
    """Draw text with its baseline at y. anchor: left/center/right relative to x."""
    fontname = _font(fontname, text)
    if fontname.startswith("notos"):
        op.insert_font(fontname=fontname, fontbuffer=pymupdf.Font(fontname).buffer)
    w = _text_width(text, fontname, size)
    if anchor == "center":
        x0 = x - w / 2
    elif anchor == "right":
        x0 = x - w
    else:
        x0 = x
    morph = None
    if angle:
        pivot = pymupdf.Point(x, y - size * 0.35) if anchor == "center" else pymupdf.Point(x0, y)
        morph = (pivot, pymupdf.Matrix(-float(angle)))
    op.insert_text(pymupdf.Point(x0, y), text, fontsize=size, fontname=fontname, color=color,
                   fill_opacity=opacity, stroke_opacity=opacity, morph=morph)
    return w


def _apply_overlay(page: pymupdf.Page, overlay_doc: pymupdf.Document, pno: int, behind: bool):
    unrot = page.rect * page.derotation_matrix
    unrot.normalize()
    page.show_pdf_page(unrot, overlay_doc, pno, rotate=page.rotation, overlay=not behind, keep_proportion=False)


def _new_overlay(page: pymupdf.Page):
    ov = pymupdf.open()
    ovp = ov.new_page(width=page.rect.width, height=page.rect.height)
    return ov, ovp


def _position(pos: str, w: float, h: float, margin: float, size: float) -> tuple[float, float, str]:
    """Baseline point and anchor for a named position."""
    vert, _, horiz = pos.partition("-")
    if pos == "center":
        return w / 2, h / 2 + size * 0.35, "center"
    if vert == "top":
        y = margin + size
    elif vert == "bottom":
        y = h - margin
    else:
        y = h / 2 + size * 0.35
    if horiz == "left":
        return margin, y, "left"
    if horiz == "right":
        return w - margin, y, "right"
    return w / 2, y, "center"


def _finish(ctx, doc, path, output, overwrite, password, pages):
    if doc_is_signed(doc):
        ctx.warn("This document is digitally signed. Adding stamps changes page content and invalidates the signature(s).")
    out = OutputFile(ctx, output, overwrite, sources=[path])
    save_doc(doc, out.temp, garbage=3)
    doc.close()
    return out.commit(validate_pdf(expected_pages=pages, password=password))


@method("stamp.watermark")
def watermark(ctx, path: str, output: str, kind: str = "text", text: str = "CONFIDENTIAL",
              fontName: str = "hebo", fontSize: float = 60, color: str = "#c92a2a", opacity: float = 0.25,
              angle: float = 45, position: str = "center", offsetX: float = 0, offsetY: float = 0,
              pages=None, behind: bool = False, imagePath: str | None = None, scale: float = 0.5,
              password: str | None = None, overwrite: bool = False):
    doc = open_pdf(path, password, require=["modify"])
    sel = parse_pages(pages, doc.page_count)
    opacity = max(0.02, min(1.0, float(opacity)))
    img_bytes = None
    img_size = None
    if kind == "image":
        if not imagePath:
            raise invalid("Choose an image for the watermark.")
        require_file(imagePath)
        from .edit import _image_bytes

        img_bytes = _image_bytes(imagePath, opacity)
        pix = pymupdf.Pixmap(img_bytes)
        img_size = (pix.width, pix.height)
    elif not text:
        raise invalid("Enter the watermark text.")
    col = hex_to_rgb(color)
    cache: dict = {}
    for k, i in enumerate(sel):
        if k % 10 == 0:
            ctx.progress(k, len(sel))
        page = doc[i]
        key = (round(page.rect.width, 1), round(page.rect.height, 1))
        if key not in cache:
            ov, ovp = _new_overlay(page)
            w, h = page.rect.width, page.rect.height
            if kind == "image":
                iw = w * max(0.05, min(1.0, float(scale)))
                ih = iw * img_size[1] / img_size[0]
                cx, cy = _img_center(position, w, h, iw, ih)
                cx += offsetX
                cy -= offsetY
                r = pymupdf.Rect(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2)
                ovp.insert_image(r, stream=img_bytes, rotate=0)
            else:
                size = float(fontSize)
                lines = text.split("\n")
                x, y, anchor = _position(position, w, h, 36, size)
                x += offsetX
                y -= offsetY
                for li, line in enumerate(lines):
                    _draw_text(ovp, line, x=x, y=y + li * size * 1.15 - (len(lines) - 1) * size * 0.575,
                               size=size, fontname=fontName, color=col, opacity=opacity, angle=float(angle),
                               anchor=anchor if position != "center" else "center")
            cache[key] = ov
        _apply_overlay(page, cache[key], 0, bool(behind))
    final = _finish(ctx, doc, path, output, overwrite, password, None)
    return {"output": final, "pages": len(sel)}


def _img_center(position: str, w: float, h: float, iw: float, ih: float):
    m = 36
    vert, _, horiz = position.partition("-")
    if position == "center":
        return w / 2, h / 2
    cy = m + ih / 2 if vert == "top" else (h - m - ih / 2 if vert == "bottom" else h / 2)
    cx = m + iw / 2 if horiz == "left" else (w - m - iw / 2 if horiz == "right" else w / 2)
    return cx, cy


def _format_number(fmt: str, n: int, total: int) -> str:
    if fmt == "roman":
        return _roman(n)
    if fmt == "ROMAN":
        return _roman(n).upper()
    if fmt == "page":
        return f"Page {n}"
    if fmt == "pageOf":
        return f"Page {n} of {total}"
    if fmt == "slash":
        return f"{n}/{total}"
    if fmt == "dash":
        return f"- {n} -"
    return str(n)


def _expand(template: str, *, page: int, total: int, path: str, title: str, date: str, bates: str = "",
            label: str = "") -> str:
    return (template.replace("{page}", str(page)).replace("{total}", str(total))
            .replace("{date}", date).replace("{filename}", os.path.basename(path))
            .replace("{name}", os.path.splitext(os.path.basename(path))[0])
            .replace("{title}", title).replace("{bates}", bates).replace("{label}", label))


@method("stamp.headerFooter")
def header_footer(ctx, path: str, output: str, header: dict | None = None, footer: dict | None = None,
                  fontName: str = "helv", fontSize: float = 9, color: str = "#333333", opacity: float = 1.0,
                  margin: float = 28, pages=None, startNumber: int = 1, numberFormat: str = "plain",
                  dateFormat: str = "%d/%m/%Y", skipFirst: bool = False, password: str | None = None,
                  overwrite: bool = False):
    """header/footer: {"left": "...", "center": "...", "right": "..."} with tokens
    {page} {total} {date} {filename} {name} {title}. {page} uses numberFormat."""
    header = header or {}
    footer = footer or {}
    if not any((header.get(k) or footer.get(k)) for k in ("left", "center", "right")):
        raise invalid("Enter text for at least one header or footer position.")
    doc = open_pdf(path, password, require=["modify"])
    sel = parse_pages(pages, doc.page_count)
    if skipFirst and sel and sel[0] == 0:
        sel = sel[1:]
    total = len(sel) + int(startNumber) - 1
    title = (doc.metadata or {}).get("title", "") or ""
    date = _dt.date.today().strftime(dateFormat)
    col = hex_to_rgb(color)
    size = float(fontSize)
    for k, i in enumerate(sel):
        if k % 10 == 0:
            ctx.progress(k, len(sel))
        page = doc[i]
        ov, ovp = _new_overlay(page)
        w, h = page.rect.width, page.rect.height
        num = int(startNumber) + k
        pstr = _format_number(numberFormat, num, total)
        for band, spec in (("top", header), ("bottom", footer)):
            for horiz in ("left", "center", "right"):
                tmpl = spec.get(horiz)
                if not tmpl:
                    continue
                txt = tmpl.replace("{page}", pstr)
                txt = _expand(txt, page=num, total=total, path=path, title=title, date=date)
                x, y, anchor = _position(f"{band}-{horiz}", w, h, float(margin), size)
                _draw_text(ovp, txt, x=x, y=y, size=size, fontname=fontName, color=col, opacity=float(opacity),
                           anchor=anchor)
        _apply_overlay(page, ov, 0, False)
    final = _finish(ctx, doc, path, output, overwrite, password, None)
    return {"output": final, "pages": len(sel)}


@method("stamp.pageNumbers")
def page_numbers(ctx, path: str, output: str, position: str = "bottom-center", numberFormat: str = "pageOf",
                 startNumber: int = 1, fontName: str = "helv", fontSize: float = 10, color: str = "#000000",
                 margin: float = 28, pages=None, skipFirst: bool = False, custom: str | None = None,
                 password: str | None = None, overwrite: bool = False):
    vert, _, horiz = position.partition("-")
    template = custom or "{page}"
    spec = {horiz or "center": template}
    kwargs = dict(header=spec if vert == "top" else None, footer=spec if vert != "top" else None)
    return header_footer(ctx, path, output, fontName=fontName, fontSize=fontSize, color=color, margin=margin,
                         pages=pages, startNumber=startNumber, numberFormat=numberFormat, skipFirst=skipFirst,
                         password=password, overwrite=overwrite, **kwargs)


@method("stamp.bates")
def bates(ctx, path: str, output: str, prefix: str = "", startNumber: int = 1, digits: int = 6,
          suffix: str = "", position: str = "bottom-right", fontName: str = "cour", fontSize: float = 9,
          color: str = "#000000", margin: float = 24, password: str | None = None, overwrite: bool = False):
    doc = open_pdf(path, password, require=["modify"])
    n = doc.page_count
    col = hex_to_rgb(color)
    size = float(fontSize)
    first = last = ""
    for i in range(n):
        if i % 10 == 0:
            ctx.progress(i, n)
        page = doc[i]
        label = f"{prefix}{int(startNumber) + i:0{int(digits)}d}{suffix}"
        if i == 0:
            first = label
        last = label
        ov, ovp = _new_overlay(page)
        x, y, anchor = _position(position, page.rect.width, page.rect.height, float(margin), size)
        _draw_text(ovp, label, x=x, y=y, size=size, fontname=fontName, color=col, opacity=1.0, anchor=anchor)
        _apply_overlay(page, ov, 0, False)
    final = _finish(ctx, doc, path, output, overwrite, password, n)
    return {"output": final, "first": first, "last": last, "nextNumber": int(startNumber) + n}


@method("stamp.background")
def background(ctx, path: str, output: str, color: str = "#fff9db", opacity: float = 1.0, pages=None,
               border: bool = False, borderColor: str = "#000000", borderWidth: float = 1.0, borderMargin: float = 18,
               fill: bool = True, password: str | None = None, overwrite: bool = False):
    doc = open_pdf(path, password, require=["modify"])
    sel = parse_pages(pages, doc.page_count)
    for k, i in enumerate(sel):
        page = doc[i]
        if fill:
            ov, ovp = _new_overlay(page)
            ovp.draw_rect(ovp.rect, color=None, fill=hex_to_rgb(color), fill_opacity=float(opacity), width=0)
            _apply_overlay(page, ov, 0, True)
        if border:
            ov, ovp = _new_overlay(page)
            m = float(borderMargin)
            ovp.draw_rect(pymupdf.Rect(m, m, ovp.rect.width - m, ovp.rect.height - m), color=hex_to_rgb(borderColor),
                          width=float(borderWidth))
            _apply_overlay(page, ov, 0, False)
    if not fill and not border:
        raise invalid("Choose a background colour and/or a border.")
    if fill:
        ctx.warn("Backgrounds are placed behind page content. Scanned pages with an opaque image will hide them.")
    final = _finish(ctx, doc, path, output, overwrite, password, None)
    return {"output": final, "pages": len(sel)}


@method("stamp.presets")
def presets(ctx):
    return {"labels": PRESET_LABELS}
