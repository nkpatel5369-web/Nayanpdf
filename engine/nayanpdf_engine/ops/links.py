"""Links: list, add and remove.

A link either goes to a page of the same document or holds a web address.
X PDF never follows web addresses itself (it has no network access); they
are stored for readers who open the file elsewhere.
"""

from __future__ import annotations

import pymupdf

from ..errors import invalid
from ..registry import method
from ..util.geometry import fitz_to_pdf_rect, to_fitz_rect
from ..util.io import OutputFile, validate_pdf
from ..util.pdf import open_pdf, save_doc

KINDS = {pymupdf.LINK_GOTO: "page", pymupdf.LINK_URI: "uri", pymupdf.LINK_NAMED: "named",
         pymupdf.LINK_GOTOR: "file", pymupdf.LINK_LAUNCH: "launch"}


def _describe(page: pymupdf.Page, i: int, l: dict) -> dict:
    d = {"page": page.number + 1, "index": i, "rect": fitz_to_pdf_rect(page, l["from"]),
         "kind": KINDS.get(l.get("kind"), "other")}
    if l.get("kind") == pymupdf.LINK_GOTO:
        d["targetPage"] = int(l.get("page", 0)) + 1
    if l.get("uri"):
        d["uri"] = l["uri"]
    if l.get("file"):
        d["file"] = l["file"]
    return d


@method("links.list")
def list_links(ctx, path: str, password: str | None = None, page: int | None = None):
    doc = open_pdf(path, password)
    try:
        pages = [page - 1] if page else range(doc.page_count)
        out = []
        for n in pages:
            p = doc[n]
            for i, l in enumerate(p.get_links()):
                out.append(_describe(p, i, l))
        return {"links": out}
    finally:
        doc.close()


def _valid_uri(u: str) -> str:
    u = (u or "").strip()
    if not u:
        raise invalid("Enter a web address.")
    low = u.lower()
    if low.startswith(("javascript:", "file:", "data:")):
        raise invalid("Only web (http/https) and e-mail (mailto) addresses are allowed in links.")
    if not low.startswith(("http://", "https://", "mailto:")):
        u = "https://" + u
    return u


@method("links.apply")
def apply_links(ctx, path: str, output: str, ops: list, password: str | None = None, overwrite: bool = False,
                space: str = "pdf"):
    """add:    {"op": "add", "page": 1, "rect": [...], "toPage": 3}  or  {"uri": "https://..."}
    delete: {"op": "delete", "page": 1, "index": 0}
    """
    if not ops:
        raise invalid("No link changes to apply.")
    doc = open_pdf(path, password, require=["modify"])
    try:
        # Deletions by index are done from the highest index down so indexes stay valid.
        for op in sorted(ops, key=lambda o: (o.get("op") != "delete", -int(o.get("index", 0)))):
            pno = int(op.get("page", 0))
            if pno < 1 or pno > doc.page_count:
                raise invalid("Page out of range.")
            page = doc[pno - 1]
            if op.get("op", "add") == "add":
                rect = to_fitz_rect(page, op["rect"], space)
                if rect.width < 2 or rect.height < 2:
                    raise invalid("Draw a larger box for the link.")
                if op.get("toPage") is not None:
                    tp = int(op["toPage"])
                    if tp < 1 or tp > doc.page_count:
                        raise invalid(f"The document has no page {tp}.")
                    page.insert_link({"kind": pymupdf.LINK_GOTO, "from": rect, "page": tp - 1, "to": pymupdf.Point(0, 0), "zoom": 0})
                else:
                    page.insert_link({"kind": pymupdf.LINK_URI, "from": rect, "uri": _valid_uri(op.get("uri", ""))})
            elif op.get("op") == "delete":
                links = page.get_links()
                i = int(op.get("index", -1))
                if i < 0 or i >= len(links):
                    raise invalid("The link no longer exists.")
                page.delete_link(links[i])
            else:
                raise invalid(f"Unknown link operation: {op.get('op')}")
        out = OutputFile(ctx, output, overwrite, sources=[path])
        save_doc(doc, out.temp, garbage=1)
    finally:
        doc.close()
    final = out.commit(validate_pdf(password=password))
    return {"output": final}
