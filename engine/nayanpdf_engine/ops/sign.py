"""Signatures.

Two different things, deliberately kept apart:

* Electronic signature placement (``sign.placeImage``) puts a picture of a
  signature (drawn, typed or imported) on the page. It does NOT prove who
  signed or protect the document against changes.

* Certificate-based digital signatures (``sign.digital``) use pyHanko to
  embed a CMS/PAdES signature made with a private key (PKCS#12 file or a
  PKCS#11 token such as an Indian DSC USB token). Validators can then detect
  any later change and show the signer's certificate.

Validation runs fully offline (``allow_fetching=False``): integrity and the
cryptographic signature are checked; certificate revocation (CRL/OCSP) cannot
be checked without network access and is reported as "not checked".
"""

from __future__ import annotations

import base64
import datetime as _dt
import io
import os

import pymupdf

from ..errors import EngineError, VALIDATION_FAILED, invalid
from ..registry import method
from ..util.geometry import to_fitz_rect
from ..util.io import OutputFile, os_path, require_file, validate_pdf
from ..util.pdf import doc_is_signed, open_pdf, parse_pages, save_doc


# ---------------------------------------------------------------------------
# Electronic signature images
# ---------------------------------------------------------------------------

@method("sign.placeImage")
def place_image(ctx, path: str, output: str, page: int, rect, imagePath: str | None = None,
                imageBase64: str | None = None, pages=None, dateText: str | None = None,
                dateFontSize: float = 9, flatten: bool = True, password: str | None = None,
                overwrite: bool = False, space: str = "pdf"):
    """Place a signature image (and optional date line under it) on one or more pages."""
    if imageBase64:
        data = base64.b64decode(imageBase64.split(",", 1)[-1])
    elif imagePath:
        require_file(imagePath)
        from .edit import _image_bytes

        data = _image_bytes(imagePath)
    else:
        raise invalid("A signature image is required.")
    doc = open_pdf(path, password, require=["modify" if flatten else "annotate"])
    if doc_is_signed(doc):
        ctx.warn("This document already carries a digital signature. Adding a signature image changes the document and will be reported by validators.")
    targets = parse_pages(pages, doc.page_count) if pages else [page - 1]
    for i in targets:
        pg = doc[i]
        box = to_fitz_rect(pg, rect, space)
        if flatten:
            pg.insert_image(box, stream=data, keep_proportion=True, overlay=True)
        else:
            a = pg.add_stamp_annot(box, stamp=data)
            a.set_info(content="Signature image", title="N PDF")
            a.update()
        if dateText:
            dr = pymupdf.Rect(box.x0, box.y1 + 1, box.x1 + 80, box.y1 + float(dateFontSize) * 1.6 + 2)
            pg.insert_textbox(dr, dateText, fontsize=float(dateFontSize), fontname="helv", color=(0.1, 0.1, 0.1))
    out = OutputFile(ctx, output, overwrite, sources=[path])
    n = doc.page_count
    save_doc(doc, out.temp, garbage=1)
    doc.close()
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final, "pages": [t + 1 for t in targets],
            "notice": "This is a visual signature image. It does not cryptographically prove identity or protect the document from changes."}


@method("sign.cleanImage")
def clean_image(ctx, imagePath: str, threshold: int = 200):
    """Make the light background of a scanned/photographed signature transparent."""
    from PIL import Image, ImageOps

    require_file(imagePath)
    im = Image.open(imagePath).convert("RGBA")
    gray = ImageOps.grayscale(im)
    alpha = gray.point(lambda v: 0 if v >= threshold else min(255, int((threshold - v) * 255 / max(1, threshold - 40))))
    im.putalpha(alpha)
    bbox = alpha.getbbox()
    if bbox:
        im = im.crop(bbox)
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return {"png": base64.b64encode(buf.getvalue()).decode("ascii"), "width": im.width, "height": im.height}


@method("sign.typedImage")
def typed_image(ctx, text: str, fontFile: str | None = None, color: str = "#1a237e", size: int = 120):
    """Render a typed name as a signature-style PNG (using a bundled script font if available)."""
    from PIL import Image, ImageDraw, ImageFont

    from ..util.pdf import hex_to_rgb
    from ..util.tools import resources_dir

    if not text.strip():
        raise invalid("Type your name.")
    candidates = [fontFile] if fontFile else []
    fdir = os.path.join(resources_dir(), "fonts")
    candidates += [os.path.join(fdir, f) for f in ("GreatVibes-Regular.ttf", "DancingScript-Regular.ttf")]
    font = None
    for c in candidates:
        if c and os.path.exists(c):
            font = ImageFont.truetype(c, size)
            break
    if font is None:
        font = ImageFont.truetype(io.BytesIO(pymupdf.Font("notosit").buffer), size)
    d = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    bb = d.textbbox((0, 0), text, font=font)
    w, h = bb[2] - bb[0] + 40, bb[3] - bb[1] + 40
    im = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    ImageDraw.Draw(im).text((20 - bb[0], 20 - bb[1]), text, font=font,
                            fill=tuple(int(c * 255) for c in hex_to_rgb(color)) + (255,))
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return {"png": base64.b64encode(buf.getvalue()).decode("ascii"), "width": w, "height": h}


