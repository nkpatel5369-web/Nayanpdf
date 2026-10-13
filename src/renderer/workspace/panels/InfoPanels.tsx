import React, { useEffect, useState } from "react";
import { ArrowDown, ArrowUp, ChevronLeft, ChevronRight, Download, Paperclip, Plus, Trash2 } from "lucide-react";
import { useWs } from "../context";
import { Field, Notice } from "../../components/ui";
import { api, formatBytes } from "../../api";
import { useApp } from "../../store";

export function PropertiesPanel() {
  const ws = useWs();
  const [info, setInfo] = useState<any>(null);
  const [meta, setMeta] = useState<any>(null);
  const [fonts, setFonts] = useState<any[] | null>(null);
  const [edit, setEdit] = useState(false);
  const [form, setForm] = useState<Record<string, string>>({});
  useEffect(() => {
    ws.query("doc.info", {}).then((i) => { setInfo(i); useApp.getState().updateDocInfo(ws.sessionId, i); }).catch(() => undefined);
    ws.query("meta.get", {}).then((m: any) => { setMeta(m); setForm({ title: m.fields.title, author: m.fields.author, subject: m.fields.subject, keywords: m.fields.keywords, creator: m.fields.creator, producer: m.fields.producer }); }).catch(() => undefined);
    setFonts(null);
  }, [ws.revision]);
  if (!info) return <div className="empty">Reading document...</div>;
  const p = info.permissions || {};
  const save = async () => {
    const r = await ws.apply("meta.set", { fields: form }, "Edit properties");
    if (r) setEdit(false);
  };
  return (
    <div className="side-body panel-pad">
      <h3 style={{ marginBottom: 8 }}>Document properties</h3>
      <table className="tbl" style={{ fontSize: 12.5 }}>
        <tbody>
          <tr><td className="muted">File</td><td className="ellipsis" title={info.path}>{info.fileName}</td></tr>
          <tr><td className="muted">Size</td><td>{formatBytes(info.fileSize)}</td></tr>
          <tr><td className="muted">Pages</td><td>{info.pageCount}</td></tr>
          <tr><td className="muted">Page size</td><td>{Object.entries(info.pageSizes).map(([k, v]) => `${k} (${v})`).join(", ")}</td></tr>
          <tr><td className="muted">PDF version</td><td>{info.pdfVersion}</td></tr>
          <tr><td className="muted">Text</td><td>{info.likelyScanned ? <span className="badge warn">Scanned - no text layer</span> : <span className="badge ok">Searchable</span>}</td></tr>
          <tr><td className="muted">Security</td><td>{info.encryption ? <span className="badge info">{info.encryption}</span> : "None"}</td></tr>
          {info.encryption && (
            <tr><td className="muted">Allowed</td><td>{["print", "copy", "modify", "annotate", "form", "assemble"].map((k) => <span key={k} className={"badge " + (p[k] ? "ok" : "err")} style={{ margin: 2 }}>{k}</span>)}</td></tr>
          )}
          <tr><td className="muted">Signed</td><td>{info.signed ? <span className="badge warn">Digitally signed</span> : "No"}</td></tr>
          <tr><td className="muted">Form fields</td><td>{info.formFields}</td></tr>
          <tr><td className="muted">Comments</td><td>{info.annotations}</td></tr>
          <tr><td className="muted">Bookmarks</td><td>{info.bookmarks}</td></tr>
          <tr><td className="muted">Attachments</td><td>{info.attachments}</td></tr>
        </tbody>
      </table>
      {info.signed && <Notice kind="warn">Any change you save (edits, comments, properties) will invalidate or be flagged against the existing digital signature(s).</Notice>}
      <div className="row" style={{ margin: "14px 0 6px" }}>
        <h3 className="grow">Metadata</h3>
        {!edit && <button className="btn sm" onClick={() => setEdit(true)}>Edit</button>}
      </div>
      {meta && !edit && (
        <table className="tbl" style={{ fontSize: 12.5 }}>
          <tbody>
            {["title", "author", "subject", "keywords", "creator", "producer"].map((k) => (
              <tr key={k}><td className="muted" style={{ textTransform: "capitalize" }}>{k}</td><td style={{ wordBreak: "break-word" }}>{meta.fields[k] || <span className="subtle">-</span>}</td></tr>
            ))}
            <tr><td className="muted">Created</td><td>{meta.fields.creationDateIso ? new Date(meta.fields.creationDateIso).toLocaleString() : "-"}</td></tr>
            <tr><td className="muted">Modified</td><td>{meta.fields.modDateIso ? new Date(meta.fields.modDateIso).toLocaleString() : "-"}</td></tr>
          </tbody>
        </table>
      )}
      {edit && (
        <>
          {["title", "author", "subject", "keywords", "creator", "producer"].map((k) => (
            <Field key={k} label={k[0].toUpperCase() + k.slice(1)}>
              <input className="input" value={form[k] || ""} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />
            </Field>
          ))}
          <div className="row">
            <button className="btn sm danger" onClick={() => ws.apply("meta.remove", { removeXmp: true }, "Remove metadata").then((r) => r && setEdit(false))}>Remove all</button>
            <div className="grow" />
            <button className="btn sm" onClick={() => setEdit(false)}>Cancel</button>
            <button className="btn sm primary" onClick={save}>Apply</button>
          </div>
        </>
      )}
      <div className="row" style={{ margin: "14px 0 6px" }}>
        <h3 className="grow">Fonts</h3>
        {!fonts && <button className="btn sm" onClick={() => ws.query("doc.fonts", {}).then((f: any) => setFonts(f.fonts))}>Show</button>}
      </div>
      {fonts && fonts.map((f) => (
        <div key={f.xref} className="row" style={{ fontSize: 12, padding: "3px 0" }}>
          <span className="grow ellipsis" title={f.name}>{f.name.split("+").pop()}</span>
          <span className={"badge " + (f.embedded ? "ok" : "warn")}>{f.embedded ? (f.subset ? "Embedded subset" : "Embedded") : "Not embedded"}</span>
        </div>
      ))}
    </div>
  );
}

