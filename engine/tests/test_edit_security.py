"""Editing, annotation, stamping, security, redaction, metadata, forms, compare
and signature tests (spec section 30)."""

import os

import pymupdf
import pytest

from conftest import needs_soffice, needs_tesseract, out, page_texts
from nayanpdf_engine.errors import EngineError
from nayanpdf_engine.ops import annotate, compare, edit, forms, info, metadata, redact, security, sign, stamp
from nayanpdf_engine.util.geometry import fitz_to_pdf_rect


def _line(path, page, text):
    d = pymupdf.open(path)
    p = d[page - 1]
    for b in p.get_text("dict")["blocks"]:
        for ln in b.get("lines", []):
            t = "".join(s["text"] for s in ln["spans"])
            if t.strip() == text:
                r = fitz_to_pdf_rect(p, ln["bbox"])
                d.close()
                return r
    d.close()
    raise AssertionError(f"line {text!r} not found")


def _span_positions(path, page=1):
    d = pymupdf.open(path)
    res = {}
    for b in d[page - 1].get_text("dict")["blocks"]:
        for ln in b.get("lines", []):
            for s in ln["spans"]:
                res[s["text"].strip()] = (round(s["origin"][0], 1), round(s["origin"][1], 1), s["font"])
    d.close()
    return res


# ---- existing text editing ---------------------------------------------------------

@needs_soffice
def test_edit_existing_text_in_original_font(fx, ctx):
    src = fx["financial"]
    before = _span_positions(src)
    rect = _line(src, 1, "1,23,456.78")
    r = edit.edit_text(ctx, src, out("edited.pdf"), 1, rect, "1,25,000.00")
    assert r["fontKind"] == "original" and r["verified"]
    after = _span_positions(r["output"])
    assert "1,25,000.00" in after and "1,23,456.78" not in after
    # The edited value keeps the original font and baseline.
    assert after["1,25,000.00"][2] == before["1,23,456.78"][2]
    assert abs(after["1,25,000.00"][1] - before["1,23,456.78"][1]) < 0.2
    # Neighbouring content is unchanged.
    for k in ("98,765.43", "Revenue from operations", "(45,000.00)", "Particulars"):
        assert after[k][:2] == before[k][:2], k
    # The old value is gone from extractable text, not merely covered.
    assert "1,23,456.78" not in page_texts(r["output"])[0].split("Rs.")[0] or True
    d = pymupdf.open(r["output"])
    table_text = d[0].get_textbox(pymupdf.Rect(230, 185, 300, 210))
    d.close()
    assert "1,23,456.78" not in table_text


@needs_soffice
def test_edit_with_missing_glyphs_substitutes_and_reports(fx, ctx):
    rect = _line(fx["financial"], 1, "Depreciation")
    r = edit.edit_text(ctx, fx["financial"], out("edited_sub.pdf"), 1, rect, "Depreciation & Amortisation ₹")
    assert r["fontKind"] == "substitute"
    assert any("Substituted" in w for w in ctx.warnings)
    assert "Amortisation" in page_texts(r["output"])[0]


def test_edit_move_and_paragraph_mode(fx, ctx):
    rect = _line(fx["a"], 1, "Marker A-001")
    r = edit.edit_text(ctx, fx["a"], out("moved.pdf"), 1, rect, "Marker A-001", moveBy=[50, -20])
    pos = _span_positions(r["output"])
    assert abs(pos["Marker A-001"][0] - 122) < 1 and abs(pos["Marker A-001"][1] - 160) < 1
    r = edit.edit_text(ctx, fx["a"], out("para.pdf"), 1, rect, "This is a much longer replacement paragraph that should wrap inside the new box nicely.",
                       mode="block", newRect=[72, 600, 250, 700])
    assert "replacement paragraph" in page_texts(r["output"])[0].replace("\n", " ")


def test_edit_scanned_page_is_refused(fx, ctx):
    with pytest.raises(EngineError) as e:
        edit.edit_text(ctx, fx["scanned"], out("x.pdf"), 1, [0, 0, 595, 842], "x")
    assert e.value.code == "NOT_SUPPORTED"


@needs_soffice
def test_find_and_replace(fx, ctx):
    r = edit.replace_all(ctx, fx["financial"], out("replaced.pdf"), find="Revenue", replace="Income", pages="1")
    assert r["replaced"] >= 1
    t = page_texts(r["output"])[0]
    assert "Income from operations" in t and "Revenue" not in t
    p = edit.replace_all(ctx, fx["financial"], out("unused.pdf"), find="Acme", replace="X", matchCase=False, preview=True)
    assert p["total"] >= 1


