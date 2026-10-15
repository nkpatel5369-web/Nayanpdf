import React, { useEffect, useState } from "react";
import { ArrowUpRight, Circle, Highlighter, Layers, Minus, MousePointer2, PenLine, Square, Stamp, StickyNote, Strikethrough, Trash2, Type, Underline, MessageSquareText, Signature, Waves } from "lucide-react";
import { useWs } from "../context";
import { ColorPicker, Field, Notice } from "../../components/ui";
import { api } from "../../api";
import { useApp } from "../../store";

const TOOLS = [
  { id: "select", label: "Select", icon: MousePointer2 },
  { id: "highlight", label: "Highlight", icon: Highlighter },
  { id: "underline", label: "Underline", icon: Underline },
  { id: "strikeout", label: "Strikethrough", icon: Strikethrough },
  { id: "squiggly", label: "Squiggly", icon: Waves },
  { id: "note", label: "Sticky note", icon: StickyNote },
  { id: "freetext", label: "Text comment", icon: Type },
  { id: "callout", label: "Callout", icon: MessageSquareText },
  { id: "ink", label: "Draw", icon: PenLine },
  { id: "line", label: "Line", icon: Minus },
  { id: "arrow", label: "Arrow", icon: ArrowUpRight },
  { id: "rect", label: "Rectangle", icon: Square },
  { id: "circle", label: "Ellipse", icon: Circle },
  { id: "stamp", label: "Stamp", icon: Stamp },
  { id: "initials", label: "Initials", icon: Signature },
];

const STAMPS = ["Approved", "NotApproved", "Draft", "Final", "Confidential", "ForComment", "AsIs", "Departmental", "Experimental", "Expired", "ForPublicRelease", "NotForPublicRelease", "Sold", "TopSecret"];
const CUSTOM_STAMPS = ["PAID", "VERIFIED", "RECEIVED", "AUDITED", "CHECKED", "REJECTED", "ENTERED", "ORIGINAL SEEN"];

export function CommentPanel() {
  const ws = useWs();
  const [list, setList] = useState<any[]>([]);
  useEffect(() => {
    let alive = true;
    ws.query("annot.list", {}).then((d: any) => alive && setList(d.annotations || [])).catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [ws.revision]);
  const sel = ws.selected?.kind === "annot" ? ws.selected : null;
  const t = ws.tool;
  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <div className="panel-pad">
        <h3 style={{ marginBottom: 10 }}>Comment and mark up</h3>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 4 }}>
          {TOOLS.map((x) => (
            <button key={x.id} className={"icon-btn" + (t === x.id ? " active" : "")} style={{ width: "100%", height: 40, flexDirection: "column", display: "flex", gap: 2, fontSize: 10 }} title={x.label} onClick={() => ws.setTool(x.id)}>
              <x.icon size={17} />
              <span style={{ fontSize: 9.5 }}>{x.label}</span>
            </button>
          ))}
        </div>
        {["select", "highlight", "underline", "strikeout", "squiggly"].includes(t) && (
          <p className="subtle" style={{ margin: "8px 0 0" }}>
            {t === "select"
              ? "Select text to copy or mark it, or click a comment to edit it. Right-click for more."
              : "Drag across text to mark it. Works on text, not on scanned images (run OCR first)."}
          </p>
        )}
        {t !== "select" && t !== "stamp" && (
          <>
            <Field label="Colour"><ColorPicker value={ws.opts.color || defaultColor(t)} onChange={(c) => ws.setOpts({ color: c })} /></Field>
            {["ink", "line", "arrow", "rect", "circle"].includes(t) && (
              <Field label={`Line width: ${ws.opts.width ?? 2} pt`}>
                <input type="range" min={0.5} max={10} step={0.5} value={ws.opts.width ?? 2} onChange={(e) => ws.setOpts({ width: parseFloat(e.target.value) })} />
              </Field>
            )}
            <Field label={`Opacity: ${Math.round((ws.opts.opacity ?? 1) * 100)}%`}>
              <input type="range" min={0.1} max={1} step={0.05} value={ws.opts.opacity ?? 1} onChange={(e) => ws.setOpts({ opacity: parseFloat(e.target.value) })} />
            </Field>
            {["freetext", "callout"].includes(t) && (
              <Field label="Font size"><input className="input" type="number" value={ws.opts.fontSize || 11} onChange={(e) => ws.setOpts({ fontSize: parseFloat(e.target.value) || 11 })} /></Field>
            )}
          </>
        )}
        {t === "stamp" && (
          <>
            <Field label="Standard stamps">
              <select className="input" value={ws.opts.stampText ? "" : ws.opts.stamp || "Approved"} onChange={(e) => ws.setOpts({ stamp: e.target.value, stampText: undefined, stampImage: undefined })}>
                {ws.opts.stampText && <option value="">(custom)</option>}
                {STAMPS.map((s) => <option key={s} value={s}>{s.replace(/([a-z])([A-Z])/g, "$1 $2")}</option>)}
              </select>
            </Field>
            <Field label="Custom text stamp">
              <div className="row wrap" style={{ gap: 4 }}>
                {CUSTOM_STAMPS.map((s) => <button key={s} className={"btn sm" + (ws.opts.stampText === s ? " primary" : "")} onClick={() => ws.setOpts({ stampText: s, stamp: undefined, stampImage: undefined })}>{s}</button>)}
              </div>
              <input className="input" style={{ marginTop: 6 }} placeholder="Or type your own" value={ws.opts.stampText || ""} onChange={(e) => ws.setOpts({ stampText: e.target.value || undefined })} />
            </Field>
            {ws.opts.stampText && (
              <>
                <label className="check"><input type="checkbox" checked={!!ws.opts.stampDate} onChange={(e) => ws.setOpts({ stampDate: e.target.checked })} /> Add today's date</label>
                <Field label="Colour"><ColorPicker value={ws.opts.stampColor || "#c92a2a"} onChange={(c) => ws.setOpts({ stampColor: c })} /></Field>
              </>
            )}
            <button className="btn sm" onClick={async () => { const f = await api.openFiles("images"); if (f[0]) ws.setOpts({ stampImage: f[0], stampText: undefined }); }}>Use an image as stamp...</button>
            {ws.opts.stampImage && <div className="subtle">Image stamp selected</div>}
            <p className="subtle">Drag a box on the page, or click to place.</p>
          </>
        )}
        {t === "initials" && <p className="subtle">Places your initials and today's date (set your initials in Settings).</p>}
      </div>
      {sel && <AnnotEditor a={sel} />}
      <div className="divider" style={{ margin: 0 }} />
      <div className="row panel-pad" style={{ paddingBottom: 6 }}>
        <strong className="grow">Comments ({list.length})</strong>
        <button className="btn sm" disabled={!list.length} onClick={async () => {
          const r = await api.confirm("Flatten annotations?", "Flattening burns all comments and markup into the page content.", "They will no longer be editable or deletable as comments. You can undo this before saving.", ["Flatten", "Cancel"]);
          if (r === 0) ws.apply("annot.flatten", { annotations: true, forms: false }, "Flatten annotations");
        }}><Layers size={14} /> Flatten</button>
      </div>
      <div className="side-body">
        {list.length === 0 && <div className="empty">No comments yet.</div>}
        {list.map((a) => (
          <div key={a.id} className={"list-item" + (sel?.id === a.id ? " on" : "")} onClick={() => { ws.setSelected({ kind: "annot", ...a }); ws.viewer?.goTo(a.page); }}>
            <div className="row">
              <span className="badge">{a.typeName}</span>
              <span className="subtle grow">p. {a.page}</span>
              {a.author && <span className="subtle">{a.author}</span>}
            </div>
            {a.contents && <div style={{ marginTop: 4, fontSize: 12.5 }} className="ellipsis">{a.contents}</div>}
          </div>
        ))}
      </div>
    </div>
  );
}

