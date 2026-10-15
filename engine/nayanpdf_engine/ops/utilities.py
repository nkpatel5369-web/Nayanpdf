"""Additional utilities: repair, linearize, PDF/A, bookmarks, page labels,
attachments, image extraction, blank pages, page size, cropping, print prep."""

from __future__ import annotations

import os

import pymupdf

from ..errors import EngineError, TOOL_MISSING, VALIDATION_FAILED, invalid
from ..registry import method
from ..util import tools
from ..util.io import OutputFile, derive_output, os_path, require_file, safe_filename, unique_path, validate_image, validate_pdf
from ..util.pdf import doc_is_signed, open_pdf, paper_rect, parse_pages, save_doc
from .pages import _fix_toc_levels


@method("util.repair")
def repair(ctx, path: str, output: str, password: str | None = None, overwrite: bool = False):
    """Attempt to recover a damaged PDF using qpdf (pikepdf) and MuPDF; keep the better result."""
    import pikepdf

    require_file(path)
    attempts = []
    candidates = []
    t1 = ctx.tmp_path(".pdf")
    try:
        with pikepdf.open(os_path(path), password=password or "", attempt_recovery=True) as pdf:
            pdf.save(t1, encryption=True if pdf.is_encrypted else False)
        d = pymupdf.open(t1)
        if d.needs_pass:
            d.authenticate(password or "")
        candidates.append((d.page_count, "qpdf", t1))
        attempts.append({"engine": "qpdf", "ok": True, "pages": d.page_count})
        d.close()
    except Exception as e:  # noqa: BLE001
        attempts.append({"engine": "qpdf", "ok": False, "error": str(e)[:200]})
    t2 = ctx.tmp_path(".pdf")
    try:
        d = pymupdf.open(os_path(path))
        if d.needs_pass:
            d.authenticate(password or "")
        repaired = d.is_repaired
        d.save(t2, garbage=3, deflate=True)
        n = d.page_count
        d.close()
        candidates.append((n, "mupdf", t2))
        attempts.append({"engine": "mupdf", "ok": True, "pages": n, "xrefRebuilt": bool(repaired)})
    except Exception as e:  # noqa: BLE001
        attempts.append({"engine": "mupdf", "ok": False, "error": str(e)[:200]})
    if not candidates:
        raise EngineError(VALIDATION_FAILED, "The file could not be recovered by either repair engine.", {"attempts": attempts})
    candidates.sort(key=lambda c: (-c[0], c[1] != "qpdf"))
    pages, engine, best = candidates[0]
    out = OutputFile(ctx, output, overwrite, sources=[path])
    import shutil

    shutil.copyfile(best, out.temp)
    final = out.commit(validate_pdf(expected_pages=pages, password=password))
    ctx.warn("Recovered content should be reviewed: repair rebuilds the file structure but cannot restore data that is physically missing.")
    return {"output": final, "engine": engine, "pages": pages, "attempts": attempts}


@method("util.linearize")
def linearize(ctx, path: str, output: str, password: str | None = None, overwrite: bool = False):
    import pikepdf

    probe = open_pdf(path, password)
    n = probe.page_count
    if doc_is_signed(probe):
        ctx.warn("Linearizing rewrites the file and invalidates digital signatures.")
    probe.close()
    out = OutputFile(ctx, output, overwrite, sources=[path])
    with pikepdf.open(os_path(path), password=password or "") as pdf:
        pdf.save(out.temp, linearize=True, encryption=True if pdf.is_encrypted else False)
    with pikepdf.open(out.temp, password=password or "") as chk:
        if not chk.is_linearized:
            raise EngineError(VALIDATION_FAILED, "The output is not linearized.")
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final, "linearized": True}


