"""Compression, conversion and OCR tests (spec section 30)."""

import os

import pymupdf
import pytest

from conftest import BANK_ROWS, needs_soffice, needs_tesseract, out, page_texts
from nayanpdf_engine.ops import compress, convert, create, ocr
from nayanpdf_engine.util.numbers import parse_date, parse_number


# ---- number safety -----------------------------------------------------------

@pytest.mark.parametrize("text,value", [
    ("1,23,456.78", 123456.78), ("123,456.78", 123456.78), ("(45,000.00)", -45000.0), ("1,234.00-", -1234.0),
    ("-12.5", -12.5), ("₹ 5,000", 5000), ("Rs. 1,000.50", 1000.5), ("0.75", 0.75), ("12.5%", 0.125),
    ("5,00,000.00 Cr", 500000.0),
])
def test_parse_number_converts(text, value):
    v, fmt = parse_number(text)
    assert abs(v - value) < 1e-9


@pytest.mark.parametrize("text", [
    "000123456789", "412345678901", "INV-2026/0042", "2025-26", "27ABCDE1234F1Z5", "98765 43210",
    "12,34,5", "1,2345.00", "1234567890123456.00", "(12", "--5", "ABCDE1234F",
])
def test_parse_number_keeps_identifiers_as_text(text):
    assert parse_number(text) is None


def test_drcr_marker_preserved_in_format():
    v, fmt = parse_number("5,00,000.00 Cr")
    assert v == 500000.0 and '"Cr"' in fmt
    v, fmt = parse_number("1,000.00 Dr")
    assert v == 1000.0 and '"Dr"' in fmt  # sign is NOT inferred


def test_parse_date():
    import datetime as dt

    assert parse_date("15/04/2025")[0] == dt.date(2025, 4, 15)
    assert parse_date("03/04/2025", "MDY")[0] == dt.date(2025, 3, 4)
    assert parse_date("15-Apr-2025")[0] == dt.date(2025, 4, 15)
    assert parse_date("31/02/2025") is None


# ---- compression -------------------------------------------------------------

def test_compress_image_heavy_reports_actual_sizes(fx, ctx):
    r = compress.compress(ctx, fx["images"], out("compressed.pdf"), preset="balanced")
    actual = os.path.getsize(r["output"])
    orig = os.path.getsize(fx["images"])
    assert r["finalSize"] == actual and r["originalSize"] == orig
    assert r["reductionBytes"] == orig - actual
    assert abs(r["reductionPercent"] - round((orig - actual) / orig * 100, 2)) < 0.01
    assert r["finalSize"] < r["originalSize"] * 0.6, r
    t = page_texts(r["output"])
    assert all("text must survive compression" in x for x in t)


def test_compress_presets_and_larger_is_reported(fx, ctx):
    r = compress.compress(ctx, fx["a"], out("tiny_max.pdf"), preset="maximum")
    assert r["larger"] == (r["finalSize"] > r["originalSize"])
    r2 = compress.compress(ctx, fx["images"], out("strong.pdf"), preset="strong")
    r3 = compress.compress(ctx, fx["images"], out("high.pdf"), preset="high")
    assert r2["finalSize"] <= r3["finalSize"]


def test_compress_target_size(fx, ctx):
    r = compress.compress(ctx, fx["images"], out("target.pdf"), targetSizeKb=400)
    assert r["attempts"]
    if r["targetReached"]:
        assert r["finalSize"] <= 400 * 1024


# ---- PDF -> Word ---------------------------------------------------------------

@needs_soffice
def test_pdf_to_word_layout(fx, ctx):
    import docx

    r = convert.to_word(ctx, fx["financial"], out("fin_layout.docx"), mode="layout", pages="1")
    d = docx.Document(r["output"])
    text = "\n".join(p.text for p in d.paragraphs)
    cells = [c.text for t in d.tables for row in t.rows for c in row.cells]
    assert "ACME Traders" in text
    assert any("1,23,456.78" in c for c in cells), cells[:20]


@needs_soffice
def test_pdf_to_word_flow(fx, ctx):
    import docx

    r = convert.to_word(ctx, fx["financial"], out("fin_flow.docx"), mode="flow")
    d = docx.Document(r["output"])
    assert r["tables"] >= 1
    allcells = [c.text for t in d.tables for row in t.rows for c in row.cells]
    assert "(45,000.00)" in allcells


