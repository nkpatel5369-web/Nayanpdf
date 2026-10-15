"""Page management: merge, split, extract, delete, rotate, reorder, insert,
replace, duplicate, interleave. Every output is reopened and its page count
and page order are verified against the plan before success is reported."""

from __future__ import annotations

import hashlib
import os

import pymupdf

from ..errors import VALIDATION_FAILED, EngineError, invalid
from ..registry import method
from ..util.io import OutputFile, apply_name_template, derive_output, validate_pdf
from ..util.pdf import open_pdf, parse_pages, save_doc

ROTATIONS = (0, 90, 180, 270, -90, -180, -270)


def _fingerprint(page: pymupdf.Page) -> str:
    """Content fingerprint used to verify page order after processing."""
    h = hashlib.sha1()
    h.update(page.get_text("text").encode("utf-8", "replace")[:4000])
    h.update(str(len(page.get_images(full=False))).encode())
    mb = page.mediabox
    h.update(f"{round(mb.width)}x{round(mb.height)}".encode())
    return h.hexdigest()


def _verify_order(out_path: str, expected: list[str | None], password: str | None = None) -> bool:
    doc = pymupdf.open(out_path)
    try:
        if password and doc.needs_pass:
            doc.authenticate(password)
        if doc.page_count != len(expected):
            raise EngineError(VALIDATION_FAILED, f"Output has {doc.page_count} pages; expected {len(expected)}.")
        for i, fp in enumerate(expected):
            if fp is None:
                continue
            if _fingerprint(doc[i]) != fp:
                raise EngineError(VALIDATION_FAILED, f"Page {i + 1} of the output does not match the intended sequence.")
        return True
    finally:
        doc.close()


def _copy_toc(dst_toc: list, src_doc, offset: int, level_shift: int = 0, keep_pages: dict | None = None):
    for lvl, title, pno, *rest in src_doc.get_toc(simple=False):
        if keep_pages is not None:
            if pno - 1 not in keep_pages:
                continue
            pno = keep_pages[pno - 1] + 1
        else:
            pno = pno + offset
        dst_toc.append([lvl + level_shift, title, pno])


def _fix_toc_levels(toc: list) -> list:
    """PyMuPDF requires each level to increase by at most 1."""
    out = []
    prev = 0
    for lvl, title, pno in toc:
        lvl = max(1, min(lvl, prev + 1))
        out.append([lvl, title, max(1, pno)])
        prev = lvl
    return out


@method("pages.merge")
def merge(ctx, files: list, output: str, overwrite: bool = False, bookmarksPerFile: bool = True,
          keepBookmarks: bool = True):
    if not files or len(files) < 1:
        raise invalid("Select at least one file to merge.")
    out = OutputFile(ctx, output, overwrite, sources=[f["path"] for f in files])
    result = pymupdf.open()
    toc: list = []
    expected: list[str] = []
    total = len(files)
    for idx, f in enumerate(files):
        ctx.progress(idx, total, os.path.basename(f["path"]))
        src = open_pdf(f["path"], f.get("password"), require=["assemble"])
        try:
            sel = parse_pages(f.get("pages"), src.page_count)
            start = result.page_count
            if bookmarksPerFile:
                toc.append([1, os.path.splitext(os.path.basename(f["path"]))[0], start + 1])
            if sel == list(range(src.page_count)):
                result.insert_pdf(src)
                mapping = {i: start + i for i in range(src.page_count)}
            else:
                mapping = {}
                for k, pno in enumerate(sel):
                    result.insert_pdf(src, from_page=pno, to_page=pno)
                    mapping.setdefault(pno, start + k)
            for pno in sel:
                expected.append(_fingerprint(src[pno]))
            if keepBookmarks:
                _copy_toc(toc, src, 0, 1 if bookmarksPerFile else 0, keep_pages=mapping)
        finally:
            src.close()
    ctx.progress(total, total, "Saving")
    if toc:
        result.set_toc(_fix_toc_levels(toc))
    result.save(out.temp, garbage=3, deflate=True, use_objstms=1)
    result.close()
    _verify_order(out.temp, expected)
    final = out.commit(validate_pdf(expected_pages=len(expected)))
    return {"output": final, "pageCount": len(expected), "orderVerified": True}