# ---------------------------------------------------------------------------
# Certificates
# ---------------------------------------------------------------------------

@method("sign.createCertificate")
def create_certificate(ctx, output: str, password: str, commonName: str, organization: str = "",
                       email: str = "", country: str = "IN", years: int = 3, overwrite: bool = False):
    """Create a self-signed signing certificate (.pfx). Useful for personal integrity
    protection; recipients will see it as 'not trusted' unless they trust it explicitly."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import pkcs12
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID, ObjectIdentifier

    if not password or len(password) < 6:
        raise invalid("Choose a certificate password of at least 6 characters.")
    if not commonName.strip():
        raise invalid("Enter the signer's name.")
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    attrs = [x509.NameAttribute(NameOID.COMMON_NAME, commonName.strip())]
    if organization:
        attrs.append(x509.NameAttribute(NameOID.ORGANIZATION_NAME, organization))
    if email:
        attrs.append(x509.NameAttribute(NameOID.EMAIL_ADDRESS, email))
    if country and len(country) == 2:
        attrs.append(x509.NameAttribute(NameOID.COUNTRY_NAME, country.upper()))
    name = x509.Name(attrs)
    now = _dt.datetime.now(_dt.timezone.utc)
    builder = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
               .serial_number(x509.random_serial_number()).not_valid_before(now - _dt.timedelta(minutes=5))
               .not_valid_after(now + _dt.timedelta(days=365 * max(1, min(10, int(years)))))
               .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
               .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=True, key_encipherment=False,
                                            data_encipherment=False, key_agreement=False, key_cert_sign=False,
                                            crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
               .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.EMAIL_PROTECTION,
                                                     ObjectIdentifier("1.3.6.1.4.1.311.10.3.12")]), critical=False)
               .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False))
    cert = builder.sign(key, hashes.SHA256())
    pfx = pkcs12.serialize_key_and_certificates(
        commonName.encode("utf-8"), key, cert, None,
        serialization.BestAvailableEncryption(password.encode("utf-8")))
    out = OutputFile(ctx, output, overwrite)
    with open(out.temp, "wb") as fh:
        fh.write(pfx)
    final = out.commit(lambda p: _check_pfx(p, password))
    cert_pem = cert.public_bytes(serialization.Encoding.PEM).decode("ascii")
    return {"output": final, "subject": commonName, "notAfter": cert.not_valid_after_utc.isoformat(),
            "certificatePem": cert_pem}


def _check_pfx(path: str, password: str) -> dict:
    from cryptography.hazmat.primitives.serialization import pkcs12

    with open(path, "rb") as fh:
        key, cert, _ = pkcs12.load_key_and_certificates(fh.read(), password.encode("utf-8"))
    if key is None or cert is None:
        raise EngineError(VALIDATION_FAILED, "The certificate file does not contain a key and certificate.")
    return {"subject": cert.subject.rfc4514_string()}


@method("sign.certificateInfo")
def certificate_info(ctx, pfxPath: str, password: str):
    from cryptography.hazmat.primitives.serialization import pkcs12

    require_file(pfxPath)
    try:
        with open(os_path(pfxPath), "rb") as fh:
            key, cert, extra = pkcs12.load_key_and_certificates(fh.read(), password.encode("utf-8"))
    except ValueError:
        raise EngineError("PASSWORD_INVALID", "The certificate password is incorrect or the file is not a PKCS#12 (.pfx/.p12) file.")
    if cert is None or key is None:
        raise invalid("The file does not contain a signing key and certificate.")
    now = _dt.datetime.now(_dt.timezone.utc)
    return {"subject": cert.subject.rfc4514_string(), "issuer": cert.issuer.rfc4514_string(),
            "notBefore": cert.not_valid_before_utc.isoformat(), "notAfter": cert.not_valid_after_utc.isoformat(),
            "expired": cert.not_valid_after_utc < now, "selfSigned": cert.subject == cert.issuer,
            "chain": len(extra or [])}


@method("sign.pkcs11List")
def pkcs11_list(ctx, libPath: str, pin: str | None = None):
    """List tokens and certificates available through a PKCS#11 driver DLL."""
    import pkcs11 as p11
    from pkcs11 import Attribute, ObjectClass

    require_file(libPath)
    try:
        lib = p11.lib(libPath)
    except Exception as e:  # noqa: BLE001
        raise EngineError("TOOL_MISSING", f"The PKCS#11 driver could not be loaded: {e}")
    tokens = []
    for slot in lib.get_slots(token_present=True):
        tok = slot.get_token()
        item = {"slot": slot.slot_id, "label": tok.label.strip(), "manufacturer": tok.manufacturer_id.strip(),
                "certificates": []}
        if pin:
            try:
                with tok.open(user_pin=pin) as sess:
                    for c in sess.get_objects({Attribute.CLASS: ObjectClass.CERTIFICATE}):
                        from asn1crypto import x509 as ax

                        der = c[Attribute.VALUE]
                        cert = ax.Certificate.load(der)
                        item["certificates"].append({
                            "label": c[Attribute.LABEL], "id": bytes(c[Attribute.ID]).hex(),
                            "subject": cert.subject.human_friendly,
                            "notAfter": cert["tbs_certificate"]["validity"]["not_after"].native.isoformat()})
            except Exception as e:  # noqa: BLE001
                item["error"] = f"Could not log in to the token: {type(e).__name__}"
        tokens.append(item)
    return {"tokens": tokens}