export function BookmarksPanel() {
  const ws = useWs();
  const [bm, setBm] = useState<any[] | null>(null);
  const [editing, setEditing] = useState(false);
  useEffect(() => {
    ws.query("bookmarks.get", {}).then((r: any) => setBm(r.bookmarks)).catch(() => setBm([]));
  }, [ws.revision]);
  if (!bm) return <div className="empty">Loading...</div>;
  const update = (i: number, patch: any) => setBm(bm.map((b, k) => (k === i ? { ...b, ...patch } : b)));
  const move = (i: number, d: number) => {
    const j = i + d;
    if (j < 0 || j >= bm.length) return;
    const n = bm.slice();
    [n[i], n[j]] = [n[j], n[i]];
    setBm(n);
  };
  return (
    <div>
      <div className="row panel-pad" style={{ paddingBottom: 4 }}>
        <strong className="grow">Bookmarks</strong>
        {!editing ? <button className="btn sm" onClick={() => setEditing(true)}>Edit</button> : (
          <>
            <button className="btn sm" onClick={() => { setEditing(false); ws.query("bookmarks.get", {}).then((r: any) => setBm(r.bookmarks)); }}>Cancel</button>
            <button className="btn sm primary" onClick={() => ws.apply("bookmarks.set", { bookmarks: bm }, "Edit bookmarks").then((r) => r && setEditing(false))}>Apply</button>
          </>
        )}
      </div>
      {bm.length === 0 && !editing && <div className="empty">No bookmarks.</div>}
      {bm.map((b, i) =>
        editing ? (
          <div key={i} className="row" style={{ padding: "3px 8px", paddingLeft: 8 + (b.level - 1) * 12, gap: 2 }}>
            <button className="icon-btn" style={{ width: 22 }} onClick={() => update(i, { level: Math.max(1, b.level - 1) })}><ChevronLeft size={13} /></button>
            <button className="icon-btn" style={{ width: 22 }} onClick={() => update(i, { level: Math.min(6, b.level + 1) })}><ChevronRight size={13} /></button>
            <input className="input sm grow" value={b.title} onChange={(e) => update(i, { title: e.target.value })} />
            <input className="input sm" style={{ width: 46 }} type="number" value={b.page} onChange={(e) => update(i, { page: parseInt(e.target.value) || 1 })} />
            <button className="icon-btn" style={{ width: 22 }} onClick={() => move(i, -1)}><ArrowUp size={13} /></button>
            <button className="icon-btn" style={{ width: 22 }} onClick={() => move(i, 1)}><ArrowDown size={13} /></button>
            <button className="icon-btn" style={{ width: 22 }} onClick={() => setBm(bm.filter((_, k) => k !== i))}><Trash2 size={13} /></button>
          </div>
        ) : (
          <div key={i} className="list-item" style={{ paddingLeft: 12 + (b.level - 1) * 14 }} onClick={() => ws.viewer?.goTo(b.page)}>
            <span className="ellipsis">{b.title}</span> <span className="subtle">{b.page}</span>
          </div>
        ),
      )}
      {editing && (
        <div className="panel-pad">
          <button className="btn sm" onClick={() => setBm([...bm, { level: 1, title: `Page ${ws.page}`, page: ws.page }])}><Plus size={13} /> Add for current page</button>
        </div>
      )}
    </div>
  );
}

export function AttachmentsPanel() {
  const ws = useWs();
  const [list, setList] = useState<any[] | null>(null);
  useEffect(() => {
    ws.query("attachments.list", {}).then((r: any) => setList(r.attachments)).catch(() => setList([]));
  }, [ws.revision]);
  const extract = async (names?: string[]) => {
    const dir = await api.pickOutputDir();
    if (!dir) return;
    const j = await api.startJob("attachments.extract", { sessionId: ws.sessionId, outputDir: dir, names }, "Extract attachments");
    useApp.getState().toast({ kind: "info", title: "Extracting attachments", body: "See Activity for the result." });
    void j;
  };
  if (!list) return <div className="empty">Loading...</div>;
  return (
    <div>
      <div className="row panel-pad" style={{ paddingBottom: 4 }}>
        <strong className="grow">Attachments</strong>
        <button className="btn sm" title="Attach files" onClick={async () => { const f = await api.openFiles("all", true); if (f.length) ws.apply("attachments.add", { files: f }, "Attach files"); }}><Paperclip size={13} /></button>
        {list.length > 0 && <button className="btn sm" onClick={() => extract()}><Download size={13} /> All</button>}
      </div>
      {list.length === 0 && <div className="empty">No attachments.</div>}
      {list.map((a) => (
        <div key={a.name} className="list-item row">
          <div className="grow" style={{ minWidth: 0 }}>
            <div className="ellipsis">{a.filename}</div>
            <div className="subtle">{formatBytes(a.size)}{a.page ? ` · page ${a.page}` : ""}</div>
          </div>
          <button className="icon-btn" title="Save to folder" onClick={() => extract([a.name])}><Download size={14} /></button>
          <button className="icon-btn" title="Remove" onClick={() => ws.apply("attachments.delete", { names: [a.name] }, "Remove attachment")}><Trash2 size={14} /></button>
        </div>
      ))}
      {list.length > 0 && <div className="panel-pad subtle">Attached files can be harmful. NayanPDF never opens them automatically.</div>}
    </div>
  );
}
