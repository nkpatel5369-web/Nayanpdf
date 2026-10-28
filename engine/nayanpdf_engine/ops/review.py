"""Comment review: replies, review status, XFDF exchange, summaries.

Replies and review states are stored the way Acrobat stores them, so threads
survive a round trip through Acrobat and other viewers:

* a reply is a Text annotation whose /IRT points at the comment it answers,
  with /RT /R. It carries the NoView flag so viewers that do not understand
  threads do not draw a second note icon on top of the original;
* a review state ("Accepted", "Rejected", "Cancelled", "Completed", "None")
  is a hidden Text annotation with /IRT, /State and /StateModel /Review; a
  check mark uses /StateModel /Marked. The latest one per reviewer wins.

XFDF is the XML exchange format Acrobat imports and exports (Comments >
Export / Import Data File). Only the annotation types NayanPDF itself
creates are written and read; anything else in an imported file is
reported, never silently dropped.
"""

from __future__ import annotations

import csv
import html
import io
import os
import uuid
import xml.etree.ElementTree as ET

import pymupdf

from ..errors import EngineError, invalid
from ..registry import method
from ..util.geometry import fitz_to_pdf_point, fitz_to_pdf_rect, pdf_to_fitz_point, pdf_to_fitz_rect
from ..util.io import OutputFile, require_file, validate_pdf
from ..util.pdf import hex_to_rgb, open_pdf, rgb_to_hex, save_doc

REVIEW_STATES = ("Accepted", "Rejected", "Cancelled", "Completed", "None")
MARK_STATES = ("Marked", "Unmarked")
NOVIEW = 32
HIDDEN = 2
XFDF_NS = "http://ns.adobe.com/xfdf/"

# Annotation subtype -> XFDF element name, for the types NayanPDF creates.
XFDF_TAGS = {
    "Text": "text", "FreeText": "freetext", "Line": "line", "Square": "square", "Circle": "circle",
    "Highlight": "highlight", "Underline": "underline", "Squiggly": "squiggly", "StrikeOut": "strikeout",
    "Ink": "ink", "Stamp": "stamp",
}
MARKUP_TAGS = {"highlight", "underline", "squiggly", "strikeout"}
MAX_XFDF_BYTES = 20 * 1024 * 1024


# ----------------------------------------------------------------- helpers
def _key(doc: pymupdf.Document, xref: int, key: str) -> str | None:
    t, v = doc.xref_get_key(xref, key)
    if t in ("null", "undefined"):
        return None
    if t == "string":
        return v
    if t == "name":
        return v.lstrip("/")
    return v


def _name_of(doc: pymupdf.Document, xref: int) -> str:
    """The annotation's unique name (/NM), used to link replies in XFDF."""
    return _key(doc, xref, "NM") or f"nayan-{xref}"


def thread_info(doc: pymupdf.Document, a: pymupdf.Annot) -> dict:
    """Reply / review-state fields for annot.list."""
    x = a.xref
    irt = None
    t, v = doc.xref_get_key(x, "IRT")
    if t == "xref":
        try:
            irt = int(v.split()[0])
        except (ValueError, IndexError):
            irt = None
    return {
        "irt": irt,
        "replyType": _key(doc, x, "RT") if irt else None,
        "state": _key(doc, x, "State"),
        "stateModel": _key(doc, x, "StateModel"),
        "hidden": bool(a.flags & HIDDEN),
        "nm": _key(doc, x, "NM"),
    }


def _new_thread_annot(page: pymupdf.Page, parent: pymupdf.Annot, contents: str, author: str | None) -> pymupdf.Annot:
    a = page.add_text_annot(parent.rect.tl, contents, icon="Comment")
    doc = page.parent
    doc.xref_set_key(a.xref, "IRT", f"{parent.xref} 0 R")
    doc.xref_set_key(a.xref, "NM", pymupdf.get_pdf_str(str(uuid.uuid4())))
    info = {"content": contents}
    if author:
        info["title"] = author
    a.set_info(**info)
    return a


def add_reply(page: pymupdf.Page, parent: pymupdf.Annot, contents: str, author: str | None) -> pymupdf.Annot:
    if not contents.strip():
        raise invalid("A reply needs some text.")
    a = _new_thread_annot(page, parent, contents, author)
    doc = page.parent
    doc.xref_set_key(a.xref, "RT", "/R")
    a.set_flags(NOVIEW | 8 | 16)  # NoView, NoZoom, NoRotate (as Acrobat writes replies)
    a.update()
    doc.xref_set_key(a.xref, "IRT", f"{parent.xref} 0 R")
    return a