@method("util.pdfa")
def pdfa(ctx, path: str, output: str, level: str = "2b", password: str | None = None, overwrite: bool = False):
    """Convert to PDF/A with Ghostscript, then run basic pre-flight checks (not a formal validation)."""
    gs = tools.require_tool(tools.ghostscript_exe(), "Ghostscript (PDF/A)")
    if level not in ("1b", "2b", "3b"):
        raise invalid("PDF/A level must be 1b, 2b or 3b.")
    probe = open_pdf(path, password)
    n = probe.page_count
    signed = doc_is_signed(probe)
    probe.close()
    if signed:
        ctx.warn("PDF/A conversion rebuilds the document; existing digital signatures are removed.")
    src = path
    if password:
        tmp = ctx.tmp_path(".pdf")
        d = open_pdf(path, password)
        d.save(tmp, encryption=pymupdf.PDF_ENCRYPT_NONE)
        d.close()
        src = tmp
    out = OutputFile(ctx, output, overwrite, sources=[path])
    part = level[0]
    icc = _find_srgb_icc(gs)
    if not icc:
        raise EngineError(TOOL_MISSING, "The sRGB colour profile needed for PDF/A output is missing from the Ghostscript component.")
    def_ps = os.path.join(ctx.tmpdir, "PDFA_def.ps")
    icc_ps = icc.replace("\\", "/").replace("(", "\\(").replace(")", "\\)")
    with open(def_ps, "w", encoding="latin-1") as fh:
        fh.write(PDFA_DEF.replace("@ICC@", icc_ps))
    args = [gs, "-dBATCH", "-dNOPAUSE", "-dSAFER", f"--permit-file-read={os.path.dirname(icc)}/",
            "-sDEVICE=pdfwrite", f"-dPDFA={part}", "-dPDFACompatibilityPolicy=1",
            "-sColorConversionStrategy=RGB", "-dEmbedAllFonts=true", "-dSubsetFonts=true",
            f"-sOutputFile={out.temp}", def_ps, src]
    ctx.status("Converting with Ghostscript")
    res = tools.run(args, timeout=1800)
    if res.returncode != 0 or not os.path.exists(out.temp) or os.path.getsize(out.temp) == 0:
        raise EngineError("OPERATION_FAILED", "Ghostscript could not convert this file to PDF/A: "
                          + res.stderr.decode("utf-8", "replace")[-400:])
    checks = preflight_pdfa(out.temp)
    final = out.commit(validate_pdf(expected_pages=n))
    ctx.warn("Basic PDF/A checks were run. This is not a formal conformance validation (e.g. veraPDF); verify with an approved validator if compliance is required.")
    return {"output": final, "level": f"PDF/A-{level}", "checks": checks}


PDFA_DEF = """%!
/ICCProfile (@ICC@) def
[/_objdef {icc_PDFA} /type /stream /OBJ pdfmark
[{icc_PDFA} << /N 3 >> /PUT pdfmark
[{icc_PDFA} ICCProfile (r) file /PUT pdfmark
[/_objdef {OutputIntent_PDFA} /type /dict /OBJ pdfmark
[{OutputIntent_PDFA} <<
  /Type /OutputIntent
  /S /GTS_PDFA1
  /DestOutputProfile {icc_PDFA}
  /OutputConditionIdentifier (sRGB)
>> /PUT pdfmark
[{Catalog} << /OutputIntents [ {OutputIntent_PDFA} ] >> /PUT pdfmark
"""


def _find_srgb_icc(gs_exe: str) -> str | None:
    env = os.environ.get("NAYANPDF_GS_ICC")
    if env and os.path.exists(env):
        return env
    base = os.path.dirname(os.path.realpath(gs_exe))
    cands = [os.path.join(base, "..", "iccprofiles", "srgb.icc"),
             os.path.join(base, "..", "Resource", "ColorSpace", "sRGB"),
             os.path.join(base, "..", "share", "ghostscript", "iccprofiles", "srgb.icc")]
    import glob

    cands += glob.glob("/usr/share/ghostscript/*/iccprofiles/srgb.icc") + glob.glob("/usr/share/color/icc/ghostscript/srgb.icc")
    cands += glob.glob(os.path.join(base, "..", "share", "ghostscript", "*", "iccprofiles", "srgb.icc"))
    for c in cands:
        if os.path.exists(c):
            return os.path.abspath(c)
    return None


