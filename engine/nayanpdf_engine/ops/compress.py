"""PDF compression.

Text and vector content are never rasterised. Size is reduced by
re-sampling/re-encoding embedded images above a resolution threshold,
removing unused objects, compressing streams and packing objects into object
streams. Results are measured from the actual files on disk.
"""

from __future__ import annotations

import os
import shutil

import pymupdf

from ..errors import invalid
from ..registry import method
from ..util.io import OutputFile, file_size, os_path, validate_pdf
from ..util.pdf import doc_is_signed, open_pdf

PRESETS = {
    # name: (dpi_threshold, dpi_target, jpeg_quality, recompress_bitonal, clean_content)
    "maximum": None,  # lossless only
    "high": (300, 220, 88, False, True),
    "balanced": (200, 150, 75, False, True),
    "strong": (130, 96, 55, True, True),
}
PRESET_LABELS = {
    "maximum": "Maximum Quality (lossless)",
    "high": "High Quality",
    "balanced": "Balanced Compression",
    "strong": "High Compression",
    "custom": "Custom Settings",
}


def _compress_once(ctx, path, password, temp_out, *, threshold, target, quality, bitonal, gray,
                   clean, remove_metadata, remove_thumbnails, remove_javascript, subset_fonts,
                   remove_attachments):
    doc = open_pdf(path, password, require=["modify"])
    try:
        if threshold:
            ctx.status("Re-encoding images")
            doc.rewrite_images(
                dpi_threshold=int(threshold),
                dpi_target=int(target),
                quality=int(quality),
                lossy=True,
                lossless=True,
                bitonal=bool(bitonal),
                color=True,
                gray=True,
                set_to_gray=bool(gray),
            )
        if subset_fonts:
            ctx.status("Subsetting fonts")
            try:
                doc.subset_fonts()
            except Exception:  # noqa: BLE001 - font subsetting is best-effort
                ctx.warn("Some fonts could not be subset; they were left unchanged.")
        if remove_metadata or remove_thumbnails or remove_javascript or remove_attachments:
            doc.scrub(
                attached_files=bool(remove_attachments),
                clean_pages=False,
                embedded_files=bool(remove_attachments),
                hidden_text=False,
                javascript=bool(remove_javascript),
                metadata=bool(remove_metadata),
                redactions=False,
                remove_links=False,
                reset_fields=False,
                reset_responses=False,
                thumbnails=bool(remove_thumbnails),
                xml_metadata=bool(remove_metadata),
            )
        ctx.status("Writing optimised file")
        doc.save(
            temp_out,
            garbage=4,
            clean=bool(clean),
            deflate=True,
            deflate_images=True,
            deflate_fonts=True,
            use_objstms=1,
            compression_effort=100 if clean else 0,
        )
    finally:
        doc.close()