def set_state(page: pymupdf.Page, parent: pymupdf.Annot, state: str, author: str | None, model: str = "Review") -> pymupdf.Annot:
    allowed = REVIEW_STATES if model == "Review" else MARK_STATES
    if state not in allowed:
        raise invalid(f"Unknown status: {state}")
    who = author or "Reviewer"
    label = f"{state} set by {who}" if model == "Review" else f"{state} by {who}"
    a = _new_thread_annot(page, parent, label, author)
    doc = page.parent
    a.set_flags(HIDDEN | 8 | 16)
    a.update()
    doc.xref_set_key(a.xref, "IRT", f"{parent.xref} 0 R")
    doc.xref_set_key(a.xref, "State", pymupdf.get_pdf_str(state))
    doc.xref_set_key(a.xref, "StateModel", pymupdf.get_pdf_str(model))
    return a


def thread_children(page: pymupdf.Page, xref: int) -> list[int]:
    """Replies and states attached (directly or through replies) to a comment."""
    doc = page.parent
    kids: dict[int, list[int]] = {}
    for a in page.annots():
        t, v = doc.xref_get_key(a.xref, "IRT")
        if t == "xref":
            try:
                kids.setdefault(int(v.split()[0]), []).append(a.xref)
            except (ValueError, IndexError):
                pass
    out, todo = [], [xref]
    while todo:
        x = todo.pop()
        for k in kids.get(x, []):
            if k not in out:
                out.append(k)
                todo.append(k)
    return out


# ----------------------------------------------------------------- listing
def _all_comments(doc: pymupdf.Document) -> list[dict]:
    from .annotate import _annot_dict  # noqa: PLC0415 (shared shape)

    out = []
    for p in doc:
        for a in p.annots():
            if a.type[0] in (pymupdf.PDF_ANNOT_POPUP, pymupdf.PDF_ANNOT_LINK, pymupdf.PDF_ANNOT_WIDGET):
                continue
            d = _annot_dict(p, a)
            d.update(thread_info(doc, a))
            out.append(d)
    return out


def threads(items: list[dict]) -> list[dict]:
    """Group a flat list into top-level comments with replies and current status."""
    by_id = {c["id"]: c for c in items}
    tops = []
    for c in items:
        c.setdefault("replies", [])
        c.setdefault("status", None)
        c.setdefault("marked", False)
    for c in items:
        if not c.get("irt") or c["irt"] not in by_id:
            if not c.get("stateModel"):
                tops.append(c)
            continue
        root = by_id[c["irt"]]
        while root.get("irt") and root["irt"] in by_id:
            root = by_id[root["irt"]]
        if c.get("stateModel") == "Review":
            if not root["status"] or (c.get("modified") or "") >= (root["status"].get("modified") or ""):
                root["status"] = {"state": c["state"], "author": c.get("author", ""), "modified": c.get("modified", "")}
        elif c.get("stateModel") == "Marked":
            root["marked"] = c.get("state") == "Marked"
        else:
            root["replies"].append({k: c[k] for k in ("id", "author", "contents", "created", "modified") if k in c})
    return tops


# ----------------------------------------------------------------- XFDF export
def _fmt(nums) -> str:
    return ",".join(f"{float(n):.4f}".rstrip("0").rstrip(".") for n in nums)


def _export_names(doc: pymupdf.Document) -> dict[int, str]:
    """Document-unique names for XFDF. /NM need only be unique within a page (and
    PyMuPDF names every page's first annotation "fitz-A0"), but XFDF links replies
    to their parent by name across the whole file."""
    names: dict[int, str] = {}
    used: set[str] = set()
    for p in doc:
        for a in p.annots():
            nm = _key(doc, a.xref, "NM") or f"nayan-{a.xref}"
            if nm in used:
                nm = f"nayan-{a.xref}"
            used.add(nm)
            names[a.xref] = nm
    return names