@method("pages.split")
def split(ctx, path: str, outputDir: str | None = None, mode: str = "every", every: int = 1,
          ranges: list | None = None, nameTemplate: str | None = None, password: str | None = None,
          overwrite: bool = False):
    src = open_pdf(path, password, require=["assemble"])
    try:
        n = src.page_count
        groups: list[tuple[str, list[int]]] = []
        if mode == "every":
            every = int(every)
            if every < 1:
                raise invalid("Pages per file must be at least 1.")
            for k, s in enumerate(range(0, n, every)):
                groups.append((f"part{k + 1}", list(range(s, min(n, s + every)))))
        elif mode == "single":
            groups = [(f"page_{i + 1:0{len(str(n))}d}", [i]) for i in range(n)]
        elif mode == "ranges":
            if not ranges:
                raise invalid("Enter at least one page range.")
            for k, r in enumerate(ranges):
                groups.append((f"part{k + 1}", parse_pages(r, n)))
        elif mode == "oddEven":
            groups = [("odd", list(range(0, n, 2)))]
            if n > 1:
                groups.append(("even", list(range(1, n, 2))))
        elif mode == "bookmarks":
            toc = [t for t in src.get_toc(simple=True) if t[0] == 1 and t[2] >= 1]
            if not toc:
                raise invalid("This document has no top-level bookmarks to split by.")
            starts = sorted({t[2] - 1 for t in toc})
            if starts[0] != 0:
                starts = [0] + starts
            titles = {t[2] - 1: t[1] for t in toc}
            for k, s in enumerate(starts):
                e = starts[k + 1] if k + 1 < len(starts) else n
                title = titles.get(s, f"part{k + 1}")
                groups.append((f"{k + 1:02d}_{title}", list(range(s, e))))
        else:
            raise invalid(f"Unknown split mode: {mode}")

        outputs = []
        total = len(groups)
        stem_template = nameTemplate or "{name}_{part}"
        for k, (label, pages) in enumerate(groups):
            ctx.progress(k, total, f"Writing {label}")
            name = apply_name_template(stem_template, path, part=label, n=k + 1)
            target = derive_output(path, outputDir, "", ".pdf", name=name)
            out = OutputFile(ctx, target, overwrite, sources=[path])
            doc = pymupdf.open()
            _insert_selection(doc, src, pages)
            doc.save(out.temp, garbage=3, deflate=True, use_objstms=1)
            doc.close()
            _verify_order(out.temp, [_fingerprint(src[i]) for i in pages])
            final = out.commit(validate_pdf(expected_pages=len(pages)))
            outputs.append({"output": final, "pages": [p + 1 for p in pages], "pageCount": len(pages)})
        ctx.progress(total, total, "Done")
        return {"outputs": outputs, "count": len(outputs), "orderVerified": True}
    finally:
        src.close()


def _insert_selection(dst, src, pages: list[int]):
    """Insert pages preserving runs (faster and keeps links between pages in a run)."""
    i = 0
    while i < len(pages):
        j = i
        while j + 1 < len(pages) and pages[j + 1] == pages[j] + 1:
            j += 1
        dst.insert_pdf(src, from_page=pages[i], to_page=pages[j])
        i = j + 1


def _save_selection(ctx, path, password, pages, output, overwrite, rotate_map=None):
    src = open_pdf(path, password, require=["assemble"])
    try:
        expected = [_fingerprint(src[i]) for i in pages]
        out = OutputFile(ctx, output, overwrite, sources=[path])
        src.select(pages)
        if rotate_map:
            for i, page in enumerate(src):
                rot = rotate_map.get(i)
                if rot:
                    page.set_rotation((page.rotation + rot) % 360)
        save_doc(src, out.temp)
    finally:
        src.close()
    _verify_order(out.temp, expected, password)
    final = out.commit(validate_pdf(expected_pages=len(pages), password=password))
    return {"output": final, "pageCount": len(pages), "orderVerified": True}


