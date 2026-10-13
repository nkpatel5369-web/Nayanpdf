"""Encode new text with a font that is already embedded in the PDF.

Most PDFs embed *subset* fonts that contain only the glyphs the document
uses, often without a usable Unicode cmap. To keep edited text in the
original typeface we read the font's ToUnicode map (or its simple encoding),
invert it, and write the edited string as the original character codes. If any
character has no glyph in the subset, the codec reports it so the caller can
fall back to a substitute font and tell the user.
"""

from __future__ import annotations

import re

import pymupdf

_HEX = re.compile(r"<([0-9A-Fa-f\s]*)>")


def _resolve(doc: pymupdf.Document, xref: int, key: str):
    """Return (type, value) for key, following one level of indirection."""
    t, v = doc.xref_get_key(xref, key)
    if t == "xref":
        n = int(v.split()[0])
        return "object", doc.xref_object(n, compressed=False), n
    return t, v, None


def _ref_num(s: str) -> int | None:
    m = re.match(r"\s*(\d+)\s+0\s+R", s)
    return int(m.group(1)) if m else None


def _tokens(s: str):
    """Tiny PDF array tokenizer: yields '[', ']', numbers."""
    for m in re.finditer(r"\[|\]|-?\d+(?:\.\d+)?|\d+\s+\d+\s+R", s):
        yield m.group(0)


def _parse_number_array(s: str) -> list[float]:
    return [float(t) for t in _tokens(s) if t not in "[]" and "R" not in t]


