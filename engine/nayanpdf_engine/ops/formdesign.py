"""Prepare a form: create, change and delete AcroForm fields, and suggest fields.

Field types: text, check box, radio button group, drop-down (combo box), list
box and signature field. Radio buttons are built as one field with a widget per
button and a distinct export value each (PyMuPDF on its own makes every radio
button a separate field with the same "Yes" value, so they would switch on
together).

Field detection is a set of plain rules, not machine learning: underscore runs
("Name: ______"), drawn lines with a label in front, small square boxes (check
boxes) and empty drawn boxes beside a label. Every suggestion is shown to the
user to accept or discard; nothing is added without being accepted.
"""

from __future__ import annotations

import re

import pymupdf

from ..errors import invalid
from ..registry import method
from ..util.geometry import fitz_to_pdf_rect, to_fitz_rect
from ..util.io import OutputFile, validate_pdf
from ..util.pdf import drop_keys, open_pdf, save_doc

WTYPE = {
    "text": pymupdf.PDF_WIDGET_TYPE_TEXT, "checkbox": pymupdf.PDF_WIDGET_TYPE_CHECKBOX,
    "combobox": pymupdf.PDF_WIDGET_TYPE_COMBOBOX, "listbox": pymupdf.PDF_WIDGET_TYPE_LISTBOX,
    "signature": pymupdf.PDF_WIDGET_TYPE_SIGNATURE,
}
DEFAULT_NAMES = {"text": "Text", "checkbox": "Check Box", "radio": "Group", "combobox": "Dropdown",
                 "listbox": "List Box", "signature": "Signature"}
READ_ONLY, REQUIRED = 1, 2
MULTILINE = 4096
RADIO_FLAGS = 49152  # Radio + NoToggleToOff


def _safe_export(s: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]", "_", (s or "").strip()) or "Choice"


def _field_names(doc: pymupdf.Document) -> set[str]:
    out = set()
    for p in doc:
        for w in p.widgets():
            if w.field_name:
                out.add(w.field_name)
    return out


def _unique(base: str, used: set[str]) -> str:
    base = (base or "Field").strip()[:60] or "Field"
    if base not in used:
        return base
    i = 1
    while f"{base} {i}" in used:
        i += 1
    return f"{base} {i}"


def _widgets_named(doc: pymupdf.Document, name: str):
    for p in doc:
        for w in p.widgets():
            if w.field_name == name:
                yield p, w


def _parent_of(doc: pymupdf.Document, xref: int) -> int | None:
    t, v = doc.xref_get_key(xref, "Parent")
    if t == "xref":
        return int(v.split()[0])
    return None


def _fields_array(doc: pymupdf.Document) -> list[str]:
    t, v = doc.xref_get_key(doc.pdf_catalog(), "AcroForm/Fields")
    return re.findall(r"(\d+) 0 R", v) if t == "array" else []


def _set_fields_array(doc: pymupdf.Document, refs: list[str]) -> None:
    doc.xref_set_key(doc.pdf_catalog(), "AcroForm/Fields", "[" + " ".join(f"{r} 0 R" for r in refs) + "]")


def _apply_props(w: pymupdf.Widget, op: dict) -> None:
    flags = w.field_flags or 0
    for key, bit in (("readOnly", READ_ONLY), ("required", REQUIRED)):
        if key in op:
            flags = (flags | bit) if op[key] else (flags & ~bit)
    if w.field_type == pymupdf.PDF_WIDGET_TYPE_TEXT and "multiline" in op:
        flags = (flags | MULTILINE) if op["multiline"] else (flags & ~MULTILINE)
    w.field_flags = flags
    if op.get("tooltip") is not None:
        w.field_label = str(op["tooltip"])
    if op.get("fontSize") is not None:
        w.text_fontsize = float(op["fontSize"])
    if w.field_type == pymupdf.PDF_WIDGET_TYPE_TEXT and op.get("maxLength") is not None:
        w.text_maxlen = max(0, int(op["maxLength"]))
    if w.field_type in (pymupdf.PDF_WIDGET_TYPE_COMBOBOX, pymupdf.PDF_WIDGET_TYPE_LISTBOX) and op.get("options") is not None:
        opts = [str(o) for o in op["options"] if str(o).strip()]
        if not opts:
            raise invalid("A drop-down or list needs at least one choice.")
        w.choice_values = opts
        if w.field_value not in opts:
            w.field_value = opts[0] if w.field_type == pymupdf.PDF_WIDGET_TYPE_COMBOBOX else ""
    if op.get("value") is not None:
        v = op["value"]
        if w.field_type == pymupdf.PDF_WIDGET_TYPE_CHECKBOX:
            w.field_value = bool(v) and v not in ("Off", "false", 0)
        elif w.field_type in (pymupdf.PDF_WIDGET_TYPE_TEXT, pymupdf.PDF_WIDGET_TYPE_COMBOBOX, pymupdf.PDF_WIDGET_TYPE_LISTBOX):
            w.field_value = str(v)


