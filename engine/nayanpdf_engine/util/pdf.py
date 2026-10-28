"""Shared PDF helpers: opening with passwords, permission enforcement,
page-range parsing, colours and save options."""

from __future__ import annotations

import os
import re
from typing import Iterable

import pymupdf

from ..errors import (
    INVALID_PDF,
    PASSWORD_INVALID,
    PASSWORD_REQUIRED,
    PERMISSION_RESTRICTED,
    UNSUPPORTED_FORMAT,
    EngineError,
    invalid,
)
from .io import os_path, require_file

# Permission classes an operation can require. Mapped to PDF permission bits.
PERM_BITS = {
    "print": pymupdf.PDF_PERM_PRINT,
    "modify": pymupdf.PDF_PERM_MODIFY,
    "copy": pymupdf.PDF_PERM_COPY,
    "annotate": pymupdf.PDF_PERM_ANNOTATE,
    "form": pymupdf.PDF_PERM_FORM,
    "assemble": pymupdf.PDF_PERM_ASSEMBLE,
}
PERM_LABELS = {
    "print": "printing",
    "modify": "modifying the document",
    "copy": "copying or extracting content",
    "annotate": "adding comments/annotations",
    "form": "filling form fields",
    "assemble": "assembling pages (insert, rotate, delete)",
}

pymupdf.TOOLS.mupdf_display_errors(False)
pymupdf.TOOLS.mupdf_display_warnings(False)


def is_pdf_file(path: str) -> bool:
    try:
        with open(os_path(path), "rb") as fh:
            head = fh.read(1024)
        return b"%PDF" in head
    except OSError:
        return False


def open_pdf(path: str, password: str | None = None, require: Iterable[str] = (),
             allow_repair: bool = True) -> pymupdf.Document:
    """Open a PDF, authenticate, and enforce document permissions.

    ``require`` lists permission classes the operation needs. If the document
    restricts any of them and the caller did not authenticate with the owner
    password, PERMISSION_RESTRICTED is raised. X PDF does not bypass
    restrictions it is not authorised to lift.
    """
    require_file(path)
    if not is_pdf_file(path):
        raise EngineError(UNSUPPORTED_FORMAT, f"{os.path.basename(path)} is not a PDF file.")
    try:
        doc = pymupdf.open(os_path(path), filetype="pdf")
    except Exception as e:  # noqa: BLE001
        raise EngineError(INVALID_PDF, f"The PDF could not be opened. It may be damaged or not a real PDF. ({e})")
    if not doc.is_pdf:
        doc.close()
        raise EngineError(UNSUPPORTED_FORMAT, "Not a PDF document.")
    if doc.needs_pass:
        if not password:
            doc.close()
            raise EngineError(PASSWORD_REQUIRED, "This document is password protected. Enter the password to open it.")
        rc = doc.authenticate(password)
        if not rc:
            doc.close()
            raise EngineError(PASSWORD_INVALID, "The password is incorrect.")
    elif password and encryption_method(doc):
        # Restrictions-only document; an owner password lifts restrictions.
        doc.authenticate(password)
    if doc.page_count == 0:
        doc.close()
        raise EngineError(INVALID_PDF, "The PDF contains no pages or its page tree is damaged.")
    enforce_permissions(doc, require)
    return doc


def encryption_method(doc: pymupdf.Document) -> str | None:
    meta = doc.metadata or {}
    return meta.get("encryption") or None


def owner_authenticated(doc: pymupdf.Document) -> bool:
    """True if the document has no restrictions in effect for this session."""
    if not encryption_method(doc):
        return True
    perms = doc.permissions
    return all(perms & bit for bit in PERM_BITS.values())


def enforce_permissions(doc: pymupdf.Document, require: Iterable[str]) -> None:
    require = list(require)
    if not require:
        return
    if not encryption_method(doc):
        return
    perms = doc.permissions
    missing = [r for r in require if r in PERM_BITS and not (perms & PERM_BITS[r])]
    if missing:
        labels = ", ".join(PERM_LABELS[m] for m in missing)
        raise EngineError(
            PERMISSION_RESTRICTED,
            f"The document's security settings do not allow {labels}. Enter the owner (permissions) password to proceed.",
            {"missing": missing},
        )


def parse_pages(spec, page_count: int, default_all: bool = True) -> list[int]:
    """Parse a page selection into 0-based indices (in the given order).

    Accepts: None/'all', 'odd', 'even', '1-3,5,8-', 'last', '-3' (first 3),
    lists of 1-based ints.
    """
    if spec is None or spec == "" or (isinstance(spec, str) and spec.strip().lower() == "all"):
        if default_all:
            return list(range(page_count))
        raise invalid("No pages selected.")
    if isinstance(spec, (list, tuple)):
        out = []
        for p in spec:
            try:
                n = int(p)
            except (TypeError, ValueError):
                raise invalid(f"Invalid page number: {p}")
            if n < 1 or n > page_count:
                raise invalid(f"Page {n} is out of range (document has {page_count} pages).")
            out.append(n - 1)
        if not out:
            raise invalid("No pages selected.")
        return out
    s = str(spec).strip().lower()
    if s == "odd":
        return list(range(0, page_count, 2))
    if s == "even":
        return list(range(1, page_count, 2))
    out: list[int] = []
    for part in re.split(r"[,;]\s*", s):
        part = part.strip()
        if not part:
            continue
        part = part.replace("last", str(page_count)).replace("end", str(page_count))
        m = re.fullmatch(r"(\d*)\s*[-–]\s*(\d*)", part)
        if m:
            a = int(m.group(1)) if m.group(1) else 1
            b = int(m.group(2)) if m.group(2) else page_count
            if a < 1 or b > page_count or a > page_count or b < 1:
                raise invalid(f"Page range '{part}' is outside 1-{page_count}.")
            step = 1 if b >= a else -1
            out.extend(range(a - 1, b - 1 + step, step))
        elif part.isdigit():
            n = int(part)
            if n < 1 or n > page_count:
                raise invalid(f"Page {n} is out of range (document has {page_count} pages).")
            out.append(n - 1)
        else:
            raise invalid(f"Could not understand the page selection '{part}'. Use forms like 1-3, 5, 8-.")
    if not out:
        raise invalid("No pages selected.")
    return out


