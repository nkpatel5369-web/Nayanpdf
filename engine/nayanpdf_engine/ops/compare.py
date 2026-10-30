"""Document comparison.

Two complementary methods:
  * text comparison - pages are aligned by content, then words are diffed
    (added / removed / modified, with locations on both documents);
  * visual comparison - aligned pages are rendered and pixel differences are
    grouped into regions (catches image, layout and graphics changes that
    text extraction cannot see).
Plus page-count, page-order, embedded-image and metadata differences.
Neither method is exhaustive: e.g. changes in invisible objects, JavaScript
or form logic are not detected. The result says which methods ran.
"""

from __future__ import annotations

import difflib
import hashlib

import pymupdf

from ..registry import method
from ..util.geometry import fitz_to_pdf_rect
from ..util.io import OutputFile, validate_pdf
from ..util.pdf import open_pdf


def _page_sig(page) -> str:
    t = " ".join(page.get_text("text").split())
    if not t:
        pix = page.get_pixmap(dpi=12, colorspace=pymupdf.csGRAY, alpha=False)
        return "img:" + hashlib.sha1(pix.samples).hexdigest()
    return "txt:" + hashlib.sha1(t.encode("utf-8", "replace")).hexdigest()


def _page_text(page) -> str:
    return " ".join(page.get_text("text").split())


def _align_pages(a, b):
    """Return list of (ia | None, ib | None) pairs aligning pages of a and b."""
    ta = [_page_text(p) for p in a]
    tb = [_page_text(p) for p in b]
    sa = [_page_sig(p) for p in a]
    sb = [_page_sig(p) for p in b]
    sm = difflib.SequenceMatcher(a=sa, b=sb, autojunk=False)
    pairs = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            pairs.extend((i1 + k, j1 + k) for k in range(i2 - i1))
        elif tag == "replace":
            # Pair modified pages by best text similarity, in order.
            ia = list(range(i1, i2))
            ib = list(range(j1, j2))
            while ia and ib:
                if len(ia) == len(ib):
                    pairs.append((ia.pop(0), ib.pop(0)))
                    continue
                r_same = difflib.SequenceMatcher(None, ta[ia[0]][:3000], tb[ib[0]][:3000]).quick_ratio()
                r_skip_b = difflib.SequenceMatcher(None, ta[ia[0]][:3000], tb[ib[1]][:3000]).quick_ratio() if len(ib) > 1 else 0
                r_skip_a = difflib.SequenceMatcher(None, ta[ia[1]][:3000], tb[ib[0]][:3000]).quick_ratio() if len(ia) > 1 else 0
                if r_same >= max(r_skip_a, r_skip_b):
                    pairs.append((ia.pop(0), ib.pop(0)))
                elif r_skip_b > r_skip_a:
                    pairs.append((None, ib.pop(0)))
                else:
                    pairs.append((ia.pop(0), None))
            pairs.extend((i, None) for i in ia)
            pairs.extend((None, j) for j in ib)
        elif tag == "delete":
            pairs.extend((i, None) for i in range(i1, i2))
        elif tag == "insert":
            pairs.extend((None, j) for j in range(j1, j2))
    return pairs, sa, sb


def _union(rects):
    r = None
    for x in rects:
        r = pymupdf.Rect(x) if r is None else r | pymupdf.Rect(x)
    return r


def _rel(page, r) -> list[float]:
    """Rect relative to the displayed page (0..1), for drawing on page images."""
    v = pymupdf.Rect(r) * page.rotation_matrix
    v.normalize()
    W, H = page.rect.width or 1, page.rect.height or 1
    return [round(v.x0 / W, 5), round(v.y0 / H, 5), round(v.x1 / W, 5), round(v.y1 / H, 5)]


def _word_diffs(pa, pb):
    wa = pa.get_text("words", sort=True)
    wb = pb.get_text("words", sort=True)
    sa = [w[4] for w in wa]
    sb = [w[4] for w in wb]
    sm = difflib.SequenceMatcher(a=sa, b=sb, autojunk=False)
    out = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            continue
        ra = _line_rects([w[:4] for w in wa[i1:i2]]) if i2 > i1 else []
        rb = _line_rects([w[:4] for w in wb[j1:j2]]) if j2 > j1 else []
        kind = {"replace": "modified", "delete": "removed", "insert": "added"}[tag]
        out.append({"type": kind, "textA": " ".join(sa[i1:i2]), "textB": " ".join(sb[j1:j2]),
                    "rectsA": [fitz_to_pdf_rect(pa, r) for r in ra], "rectsB": [fitz_to_pdf_rect(pb, r) for r in rb],
                    "relA": [_rel(pa, r) for r in ra], "relB": [_rel(pb, r) for r in rb]})
    return out


def _line_rects(rects):
    """Merge word rects into one rect per text line."""
    lines = []
    for r in rects:
        r = pymupdf.Rect(r)
        for i, L in enumerate(lines):
            if abs(L.y0 - r.y0) < 2 and abs(L.y1 - r.y1) < 2:
                lines[i] = L | r
                break
        else:
            lines.append(r)
    return lines