def _add_simple(doc, page, op, used) -> list[tuple[int, str]]:
    t = op.get("type")
    rect = to_fitz_rect(page, op["rect"], op.get("space", "pdf"))
    if rect.width < 4 or rect.height < 4:
        raise invalid("Draw a larger box for the field.")
    name = (op.get("name") or "").strip()
    if not name:
        name = _unique_numbered(DEFAULT_NAMES[t], used)
    elif name in used:
        raise invalid(f'A field named "{name}" already exists. Fields with the same name share one value.')
    w = pymupdf.Widget()
    w.field_type = WTYPE[t]
    w.field_name = name
    w.rect = rect
    w.text_fontsize = float(op.get("fontSize", 0 if t == "text" else 10))  # 0 = auto size
    if t == "checkbox":
        w.field_value = False
        _visible_border(w, op)
    elif t in ("combobox", "listbox"):
        opts = [str(o) for o in (op.get("options") or ["Choice 1", "Choice 2"]) if str(o).strip()]
        w.choice_values = opts
        w.field_value = opts[0] if t == "combobox" else ""
    else:
        w.field_value = ""
    _apply_props(w, op)
    a = page.add_widget(w)
    used.add(name)
    return [(a.xref, name)]


def _visible_border(w: pymupdf.Widget, op: dict) -> None:
    """Check boxes and radio buttons get a thin black border (Acrobat's default),
    so they can be seen on screen and on paper before anything is ticked."""
    if op.get("border", True):
        w.border_color = (0, 0, 0)
        w.border_width = 1


def _unique_numbered(base: str, used: set[str]) -> str:
    i = 1
    while f"{base}{i}" in used:
        i += 1
    return f"{base}{i}"


def _add_radio_widget(doc, page, parent: int, rect: pymupdf.Rect, export: str) -> int:
    """Add one button to a radio group, keeping its own export value."""
    w = pymupdf.Widget()
    w.field_type = pymupdf.PDF_WIDGET_TYPE_RADIOBUTTON
    w.field_name = f"nayan-tmp-{parent}-{export}"
    w.rect = rect
    w.field_value = False
    _visible_border(w, {})
    a = page.add_widget(w)
    x = a.xref
    # The button inherits name, type, flags and value from the group. Setting a
    # key to null with xref_set_key leaves "/FT null" in the file, which PDF.js
    # cannot read, so the keys are removed from the object itself.
    drop_keys(doc, x, ("T", "FT", "Ff", "V", "TU"))
    doc.xref_set_key(x, "Parent", f"{parent} 0 R")
    for st in ("N", "D"):
        t, v = doc.xref_get_key(x, f"AP/{st}")
        if t == "dict":
            off = re.search(r"/Off\s+(\d+ 0 R)", v)
            on = re.search(r"/Yes\s+(\d+ 0 R)", v)
            if off and on:
                doc.xref_set_key(x, f"AP/{st}", f"<</Off {off.group(1)}/{export} {on.group(1)}>>")
    doc.xref_set_key(x, "AS", "/Off")
    # The widget belongs to the group, not to the form's top-level field list.
    _set_fields_array(doc, [r for r in _fields_array(doc) if r != str(x)])
    t, v = doc.xref_get_key(parent, "Kids")
    kids = re.findall(r"(\d+) 0 R", v) if t == "array" else []
    doc.xref_set_key(parent, "Kids", "[" + " ".join(f"{k} 0 R" for k in kids + [str(x)]) + "]")
    return x


def _radio_parent(doc, name: str) -> int | None:
    for _, w in _widgets_named(doc, name):
        if w.field_type == pymupdf.PDF_WIDGET_TYPE_RADIOBUTTON:
            return _parent_of(doc, w.xref)
    return None