def preflight_pdfa(path: str) -> dict:
    d = pymupdf.open(path)
    try:
        xmp = d.get_xml_metadata() or ""
        cat = d.pdf_catalog()
        oi = d.xref_get_key(cat, "OutputIntents")[0] != "null"
        fonts_ok = True
        unembedded = []
        for p in d:
            for f in p.get_fonts(full=True):
                if f[1] in ("n/a", ""):
                    fonts_ok = False
                    unembedded.append(f[3])
        enc = bool((d.metadata or {}).get("encryption"))
        js = any(d.xref_get_key(x, "S")[1] == "/JavaScript" for x in range(1, d.xref_length()))
        return {"xmpPdfaId": "pdfaid:part" in xmp, "outputIntent": oi, "allFontsEmbedded": fonts_ok,
                "unembeddedFonts": sorted(set(unembedded))[:20], "notEncrypted": not enc, "noJavaScript": not js,
                "passedBasicChecks": ("pdfaid:part" in xmp) and oi and fonts_ok and not enc and not js}
    finally:
        d.close()


@method("util.pdfaCheck")
def pdfa_check(ctx, path: str):
    return preflight_pdfa(require_file(path))


# ---- bookmarks & page labels ---------------------------------------------

@method("bookmarks.get")
def bookmarks_get(ctx, path: str, password: str | None = None):
    doc = open_pdf(path, password)
    try:
        return {"bookmarks": [{"level": t[0], "title": t[1], "page": t[2]} for t in doc.get_toc(simple=True)],
                "pageCount": doc.page_count}
    finally:
        doc.close()


@method("bookmarks.set")
def bookmarks_set(ctx, path: str, output: str, bookmarks: list, password: str | None = None,
                  overwrite: bool = False):
    doc = open_pdf(path, password, require=["modify"])
    n = doc.page_count
    toc = []
    for b in bookmarks:
        pg = int(b.get("page", 1))
        if pg < 1 or pg > n:
            raise invalid(f"Bookmark '{b.get('title')}' points to page {pg}, which does not exist.")
        toc.append([int(b.get("level", 1)), str(b.get("title", "")).strip() or "Untitled", pg])
    doc.set_toc(_fix_toc_levels(toc))
    out = OutputFile(ctx, output, overwrite, sources=[path])
    save_doc(doc, out.temp, garbage=1)
    doc.close()
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final, "count": len(toc)}


@method("labels.get")
def labels_get(ctx, path: str, password: str | None = None):
    doc = open_pdf(path, password)
    try:
        rules = doc.get_page_labels() or []
        labels = [doc[i].get_label() for i in range(min(doc.page_count, 2000))]
        return {"rules": rules, "labels": labels}
    finally:
        doc.close()


@method("labels.set")
def labels_set(ctx, path: str, output: str, rules: list, password: str | None = None, overwrite: bool = False):
    """rules: [{"startpage": 0-based, "prefix": "", "style": "D|r|R|a|A|", "firstpagenum": 1}]"""
    doc = open_pdf(path, password, require=["modify"])
    n = doc.page_count
    clean = []
    for r in rules:
        sp = int(r.get("startpage", 0))
        if sp < 0 or sp >= n:
            raise invalid("A page label rule starts on a page that does not exist.")
        style = r.get("style", "D")
        if style not in ("D", "r", "R", "a", "A", ""):
            raise invalid("Invalid label style.")
        clean.append({"startpage": sp, "prefix": r.get("prefix", ""), "style": style,
                      "firstpagenum": max(1, int(r.get("firstpagenum", 1)))})
    doc.set_page_labels(clean)
    out = OutputFile(ctx, output, overwrite, sources=[path])
    save_doc(doc, out.temp, garbage=1)
    doc.close()
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final}


# ---- attachments ------------------------------------------------------------

@method("attachments.list")
def attachments_list(ctx, path: str, password: str | None = None):
    doc = open_pdf(path, password)
    try:
        items = []
        for name in doc.embfile_names():
            info = doc.embfile_info(name)
            items.append({"name": name, "filename": info.get("filename") or name, "size": info.get("size"),
                          "description": info.get("description", ""), "kind": "embedded"})
        for p in doc:
            for a in p.annots(types=[pymupdf.PDF_ANNOT_FILE_ATTACHMENT]):
                fi = a.file_info
                items.append({"name": f"annot:{p.number + 1}:{a.xref}", "filename": fi.get("filename"),
                              "size": fi.get("size"), "description": fi.get("description", ""),
                              "kind": "annotation", "page": p.number + 1})
        return {"attachments": items}
    finally:
        doc.close()