def hex_to_rgb(color, default=(0, 0, 0)) -> tuple[float, float, float]:
    if color is None or color == "":
        return default
    if isinstance(color, (list, tuple)) and len(color) >= 3:
        vals = [float(c) for c in color[:3]]
        if any(v > 1 for v in vals):
            vals = [v / 255 for v in vals]
        return tuple(vals)  # type: ignore[return-value]
    s = str(color).strip().lstrip("#")
    if len(s) == 3:
        s = "".join(ch * 2 for ch in s)
    if not re.fullmatch(r"[0-9a-fA-F]{6}", s):
        raise invalid(f"Invalid colour: {color}")
    return tuple(int(s[i:i + 2], 16) / 255 for i in (0, 2, 4))  # type: ignore[return-value]


def int_to_rgb(c: int) -> tuple[float, float, float]:
    return (((c >> 16) & 255) / 255, ((c >> 8) & 255) / 255, (c & 255) / 255)


def rgb_to_hex(rgb) -> str:
    if rgb is None:
        return ""
    if isinstance(rgb, int):
        rgb = int_to_rgb(rgb)
    vals = list(rgb)
    if len(vals) == 1:
        vals = vals * 3
    if len(vals) == 4:  # CMYK -> RGB approximation
        c, m, y, k = vals
        vals = [(1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k)]
    return "#" + "".join(f"{max(0, min(255, round(v * 255))):02x}" for v in vals[:3])


def save_doc(doc: pymupdf.Document, path: str, *, keep_encryption: bool = True,
             garbage: int = 3, incremental: bool = False) -> None:
    """Save with sensible defaults. Keeps existing encryption unless told not to."""
    if incremental:
        doc.save(path, incremental=True, encryption=pymupdf.PDF_ENCRYPT_KEEP)
        return
    kwargs = dict(garbage=garbage, deflate=True, use_objstms=1)
    if keep_encryption and encryption_method(doc):
        kwargs["encryption"] = pymupdf.PDF_ENCRYPT_KEEP
    elif not keep_encryption:
        kwargs["encryption"] = pymupdf.PDF_ENCRYPT_NONE
    doc.save(path, **kwargs)


def drop_keys(doc: pymupdf.Document, xref: int, keys) -> None:
    """Remove keys with simple values from an object. (Setting a key to null with
    xref_set_key leaves "/Key null" in the file, which some readers reject.)"""
    obj = doc.xref_object(xref, compressed=True)
    for k in keys:
        obj = re.sub(rf"/{k}(?=[\s/(<\[])\s*(?:/[^\s/<>\[\]()]*|\((?:\\.|[^\\)])*\)|<[0-9A-Fa-f]*>|[-+.\d]+|true|false|null)", "", obj, count=1)
    doc.update_object(xref, obj)


def doc_is_signed(doc: pymupdf.Document) -> bool:
    try:
        if doc.get_sigflags() > 0:
            return True
    except Exception:  # noqa: BLE001
        pass
    try:
        for page in doc:
            for w in page.widgets() or []:
                if w.field_type == pymupdf.PDF_WIDGET_TYPE_SIGNATURE and w.field_value:
                    return True
    except Exception:  # noqa: BLE001
        pass
    return False


PAPER_SIZES_PT = {
    "A3": (841.89, 1190.55),
    "A4": (595.28, 841.89),
    "A5": (419.53, 595.28),
    "Letter": (612.0, 792.0),
    "Legal": (612.0, 1008.0),
    "Tabloid": (792.0, 1224.0),
    "B5": (498.9, 708.66),
}


def paper_name(width: float, height: float, tol: float = 3.0) -> str:
    w, h = sorted((width, height))
    for name, (pw, ph) in PAPER_SIZES_PT.items():
        if abs(w - pw) <= tol and abs(h - ph) <= tol:
            return name
    return "Custom"


def paper_rect(size: str, orientation: str = "portrait", custom: tuple | None = None) -> pymupdf.Rect:
    if size == "Custom" and custom:
        w, h = custom
    elif size in PAPER_SIZES_PT:
        w, h = PAPER_SIZES_PT[size]
    else:
        raise invalid(f"Unknown page size: {size}")
    if orientation == "landscape" and h > w:
        w, h = h, w
    elif orientation == "portrait" and w > h:
        w, h = h, w
    return pymupdf.Rect(0, 0, w, h)