function defaultColor(t: string) {
  return { highlight: "#ffd43b", underline: "#1c7ed6", strikeout: "#e03131", ink: "#1c7ed6", note: "#fab005" }[t] || "#e03131";
}

function AnnotEditor({ a }: { a: any }) {
  const ws = useWs();
  const [contents, setContents] = useState(a.contents || "");
  const [color, setColor] = useState(a.stroke || a.fill || "#e03131");
  const [opacity, setOpacity] = useState(a.opacity ?? 1);
  useEffect(() => {
    setContents(a.contents || "");
    setColor(a.stroke || a.fill || "#e03131");
    setOpacity(a.opacity ?? 1);
  }, [a.id]);
  const save = () =>
    ws.apply("annot.apply", { ops: [{ op: "update", page: a.page, id: a.id, contents, color: a.type === "freetext" ? undefined : color, opacity }] }, "Edit comment").then(() => ws.setSelected(null));
  return (
    <div className="panel-pad" style={{ background: "var(--surface-2)", borderTop: "1px solid var(--border)" }}>
      <div className="row" style={{ marginBottom: 8 }}>
        <strong className="grow">{a.typeName}</strong>
        <button className="icon-btn" title="Delete" onClick={() => ws.apply("annot.apply", { ops: [{ op: "delete", page: a.page, id: a.id }] }, "Delete comment").then(() => ws.setSelected(null))}>
          <Trash2 size={15} />
        </button>
      </div>
      <Field label="Comment text"><textarea className="input" rows={3} value={contents} onChange={(e) => setContents(e.target.value)} /></Field>
      {a.type !== "freetext" && a.type !== "stamp" && <Field label="Colour"><ColorPicker value={color} onChange={setColor} /></Field>}
      <Field label={`Opacity: ${Math.round(opacity * 100)}%`}><input type="range" min={0.1} max={1} step={0.05} value={opacity} onChange={(e) => setOpacity(parseFloat(e.target.value))} /></Field>
      <div className="row"><div className="grow" /><button className="btn sm" onClick={() => ws.setSelected(null)}>Close</button><button className="btn sm primary" onClick={save}>Save</button></div>
      {a.author && <div className="subtle" style={{ marginTop: 6 }}>By {a.author}</div>}
    </div>
  );
}

export function useReviewerReminder() {
  const s = useApp((x) => x.settings);
  return !s.reviewerName;
}

export function CommentNotice() {
  return <Notice kind="info">Set your reviewer name in Settings so comments show who made them.</Notice>;
}