def _xfdf_element(doc: pymupdf.Document, page: pymupdf.Page, a: pymupdf.Annot, names: dict[int, str]) -> ET.Element | None:
    sub = a.type[1]
    tag = XFDF_TAGS.get(sub)
    if not tag:
        return None
    info = a.info
    colors = a.colors or {}
    x = a.xref
    attrs = {
        "page": str(page.number),
        "rect": _fmt(fitz_to_pdf_rect(page, a.rect)),
        "name": names.get(x) or _name_of(doc, x),
        "flags": ",".join(n for n, bit in (("hidden", HIDDEN), ("print", 4), ("nozoom", 8), ("norotate", 16), ("noview", NOVIEW)) if a.flags & bit),
    }
    if info.get("title"):
        attrs["title"] = info["title"]
    if info.get("subject"):
        attrs["subject"] = info["subject"]
    for k, xk in (("date", "modDate"), ("creationdate", "creationDate")):
        if info.get(xk):
            attrs[k] = info[xk]
    if colors.get("stroke"):
        attrs["color"] = rgb_to_hex(colors["stroke"]).upper()
    if colors.get("fill"):
        attrs["interior-color"] = rgb_to_hex(colors["fill"]).upper()
    if a.opacity is not None and 0 <= a.opacity < 1:
        attrs["opacity"] = f"{a.opacity:.3f}"
    t = thread_info(doc, a)
    if t["irt"]:
        attrs["inreplyto"] = names.get(t["irt"]) or _name_of(doc, t["irt"])
        if t["stateModel"]:
            attrs["state"] = t["state"] or ""
            attrs["statemodel"] = t["stateModel"]
        else:
            attrs["replyType"] = "group" if t["replyType"] == "Group" else "reply"
    if tag == "text":
        attrs["icon"] = info.get("name") or "Note"
    elif tag in MARKUP_TAGS:
        verts = a.vertices or []
        pts = [fitz_to_pdf_point(page, v) for v in verts]
        attrs["coords"] = _fmt([c for pt in pts for c in pt])
    elif tag == "line":
        verts = a.vertices or []
        if len(verts) >= 2:
            attrs["start"] = _fmt(fitz_to_pdf_point(page, verts[0]))
            attrs["end"] = _fmt(fitz_to_pdf_point(page, verts[1]))
    elif tag in ("square", "circle"):
        attrs["width"] = _fmt([(a.border or {}).get("width", 1)])
    elif tag == "stamp":
        attrs["icon"] = (info.get("name") or "Draft")
    el = ET.Element(tag, attrs)
    if tag == "ink":
        il = ET.SubElement(el, "inklist")
        for stroke in a.vertices or []:
            g = ET.SubElement(il, "gesture")
            g.text = ";".join(_fmt(fitz_to_pdf_point(page, p)) for p in stroke)
    contents = info.get("content", "")
    if contents:
        c = ET.SubElement(el, "contents")
        c.text = contents
    return el


@method("annot.exportXfdf")
def export_xfdf(ctx, path: str, output: str, password: str | None = None, overwrite: bool = False):
    doc = open_pdf(path, password)
    try:
        root = ET.Element("xfdf", {"xmlns": XFDF_NS, "xml:space": "preserve"})
        annots = ET.SubElement(root, "annots")
        n = 0
        skipped = 0
        names = _export_names(doc)
        for page in doc:
            for a in page.annots():
                if a.type[0] in (pymupdf.PDF_ANNOT_POPUP, pymupdf.PDF_ANNOT_LINK, pymupdf.PDF_ANNOT_WIDGET):
                    continue
                el = _xfdf_element(doc, page, a, names)
                if el is None:
                    skipped += 1
                    continue
                annots.append(el)
                n += 1
        ET.SubElement(root, "f", {"href": os.path.basename(path)})
        data = b'<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(root, encoding="utf-8")
    finally:
        doc.close()
    out = OutputFile(ctx, output, overwrite, sources=[path])
    with open(out.temp, "wb") as f:
        f.write(data)
    final = out.commit(lambda p: _check_xfdf(p, n))
    if skipped:
        ctx.warn(f"{skipped} annotation(s) of a type XFDF export does not cover were left out.")
    return {"output": final, "comments": n, "skipped": skipped}


def _check_xfdf(p: str, expected: int):
    tree = ET.parse(p)
    el = tree.getroot().find(f"{{{XFDF_NS}}}annots")
    found = len(list(el)) if el is not None else 0
    if found != expected:
        raise EngineError("VALIDATION_FAILED", f"The exported file holds {found} comments, expected {expected}.")
    return {"comments": found}