@method("attachments.extract")
def attachments_extract(ctx, path: str, outputDir: str, names: list | None = None, password: str | None = None):
    doc = open_pdf(path, password, require=["copy"])
    try:
        outputs = []
        os.makedirs(os_path(outputDir), exist_ok=True)
        wanted = set(names or [])
        for name in doc.embfile_names():
            if wanted and name not in wanted:
                continue
            info = doc.embfile_info(name)
            data = doc.embfile_get(name)
            target = unique_path(os.path.join(outputDir, safe_filename(info.get("filename") or name)))
            with open(os_path(target), "wb") as fh:
                fh.write(data)
            outputs.append({"output": target, "size": len(data)})
        for p in doc:
            for a in p.annots(types=[pymupdf.PDF_ANNOT_FILE_ATTACHMENT]):
                key = f"annot:{p.number + 1}:{a.xref}"
                if wanted and key not in wanted:
                    continue
                data = a.get_file()
                fn = a.file_info.get("filename") or f"attachment_{a.xref}"
                target = unique_path(os.path.join(outputDir, safe_filename(fn)))
                with open(os_path(target), "wb") as fh:
                    fh.write(data)
                outputs.append({"output": target, "size": len(data)})
        if not outputs:
            raise invalid("No matching attachments found.")
        ctx.warn("Attachments can contain malicious files. Open extracted files only if you trust the document's source.")
        return {"outputs": outputs, "count": len(outputs)}
    finally:
        doc.close()


@method("attachments.add")
def attachments_add(ctx, path: str, output: str, files: list, password: str | None = None, overwrite: bool = False):
    doc = open_pdf(path, password, require=["modify"])
    n = doc.page_count
    existing = set(doc.embfile_names())
    for f in files:
        require_file(f)
        name = os.path.basename(f)
        base = name
        k = 1
        while name in existing:
            name = f"{os.path.splitext(base)[0]} ({k}){os.path.splitext(base)[1]}"
            k += 1
        with open(os_path(f), "rb") as fh:
            doc.embfile_add(name, fh.read(), filename=os.path.basename(f), ufilename=os.path.basename(f))
        existing.add(name)
    out = OutputFile(ctx, output, overwrite, sources=[path])
    save_doc(doc, out.temp, garbage=1)
    doc.close()
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final, "count": len(files)}


@method("attachments.delete")
def attachments_delete(ctx, path: str, output: str, names: list, password: str | None = None, overwrite: bool = False):
    doc = open_pdf(path, password, require=["modify"])
    n = doc.page_count
    for name in names:
        if name.startswith("annot:"):
            _, pg, xref = name.split(":")
            page = doc[int(pg) - 1]
            a = page.load_annot(int(xref))
            if a:
                page.delete_annot(a)
        else:
            doc.embfile_del(name)
    out = OutputFile(ctx, output, overwrite, sources=[path])
    save_doc(doc, out.temp, garbage=3)
    doc.close()
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final}


# ---- images -----------------------------------------------------------------

@method("images.extract")
def extract_images(ctx, path: str, outputDir: str, pages=None, minWidth: int = 32, minHeight: int = 32,
                   format: str = "native", password: str | None = None):
    doc = open_pdf(path, password, require=["copy"])
    try:
        os.makedirs(os_path(outputDir), exist_ok=True)
        stem = os.path.splitext(os.path.basename(path))[0]
        seen = set()
        outputs = []
        sel = parse_pages(pages, doc.page_count)
        for k, i in enumerate(sel):
            ctx.progress(k, len(sel), f"Page {i + 1}")
            for img in doc[i].get_images(full=True):
                xref, smask = img[0], img[1]
                if xref in seen:
                    continue
                seen.add(xref)
                try:
                    info = doc.extract_image(xref)
                except Exception:  # noqa: BLE001
                    continue
                if not info or info.get("width", 0) < minWidth or info.get("height", 0) < minHeight:
                    continue
                ext = info.get("ext", "png")
                data = info["image"]
                if smask or format == "png" or ext not in ("jpeg", "jpg", "png", "jp2", "jpx", "tiff", "bmp"):
                    pix = pymupdf.Pixmap(doc, xref)
                    if smask:
                        try:
                            mask = pymupdf.Pixmap(doc, smask)
                            pix = pymupdf.Pixmap(pix, mask)
                        except Exception:  # noqa: BLE001
                            pass
                    if pix.colorspace and pix.colorspace.n > 3:
                        pix = pymupdf.Pixmap(pymupdf.csRGB, pix)
                    data = pix.tobytes("png")
                    ext = "png"
                target = unique_path(os.path.join(outputDir, f"{safe_filename(stem)}_p{i + 1}_img{xref}.{ext}"))
                with open(os_path(target), "wb") as fh:
                    fh.write(data)
                entry = {"output": target, "page": i + 1, "width": info.get("width"), "height": info.get("height"),
                         "format": ext}
                if ext in ("png", "jpeg", "jpg", "tiff", "bmp"):
                    try:
                        validate_image(target)
                    except Exception:  # noqa: BLE001
                        entry["warning"] = "image could not be verified"
                outputs.append(entry)
        return {"outputs": outputs, "count": len(outputs)}
    finally:
        doc.close()


