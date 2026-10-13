"""Password protection, authorised removal, permission and integrity inspection.

NayanPDF only removes protection when the caller supplies a password that
grants the necessary rights. It never attempts to guess, crack or bypass
passwords.
"""

from __future__ import annotations

import hashlib

import pymupdf

from ..errors import EngineError, PERMISSION_RESTRICTED, VALIDATION_FAILED, invalid
from ..registry import method
from ..util.io import OutputFile, file_size, os_path, validate_pdf
from ..util.pdf import PERM_BITS, doc_is_signed, encryption_method, open_pdf, owner_authenticated

METHODS = {
    "AES-256": pymupdf.PDF_ENCRYPT_AES_256,
    "AES-128": pymupdf.PDF_ENCRYPT_AES_128,
    "RC4-128": pymupdf.PDF_ENCRYPT_RC4_128,
}
PERM_FLAGS = {
    "print": pymupdf.PDF_PERM_PRINT,
    "printHighQuality": pymupdf.PDF_PERM_PRINT_HQ,
    "modify": pymupdf.PDF_PERM_MODIFY,
    "copy": pymupdf.PDF_PERM_COPY,
    "annotate": pymupdf.PDF_PERM_ANNOTATE,
    "form": pymupdf.PDF_PERM_FORM,
    "accessibility": pymupdf.PDF_PERM_ACCESSIBILITY,
    "assemble": pymupdf.PDF_PERM_ASSEMBLE,
}


@method("security.encrypt")
def encrypt(ctx, path: str, output: str, userPassword: str = "", ownerPassword: str = "",
            encryption: str = "AES-256", permissions: dict | None = None, password: str | None = None,
            overwrite: bool = False):
    if encryption not in METHODS:
        raise invalid("Unsupported encryption method.")
    if not userPassword and not ownerPassword:
        raise invalid("Enter an open password, a permissions password, or both.")
    perms_req = permissions or {k: True for k in PERM_FLAGS}
    restricting = not all(perms_req.get(k, True) for k in PERM_FLAGS)
    if restricting and not ownerPassword:
        raise invalid("Restricting permissions requires a permissions (owner) password.")
    if ownerPassword and userPassword and ownerPassword == userPassword and restricting:
        raise invalid("The permissions password must differ from the open password, otherwise anyone who can open the file can lift the restrictions.")
    doc = open_pdf(path, password)
    if encryption_method(doc) and not owner_authenticated(doc):
        doc.close()
        raise EngineError(PERMISSION_RESTRICTED, "Changing the security of this document requires its current permissions (owner) password.")
    if encryption == "RC4-128":
        ctx.warn("RC4 is an obsolete cipher. Use AES-256 unless the file must open in very old software.")
    if doc_is_signed(doc):
        ctx.warn("This document is digitally signed. Encrypting rewrites the file and invalidates the signature(s).")
    perm_value = 0
    for k, bit in PERM_FLAGS.items():
        if perms_req.get(k, True):
            perm_value |= bit
    owner = ownerPassword or userPassword
    n = doc.page_count
    out = OutputFile(ctx, output, overwrite, sources=[path])
    doc.save(out.temp, garbage=3, deflate=True, encryption=METHODS[encryption], owner_pw=owner,
             user_pw=userPassword or "", permissions=perm_value)
    doc.close()

    # Verify the protection actually works before reporting success.
    chk = pymupdf.open(out.temp)
    try:
        if userPassword:
            if not chk.needs_pass:
                raise EngineError(VALIDATION_FAILED, "Verification failed: the output opens without a password.")
            if chk.authenticate("") or chk.authenticate("wrong-" + userPassword):
                raise EngineError(VALIDATION_FAILED, "Verification failed: the output accepts an incorrect password.")
            chk2 = pymupdf.open(out.temp)
            if not chk2.authenticate(userPassword):
                raise EngineError(VALIDATION_FAILED, "Verification failed: the open password does not work.")
            got_perms = chk2.permissions
            chk2.close()
        else:
            got_perms = chk.permissions
        chk3 = pymupdf.open(out.temp)
        rc = chk3.authenticate(owner)
        chk3.close()
        if rc not in (4, 6) and ownerPassword:
            raise EngineError(VALIDATION_FAILED, "Verification failed: the permissions password does not grant owner access.")
        for k, bit in PERM_FLAGS.items():
            if bool(perms_req.get(k, True)) != bool(got_perms & bit) and restricting:
                if k in ("printHighQuality", "accessibility"):
                    continue
                raise EngineError(VALIDATION_FAILED, f"Verification failed: permission '{k}' was not applied as requested.")
    finally:
        chk.close()
    final = out.commit(validate_pdf(expected_pages=n, password=userPassword or owner))
    return {"output": final, "encryption": encryption, "openPassword": bool(userPassword),
            "restricted": restricting, "verified": True}


