"""Opening/inspection and page-management tests (spec section 30)."""

import os

import pymupdf
import pytest

from conftest import out, page_texts
from nayanpdf_engine.errors import EngineError
from nayanpdf_engine.ops import info, pages


# ---- opening & viewing -------------------------------------------------------

def test_open_ordinary(fx, ctx):
    r = info.info(ctx, fx["twenty"])
    assert r["pageCount"] == 20
    assert r["metadata"]["title"] == "Report test document"
    assert r["likelyScanned"] is False
    assert r["fileSize"] == os.path.getsize(fx["twenty"])


def test_open_large(fx, ctx):
    r = info.info(ctx, fx["large"])
    assert r["pageCount"] == 300
    lines = info.text_lines(ctx, fx["large"], 300)
    assert any("Large page 300" in ln["text"] for b in lines["blocks"] for ln in b["lines"])


def test_open_scanned_detected(fx, ctx):
    r = info.info(ctx, fx["scanned"])
    assert r["likelyScanned"] is True


def test_open_encrypted_requires_password(fx, ctx):
    with pytest.raises(EngineError) as e:
        info.info(ctx, fx["encrypted"])
    assert e.value.code == "PASSWORD_REQUIRED"
    with pytest.raises(EngineError) as e:
        info.info(ctx, fx["encrypted"], password="wrong")
    assert e.value.code == "PASSWORD_INVALID"
    r = info.info(ctx, fx["encrypted"], password="open123")
    assert r["pageCount"] == 2 and r["encryption"]
    assert r["permissions"]["restricted"] is True


def test_open_malformed_is_repaired_or_reported(fx, ctx):
    try:
        r = info.info(ctx, fx["malformed"])
        assert r["pageCount"] >= 1
    except EngineError as e:
        assert e.code == "INVALID_PDF"


def test_open_truncated_reports_error(fx, ctx):
    with pytest.raises(EngineError) as e:
        info.info(ctx, fx["truncated"])
    assert e.value.code in ("INVALID_PDF",)


def test_open_not_a_pdf(fx, ctx):
    with pytest.raises(EngineError) as e:
        info.info(ctx, fx["notpdf"])
    assert e.value.code == "UNSUPPORTED_FORMAT"


def test_probe(fx, ctx):
    assert info.probe(ctx, fx["encrypted"])["needsPassword"] is True
    assert info.probe(ctx, fx["encrypted"], password="open123")["ok"] is True


# ---- merge / split / reorder ---------------------------------------------------

def test_merge_three_pdfs(fx, ctx):
    o = out("merged.pdf")
    r = pages.merge(ctx, [{"path": fx["a"]}, {"path": fx["b"]}, {"path": fx["c"]}], o)
    assert r["pageCount"] == 9 and r["orderVerified"]
    t = page_texts(r["output"])
    expected = [f"A page {i}" for i in (1, 2)] + [f"B page {i}" for i in (1, 2, 3)] + [f"C page {i}" for i in (1, 2, 3, 4)]
    for txt, exp in zip(t, expected):
        assert exp in txt
    d = pymupdf.open(r["output"])
    toc = d.get_toc()
    d.close()
    assert [x[1] for x in toc if x[0] == 1] == ["a", "b", "c"]


def test_merge_with_page_selection(fx, ctx):
    r = pages.merge(ctx, [{"path": fx["c"], "pages": "4,1"}, {"path": fx["a"]}], out("merged_sel.pdf"))
    t = page_texts(r["output"])
    assert "C page 4" in t[0] and "C page 1" in t[1] and "A page 1" in t[2]


def test_split_twenty_into_four(fx, ctx):
    od = os.path.join(os.path.dirname(out("x")), "split4")
    if os.path.isdir(od):
        import shutil
        shutil.rmtree(od)
    r = pages.split(ctx, fx["twenty"], outputDir=od, mode="every", every=5)
    assert r["count"] == 4
    for k, o in enumerate(r["outputs"]):
        t = page_texts(o["output"])
        assert len(t) == 5
        for j, txt in enumerate(t):
            assert f"Report page {k * 5 + j + 1}" in txt


def test_split_modes(fx, ctx):
    od = os.path.join(os.path.dirname(out("x")), "split_modes")
    r = pages.split(ctx, fx["c"], outputDir=od, mode="single", overwrite=True)
    assert r["count"] == 4
    r = pages.split(ctx, fx["c"], outputDir=od, mode="ranges", ranges=["1-2", "3-4"], overwrite=True)
    assert [o["pageCount"] for o in r["outputs"]] == [2, 2]
    r = pages.split(ctx, fx["twenty"], outputDir=od, mode="oddEven", overwrite=True)
    odd = page_texts(r["outputs"][0]["output"])
    assert len(odd) == 10 and "Report page 1" in odd[0] and "Report page 3" in odd[1]