# ---- blank pages --------------------------------------------------------------

def _ink_ratio(page: pymupdf.Page, dpi: int = 30) -> float:
    pix = page.get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY, alpha=False)
    s = pix.samples
    if not s:
        return 0.0
    dark = sum(1 for v in s if v < 200)
    return dark / len(s)


@method("blank.detect")
def blank_detect(ctx, path: str, sensitivity: float = 0.003, password: str | None = None):
    """A page is blank if it has no text, no drawings and almost no dark pixels."""
    doc = open_pdf(path, password)
    try:
        blanks = []
        n = doc.page_count
        for i in range(n):
            if i % 10 == 0:
                ctx.progress(i, n)
            p = doc[i]
            if p.get_text("text").strip():
                continue
            ratio = _ink_ratio(p)
            if ratio <= float(sensitivity):
                blanks.append({"page": i + 1, "ink": round(ratio, 5)})
        return {"blankPages": blanks, "pageCount": n}
    finally:
        doc.close()


# ---- page size ---------------------------------------------------------------

@method("pagesize.standardize")
def standardize(ctx, path: str, output: str, size: str = "A4", orientation: str = "auto", fit: str = "fit",
                pages=None, password: str | None = None, overwrite: bool = False):
    """Rescale pages to a standard size. Content is scaled with a transformation
    (vector text stays text); annotations and links are moved with it."""
    doc = open_pdf(path, password, require=["modify"])
    sel = set(parse_pages(pages, doc.page_count))
    n = doc.page_count
    for i in range(n):
        if i not in sel:
            continue
        page = doc[i]
        view = page.rect
        orient = orientation
        if orient == "auto":
            orient = "landscape" if view.width > view.height else "portrait"
        tgt = paper_rect(size, orient)
        # Work in unrotated space.
        if page.rotation in (90, 270):
            tgt = pymupdf.Rect(0, 0, tgt.height, tgt.width)
        mb = page.mediabox
        cb = page.cropbox
        sx, sy = tgt.width / cb.width, tgt.height / cb.height
        s = min(sx, sy) if fit == "fit" else max(sx, sy)
        tx = (tgt.width - cb.width * s) / 2 - cb.x0 * s
        ty = (tgt.height - cb.height * s) / 2 - cb.y0 * s
        if not page.is_wrapped:
            page.wrap_contents()
        doc_ = page.parent
        head = doc_.get_new_xref()
        doc_.update_object(head, "<<>>")
        doc_.update_stream(head, f"q {s:.6f} 0 0 {s:.6f} {tx:.4f} {ty:.4f} cm\n".encode())
        tail = doc_.get_new_xref()
        doc_.update_object(tail, "<<>>")
        doc_.update_stream(tail, b"Q\n")
        cont = page.get_contents()
        doc_.xref_set_key(page.xref, "Contents", "[" + " ".join(f"{x} 0 R" for x in [head] + cont + [tail]) + "]")
        old_tm = page.transformation_matrix
        # Map annotation rectangles (PDF space): p' = p*s + t
        moves = []
        for a in page.annots():
            r = pymupdf.Rect(a.rect) * ~old_tm
            r = pymupdf.Rect(r.x0 * s + tx, r.y0 * s + ty, r.x1 * s + tx, r.y1 * s + ty)
            moves.append((a.xref, r))
        links = page.get_links()
        page.set_mediabox(pymupdf.Rect(0, 0, tgt.width, tgt.height))
        new_tm = page.transformation_matrix
        for xref, r in moves:
            a = page.load_annot(xref)
            if a:
                fr = r * new_tm
                fr.normalize()
                try:
                    a.set_rect(fr)
                    a.update()
                except Exception:  # noqa: BLE001
                    pass
        for ln in links:
            r = pymupdf.Rect(ln["from"]) * ~old_tm
            r = pymupdf.Rect(r.x0 * s + tx, r.y0 * s + ty, r.x1 * s + tx, r.y1 * s + ty) * new_tm
            r.normalize()
            ln["from"] = r
            try:
                page.update_link(ln)
            except Exception:  # noqa: BLE001
                pass
    if any(doc[i].first_widget for i in sel):
        ctx.warn("Form fields were not repositioned. Check fillable fields after resizing, or flatten the form first.")
    out = OutputFile(ctx, output, overwrite, sources=[path])
    save_doc(doc, out.temp, garbage=3)
    doc.close()
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final, "pages": len(sel), "size": size}


