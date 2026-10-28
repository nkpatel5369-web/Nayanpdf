"""Prepare a form: creating, changing and deleting fields, field detection,
and filling radio groups (one button on, the rest off)."""

import pymupdf
import pytest

from conftest import out
from nayanpdf_engine.errors import EngineError
from nayanpdf_engine.ops import formdesign, forms


def _blank_form(path):
    """A flat client registration form with no fields: the kind a CA office
    receives as a Word export or a scan."""
    doc = pymupdf.open()
    p = doc.new_page()
    p.insert_text((72, 100), "Client registration form", fontsize=16)
    p.insert_text((72, 150), "Name: ______________________", fontsize=11)
    p.insert_text((72, 180), "PAN", fontsize=11)
    p.draw_line((140, 182), (360, 182))
    p.insert_text((72, 210), "Email address", fontsize=11)
    p.draw_rect(pymupdf.Rect(170, 196, 420, 214))
    p.draw_rect(pymupdf.Rect(72, 232, 84, 244))
    p.insert_text((90, 242), "GST registered", fontsize=11)
    p.draw_rect(pymupdf.Rect(200, 232, 212, 244))
    p.insert_text((218, 242), "MSME", fontsize=11)
    # A line with text already written above it is not a blank to fill.
    p.insert_text((72, 280), "Prepared by", fontsize=11)
    p.insert_text((150, 280), "N K Patel", fontsize=11)
    p.draw_line((140, 282), (360, 282))
    doc.save(path)
    doc.close()


def _fields(ctx, path):
    return {f["name"]: f for f in forms.list_fields(ctx, path)["fields"]}


def _contents(path):
    with pymupdf.open(path) as d:
        return d[0].read_contents()


def _all_types(ctx, tag):
    # Each test writes its own files: on Windows a file still open elsewhere cannot be replaced.
    src = out(f"fd_blank_{tag}.pdf")
    _blank_form(src)
    p1 = out(f"fd_fields_{tag}.pdf")
    r = formdesign.design(ctx, src, p1, [
        {"op": "add", "type": "text", "page": 1, "rect": [100, 600, 300, 620], "name": "Client name", "tooltip": "Full name", "required": True},
        {"op": "add", "type": "checkbox", "page": 1, "rect": [100, 570, 112, 582]},
        {"op": "add", "type": "radio", "page": 1, "name": "Scheme", "buttons": [
            {"rect": [100, 540, 112, 552], "export": "Regular"}, {"rect": [160, 540, 172, 552], "export": "Composition"}]},
        {"op": "add", "type": "combobox", "page": 1, "rect": [100, 500, 250, 516], "options": ["Q1", "Q2", "Q3", "Q4"]},
        {"op": "add", "type": "listbox", "page": 1, "rect": [300, 460, 400, 516], "options": ["Audit", "Tax"]},
        {"op": "add", "type": "signature", "page": 1, "rect": [300, 560, 450, 600]},
    ])
    return src, p1, r


def test_every_field_type_is_created_with_its_properties(ctx):
    src, p1, r = _all_types(ctx, "types")
    assert [c["name"] for c in r["created"]] == ["Client name", "Check Box1", "Scheme", "Scheme", "Dropdown1", "List Box1", "Signature1"]
    f = _fields(ctx, p1)
    assert {n: x["type"] for n, x in f.items()} == {"Client name": "text", "Check Box1": "checkbox", "Scheme": "radio",
                                                     "Dropdown1": "combobox", "List Box1": "listbox", "Signature1": "signature"}
    assert f["Client name"]["required"] and f["Client name"]["label"] == "Full name"
    assert f["Client name"]["widgets"][0]["rect"] == [100.0, 600.0, 300.0, 620.0]
    # One radio field with two buttons and their own values (not two fields both "Yes").
    assert f["Scheme"]["options"] == ["Regular", "Composition"] and len(f["Scheme"]["widgets"]) == 2
    assert f["Dropdown1"]["options"] == ["Q1", "Q2", "Q3", "Q4"]
    assert f["List Box1"]["options"] == ["Audit", "Tax"]
    # The page text is untouched.
    assert _contents(p1) == _contents(src)


def test_radio_group_fills_one_button_and_survives_reopening(ctx):
    _, p1, _ = _all_types(ctx, "radio")
    p2 = out("fd_radio_added.pdf")
    formdesign.design(ctx, p1, p2, [{"op": "add", "type": "radio", "page": 1, "name": "Scheme",
                                      "buttons": [{"rect": [220, 540, 232, 552], "export": "Exempt"}]},
                                     {"op": "update", "name": "Scheme", "required": True}])
    assert _fields(ctx, p2)["Scheme"]["options"] == ["Regular", "Composition", "Exempt"]
    # Each button inherits name, type and flags from the group: none of those keys,
    # and no "/Key null" left behind (PDF.js fails to open a page that has one).
    d = pymupdf.open(p2)
    for w in d[0].widgets():
        if w.field_name == "Scheme":
            obj = d.xref_object(w.xref, compressed=True)
            assert "null" not in obj and "/FT" not in obj and "/T(" not in obj and "/Ff" not in obj, obj
    d.close()
    # The selected button is neither first nor last: PyMuPDF on its own would also
    # switch on any button it is told to set to "Off".
    p3 = out("fd_radio_filled.pdf")
    forms.fill(ctx, p2, p3, {"Scheme": "Composition", "Client name": "ACME Traders", "Check Box1": True, "Dropdown1": "Q3"})
    d = pymupdf.open(p3)
    states = {}
    for w in d[0].widgets():
        if w.field_name == "Scheme":
            states[w.on_state()] = d.xref_get_key(w.xref, "AS")[1]
            parent = int(d.xref_get_key(w.xref, "Parent")[1].split()[0])
    assert states == {"Regular": "/Off", "Composition": "/Composition", "Exempt": "/Off"}
    assert d.xref_get_key(parent, "V") == ("name", "/Composition")
    d.close()
    f = _fields(ctx, p3)
    assert f["Scheme"]["value"] == "Composition" and f["Client name"]["value"] == "ACME Traders" and f["Check Box1"]["value"] is True
    assert f["Scheme"]["required"]  # filling did not override the group's flags
    # Switching to another choice turns the first one off.
    p4 = out("fd_radio_refilled.pdf")
    forms.fill(ctx, p3, p4, {"Scheme": "Exempt"})
    assert _fields(ctx, p4)["Scheme"]["value"] == "Exempt"


