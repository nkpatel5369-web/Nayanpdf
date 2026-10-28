"""Interactive form (AcroForm) inspection, filling and flattening."""

from __future__ import annotations

import pymupdf

from ..errors import EngineError, VALIDATION_FAILED, invalid
from ..registry import method
from ..util.geometry import fitz_to_pdf_rect
from ..util.io import OutputFile, validate_pdf
from ..util.pdf import doc_is_signed, drop_keys, open_pdf, save_doc

TYPES = {
    pymupdf.PDF_WIDGET_TYPE_TEXT: "text",
    pymupdf.PDF_WIDGET_TYPE_CHECKBOX: "checkbox",
    pymupdf.PDF_WIDGET_TYPE_RADIOBUTTON: "radio",
    pymupdf.PDF_WIDGET_TYPE_COMBOBOX: "combobox",
    pymupdf.PDF_WIDGET_TYPE_LISTBOX: "listbox",
    pymupdf.PDF_WIDGET_TYPE_SIGNATURE: "signature",
    pymupdf.PDF_WIDGET_TYPE_BUTTON: "button",
}


def _on_state(w) -> str:
    try:
        s = w.on_state()
        return s if isinstance(s, str) else "Yes"
    except Exception:  # noqa: BLE001
        return "Yes"


@method("forms.list")
def list_fields(ctx, path: str, password: str | None = None):
    doc = open_pdf(path, password)
    try:
        fields = {}
        order = []
        for page in doc:
            for w in page.widgets():
                t = TYPES.get(w.field_type, "unknown")
                name = w.field_name or f"(unnamed {w.xref})"
                entry = fields.get(name)
                if entry is None:
                    entry = {
                        "name": name, "label": w.field_label or "", "type": t, "value": None,
                        "options": [], "widgets": [], "readOnly": bool(w.field_flags & 1),
                        "required": bool(w.field_flags & 2), "multiline": bool(w.field_flags & 4096) if t == "text" else False,
                        "maxLength": getattr(w, "text_maxlen", 0) or 0,
                    }
                    fields[name] = entry
                    order.append(name)
                entry["widgets"].append({"page": page.number + 1, "rect": fitz_to_pdf_rect(page, w.rect), "xref": w.xref,
                                         "onState": _on_state(w) if t in ("checkbox", "radio") else None})
                if t == "checkbox":
                    entry["value"] = bool(w.field_value) and w.field_value not in ("Off", False)
                elif t == "radio":
                    if w.field_value and w.field_value not in ("Off", False):
                        entry["value"] = _on_state(w) if w.field_value is True else w.field_value
                    if _on_state(w) not in entry["options"]:
                        entry["options"].append(_on_state(w))
                elif t in ("combobox", "listbox"):
                    entry["value"] = w.field_value
                    opts = w.choice_values or []
                    entry["options"] = [o[1] if isinstance(o, (list, tuple)) else o for o in opts]
                elif t == "signature":
                    entry["value"] = "signed" if w.field_value else ""
                else:
                    entry["value"] = w.field_value or ""
        return {"fields": [fields[n] for n in order], "count": len(order), "hasXfa": _has_xfa(doc)}
    finally:
        doc.close()


def _has_xfa(doc) -> bool:
    try:
        cat = doc.pdf_catalog()
        t, v = doc.xref_get_key(cat, "AcroForm")
        if t == "xref":
            return doc.xref_get_key(int(v.split()[0]), "XFA")[0] != "null"
        if t == "dict":
            return "/XFA" in v
    except Exception:  # noqa: BLE001
        return False
    return False


def _pdf_name(s: str) -> str:
    return "/" + "".join(chr(b) if 33 <= b <= 126 and chr(b) not in "()<>[]{}/%#" else f"#{b:02X}" for b in s.encode("utf-8"))