def _visual_diffs(pa, pb, dpi: int = 60, threshold: int = 40):
    import numpy as np

    za = pa.get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY, alpha=False)
    zb = pb.get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY, alpha=False)
    if (za.width, za.height) != (zb.width, zb.height):
        return [{"type": "layout", "detail": "Page size differs"}], True
    a = np.frombuffer(za.samples, dtype=np.uint8).reshape(za.height, za.width).astype(np.int16)
    b = np.frombuffer(zb.samples, dtype=np.uint8).reshape(zb.height, zb.width).astype(np.int16)
    mask = (np.abs(a - b) > threshold).astype(np.uint8)
    if mask.sum() == 0:
        return [], False
    regions = []
    try:
        import cv2

        k = np.ones((7, 7), np.uint8)
        dil = cv2.dilate(mask * 255, k, iterations=2)
        contours, _ = cv2.findContours(dil, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for c in contours:
            x, y, w, h = cv2.boundingRect(c)
            if w * h < 9:
                continue
            regions.append((x, y, x + w, y + h))
    except ImportError:
        ys, xs = np.nonzero(mask)
        regions.append((int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1))
    scale = 72.0 / dpi
    out = []
    # Region coordinates are in the rendered (rotated) page; map back to unrotated fitz space.
    for x0, y0, x1, y1 in regions[:200]:
        r = pymupdf.Rect(x0 * scale, y0 * scale, x1 * scale, y1 * scale) * pa.derotation_matrix
        r.normalize()
        out.append({"type": "visual", "rectsA": [fitz_to_pdf_rect(pa, r)], "rectsB": [fitz_to_pdf_rect(pb, r)],
                    "relA": [_rel(pa, r)], "relB": [_rel(pb, r)]})
    return out, False


def _image_hashes(page):
    try:
        return sorted(i.get("digest", b"").hex() for i in page.get_image_info(hashes=True))
    except Exception:  # noqa: BLE001
        return []


@method("compare.run")
def run(ctx, pathA: str, pathB: str, passwordA: str | None = None, passwordB: str | None = None,
        text: bool = True, visual: bool = True, visualDpi: int = 60):
    a = open_pdf(pathA, passwordA)
    b = open_pdf(pathB, passwordB)
    try:
        diffs = []
        ctx.status("Aligning pages")
        pairs, sa, sb = _align_pages(a, b)
        # Page order changes: identical pages appearing at different relative positions.
        moved = []
        pos_b = {}
        for j, s in enumerate(sb):
            pos_b.setdefault(s, []).append(j)
        for ia, ib in pairs:
            if ia is not None and ib is None and sa[ia] in pos_b:
                moved.append((ia, pos_b[sa[ia]][0]))
        moved_a = {m[0] for m in moved}
        moved_b = {m[1] for m in moved}
        for ia, ib in moved:
            diffs.append({"type": "page-moved", "pageA": ia + 1, "pageB": ib + 1,
                          "detail": f"Page {ia + 1} of A appears as page {ib + 1} of B"})
        total = len(pairs)
        for k, (ia, ib) in enumerate(pairs):
            ctx.progress(k, total, "Comparing pages")
            if ia is None and ib is not None:
                if ib not in moved_b:
                    diffs.append({"type": "page-added", "pageB": ib + 1, "detail": f"Page {ib + 1} exists only in B"})
                continue
            if ib is None and ia is not None:
                if ia not in moved_a:
                    diffs.append({"type": "page-removed", "pageA": ia + 1, "detail": f"Page {ia + 1} exists only in A"})
                continue
            pa, pb = a[ia], b[ib]
            text_diffs = _word_diffs(pa, pb) if text else []
            for d in text_diffs:
                d.update(pageA=ia + 1, pageB=ib + 1)
                diffs.append(d)
            if visual:
                vd, size_changed = _visual_diffs(pa, pb, dpi=int(visualDpi))
                text_areas = [pymupdf.Rect(r) for d in text_diffs for r in d.get("rectsA", []) + d.get("rectsB", [])]
                for d in vd:
                    d.update(pageA=ia + 1, pageB=ib + 1)
                    if d["type"] == "visual":
                        r = pymupdf.Rect(d["rectsA"][0])
                        if any(r.intersects(t + (-3, -3, 3, 3)) for t in text_areas):
                            continue  # already explained by a text change
                    diffs.append(d)
            ha, hb = _image_hashes(pa), _image_hashes(pb)
            if ha != hb:
                diffs.append({"type": "image", "pageA": ia + 1, "pageB": ib + 1,
                              "detail": f"Embedded images differ ({len(ha)} in A, {len(hb)} in B)"})
            if abs(pa.rect.width - pb.rect.width) > 1 or abs(pa.rect.height - pb.rect.height) > 1 or pa.rotation != pb.rotation:
                diffs.append({"type": "layout", "pageA": ia + 1, "pageB": ib + 1,
                              "detail": "Page size or rotation differs"})
        ma, mb = a.metadata or {}, b.metadata or {}
        for key in ("title", "author", "subject", "keywords", "creator", "producer", "creationDate", "modDate"):
            if (ma.get(key) or "") != (mb.get(key) or ""):
                diffs.append({"type": "metadata", "field": key, "textA": ma.get(key) or "", "textB": mb.get(key) or ""})
        for i, d in enumerate(diffs):
            d["id"] = i + 1
        summary = {}
        for d in diffs:
            summary[d["type"]] = summary.get(d["type"], 0) + 1
        return {"pageCountA": a.page_count, "pageCountB": b.page_count, "differences": diffs, "summary": summary,
                "identical": not diffs, "methods": [m for m, on in (("text", text), ("visual", visual)) if on] + ["images", "metadata", "page-order"],
                "pairs": [[x + 1 if x is not None else None, y + 1 if y is not None else None] for x, y in pairs]}
    finally:
        a.close()
        b.close()


@method("compare.report")
def report(ctx, pathA: str, pathB: str, output: str, passwordA: str | None = None, passwordB: str | None = None,
           overwrite: bool = False, text: bool = True, visual: bool = True):
    """Side-by-side comparison report PDF with highlighted differences."""
    res = run(ctx, pathA, pathB, passwordA, passwordB, text=text, visual=visual)
    a = open_pdf(pathA, passwordA)
    b = open_pdf(pathB, passwordB)
    out_doc = pymupdf.open()
    colors = {"added": (0.17, 0.62, 0.29), "removed": (0.86, 0.19, 0.19), "modified": (0.93, 0.55, 0.0),
              "visual": (0.11, 0.49, 0.84), "layout": (0.5, 0.3, 0.8), "image": (0.11, 0.49, 0.84)}
    by_pair: dict = {}
    for d in res["differences"]:
        by_pair.setdefault((d.get("pageA"), d.get("pageB")), []).append(d)
    # Summary page
    sp = out_doc.new_page(width=842, height=595)
    import os

    lines = [f"N PDF comparison report", "", f"A: {os.path.basename(pathA)} ({res['pageCountA']} pages)",
             f"B: {os.path.basename(pathB)} ({res['pageCountB']} pages)", "",
             "Differences found: " + (", ".join(f"{k}: {v}" for k, v in res["summary"].items()) or "none"),
             "Methods: " + ", ".join(res["methods"]),
             "", "Colour key: green = added in B, red = removed from A, orange = modified, blue = visual/image change.",
             "This report highlights detected differences; it is not a guarantee that no other changes exist."]
    sp.insert_textbox(pymupdf.Rect(40, 40, 800, 560), "\n".join(lines), fontsize=12, fontname="helv")
    try:
        total = len(res["pairs"])
        for k, (pa_no, pb_no) in enumerate(res["pairs"]):
            ctx.progress(k, total, "Building report")
            page = out_doc.new_page(width=842, height=595)
            half = pymupdf.Rect(20, 40, 416, 585)
            right = pymupdf.Rect(426, 40, 822, 585)
            page.insert_text((20, 28), f"A page {pa_no or '-'}", fontsize=10)
            page.insert_text((426, 28), f"B page {pb_no or '-'}", fontsize=10)
            for side, doc, pno, box in (("A", a, pa_no, half), ("B", b, pb_no, right)):
                if not pno:
                    page.draw_rect(box, color=(0.7, 0.7, 0.7), dashes="[4] 0")
                    page.insert_textbox(box + (0, 200, 0, 0), "No corresponding page", fontsize=11, align=1)
                    continue
                src = doc[pno - 1]
                page.show_pdf_page(box, doc, pno - 1)
                # Map the source page (viewed) into box keeping proportion.
                sr = src.rect
                scale = min(box.width / sr.width, box.height / sr.height)
                ox = box.x0 + (box.width - sr.width * scale) / 2
                oy = box.y0 + (box.height - sr.height * scale) / 2
                for d in by_pair.get((pa_no, pb_no), []):
                    rects = d.get("rectsA" if side == "A" else "rectsB") or []
                    col = colors.get(d["type"], (0.5, 0.5, 0.5))
                    for r in rects:
                        fr = pymupdf.Rect(r) * src.transformation_matrix
                        fr = fr * src.rotation_matrix
                        fr.normalize()
                        hr = pymupdf.Rect(ox + fr.x0 * scale, oy + fr.y0 * scale, ox + fr.x1 * scale, oy + fr.y1 * scale)
                        page.draw_rect(hr, color=col, fill=col, fill_opacity=0.18, width=0.8)
        out = OutputFile(ctx, output, overwrite, sources=[pathA, pathB])
        out_doc.save(out.temp, garbage=3, deflate=True)
    finally:
        out_doc.close()
        a.close()
        b.close()
    final = out.commit(validate_pdf())
    return {"output": final, "summary": res["summary"], "differences": len(res["differences"])}