@method("pages.extract")
def extract(ctx, path: str, pages, output: str | None = None, outputDir: str | None = None,
            separate: bool = False, password: str | None = None, overwrite: bool = False):
    probe = open_pdf(path, password, require=["assemble"])
    n = probe.page_count
    probe.close()
    sel = parse_pages(pages, n, default_all=False)
    if separate:
        outputs = []
        for k, p in enumerate(sel):
            ctx.progress(k, len(sel))
            target = derive_output(path, outputDir, f"_page_{p + 1}", ".pdf")
            outputs.append(_save_selection(ctx, path, password, [p], target, overwrite))
        return {"outputs": outputs, "count": len(outputs)}
    output = output or derive_output(path, outputDir, "_extracted", ".pdf")
    return _save_selection(ctx, path, password, sel, output, overwrite)


@method("pages.delete")
def delete(ctx, path: str, pages, output: str, password: str | None = None, overwrite: bool = False):
    probe = open_pdf(path, password, require=["assemble"])
    n = probe.page_count
    probe.close()
    remove = set(parse_pages(pages, n, default_all=False))
    keep = [i for i in range(n) if i not in remove]
    if not keep:
        raise invalid("You cannot delete every page of the document.")
    return _save_selection(ctx, path, password, keep, output, overwrite)


@method("pages.rotate")
def rotate(ctx, path: str, pages, angle: int, output: str, password: str | None = None,
           overwrite: bool = False):
    angle = int(angle)
    if angle not in ROTATIONS or angle == 0:
        raise invalid("Rotation must be 90, 180 or 270 degrees.")
    probe = open_pdf(path, password, require=["assemble"])
    n = probe.page_count
    probe.close()
    sel = set(parse_pages(pages, n))
    return _save_selection(ctx, path, password, list(range(n)), output, overwrite,
                           rotate_map={i: angle % 360 for i in sel})


@method("pages.reverse")
def reverse(ctx, path: str, output: str, password: str | None = None, overwrite: bool = False):
    probe = open_pdf(path, password)
    n = probe.page_count
    probe.close()
    return _save_selection(ctx, path, password, list(range(n - 1, -1, -1)), output, overwrite)