# ---- PDF -> Excel ----------------------------------------------------------------

def _xl_cells(path):
    """Every non-empty cell value of every worksheet, in reading order."""
    import openpyxl

    wb = openpyxl.load_workbook(path)
    return wb, [v for ws in wb.worksheets for row in ws.iter_rows(values_only=True) for v in row if v is not None]


def _xl_rows(ws):
    return [tuple(v for v in row) for row in ws.iter_rows(values_only=True)]


def _row_with(ws, text):
    return next(r for r in _xl_rows(ws) if text in r)


@needs_soffice
def test_pdf_to_excel_financial_values(fx, ctx):
    r = convert.to_excel(ctx, fx["financial"], out("fin.xlsx"))
    wb, flat = _xl_cells(r["output"])
    # One worksheet per page and nothing else: no report sheet, no "other text" sheet.
    assert wb.sheetnames == [f"Page {k}" for k in range(1, len(wb.sheetnames) + 1)], wb.sheetnames
    assert r["reviewPages"] == []                  # every printed digit is in a cell
    assert 123456.78 in flat
    assert -45000.0 in flat                        # parentheses -> negative, sign preserved
    assert "000987654321" in flat                  # reference with leading zeros stays text
    assert "412345678901" in flat                  # long reference stays text
    assert "NEFT-HDFC0001234-ACME SUPPLIES" in flat  # wrapped cell rebuilt without inserting spaces
    assert "UPI/CR/412345678901/Client A" in flat
    # Text around the tables stays on the page's sheet, where it is printed.
    page1 = " ".join(str(v) for v in _xl_rows(wb["Page 1"]) for v in v if v)
    assert "000123456789" in page1 and "27ABCDE1234F1Z5" in page1 and "Statement of Profit and Loss" in page1
    rev = _row_with(wb["Page 1"], "Revenue from operations")
    assert [v for v in rev if v is not None] == ["Revenue from operations", 123456.78, 98765.43]


@needs_soffice
def test_pdf_to_excel_whole_document_on_one_sheet(fx, ctx):
    import datetime as dt

    r = convert.to_excel(ctx, fx["financial"], out("fin_doc.xlsx"), layout="document")
    wb, flat = _xl_cells(r["output"])
    assert len(wb.sheetnames) == 1
    rows = _xl_rows(wb.worksheets[0])
    head = next(k for k, row in enumerate(rows) if "Description" in row)
    bank = [row for row in rows[head + 1:] if any(isinstance(v, dt.datetime) for v in row)]
    # The bank table runs over several pages: every row once, the rows that a page
    # break cut in two joined again.
    odd = sorted({v for v in flat if isinstance(v, str) and v not in ("NEFT-HDFC0001234-ACME SUPPLIES", "UPI/CR/412345678901/Client A")
                  and any(k in v for k in ("NEFT", "SUPPLIES", "UPI/", "Client A"))})
    assert len(bank) == 18 * len(BANK_ROWS), (len(bank), odd)
    assert sum(1 for row in rows if "Description" in row) == 1
    assert flat.count("NEFT-HDFC0001234-ACME SUPPLIES") == 18, odd
    assert flat.count("UPI/CR/412345678901/Client A") == 18, odd
    assert flat.count("000987654321") == 18, sorted({v for v in flat if isinstance(v, str) and v.startswith("0009")})
    assert any(isinstance(v, dt.datetime) and v.date() == dt.date(2025, 4, 15) for row in bank for v in row)


@needs_soffice
def test_pdf_to_csv(fx, ctx):
    r = convert.to_excel(ctx, fx["financial"], out("fin.csv"))
    text = open(r["output"], encoding="utf-8-sig").read()
    assert "1,23,456.78" in text and ",000987654321," in text and "NEFT-HDFC0001234-ACME SUPPLIES" in text


# ---- PDF -> Excel: layouts made by other programs (synthetic, no client data) ----

def _draw(page, x, y, text, bold=False, size=9, right=False):
    """One separately placed run of text, as accounting software writes each cell."""
    font = "hebo" if bold else "helv"
    if right:
        x -= pymupdf.get_text_length(text, fontname=font, fontsize=size)
    page.insert_text((x, y), text, fontname=font, fontsize=size)