def _add_radio(doc, page, op, used) -> list[tuple[int, str]]:
    buttons = op.get("buttons") or ([{"rect": op["rect"], "export": op.get("export")}] if op.get("rect") else [])
    if not buttons:
        raise invalid("A radio group needs at least one button.")
    name = op.get("name")
    parent = _radio_parent(doc, name) if name else None
    if name and parent is None and name in used:
        raise invalid(f'A field named "{name}" already exists.')
    if parent is None:
        name = name or _unique_numbered(DEFAULT_NAMES["radio"], used)
        parent = doc.get_new_xref()
        doc.update_object(parent, f"<< /FT /Btn /Ff {RADIO_FLAGS} /T {pymupdf.get_pdf_str(name)} /V /Off /Kids [] >>")
        _set_fields_array(doc, _fields_array(doc) + [str(parent)])
        used.add(name)
    existing = {w.on_state() for _, w in _widgets_named(doc, name) if w.field_type == pymupdf.PDF_WIDGET_TYPE_RADIOBUTTON}
    out = []
    for b in buttons:
        rect = to_fitz_rect(page, b["rect"], op.get("space", "pdf"))
        if rect.width < 4 or rect.height < 4:
            raise invalid("Draw a larger box for the radio button.")
        export = _safe_export(b.get("export") or "")
        if not b.get("export"):
            i = 1
            while f"Choice{i}" in existing:
                i += 1
            export = f"Choice{i}"
        if export in existing:
            raise invalid(f'The group already has a button with the value "{export}".')
        existing.add(export)
        out.append((_add_radio_widget(doc, page, parent, rect, export), name))
    _radio_props(doc, parent, op)
    return out


def _radio_props(doc, parent: int, op: dict) -> None:
    t, v = doc.xref_get_key(parent, "Ff")
    flags = int(v) if t == "int" else RADIO_FLAGS
    for key, bit in (("readOnly", READ_ONLY), ("required", REQUIRED)):
        if key in op:
            flags = (flags | bit) if op[key] else (flags & ~bit)
    doc.xref_set_key(parent, "Ff", str(flags))
    if op.get("tooltip") is not None:
        doc.xref_set_key(parent, "TU", pymupdf.get_pdf_str(str(op["tooltip"])))


def _delete_field(doc, name: str) -> int:
    n = 0
    parents = set()
    for page in doc:
        for w in list(page.widgets()):
            if w.field_name == name:
                p = _parent_of(doc, w.xref)
                if p:
                    parents.add(p)
                page.delete_widget(w)
                n += 1
    if parents:
        _set_fields_array(doc, [r for r in _fields_array(doc) if int(r) not in parents])
    return n


def _delete_widget(doc, page, xref: int) -> None:
    w = next((x for x in page.widgets() if x.xref == xref), None)
    if w is None:
        raise invalid("The field no longer exists.")
    parent = _parent_of(doc, xref)
    page.delete_widget(w)
    if parent:
        t, v = doc.xref_get_key(parent, "Kids")
        kids = [k for k in (re.findall(r"(\d+) 0 R", v) if t == "array" else []) if k != str(xref)]
        if kids:
            doc.xref_set_key(parent, "Kids", "[" + " ".join(f"{k} 0 R" for k in kids) + "]")
        else:
            _set_fields_array(doc, [r for r in _fields_array(doc) if r != str(parent)])


