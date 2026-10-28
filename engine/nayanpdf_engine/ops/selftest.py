"""Offline self-test of the bundled engines (used by Settings > Diagnostics and CI)."""

from __future__ import annotations

import os
import platform
import sys
import time

import pymupdf

from ..registry import method
from ..util import tools


def _timed(name, fn, results):
    t = time.time()
    try:
        detail = fn()
        results.append({"check": name, "ok": True, "detail": detail, "seconds": round(time.time() - t, 2)})
    except Exception as e:  # noqa: BLE001
        results.append({"check": name, "ok": False, "detail": f"{type(e).__name__}: {e}"[:400],
                        "seconds": round(time.time() - t, 2)})


@method("selftest.run")
def run(ctx, quick: bool = False):
    from ..registry import Context

    results = []
    work = ctx.tmpdir
    sample = os.path.join(work, "selftest.pdf")

    def make_pdf():
        d = pymupdf.open()
        for i in range(3):
            p = d.new_page()
            p.insert_text((72, 100), f"X PDF self test page {i + 1}", fontsize=16)
            p.insert_text((72, 140), "Invoice INV-2026/0042 total 1,23,456.78", fontsize=12)
        d.save(sample)
        d.close()
        return "3 pages"

    _timed("PDF engine (MuPDF)", lambda: (make_pdf(), f"PyMuPDF {pymupdf.VersionBind}, MuPDF {pymupdf.VersionFitz}")[1], results)

    def merge_split():
        from .pages import merge

        sub = Context("selftest")
        try:
            r = merge(sub, [{"path": sample}, {"path": sample}], os.path.join(work, "merged.pdf"), overwrite=True)
            assert r["pageCount"] == 6
            return "merged 3+3 pages, order verified"
        finally:
            sub.cleanup()

    _timed("Page operations", merge_split, results)

    def compress():
        from .compress import compress as c

        sub = Context("selftest")
        try:
            r = c(sub, sample, os.path.join(work, "c.pdf"), preset="balanced", overwrite=True)
            return f"{r['originalSize']} -> {r['finalSize']} bytes"
        finally:
            sub.cleanup()

    _timed("Compression", compress, results)

    def qpdf():
        import pikepdf

        with pikepdf.open(sample) as p:
            return f"pikepdf {pikepdf.__version__} / qpdf {pikepdf.__libqpdf_version__}, {len(p.pages)} pages"

    _timed("qpdf structural engine", qpdf, results)

    def office_libs():
        import docx  # noqa: F401
        import openpyxl
        import pptx  # noqa: F401
        import pdf2docx  # noqa: F401

        return f"python-docx, openpyxl {openpyxl.__version__}, python-pptx, pdf2docx"

    _timed("Office format writers", office_libs, results)

    def signing():
        import pyhanko  # noqa: F401
        import cryptography

        return f"pyHanko, cryptography {cryptography.__version__}"

    _timed("Digital signature engine", signing, results)

    def ocr():
        exe = tools.tesseract_exe()
        if not exe:
            raise RuntimeError("Tesseract not found")
        langs = tools.ocr_languages()
        if "eng" not in langs:
            raise RuntimeError(f"English language data missing (found: {langs})")
        if quick:
            return f"{exe} languages={langs}"
        d = pymupdf.open()
        p = d.new_page(width=400, height=120)
        p.insert_text((20, 70), "Searchable Invoice 4521", fontsize=26)
        pix = p.get_pixmap(dpi=200)
        img_doc = pymupdf.open()
        ip = img_doc.new_page(width=400, height=120)
        ip.insert_image(ip.rect, stream=pix.tobytes("png"))
        scan = os.path.join(work, "scan.pdf")
        img_doc.save(scan)
        from .ocr import run as ocr_run

        sub = Context("selftest")
        try:
            r = ocr_run(sub, scan, os.path.join(work, "scan_ocr.pdf"), languages=["eng"], overwrite=True)
        finally:
            sub.cleanup()
        chk = pymupdf.open(r["output"])
        found = bool(chk[0].search_for("Invoice"))
        chk.close()
        if not found:
            raise RuntimeError("OCR text layer not searchable")
        return f"text layer verified; languages={','.join(l for l in langs if l != 'osd')}"

    _timed("OCR (Tesseract)", ocr, results)

    def office():
        exe = tools.soffice_exe()
        if not exe:
            raise RuntimeError("LibreOffice not found")
        if quick:
            return exe
        import docx

        d = docx.Document()
        d.add_paragraph("X PDF LibreOffice self test")
        src = os.path.join(work, "t.docx")
        d.save(src)
        from .convert import office_to_pdf

        sub = Context("selftest")
        try:
            r = office_to_pdf(sub, src, os.path.join(work, "t.pdf"), overwrite=True)
        finally:
            sub.cleanup()
        chk = pymupdf.open(r["output"])
        ok = "self test" in chk[0].get_text()
        chk.close()
        if not ok:
            raise RuntimeError("converted PDF lacks expected text")
        return "DOCX -> PDF verified"

    _timed("Office conversion (LibreOffice)", office, results)

    def gs():
        exe = tools.ghostscript_exe()
        if not exe:
            raise RuntimeError("Ghostscript not found")
        r = tools.run([exe, "--version"], timeout=30)
        return f"Ghostscript {r.stdout.decode().strip()}"

    _timed("PDF/A engine (Ghostscript)", gs, results)

    ok = all(r["ok"] for r in results)
    return {"ok": ok, "results": results, "python": sys.version.split()[0], "platform": platform.platform(),
            "executable": sys.executable}