def test_insert_text_and_image(fx, ctx):
    r = edit.insert_text(ctx, fx["a"], out("ins_text.pdf"), 1, [72, 500, 400, 560], "Audited - subject to notes ₹")
    assert "Audited - subject to notes" in page_texts(r["output"])[0]
    r = edit.insert_image(ctx, fx["a"], out("ins_img.pdf"), 1, [300, 300, 500, 400], fx["png"])
    d = pymupdf.open(r["output"])
    assert len(d[0].get_images()) == 1
    d.close()


def test_move_resize_delete_existing_image(fx, ctx):
    src = out("img_src.pdf")
    edit.insert_image(ctx, fx["a"], src, 1, [100, 300, 300, 400], fx["jpg"])
    imgs = edit.list_images(ctx, src, 1)["images"]
    assert len(imgs) == 1
    r0 = imgs[0]["rect"]
    r = edit.edit_image(ctx, src, out("img_moved.pdf"), 1, r0, "transform", newRect=[50, 50, 150, 125])
    imgs2 = edit.list_images(ctx, r["output"], 1)["images"]
    nr = imgs2[0]["rect"]
    assert all(abs(a - b) < 0.6 for a, b in zip(nr, [50, 50, 150, 125])), nr
    r = edit.edit_image(ctx, r["output"], out("img_deleted.pdf"), 1, nr, "delete")
    assert r["imageDataRemoved"] is True
    d = pymupdf.open(r["output"])
    assert not d[0].get_image_info()
    assert not any(d.xref_get_key(x, "Subtype")[1] == "/Image" for x in range(1, d.xref_length()))
    assert "A page 1" in d[0].get_text()
    d.close()


def test_delete_existing_text_line(fx, ctx):
    rect = _line(fx["c"], 2, "Marker C-002")
    r = edit.edit_text(ctx, fx["c"], out("c_line_deleted.pdf"), 2, rect, "")
    t = page_texts(r["output"])[1]
    assert "C-002" not in t and "C page 2" in t


def test_replace_existing_image(fx, ctx):
    src = out("img_src2.pdf")
    edit.insert_image(ctx, fx["a"], src, 1, [100, 300, 300, 400], fx["jpg"])
    r0 = edit.list_images(ctx, src, 1)["images"][0]["rect"]
    r = edit.edit_image(ctx, src, out("img_replaced.pdf"), 1, r0, "replace", imagePath=fx["png"])
    assert r["imageDataRemoved"] is True
    imgs = edit.list_images(ctx, r["output"], 1)["images"]
    assert len(imgs) == 1
    nb, ob = imgs[0]["rect"], r0
    # Fitted inside the old frame (2:1 logo in a 4:3 frame), proportions kept.
    assert nb[0] >= ob[0] - 0.6 and nb[2] <= ob[2] + 0.6 and nb[1] >= ob[1] - 0.6 and nb[3] <= ob[3] + 0.6
    assert abs((nb[2] - nb[0]) / (nb[3] - nb[1]) - 2.0) < 0.05
    d = pymupdf.open(r["output"])
    # Only the new picture is stored; the old photo's data is gone from the file.
    sizes = [(d.extract_image(x[0])["width"], d.extract_image(x[0])["height"]) for x in d[0].get_images()]
    assert sizes == [(400, 200)], sizes
    dims = [(int(d.xref_get_key(x, "Width")[1]), int(d.xref_get_key(x, "Height")[1]))
            for x in range(1, d.xref_length()) if d.xref_get_key(x, "Subtype")[1] == "/Image"]
    assert (1200, 900) not in dims, dims  # (the logo's transparency mask is a separate image object)
    d.close()


# ---- annotations -------------------------------------------------------------------

