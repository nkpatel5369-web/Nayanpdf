"""Comment review (replies, status, XFDF, summary, CSV), search across files,
links and the document's initial view."""

import csv
import os
import shutil

import pymupdf
import pytest

from conftest import out, page_texts
from nayanpdf_engine.errors import EngineError
from nayanpdf_engine.ops import annotate, links, metadata, review, search
from nayanpdf_engine.util.geometry import fitz_to_pdf_rect


def _rect_of(path, page, text):
    d = pymupdf.open(path)
    try:
        p = d[page - 1]
        hits = p.search_for(text)
        assert hits, f"{text!r} not on page {page}"
        return fitz_to_pdf_rect(p, hits[0])
    finally:
        d.close()


def _reviewed(ctx, fx):
    """twenty.pdf with a highlight and a note, a reply thread and review states."""
    src = fx["twenty"]
    r = _rect_of(src, 1, "Report page 1")
    p1 = out("review_1.pdf")
    res = annotate.apply(ctx, src, p1, [
        {"op": "add", "page": 1, "kind": "highlight", "quads": [r], "contents": "Check this heading"},
        {"op": "add", "page": 2, "kind": "note", "point": [100, 700], "contents": "Totals differ from ledger"},
    ], author="Auditor A")
    hl, note = res["created"][0]["id"], res["created"][1]["id"]
    p2 = out("review_2.pdf")
    annotate.apply(ctx, p1, p2, [
        {"op": "reply", "page": 1, "id": hl, "contents": "Heading confirmed with client"},
        {"op": "status", "page": 1, "id": hl, "state": "Accepted"},
        {"op": "reply", "page": 2, "id": note, "contents": "Ledger corrected"},
    ], author="Partner B")
    p3 = out("review_3.pdf")
    annotate.apply(ctx, p2, p3, [{"op": "status", "page": 2, "id": note, "state": "Rejected"},
                                 {"op": "mark", "page": 2, "id": note, "marked": True}], author="Partner B")
    return p3, hl, note


def test_replies_and_review_status_are_stored_as_acrobat_threads(ctx, fx):
    path, hl, note = _reviewed(ctx, fx)
    items = annotate.list_annots(ctx, path)["annotations"]
    tops = review.threads([dict(x) for x in items])
    assert sorted(t["typeName"] for t in tops) == ["Highlight", "Text"]
    h = next(t for t in tops if t["typeName"] == "Highlight")
    n = next(t for t in tops if t["typeName"] == "Text")
    assert [r["contents"] for r in h["replies"]] == ["Heading confirmed with client"]
    assert h["replies"][0]["author"] == "Partner B"
    assert h["status"]["state"] == "Accepted"
    assert n["status"]["state"] == "Rejected" and n["marked"] is True
    # On disk: replies point at their parent with /IRT and /RT /R and are not drawn
    # (NoView); states are hidden and carry /State and /StateModel.
    d = pymupdf.open(path)
    try:
        kinds = {}
        for p in d:
            for a in p.annots():
                t, irt = d.xref_get_key(a.xref, "IRT")
                if t != "xref":
                    continue
                model = d.xref_get_key(a.xref, "StateModel")[1]
                kinds.setdefault(model, []).append((a.flags, d.xref_get_key(a.xref, "RT")[1], d.xref_get_key(a.xref, "State")[1]))
        replies = kinds["null"]
        assert len(replies) == 2 and all(f & 32 for f, _, _ in replies) and all(rt == "/R" for _, rt, _ in replies)
        assert {s for _, _, s in kinds["Review"]} == {"Accepted", "Rejected"} and all(f & 2 for f, _, _ in kinds["Review"])
        assert [s for _, _, s in kinds["Marked"]] == ["Marked"]
    finally:
        d.close()
    # Deleting a comment removes its replies and states with it.
    p4 = out("review_4.pdf")
    annotate.apply(ctx, path, p4, [{"op": "delete", "page": 1, "id": hl}])
    left = annotate.list_annots(ctx, p4)["annotations"]
    assert not [a for a in left if a["page"] == 1]
    assert len([a for a in left if a["page"] == 2]) == 4  # note, reply, state, mark


