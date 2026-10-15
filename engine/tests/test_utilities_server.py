"""Utilities, JSON-lines server protocol, batch behaviour and source integrity."""

import json
import os
import subprocess
import sys

import pymupdf
import pytest

from conftest import needs_gs, out, page_texts, sha256
from nayanpdf_engine.errors import EngineError
from nayanpdf_engine.ops import utilities

ENGINE = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "run_engine.py"))


def test_repair_malformed(fx, ctx):
    r = utilities.repair(ctx, fx["malformed"], out("repaired.pdf"))
    assert r["pages"] >= 1
    d = pymupdf.open(r["output"])
    assert not d.is_repaired
    d.close()


def test_linearize(fx, ctx):
    import pikepdf

    r = utilities.linearize(ctx, fx["twenty"], out("lin.pdf"))
    with pikepdf.open(r["output"]) as p:
        assert p.is_linearized


@needs_gs
def test_pdfa_conversion_with_basic_checks(fx, ctx):
    r = utilities.pdfa(ctx, fx["a"], out("pdfa.pdf"), level="2b")
    c = r["checks"]
    assert c["xmpPdfaId"] and c["outputIntent"] and c["allFontsEmbedded"], c
    assert any("not a formal" in w for w in ctx.warnings)


def test_bookmarks_and_labels(fx, ctx):
    r = utilities.bookmarks_set(ctx, fx["c"], out("bm.pdf"), [
        {"level": 1, "title": "Financial statements", "page": 1},
        {"level": 2, "title": "Notes", "page": 3},
        {"level": 1, "title": "Annexure", "page": 4}])
    bm = utilities.bookmarks_get(ctx, r["output"])["bookmarks"]
    assert [b["title"] for b in bm] == ["Financial statements", "Notes", "Annexure"]
    r = utilities.labels_set(ctx, fx["c"], out("labels.pdf"), [
        {"startpage": 0, "style": "r", "firstpagenum": 1}, {"startpage": 2, "style": "D", "prefix": "A-", "firstpagenum": 1}])
    assert utilities.labels_get(ctx, r["output"])["labels"] == ["i", "ii", "A-1", "A-2"]


def test_attachments(fx, ctx):
    r = utilities.attachments_add(ctx, fx["a"], out("att.pdf"), [fx["txt"], fx["png"]])
    lst = utilities.attachments_list(ctx, r["output"])["attachments"]
    assert {a["filename"] for a in lst} == {"notes.txt", "logo.png"}
    od = os.path.join(os.path.dirname(out("x")), "att_out")
    e = utilities.attachments_extract(ctx, r["output"], od)
    assert e["count"] == 2
    extracted = [o["output"] for o in e["outputs"] if o["output"].endswith(".txt")][0]
    assert sha256(extracted) == sha256(fx["txt"])
    r2 = utilities.attachments_delete(ctx, r["output"], out("att_del.pdf"), ["notes.txt"])
    assert len(utilities.attachments_list(ctx, r2["output"])["attachments"]) == 1


def test_extract_images(fx, ctx):
    od = os.path.join(os.path.dirname(out("x")), "img_extract")
    r = utilities.extract_images(ctx, fx["images"], od)
    assert r["count"] == 3
    assert all(o["format"] in ("jpeg", "jpg", "png") for o in r["outputs"])


def test_blank_page_detection(fx, ctx):
    from nayanpdf_engine.ops import pages

    src = pages.insert_blank(ctx, fx["c"], after=2, count=1, output=out("with_blank.pdf"))["output"]
    r = utilities.blank_detect(ctx, src)
    assert [b["page"] for b in r["blankPages"]] == [3]


def test_page_size_standardize_and_crop(fx, ctx):
    src = out("letter.pdf")
    d = pymupdf.open()
    p = d.new_page(width=612, height=792)
    p.insert_text((72, 72), "Letter sized page", fontsize=14)
    p.insert_link({"kind": pymupdf.LINK_URI, "from": pymupdf.Rect(72, 60, 200, 80), "uri": "https://example.com"})
    d.save(src)
    d.close()
    r = utilities.standardize(ctx, src, out("a4.pdf"), size="A4")
    d = pymupdf.open(r["output"])
    assert abs(d[0].rect.width - 595.28) < 1 and abs(d[0].rect.height - 841.89) < 1
    assert "Letter sized page" in d[0].get_text()
    d.close()
    r = utilities.crop(ctx, src, out("cropped.pdf"), top=10, bottom=10, left=10, right=10, unit="mm")
    d = pymupdf.open(r["output"])
    assert abs(d[0].rect.width - (612 - 2 * 10 * 72 / 25.4)) < 1
    d.close()


def test_print_prepare_respects_permission(fx, ctx):
    od = os.path.join(os.path.dirname(out("x")), "print")
    r = utilities.print_prepare(ctx, fx["a"], od, dpi=100)
    assert len(r["pages"]) == 2 and os.path.exists(r["pages"][0]["file"])
    # A document that forbids printing cannot be prepared without the owner password.
    noprint = out("noprint.pdf")
    d = pymupdf.open(fx["a"])
    d.save(noprint, encryption=pymupdf.PDF_ENCRYPT_AES_256, owner_pw="own", user_pw="", permissions=pymupdf.PDF_PERM_ACCESSIBILITY)
    d.close()
    with pytest.raises(EngineError) as e:
        utilities.print_prepare(ctx, noprint, od)
    assert e.value.code == "PERMISSION_RESTRICTED"