def _borderless_pl(path):
    d = pymupdf.open()
    p = d.new_page()
    _draw(p, 200, 50, "ACME TRADERS PRIVATE LIMITED", bold=True, size=13)
    _draw(p, 160, 70, "Statement of Profit and Loss for the year ended 31 March 2026", bold=True, size=10)
    _draw(p, 50, 110, "Particulars", bold=True)
    _draw(p, 300, 110, "Note", bold=True)
    _draw(p, 440, 110, "31 March 2026", bold=True, right=True)
    _draw(p, 540, 110, "31 March 2025", bold=True, right=True)
    rows = [("I", "Revenue from operations", "21", "12,34,56,789.00", "9,87,65,432.50"),
            ("II", "Other income", "22", "12,34,567.00", "9,87,654.00"),
            ("", "Changes in inventories of finished goods", "25", "(12,34,567.00)", "8,76,543.00"),
            ("VIII", "Earnings per equity share", "", "", ""),
            ("", "Basic and diluted (in Rs.)", "31", "20.36", "11.96")]
    y = 130
    for rn, name, note, a, b in rows:
        _draw(p, 50, y, rn, bold=rn == "VIII")
        _draw(p, 75, y, name, bold=rn == "VIII")
        _draw(p, 300, y, note)
        _draw(p, 440, y, a, right=True)
        _draw(p, 540, y, b, right=True)
        y += 15
    d.save(path)


def test_excel_borderless_statement_keeps_phrases_and_columns(ctx):
    src = out("pl_borderless.pdf")
    _borderless_pl(src)
    r = convert.to_excel(ctx, src, out("pl_borderless.xlsx"))
    wb, flat = _xl_cells(r["output"])
    assert wb.sheetnames == ["Page 1"]
    ws = wb["Page 1"]
    assert _row_with(ws, "Revenue from operations") == ("I", "Revenue from operations", 21, 123456789.0, 98765432.5)
    assert _row_with(ws, "Changes in inventories of finished goods")[1:] == ("Changes in inventories of finished goods", 25, -1234567.0, 876543.0)
    assert _row_with(ws, "Earnings per equity share")[:2] == ("VIII", "Earnings per equity share")
    assert _row_with(ws, "Particulars") == ("Particulars", None, "Note", "31 March 2026", "31 March 2025")
    # Headings are kept whole and in place; no word is cut between two cells.
    assert "ACME TRADERS PRIVATE LIMITED" in flat and "Statement of Profit and Loss for the year ended 31 March 2026" in flat
    assert not any(isinstance(v, str) and v in ("Partic", "ulars", "PRIVATE LIM") for v in flat)
    # Amounts show as printed: grouped, two decimals, brackets for the negative.
    cell = next(c for row in ws.iter_rows() for c in row if c.value == -1234567.0)
    assert cell.number_format.startswith("#,##0.00") and "(" in cell.number_format


def _bank_statement(path, pages=3, per_page=12):
    """Borderless statement: header and column headings on every page, a page
    number at the foot, narrations cut at a fixed width (even inside a word)."""
    d = pymupdf.open()
    narr = ["IMPS/P2A/602512345678/SURESH/Salary advance", "NEFT CR-SBIN0001234-GLOBAL EXPORTS LTD-INV 2026/045", "ATM WDL"]
    n = 0
    bal = 500000.0
    for pg in range(pages):
        p = d.new_page()
        _draw(p, 40, 40, "STATEMENT OF ACCOUNT", bold=True, size=12)
        _draw(p, 40, 56, "Account No: 50100123456789    Branch: NAVRANGPURA    Period: 01/03/2026 to 31/03/2026", size=8.5)
        y = 84
        for x, t, right in [(40, "Date", False), (95, "Narration", False), (290, "Ref No.", False), (420, "Withdrawal", True),
                            (490, "Deposit", True), (560, "Balance", True)]:
            _draw(p, x, y, t, bold=True, size=8.5, right=right)
        y += 16
        for _ in range(per_page):
            n += 1
            text = narr[n % 3]
            parts = [text[i:i + 30] for i in range(0, len(text), 30)]
            amt = 1000.0 + n
            bal -= amt
            _draw(p, 40, y, f"{(n % 28) + 1:02d}/03/2026", size=8)
            _draw(p, 95, y, parts[0], size=8)
            _draw(p, 290, y, f"{410000 + n:010d}", size=8)
            _draw(p, 420, y, f"{amt:,.2f}", size=8, right=True)
            _draw(p, 560, y, f"{bal:,.2f}", size=8, right=True)
            for part in parts[1:]:
                y += 10
                _draw(p, 95, y, part, size=8)
            y += 14
        _draw(p, 280, 820, f"Page {pg + 1} of {pages}", size=7.5)
    d.save(path)
    return n