@method("pages.assemble")
def assemble(ctx, sources: dict, plan: list, output: str, overwrite: bool = False,
             keepBookmarks: bool = True):
    """Build a document from an explicit page plan.

    sources: {"A": {"path":..., "password":...}, "B": {...}}
    plan:    [{"src": "A", "page": 3, "rotate": 90}, {"blank": true, "width": 595, "height": 842}, ...]
    """
    if not plan:
        raise invalid("The page plan is empty.")
    docs = {}
    try:
        for key, s in sources.items():
            docs[key] = open_pdf(s["path"], s.get("password"), require=["assemble"])
        for item in plan:
            if item.get("blank"):
                continue
            d = docs.get(item.get("src"))
            if d is None:
                raise invalid(f"Unknown source '{item.get('src')}' in page plan.")
            pg = int(item.get("page", 0))
            if pg < 1 or pg > d.page_count:
                raise invalid(f"Page {pg} does not exist in {os.path.basename(sources[item['src']]['path'])}.")
        primary_key = next(iter(sources))
        only_primary = all(it.get("blank") or it.get("src") == primary_key for it in plan)
        expected: list[str | None] = []
        out = OutputFile(ctx, output, overwrite, sources=[s["path"] for s in sources.values()])
        total = len(plan)

        if only_primary:
            # Reorder in place: keeps form fields, links and annotations intact.
            src = docs[primary_key]
            order = [int(it["page"]) - 1 for it in plan if not it.get("blank")]
            fps = {i: _fingerprint(src[i]) for i in set(order)}
            src.select(order)
            # Insert blanks at their positions.
            pos = 0
            for it in plan:
                if it.get("blank"):
                    ref = src[min(pos, src.page_count - 1)] if src.page_count else None
                    w = float(it.get("width") or (ref.rect.width if ref else 595.28))
                    h = float(it.get("height") or (ref.rect.height if ref else 841.89))
                    src.new_page(pno=pos, width=w, height=h)
                pos += 1
            for k, it in enumerate(plan):
                rot = int(it.get("rotate") or 0) % 360
                if rot:
                    pg = src[k]
                    pg.set_rotation((pg.rotation + rot) % 360)
            for it in plan:
                expected.append(None if it.get("blank") else fps[int(it["page"]) - 1])
            ctx.progress(total, total, "Saving")
            save_doc(src, out.temp)
        else:
            result = pymupdf.open()
            toc = []
            for k, it in enumerate(plan):
                if k % 10 == 0:
                    ctx.progress(k, total)
                if it.get("blank"):
                    ref = result[-1] if result.page_count else None
                    w = float(it.get("width") or (ref.rect.width if ref else 595.28))
                    h = float(it.get("height") or (ref.rect.height if ref else 841.89))
                    result.new_page(width=w, height=h)
                    expected.append(None)
                    continue
                d = docs[it["src"]]
                pno = int(it["page"]) - 1
                result.insert_pdf(d, from_page=pno, to_page=pno)
                rot = int(it.get("rotate") or 0) % 360
                if rot:
                    pg = result[-1]
                    pg.set_rotation((pg.rotation + rot) % 360)
                expected.append(_fingerprint(d[pno]))
            if keepBookmarks:
                for key, d in docs.items():
                    mapping = {}
                    for k, it in enumerate(plan):
                        if it.get("src") == key and not it.get("blank"):
                            mapping.setdefault(int(it["page"]) - 1, k)
                    _copy_toc(toc, d, 0, 0, keep_pages=mapping)
                if toc:
                    toc.sort(key=lambda t: t[2])
                    result.set_toc(_fix_toc_levels(toc))
            ctx.progress(total, total, "Saving")
            result.save(out.temp, garbage=3, deflate=True, use_objstms=1)
            result.close()
        pw = sources[primary_key].get("password") if only_primary else None
        _verify_order(out.temp, expected, pw)
        final = out.commit(validate_pdf(expected_pages=len(plan), password=pw))
        return {"output": final, "pageCount": len(plan), "orderVerified": True}
    finally:
        for d in docs.values():
            d.close()


@method("pages.insertBlank")
def insert_blank(ctx, path: str, after: int, count: int, output: str, width: float | None = None,
                 height: float | None = None, password: str | None = None, overwrite: bool = False):
    probe = open_pdf(path, password)
    n = probe.page_count
    probe.close()
    if after < 0 or after > n:
        raise invalid("Insert position is out of range.")
    plan = [{"src": "A", "page": i + 1} for i in range(n)]
    blanks = [{"blank": True, "width": width, "height": height} for _ in range(max(1, int(count)))]
    plan = plan[:after] + blanks + plan[after:]
    return assemble(ctx, {"A": {"path": path, "password": password}}, plan, output, overwrite)


@method("pages.insertFromFile")
def insert_from_file(ctx, path: str, insertPath: str, after: int, output: str, insertPages=None,
                     password: str | None = None, insertPassword: str | None = None, overwrite: bool = False):
    a = open_pdf(path, password)
    na = a.page_count
    a.close()
    b = open_pdf(insertPath, insertPassword)
    sel = parse_pages(insertPages, b.page_count)
    b.close()
    if after < 0 or after > na:
        raise invalid("Insert position is out of range.")
    plan = [{"src": "A", "page": i + 1} for i in range(na)]
    ins = [{"src": "B", "page": p + 1} for p in sel]
    plan = plan[:after] + ins + plan[after:]
    return assemble(ctx, {"A": {"path": path, "password": password},
                          "B": {"path": insertPath, "password": insertPassword}}, plan, output, overwrite)


