"""Document properties (Info dictionary and XMP), kept in sync via pikepdf."""

from __future__ import annotations

import datetime as _dt
import re

import pymupdf

from ..errors import EngineError, invalid
from ..registry import method
from ..util.io import OutputFile, os_path, validate_pdf
from ..util.pdf import doc_is_signed, open_pdf, owner_authenticated

FIELDS = ("title", "author", "subject", "keywords", "creator", "producer", "creationDate", "modDate")
DOCINFO_KEYS = {"title": "/Title", "author": "/Author", "subject": "/Subject", "keywords": "/Keywords",
                "creator": "/Creator", "producer": "/Producer", "creationDate": "/CreationDate", "modDate": "/ModDate"}


def pdf_date_to_iso(s: str) -> str:
    m = re.match(r"D?:?(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?(\d{2})?([Zz+\-])?(\d{2})?'?(\d{2})?", s or "")
    if not m:
        return ""
    y, mo, d, hh, mm, ss = (m.group(i) or ("01" if i in (2, 3) else "00") for i in range(1, 7))
    tz = ""
    if m.group(7) in ("+", "-") and m.group(8):
        tz = f"{m.group(7)}{m.group(8)}:{m.group(9) or '00'}"
    elif m.group(7) in ("Z", "z"):
        tz = "Z"
    return f"{y}-{mo}-{d}T{hh}:{mm}:{ss}{tz}"


def iso_to_pdf_date(s: str) -> str:
    try:
        dt = _dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        raise invalid(f"Invalid date: {s}. Use YYYY-MM-DD or YYYY-MM-DDTHH:MM.")
    if dt.tzinfo is None:
        dt = dt.astimezone()
    off = dt.utcoffset() or _dt.timedelta(0)
    mins = int(off.total_seconds() // 60)
    sign = "+" if mins >= 0 else "-"
    mins = abs(mins)
    return dt.strftime("D:%Y%m%d%H%M%S") + f"{sign}{mins // 60:02d}'{mins % 60:02d}'"


@method("meta.get")
def get(ctx, path: str, password: str | None = None):
    doc = open_pdf(path, password)
    try:
        m = doc.metadata or {}
        out = {k: m.get(k, "") or "" for k in FIELDS}
        out["creationDateIso"] = pdf_date_to_iso(out["creationDate"])
        out["modDateIso"] = pdf_date_to_iso(out["modDate"])
        xmp = doc.get_xml_metadata() or ""
        return {"fields": out, "hasXmp": bool(xmp), "xmp": xmp[:20000], "signed": doc_is_signed(doc),
                "format": m.get("format", ""), "encryption": m.get("encryption")}
    finally:
        doc.close()


def _pikepdf_open(path, password):
    import pikepdf

    try:
        return pikepdf.open(os_path(path), password=password or "")
    except pikepdf.PasswordError:
        raise EngineError("PASSWORD_REQUIRED", "Password required.")


@method("meta.set")
def set_meta(ctx, path: str, output: str, fields: dict, password: str | None = None, overwrite: bool = False):
    """Update properties. Empty string clears a field. Dates accept ISO 8601."""
    probe = open_pdf(path, password, require=["modify"])
    signed = doc_is_signed(probe)
    n = probe.page_count
    can_full = owner_authenticated(probe)
    probe.close()
    if signed:
        ctx.warn("This document is digitally signed. Changing its properties invalidates the existing signature(s).")
    pdf = _pikepdf_open(path, password)
    try:
        import pikepdf

        docinfo = pdf.docinfo
        with pdf.open_metadata(set_pikepdf_as_editor=False, update_docinfo=False) as xmp:
            for key, val in fields.items():
                if key not in DOCINFO_KEYS:
                    continue
                if key in ("creationDate", "modDate") and val:
                    val = iso_to_pdf_date(val) if not str(val).startswith("D:") else val
                if val in (None, ""):
                    if DOCINFO_KEYS[key] in docinfo:
                        del docinfo[DOCINFO_KEYS[key]]
                else:
                    docinfo[DOCINFO_KEYS[key]] = pikepdf.String(str(val))
                xkey = {"title": "dc:title", "author": "dc:creator", "subject": "dc:description",
                        "keywords": "pdf:Keywords", "creator": "xmp:CreatorTool", "producer": "pdf:Producer",
                        "creationDate": "xmp:CreateDate", "modDate": "xmp:ModifyDate"}[key]
                try:
                    if val in (None, ""):
                        if xkey in xmp:
                            del xmp[xkey]
                    elif key == "author":
                        xmp[xkey] = [str(val)]
                    elif key in ("creationDate", "modDate"):
                        xmp[xkey] = pdf_date_to_iso(val)
                    else:
                        xmp[xkey] = str(val)
                except Exception:  # noqa: BLE001
                    ctx.warn(f"The XMP copy of '{key}' could not be updated; the document info entry was updated.")
        out = OutputFile(ctx, output, overwrite, sources=[path])
        pdf.save(out.temp, encryption=True if pdf.is_encrypted else False)
    finally:
        pdf.close()
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final}


@method("meta.remove")
def remove(ctx, path: str, output: str, fields: list | None = None, removeXmp: bool = True,
           password: str | None = None, overwrite: bool = False):
    """Remove selected properties (default: all) and optionally the XMP packet."""
    fields = fields or list(FIELDS)
    probe = open_pdf(path, password, require=["modify"])
    signed = doc_is_signed(probe)
    n = probe.page_count
    probe.close()
    if signed:
        ctx.warn("This document is digitally signed. Removing properties invalidates the existing signature(s).")
    pdf = _pikepdf_open(path, password)
    try:
        for f in fields:
            k = DOCINFO_KEYS.get(f)
            if k and k in pdf.docinfo:
                del pdf.docinfo[k]
        if removeXmp and "/Metadata" in pdf.Root:
            del pdf.Root["/Metadata"]
        if removeXmp and len(pdf.docinfo) == 0 and "/Info" in pdf.trailer:
            del pdf.trailer["/Info"]
        out = OutputFile(ctx, output, overwrite, sources=[path])
        pdf.save(out.temp, encryption=True if pdf.is_encrypted else False, fix_metadata_version=False)
    finally:
        pdf.close()
    chk = pymupdf.open(out.temp)
    if chk.needs_pass:
        chk.authenticate(password or "")
    left = {k: v for k, v in (chk.metadata or {}).items() if k in fields and v}
    has_xmp = bool(chk.get_xml_metadata())
    chk.close()
    if left:
        ctx.warn("Some properties could not be removed: " + ", ".join(left))
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final, "remaining": list(left), "xmpRemoved": not has_xmp if removeXmp else False}
