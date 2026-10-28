"""Saved actions: several steps run in order on one file, the source untouched,
only the final result written, passwords supplied at run time."""

import hashlib

import pymupdf
import pytest

from conftest import out, page_texts
from nayanpdf_engine.errors import EngineError
from nayanpdf_engine.ops import actions, metadata


def _sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()


def test_steps_run_in_order_and_the_source_is_untouched(ctx, fx):
    src = fx["twenty"]
    before = _sha(src)
    o = out("action_chain.pdf")
    r = actions.run(ctx, src, o, [
        {"op": "watermark", "text": "CONFIDENTIAL", "opacity": 0.2},
        {"op": "pageNumbers", "position": "bottom-right", "numberFormat": "pageOf"},
        {"op": "setMetadata", "fields": {"author": "NK Patel & Co", "title": "Should be cleared next"}},
        {"op": "removeMetadata"},
        {"op": "compress", "preset": "balanced"},
    ], name="Client copy")
    assert [s["op"] for s in r["steps"]] == ["watermark", "pageNumbers", "setMetadata", "removeMetadata", "compress"]
    t = page_texts(r["output"])
    assert len(t) == 20 and "CONFIDENTIAL" in t[0] and "Report page 1" in t[0]
    assert "20" in t[19].replace("\n", " ")
    m = metadata.get(ctx, r["output"])["fields"]
    assert not m.get("author") and not m.get("title")
    assert _sha(src) == before


def test_blank_pages_and_pan_redaction(ctx):
    src = out("action_in.pdf")
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((72, 100), "Client PAN ABCDE1234F for AY 2026-27", fontsize=12)
    d.new_page()  # blank
    d.new_page().insert_text((72, 100), "Totals as per ledger", fontsize=12)
    d.save(src)
    d.close()
    o = out("action_redacted.pdf")
    r = actions.run(ctx, src, o, [{"op": "removeBlankPages"}, {"op": "redactPatterns", "patterns": ["pan"]}])
    t = page_texts(r["output"])
    assert len(t) == 2 and "ABCDE1234F" not in "".join(t) and "Totals as per ledger" in t[1]
    assert "removed page(s) 2" in r["steps"][0]["note"] and "redacted" in r["steps"][1]["note"]


def test_protect_is_last_and_takes_its_password_at_run_time(ctx, fx):
    o = out("action_protected.pdf")
    r = actions.run(ctx, fx["a"], o, [{"op": "compress"}, {"op": "protect", "userPassword": "open-2026"}])
    d = pymupdf.open(r["output"])
    assert d.needs_pass and d.authenticate("open-2026")
    d.close()
    with pytest.raises(EngineError, match="when you run the action"):
        actions.run(ctx, fx["a"], out("action_nopw.pdf"), [{"op": "protect"}])


def test_invalid_actions_are_refused(ctx, fx):
    bad = [
        [],
        [{"op": "deleteEverything"}],
        [{"op": "protect", "userPassword": "x1234"}, {"op": "compress"}],
        [{"op": "pdfa"}, {"op": "protect", "userPassword": "x1234"}],
        [{"op": "compress"}] * 21,
    ]
    for steps in bad:
        with pytest.raises(EngineError):
            actions.check_steps(steps)
    # Keys a step may not set (where to read or write) are dropped.
    assert actions.check_steps([{"op": "compress", "path": "/etc/passwd", "output": "/tmp/x.pdf", "preset": "high"}]) == [{"op": "compress", "preset": "high"}]


def test_a_failing_step_names_itself_and_writes_nothing(ctx, fx):
    o = out("action_fail.pdf")
    with pytest.raises(EngineError, match=r"Step 2 \(Rotate pages\) failed"):
        actions.run(ctx, fx["a"], o, [{"op": "compress"}, {"op": "rotate", "pages": "all", "angle": 45}])
    import os
    assert not os.path.exists(o)


def test_ocr_step_makes_a_scan_searchable(ctx, fx):
    o = out("action_ocr.pdf")
    r = actions.run(ctx, fx["scanned"], o, [{"op": "ocr", "languages": ["eng"]}, {"op": "compress"}])
    assert sum(len(x.strip()) for x in page_texts(r["output"])) > 100
