"""Page layout to spreadsheet rows, the way Acrobat exports a PDF to Excel.

Every text line of a page becomes a worksheet row, in reading order, and the
pieces of a line that are set apart on the page (a label, a note number, an
amount) go into separate cells. Columns are found from where the text sits:

* a ruled table (a grid of drawn lines) keeps its own cell boundaries;
* elsewhere, a column boundary is an x position that the text of the
  multi-part lines leaves empty;
* an amount goes into the column of its right edge (amounts are right-aligned),
  other text into the column of its left edge, except a heading right-aligned
  over amounts, which follows them;
* a cell that wraps onto further lines (a long bank narration) is joined back
  into one cell, when its continuation lines are closer together than the rows;
* headings, notes and other text stay where they are, between the tables.

No text is dropped, cut inside a word, or moved to another sheet. The only
exception is the optional "whole document on one sheet" mode, which shows a
page header, footer or repeated column heading once (see ``drop_repeats``).
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass, field

import pymupdf

from ..util.numbers import parse_date, parse_number


@dataclass
class Seg:
    x0: float
    x1: float
    y0: float
    y1: float
    text: str
    bold: bool = False
    size: float = 10.0
    forced_col: float | None = None  # left boundary of a ruled-table cell
    words: list = field(default_factory=list)  # (x0, x1, text, run) of the words, for splitting at a column boundary
    trailing_space: bool = False  # a ruled-table cell whose last line ended with a space (a soft wrap)

    @property
    def numeric(self) -> bool:
        return parse_number(self.text) is not None


@dataclass
class Line:
    y0: float
    y1: float
    segs: list[Seg]
    table: int | None = None  # index of the ruled table it belongs to
    cells: dict[int, Seg] = field(default_factory=dict)  # column -> segment, after placement
    page: int = 0
    blank_before: bool = False
    keeps_wrap: bool = False  # the page keeps the space at soft line wraps
    run: int = 0  # pages laid out alike share one set of columns

    @property
    def text(self) -> str:
        return " ".join(s.text for s in self.segs)

    @property
    def size(self) -> float:
        return max((s.size for s in self.segs), default=10.0)


# ---------------------------------------------------------------- words


@dataclass
class _Word:
    x0: float
    y0: float
    x1: float
    y1: float
    text: str
    run: tuple  # (block, line): one text run of the PDF
    bold: bool
    size: float
    spaces_before: int = 0  # space characters between this word and the previous one in its run


def _is_bold(span) -> bool:
    f = span.get("font", "")
    return bool(span.get("flags", 0) & 16) or bool(re.search(r"bold|black|heavy|semibold|demi", f, re.I))


def _words(page: pymupdf.Page, skip: list[pymupdf.Rect]) -> list[_Word]:
    """Words of the page with their text run, boldness and size; ligatures expanded."""
    flags = (pymupdf.TEXTFLAGS_RAWDICT & ~pymupdf.TEXT_PRESERVE_IMAGES & ~pymupdf.TEXT_PRESERVE_LIGATURES)
    raw = page.get_text("rawdict", flags=flags)
    out: list[_Word] = []
    for bi, b in enumerate(raw["blocks"]):
        for li, ln in enumerate(b.get("lines", [])):
            dx, dy = ln.get("dir", (1, 0))
            horizontal = abs(dy) < 0.1 and dx > 0
            if not horizontal:
                # Rotated text (a stamp, a margin label): kept whole, where it lies.
                t = "".join(c["c"] for s in ln["spans"] for c in s["chars"]).strip()
                if t:
                    r = pymupdf.Rect(ln["bbox"])
                    if not any(_inside(r, k) for k in skip):
                        out.append(_Word(r.x0, r.y0, r.x1, r.y1, re.sub(r"\s+", " ", t), (bi, li), False, 10.0))
                continue
            cur: list = []
            spaces = [0]

            def flush():
                if not cur:
                    return
                text = "".join(c["c"] for c, _ in cur)
                x0 = min(c["bbox"][0] for c, _ in cur)
                x1 = max(c["bbox"][2] for c, _ in cur)
                y0 = min(c["bbox"][1] for c, _ in cur)
                y1 = max(c["bbox"][3] for c, _ in cur)
                bold = all(_is_bold(s) for _, s in cur)
                size = max(s.get("size", 10.0) for _, s in cur)
                r = pymupdf.Rect(x0, y0, x1, y1)
                if not any(_inside(r, k) for k in skip):
                    out.append(_Word(x0, y0, x1, y1, text, (bi, li), bold, size, spaces[0]))
                cur.clear()
                spaces[0] = 0

            for s in ln["spans"]:
                for c in s["chars"]:
                    if c["c"].isspace():
                        flush()
                        spaces[0] += 1
                    else:
                        cur.append((c, s))
            flush()
    return _dedupe(out)


def _inside(r: pymupdf.Rect, box: pymupdf.Rect) -> bool:
    cx, cy = (r.x0 + r.x1) / 2, (r.y0 + r.y1) / 2
    return box.x0 - 0.5 <= cx <= box.x1 + 0.5 and box.y0 - 0.5 <= cy <= box.y1 + 0.5


def _dedupe(words: list[_Word]) -> list[_Word]:
    """Drop words printed twice at (almost) the same place: some programs fake bold that way."""
    keep: list[_Word] = []
    seen: dict[str, list[_Word]] = {}
    for w in words:
        dup = False
        for o in seen.get(w.text, []):
            ix = min(w.x1, o.x1) - max(w.x0, o.x0)
            iy = min(w.y1, o.y1) - max(w.y0, o.y0)
            if ix > 0.7 * (w.x1 - w.x0) and iy > 0.7 * (w.y1 - w.y0):
                dup = True
                o.bold = True
                break
        if not dup:
            keep.append(w)
            seen.setdefault(w.text, []).append(w)
    return keep


# ---------------------------------------------------------------- lines and segments


def _visual_lines(words: list[_Word]) -> list[list[_Word]]:
    """Group words whose vertical centres line up into visual lines (top to bottom)."""
    ws = sorted(words, key=lambda w: ((w.y0 + w.y1) / 2, w.x0))
    lines: list[list[_Word]] = []
    centres: list[float] = []
    heights: list[float] = []
    for w in ws:
        cy = (w.y0 + w.y1) / 2
        h = max(w.y1 - w.y0, 1.0)
        placed = False
        # Compare with the last few lines (lines are built top-down).
        for k in range(len(lines) - 1, max(-1, len(lines) - 4), -1):
            if abs(cy - centres[k]) <= 0.45 * min(h, heights[k]):
                lines[k].append(w)
                n = len(lines[k])
                centres[k] = (centres[k] * (n - 1) + cy) / n
                heights[k] = max(heights[k], h)
                placed = True
                break
        if not placed:
            lines.append([w])
            centres.append(cy)
            heights.append(h)
    order = sorted(range(len(lines)), key=lambda k: centres[k])
    return [sorted(lines[k], key=lambda w: w.x0) for k in order]


def _segments(line: list[_Word]) -> list[Seg]:
    """Split a visual line into the pieces that are set apart on the page."""
    segs: list[Seg] = []
    prev: _Word | None = None
    cur: list[_Word] = []

    def flush():
        if cur:
            segs.append(Seg(min(w.x0 for w in cur), max(w.x1 for w in cur), min(w.y0 for w in cur), max(w.y1 for w in cur),
                            " ".join(w.text for w in cur), all(w.bold for w in cur), max(w.size for w in cur),
                            words=[(w.x0, w.x1, w.text, w.run, w.bold, w.size, w.y0, w.y1) for w in cur]))
            cur.clear()

    for w in line:
        if prev is not None:
            gap = w.x0 - prev.x1
            size = max(min(w.size, prev.size), 4.0)
            # Inside one text run, a single space joins a phrase even where it is drawn
            # wide (justified or recognised text, whose font sizes are estimates);
            # several spaces in a row set pieces apart. Separate runs side by side are
            # separate cells unless they nearly touch.
            if w.run == prev.run:
                limit = (2.5 if w.spaces_before <= 1 else 0.9) * size
            else:
                limit = 0.55 * size
            if gap > limit:
                flush()
        cur.append(w)
        prev = w
    flush()
    return segs


# ---------------------------------------------------------------- ruled tables


def _ruled_tables(page: pymupdf.Page, page_chars) -> list[tuple[list[pymupdf.Rect], list[float], list[Line]]]:
    """Tables drawn as a grid of lines, kept only when no word is cut by a cell border."""
    try:
        found = page.find_tables(strategy="lines").tables
    except Exception:  # noqa: BLE001
        return []
    words = page.get_text("words")
    out = []
    for t in found:
        if t.row_count < 2 or t.col_count < 2:
            continue
        bbox = pymupdf.Rect(t.bbox)
        cells = [pymupdf.Rect(c) for c in t.cells if c]
        inside = [w for w in words if _inside(pymupdf.Rect(w[:4]), bbox)]
        if not inside:
            continue
        cut = 0
        for w in inside:
            wr = pymupdf.Rect(w[:4])
            home = next((c for c in cells if _inside(wr, c)), None)
            if home is None or wr.x0 < home.x0 - 2 or wr.x1 > home.x1 + 2:
                cut += 1
        if cut > max(1, 0.02 * len(inside)):
            continue  # the grid does not match the text: read it by alignment instead
        xs = sorted({round(c.x0, 1) for c in cells} | {round(c.x1, 1) for c in cells})
        lines = []
        for row in t.rows:
            segs = []
            ys = [c for c in row.cells if c]
            if not ys:
                continue
            for cb in row.cells:
                if cb is None:
                    continue
                r = pymupdf.Rect(cb)
                txt, _, trailing = page_chars.cell_text(r)
                if not txt:
                    continue
                bold = _bold_in(page, r)
                size = _size_in(page, r)
                segs.append(Seg(r.x0, r.x1, r.y0, r.y1, txt, bold, size, forced_col=r.x0, trailing_space=trailing))
            if segs:
                lines.append(Line(min(s.y0 for s in segs), max(s.y1 for s in segs), segs))
        if lines:
            out.append((cells, xs, lines))
    return out


def _bold_in(page: pymupdf.Page, r: pymupdf.Rect) -> bool:
    spans = [s for b in page.get_text("dict", clip=r)["blocks"] for ln in b.get("lines", []) for s in ln["spans"] if s["text"].strip()]
    return bool(spans) and all(_is_bold(s) for s in spans)


def _size_in(page: pymupdf.Page, r: pymupdf.Rect) -> float:
    sizes = [s["size"] for b in page.get_text("dict", clip=r)["blocks"] for ln in b.get("lines", []) for s in ln["spans"] if s["text"].strip()]
    return max(sizes) if sizes else 10.0


# ---------------------------------------------------------------- page analysis


def page_lines(page: pymupdf.Page, page_no: int, page_chars_factory) -> tuple[list[Line], list[list[float]]]:
    """Lines of one page (ruled-table rows included) and each ruled table's column boundaries."""
    chars = page_chars_factory(page)
    tables = _ruled_tables(page, chars)
    # Words in a ruled table's cells are read with the table; anything else in its
    # frame (a caption inside the border) is read with the page.
    skip = [c for cells, _, _ in tables for c in cells]
    forced: list[list[float]] = []
    lines: list[Line] = []
    for ti, (_, xs, tlines) in enumerate(tables):
        forced.append(xs)
        for ln in tlines:
            ln.table = ti
            lines.append(ln)
    for wl in _visual_lines(_words(page, skip)):
        segs = _segments(wl)
        if segs:
            lines.append(Line(min(s.y0 for s in segs), max(s.y1 for s in segs), segs))
    lines.sort(key=lambda ln: ((ln.y0 + ln.y1) / 2, ln.segs[0].x0))
    for ln in lines:
        ln.page = page_no
        ln.keeps_wrap = chars.keeps_wrap_spaces
    return lines, forced