def test_print_prepare_comments_and_forms_options(ctx):
    """Document and markups / document only / form fields only, as in Acrobat."""
    from PIL import Image

    src = out("print_annots.pdf")
    d = pymupdf.open()
    pg = d.new_page(width=300, height=300)
    pg.insert_text((20, 30), "Print test")
    box = pg.add_rect_annot(pymupdf.Rect(20, 60, 120, 120))  # a comment (red filled box)
    box.set_colors(stroke=(1, 0, 0), fill=(1, 0, 0))
    box.update()
    w = pymupdf.Widget()
    w.field_type = pymupdf.PDF_WIDGET_TYPE_TEXT
    w.field_name = "amount"
    w.rect = pymupdf.Rect(150, 60, 280, 120)
    w.field_value = "1,25,000.00"
    w.fill_color = (0, 0, 1)  # blue field background
    pg.add_widget(w)
    d.save(src)
    d.close()

    def sample(mode):
        od = os.path.join(os.path.dirname(out("x")), "print_" + mode)
        r = utilities.print_prepare(ctx, src, od, pages=[1], dpi=72, annots=mode)
        im = Image.open(r["pages"][0]["file"]).convert("RGB")
        return im.getpixel((70, 90)), im.getpixel((272, 66))  # inside the comment, an empty corner of the field

    comment, field = sample("all")
    assert comment[0] > 200 and comment[1] < 80, comment
    assert field[2] > 200 and field[0] < 80, field
    comment, field = sample("none")
    assert comment == (255, 255, 255) and field == (255, 255, 255), (comment, field)
    comment, field = sample("forms")
    assert comment == (255, 255, 255), comment
    assert field[2] > 200 and field[0] < 80, field
    # The source is not modified by hiding comments for printing.
    d = pymupdf.open(src)
    assert not (next(d[0].annots()).flags & pymupdf.PDF_ANNOT_IS_HIDDEN)
    d.close()


# ---- protocol --------------------------------------------------------------------------

def _server(requests, env=None):
    payload = "\n".join(json.dumps(r) for r in requests) + '\n"__exit__"\n'
    p = subprocess.run([sys.executable, "-I", ENGINE], input=payload.encode(), capture_output=True, timeout=600,
                       env={**os.environ, **(env or {})})
    lines = [json.loads(l) for l in p.stdout.decode().splitlines() if l.strip()]
    return lines, p


def test_server_protocol_events_and_errors(fx):
    od = os.path.join(os.path.dirname(out("x")), "proto")
    lines, proc = _server([
        {"id": 1, "method": "pages.split", "params": {"path": fx["twenty"], "outputDir": od, "mode": "every", "every": 10, "overwrite": True}},
        {"id": 2, "method": "doc.info", "params": {"path": fx["encrypted"]}},
        {"id": 3, "method": "os.system", "params": {"cmd": "echo hacked"}},
        {"id": 4, "method": "doc.info", "params": {"path": fx["a"], "unexpected": 1}},
    ])
    assert lines[0]["event"] == "ready"
    progress = [l for l in lines if l.get("id") == 1 and l.get("event") == "progress"]
    assert progress and all(0 <= l["current"] <= l["total"] for l in progress)
    res = {l["id"]: l for l in lines if "result" in l or "error" in l}
    assert res[1]["result"]["count"] == 2
    assert res[2]["error"]["code"] == "PASSWORD_REQUIRED"
    assert res[3]["error"]["code"] == "UNKNOWN_METHOD"
    assert res[4]["error"]["code"] == "INVALID_ARGUMENT"
    # Nothing but JSON on stdout.
    assert all(isinstance(l, dict) for l in lines)


def test_batch_continues_after_invalid_file(fx):
    """Mirrors the desktop batch queue: one job per file, failures reported, others succeed."""
    od = os.path.join(os.path.dirname(out("x")), "batch")
    os.makedirs(od, exist_ok=True)
    files = [fx["a"], fx["notpdf"], fx["b"], fx["truncated"], fx["c"]]
    reqs = [{"id": k + 1, "method": "compress.run",
             "params": {"path": f, "output": os.path.join(od, os.path.basename(f).replace(".pdf", "_c.pdf")), "overwrite": True}}
            for k, f in enumerate(files)]
    lines, _ = _server(reqs)
    res = {l["id"]: l for l in lines if "result" in l or "error" in l}
    ok = [k for k, l in res.items() if "result" in l]
    bad = {k: l["error"]["code"] for k, l in res.items() if "error" in l}
    assert ok == [1, 3, 5]
    assert bad == {2: "UNSUPPORTED_FORMAT", 4: "INVALID_PDF"}
    for k in ok:
        assert os.path.exists(res[k]["result"]["output"])
    # No partial output for failed files.
    assert not os.path.exists(os.path.join(od, "notes_c.pdf"))


def test_sources_never_modified(fx):
    """Runs last (alphabetical module order): every fixture must be byte-identical to its original."""
    for name, digest in fx["_hashes"].items():
        assert sha256(fx[name]) == digest, f"source {name} was modified"