# ---------------------------------------------------------------------------
# Digital signing
# ---------------------------------------------------------------------------

def _stamp_style(text: str, image_b64: str | None):
    from pyhanko import stamp
    from pyhanko.pdf_utils import images, text as ptext

    bg = None
    if image_b64:
        from PIL import Image

        im = Image.open(io.BytesIO(base64.b64decode(image_b64.split(",", 1)[-1])))
        bg = images.PdfImage(im)
    return stamp.TextStampStyle(stamp_text=text, background=bg, border_width=1,
                                text_box_style=ptext.TextBoxStyle(font_size=8), background_opacity=0.9)


@method("sign.digital")
def digital(ctx, path: str, output: str, source: str = "pfx", pfxPath: str | None = None,
            pfxPassword: str | None = None, pkcs11Lib: str | None = None, pin: str | None = None,
            tokenLabel: str | None = None, certId: str | None = None, certLabel: str | None = None,
            page: int = 1, rect=None, invisible: bool = False, reason: str = "", location: str = "",
            contact: str = "", fieldName: str | None = None, certify: bool = False, signatureImage: str | None = None,
            password: str | None = None, overwrite: bool = False):
    """Apply a PAdES (B-B) digital signature as an incremental update."""
    from pyhanko.pdf_utils.incremental_writer import IncrementalPdfFileWriter
    from pyhanko.sign import fields, signers

    probe = open_pdf(path, password)
    n = probe.page_count
    if page < 1 or page > n:
        probe.close()
        raise invalid("Page out of range.")
    pg = probe[page - 1]
    box = None
    if not invisible:
        if not rect:
            probe.close()
            raise invalid("Draw the signature box on the page, or choose an invisible signature.")
        r = [float(x) for x in rect]
        box = (int(min(r[0], r[2])), int(min(r[1], r[3])), int(max(r[0], r[2])), int(max(r[1], r[3])))
        # pyHanko boxes are in default user space relative to the MediaBox origin.
        mb = pg.mediabox
    existing = [w.field_name for p in probe for w in p.widgets() if w.field_type == pymupdf.PDF_WIDGET_TYPE_SIGNATURE]
    probe.close()

    signer = None
    session = None
    try:
        if source == "pfx":
            require_file(pfxPath or "")
            try:
                signer = signers.SimpleSigner.load_pkcs12(pfx_file=os_path(pfxPath),
                                                          passphrase=(pfxPassword or "").encode("utf-8"))
            except Exception:  # noqa: BLE001
                signer = None
            if signer is None:
                raise EngineError("PASSWORD_INVALID", "The certificate could not be opened. Check the .pfx password.")
        elif source == "pkcs11":
            from pyhanko.config.pkcs11 import TokenCriteria
            from pyhanko.sign import pkcs11 as hp11

            require_file(pkcs11Lib or "")
            if not pin:
                raise invalid("Enter the token PIN.")
            try:
                session = hp11.open_pkcs11_session(pkcs11Lib, token_criteria=TokenCriteria(label=tokenLabel) if tokenLabel else None,
                                                   user_pin=pin)
            except Exception as e:  # noqa: BLE001
                raise EngineError("PASSWORD_INVALID", f"Could not log in to the token: {type(e).__name__}. Check the PIN and that the token is connected.")
            signer = hp11.PKCS11Signer(session, cert_label=certLabel, key_label=certLabel,
                                       cert_id=bytes.fromhex(certId) if certId else None,
                                       key_id=bytes.fromhex(certId) if certId else None)
        else:
            raise invalid("Unknown signing source.")

        cn = "the signer"
        try:
            cn = signer.signing_cert.subject.native.get("common_name") or cn
        except Exception:  # noqa: BLE001
            pass
        field = fieldName or f"Signature{len(existing) + 1}"
        while field in existing:
            field += "_1"
        meta = signers.PdfSignatureMetadata(field_name=field, reason=reason or None, location=location or None,
                                            contact_info=contact or None, md_algorithm="sha256",
                                            subfilter=fields.SigSeedSubFilter.PADES, certify=bool(certify))
        lines = [f"Digitally signed by {cn}", "Date: %(ts)s"]
        if reason:
            lines.append(f"Reason: {reason}")
        if location:
            lines.append(f"Location: {location}")
        style = _stamp_style("\n".join(lines), signatureImage) if not invisible else None
        spec = fields.SigFieldSpec(sig_field_name=field, on_page=page - 1, box=box)
        pdf_signer = signers.PdfSigner(meta, signer=signer, stamp_style=style, new_field_spec=spec)
        out = OutputFile(ctx, output, overwrite, sources=[path])
        with open(os_path(path), "rb") as inf:
            w = IncrementalPdfFileWriter(inf, strict=False)
            if w.security_handler is not None:
                if not password:
                    raise EngineError("PASSWORD_REQUIRED", "Password required.")
                w.encrypt(password)
            with open(out.temp, "wb") as outf:
                pdf_signer.sign_pdf(w, output=outf)
    finally:
        if session is not None:
            try:
                session.close()
            except Exception:  # noqa: BLE001
                pass
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    status = verify(ctx, final, password)
    mine = [s for s in status["signatures"] if s["field"] == field]
    if not mine or not mine[0]["intact"] or not mine[0]["valid"]:
        raise EngineError(VALIDATION_FAILED, "The signature was written but did not validate. Do not rely on this file.")
    return {"output": final, "field": field, "signer": cn, "verification": mine[0]}