# ----------------------------------------------------------------- XFDF import
def _nums(s: str | None) -> list[float]:
    if not s:
        return []
    out = []
    for part in s.replace(";", ",").split(","):
        part = part.strip()
        if part:
            out.append(float(part))
    return out


def _strip(tag: str) -> str:
    return tag.split("}", 1)[1] if "}" in tag else tag


@method("annot.importXfdf")
def import_xfdf(ctx, path: str, output: str, xfdfPath: str, password: str | None = None, overwrite: bool = False,
                author: str | None = None):
    """Add the comments of an XFDF file to the document (existing comments stay)."""
    require_file(xfdfPath)
    if os.path.getsize(xfdfPath) > MAX_XFDF_BYTES:
        raise invalid("The comments file is too large.")
    try:
        tree = ET.parse(xfdfPath)
    except ET.ParseError as e:
        raise EngineError("INVALID_FILE", f"This is not a readable XFDF comments file: {e}")
    root = tree.getroot()
    annots_el = None
    for el in root:
        if _strip(el.tag) == "annots":
            annots_el = el
    if annots_el is None:
        raise EngineError("INVALID_FILE", "The file contains no comments (no <annots> element).")
    doc = open_pdf(path, password, require=["annotate"])
    created_by_name: dict[str, int] = {}
    created_on_page: dict[tuple[int, str], int] = {}
    pending_links: list[tuple[int, int, str, dict]] = []
    added = 0
    unsupported: dict[str, int] = {}
    try:
        for el in annots_el:
            tag = _strip(el.tag)
            pno = int(el.get("page", "0"))
            if pno < 0 or pno >= doc.page_count:
                unsupported["(page out of range)"] = unsupported.get("(page out of range)", 0) + 1
                continue
            page = doc[pno]
            a = _create_from_xfdf(page, tag, el)
            if a is None:
                unsupported[tag] = unsupported.get(tag, 0) + 1
                continue
            contents = ""
            for c in el:
                if _strip(c.tag) == "contents":
                    contents = "".join(c.itertext())
            info = {"content": contents}
            if el.get("title") or author:
                info["title"] = el.get("title") or author
            if el.get("subject"):
                info["subject"] = el.get("subject")
            if el.get("creationdate"):
                info["creationDate"] = el.get("creationdate")
            if el.get("date"):
                info["modDate"] = el.get("date")
            a.set_info(**info)
            color = el.get("color")
            if color and tag not in ("freetext", "stamp"):
                try:
                    cols = {"stroke": hex_to_rgb(color)}
                    if el.get("interior-color") and tag in ("square", "circle"):
                        cols["fill"] = hex_to_rgb(el.get("interior-color"))
                    a.set_colors(**cols)
                except Exception:  # noqa: BLE001
                    pass
            if el.get("opacity"):
                try:
                    a.set_opacity(float(el.get("opacity")))
                except ValueError:
                    pass
            flags = set((el.get("flags") or "").split(","))
            f = 0
            for n, bit in (("hidden", HIDDEN), ("print", 4), ("nozoom", 8), ("norotate", 16), ("noview", NOVIEW)):
                if n in flags:
                    f |= bit
            if f:
                a.set_flags(f)
            a.update()
            name = el.get("name") or str(uuid.uuid4())
            doc.xref_set_key(a.xref, "NM", pymupdf.get_pdf_str(name))
            created_by_name.setdefault(name, a.xref)
            created_on_page[(pno, name)] = a.xref
            if el.get("inreplyto"):
                pending_links.append((pno, a.xref, el.get("inreplyto"), dict(el.attrib)))
            added += 1
        # Threads: link replies and states to their parents by name.
        for pno, xref, parent_name, attrs in pending_links:
            parent = created_on_page.get((pno, parent_name)) or created_by_name.get(parent_name)
            if parent is None:
                for p in doc:
                    for a in p.annots():
                        if _name_of(doc, a.xref) == parent_name:
                            parent = a.xref
            if parent is None:
                continue
            doc.xref_set_key(xref, "IRT", f"{parent} 0 R")
            if attrs.get("statemodel"):
                doc.xref_set_key(xref, "State", pymupdf.get_pdf_str(attrs.get("state", "")))
                doc.xref_set_key(xref, "StateModel", pymupdf.get_pdf_str(attrs["statemodel"]))
            else:
                doc.xref_set_key(xref, "RT", "/Group" if attrs.get("replyType") == "group" else "/R")
        out = OutputFile(ctx, output, overwrite, sources=[path])
        save_doc(doc, out.temp, garbage=1)
    finally:
        doc.close()
    final = out.commit(validate_pdf(password=password))
    if unsupported:
        ctx.warn("Not imported: " + ", ".join(f"{k} ({v})" for k, v in sorted(unsupported.items())))
    return {"output": final, "imported": added, "notImported": unsupported}