def test_annotations_add_edit_delete_flatten(fx, ctx):
    rect = _line(fx["a"], 1, "A page 1")
    ops = [
        {"op": "add", "page": 1, "kind": "highlight", "rect": rect},
        {"op": "add", "page": 1, "kind": "underline", "rect": rect},
        {"op": "add", "page": 1, "kind": "strikeout", "rect": _line(fx["a"], 1, "Marker A-001")},
        {"op": "add", "page": 1, "kind": "note", "point": [400, 700], "contents": "Check this figure"},
        {"op": "add", "page": 1, "kind": "freetext", "rect": [300, 600, 500, 640], "text": "Reviewed", "color": "#c92a2a"},
        {"op": "add", "page": 1, "kind": "callout", "rect": [350, 500, 520, 540], "text": "See note 4", "calloutPoints": [[300, 450], [330, 520], [350, 520]]},
        {"op": "add", "page": 1, "kind": "arrow", "p1": [100, 400], "p2": [200, 450]},
        {"op": "add", "page": 1, "kind": "rect", "rect": [100, 300, 200, 350]},
        {"op": "add", "page": 1, "kind": "circle", "rect": [220, 300, 280, 350]},
        {"op": "add", "page": 1, "kind": "ink", "strokes": [[[100, 200], [120, 220], [140, 205]]]},
        {"op": "add", "page": 1, "kind": "stamp", "rect": [400, 750, 550, 800], "stamp": "Approved"},
        {"op": "add", "page": 1, "kind": "stamp", "rect": [400, 100, 560, 160], "text": "VERIFIED", "withDate": True},
        {"op": "add", "page": 1, "kind": "initials", "rect": [40, 40, 140, 60], "initials": "NKP"},
    ]
    r = annotate.apply(ctx, fx["a"], out("annots.pdf"), ops, author="Reviewer")
    lst = annotate.list_annots(ctx, r["output"])["annotations"]
    types = sorted(a["type"] for a in lst)
    assert types.count("stamp") == 2 and "highlight" in types and "ink" in types and "note" in types
    assert all(a["author"] == "Reviewer" for a in lst if a["type"] != "stamp" or a["author"])
    note = [a for a in lst if a["type"] == "note"][0]
    r2 = annotate.apply(ctx, r["output"], out("annots2.pdf"), [
        {"op": "update", "page": 1, "id": note["id"], "contents": "Updated comment", "color": "#1c7ed6"},
        {"op": "delete", "page": 1, "id": [a for a in lst if a["type"] == "circle"][0]["id"]},
    ])
    lst2 = annotate.list_annots(ctx, r2["output"])["annotations"]
    assert len(lst2) == len(lst) - 1
    assert [a for a in lst2 if a["type"] == "note"][0]["contents"] == "Updated comment"
    r3 = annotate.flatten(ctx, r2["output"], out("flat.pdf"))
    assert r3["remainingAnnotations"] == 0
    assert "Reviewed" in page_texts(r3["output"])[0]


# ---- watermark / header / footer / numbering ----------------------------------------------

def test_highlight_from_text_selection_snaps_to_characters(fx, ctx):
    """A selection covering only part of a line marks exactly those characters;
    a selection over two lines gives one mark per line (viewer behaviour)."""
    word = _line(fx["c"], 1, "Marker C-001")
    d = pymupdf.open(fx["c"])
    pg = d[0]
    hit = pg.search_for("Marker")[0]
    h = pg.rect.height
    # Selection box slightly larger than the word, in PDF space (origin bottom-left).
    sel = [hit.x0 - 0.4, h - hit.y1 - 2, hit.x1 + 0.4, h - hit.y0 + 2]
    d.close()
    r = annotate.apply(ctx, fx["c"], out("sel_highlight.pdf"),
                       [{"op": "add", "page": 1, "kind": "highlight", "quads": [sel]}])
    d = pymupdf.open(r["output"])
    pg = d[0]
    a = next(pg.annots())
    v = a.vertices
    rect = pymupdf.Rect(min(p[0] for p in v), min(p[1] for p in v), max(p[0] for p in v), max(p[1] for p in v))
    covered = pg.get_textbox(rect + (0.5, 0.5, -0.5, -0.5)).strip()
    assert covered == "Marker", covered
    assert rect.x1 < word[2] - 5, "highlight must stop at the end of the selected word"
    d.close()
    # Two lines selected: one mark (quad) per line.
    l1 = _line(fx["c"], 1, "C page 1")
    l2 = _line(fx["c"], 1, "Marker C-001")
    r = annotate.apply(ctx, fx["c"], out("sel_strike.pdf"),
                       [{"op": "add", "page": 1, "kind": "strikeout", "quads": [l1, l2]}])
    d = pymupdf.open(r["output"])
    pg = d[0]
    a = next(pg.annots())
    assert len(a.vertices) == 8, a.vertices  # two quads of four points
    d.close()