@method("forms.design")
def design(ctx, path: str, output: str, ops: list, password: str | None = None, overwrite: bool = False):
    """add:    {"op": "add", "type": "text", "page": 1, "rect": [...], "name"?, "tooltip"?, "required"?, ...}
               {"op": "add", "type": "radio", "page": 1, "name"?, "buttons": [{"rect": [...], "export": "Yes"}]}
    update: {"op": "update", "name": "Text1", "newName"?, "tooltip"?, "required"?, "readOnly"?, "multiline"?,
             "maxLength"?, "fontSize"?, "options"?, "value"?}
    move:   {"op": "move", "page": 1, "xref": 12, "rect": [...]}
    delete: {"op": "delete", "name": "Text1"}            (every widget of the field)
    deleteWidget: {"op": "deleteWidget", "page": 1, "xref": 12}   (one widget, e.g. one radio button)
    """
    if not ops:
        raise invalid("No form changes to apply.")
    doc = open_pdf(path, password, require=["modify"])
    created: list[dict] = []
    try:
        used = _field_names(doc)
        for op in ops:
            kind = op.get("op", "add")
            if kind == "add":
                pno = int(op.get("page", 0))
                if pno < 1 or pno > doc.page_count:
                    raise invalid("Page out of range.")
                page = doc[pno - 1]
                t = op.get("type")
                if t == "radio":
                    xs = _add_radio(doc, page, op, used)
                elif t in WTYPE:
                    xs = _add_simple(doc, page, op, used)
                else:
                    raise invalid(f"Unknown field type: {t}")
                created += [{"page": pno, "xref": x, "name": nm} for x, nm in xs]
            elif kind == "update":
                name = op.get("name")
                ws = list(_widgets_named(doc, name))
                if not ws:
                    raise invalid(f'There is no field named "{name}".')
                new = op.get("newName")
                if new and new != name and new in used:
                    raise invalid(f'A field named "{new}" already exists.')
                radio_parent = _radio_parent(doc, name)
                if radio_parent:
                    _radio_props(doc, radio_parent, op)
                    if new and new != name:
                        doc.xref_set_key(radio_parent, "T", pymupdf.get_pdf_str(new))
                else:
                    for page, w in ws:
                        _apply_props(w, op)
                        if new and new != name:
                            w.field_name = new
                        w.update()
                if new and new != name:
                    used.discard(name)
                    used.add(new)
            elif kind == "move":
                pno = int(op.get("page", 0))
                page = doc[pno - 1]
                target = next((w for w in page.widgets() if w.xref == int(op["xref"])), None)
                if target is None:
                    raise invalid("The field no longer exists.")
                rect = to_fitz_rect(page, op["rect"], op.get("space", "pdf"))
                if rect.width < 4 or rect.height < 4:
                    raise invalid("The field is too small.")
                if target.field_type == pymupdf.PDF_WIDGET_TYPE_RADIOBUTTON:
                    # Keep the button's own export value: move it without regenerating it.
                    r = fitz_to_pdf_rect(page, rect)
                    doc.xref_set_key(target.xref, "Rect", f"[{r[0]} {r[1]} {r[2]} {r[3]}]")
                else:
                    target.rect = rect
                    target.update()
            elif kind == "deleteWidget":
                pno = int(op.get("page", 0))
                if pno < 1 or pno > doc.page_count:
                    raise invalid("Page out of range.")
                _delete_widget(doc, doc[pno - 1], int(op["xref"]))
                used = _field_names(doc)
            elif kind == "delete":
                if not _delete_field(doc, op.get("name")):
                    raise invalid(f'There is no field named "{op.get("name")}".')
                used.discard(op.get("name"))
            else:
                raise invalid(f"Unknown form operation: {kind}")
        n = doc.page_count
        out = OutputFile(ctx, output, overwrite, sources=[path])
        save_doc(doc, out.temp, garbage=1)
    finally:
        doc.close()
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final, "created": created}


# ----------------------------------------------------------------- detection
LABEL_GAP = 160  # how far left of a blank a label may sit, in points


def _label_left(words, y0, y1, x, max_gap=LABEL_GAP) -> str:
    """Words on the same line ending just before x."""
    line = [w for w in words if w[2] <= x + 1 and x - w[2] <= max_gap and min(w[3], y1) - max(w[1], y0) > 0.4 * (y1 - y0)]
    line.sort(key=lambda w: w[0])
    if not line:
        return ""
    # Take the run of words closest to the blank (stop at a large gap).
    run = [line[-1]]
    for w in reversed(line[:-1]):
        if run[0][0] - w[2] > 12:
            break
        run.insert(0, w)
    return " ".join(w[4] for w in run)


def _clean_label(s: str) -> str:
    s = re.sub(r"[_\.…]{2,}", " ", s)
    s = s.strip().rstrip(":").strip()
    s = re.sub(r"\s+", " ", s)
    return s[:40]


def _overlaps(r: pymupdf.Rect, others: list[pymupdf.Rect], thr=0.3) -> bool:
    for o in others:
        inter = r & o
        if inter.is_empty:
            continue
        if inter.get_area() > thr * min(r.get_area(), o.get_area()):
            return True
    return False