def test_excel_bank_statement_wrapped_narrations_and_pages(ctx):
    import datetime as dt

    src = out("bank_statement.pdf")
    n = _bank_statement(src)
    r = convert.to_excel(ctx, src, out("bank_pages.xlsx"))
    wb, flat = _xl_cells(r["output"])
    assert wb.sheetnames == ["Page 1", "Page 2", "Page 3"]
    first = _row_with(wb["Page 1"], "Narration")
    assert first == ("Date", "Narration", "Ref No.", "Withdrawal", "Deposit", "Balance")
    # A narration cut at 30 characters, inside "Salary", is one cell again.
    assert "IMPS/P2A/602512345678/SURESH/Salary advance" in flat
    assert "NEFT CR-SBIN0001234-GLOBAL EXPORTS LTD-INV 2026/045" in flat
    assert "0000410001" in flat                         # reference keeps its leading zeros
    assert "Page 2 of 3" in flat                        # one sheet per page keeps everything
    row = _row_with(wb["Page 1"], "0000410001")
    assert isinstance(row[0], dt.datetime) and row[3] == 1001.0 and row[4] is None

    r = convert.to_excel(ctx, src, out("bank_doc.xlsx"), layout="document")
    wb, flat = _xl_cells(r["output"])
    assert len(wb.sheetnames) == 1
    rows = _xl_rows(wb.worksheets[0])
    data = [row for row in rows if isinstance(row[0], dt.datetime)]
    assert len(data) == n                               # every transaction, once
    assert sum(1 for row in rows if "Narration" in row) == 1
    assert flat.count("STATEMENT OF ACCOUNT") == 1
    assert not any(isinstance(v, str) and v.startswith("Page ") for v in flat)


def test_excel_row_cut_by_a_page_break_is_one_row_again(ctx):
    """A ruled statement whose last row on page 1 is cut by the page break: page 2
    starts with the rest of the narration and the last digit of the reference
    (as a word processor lays out a long row). On one worksheet it is one row."""
    import datetime as dt

    d = pymupdf.open()
    xs = [40, 110, 300, 400, 480, 560]
    heads = ["Date", "Description", "Ref No", "Debit", "Balance"]

    def grid(p, top, rows):
        rh = 26
        for k in range(len(rows) + 1):
            p.draw_line((xs[0], top + k * rh), (xs[-1], top + k * rh))
        for x in xs:
            p.draw_line((x, top), (x, top + len(rows) * rh))
        for k, row in enumerate(rows):
            for j, cell in enumerate(row):
                for li, part in enumerate(cell.split("\n") if cell else []):
                    y = top + k * rh + 11 + li * 10
                    if j >= 3:
                        _draw(p, xs[j + 1] - 3, y, part, right=True)
                    else:
                        _draw(p, xs[j] + 3, y, part, bold=k == 0 and top == 60)

    p1 = d.new_page()
    grid(p1, 60, [heads, ["03/04/2025", "Opening balance", "", "", "5,00,000.00 Cr"],
                  ["04/04/2025", "NEFT-HDFC0001234-\nACME", "00098765432", "1,23,456.78", "3,76,543.22"]])
    p2 = d.new_page()
    # The rest of the cut row: narration, the last digit of the reference and the
    # "Cr" of the balance, which wrapped in its narrow column.
    grid(p2, 40, [["", "SUPPLIES", "1", "", "Cr"], ["05/04/2025", "UPI/CR/412345678901/Client A", "412345678901", "45,000.00", "4,21,543.22 Cr"]])
    src = out("cut_row.pdf")
    d.save(src)
    r = convert.to_excel(ctx, src, out("cut_row.xlsx"), layout="document")
    wb, flat = _xl_cells(r["output"])
    rows = [row for row in _xl_rows(wb.worksheets[0]) if any(isinstance(v, dt.datetime) for v in row)]
    assert len(rows) == 3, rows
    cut = rows[1]
    assert "NEFT-HDFC0001234-ACME SUPPLIES" in cut and "000987654321" in cut and 123456.78 in cut, cut
    ws = wb.worksheets[0]
    bal = next(c for row in ws.iter_rows() for c in row if c.value == 376543.22)
    assert '"Cr"' in bal.number_format, bal.number_format      # the balance kept its Cr
    # A second amount under an amount is a value of its own, not the rest of one.
    assert 45000.0 in [v for row in rows for v in row]
    # One worksheet per page keeps the page as printed.
    r = convert.to_excel(ctx, src, out("cut_row_pages.xlsx"))
    wb, flat = _xl_cells(r["output"])
    assert wb.sheetnames == ["Page 1", "Page 2"] and "SUPPLIES" in flat