@method("sign.verify")
def verify(ctx, path: str, password: str | None = None, trustedCerts: list | None = None):
    """Validate every embedded signature offline."""
    from asn1crypto import x509 as ax
    from pyhanko.pdf_utils.reader import PdfFileReader
    from pyhanko.sign.validation import validate_pdf_signature
    from pyhanko_certvalidator import ValidationContext

    roots = []
    for c in trustedCerts or []:
        try:
            data = open(os_path(c), "rb").read() if os.path.exists(os_path(c)) else c.encode("ascii")
            if b"-----BEGIN" in data:
                from asn1crypto import pem

                _, _, data = pem.unarmor(data)
            roots.append(ax.Certificate.load(data))
        except Exception:  # noqa: BLE001
            ctx.warn("A trusted certificate could not be read and was ignored.")
    vc = ValidationContext(trust_roots=roots or None, allow_fetching=False, revocation_mode="soft-fail")
    results = []
    with open(os_path(path), "rb") as fh:
        try:
            r = PdfFileReader(fh, strict=False)
            if r.security_handler is not None:
                if not password:
                    raise EngineError("PASSWORD_REQUIRED", "Password required to verify signatures.")
                r.decrypt(password)
            sigs = list(r.embedded_signatures)
        except EngineError:
            raise
        except Exception as e:  # noqa: BLE001
            return {"signatures": [], "count": 0, "damaged": True,
                    "error": f"The file structure is damaged, so signatures cannot be validated ({type(e).__name__}). Treat any signature in it as INVALID.",
                    "note": "Integrity could not be established."}
        for es in sigs:
            item = {"field": es.field_name}
            try:
                st = validate_pdf_signature(es, signer_validation_context=vc)
                cert = st.signing_cert
                item.update({
                    "signer": cert.subject.human_friendly if cert else "",
                    "issuer": cert.issuer.human_friendly if cert else "",
                    "signedAt": st.signer_reported_dt.isoformat() if st.signer_reported_dt else None,
                    "intact": bool(st.intact),
                    "valid": bool(st.valid),
                    "trusted": bool(st.trusted),
                    "coverage": str(st.coverage.name) if st.coverage else None,
                    "modificationLevel": str(st.modification_level.name) if st.modification_level else None,
                    "docmdpOk": getattr(st, "docmdp_ok", None),
                    "revocation": "not checked (offline)",
                    "summary": st.summary(),
                    "selfSigned": bool(cert and cert.self_signed),
                })
            except Exception as e:  # noqa: BLE001
                item.update({"intact": False, "valid": False, "trusted": False, "error": f"{type(e).__name__}: {e}"[:300]})
            results.append(item)
    return {"signatures": results, "count": len(results),
            "note": "Integrity and cryptographic validity are checked offline. Certificate revocation status is not checked. 'Trusted' means the certificate chains to a certificate you marked as trusted."}