@method("compress.run")
def compress(ctx, path: str, output: str, preset: str = "balanced", password: str | None = None,
             overwrite: bool = False, dpiThreshold: int | None = None, dpiTarget: int | None = None,
             quality: int | None = None, grayscale: bool = False, removeMetadata: bool = False,
             removeThumbnails: bool = True, removeJavaScript: bool = False, subsetFonts: bool = True,
             removeAttachments: bool = False, targetSizeKb: float | None = None):
    original = file_size(path)
    probe = open_pdf(path, password, require=["modify"])
    signed = doc_is_signed(probe)
    pages = probe.page_count
    probe.close()
    if signed:
        ctx.warn("This document is digitally signed. Compression rewrites the file and the existing signature will no longer validate.")

    out = OutputFile(ctx, output, overwrite, sources=[path])
    common = dict(gray=grayscale, remove_metadata=removeMetadata, remove_thumbnails=removeThumbnails,
                  remove_javascript=removeJavaScript, subset_fonts=subsetFonts,
                  remove_attachments=removeAttachments)

    def settings_for(name):
        if name == "custom":
            if not dpiTarget or not quality:
                raise invalid("Custom compression needs a target resolution and JPEG quality.")
            thr = int(dpiThreshold or int(dpiTarget) + 50)
            return dict(threshold=thr, target=int(dpiTarget), quality=max(10, min(100, int(quality))),
                        bitonal=False, clean=True)
        if name not in PRESETS:
            raise invalid(f"Unknown compression preset: {name}")
        p = PRESETS[name]
        if p is None:
            return dict(threshold=None, target=None, quality=None, bitonal=False, clean=False)
        return dict(threshold=p[0], target=p[1], quality=p[2], bitonal=p[3], clean=p[4])

    attempts = []
    used = preset
    if targetSizeKb:
        target_bytes = float(targetSizeKb) * 1024
        ladder = ["maximum", "high", "balanced", "strong", "x1", "x2"]
        extra = {
            "x1": dict(threshold=100, target=72, quality=45, bitonal=True, clean=True),
            "x2": dict(threshold=80, target=60, quality=35, bitonal=True, clean=True),
        }
        best = None
        for k, name in enumerate(ladder):
            ctx.progress(k, len(ladder), f"Trying {PRESET_LABELS.get(name, 'stronger settings')}")
            s = extra.get(name) or settings_for(name)
            tmp = ctx.tmp_path(".pdf")
            _compress_once(ctx, path, password, tmp, **s, **common)
            size = os.path.getsize(tmp)
            attempts.append({"preset": name, "size": size})
            if best is None or size < best[1]:
                best = (tmp, size, name)
            if size <= target_bytes:
                break
        shutil.copyfile(best[0], out.temp)
        used = best[2]
        reached = best[1] <= target_bytes
        if not reached:
            ctx.warn(f"The target size of {targetSizeKb:.0f} KB could not be reached without unacceptable quality loss. "
                     f"The smallest result is {best[1] / 1024:.0f} KB.")
    else:
        ctx.progress(0, 1, PRESET_LABELS.get(preset, preset))
        _compress_once(ctx, path, password, out.temp, **settings_for(preset), **common)
        reached = None

    final = out.commit(validate_pdf(expected_pages=pages, password=password))
    final_size = file_size(final)
    reduction = original - final_size
    pct = (reduction / original * 100) if original else 0.0
    ctx.progress(1, 1, "Done")
    return {
        "output": final,
        "originalSize": original,
        "finalSize": final_size,
        "reductionBytes": reduction,
        "reductionPercent": round(pct, 2),
        "larger": final_size > original,
        "preset": used,
        "presetLabel": PRESET_LABELS.get(used, "Target size search"),
        "targetReached": reached,
        "attempts": attempts,
        "pageCount": pages,
    }


@method("compress.analyze")
def analyze(ctx, path: str, password: str | None = None):
    """Break down what occupies space, to help choose a preset."""
    doc = open_pdf(path, password)
    try:
        img_bytes = 0
        images = 0
        seen = set()
        for p in doc:
            for img in p.get_images(full=True):
                xref = img[0]
                if xref in seen:
                    continue
                seen.add(xref)
                images += 1
                try:
                    img_bytes += len(doc.xref_stream_raw(xref) or b"")
                except Exception:  # noqa: BLE001
                    pass
        font_bytes = 0
        fseen = set()
        for p in doc:
            for f in p.get_fonts(full=True):
                if f[0] in fseen:
                    continue
                fseen.add(f[0])
                try:
                    _, _, _, buf = doc.extract_font(f[0])
                    font_bytes += len(buf or b"")
                except Exception:  # noqa: BLE001
                    pass
        total = file_size(path)
        return {"fileSize": total, "images": images, "imageBytes": img_bytes, "fonts": len(fseen),
                "fontBytes": font_bytes, "otherBytes": max(0, total - img_bytes - font_bytes),
                "pageCount": doc.page_count}
    finally:
        doc.close()