def _set_radio(ctx, doc, name: str, items, value: str) -> int:
    """Switch one button of a radio group on (or all off) and set the group's value."""
    widgets = []
    for page, xref in items:
        w = next((x for x in page.widgets() if x.xref == xref), None)
        if w is not None:
            widgets.append(w)
    target = [w for w in widgets if _on_state(w) == value]
    if value != "Off" and not target:
        ctx.warn(f"'{value}' is not one of the choices of '{name}'; no button was selected.")
    # PyMuPDF writes /Ff on each button it updates; on a button that inherits its
    # flags from the group that would override them (a required group would stop
    # being required), so the key is removed again where it was not there before.
    own_flags = {w.xref: doc.xref_get_key(w.xref, "Ff")[0] != "null" for w in widgets}
    for w in widgets:
        if w not in target:
            w.field_value = False
            w.update()
    for w in target:
        w.field_value = True
        w.update()
    for w in widgets:
        if not own_flags[w.xref] and doc.xref_get_key(w.xref, "Ff")[0] != "null":
            drop_keys(doc, w.xref, ("Ff",))
    # The group's value is a name (/Composition), not a string.
    v = _pdf_name(value if target else "Off")
    for w in widgets:
        t, p = doc.xref_get_key(w.xref, "Parent")
        holder = int(p.split()[0]) if t == "xref" else w.xref
        doc.xref_set_key(holder, "V", v)
    return 1 if widgets else 0


@method("forms.fill")
def fill(ctx, path: str, output: str, values: dict, flatten: bool = False, password: str | None = None,
         overwrite: bool = False):
    if not isinstance(values, dict):
        raise invalid("values must be an object of field name to value.")
    doc = open_pdf(path, password, require=["form"])
    if _has_xfa(doc):
        ctx.warn("This form contains XFA data. NayanPDF fills the standard AcroForm fields; XFA-only content may not update.")
    if doc_is_signed(doc):
        ctx.warn("This document is digitally signed. Filling fields after signing may be reported as a change by signature validators.")
    changed = 0
    unknown = set(values)
    expected = {}
    radios: dict[str, list] = {}
    for page in doc:
        for w in page.widgets():
            name = w.field_name
            if name not in values:
                continue
            unknown.discard(name)
            if w.field_flags & 1:
                ctx.warn(f"Field '{name}' is read-only and was not changed.")
                continue
            v = values[name]
            t = w.field_type
            if t == pymupdf.PDF_WIDGET_TYPE_CHECKBOX:
                w.field_value = _on_state(w) if v in (True, "true", "on", "Yes", 1, "1") else "Off"
                expected[name] = bool(v in (True, "true", "on", "Yes", 1, "1"))
            elif t == pymupdf.PDF_WIDGET_TYPE_RADIOBUTTON:
                # Handled per group below: PyMuPDF switches a button on for any
                # non-empty value, including "Off".
                radios.setdefault(name, []).append((page, w.xref))
                expected[name] = "Off" if v in (None, False, "", "Off") else str(v)
                continue
            elif t in (pymupdf.PDF_WIDGET_TYPE_COMBOBOX, pymupdf.PDF_WIDGET_TYPE_LISTBOX):
                w.field_value = "" if v is None else str(v)
                expected[name] = "" if v is None else str(v)
            elif t == pymupdf.PDF_WIDGET_TYPE_TEXT:
                w.field_value = "" if v is None else str(v)
                expected[name] = "" if v is None else str(v)
            else:
                continue
            w.update()
            changed += 1
    for name, items in radios.items():
        changed += _set_radio(ctx, doc, name, items, expected[name])
    if unknown:
        ctx.warn("Unknown field(s) ignored: " + ", ".join(sorted(unknown)[:10]))
    n = doc.page_count
    if flatten:
        doc.bake(annots=False, widgets=True)
    out = OutputFile(ctx, output, overwrite, sources=[path])
    save_doc(doc, out.temp, garbage=1)
    doc.close()
    # Read back.
    if not flatten:
        chk = pymupdf.open(out.temp)
        if chk.needs_pass:
            chk.authenticate(password or "")
        mismatches = []
        for page in chk:
            for w in page.widgets():
                if w.field_name in expected:
                    e = expected[w.field_name]
                    if w.field_type == pymupdf.PDF_WIDGET_TYPE_CHECKBOX:
                        got = bool(w.field_value) and w.field_value not in ("Off", False)
                        if got != e:
                            mismatches.append(w.field_name)
                    elif w.field_type == pymupdf.PDF_WIDGET_TYPE_RADIOBUTTON:
                        state = chk.xref_get_key(w.xref, "AS")[1].lstrip("/") or "Off"
                        if (state == _on_state(w)) != (_on_state(w) == e) and w.field_name not in mismatches:
                            mismatches.append(w.field_name)
                    elif (w.field_value or "") != e:
                        mismatches.append(w.field_name)
        chk.close()
        if mismatches:
            raise EngineError(VALIDATION_FAILED, "Some fields did not keep their new values: " + ", ".join(mismatches[:10]))
    final = out.commit(validate_pdf(expected_pages=n, password=password))
    return {"output": final, "changed": changed, "flattened": bool(flatten)}