def _create_from_xfdf(page: pymupdf.Page, tag: str, el: ET.Element) -> pymupdf.Annot | None:
    rect_n = _nums(el.get("rect"))
    rect = pdf_to_fitz_rect(page, rect_n) if len(rect_n) == 4 else None
    if tag == "text":
        if rect is None:
            return None
        return page.add_text_annot(rect.tl, "", icon=el.get("icon") or "Note")
    if tag in MARKUP_TAGS:
        c = _nums(el.get("coords"))
        quads = []
        for i in range(0, len(c) - 7, 8):
            pts = [pdf_to_fitz_point(page, (c[i + k], c[i + k + 1])) for k in (0, 2, 4, 6)]
            quads.append(pymupdf.Quad(pts[0], pts[1], pts[2], pts[3]))
        if not quads and rect is not None:
            quads = [rect.quad]
        if not quads:
            return None
        fn = {"highlight": page.add_highlight_annot, "underline": page.add_underline_annot,
              "strikeout": page.add_strikeout_annot, "squiggly": page.add_squiggly_annot}[tag]
        return fn(quads)
    if tag == "freetext":
        if rect is None:
            return None
        text = ""
        for c in el:
            if _strip(c.tag) == "contents":
                text = "".join(c.itertext())
        return page.add_freetext_annot(rect, text, fontsize=11, text_color=hex_to_rgb(el.get("color") or "#000000"))
    if tag in ("square", "circle"):
        if rect is None:
            return None
        a = page.add_rect_annot(rect) if tag == "square" else page.add_circle_annot(rect)
        w = _nums(el.get("width"))
        if w:
            a.set_border(width=w[0])
        return a
    if tag == "line":
        s, e = _nums(el.get("start")), _nums(el.get("end"))
        if len(s) < 2 or len(e) < 2:
            return None
        return page.add_line_annot(pdf_to_fitz_point(page, s[:2]), pdf_to_fitz_point(page, e[:2]))
    if tag == "ink":
        strokes = []
        for il in el:
            if _strip(il.tag) != "inklist":
                continue
            for g in il:
                pts = [p for p in (g.text or "").split(";") if p.strip()]
                stroke = []
                for p in pts:
                    xy = _nums(p)
                    if len(xy) >= 2:
                        stroke.append(tuple(pdf_to_fitz_point(page, xy[:2])))
                if len(stroke) > 1:
                    strokes.append(stroke)
        return page.add_ink_annot(strokes) if strokes else None
    if tag == "stamp":
        if rect is None:
            return None
        from .annotate import STANDARD_STAMPS  # noqa: PLC0415

        icon = el.get("icon") or "Draft"
        return page.add_stamp_annot(rect, stamp=STANDARD_STAMPS.get(icon, pymupdf.STAMP_Draft))
    return None


# ----------------------------------------------------------------- summary and list
STATUS_LABEL = {"Accepted": "Accepted", "Rejected": "Rejected", "Cancelled": "Cancelled", "Completed": "Completed", "None": "None"}


def _when(pdf_date: str) -> str:
    s = (pdf_date or "").replace("D:", "")
    if len(s) >= 12:
        return f"{s[6:8]}/{s[4:6]}/{s[0:4]} {s[8:10]}:{s[10:12]}"
    if len(s) >= 8:
        return f"{s[6:8]}/{s[4:6]}/{s[0:4]}"
    return ""