def _utf16_hex_to_str(h: str) -> str:
    h = re.sub(r"\s", "", h)
    if len(h) % 4:
        h = h.rjust((len(h) + 3) // 4 * 4, "0")
    try:
        return bytes.fromhex(h).decode("utf-16-be", errors="replace")
    except ValueError:
        return ""


def parse_tounicode(data: str) -> dict[int, str]:
    out: dict[int, str] = {}
    for sect in re.finditer(r"beginbfchar(.*?)endbfchar", data, re.S):
        hexes = _HEX.findall(sect.group(1))
        for i in range(0, len(hexes) - 1, 2):
            try:
                out[int(hexes[i] or "0", 16)] = _utf16_hex_to_str(hexes[i + 1])
            except ValueError:
                continue
    for sect in re.finditer(r"beginbfrange(.*?)endbfrange", data, re.S):
        body = sect.group(1)
        for m in re.finditer(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(<[0-9A-Fa-f\s]*>|\[[^\]]*\])", body):
            lo, hi = int(m.group(1), 16), int(m.group(2), 16)
            dst = m.group(3)
            if hi - lo > 65535:
                continue
            if dst.startswith("["):
                items = _HEX.findall(dst)
                for k, h in enumerate(items):
                    if lo + k <= hi:
                        out[lo + k] = _utf16_hex_to_str(h)
            else:
                base = _utf16_hex_to_str(dst[1:-1])
                if not base:
                    continue
                prefix, last = base[:-1], ord(base[-1])
                for k in range(hi - lo + 1):
                    try:
                        out[lo + k] = prefix + chr(last + k)
                    except ValueError:
                        break
    return out


def _simple_base_table(name: str) -> dict[int, str]:
    table: dict[int, str] = {}
    codec = {"WinAnsiEncoding": "cp1252", "MacRomanEncoding": "mac_roman"}.get(name, "latin-1")
    for code in range(32, 256):
        try:
            table[code] = bytes([code]).decode(codec)
        except UnicodeDecodeError:
            continue
    if name == "StandardEncoding":
        table[0x27] = "’"
        table[0x60] = "‘"
    return table


_BASE14 = {
    "Helvetica": "helv", "Helvetica-Bold": "hebo", "Helvetica-Oblique": "heit", "Helvetica-BoldOblique": "hebi",
    "Arial": "helv", "Arial-Bold": "hebo", "Arial-Italic": "heit", "Arial-BoldItalic": "hebi", "ArialMT": "helv",
    "Times-Roman": "tiro", "Times-Bold": "tibo", "Times-Italic": "tiit", "Times-BoldItalic": "tibi",
    "TimesNewRoman": "tiro", "TimesNewRoman-Bold": "tibo",
    "Courier": "cour", "Courier-Bold": "cobo", "Courier-Oblique": "coit", "Courier-BoldOblique": "cobi",
    "Symbol": "symb", "ZapfDingbats": "zadb",
}


class FontCodec:
    """Maps Unicode text to the character codes of an existing PDF font."""

    def __init__(self, doc: pymupdf.Document, xref: int):
        self.doc = doc
        self.xref = xref
        self.ok = False
        self.reason = ""
        self.two_byte = False
        self.code_to_uni: dict[int, str] = {}
        self.uni_to_code: dict[str, int] = {}
        self.widths: dict[int, float] = {}
        self.default_width = 0.0
        self.embedded = False
        self.base14 = False
        try:
            self._load()
        except Exception as e:  # noqa: BLE001
            self.ok = False
            self.reason = f"font could not be analysed ({type(e).__name__})"

    # ------------------------------------------------------------------
    def _load(self) -> None:
        doc, xref = self.doc, self.xref
        st, subtype = doc.xref_get_key(xref, "Subtype")
        subtype = subtype.lstrip("/") if st == "name" else ""
        if subtype == "Type3":
            self.reason = "Type 3 fonts cannot be re-used"
            return
        base = doc.xref_get_key(xref, "BaseFont")[1].lstrip("/")
        self.basefont = base
        tu_t, tu_v = doc.xref_get_key(xref, "ToUnicode")
        if tu_t == "xref":
            tu_x = int(tu_v.split()[0])
            try:
                data = doc.xref_stream(tu_x).decode("latin-1")
                self.code_to_uni = parse_tounicode(data)
            except Exception:  # noqa: BLE001
                self.code_to_uni = {}

        if subtype == "Type0":
            enc_t, enc_v = doc.xref_get_key(xref, "Encoding")
            if enc_t != "name" or enc_v not in ("/Identity-H",):
                self.reason = "the font uses an unsupported CMap"
                return
            self.two_byte = True
            df_t, df_v = doc.xref_get_key(xref, "DescendantFonts")
            desc = None
            if df_t == "array":
                desc = _ref_num(df_v.strip("[] "))
            elif df_t == "xref":
                arr = doc.xref_object(int(df_v.split()[0]))
                desc = _ref_num(arr.strip("[] "))
            if desc is None:
                self.reason = "missing descendant font"
                return
            dw_t, dw_v = doc.xref_get_key(desc, "DW")
            self.default_width = float(dw_v) if dw_t == "int" or dw_t == "real" else 1000.0
            w_t, w_v = doc.xref_get_key(desc, "W")
            if w_t == "xref":
                w_v = doc.xref_object(int(w_v.split()[0]))
            if w_t in ("array", "xref"):
                self._parse_cid_widths(w_v)
            fd = doc.xref_get_key(desc, "FontDescriptor")
            self.embedded = self._descriptor_embedded(fd)
            if not self.code_to_uni:
                self.reason = "the embedded font has no Unicode map"
                return
        else:
            fc_t, fc_v = doc.xref_get_key(xref, "FirstChar")
            first = int(float(fc_v)) if fc_t in ("int", "real") else 0
            w_t, w_v = doc.xref_get_key(xref, "Widths")
            if w_t == "xref":
                w_v = doc.xref_object(int(w_v.split()[0]))
            if w_t in ("array", "xref"):
                for k, w in enumerate(_parse_number_array(w_v)):
                    self.widths[first + k] = w
            fd = doc.xref_get_key(xref, "FontDescriptor")
            self.embedded = self._descriptor_embedded(fd)
            self.base14 = fd[0] == "null" and not self.embedded
            if not self.code_to_uni:
                enc_t, enc_v = doc.xref_get_key(xref, "Encoding")
                base_name = "StandardEncoding"
                diffs = ""
                if enc_t == "name":
                    base_name = enc_v.lstrip("/")
                elif enc_t in ("dict", "xref"):
                    obj = enc_v if enc_t == "dict" else doc.xref_object(int(enc_v.split()[0]))
                    m = re.search(r"/BaseEncoding\s*/(\w+)", obj)
                    if m:
                        base_name = m.group(1)
                    m = re.search(r"/Differences\s*\[(.*?)\]", obj, re.S)
                    if m:
                        diffs = m.group(1)
                elif self.base14:
                    base_name = "WinAnsiEncoding"
                table = _simple_base_table(base_name)
                if diffs:
                    code = 0
                    for tok in re.findall(r"\d+|/[^\s/\[\]]+", diffs):
                        if tok[0] == "/":
                            uni = pymupdf.glyph_name_to_unicode(tok[1:])
                            if uni and uni != 0xFFFD:
                                table[code] = chr(uni)
                            code += 1
                        else:
                            code = int(tok)
                if self.embedded:
                    # Only codes that have a width are likely present in the subset.
                    table = {c: u for c, u in table.items() if self.widths.get(c, 0) > 0 or c not in self.widths and not self.widths}
                self.code_to_uni = table
        for code, uni in sorted(self.code_to_uni.items()):
            if len(uni) == 1 and uni not in self.uni_to_code:
                self.uni_to_code[uni] = code
        # Standard (non-embedded) fonts usually carry no /Widths: use built-in metrics.
        self._metrics = None
        if not self.widths and not self.two_byte:
            key = _BASE14.get(getattr(self, "basefont", "").split("+", 1)[-1].replace(",", "-"))
            if key:
                try:
                    self._metrics = pymupdf.Font(key)
                except Exception:  # noqa: BLE001
                    self._metrics = None
        self.ok = bool(self.uni_to_code)
        if not self.ok and not self.reason:
            self.reason = "no usable character map"

    def _descriptor_embedded(self, fd) -> bool:
        t, v = fd
        if t != "xref":
            return False
        n = int(v.split()[0])
        for key in ("FontFile", "FontFile2", "FontFile3"):
            if self.doc.xref_get_key(n, key)[0] != "null":
                return True
        return False

    def _parse_cid_widths(self, s: str) -> None:
        toks = list(_tokens(s))
        # Drop outer brackets.
        if toks and toks[0] == "[":
            toks = toks[1:]
        if toks and toks[-1] == "]":
            toks = toks[:-1]
        i = 0
        while i < len(toks):
            if toks[i] in "[]":
                i += 1
                continue
            c = int(float(toks[i]))
            if i + 1 < len(toks) and toks[i + 1] == "[":
                j = i + 2
                k = 0
                while j < len(toks) and toks[j] != "]":
                    self.widths[c + k] = float(toks[j])
                    k += 1
                    j += 1
                i = j + 1
            elif i + 2 < len(toks):
                c2 = int(float(toks[i + 1]))
                w = float(toks[i + 2])
                if c2 - c < 65536:
                    for cc in range(c, c2 + 1):
                        self.widths[cc] = w
                i += 3
            else:
                break

    # ------------------------------------------------------------------
    def missing_chars(self, text: str) -> list[str]:
        miss = []
        for ch in text:
            if ch not in self.uni_to_code and ch not in miss:
                miss.append(ch)
        return miss

    def can_encode(self, text: str) -> bool:
        return self.ok and not self.missing_chars(text)

    def encode_hex(self, text: str) -> str:
        width = 4 if self.two_byte else 2
        return "".join(f"{self.uni_to_code[ch]:0{width}X}" for ch in text)

    def char_width(self, ch: str) -> float:
        if self._metrics is not None:
            return self._metrics.glyph_advance(ord(ch)) * 1000.0
        code = self.uni_to_code.get(ch)
        if code is None:
            return self.default_width
        return self.widths.get(code, self.default_width)

    def text_width(self, text: str, size: float) -> float:
        return sum(self.char_width(ch) for ch in text) / 1000.0 * size


def find_font_xref(page: pymupdf.Page, span_font: str, sample_text: str = "") -> int | None:
    """Find the font object used by a text span (spans only carry the font name)."""
    cands = []
    for f in page.get_fonts(full=True):
        xref, ext, ftype, basefont = f[0], f[1], f[2], f[3]
        name = basefont.split("+", 1)[-1] if "+" in basefont[:8] else basefont
        if name == span_font or basefont == span_font or name.replace(" ", "") == span_font.replace(" ", ""):
            cands.append(xref)
    if not cands:
        return None
    if len(cands) == 1 or not sample_text:
        return cands[0]
    doc = page.parent
    for x in cands:
        c = FontCodec(doc, x)
        if c.ok and c.can_encode(sample_text.replace(" ", "")):
            return x
    return cands[0]
