import React, { useEffect, useRef, useState } from "react";
import { ChevronsUpDown, CircleDot, List, MousePointer2, Signature, SquareCheck, TextCursorInput, Trash2, WandSparkles } from "lucide-react";
import { useWs } from "../context";
import { Check, Field, Notice } from "../../components/ui";
import { useApp } from "../../store";

/** Field tools of Prepare a form (Acrobat: All tools > Prepare a form). */
export const FIELD_TOOLS = [
  { id: "fieldSelect", type: "", label: "Select", icon: MousePointer2, hint: "Click a field to change it. Drag to move, drag the corner to resize, Delete removes it." },
  { id: "textField", type: "text", label: "Text field", icon: TextCursorInput, hint: "Drag a box (or click) where the text goes." },
  { id: "checkField", type: "checkbox", label: "Check box", icon: SquareCheck, hint: "Click where the box goes." },
  { id: "radioField", type: "radio", label: "Radio button", icon: CircleDot, hint: "Click where the button goes, then name the group and this choice. Buttons in one group switch each other off." },
  { id: "comboField", type: "combobox", label: "Drop-down", icon: ChevronsUpDown, hint: "Drag a box, then list the choices in the field's properties." },
  { id: "listField", type: "listbox", label: "List box", icon: List, hint: "Drag a box, then list the choices in the field's properties." },
  { id: "sigField", type: "signature", label: "Signature field", icon: Signature, hint: "Drag a box where the signer signs (Sign > Digital signature fills it)." },
];
export const FIELD_TYPE_OF: Record<string, string> = Object.fromEntries(FIELD_TOOLS.filter((t) => t.type).map((t) => [t.id, t.type]));
const TYPE_LABEL: Record<string, string> = { text: "Text", checkbox: "Check box", radio: "Radio group", combobox: "Drop-down", listbox: "List box", signature: "Signature", button: "Button" };

export function PreparePanel() {
  const ws = useWs();
  const [fields, setFields] = useState<any[] | null>(null);
  const [detecting, setDetecting] = useState(false);
  const autoDetected = useRef(false);
  const suggestions: any[] = ws.opts.suggestions || [];

  useEffect(() => {
    let alive = true;
    ws.query("forms.list", {}).then((d: any) => {
      if (!alive) return;
      setFields(d.fields);
      ws.setOpts({ designFields: d.fields });
      // A document without fields: look for blanks once, as Acrobat does on entry.
      if (!d.fields.length && !autoDetected.current) {
        autoDetected.current = true;
        detect(true);
      }
    }).catch(() => alive && setFields([]));
    return () => {
      alive = false;
    };
  }, [ws.revision]);

  const detect = async (auto = false) => {
    setDetecting(true);
    try {
      const r: any = await ws.query("forms.detect", {});
      ws.setOpts({ suggestions: r.suggestions });
      if (!auto && !r.suggestions.length) useApp.getState().toast({ kind: "info", title: "No blanks found", body: "Nothing on these pages looks like an empty field. Add fields with the tools above." });
    } catch {
      ws.setOpts({ suggestions: [] });
    } finally {
      setDetecting(false);
    }
  };
  const acceptAll = async () => {
    const r = await ws.apply("forms.design", { ops: suggestions.map((s) => ({ op: "add", type: s.type, page: s.page, rect: s.rect, name: s.name, tooltip: s.label || undefined })) }, `Add ${suggestions.length} field(s)`);
    if (r) ws.setOpts({ suggestions: [] });
  };

  const cur = FIELD_TOOLS.find((t) => t.id === ws.tool);
  const sel = ws.selected?.kind === "dfield" ? fields?.find((f) => f.name === ws.selected.name) : null;
  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <div className="panel-pad" style={{ paddingBottom: 0 }}>
        <h3 style={{ marginBottom: 10 }}>Prepare a form</h3>
        <div className="field-tools">
          {FIELD_TOOLS.map((t) => (
            <button key={t.id} className={"btn sm" + (ws.tool === t.id ? " primary" : "")} title={t.label} aria-pressed={ws.tool === t.id}
              onClick={() => { ws.setTool(t.id); if (t.id !== "radioField") ws.setOpts({ radioGroup: undefined }); }}>
              <t.icon size={15} /> {t.label}
            </button>
          ))}
        </div>
        {cur && <p className="subtle">{cur.hint}{ws.tool === "radioField" && ws.opts.radioGroup ? ` Adding to group "${ws.opts.radioGroup}".` : ""}</p>}
        <button className="btn" style={{ width: "100%" }} disabled={detecting} onClick={() => detect()}>
          <WandSparkles size={15} /> {detecting ? "Looking for blanks..." : "Detect form fields"}
        </button>
        {suggestions.length > 0 && (
          <div className="suggest-box">
            <strong>{suggestions.length} suggested field(s)</strong>
            <p className="subtle">Found from underlines, lines, boxes and check squares next to labels. Dashed boxes on the page: ✓ adds one, × drops it. Nothing is added until you accept it.</p>
            <div className="row">
              <button className="btn sm primary" onClick={acceptAll}>Add all {suggestions.length}</button>
              <button className="btn sm" onClick={() => ws.setOpts({ suggestions: [] })}>Discard</button>
            </div>
          </div>
        )}
      </div>
      <div className="side-body panel-pad">
        {!fields ? <div className="empty">Reading form fields...</div> : (
          <>
            <div className="subtle" style={{ marginBottom: 4 }}>Fields ({fields.length})</div>
            {!fields.length && <div className="empty">No fields yet. Pick a field type above and click on the page.</div>}
            <div className="design-list">
              {fields.map((f) => (
                <button key={f.name} className={"design-item" + (sel?.name === f.name ? " on" : "")}
                  onClick={() => { ws.setSelected({ kind: "dfield", name: f.name }); if (f.widgets[0]) ws.viewer?.goTo(f.widgets[0].page); }}>
                  <span className="grow ellipsis">{f.name}</span>
                  <span className="subtle">{TYPE_LABEL[f.type] || f.type} · p. {f.widgets[0]?.page}</span>
                </button>
              ))}
            </div>
            {sel && <FieldProps key={sel.name + ws.revision} field={sel} />}
          </>
        )}
      </div>
      <div className="panel-pad" style={{ borderTop: "1px solid var(--border)" }}>
        <p className="subtle" style={{ margin: 0 }}>Use Save or Save As to keep the form. Fill it with Fill in a form.</p>
      </div>
    </div>
  );
}

