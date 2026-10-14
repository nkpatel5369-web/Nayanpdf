"""OCR with the bundled Tesseract engine.

Searchable PDFs keep the original page (scan image and all other content
untouched) and receive an invisible text layer produced by Tesseract's
text-only PDF renderer, aligned to the page as displayed. Word confidences
from Tesseract's TSV output flag pages that need review. OCR text is never
presented as guaranteed accurate.
"""

from __future__ import annotations

import concurrent.futures as cf
import csv
import io
import os

import pymupdf

from ..errors import EngineError, TOOL_MISSING, VALIDATION_FAILED, invalid
from ..registry import method
from ..util import tools
from ..util.io import OutputFile, validate_nonempty, validate_pdf
from ..util.pdf import doc_is_signed, open_pdf, parse_pages, save_doc


def _check_langs(languages: list[str]) -> str:
    avail = set(tools.ocr_languages())
    if not avail:
        raise EngineError(TOOL_MISSING, "OCR language data is not installed.")
    langs = [l for l in (languages or ["eng"]) if l]
    missing = [l for l in langs if l not in avail]
    if missing:
        raise EngineError(TOOL_MISSING, f"OCR language(s) not installed: {', '.join(missing)}. Available: {', '.join(sorted(avail - {'osd'}))}")
    return "+".join(langs)


def _tess_env() -> dict:
    env = {"OMP_THREAD_LIMIT": "1"}
    td = tools.tessdata_dir()
    if td:
        env["TESSDATA_PREFIX"] = td
    return env


def _osd_rotation(exe: str, img: str) -> int:
    """Clockwise rotation (0/90/180/270) Tesseract suggests to make the text upright."""
    res = tools.run([exe, img, "-", "--psm", "0", "-l", "osd"], timeout=120, env=_tess_env())
    out = res.stdout.decode("utf-8", "replace") + res.stderr.decode("utf-8", "replace")
    rot, conf = 0, 0.0
    for line in out.splitlines():
        if line.startswith("Rotate:"):
            try:
                rot = int(line.split(":")[1].strip())
            except ValueError:
                pass
        if line.startswith("Orientation confidence:"):
            try:
                conf = float(line.split(":")[1].strip())
            except ValueError:
                pass
    return rot if conf >= 2.0 else 0


def _run_tesseract(exe: str, img: str, base: str, lang: str, dpi: int, psm: int, want_pdf: bool) -> dict:
    # Output formats are requested as parameters, not as config-file names, so OCR
    # does not depend on Tesseract's optional "configs" folder being present.
    args = [exe, img, base, "-l", lang, "--dpi", str(dpi), "--psm", str(psm), "-c", "tessedit_create_tsv=1"]
    if want_pdf:
        args += ["-c", "tessedit_create_pdf=1", "-c", "textonly_pdf=1"]
    res = tools.run(args, timeout=900, env=_tess_env())
    err = res.stderr.decode("utf-8", "replace").strip()
    if res.returncode != 0:
        raise EngineError("OPERATION_FAILED", "Tesseract failed: " + err[-300:])
    tsv_path = base + ".tsv"
    # Never report OCR as done if Tesseract did not write what was asked for.
    missing = [p for p in ([tsv_path] + ([base + ".pdf"] if want_pdf else [])) if not os.path.exists(p) or os.path.getsize(p) == 0]
    if missing:
        raise EngineError("OPERATION_FAILED", "Tesseract produced no output (" + ", ".join(os.path.basename(m) for m in missing)
                          + "). " + err[-300:])
    words = []
    if os.path.exists(tsv_path):
        with open(tsv_path, encoding="utf-8", errors="replace") as fh:
            rd = csv.DictReader(fh, delimiter="\t", quoting=csv.QUOTE_NONE)
            for row in rd:
                try:
                    if row.get("level") == "5" and (row.get("text") or "").strip():
                        words.append((float(row["conf"]), row["text"]))
                except (KeyError, ValueError, TypeError):
                    continue
    return {"pdf": base + ".pdf" if want_pdf else None, "words": words}