def test_xfdf_round_trip_keeps_threads_authors_and_status(ctx, fx):
    path, _, _ = _reviewed(ctx, fx)
    x = out("review.xfdf")
    r = review.export_xfdf(ctx, path, x)
    assert r["comments"] == 7 and os.path.getsize(x) > 0  # 2 comments, 2 replies, 2 states, 1 check mark
    text = open(x, encoding="utf-8").read()
    assert 'xmlns="http://ns.adobe.com/xfdf/"' in text and "<highlight" in text and 'statemodel="Review"' in text
    target = out("review_imported.pdf")
    res = review.import_xfdf(ctx, fx["twenty"], target, xfdfPath=x)
    assert res["imported"] == 7 and not res["notImported"]
    a = review.threads([dict(i) for i in annotate.list_annots(ctx, path)["annotations"]])
    b = review.threads([dict(i) for i in annotate.list_annots(ctx, target)["annotations"]])
    shape = lambda ts: sorted((t["page"], t["typeName"], t["contents"], t["author"], (t["status"] or {}).get("state"),  # noqa: E731
                                t["marked"], tuple((r["author"], r["contents"]) for r in t["replies"])) for t in ts)
    assert shape(a) == shape(b)
    # The highlight still covers the same words.
    hl = next(t for t in b if t["typeName"] == "Highlight")
    orig = next(t for t in a if t["typeName"] == "Highlight")
    assert all(abs(p - q) < 0.5 for p, q in zip(hl["rect"], orig["rect"]))


def test_xfdf_import_reports_what_it_cannot_take(ctx, fx):
    x = out("acrobat_style.xfdf")
    with open(x, "w", encoding="utf-8") as f:
        f.write("""<?xml version="1.0" encoding="UTF-8"?>
<xfdf xmlns="http://ns.adobe.com/xfdf/" xml:space="preserve"><annots>
<text page="0" rect="72,700,92,720" name="n1" title="Reviewer" color="#FFFF00" icon="Comment"><contents>Please explain</contents></text>
<text page="0" rect="72,700,92,720" name="n2" title="Preparer" inreplyto="n1" replyType="reply" flags="noview"><contents>See note 4</contents></text>
<polygon page="0" rect="10,10,50,50" name="p1" vertices="10,10,50,10,30,50"/>
</annots><f href="x.pdf"/></xfdf>""")
    target = out("acrobat_style.pdf")
    res = review.import_xfdf(ctx, fx["a"], target, xfdfPath=x)
    assert res["imported"] == 2 and res["notImported"] == {"polygon": 1}
    assert any("polygon" in w for w in ctx.warnings)
    t = review.threads([dict(i) for i in annotate.list_annots(ctx, target)["annotations"]])
    assert len(t) == 1 and t[0]["contents"] == "Please explain" and t[0]["replies"][0]["contents"] == "See note 4"
    bad = out("broken.xfdf")
    open(bad, "w").write("<xfdf><annots><text")
    with pytest.raises(EngineError):
        review.import_xfdf(ctx, fx["a"], out("never.pdf"), xfdfPath=bad)


def test_comment_summary_and_csv_list(ctx, fx):
    path, _, _ = _reviewed(ctx, fx)
    s = out("summary.pdf")
    r = review.summary(ctx, path, s)
    assert r["comments"] == 2 and r["pages"] >= 1
    import unicodedata

    text = unicodedata.normalize("NFKC", "".join(page_texts(s)))  # the summary typesets ligatures ("ﬀ")
    for needle in ("Summary of comments", "Page 1", "Page 2", "Check this heading", "Totals differ from ledger",
                   "Reply from Partner B: Heading confirmed with client", "Accepted", "Rejected"):
        assert needle in " ".join(text.split()), needle
    c = out("comments.csv")
    r = review.export_list(ctx, path, c)
    assert r["rows"] == 4
    raw = open(c, "rb").read()
    assert raw.startswith(b"\xef\xbb\xbf")  # opens correctly in Excel
    rows = list(csv.reader(open(c, encoding="utf-8-sig")))
    assert rows[0] == ["Page", "Type", "Author", "Date", "Status", "Comment", "Reply to"]
    assert ["2", "Text", "Auditor A"] == rows[3][:3] and rows[3][4] == "Rejected"
    assert rows[2][1] == "Reply" and rows[2][5] == "Heading confirmed with client"