def test_rename_properties_move_and_delete(ctx):
    _, p1, _ = _all_types(ctx, "change")
    f = _fields(ctx, p1)
    first_radio = f["Scheme"]["widgets"][0]["xref"]
    p2 = out("fd_changed.pdf")
    formdesign.design(ctx, p1, p2, [
        {"op": "update", "name": "Client name", "newName": "Name of client", "multiline": True, "maxLength": 40, "required": False},
        {"op": "update", "name": "Scheme", "tooltip": "GST scheme", "required": True},
        {"op": "update", "name": "Dropdown1", "options": ["Apr-Jun", "Jul-Sep"]},
        {"op": "move", "page": 1, "xref": first_radio, "rect": [90, 530, 104, 544]},
        {"op": "delete", "name": "List Box1"},
    ])
    f = _fields(ctx, p2)
    assert "Client name" not in f and "List Box1" not in f
    t = f["Name of client"]
    assert t["multiline"] and t["maxLength"] == 40 and not t["required"]
    assert f["Scheme"]["required"] and f["Scheme"]["label"] == "GST scheme"
    assert f["Scheme"]["widgets"][0]["rect"] == [90.0, 530.0, 104.0, 544.0]
    assert f["Scheme"]["options"] == ["Regular", "Composition"]  # moving kept the button's value
    assert f["Dropdown1"]["options"] == ["Apr-Jun", "Jul-Sep"]
    # Removing one button keeps the group; removing the last removes the field.
    p3 = out("fd_one_button.pdf")
    xs = [w["xref"] for w in f["Scheme"]["widgets"]]
    formdesign.design(ctx, p2, p3, [{"op": "deleteWidget", "page": 1, "xref": xs[0]}])
    assert _fields(ctx, p3)["Scheme"]["options"] == ["Composition"]
    p4 = out("fd_no_group.pdf")
    formdesign.design(ctx, p3, p4, [{"op": "deleteWidget", "page": 1, "xref": xs[1]}])
    assert "Scheme" not in _fields(ctx, p4)
    d = pymupdf.open(p4)
    refs = d.xref_get_key(d.pdf_catalog(), "AcroForm/Fields")[1]
    assert all(d.xref_get_key(int(x), "T")[1] != "Scheme" for x in refs.replace("[", "").replace("]", "").replace("0 R", "").split())
    d.close()


def test_duplicate_names_and_bad_input_are_refused(ctx):
    _, p1, _ = _all_types(ctx, "bad")
    bad = [
        [{"op": "add", "type": "text", "page": 1, "rect": [10, 10, 100, 30], "name": "Scheme"}],
        [{"op": "add", "type": "radio", "page": 1, "name": "Scheme", "buttons": [{"rect": [10, 10, 22, 22], "export": "Regular"}]}],
        [{"op": "add", "type": "text", "page": 1, "rect": [10, 10, 12, 12]}],
        [{"op": "add", "type": "text", "page": 9, "rect": [10, 10, 100, 30]}],
        [{"op": "add", "type": "barcode", "page": 1, "rect": [10, 10, 100, 30]}],
        [{"op": "update", "name": "Client name", "newName": "Dropdown1"}],
        [{"op": "update", "name": "Dropdown1", "options": []}],
        [{"op": "delete", "name": "No such field"}],
    ]
    for ops in bad:
        with pytest.raises(EngineError):
            formdesign.design(ctx, p1, out("fd_bad.pdf"), ops)


def test_detect_suggests_blanks_with_their_labels(ctx):
    src = out("fd_detect.pdf")
    _blank_form(src)
    s = formdesign.detect(ctx, src)["suggestions"]
    got = {(x["name"], x["type"], x["source"]) for x in s}
    assert got == {("Name", "text", "underline"), ("PAN", "text", "line"), ("Email address", "text", "box"),
                   ("GST registered", "checkbox", "box"), ("MSME", "checkbox", "box")}
    # Suggestions are only suggestions: the file is unchanged and has no fields.
    assert forms.list_fields(ctx, src)["count"] == 0
    # Accepting them creates the fields; detecting again suggests nothing new.
    p2 = out("fd_detected.pdf")
    formdesign.design(ctx, src, p2, [{"op": "add", "type": x["type"], "page": x["page"], "rect": x["rect"], "name": x["name"]} for x in s])
    assert set(_fields(ctx, p2)) == {"Name", "PAN", "Email address", "GST registered", "MSME"}
    assert formdesign.detect(ctx, p2)["suggestions"] == []