@method("annot.summary")
def summary(ctx, path: str, output: str, password: str | None = None, overwrite: bool = False,
            includePages: bool = True, title: str | None = None):
    """A PDF listing every comment page by page, with the page image beside it."""
    doc = open_pdf(path, password)
    try:
        tops = threads(_all_comments(doc))
        by_page: dict[int, list[dict]] = {}
        for c in tops:
            by_page.setdefault(c["page"], []).append(c)
        name = title or os.path.basename(path)
        archive = pymupdf.Archive()
        parts = [f"<h1>Summary of comments</h1><p class='sub'>{html.escape(name)} &middot; {len(tops)} comment(s)</p>"]
        if not tops:
            parts.append("<p>This document has no comments.</p>")
        total = len(by_page)
        for i, (pno, items) in enumerate(sorted(by_page.items()), 1):
            ctx.progress(i, total, f"Page {pno}")
            parts.append(f"<h2>Page {pno}</h2>")
            if includePages:
                pix = doc[pno - 1].get_pixmap(dpi=50, annots=True)
                img = f"p{pno}.png"
                archive.add(pix.tobytes("png"), img)
                parts.append(f"<p><img src='{img}' width='200'/></p>")
            parts.append("<ol>")
            for c in sorted(items, key=lambda c: (-(c["rect"][3]), c["rect"][0])):
                head = f"<b>{html.escape(c.get('typeName', ''))}</b>"
                if c.get("author"):
                    head += f" &middot; {html.escape(c['author'])}"
                if c.get("modified") or c.get("created"):
                    head += f" &middot; {_when(c.get('modified') or c.get('created'))}"
                if c.get("status") and c["status"]["state"] not in (None, "None"):
                    head += f" &middot; <i>{html.escape(STATUS_LABEL.get(c['status']['state'], c['status']['state']))}</i>"
                body = html.escape(c.get("contents") or "").replace("\n", "<br/>")
                reps = "".join(f"<p class='rep'>Reply from {html.escape(r.get('author') or 'unknown')}: {html.escape(r.get('contents') or '')}</p>" for r in c["replies"])
                parts.append(f"<li><p>{head}</p>{f'<p>{body}</p>' if body else ''}{reps}</li>")
            parts.append("</ol>")
        css = ("body{font-family:sans-serif;font-size:10pt} h1{font-size:16pt;margin:0} .sub{color:#555}"
               "h2{font-size:12pt;border-bottom:1px solid #999;margin-top:14pt} li{margin-bottom:6pt} .rep{margin-left:14pt;color:#333}")
        story = pymupdf.Story(html="<html><body>" + "".join(parts) + "</body></html>", user_css=css, archive=archive)
        out = OutputFile(ctx, output, overwrite, sources=[path])
        writer = pymupdf.DocumentWriter(out.temp)
        mediabox = pymupdf.paper_rect("a4")
        where = mediabox + (40, 40, -40, -40)
        more = True
        pages = 0
        while more:
            dev = writer.begin_page(mediabox)
            more, _ = story.place(where)
            story.draw(dev)
            writer.end_page()
            pages += 1
            if pages > 2000:
                break
        writer.close()
    finally:
        doc.close()
    final = out.commit(validate_pdf())
    return {"output": final, "comments": len(tops), "pages": pages}


@method("annot.exportList")
def export_list(ctx, path: str, output: str, password: str | None = None, overwrite: bool = False, format: str = "csv"):
    """The comments as a spreadsheet-friendly CSV (one row per comment or reply)."""
    if format != "csv":
        raise invalid("Only CSV is supported.")
    doc = open_pdf(path, password)
    try:
        tops = threads(_all_comments(doc))
    finally:
        doc.close()
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["Page", "Type", "Author", "Date", "Status", "Comment", "Reply to"])
    rows = 0
    for c in sorted(tops, key=lambda c: (c["page"], -c["rect"][3])):
        st = (c.get("status") or {}).get("state") or ""
        w.writerow([c["page"], c.get("typeName", ""), c.get("author", ""), _when(c.get("modified") or c.get("created")),
                    "" if st == "None" else st, c.get("contents", ""), ""])
        rows += 1
        for r in c["replies"]:
            w.writerow([c["page"], "Reply", r.get("author", ""), _when(r.get("modified") or r.get("created")), "", r.get("contents", ""),
                        (c.get("contents") or c.get("typeName", ""))[:60]])
            rows += 1
    out = OutputFile(ctx, output, overwrite, sources=[path])
    with open(out.temp, "w", encoding="utf-8-sig", newline="") as f:
        f.write(buf.getvalue())
    final = out.commit()
    return {"output": final, "rows": rows}