def test_search_across_files(ctx, fx, tmp_path):
    folder = tmp_path / "statements"
    folder.mkdir()
    d = pymupdf.open()
    pg = d.new_page()
    pg.insert_text((72, 100), "Opening balance as per bank", fontsize=11)
    pg.insert_text((72, 114), "statement 31 March 2026", fontsize=11)
    pg.insert_text((72, 140), "TDS deducted under section 194J", fontsize=11)
    d.save(str(folder / "bank.pdf"))
    d.close()
    shutil.copy(fx["twenty"], folder / "report.pdf")
    shutil.copy(fx["encrypted"], folder / "locked.pdf")
    shutil.copy(fx["scanned"], folder / "scan.pdf")
    files = sorted(str(p) for p in folder.iterdir())
    # A phrase broken across two lines is found; case-insensitive by default.
    r = search.search_files(ctx, files, "BANK statement")
    assert r["total"] == 1 and r["files"][0]["name"] == "bank.pdf" and r["files"][0]["matches"][0]["page"] == 1
    assert r["files"][0]["matches"][0]["match"] == "bank statement"
    reasons = {os.path.basename(s["path"]): s["reason"] for s in r["skipped"]}
    assert reasons["locked.pdf"] == "password protected" and "no text layer" in reasons["scan.pdf"]
    assert r["scannedWithoutText"] == 1 and r["searched"] == 4
    assert search.search_files(ctx, files, "bank STATEMENT", caseSensitive=True)["total"] == 0
    # Whole words: "Report" matches, "Repor" does not.
    whole = search.search_files(ctx, files, "Repor", wholeWord=True)
    assert whole["total"] == 0
    many = search.search_files(ctx, files, "report page", maxResults=5)
    assert many["total"] == 5 and many["truncated"] is True
    # Comments are searched only when asked.
    with_note = out("search_note.pdf")
    annotate.apply(ctx, str(folder / "bank.pdf"), with_note, [{"op": "add", "page": 1, "kind": "note", "point": [300, 700], "contents": "Ask about 194J refund"}])
    assert search.search_files(ctx, [with_note], "refund")["total"] == 0
    assert search.search_files(ctx, [with_note], "refund", inComments=True)["files"][0]["matches"][0]["where"] == "comment"
    with pytest.raises(EngineError):
        search.search_files(ctx, files, "   ")


def test_links_add_list_delete(ctx, fx):
    src = fx["twenty"]
    p1 = out("links_1.pdf")
    links.apply_links(ctx, src, p1, [
        {"op": "add", "page": 1, "rect": [72, 700, 200, 720], "toPage": 5},
        {"op": "add", "page": 1, "rect": [72, 650, 200, 670], "uri": "www.example.com/rates"},
    ])
    ls = links.list_links(ctx, p1, page=1)["links"]
    kinds = sorted((l["kind"], l.get("targetPage"), l.get("uri")) for l in ls)
    assert kinds == [("page", 5, None), ("uri", None, "https://www.example.com/rates")]
    p2 = out("links_2.pdf")
    idx = next(l["index"] for l in ls if l["kind"] == "uri")
    links.apply_links(ctx, p1, p2, [{"op": "delete", "page": 1, "index": idx}])
    assert [l["kind"] for l in links.list_links(ctx, p2, page=1)["links"]] == ["page"]
    for bad in ("javascript:alert(1)", "file:///C:/Windows/system32/calc.exe"):
        with pytest.raises(EngineError):
            links.apply_links(ctx, src, out("links_bad.pdf"), [{"op": "add", "page": 1, "rect": [72, 600, 200, 620], "uri": bad}])
    with pytest.raises(EngineError):
        links.apply_links(ctx, src, out("links_bad2.pdf"), [{"op": "add", "page": 1, "rect": [72, 600, 200, 620], "toPage": 99}])


def test_initial_view_set_and_read_back(ctx, fx):
    src = fx["twenty"]
    assert metadata.get_initial_view(ctx, src) == {"pageMode": "UseNone", "pageLayout": "", "openPage": 1, "zoom": "default", "displayDocTitle": False}
    p1 = out("initial_view.pdf")
    r = metadata.set_initial_view(ctx, src, p1, pageMode="UseOutlines", pageLayout="TwoColumnLeft", openPage=3,
                                  zoom="fitWidth", displayDocTitle=True)
    assert r["initialView"] == {"pageMode": "UseOutlines", "pageLayout": "TwoColumnLeft", "openPage": 3, "zoom": "fitWidth", "displayDocTitle": True}
    d = pymupdf.open(p1)
    assert d.pagemode == "UseOutlines" and d.pagelayout == "TwoColumnLeft"
    d.close()
    p2 = out("initial_view_125.pdf")
    assert metadata.set_initial_view(ctx, p1, p2, pageMode="UseThumbs", openPage=1, zoom=125)["initialView"]["zoom"] == 125
    p3 = out("initial_view_reset.pdf")
    back = metadata.set_initial_view(ctx, p2, p3)["initialView"]
    assert back == {"pageMode": "UseNone", "pageLayout": "", "openPage": 1, "zoom": "default", "displayDocTitle": False}
    with pytest.raises(EngineError):
        metadata.set_initial_view(ctx, src, out("iv_bad.pdf"), openPage=99)