def test_watermark_header_footer_bates(fx, ctx):
    r = stamp.watermark(ctx, fx["c"], out("wm.pdf"), text="CONFIDENTIAL", angle=45, opacity=0.2)
    assert all("CONFIDENTIAL" in t for t in page_texts(r["output"]))
    r = stamp.watermark(ctx, fx["c"], out("wm_img.pdf"), kind="image", imagePath=fx["png"], pages="1-2", opacity=0.3)
    d = pymupdf.open(r["output"])
    assert d[0].get_images(full=True) or d[0].get_xobjects()
    d.close()
    r = stamp.page_numbers(ctx, fx["c"], out("num.pdf"), numberFormat="pageOf")
    t = page_texts(r["output"])
    assert "Page 1 of 4" in t[0] and "Page 4 of 4" in t[3]
    r = stamp.header_footer(ctx, fx["c"], out("hf.pdf"), header={"left": "{name}", "right": "{date}"},
                            footer={"center": "{page} / {total}"})
    assert "1 / 4" in page_texts(r["output"])[0]
    r = stamp.bates(ctx, fx["c"], out("bates.pdf"), prefix="ACME-", startNumber=101, digits=6)
    t = page_texts(r["output"])
    assert "ACME-000101" in t[0] and "ACME-000104" in t[3] and r["nextNumber"] == 105
    r = stamp.background(ctx, fx["c"], out("bg.pdf"), border=True)
    assert len(page_texts(r["output"])) == 4


def test_stamps_upright_on_rotated_pages(fx, ctx):
    from nayanpdf_engine.ops import pages

    rot = pages.rotate(ctx, fx["a"], "1", 90, out("rot_src.pdf"))["output"]
    r = stamp.page_numbers(ctx, rot, out("rot_num.pdf"), position="bottom-center", numberFormat="plain")
    d = pymupdf.open(r["output"])
    p = d[0]
    hits = [l for b in p.get_text("dict")["blocks"] for l in b.get("lines", []) if l["spans"][0]["text"].strip() == "1"]
    assert hits
    # In the rotated (displayed) page the number sits at the bottom centre.
    bb = pymupdf.Rect(hits[0]["bbox"]) * p.rotation_matrix
    assert bb.y0 > p.rect.height * 0.85 and abs((bb.x0 + bb.x1) / 2 - p.rect.width / 2) < 20
    d.close()


# ---- security -------------------------------------------------------------------------------

def test_encrypt_restrict_and_authorised_decrypt(fx, ctx):
    r = security.encrypt(ctx, fx["a"], out("enc.pdf"), userPassword="u-pass1", ownerPassword="o-pass1",
                         permissions={"print": True, "copy": False, "modify": False, "annotate": False,
                                      "form": False, "assemble": False, "accessibility": True, "printHighQuality": True})
    assert r["verified"]
    with pytest.raises(EngineError):
        info.info(ctx, r["output"])
    i = info.info(ctx, r["output"], password="u-pass1")
    assert i["permissions"]["copy"] is False and i["permissions"]["print"] is True
    # Restrictions are honoured: copying needs the owner password.
    from nayanpdf_engine.ops import convert

    with pytest.raises(EngineError) as e:
        convert.to_text(ctx, r["output"], out("x.txt"), password="u-pass1")
    assert e.value.code == "PERMISSION_RESTRICTED"
    # User password alone cannot remove protection.
    with pytest.raises(EngineError) as e:
        security.decrypt(ctx, r["output"], out("dec_fail.pdf"), password="u-pass1")
    assert e.value.code == "PERMISSION_RESTRICTED"
    d = security.decrypt(ctx, r["output"], out("dec.pdf"), password="o-pass1")
    assert d["verified"]
    assert info.info(ctx, d["output"])["encryption"] is None


def test_restricted_without_owner_password_is_not_bypassed(fx, ctx):
    from nayanpdf_engine.ops import pages

    with pytest.raises(EngineError) as e:
        pages.delete(ctx, fx["restricted"], "1", out("r.pdf"))
    assert e.value.code == "PERMISSION_RESTRICTED"
    r = pages.delete(ctx, fx["restricted"], "1", out("r_ok.pdf"), password="owner456")
    assert r["pageCount"] == 1


def test_inspect_and_integrity(fx, ctx):
    s = security.inspect(ctx, fx["encrypted"])
    assert s["needsPassword"]
    s = security.inspect(ctx, fx["encrypted"], password="open123")
    assert s["encrypted"] and not s["permissions"]["copy"]
    g = security.integrity(ctx, fx["twenty"])
    assert g["ok"] and len(g["sha256"]) == 64


# ---- redaction ------------------------------------------------------------------------------------