def test_excel_ruled_invoice_totals_follow_the_grid(ctx):
    d = pymupdf.open()
    p = d.new_page()
    _draw(p, 250, 45, "TAX INVOICE", bold=True, size=13)
    _draw(p, 40, 75, "Shree Ganesh Enterprises", bold=True, size=10)
    _draw(p, 350, 75, "Invoice No: SGE/2025-26/0457")
    xs = [40, 70, 250, 310, 420, 555]
    top, rh = 100, 18
    items = [("Sr.", "Description", "HSN", "Qty", "Amount"), ("1", "MS Flange 4 inch", "7307", "120", "54,000.00"),
             ("2", "SS Ball Valve 2 inch", "8481", "35", "99,750.00")]
    for k in range(len(items) + 1):
        p.draw_line((xs[0], top + k * rh), (xs[-1], top + k * rh))
    for x in xs:
        p.draw_line((x, top), (x, top + len(items) * rh))
    for k, row in enumerate(items):
        for j, t in enumerate(row):
            if j >= 3 and k > 0:
                _draw(p, xs[j + 1] - 3, top + k * rh + 12, t, right=True)
            else:
                _draw(p, xs[j] + 3, top + k * rh + 12, t, bold=k == 0)
    _draw(p, 330, 175, "Taxable value")
    _draw(p, 552, 175, "1,53,750.00", right=True)
    _draw(p, 330, 190, "Invoice total", bold=True)
    _draw(p, 552, 190, "1,81,425.00", bold=True, right=True)
    src = out("invoice.pdf")
    d.save(src)
    r = convert.to_excel(ctx, src, out("invoice.xlsx"))
    wb, flat = _xl_cells(r["output"])
    ws = wb["Page 1"]
    head = _row_with(ws, "Description")
    amount_col = head.index("Amount")
    assert _row_with(ws, "MS Flange 4 inch") == ("1", "MS Flange 4 inch", 7307, 120, 54000.0) or \
        _row_with(ws, "MS Flange 4 inch")[amount_col] == 54000.0
    # The totals under the table land in the table's Amount column.
    assert _row_with(ws, "Taxable value")[amount_col] == 153750.0
    assert _row_with(ws, "Invoice total")[amount_col] == 181425.0
    assert "TAX INVOICE" in flat and "Invoice No: SGE/2025-26/0457" in flat


def test_excel_text_is_never_a_formula(ctx):
    import zipfile

    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((50, 80), '=HYPERLINK("http://x.example","click")', fontsize=10)
    p.insert_text((50, 100), "Bell\x07 inside", fontsize=10)
    _draw(p, 50, 120, "Total")
    _draw(p, 300, 120, "-5.00", right=True)
    src = out("hostile.pdf")
    d.save(src)
    r = convert.to_excel(ctx, src, out("hostile.xlsx"))
    wb, flat = _xl_cells(r["output"])
    assert '=HYPERLINK("http://x.example","click")' in flat and "Bell inside" in flat and -5.0 in flat
    assert "<f>" not in zipfile.ZipFile(r["output"]).read("xl/worksheets/sheet1.xml").decode()
    r = convert.to_excel(ctx, src, out("hostile.csv"))
    text = open(r["output"], encoding="utf-8-sig").read()
    assert "'=HYPERLINK" in text and "-5.00" in text and "'-5.00" not in text


