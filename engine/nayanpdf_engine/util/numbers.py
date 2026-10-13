"""Conservative conversion of extracted cell text into numbers and dates.

Rules (financial-document safe):
  * a value is converted only if it matches a strict numeric/date pattern and
    the converted value reproduces exactly the same digits;
  * identifiers stay text: leading zeros, > 15 significant digits, mixed
    letters/digits, spaced digit groups, fiscal years like 2025-26;
  * signs are never inferred: "(1,234.00)" and "1,234.00-" are negative
    because they are written as negative; "Dr"/"Cr" markers are preserved in
    the cell's display format, not turned into signs;
  * Indian (1,23,456.78) and Western (123,456.78) digit grouping both accepted.
"""

from __future__ import annotations

import datetime as _dt
import re
from decimal import Decimal, InvalidOperation

_CUR = r"(?:₹|Rs\.?|INR|\$|USD|€|EUR|£|GBP)"
_GROUPED = r"\d{1,3}(?:,\d{3})+|\d{1,2}(?:,\d{2})*,\d{3}|\d+"
NUM_RE = re.compile(
    rf"^(?P<pre>[-+])?\s*(?P<open>\()?\s*(?P<cur>{_CUR})?\s*(?P<sign2>[-+])?\s*"
    rf"(?P<int>{_GROUPED})(?P<dec>\.\d+)?\s*(?P<close>\))?\s*(?P<post>-)?\s*"
    rf"(?P<pct>%)?\s*(?P<drcr>Dr\.?|Cr\.?|DR\.?|CR\.?)?$"
)
MONTHS = {m: i + 1 for i, m in enumerate(["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"])}
DATE_NUM = re.compile(r"^(\d{1,4})([/.\-])(\d{1,2})\2(\d{2,4})$")
DATE_MON = re.compile(r"^(\d{1,2})[\s\-/.]?([A-Za-z]{3,9})[\s\-/.,]+(\d{2,4})$")


def _valid_grouping(s: str) -> bool:
    if "," not in s:
        return True
    parts = s.split(",")
    if len(parts[-1]) != 3:
        return False
    western = all(len(p) == 3 for p in parts[1:]) and 1 <= len(parts[0]) <= 3
    indian = len(parts[-1]) == 3 and all(len(p) == 2 for p in parts[1:-1]) and 1 <= len(parts[0]) <= 2
    return western or indian


def parse_number(text: str):
    """Return (value, number_format) or None if the text is not a plain number."""
    s = (text or "").strip().replace("−", "-").replace(" ", " ")
    if not s or len(s) > 40:
        return None
    m = NUM_RE.match(s)
    if not m:
        return None
    integer = m.group("int")
    if not _valid_grouping(integer):
        return None
    digits = integer.replace(",", "")
    if len(digits) > 1 and digits.startswith("0") and not m.group("dec"):
        return None  # leading zero: identifier
    dec = m.group("dec") or ""
    if len((digits + dec[1:]).lstrip("0")) > 15:
        return None  # beyond Excel precision: keep as text
    if bool(m.group("open")) != bool(m.group("close")):
        return None
    if integer.isdigit() and len(integer) >= 5 and not dec and not m.group("cur") and "," not in integer:
        # Long ungrouped integers in financial documents are often references (cheque no, PIN, etc.).
        if len(integer) >= 7:
            return None
    signs = [m.group("pre"), m.group("sign2"), "(" if m.group("open") else None, m.group("post")]
    neg_markers = sum(1 for x in signs if x in ("-", "("))
    if neg_markers > 1:
        return None
    negative = neg_markers == 1
    try:
        val = Decimal(digits + dec)
    except InvalidOperation:
        return None
    if negative:
        val = -val
    pct = bool(m.group("pct"))
    places = len(dec) - 1 if dec else 0
    fmt_num = "#,##0" + ("." + "0" * places if places else "")
    if pct:
        fmt = ("0" + ("." + "0" * places if places else "")) + "%"
        value = float(val) / 100.0
        return value, fmt
    cur = m.group("cur")
    prefix = ""
    if cur:
        sym = {"Rs": "Rs.", "Rs.": "Rs.", "INR": "INR ", "USD": "USD ", "EUR": "EUR ", "GBP": "GBP "}.get(cur, cur)
        prefix = f'"{sym}"' if sym else ""
    suffix = ""
    if m.group("drcr"):
        suffix = f' "{m.group("drcr").rstrip(".").capitalize()}"'
    if m.group("open"):
        fmt = f"{prefix}{fmt_num}{suffix};{prefix}({fmt_num}){suffix}"
    else:
        fmt = f"{prefix}{fmt_num}{suffix};-{prefix}{fmt_num}{suffix}"
    value = int(val) if places == 0 else float(val)
    # Round-trip check: the stored value must reproduce the same digits.
    check = f"{abs(Decimal(str(value))):.{places}f}".replace(".", "")
    if check.lstrip("0") != (digits + dec[1:]).lstrip("0"):
        return None
    return value, fmt


def parse_date(text: str, order: str = "DMY"):
    """Return (datetime.date, number_format) or None."""
    s = (text or "").strip()
    if not s or len(s) > 20:
        return None
    m = DATE_NUM.match(s)
    if m:
        a, sep, b, c = m.group(1), m.group(2), m.group(3), m.group(4)
        try:
            if len(a) == 4:
                y, mo, d = int(a), int(b), int(c)
                fmt = f"yyyy{sep}mm{sep}dd"
            else:
                if len(c) not in (2, 4):
                    return None
                y = int(c)
                if len(c) == 2:
                    y += 2000 if y < 70 else 1900
                if order == "MDY":
                    mo, d = int(a), int(b)
                    fmt = f"mm{sep}dd{sep}" + ("yyyy" if len(c) == 4 else "yy")
                else:
                    d, mo = int(a), int(b)
                    fmt = f"dd{sep}mm{sep}" + ("yyyy" if len(c) == 4 else "yy")
            return _dt.date(y, mo, d), fmt
        except ValueError:
            return None
    m = DATE_MON.match(s)
    if m:
        mon = MONTHS.get(m.group(2)[:3].lower())
        if not mon:
            return None
        y = int(m.group(3))
        if len(m.group(3)) == 2:
            y += 2000 if y < 70 else 1900
        try:
            return _dt.date(y, mon, int(m.group(1))), "dd-mmm-yyyy"
        except ValueError:
            return None
    return None


def digits_of(text: str) -> str:
    return re.sub(r"\D", "", text or "")


NUM_TOKEN = re.compile(r"(?<![\w.])\(?-?\d[\d,]*(?:\.\d+)?\)?-?(?![\w])")


def numeric_tokens(text: str) -> list[str]:
    """Number-like tokens used for coverage checks (digits only, length >= 2)."""
    out = []
    for t in NUM_TOKEN.findall(text or ""):
        d = digits_of(t)
        if len(d) >= 2:
            out.append(d)
    return out