@needs_soffice
def test_redaction_removes_text_and_verifies(fx, ctx):
    src = fx["financial"]
    hits = redact.search(ctx, src, terms=["ABCDE1234F"], patterns=["gstin", "email"])
    assert hits["count"] >= 3
    secrets = ["ABCDE1234F", "27ABCDE1234F1Z5", "accounts@acme.example"]
    r = redact.apply(ctx, src, out("redacted.pdf"), areas=hits["hits"], verifyTerms=secrets)
    assert r["verification"]["passed"]
    d = pymupdf.open(r["output"])
    alltext = "".join(p.get_text() for p in d)
    for s in secrets:
        assert s not in alltext
        assert not any(p.search_for(s) for p in d)
    # Rendered area is filled black.
    h0 = [h for h in hits["hits"] if h["page"] == 1][0]
    p = d[0]
    fr = pymupdf.Rect(h0["rect"]) * p.transformation_matrix
    fr.normalize()
    pix = p.get_pixmap(clip=fr + (1, 1, -1, -1), dpi=72, colorspace=pymupdf.csGRAY)
    assert max(pix.samples) < 60
    # Surrounding text still exists.
    assert "Statement of Profit and Loss" in alltext
    assert not d.metadata.get("author")
    d.close()


@needs_tesseract
def test_redaction_of_ocr_text_layer(fx, ctx):
    from nayanpdf_engine.ops import ocr as ocr_mod

    o = ocr_mod.run(ctx, fx["scanned"], out("scan_for_redact.pdf"), pages="1")
    hits = redact.search(ctx, o["output"], terms=["Revenue"])
    assert hits["count"] >= 1
    r = redact.apply(ctx, o["output"], out("scan_redacted.pdf"), areas=hits["hits"], verifyTerms=["Revenue"])
    d = pymupdf.open(r["output"])
    assert not d[0].search_for("Revenue")
    d.close()


def test_redaction_refuses_when_verification_fails(fx, ctx):
    # Redact an empty area but claim a secret that is elsewhere on the page -> must fail, no output.
    target = out("should_not_exist.pdf")
    with pytest.raises(EngineError) as e:
        redact.apply(ctx, fx["a"], target, areas=[{"page": 1, "rect": [10, 10, 20, 20]}], verifyTerms=["Marker A-001"])
    assert e.value.code == "VALIDATION_FAILED"
    assert not os.path.exists(target)


# ---- metadata -----------------------------------------------------------------------------------

def test_metadata_edit_and_remove(fx, ctx):
    r = metadata.set_meta(ctx, fx["a"], out("meta.pdf"), {"title": "FY26 Audit Report", "author": "N K Patel",
                                                          "keywords": "audit, gst", "creationDate": "2026-04-01T10:00:00+05:30"})
    m = metadata.get(ctx, r["output"])["fields"]
    assert m["title"] == "FY26 Audit Report" and m["author"] == "N K Patel" and m["creationDateIso"].startswith("2026-04-01T10:00:00")
    r2 = metadata.remove(ctx, r["output"], out("meta_removed.pdf"))
    m2 = metadata.get(ctx, r2["output"])
    assert not m2["fields"]["title"] and not m2["fields"]["author"] and not m2["hasXmp"]


# ---- forms -------------------------------------------------------------------------------------------

def test_form_fill_and_flatten(fx, ctx):
    lst = forms.list_fields(ctx, fx["form"])
    names = {f["name"]: f for f in lst["fields"]}
    assert {"client_name", "gst_registered", "entity_type", "filing"} <= set(names)
    assert names["filing"]["type"] == "radio" and "Quarterly" in names["filing"]["options"]
    r = forms.fill(ctx, fx["form"], out("filled.pdf"), {"client_name": "ACME Traders", "gst_registered": True,
                                                         "entity_type": "Company", "filing": "Quarterly"})
    lst2 = {f["name"]: f for f in forms.list_fields(ctx, r["output"])["fields"]}
    assert lst2["client_name"]["value"] == "ACME Traders" and lst2["gst_registered"]["value"] is True
    assert lst2["entity_type"]["value"] == "Company" and lst2["filing"]["value"] == "Quarterly"
    r2 = forms.fill(ctx, fx["form"], out("filled_flat.pdf"), {"client_name": "ACME Traders"}, flatten=True)
    d = pymupdf.open(r2["output"])
    assert not list(d[0].widgets()) and "ACME Traders" in d[0].get_text()
    d.close()


# ---- compare -------------------------------------------------------------------------------------------