def column_bounds(lines: list[Line], tables: list[list[float]]) -> list[float]:
    """Left boundaries of the columns (the first is -inf).

    ``tables`` holds each ruled table's column boundaries; they always apply. A
    boundary found from the text alone that falls inside a ruled table's column is
    kept only when many lines support it (a borderless table of its own); a few
    lines near the table (its totals, the invoice header) follow the table's grid.
    """
    multi = [ln for ln in lines if len(ln.segs) >= 2 and ln.table is None]
    seps: list[float] = []
    found: list[tuple[float, int]] = []
    if multi:
        lo = int(min(s.x0 for ln in multi for s in ln.segs))
        hi = int(math.ceil(max(s.x1 for ln in multi for s in ln.segs)))
        n = hi - lo + 1
        cross = [0] * n  # lines whose text lies across x
        votes = [0] * n  # lines with a gap between two pieces at x
        for ln in multi:
            for s in ln.segs:
                for x in range(max(0, int(s.x0) - lo), min(n, int(math.ceil(s.x1)) - lo)):
                    cross[x] += 1
            for p, q in zip(ln.segs, ln.segs[1:]):
                for x in range(max(0, int(math.ceil(p.x1)) - lo), min(n, int(q.x0) - lo)):
                    votes[x] += 1
        # A boundary may cross a few lines (a wide heading, a signature line), as
        # long as clearly more lines have a gap there.
        limit = max(1, int(0.12 * len(multi)))

        def good(x):
            c = cross[x]
            return votes[x] >= 1 and c <= limit and votes[x] >= 2 * c + (1 if c else 0)

        x = 0
        while x < n:
            if not good(x):
                x += 1
                continue
            y = x
            while y < n and good(y):
                y += 1
            # One boundary per clear stretch of the gap: a heading lying in the gap
            # ("Deposit" over a column with no amounts on this page) is a column of
            # its own. Where nothing is clear, the gap gives one boundary.
            clear: list[tuple[int, int]] = []
            k = x
            while k < y:
                if cross[k] == 0:
                    j = k
                    while j < y and cross[j] == 0:
                        j += 1
                    clear.append((k, j))
                    k = j
                else:
                    k += 1
            for a_, b_ in (clear or [(x, y)]):
                # The best-supported position, and of those the last one: a column
                # begins where its text begins.
                best = max(range(a_, b_), key=lambda k: (votes[k] - 2 * cross[k], k))
                found.append((lo + best + 0.5, votes[best]))
            x = y
    for pos, support in found:
        inside = any(xs[0] < pos < xs[-1] and all(abs(pos - b) > 4 for b in xs) for xs in tables)
        if inside and support < 8:
            continue
        seps.append(pos)
    forced = [b for xs in tables for b in xs]
    seps.extend(forced)
    seps.sort()
    merged: list[float] = []
    for s in seps:
        if merged and s - merged[-1] < 4:
            continue
        merged.append(s)
    return merged


