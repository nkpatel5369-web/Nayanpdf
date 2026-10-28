import React, { useState } from "react";
import { Image, ImagePlus, Link2, MousePointer2, Replace, Type, TypeOutline } from "lucide-react";
import { useWs } from "../context";
import { Check, Field, Modal, Notice, Seg } from "../../components/ui";
import { useApp } from "../../store";

export function EditPanel() {
  const ws = useWs();
  const [fr, setFr] = useState(false);
  const tools = [
    { id: "text", label: "Edit text", icon: Type, hint: "Click a line to change it. Drag a selected line to move it." },
    { id: "image", label: "Edit images", icon: Image, hint: "Select an image to move, resize (corner) or delete (Delete key)." },
    { id: "addText", label: "Add text", icon: TypeOutline, hint: "Drag a box where the new text should go." },
    { id: "addImage", label: "Add image", icon: ImagePlus, hint: "Drag a box, then choose an image file." },
    { id: "link", label: "Link", icon: Link2, hint: "Drag a box over text or an image, then choose a page or a web address. Existing links show as dashed boxes; × removes one." },
  ];
  const cur = tools.find((t) => t.id === ws.tool);
  return (
    <div className="panel-pad">
      <h3 style={{ marginBottom: 10 }}>Edit content</h3>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 6 }}>
        {tools.map((t) => (
          <button key={t.id} className={"btn" + (ws.tool === t.id ? " primary" : "")} onClick={() => ws.setTool(t.id)}>
            <t.icon size={15} /> {t.label}
          </button>
        ))}
      </div>
      {cur && <p className="subtle">{cur.hint}</p>}
      {ws.tool === "text" && (
        <Field label="Select by">
          <Seg value={ws.opts.editUnit || "line"} onChange={(v) => ws.setOpts({ editUnit: v })}
            options={[{ value: "line", label: "Line" }, { value: "paragraph", label: "Paragraph" }]} />
        </Field>
      )}
      {(ws.tool === "addText") && (
        <>
          <div className="grid2">
            <Field label="Font">
              <select className="input" value={ws.opts.fontFamily || "helv"} onChange={(e) => ws.setOpts({ fontFamily: e.target.value })}>
                <option value="helv">Sans (Helvetica)</option>
                <option value="tiro">Serif (Times)</option>
                <option value="cour">Mono (Courier)</option>
                <option value="notos">Noto Sans (₹ and more)</option>
              </select>
            </Field>
            <Field label="Size">
              <input className="input" type="number" value={ws.opts.fontSize || 11} onChange={(e) => ws.setOpts({ fontSize: parseFloat(e.target.value) || 11 })} />
            </Field>
          </div>
          <div className="row">
            <input type="color" value={ws.opts.textColor || "#000000"} onChange={(e) => ws.setOpts({ textColor: e.target.value })} />
            <Check checked={!!ws.opts.bold} onChange={(v) => ws.setOpts({ bold: v })}>Bold</Check>
            <Check checked={!!ws.opts.italic} onChange={(v) => ws.setOpts({ italic: v })}>Italic</Check>
          </div>
          <Field label="Alignment">
            <Seg value={ws.opts.align || "left"} onChange={(v) => ws.setOpts({ align: v })}
              options={[{ value: "left", label: "Left" }, { value: "center", label: "Centre" }, { value: "right", label: "Right" }]} />
          </Field>
        </>
      )}
      <div className="divider" />
      <button className="btn" style={{ width: "100%" }} onClick={() => setFr(true)}>
        <Replace size={15} /> Find and replace text...
      </button>
      <div className="divider" />
      <Notice kind="info">
        NayanPDF edits the actual page content. Results depend on how the PDF was made: scanned pages need OCR first, and text drawn as outlines cannot be edited.
        For heavy rewording, convert to Word and back.
      </Notice>
      {fr && <FindReplaceDialog onClose={() => setFr(false)} />}
    </div>
  );
}

function FindReplaceDialog({ onClose }: { onClose: () => void }) {
  const ws = useWs();
  const [find, setFind] = useState("");
  const [rep, setRep] = useState("");
  const [matchCase, setMatchCase] = useState(true);
  const [whole, setWhole] = useState(false);
  const [preview, setPreview] = useState<any>(null);
  const [busy, setBusy] = useState(false);
  const doPreview = async () => {
    setBusy(true);
    try {
      // Preview runs against a temp output that is discarded.
      const r = await ws.query("doc.search", { term: find, matchCase });
      setPreview({ total: r.hits.length, pages: [...new Set(r.hits.map((h: any) => h.page))] });
    } catch (e: any) {
      useApp.getState().toast({ kind: "error", title: "Search failed", body: e.message });
    } finally {
      setBusy(false);
    }
  };
  const run = async () => {
    const r = await ws.apply("edit.replaceAll", { find, replace: rep, matchCase, wholeWord: whole }, `Replace "${find}"`);
    if (r) {
      useApp.getState().toast({ kind: "success", title: `Replaced ${r.replaced} occurrence(s)`, body: r.skipped?.length ? `${r.skipped.reduce((a: number, s: any) => a + s.count, 0)} could not be replaced in place.` : undefined });
      onClose();
    }
  };
  return (
    <Modal title="Find and replace" onClose={onClose} footer={
      <>
        <button className="btn" onClick={doPreview} disabled={!find || busy}>Count matches</button>
        <button className="btn primary" onClick={run} disabled={!find}>Replace all</button>
      </>
    }>
      <Field label="Find"><input className="input" autoFocus value={find} onChange={(e) => { setFind(e.target.value); setPreview(null); }} /></Field>
      <Field label="Replace with"><input className="input" value={rep} onChange={(e) => setRep(e.target.value)} /></Field>
      <div className="row">
        <Check checked={matchCase} onChange={setMatchCase}>Match case</Check>
        <Check checked={whole} onChange={setWhole}>Whole words</Check>
      </div>
      {preview && <Notice kind="info">{preview.total} match(es) on page(s) {preview.pages.slice(0, 20).join(", ") || "-"}.</Notice>}
      <Notice kind="warn">Replacements are written into the page content in the original font where possible. A longer replacement can overlap following text on the same line; check the result (Undo is available).</Notice>
    </Modal>
  );
}

export function EditToolIcon() {
  return <MousePointer2 size={15} />;
}