@method("pages.replace")
def replace(ctx, path: str, pages, replacementPath: str, output: str, replacementPages=None,
            password: str | None = None, replacementPassword: str | None = None, overwrite: bool = False):
    a = open_pdf(path, password)
    na = a.page_count
    a.close()
    b = open_pdf(replacementPath, replacementPassword)
    rsel = parse_pages(replacementPages, b.page_count)
    b.close()
    targets = sorted(set(parse_pages(pages, na, default_all=False)))
    if len(rsel) != len(targets):
        raise invalid(f"You selected {len(targets)} page(s) to replace but {len(rsel)} replacement page(s).")
    mapping = dict(zip(targets, rsel))
    plan = []
    for i in range(na):
        if i in mapping:
            plan.append({"src": "B", "page": mapping[i] + 1})
        else:
            plan.append({"src": "A", "page": i + 1})
    return assemble(ctx, {"A": {"path": path, "password": password},
                          "B": {"path": replacementPath, "password": replacementPassword}}, plan, output, overwrite)


@method("pages.duplicate")
def duplicate(ctx, path: str, pages, output: str, copies: int = 1, password: str | None = None,
              overwrite: bool = False):
    a = open_pdf(path, password)
    n = a.page_count
    a.close()
    dup = set(parse_pages(pages, n, default_all=False))
    plan = []
    for i in range(n):
        plan.append({"src": "A", "page": i + 1})
        if i in dup:
            plan.extend({"src": "A", "page": i + 1} for _ in range(max(1, int(copies))))
    return assemble(ctx, {"A": {"path": path, "password": password}}, plan, output, overwrite)


@method("pages.interleave")
def interleave(ctx, pathA: str, pathB: str, output: str, reverseB: bool = False,
               passwordA: str | None = None, passwordB: str | None = None, overwrite: bool = False):
    a = open_pdf(pathA, passwordA)
    na = a.page_count
    a.close()
    b = open_pdf(pathB, passwordB)
    nb = b.page_count
    b.close()
    bseq = list(range(nb))
    if reverseB:
        bseq.reverse()
    plan = []
    for k in range(max(na, nb)):
        if k < na:
            plan.append({"src": "A", "page": k + 1})
        if k < nb:
            plan.append({"src": "B", "page": bseq[k] + 1})
    return assemble(ctx, {"A": {"path": pathA, "password": passwordA},
                          "B": {"path": pathB, "password": passwordB}}, plan, output, overwrite,
                    keepBookmarks=False)


@method("pages.thumbnail")
def thumbnail(ctx, path: str, page: int, width: int = 160, password: str | None = None, annots: str = "all",
              grayscale: bool = False):
    """PNG thumbnail as base64 (merge lists, print preview).

    annots/grayscale mirror print.prepare so the print preview matches the printout.
    """
    import base64

    doc = open_pdf(path, password)
    try:
        if page < 1 or page > doc.page_count:
            raise invalid("Page out of range.")
        p = doc[page - 1]
        if annots == "forms":
            for a in p.annots():
                if a.type[0] != pymupdf.PDF_ANNOT_WIDGET:
                    a.set_flags(a.flags | pymupdf.PDF_ANNOT_IS_HIDDEN)
                    a.update()
        zoom = max(0.05, min(4.0, width / max(1, p.rect.width)))
        pix = p.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), alpha=False, annots=annots != "none",
                           colorspace=pymupdf.csGRAY if grayscale else pymupdf.csRGB)
        return {"png": base64.b64encode(pix.tobytes("png")).decode("ascii"), "width": pix.width,
                "height": pix.height, "pageCount": doc.page_count}
    finally:
        doc.close()