def layout_runs(per_page: list[list[Line]], tables: list[list[list[float]]]) -> list[list[int]]:
    """Consecutive pages with the same columns (a statement running over pages)
    form one run that shares a grid; a page laid out differently starts a new run."""

    def same(a: list[float], b: list[float]) -> bool:
        if not a and not b:
            return True
        hit = sum(1 for x in a if any(abs(x - y) <= 3 for y in b))
        return hit >= 0.6 * max(len(a), len(b))

    runs: list[list[int]] = []
    ref: list[float] | None = None
    for k, (ls, tb) in enumerate(zip(per_page, tables)):
        b = column_bounds(ls, tb)
        if runs and ref is not None and (same(ref, b) or not ls):
            runs[-1].append(k)
        else:
            runs.append([k])
            ref = b
    return runs


def place(lines: list[Line], bounds: list[float]) -> int:
    """Put every segment of every line in a column; returns the number of columns."""
    import bisect

    # Right edges of amounts, so a heading right-aligned over a column of amounts follows them.
    num_right: list[float] = sorted(s.x1 for ln in lines if ln.table is None for s in ln.segs if s.numeric)

    def col_of(x: float) -> int:
        return bisect.bisect_right(bounds, x)

    def aligned_with_amounts(x1: float) -> bool:
        k = bisect.bisect_left(num_right, x1 - 1.5)
        return k < len(num_right) and num_right[k] <= x1 + 1.5 and sum(1 for v in num_right[k:k + 3] if abs(v - x1) <= 1.5) >= 2

    def split_at_bounds(s: Seg) -> list[Seg]:
        """Two text runs set close together but either side of a column boundary
        (a bold "VIII" 3 pt from its caption) are two cells."""
        if s.forced_col is not None or len(s.words) < 2:
            return [s]
        parts: list[list] = [[s.words[0]]]
        for prev, w in zip(s.words, s.words[1:]):
            gap_has_bound = any(prev[1] - 0.5 <= b <= w[0] + 0.5 for b in bounds)
            if prev[3] != w[3] and gap_has_bound:
                parts.append([w])
            else:
                parts[-1].append(w)
        if len(parts) == 1:
            return [s]
        return [Seg(min(w[0] for w in p), max(w[1] for w in p), min(w[6] for w in p), max(w[7] for w in p),
                    " ".join(w[2] for w in p), all(w[4] for w in p), max(w[5] for w in p), words=p) for p in parts]

    def off_grid_run(segs: list[Seg]) -> bool:
        """One printed run spaced out with blanks ("Account No: ...    Branch: ...")
        whose pieces do not start at the page's columns: a line of text, not cells.
        (A statement printed as fixed-width text has its pieces on the columns.)"""
        if len(segs) < 2 or any(s.forced_col is not None or not s.words for s in segs):
            return False
        runs = {w[3] for s in segs for w in s.words}
        if len(runs) != 1:
            return False
        on = 0
        for s in segs[1:]:
            # On a column: starts at a column's left edge, or ends where an amount
            # ends (a right-aligned heading such as "Withdrawal" or "Deposit").
            k = bisect.bisect_left(num_right, s.x1 - 1.5)
            ends_at_amount = k < len(num_right) and num_right[k] <= s.x1 + 1.5
            if any(abs(s.x0 - b) <= 3 for b in bounds) or ends_at_amount:
                on += 1
        return on < 0.6 * (len(segs) - 1)

    ncols = len(bounds) + 1
    for ln in lines:
        ln.cells = {}
        if off_grid_run(ln.segs):
            ws_ = [w for s in ln.segs for w in s.words]
            ln.segs = [Seg(ln.segs[0].x0, ln.segs[-1].x1, min(s.y0 for s in ln.segs), max(s.y1 for s in ln.segs),
                           "   ".join(s.text for s in ln.segs), all(s.bold for s in ln.segs), max(s.size for s in ln.segs), words=ws_)]
        ln.segs = [p for s in ln.segs for p in split_at_bounds(s)]
        for s in ln.segs:
            if s.forced_col is not None:
                c = col_of(s.forced_col + 0.5)
            elif s.numeric or aligned_with_amounts(s.x1):
                c = col_of(s.x1 - 0.5)
            else:
                c = col_of(s.x0 + 0.5)
            if c in ln.cells:
                o = ln.cells[c]
                ln.cells[c] = Seg(min(o.x0, s.x0), max(o.x1, s.x1), min(o.y0, s.y0), max(o.y1, s.y1), o.text + " " + s.text,
                                  o.bold and s.bold, max(o.size, s.size), o.forced_col)
            else:
                ln.cells[c] = s
    return ncols