@needs_tesseract
def test_excel_from_a_scan_keeps_phrases_and_amounts(ctx):
    """A scanned statement read by OCR: recognised word sizes are estimates, so a
    phrase must not be split at its spaces, and the amounts keep their columns."""
    src = out("pl_vector.pdf")
    _borderless_pl(src)
    vec = pymupdf.open(src)
    pix = vec[0].get_pixmap(dpi=200)
    scan = pymupdf.open()
    pg = scan.new_page(width=vec[0].rect.width, height=vec[0].rect.height)
    pg.insert_image(pg.rect, stream=pix.tobytes("png"))
    scanned = out("pl_scan.pdf")
    scan.save(scanned)
    r = convert.to_excel(ctx, scanned, out("pl_scan.xlsx"), ocr="auto")
    assert r.get("ocr")
    wb, flat = _xl_cells(r["output"])
    ws = wb["Page 1"]
    row = _row_with(ws, "Changes in inventories of finished goods")
    assert [v for v in row if v is not None][-3:] == [25, -1234567.0, 876543.0]
    assert _row_with(ws, "Revenue from operations")[-2:] == (123456789.0, 98765432.5)
    assert "ACME TRADERS PRIVATE LIMITED" in flat


def test_excel_large_document_keeps_every_line(fx, ctx):
    r = convert.to_excel(ctx, fx["large"], out("large_doc.xlsx"), layout="document", ocr="off")
    lines = sum(1 for pg in pymupdf.open(fx["large"]) for ln in pg.get_text("text").splitlines() if ln.strip())
    assert r["rows"] >= lines and r["reviewPages"] == []


# ---- PDF -> PowerPoint ------------------------------------------------------------

@needs_soffice
@pytest.mark.parametrize("mode", ["image", "hybrid", "editable"])
def test_pdf_to_ppt_modes(fx, ctx, mode):
    import pptx

    r = convert.to_powerpoint(ctx, fx["financial"], out(f"fin_{mode}.pptx"), mode=mode, pages="1")
    p = pptx.Presentation(r["output"])
    shapes = list(p.slides[0].shapes)
    texts = " ".join(s.text_frame.text for s in shapes if s.has_text_frame)
    if mode == "image":
        assert r["textBoxes"] == 0 and r["pictures"] == 1 and texts.strip() == ""
    else:
        assert "ACME Traders" in texts and "1,23,456.78" in texts


# ---- PDF -> images / text / html / md ------------------------------------------------

def test_pdf_to_images(fx, ctx):
    from PIL import Image

    od = os.path.join(os.path.dirname(out("x")), "imgs")
    r = convert.to_images(ctx, fx["c"], od, format="png", dpi=100, pages="2-3", overwrite=True)
    assert r["count"] == 2
    for o, pg in zip(r["outputs"], (2, 3)):
        assert o["output"].endswith(f"c_page_{pg:03d}.png")
        with Image.open(o["output"]) as im:
            assert im.format == "PNG"
            assert abs(im.size[0] - 595 * 100 / 72) <= 1 and abs(im.size[1] - 842 * 100 / 72) <= 1, im.size
            assert im.size == (o["width"], o["height"])
    for fmt, pil in (("jpg", "JPEG"), ("tiff", "TIFF"), ("bmp", "BMP"), ("webp", "WEBP")):
        r = convert.to_images(ctx, fx["a"], od, format=fmt, dpi=72, pages="1", overwrite=True)
        with Image.open(r["outputs"][0]["output"]) as im:
            assert im.format == pil


def test_pdf_to_text_layout_html_md(fx, ctx):
    r = convert.to_text(ctx, fx["twenty"], out("t.txt"), pages="1-2")
    t = open(r["output"], encoding="utf-8").read()
    assert "Report page 1" in t and "Report page 2" in t
    r = convert.to_html(ctx, fx["a"], out("t.html"))
    assert "A page 1" in open(r["output"], encoding="utf-8").read()
    r = convert.to_markdown(ctx, fx["a"], out("t.md"))
    assert "A page 1" in open(r["output"], encoding="utf-8").read()


