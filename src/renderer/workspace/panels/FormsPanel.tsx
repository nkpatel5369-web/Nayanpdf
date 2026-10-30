import React, { useEffect, useState } from "react";
import { useWs } from "../context";
import { Check, Field, Notice } from "../../components/ui";
import { api } from "../../api";

export function FormsPanel() {
  const ws = useWs();
  const [fields, setFields] = useState<any[] | null>(null);
  const [xfa, setXfa] = useState(false);
  const [values, setValues] = useState<Record<string, any>>({});
  const [flatten, setFlatten] = useState(false);
  useEffect(() => {
    let alive = true;
    ws.query("forms.list", {}).then((d: any) => {
      if (!alive) return;
      setFields(d.fields);
      setXfa(d.hasXfa);
      ws.setOpts({ fields: d.fields });
      const v: Record<string, any> = {};
      for (const f of d.fields) v[f.name] = f.value;
      setValues(v);
    }).catch(() => alive && setFields([]));
    return () => {
      alive = false;
    };
  }, [ws.revision]);
  useEffect(() => {
    if (ws.selected?.kind === "field") document.getElementById(`fld-${ws.sessionId}-${ws.selected.name}`)?.focus();
  }, [ws.selected]);
  if (!fields) return <div className="empty">Reading form fields...</div>;
  if (!fields.length) return <div className="panel-pad"><h3>Form fields</h3><div className="empty">This document has no fillable form fields.</div></div>;
  const changed = fields.filter((f) => JSON.stringify(values[f.name]) !== JSON.stringify(f.value));
  const apply = async () => {
    if (flatten) {
      const r = await api.confirm("Flatten form?", "Flattening converts fields into fixed page content.", "The form will no longer be fillable. You can undo this before saving.", ["Flatten", "Cancel"]);
      if (r !== 0) return;
    }
    const v: Record<string, any> = {};
    for (const f of flatten ? fields : changed) v[f.name] = values[f.name];
    await ws.apply("forms.fill", { values: v, flatten }, flatten ? "Fill and flatten form" : "Fill form");
  };
  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <div className="panel-pad" style={{ paddingBottom: 0 }}>
        <h3>Form fields ({fields.length})</h3>
        {xfa && <Notice kind="warn">This form also contains XFA data. N PDF fills the standard (AcroForm) fields.</Notice>}
      </div>
      <div className="side-body panel-pad">
        {fields.map((f) => {
          const id = `fld-${ws.sessionId}-${f.name}`;
          const v = values[f.name];
          const set = (x: any) => setValues((s) => ({ ...s, [f.name]: x }));
          const focus = () => { ws.setSelected({ kind: "field", name: f.name }); if (f.widgets[0]) ws.viewer?.goTo(f.widgets[0].page); };
          const label = <>{f.label || f.name}{f.required ? " *" : ""}{f.readOnly ? " (read-only)" : ""}</>;
          if (f.type === "checkbox") return <Check key={f.name} checked={!!v} onChange={set}><span onClick={focus}>{label}</span></Check>;
          if (f.type === "radio")
            return (
              <Field key={f.name} label={label}>
                <div className="row wrap">
                  {f.options.map((o: string) => (
                    <label key={o} className="check" style={{ margin: 0 }}><input type="radio" name={id} checked={v === o} onChange={() => set(o)} onFocus={focus} /> {o}</label>
                  ))}
                </div>
              </Field>
            );
          if (f.type === "combobox" || f.type === "listbox")
            return (
              <Field key={f.name} label={label}>
                <select id={id} className="input" value={v || ""} disabled={f.readOnly} onChange={(e) => set(e.target.value)} onFocus={focus}>
                  <option value=""></option>
                  {f.options.map((o: string) => <option key={o} value={o}>{o}</option>)}
                </select>
              </Field>
            );
          if (f.type === "signature") return <Field key={f.name} label={label}><div className="subtle">{v ? "Signed" : "Unsigned signature field (use Sign > Digital signature)"}</div></Field>;
          if (f.type === "button") return null;
          return (
            <Field key={f.name} label={label}>
              {f.multiline ? (
                <textarea id={id} className="input" rows={3} value={v || ""} disabled={f.readOnly} onChange={(e) => set(e.target.value)} onFocus={focus} maxLength={f.maxLength || undefined} />
              ) : (
                <input id={id} className="input" value={v || ""} disabled={f.readOnly} onChange={(e) => set(e.target.value)} onFocus={focus} maxLength={f.maxLength || undefined} />
              )}
            </Field>
          );
        })}
      </div>
      <div className="panel-pad" style={{ borderTop: "1px solid var(--border)" }}>
        <Check checked={flatten} onChange={setFlatten}>Flatten after filling (not editable afterwards)</Check>
        <button className="btn primary" style={{ width: "100%" }} disabled={!changed.length && !flatten} onClick={apply}>
          Apply {changed.length ? `${changed.length} change(s)` : ""}
        </button>
        <p className="subtle">Then use Save or Save As to keep the filled form. Print with Ctrl+P.</p>
      </div>
    </div>
  );
}