@method("forms.detect")
def detect(ctx, path: str, password: str | None = None, pages: list | None = None):
    doc = open_pdf(path, password)
    try:
        used = _field_names(doc)
        names = set(used)
        out = []
        for page in doc:
            if pages and page.number + 1 not in pages:
                continue
            words = page.get_text("words")
            taken = [w.rect for w in page.widgets()]
            found: list[tuple[str, pymupdf.Rect, str, str]] = []
            # 1. Underscore runs: "Name: ________"
            for w in words:
                m = re.search(r"_{4,}", w[4])
                if not m:
                    continue
                wr = pymupdf.Rect(w[:4])
                frac0 = m.start() / max(1, len(w[4]))
                frac1 = m.end() / max(1, len(w[4]))
                x0 = wr.x0 + wr.width * frac0
                x1 = wr.x0 + wr.width * frac1
                h = max(12.0, wr.height)
                r = pymupdf.Rect(x0, wr.y1 - h, x1, wr.y1)
                before = w[4][: m.start()]
                label = before if before.strip() else _label_left(words, wr.y0, wr.y1, wr.x0)
                found.append(("text", r, label, "underline"))
            drawings = page.get_drawings()
            hlines, boxes = [], []
            for d in drawings:
                for it in d.get("items", []):
                    if it[0] == "l":
                        p1, p2 = it[1], it[2]
                        if abs(p1.y - p2.y) < 0.8 and abs(p2.x - p1.x) >= 40:
                            hlines.append((min(p1.x, p2.x), max(p1.x, p2.x), (p1.y + p2.y) / 2))
                    elif it[0] == "re":
                        r = pymupdf.Rect(it[1])
                        if r.height < 1.6 and r.width >= 40:
                            hlines.append((r.x0, r.x1, (r.y0 + r.y1) / 2))
                        else:
                            boxes.append(r)
            # 2. Lines with a label in front and nothing written on them.
            for x0, x1, y in hlines:
                above = pymupdf.Rect(x0 + 1, y - 14, x1 - 1, y - 0.5)
                if any(pymupdf.Rect(w[:4]).intersects(above) for w in words):
                    continue
                label = _label_left(words, y - 14, y, x0)
                if not label:
                    continue
                found.append(("text", pymupdf.Rect(x0, y - 16, x1, y - 0.5), label, "line"))
            # 3. Small squares: check boxes. 4. Empty boxes beside a label: text fields.
            for r in boxes:
                if 6 <= r.width <= 18 and abs(r.width - r.height) <= 2.5:
                    right = _label_right(words, r)
                    # Exactly on the printed square, so the field's border covers it.
                    found.append(("checkbox", pymupdf.Rect(r), right or _label_left(words, r.y0, r.y1, r.x0), "box"))
                elif 12 <= r.height <= 40 and r.width >= 60:
                    if any(pymupdf.Rect(w[:4]).intersects(r + (2, 2, -2, -2)) for w in words):
                        continue
                    label = _label_left(words, r.y0, r.y1, r.x0, 220)
                    if not label:
                        continue
                    found.append(("text", r + (1, 1, -1, -1), label, "box"))
            for w in words:
                if w[4] in ("☐", "□", "❏"):
                    r = pymupdf.Rect(w[:4])
                    found.append(("checkbox", r, _label_right(words, r), "glyph"))
            accepted: list[pymupdf.Rect] = []
            for t, r, label, source in found:
                if _overlaps(r, taken) or _overlaps(r, accepted):
                    continue
                accepted.append(r)
                clean = _clean_label(label)
                name = _unique(clean, names) if clean else _unique_numbered(DEFAULT_NAMES[t], names)
                names.add(name)
                out.append({"page": page.number + 1, "type": t, "rect": fitz_to_pdf_rect(page, r), "name": name,
                            "label": clean, "source": source})
        return {"suggestions": out}
    finally:
        doc.close()


def _label_right(words, r: pymupdf.Rect) -> str:
    line = [w for w in words if w[0] >= r.x1 - 1 and w[0] - r.x1 <= 40 + 400 and min(w[3], r.y1) - max(w[1], r.y0) > 0.3 * r.height]
    line.sort(key=lambda w: w[0])
    if not line or line[0][0] - r.x1 > 40:
        return ""
    run = [line[0]]
    for w in line[1:]:
        if w[0] - run[-1][2] > 12 or len(run) >= 6:
            break
        run.append(w)
    return " ".join(w[4] for w in run)