# ---- into PDF -------------------------------------------------------------------------

@needs_soffice
def test_office_to_pdf_word_excel_ppt(fx, ctx):
    import openpyxl
    import pptx

    r = convert.office_to_pdf(ctx, fx["financial_docx"], out("docx2pdf.pdf"))
    assert "ACME Traders" in page_texts(r["output"])[0]
    xl = out("sheet.xlsx")
    wb = openpyxl.Workbook()
    wb.active.append(["Item", "Amount"])
    wb.active.append(["Audit fee", 75000])
    wb.save(xl)
    r = convert.office_to_pdf(ctx, xl, out("xlsx2pdf.pdf"), singlePageSheets=True)
    assert "Audit fee" in page_texts(r["output"])[0]
    pp = out("deck.pptx")
    prs = pptx.Presentation()
    s = prs.slides.add_slide(prs.slide_layouts[1])
    s.shapes.title.text = "Board report Q1"
    prs.save(pp)
    r = convert.office_to_pdf(ctx, pp, out("pptx2pdf.pdf"))
    assert "Board report Q1" in page_texts(r["output"])[0]


def test_images_to_pdf(fx, ctx):
    r = create.from_images(ctx, [fx["jpg"], fx["png"]], out("imgs.pdf"), pageSize="A4", margin=36)
    d = pymupdf.open(r["output"])
    assert d.page_count == 2
    assert round(d[0].rect.width) == 842  # landscape image -> landscape page (auto)
    d.close()
    r = create.from_images(ctx, [fx["jpg"]], out("imgs_fit.pdf"), pageSize="fit")
    assert r["pageCount"] == 1


def test_text_to_pdf(fx, ctx):
    r = create.from_text(ctx, fx["txt"], out("txt.pdf"))
    t = "".join(page_texts(r["output"]))
    assert "Line 119: amount ₹ 119,000" in t


@needs_soffice
def test_assemble_mixed(fx, ctx):
    r = create.assemble(ctx, [fx["a"], fx["jpg"], fx["financial_docx"], fx["txt"]], out("assembled.pdf"))
    d = pymupdf.open(r["output"])
    assert d.page_count >= 5
    assert [t[1] for t in d.get_toc() if t[0] == 1][:2] == ["a", "photo"]
    d.close()


# ---- OCR --------------------------------------------------------------------------------

@needs_tesseract
def test_ocr_creates_searchable_pdf(fx, ctx):
    src = fx["scanned"]
    assert not any(t.strip() for t in page_texts(src))
    r = ocr.run(ctx, src, out("scanned_ocr.pdf"), languages=["eng"], pages="1")
    assert r["processed"] == [1]
    d = pymupdf.open(r["output"])
    assert d[0].search_for("Traders"), "OCR text not searchable"
    assert d[0].search_for("Revenue")
    # The original scan image is preserved untouched.
    assert len(d[0].get_images()) == 1
    d.close()
    assert r["pages"][1]["meanConfidence"] is not None


@needs_tesseract
def test_ocr_skips_text_pages(fx, ctx):
    from nayanpdf_engine.errors import EngineError

    with pytest.raises(EngineError):
        ocr.run(ctx, fx["a"], out("a_ocr.pdf"))


@needs_tesseract
@needs_soffice
def test_scanned_to_excel_with_ocr(fx, ctx):
    import openpyxl

    r = convert.to_excel(ctx, fx["scanned"], out("scan.xlsx"), pages="1", ocr="auto")
    assert r.get("ocr")
    wb = openpyxl.load_workbook(r["output"])
    flat = [str(v) for ws in wb.worksheets for row in ws.iter_rows(values_only=True) for v in row if v is not None]
    assert any("Revenue" in v for v in flat)


@needs_tesseract
def test_ocr_plain_text_extraction(fx, ctx):
    r = ocr.text(ctx, fx["scanned"], out("scanned_ocr.txt"), pages="1")
    txt = open(r["output"], encoding="utf-8").read()
    assert "Revenue" in txt