def _continues(a: Seg, b: Seg) -> bool:
    """Whether ``b`` (the next line of a cell) can be the rest of ``a``: any text
    continues text; under an amount only what completes it ("3,76,543.22" / "Cr"),
    never a second value."""
    if not a.numeric:
        return True
    return parse_number(a.text + " " + b.text) is not None


def _joiner(a: Seg, b: Seg, keeps_wrap: bool = False) -> str:
    """What goes between two pieces of one cell."""
    if a.numeric:
        return "" if a.text[-1:].isdigit() and b.text[:1].isdigit() else " "
    if a.trailing_space:
        return " "
    if a.text.endswith(("-", "/")) or b.text.startswith(("-", "/")) or keeps_wrap or (a.text[-1:].isdigit() and b.text[:1].isdigit()):
        # The break fell inside a word, a reference or a number: a PDF that keeps the
        # space at ordinary line wraps has none here.
        return ""
    return " "


def join_wrapped_cells(lines: list[Line]) -> list[Line]:
    """Join the continuation lines of a wrapped cell (a long narration) to its row.

    A line is a continuation when it holds text only, in columns where the row
    above has text, it follows that row more closely than rows follow each other,
    and the row above is a multi-cell row (not a heading).
    """
    import statistics

    pitches = []
    prev = None
    for ln in lines:
        if ln.table is None and len(ln.cells) >= 2:
            if prev is not None and ln.page == prev.page:
                pitches.append(((ln.y0 + ln.y1) - (prev.y0 + prev.y1)) / 2)
            prev = ln
        elif ln.table is None:
            pass
    if not pitches:
        return lines
    row_pitch = statistics.median(pitches)

    # First pass: which lines continue which row, and in which column.
    plan: list[tuple[Line, Line | None]] = []  # (line, the row it continues or None)
    anchor: Line | None = None
    last: Line | None = None
    for ln in lines:
        target = None
        if anchor is not None and last is not None and ln.table is None and ln.page == anchor.page:
            pitch = ((ln.y0 + ln.y1) - (last.y0 + last.y1)) / 2
            # Text continuing text cells of the row above (a narration; a reference
            # cut as "00098765432" / "1"), never an amount column or an empty one.
            fits = all(c in anchor.cells and _continues(anchor.cells[c], s) for c, s in ln.cells.items())
            # A date under the row's own date is a new row (a transaction), not
            # the rest of one; under a heading ("Year ended" / "31 March 2026") it
            # is the heading's second line.
            dated = any(parse_date(s.text) and parse_date(anchor.cells[c].text) for c, s in ln.cells.items() if c in anchor.cells)
            if fits and not dated and pitch < 0.85 * row_pitch and pitch <= 1.45 * ln.size:
                target = anchor
        plan.append((ln, target))
        last = ln
        if target is None:
            anchor = ln if ln.table is None and len(ln.cells) >= 2 else None

    # Some statements cut a long narration at a fixed number of characters, even
    # inside a word ("SURESH/Salar" + "y advance"): when the first pieces of the
    # wrapped cells all have the same length, the pieces are joined without a space.
    first_len: dict[tuple, int] = {}
    for ln, target in plan:
        if target is not None:
            for c in ln.cells:
                if (id(target), c) not in first_len:
                    first_len[(id(target), c)] = len(target.cells[c].text)
    lens = list(first_len.values())
    fixed = None
    if len(lens) >= 3:
        common = max(set(lens), key=lens.count)
        if lens.count(common) >= 0.6 * len(lens):
            fixed = common

    out: list[Line] = []
    pieces: dict[tuple, int] = {}
    for ln, target in plan:
        if target is None:
            out.append(ln)
            continue
        for c, s in ln.cells.items():
            a = target.cells[c]
            key = (id(target), c)
            n = pieces.get(key, 0)
            # Cut at a fixed width: every piece so far is exactly that long. If the
            # cut fell on a space, the next piece starts one space further right.
            hard = fixed is not None and len(a.text) == fixed * (n + 1)
            indented = s.x0 - a.x0 > 0.18 * max(s.size, 4.0)
            digits = a.text[-1:].isdigit() and s.text[:1].isdigit()  # a number or reference cut in two
            if a.numeric:
                sep = _joiner(a, s)
            else:
                sep = "" if (hard and not indented) or digits or a.text.endswith(("-", "/")) else " "
            target.cells[c] = Seg(a.x0, max(a.x1, s.x1), a.y0, s.y1, a.text + sep + s.text, a.bold and s.bold, a.size, a.forced_col)
            pieces[key] = n + 1
        target.y1 = max(target.y1, ln.y1)
    return out