def test_reorder_rotate_blank(fx, ctx):
    plan = [{"src": "A", "page": 3}, {"src": "A", "page": 1, "rotate": 90}, {"blank": True},
            {"src": "A", "page": 4}, {"src": "A", "page": 2}]
    r = pages.assemble(ctx, {"A": {"path": fx["c"]}}, plan, out("reordered.pdf"))
    assert r["pageCount"] == 5 and r["orderVerified"]
    d = pymupdf.open(r["output"])
    assert "C page 3" in d[0].get_text() and "C page 1" in d[1].get_text()
    assert d[1].rotation == 90
    assert d[2].get_text().strip() == ""
    assert "C page 4" in d[3].get_text() and "C page 2" in d[4].get_text()
    d.close()


def test_assemble_multi_source_interleave(fx, ctx):
    r = pages.interleave(ctx, fx["a"], fx["b"], out("interleave.pdf"), reverseB=True)
    t = page_texts(r["output"])
    assert ["A page 1" in t[0], "B page 3" in t[1], "A page 2" in t[2], "B page 2" in t[3], "B page 1" in t[4]] == [True] * 5


def test_delete_extract_rotate_reverse_duplicate(fx, ctx):
    r = pages.delete(ctx, fx["c"], "2-3", out("deleted.pdf"))
    t = page_texts(r["output"])
    assert len(t) == 2 and "C page 1" in t[0] and "C page 4" in t[1]
    with pytest.raises(EngineError):
        pages.delete(ctx, fx["a"], "1-2", out("deleted_all.pdf"))
    r = pages.extract(ctx, fx["twenty"], "even", output=out("even.pdf"))
    t = page_texts(r["output"])
    assert len(t) == 10 and "Report page 2" in t[0]
    r = pages.rotate(ctx, fx["c"], "1,3", 270, out("rot.pdf"))
    d = pymupdf.open(r["output"])
    assert [p.rotation for p in d] == [270, 0, 270, 0]
    d.close()
    r = pages.reverse(ctx, fx["c"], out("rev.pdf"))
    assert "C page 4" in page_texts(r["output"])[0]
    r = pages.duplicate(ctx, fx["a"], "1", out("dup.pdf"))
    t = page_texts(r["output"])
    assert len(t) == 3 and "A page 1" in t[0] and "A page 1" in t[1]


def test_insert_and_replace(fx, ctx):
    r = pages.insert_from_file(ctx, fx["a"], fx["b"], after=1, output=out("ins.pdf"), insertPages="2")
    t = page_texts(r["output"])
    assert "A page 1" in t[0] and "B page 2" in t[1] and "A page 2" in t[2]
    r = pages.replace(ctx, fx["c"], "2-3", fx["b"], out("repl.pdf"), replacementPages="1-2")
    t = page_texts(r["output"])
    assert "C page 1" in t[0] and "B page 1" in t[1] and "B page 2" in t[2] and "C page 4" in t[3]
    r = pages.insert_blank(ctx, fx["a"], after=2, count=2, output=out("blank.pdf"))
    assert len(page_texts(r["output"])) == 4


def test_no_overwrite_of_existing_output(fx, ctx):
    import shutil

    d = os.path.join(os.path.dirname(out("x")), "no_overwrite")
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    o = os.path.join(d, "same_name.pdf")
    r1 = pages.extract(ctx, fx["a"], "1", output=o)
    r2 = pages.extract(ctx, fx["a"], "1", output=o)
    assert r1["output"] != r2["output"]
    assert r2["output"].endswith("same_name (1).pdf")


def test_refuses_to_replace_source(fx, ctx):
    with pytest.raises(EngineError) as e:
        pages.extract(ctx, fx["a"], "1", output=fx["a"])
    assert e.value.code == "OUTPUT_EXISTS"


def test_special_characters_and_long_paths(fx, ctx):
    base = os.path.join(os.path.dirname(out("x")), "Ünïcødé फ़ाइल & (2025-26) #1")
    deep = os.path.join(base, *(["very_long_folder_name_for_path_testing"] * 6))
    os.makedirs(deep, exist_ok=True)
    r = pages.extract(ctx, fx["a"], "1", output=os.path.join(deep, "निर्यात report ₹.pdf"), overwrite=True)
    assert os.path.exists(r["output"])
    assert len(r["output"]) > 260