@needs_tesseract
def test_ocr_turns_sideways_scan_upright(fx, ctx):
    from nayanpdf_engine.util import tools
    if not os.path.exists(os.path.join(tools.tessdata_dir() or "", "osd.traineddata")):
        pytest.skip("orientation data (osd) not installed")
    # A scan fed into the scanner sideways: the page image is rotated 90 degrees.
    from PIL import Image
    src = pymupdf.open(fx["scanned"])
    pix = src[0].get_pixmap(dpi=200)
    src.close()
    img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples).rotate(90, expand=True)
    png = out("sideways.png")
    img.save(png)
    side = pymupdf.open()
    pg = side.new_page(width=img.width * 72 / 200, height=img.height * 72 / 200)
    pg.insert_image(pg.rect, filename=png)
    side_pdf = out("sideways.pdf")
    side.save(side_pdf)
    side.close()
    r = ocr.run(ctx, side_pdf, out("sideways_ocr.pdf"), languages=["eng"])
    assert r.get("rotated"), r
    d = pymupdf.open(r["output"])
    assert d[0].search_for("Revenue"), "text on the corrected page is not searchable"
    d.close()


@needs_soffice
def test_office_conversion_never_fetches_linked_content(ctx, tmp_path, monkeypatch):
    """Office files can reference images or data by URL. Converting them must not
    contact the network: a local HTTP server stands in for the remote host and
    must receive no request."""
    import http.server
    import io
    import threading

    import docx
    import openpyxl
    from docx.opc.constants import RELATIONSHIP_TYPE as RT
    from PIL import Image

    hits = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            hits.append(self.path)
            buf = io.BytesIO()
            Image.new("RGB", (40, 40), (200, 0, 0)).save(buf, "PNG")
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.end_headers()
            self.wfile.write(buf.getvalue())

        def log_message(self, *args):
            pass

    srv = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_port}"
    monkeypatch.setenv("NAYANPDF_LO_PROFILES", str(tmp_path / "profiles"))
    try:
        # Word file whose picture is linked to a URL instead of embedded.
        d = docx.Document()
        d.add_paragraph("Linked image test")
        buf = io.BytesIO()
        Image.new("RGB", (40, 40), (0, 0, 200)).save(buf, "PNG")
        buf.seek(0)
        d.add_picture(buf)
        rid = d.part.relate_to(base + "/tracker.png", RT.IMAGE, is_external=True)
        ns = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
        for blip in d.element.body.iter("{http://schemas.openxmlformats.org/drawingml/2006/main}blip"):
            blip.attrib.pop(ns + "embed", None)
            blip.set(ns + "link", rid)
        src_docx = str(tmp_path / "linked.docx")
        d.save(src_docx)
        r = convert.office_to_pdf(ctx, src_docx, str(tmp_path / "linked.pdf"))
        assert "Linked image test" in page_texts(r["output"])[0]

        # Excel file with a formula that would download data when recalculated.
        wb = openpyxl.Workbook()
        wb.active["A1"] = "Rate"
        wb.active["B1"] = f'=WEBSERVICE("{base}/rate")'
        src_xlsx = str(tmp_path / "webservice.xlsx")
        wb.save(src_xlsx)
        r = convert.office_to_pdf(ctx, src_xlsx, str(tmp_path / "webservice.pdf"))
        assert "Rate" in page_texts(r["output"])[0]
    finally:
        srv.shutdown()
    assert hits == [], f"conversion contacted the network: {hits}"


@needs_tesseract
def test_ocr_with_language_files_only(fx, ctx, tmp_path, monkeypatch):
    """The installer ships tessdata with only the .traineddata files. OCR must not
    depend on Tesseract's optional configs folder (this failed on Windows once)."""
    import shutil
    from nayanpdf_engine.util import tools

    src = tools.tessdata_dir()
    td = tmp_path / "tessdata"
    td.mkdir()
    shutil.copy(os.path.join(src, "eng.traineddata"), td / "eng.traineddata")
    monkeypatch.setenv("NAYANPDF_TESSDATA", str(td))
    r = ocr.run(ctx, fx["scanned"], out("scanned_ocr_minimal.pdf"), languages=["eng"], pages="1", autoRotate=False)
    d = pymupdf.open(r["output"])
    assert d[0].search_for("Revenue"), "OCR text not searchable with a minimal tessdata folder"
    d.close()