def join_across_pages(pages: list[list[Line]]) -> None:
    """On one sheet for the whole document, a table row that the page break cut in
    two (its first column empty on the next page, text only, in columns the row
    above has text) is one row again."""
    for k in range(1, len(pages)):
        prev = next((ls for ls in reversed(pages[:k]) if ls), None)
        cur = pages[k]
        if not prev or not cur:
            continue
        a, b = prev[-1], cur[0]
        if len(a.cells) < 2 or not b.cells:
            continue
        # The rest of a cut row: its first column is empty (no new date starts
        # there), and it only continues cells of the row above: a narration, a
        # reference cut as "00098765432" / "1", an amount cut as "3,76,543.22" /
        # "Cr"; never a second value, nor a column the row above leaves empty.
        if any(parse_date(s.text) for s in b.cells.values()):
            continue
        if min(b.cells) <= min(a.cells) or any(c not in a.cells or not _continues(a.cells[c], s) for c, s in b.cells.items()):
            continue
        for c, s in b.cells.items():
            t = a.cells[c]
            sep = _joiner(t, s, a.keeps_wrap)
            a.cells[c] = Seg(t.x0, max(t.x1, s.x1), t.y0, t.y1, t.text + sep + s.text, t.bold and s.bold, t.size, t.forced_col,
                             trailing_space=s.trailing_space)
        del cur[0]