def _page_stats(words) -> dict:
    confs = [c for c, _ in words if c >= 0]
    if not confs:
        return {"words": 0, "meanConfidence": None, "lowConfidenceWords": 0, "review": True}
    low = sum(1 for c in confs if c < 60)
    mean = sum(confs) / len(confs)
    return {"words": len(confs), "meanConfidence": round(mean, 1), "lowConfidenceWords": low,
            "review": mean < 75 or low / len(confs) > 0.2}


def ocr_document(ctx, doc: pymupdf.Document, pages: list[int], lang: str, dpi: int, skip_text: bool,
                 auto_rotate: bool, psm: int = 3, want_layer: bool = True, threads: int | None = None) -> dict:
    """OCR selected pages of an open document in place. Returns per-page results."""
    exe = tools.require_tool(tools.tesseract_exe(), "Tesseract OCR")
    work = ctx.tmpdir
    todo = []
    skipped = []
    for i in pages:
        p = doc[i]
        if skip_text and p.get_text("text").strip():
            skipped.append(i + 1)
            continue
        todo.append(i)
    results: dict[int, dict] = {}
    total = len(todo)
    if total == 0:
        return {"processed": [], "skipped": skipped, "pages": {}}
    # Render pages first (MuPDF is not used across threads), then OCR in parallel.
    rotated = []
    images = {}
    for k, i in enumerate(todo):
        ctx.progress(k, total * 2, f"Rendering page {i + 1}")
        p = doc[i]
        pix = p.get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY, alpha=False)
        img = os.path.join(work, f"p{i:05d}.png")
        pix.save(img)
        if auto_rotate:
            rot = _osd_rotation(exe, img)
            if rot:
                p.set_rotation((p.rotation + rot) % 360)
                rotated.append({"page": i + 1, "rotation": rot})
                pix = p.get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY, alpha=False)
                pix.save(img)
        images[i] = img
    workers = threads or max(1, min(4, (os.cpu_count() or 2)))
    done = 0
    with cf.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(_run_tesseract, exe, images[i], os.path.join(work, f"o{i:05d}"), lang, dpi, psm, want_layer): i
                for i in todo}
        for fut in cf.as_completed(futs):
            i = futs[fut]
            results[i] = fut.result()
            done += 1
            ctx.progress(total + done, total * 2, f"Recognised page {i + 1}")
    page_info = {}
    for i in todo:
        r = results[i]
        stats = _page_stats(r["words"])
        if want_layer and r["pdf"] and os.path.exists(r["pdf"]):
            tdoc = pymupdf.open(r["pdf"])
            p = doc[i]
            unrot = p.rect * p.derotation_matrix
            unrot.normalize()
            p.show_pdf_page(unrot, tdoc, 0, rotate=p.rotation, overlay=True, keep_proportion=False)
            tdoc.close()
        page_info[i + 1] = stats
        page_info[i + 1]["text"] = " ".join(w for _, w in r["words"])
    return {"processed": [i + 1 for i in todo], "skipped": skipped, "pages": page_info, "rotated": rotated}