@method("pagesize.crop")
def crop(ctx, path: str, output: str, top: float = 0, right: float = 0, bottom: float = 0, left: float = 0,
         unit: str = "mm", pages=None, password: str | None = None, overwrite: bool = False):
    f = {"mm": 72 / 25.4, "pt": 1.0, "in": 72.0}.get(unit, 1.0)
    doc = open_pdf(path, password, require=["modify"])
    n = doc.page_count
    for i in parse_pages(pages, n):
        p = doc[i]
        cb = p.cropbox
        # cropbox is in unrotated, y-down (MuPDF) space relative to the MediaBox.
        r = pymupdf.Rect(cb.x0 + left * f, cb.y0 + top * f, cb.x1 - right * f, cb.y1 - bottom * f)
        if r.width < 36 or r.height < 36:
            raise invalid(f"The margins remove too much of page {i + 1}.")
        p.set_cropbox(r)
    out = OutputFile(ctx, output, overwrite, sources=[path])
    save_doc(doc, out.temp, garbage=1)
    doc.close()
    ctx.warn("Cropping hides the area outside the crop box; the content is still present in the file. Use Redact to remove sensitive content.")
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final}


# ---- printing -------------------------------------------------------------------

@method("print.prepare")
def print_prepare(ctx, path: str, outputDir: str, pages=None, dpi: int = 300, grayscale: bool = False,
                  password: str | None = None, annots: str = "all"):
    """Render pages to images for printing (honours the print permission).

    pages  : page string ("1-3,5") or a list of page numbers; each page is rendered once
    annots : "all" (document and markups), "none" (document only) or "forms"
             (document and form fields, without comments)
    """
    if annots not in ("all", "none", "forms"):
        raise invalid("annots must be all, none or forms.")
    doc = open_pdf(path, password, require=["print"])
    try:
        os.makedirs(os_path(outputDir), exist_ok=True)
        if isinstance(pages, list):
            sel = []
            for n in pages:
                n = int(n)
                if n < 1 or n > doc.page_count:
                    raise invalid(f"Page {n} does not exist.")
                if n - 1 not in sel:
                    sel.append(n - 1)
        else:
            sel = parse_pages(pages, doc.page_count)
        files = []
        for k, i in enumerate(sel):
            ctx.progress(k, len(sel), f"Preparing page {i + 1}")
            p = doc[i]
            if annots == "forms":
                # Hide comments for this rendering only (the document is not saved).
                for a in p.annots():
                    if a.type[0] != pymupdf.PDF_ANNOT_WIDGET:
                        a.set_flags(a.flags | pymupdf.PDF_ANNOT_IS_HIDDEN)
                        a.update()
            pix = p.get_pixmap(dpi=int(dpi), colorspace=pymupdf.csGRAY if grayscale else pymupdf.csRGB, alpha=False,
                               annots=annots != "none")
            target = os.path.join(outputDir, f"print_{i + 1:05d}.png")
            pix.save(target)
            files.append({"page": i + 1, "file": target, "widthPt": p.rect.width, "heightPt": p.rect.height})
        return {"pages": files}
    finally:
        doc.close()