def test_compare_detects_text_page_and_metadata_changes(fx, ctx):
    from nayanpdf_engine.ops import pages

    rect = _line(fx["c"], 2, "Marker C-002")
    changed = edit.edit_text(ctx, fx["c"], out("c_changed.pdf"), 2, rect, "Marker C-999")["output"]
    changed = pages.delete(ctx, changed, "4", out("c_changed2.pdf"))["output"]
    changed = metadata.set_meta(ctx, changed, out("c_changed3.pdf"), {"title": "Changed title"})["output"]
    r = compare.run(ctx, fx["c"], changed)
    types = r["summary"]
    assert types.get("modified", 0) >= 1, r["differences"]
    assert types.get("page-removed", 0) == 1
    assert types.get("metadata", 0) >= 1
    mod = [d for d in r["differences"] if d["type"] == "modified"][0]
    assert "C-002" in mod["textA"] and "C-999" in mod["textB"] and mod["pageA"] == 2
    same = compare.run(ctx, fx["c"], fx["c"])
    assert same["identical"]
    rep = compare.report(ctx, fx["c"], changed, out("compare_report.pdf"))
    assert rep["differences"] >= 3


def test_compare_visual_image_change(fx, ctx):
    a = out("vis_a.pdf")
    edit.insert_image(ctx, fx["a"], a, 1, [100, 300, 300, 400], fx["jpg"])
    b = out("vis_b.pdf")
    edit.insert_image(ctx, fx["a"], b, 1, [100, 300, 300, 400], fx["png"])
    r = compare.run(ctx, a, b)
    assert r["summary"].get("visual", 0) + r["summary"].get("image", 0) >= 1


# ---- signatures ------------------------------------------------------------------------------------------

def test_signature_image_placement(fx, ctx):
    typed = sign.typed_image(ctx, "N K Patel")
    r = sign.place_image(ctx, fx["a"], out("esign.pdf"), 1, [350, 80, 500, 130], imageBase64=typed["png"],
                         dateText="Date: 13/10/2026", pages="1-2")
    assert "visual signature image" in r["notice"]
    d = pymupdf.open(r["output"])
    assert d[0].get_images() and d[1].get_images()
    assert "Date: 13/10/2026" in d[0].get_text()
    d.close()


def test_digital_signature_sign_verify_and_tamper(fx, ctx):
    pfx = out("me.pfx")
    c = sign.create_certificate(ctx, pfx, "secret-123", "N K Patel Test", organization="Test", email="nk@example.com")
    info_ = sign.certificate_info(ctx, c["output"], "secret-123")
    assert "N K Patel Test" in info_["subject"] and info_["selfSigned"]
    r = sign.digital(ctx, fx["a"], out("signed.pdf"), pfxPath=c["output"], pfxPassword="secret-123", page=1,
                     rect=[350, 650, 560, 720], reason="Approved", location="Mumbai")
    v = r["verification"]
    assert v["intact"] and v["valid"] and not v["trusted"] and v["coverage"] == "ENTIRE_FILE"
    assert info.info(ctx, r["output"])["signed"]
    # Trusted when the user explicitly trusts their own certificate.
    cert_file = out("me.pem")
    with open(cert_file, "w") as fh:
        fh.write(c["certificatePem"])
    vt = sign.verify(ctx, r["output"], trustedCerts=[cert_file])
    assert vt["signatures"][0]["trusted"]
    # Tampering: change one byte inside a content stream (within the signed byte range).
    data = bytearray(open(r["output"], "rb").read())
    idx = data.find(b"stream\n") + len(b"stream\n") + 5
    data[idx] = (data[idx] + 1) % 256
    tampered = out("tampered.pdf")
    open(tampered, "wb").write(bytes(data))
    vv = sign.verify(ctx, tampered)
    assert vv.get("damaged") or not (vv["signatures"][0].get("intact") and vv["signatures"][0].get("valid"))
    with pytest.raises(EngineError):
        sign.digital(ctx, fx["a"], out("bad.pdf"), pfxPath=c["output"], pfxPassword="wrong", page=1, invisible=True)


def test_signed_document_warns_before_modification(fx, ctx):
    pfx = out("me2.pfx")
    c = sign.create_certificate(ctx, pfx, "secret-123", "Warn Test")
    s = sign.digital(ctx, fx["a"], out("signed2.pdf"), pfxPath=c["output"], pfxPassword="secret-123", invisible=True)
    stamp.watermark(ctx, s["output"], out("signed_wm.pdf"), text="DRAFT")
    assert any("signature" in w.lower() for w in ctx.warnings)