function FieldProps({ field }: { field: any }) {
  const ws = useWs();
  const [name, setName] = useState(field.name);
  const [tooltip, setTooltip] = useState(field.label || "");
  const [required, setRequired] = useState(!!field.required);
  const [readOnly, setReadOnly] = useState(!!field.readOnly);
  const [multiline, setMultiline] = useState(!!field.multiline);
  const [maxLength, setMaxLength] = useState(String(field.maxLength || ""));
  const [options, setOptions] = useState((field.options || []).join("\n"));
  const choice = field.type === "combobox" || field.type === "listbox";
  const save = async () => {
    const op: any = { op: "update", name: field.name, tooltip, required, readOnly };
    if (name.trim() && name.trim() !== field.name) op.newName = name.trim();
    if (field.type === "text") {
      op.multiline = multiline;
      op.maxLength = parseInt(maxLength) || 0;
    }
    if (choice) op.options = options.split("\n").map((s: string) => s.trim()).filter(Boolean);
    const r = await ws.apply("forms.design", { ops: [op] }, "Field properties");
    if (r && op.newName) ws.setSelected({ kind: "dfield", name: op.newName });
  };
  const remove = async () => {
    const r = await ws.apply("forms.design", { ops: [{ op: "delete", name: field.name }] }, "Delete field");
    if (r) ws.setSelected(null);
  };
  return (
    <div className="card card-pad field-props" style={{ marginTop: 10 }} aria-label="Field properties">
      <strong>{TYPE_LABEL[field.type] || field.type} properties</strong>
      <Field label="Name"><input className="input" aria-label="Field name" value={name} onChange={(e) => setName(e.target.value)} /></Field>
      <Field label="Tooltip"><input className="input" aria-label="Field tooltip" value={tooltip} placeholder="Shown when the pointer rests on the field" onChange={(e) => setTooltip(e.target.value)} /></Field>
      <div className="row wrap" style={{ gap: 12 }}>
        <Check checked={required} onChange={setRequired}>Required</Check>
        <Check checked={readOnly} onChange={setReadOnly}>Read only</Check>
        {field.type === "text" && <Check checked={multiline} onChange={setMultiline}>Multi-line</Check>}
      </div>
      {field.type === "text" && (
        <Field label="Limit characters to"><input className="input" aria-label="Character limit" type="number" min={0} placeholder="No limit" value={maxLength} onChange={(e) => setMaxLength(e.target.value)} /></Field>
      )}
      {choice && (
        <Field label="Choices (one per line)"><textarea className="input" aria-label="Field choices" rows={4} value={options} onChange={(e) => setOptions(e.target.value)} /></Field>
      )}
      {field.type === "radio" && (
        <>
          <div className="subtle">Buttons: {field.options.join(", ")}</div>
          <button className="btn sm" style={{ marginTop: 6 }} onClick={() => { ws.setOpts({ radioGroup: field.name }); ws.setTool("radioField"); }}>
            <CircleDot size={14} /> Add a button to this group
          </button>
        </>
      )}
      {field.type === "signature" && <Notice kind="info">An empty signature field. Signers fill it with Sign &gt; Digital signature; a picture of a signature is not a digital signature.</Notice>}
      <div className="row" style={{ marginTop: 10 }}>
        <button className="btn sm" title="Delete field" onClick={remove}><Trash2 size={14} /> Delete</button>
        <div className="grow" />
        <button className="btn sm primary" onClick={save}>Apply</button>
      </div>
    </div>
  );
}