@method("ocr.run")
def run(ctx, path: str, output: str, languages: list | None = None, pages=None, dpi: int = 300,
        skipText: bool = True, autoRotate: bool = True, psm: int = 3, password: str | None = None,
        overwrite: bool = False, textOutput: str | None = None):
    """Create a searchable PDF (invisible text over the original page)."""
    lang = _check_langs(languages or ["eng"])
    dpi = max(150, min(600, int(dpi)))
    doc = open_pdf(path, password, require=["modify"])
    if doc_is_signed(doc):
        ctx.warn("This document is digitally signed. Adding a text layer changes the document and invalidates the signature(s).")
    sel = parse_pages(pages, doc.page_count)
    res = ocr_document(ctx, doc, sel, lang, dpi, bool(skipText), bool(autoRotate), int(psm))
    n = doc.page_count
    if not res["processed"]:
        doc.close()
        raise EngineError("NOT_SUPPORTED", "All selected pages already contain text. Turn off 'Skip pages that already have text' to OCR them anyway.")
    out = OutputFile(ctx, output, overwrite, sources=[path])
    save_doc(doc, out.temp, garbage=3)
    doc.close()
    # Verify the text layer is present and searchable.
    chk = pymupdf.open(out.temp)
    if chk.needs_pass:
        chk.authenticate(password or "")
    missing = []
    for pno in res["processed"]:
        st = res["pages"][pno]
        if st["words"] and not chk[pno - 1].get_text("text").strip():
            missing.append(pno)
    # Spot-check that a recognised word can be found by search.
    probe_ok = None
    for pno in res["processed"]:
        words = [w for w in res["pages"][pno]["text"].split() if len(w) >= 4 and w.isalnum()]
        if words:
            probe_ok = bool(chk[pno - 1].search_for(words[0]))
            break
    chk.close()
    if missing:
        raise EngineError(VALIDATION_FAILED, f"The text layer is missing on page(s) {missing[:10]} after OCR.")
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    review = [p for p, st in res["pages"].items() if st["review"]]
    if review:
        ctx.warn(f"Low OCR confidence on page(s) {', '.join(map(str, review[:20]))}. Review these pages before relying on the text.")
    if textOutput:
        t = OutputFile(ctx, textOutput, overwrite, sources=[path])
        with open(t.temp, "w", encoding="utf-8") as fh:
            for pno in sorted(res["pages"]):
                fh.write(f"--- Page {pno} ---\n{res['pages'][pno]['text']}\n\n")
        t.commit(validate_nonempty)
    pages_out = {p: {k: v for k, v in st.items() if k != "text"} for p, st in res["pages"].items()}
    return {"output": final, "processed": res["processed"], "skipped": res["skipped"], "pages": pages_out,
            "reviewPages": review, "rotated": res.get("rotated", []), "searchVerified": probe_ok,
            "notice": "OCR output can contain recognition errors. Check figures and identifiers against the source."}


@method("ocr.text")
def text(ctx, path: str, output: str, languages: list | None = None, pages=None, dpi: int = 300,
         password: str | None = None, overwrite: bool = False, useExistingText: bool = True):
    """Extract plain text, running OCR only where pages have no text layer."""
    lang = _check_langs(languages or ["eng"])
    doc = open_pdf(path, password, require=["copy"])
    sel = parse_pages(pages, doc.page_count)
    res = ocr_document(ctx, doc, sel, lang, int(dpi), bool(useExistingText), True, 3, want_layer=False)
    out = OutputFile(ctx, output, overwrite, sources=[path])
    with open(out.temp, "w", encoding="utf-8") as fh:
        for i in sel:
            fh.write(f"--- Page {i + 1} ---\n")
            if (i + 1) in res["pages"]:
                fh.write(res["pages"][i + 1]["text"] + "\n\n")
            else:
                fh.write(doc[i].get_text("text", sort=True) + "\n")
    doc.close()
    final = out.commit(validate_nonempty)
    return {"output": final, "ocrPages": res["processed"],
            "reviewPages": [p for p, st in res["pages"].items() if st["review"]]}


@method("ocr.languages")
def languages(ctx):
    return {"languages": [l for l in tools.ocr_languages() if l != "osd"], "osd": "osd" in tools.ocr_languages(),
            "tesseract": bool(tools.tesseract_exe())}


def searchable_copy(ctx, path: str, password: str | None, lang: str, dpi: int = 300) -> tuple[str, dict]:
    """OCR textless pages into a temp copy (used by conversions)."""
    doc = open_pdf(path, password)
    sel = list(range(doc.page_count))
    res = ocr_document(ctx, doc, sel, lang, dpi, True, True, 3)
    tmp = ctx.tmp_path(".pdf")
    doc.save(tmp, garbage=1, deflate=True, encryption=pymupdf.PDF_ENCRYPT_NONE)
    doc.close()
    return tmp, res