def mark_blank_rows(lines: list[Line]) -> None:
    """A wide vertical gap on the page becomes one empty row, as the eye sees sections."""
    import statistics

    gaps = [b.y0 - a.y1 for a, b in zip(lines, lines[1:]) if a.page == b.page and b.y0 > a.y1]
    if not gaps:
        return
    usual = statistics.median(gaps)
    for a, b in zip(lines, lines[1:]):
        if a.page == b.page and b.y0 - a.y1 > max(2.0 * usual, 0.9 * b.size):
            b.blank_before = True


_DIGITS = re.compile(r"\d+")


def _norm(t: str) -> str:
    return _DIGITS.sub("#", re.sub(r"\s+", " ", t.strip().lower()))


_PAGE_NO = re.compile(r"^(page\s*#(\s*(of|/)\s*#)?|#\s*(of|/)\s*#|-\s*#\s*-|#)$")


def drop_repeats(pages: list[list[Line]], heights: list[float]) -> list[list[Line]]:
    """For one sheet holding the whole document: the page header, the repeated column
    headings and the page footer appear once, so a table running over pages stays one table.

    Left out, and nothing else:
    * on page 2 onwards, a line above the page's first amount or date that repeats a
      line from the top of page 1 word for word (the bank's name, the account line,
      the column headings), on pages that hold a table;
    * a line in the bottom tenth of a page, below its last amount, that is a page
      number ("Page 2 of 3", "2/3", "- 2 -") or recurs word for word on another page.
    """
    if len(pages) < 2:
        return pages

    def is_data(ln):
        return any(s.numeric or parse_date(s.text) for s in ln.cells.values())

    def first_data(ls):
        return next((k for k, ln in enumerate(ls) if is_data(ln)), len(ls))

    def last_data(ls):
        k = len(ls) - 1
        while k >= 0 and not is_data(ls[k]):
            k -= 1
        return k

    def exact(t: str) -> str:
        return re.sub(r"\s+", " ", t.strip())

    head1 = {exact(ln.text) for ln in pages[0][:first_data(pages[0])]}
    foot_counts: dict[str, int] = {}
    for ls, h in zip(pages, heights):
        lk = last_data(ls)
        for t in {exact(ln.text) for ln in ls[lk + 1:] if ln.y0 > 0.9 * h}:
            foot_counts[t] = foot_counts.get(t, 0) + 1
    out = []
    for pi, (ls, h) in enumerate(zip(pages, heights)):
        fd = first_data(ls)
        lk = last_data(ls)
        keep = []
        for k, ln in enumerate(ls):
            t = exact(ln.text)
            if pi > 0 and fd < len(ls) and k < fd and t in head1:
                continue
            if k > lk and ln.y0 > 0.9 * h and (foot_counts.get(t, 0) >= 2 or _PAGE_NO.match(_norm(t))):
                continue
            keep.append(ln)
        out.append(keep)
    return out


_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def clean_text(text: str) -> str:
    """Text a worksheet can hold: control characters removed (joiners used by
    Hindi and Gujarati are not control characters and stay), at most 32,767 characters."""
    return _CONTROL.sub("", text)[:32767]


def csv_safe(text: str) -> str:
    """A CSV value Excel would run as a formula ("=...", "@...", "+A1") gets the
    usual apostrophe in front; numbers such as -5.00 are left alone."""
    t = clean_text(text)
    if t[:1] in ("=", "@") or (t[:1] in ("+", "-") and parse_number(t) is None):
        return "'" + t
    return t


def cell_value(text: str, date_column: bool, convert: bool, date_order: str):
    """(value, number_format) for a cell; text stays text."""
    text = clean_text(text)
    if not convert:
        return text, "@"
    num = parse_number(text)
    if num is not None:
        value, fmt = num
        # Mirror the source: no thousands separator where the PDF printed none
        # (a year heading "2026" must not read "2,026").
        if "," not in text:
            fmt = fmt.replace("#,##0", "0")
        return value, fmt
    if date_column:
        dt = parse_date(text, date_order)
        if dt is not None:
            return dt
    return text, "@"