@method("security.decrypt")
def decrypt(ctx, path: str, output: str, password: str, overwrite: bool = False):
    """Remove protection. Requires a password granting full (owner) rights."""
    if not password:
        raise invalid("Enter the document password.")
    doc = open_pdf(path, password)
    if not encryption_method(doc):
        doc.close()
        raise invalid("This document is not encrypted.")
    if not owner_authenticated(doc):
        doc.close()
        raise EngineError(PERMISSION_RESTRICTED,
                          "This password opens the document but does not grant permission to remove its security. Enter the permissions (owner) password.")
    n = doc.page_count
    out = OutputFile(ctx, output, overwrite, sources=[path])
    doc.save(out.temp, garbage=3, deflate=True, encryption=pymupdf.PDF_ENCRYPT_NONE)
    doc.close()
    chk = pymupdf.open(out.temp)
    try:
        if chk.needs_pass or encryption_method(chk):
            raise EngineError(VALIDATION_FAILED, "Verification failed: the output is still encrypted.")
    finally:
        chk.close()
    final = out.commit(validate_pdf(expected_pages=n))
    return {"output": final, "verified": True}


@method("security.inspect")
def inspect(ctx, path: str, password: str | None = None):
    raw = pymupdf.open(os_path(path))
    needs = bool(raw.needs_pass)
    raw.close()
    if needs and not password:
        return {"encrypted": True, "needsPassword": True}
    doc = open_pdf(path, password)
    try:
        perms = doc.permissions
        enc = encryption_method(doc)
        js = False
        try:
            for xref in range(1, doc.xref_length()):
                if doc.xref_get_key(xref, "JS")[0] != "null" or doc.xref_get_key(xref, "S")[1] == "/JavaScript":
                    js = True
                    break
        except Exception:  # noqa: BLE001
            pass
        launch = False
        try:
            for xref in range(1, doc.xref_length()):
                if doc.xref_get_key(xref, "S")[1] == "/Launch":
                    launch = True
                    break
        except Exception:  # noqa: BLE001
            pass
        return {
            "encrypted": bool(enc),
            "needsPassword": needs,
            "method": enc,
            "ownerAccess": owner_authenticated(doc),
            "permissions": {k: bool(perms & bit) for k, bit in PERM_FLAGS.items()},
            "signed": doc_is_signed(doc),
            "javascript": js,
            "launchActions": launch,
            "embeddedFiles": doc.embfile_count(),
        }
    finally:
        doc.close()


@method("security.integrity")
def integrity(ctx, path: str, password: str | None = None):
    """Structural check (qpdf via pikepdf + MuPDF), file hash and update history."""
    import pikepdf

    h = hashlib.sha256()
    eof_count = 0
    with open(os_path(path), "rb") as fh:
        prev_tail = b""
        while True:
            chunk = fh.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
            eof_count += (prev_tail + chunk).count(b"%%EOF") - prev_tail.count(b"%%EOF")
            prev_tail = chunk[-8:]
    problems: list[str] = []
    qpdf_ok = None
    try:
        with pikepdf.open(os_path(path), password=password or "") as pdf:
            issues = pdf.check_pdf_syntax() if hasattr(pdf, "check_pdf_syntax") else pdf.check()
            problems.extend(str(i) for i in issues)
            qpdf_ok = not issues
    except pikepdf.PasswordError:
        problems.append("Password required for structural check.")
    except Exception as e:  # noqa: BLE001
        problems.append(f"qpdf could not parse the file: {e}")
        qpdf_ok = False
    pymupdf.TOOLS.mupdf_warnings(reset=True)
    render_errors = []
    try:
        doc = open_pdf(path, password)
        repaired = bool(doc.is_repaired)
        for i in range(doc.page_count):
            try:
                doc[i].get_text("text")
            except Exception as e:  # noqa: BLE001
                render_errors.append(f"Page {i + 1}: {e}")
        doc.close()
    except EngineError as e:
        return {"sha256": h.hexdigest(), "ok": False, "problems": problems + [e.message]}
    warnings = [w for w in pymupdf.TOOLS.mupdf_warnings().splitlines() if w.strip()][:50]
    ok = (qpdf_ok is not False) and not render_errors and not repaired
    return {"sha256": h.hexdigest(), "size": file_size(path), "ok": ok, "qpdfOk": qpdf_ok, "repairedOnOpen": repaired,
            "problems": problems[:100], "pageErrors": render_errors[:100], "mupdfWarnings": warnings,
            "revisions": max(1, eof_count), "incrementallyUpdated": eof_count > 1}
