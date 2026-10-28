import React, { useEffect, useState } from "react";
import { ArrowUpRight, Circle, Highlighter, ListFilter, MessageSquareReply, Minus, Ellipsis as MoreHorizontal, MousePointer2, PenLine, Square, Stamp, StickyNote, Strikethrough, Trash2, Type, Underline, MessageSquareText, Signature, Waves } from "lucide-react";
import { useWs } from "../context";
import { ColorPicker, Field, Notice } from "../../components/ui";
import { api, baseName, dirName, joinPath, stem, waitForJob } from "../../api";
import { openDocument } from "../../components/common";
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

/** Comment and markup tools (left tools pane). The list of comments is in the right-hand panel. */
export function CommentPanel() {
  const ws = useWs();
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
    </div>
  );
}

/** A comment with its replies and current review status (as Acrobat shows threads). */
interface Thread {
  id: number; page: number; type: string; typeName: string; author: string; contents: string; modified: string; created: string;
  rect: number[]; status: { state: string; author: string; modified: string } | null; marked: boolean;
  replies: { id: number; author: string; contents: string; modified: string; created: string }[]; raw: any;
}

function buildThreads(items: any[]): Thread[] {
  const byId = new Map<number, any>(items.map((c) => [c.id, c]));
  const tops = new Map<number, Thread>();
  for (const c of items) {
    if ((!c.irt || !byId.has(c.irt)) && !c.stateModel) {
      tops.set(c.id, { id: c.id, page: c.page, type: c.type, typeName: c.typeName, author: c.author || "", contents: c.contents || "",
        modified: c.modified || "", created: c.created || "", rect: c.rect, status: null, marked: false, replies: [], raw: c });
    }
  }
  const rootOf = (c: any): any => {
    let r = c;
    for (let i = 0; i < 50 && r.irt && byId.has(r.irt); i++) r = byId.get(r.irt);
    return r;
  };
  for (const c of [...items].sort((a, b) => String(a.modified || "").localeCompare(String(b.modified || "")))) {
    if (!c.irt || !byId.has(c.irt)) continue;
    const t = tops.get(rootOf(c).id);
    if (!t) continue;
    if (c.stateModel === "Review") t.status = { state: c.state, author: c.author || "", modified: c.modified || "" };
    else if (c.stateModel === "Marked") t.marked = c.state === "Marked";
    else t.replies.push({ id: c.id, author: c.author || "", contents: c.contents || "", modified: c.modified || "", created: c.created || "" });
  }
  return [...tops.values()];
}

export function pdfDate(d: string): string {
  const s = (d || "").replace(/^D:/, "");
  if (s.length < 8) return "";
  return `${s.slice(6, 8)}/${s.slice(4, 6)}/${s.slice(0, 4)}${s.length >= 12 ? ` ${s.slice(8, 10)}:${s.slice(10, 12)}` : ""}`;
}

const STATES = ["Accepted", "Rejected", "Cancelled", "Completed", "None"];

/** The document's comments (right-hand panel): threads, replies, status, filters, export and import. */
export function CommentsList() {
  const ws = useWs();
  const [items, setItems] = useState<any[]>([]);
  const [q, setQ] = useState("");
  const [fType, setFType] = useState("");
  const [fAuthor, setFAuthor] = useState("");
  const [fStatus, setFStatus] = useState("");
  const [sort, setSort] = useState<"page" | "date" | "author" | "type">("page");
  const [showFilters, setShowFilters] = useState(false);
  const [menu, setMenu] = useState(false);
  useEffect(() => {
    let alive = true;
    ws.query("annot.list", {}).then((d: any) => alive && setItems(d.annotations || [])).catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [ws.revision]);
  const threads = buildThreads(items);
  const types = [...new Set(threads.map((t) => t.typeName))].sort();
  const authors = [...new Set(threads.flatMap((t) => [t.author, ...t.replies.map((r) => r.author)]).filter(Boolean))].sort();
  const needle = q.trim().toLowerCase();
  const shown = threads
    .filter((t) => !fType || t.typeName === fType)
    .filter((t) => !fAuthor || t.author === fAuthor || t.replies.some((r) => r.author === fAuthor))
    .filter((t) => !fStatus || (fStatus === "Marked" ? t.marked : fStatus === "Unmarked" ? !t.marked : (t.status?.state || "None") === fStatus))
    .filter((t) => !needle || [t.contents, t.author, t.typeName, ...t.replies.map((r) => r.contents + " " + r.author)].join(" ").toLowerCase().includes(needle))
    .sort((a, b) => sort === "date" ? String(b.modified || b.created).localeCompare(String(a.modified || a.created))
      : sort === "author" ? a.author.localeCompare(b.author) : sort === "type" ? a.typeName.localeCompare(b.typeName)
      : a.page - b.page || b.rect[3] - a.rect[3]);
  const sel = ws.selected?.kind === "annot" ? ws.selected : null;
  const filtered = fType || fAuthor || fStatus || needle;
  const st = ws.doc.state;
  const base = st.savedPath || st.originalPath;
  const runJob = async (method: string, ext: string, suffix: string, title: string, done: string) => {
    const out = await api.saveFile(joinPath(dirName(base), `${stem(base)}${suffix}.${ext}`), ext, title);
    if (!out) return;
    const j = await api.startJob(method, { sessionId: ws.sessionId, output: out }, title);
    const r = await waitForJob(j.id);
    if (r.status === "succeeded") {
      const path = r.result?.output || out;
      useApp.getState().toast({ kind: "success", title: done, body: baseName(path), actions: ext === "pdf" ? [{ label: "Open", run: () => openDocument(path) }] : undefined });
    } else useApp.getState().toast({ kind: "error", title: `${title} failed`, body: r.error?.message || "" });
  };
  const options = [
    { label: "Export comments to a data file (XFDF)...", run: () => runJob("annot.exportXfdf", "xfdf", "", "Export comments", "Comments exported") },
    { label: "Import comments from a data file (XFDF)...", run: async () => {
      const f = await api.openFiles("xfdf", false, "Import comments");
      if (!f[0]) return;
      const r = await ws.apply("annot.importXfdf", { xfdfPath: f[0], author: authorName() }, "Import comments");
      if (r) useApp.getState().toast({ kind: "success", title: `${r.imported} comment(s) imported` });
    } },
    { label: "Create comment summary (PDF)...", run: () => runJob("annot.summary", "pdf", "_comment_summary", "Comment summary", "Comment summary created") },
    { label: "Export comment list (CSV for Excel)...", run: () => runJob("annot.exportList", "csv", "_comments", "Export comment list", "Comment list exported") },
    { label: "Flatten comments into the page...", run: async () => {
      const r = await api.confirm("Flatten annotations?", "Flattening burns all comments and markup into the page content.", "They will no longer be editable or deletable as comments. You can undo this before saving.", ["Flatten", "Cancel"]);
      if (r === 0) ws.apply("annot.flatten", { annotations: true, forms: false }, "Flatten annotations");
    } },
  ];
  return (
    <div className="comments-panel" style={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
      <div className="row panel-pad" style={{ paddingBottom: 6, position: "relative" }}>
        <strong className="grow">Comments ({filtered ? `${shown.length} of ${threads.length}` : threads.length})</strong>
        <button className={"icon-btn" + (showFilters ? " active" : "")} title="Filter and sort comments" aria-pressed={showFilters} onClick={() => setShowFilters(!showFilters)}><ListFilter size={16} /></button>
        <button className={"icon-btn" + (menu ? " active" : "")} title="Comment options" aria-haspopup="menu" onClick={() => setMenu(!menu)}><MoreHorizontal size={16} /></button>
        {menu && (
          <>
            <div style={{ position: "fixed", inset: 0, zIndex: 50 }} onClick={() => setMenu(false)} />
            <div className="sel-menu" role="menu" style={{ right: 8, top: 40, zIndex: 51, minWidth: 300 }}>
              {options.map((o) => <button key={o.label} role="menuitem" onClick={() => { setMenu(false); o.run(); }}>{o.label}</button>)}
            </div>
          </>
        )}
      </div>
      {showFilters && (
        <div className="panel-pad comment-filters">
          <input className="input sm" placeholder="Search comments" aria-label="Search comments" value={q} onChange={(e) => setQ(e.target.value)} />
          <div className="grid2">
            <select className="input sm" aria-label="Type" value={fType} onChange={(e) => setFType(e.target.value)}><option value="">All types</option>{types.map((t) => <option key={t}>{t}</option>)}</select>
            <select className="input sm" aria-label="Reviewer" value={fAuthor} onChange={(e) => setFAuthor(e.target.value)}><option value="">All reviewers</option>{authors.map((a) => <option key={a}>{a}</option>)}</select>
            <select className="input sm" aria-label="Status" value={fStatus} onChange={(e) => setFStatus(e.target.value)}>
              <option value="">Any status</option>{STATES.map((x) => <option key={x} value={x}>{x === "None" ? "No status" : x}</option>)}<option value="Marked">Checked</option><option value="Unmarked">Not checked</option>
            </select>
            <select className="input sm" aria-label="Sort by" value={sort} onChange={(e) => setSort(e.target.value as any)}>
              <option value="page">Sort by page</option><option value="date">Sort by date</option><option value="author">Sort by reviewer</option><option value="type">Sort by type</option>
            </select>
          </div>
        </div>
      )}
      <div className="side-body">
        {threads.length === 0 && <div className="empty">No comments yet.</div>}
        {threads.length > 0 && shown.length === 0 && <div className="empty">No comment matches the filter.</div>}
        {shown.map((t) => <CommentCard key={t.id} t={t} on={sel?.id === t.id} />)}
      </div>
      {sel && <AnnotEditor a={sel} />}
    </div>
  );
}

function CommentCard({ t, on }: { t: Thread; on: boolean }) {
  const ws = useWs();
  const [reply, setReply] = useState("");
  const st = t.status?.state && t.status.state !== "None" ? t.status.state : "";
  const op = (o: any, label: string) => ws.apply("annot.apply", { ops: [{ page: t.page, id: t.id, ...o }], author: authorName() }, label);
  return (
    <div className={"list-item comment-card" + (on ? " on" : "")} onClick={() => { if (!on) { ws.setSelected({ kind: "annot", ...t.raw }); ws.viewer?.goTo(t.page); } }}>
      <div className="row">
        <input type="checkbox" title="Check mark (for your own tracking)" aria-label="Check mark" checked={t.marked}
          onClick={(e) => e.stopPropagation()} onChange={() => op({ op: "mark", marked: !t.marked }, t.marked ? "Clear check mark" : "Check comment")} />
        <span className="badge">{t.typeName}</span>
        <span className="subtle grow">p. {t.page}</span>
        {st && <span className={"badge status-" + st.toLowerCase()} title={`${st} by ${t.status!.author}`}>{st}</span>}
      </div>
      <div className="subtle" style={{ fontSize: 11.5, marginTop: 2 }}>{t.author || "Unknown reviewer"}{pdfDate(t.modified || t.created) && ` · ${pdfDate(t.modified || t.created)}`}</div>
      {t.contents && <div className={on ? "comment-text" : "comment-text ellipsis"}>{t.contents}</div>}
      {t.replies.length > 0 && (
        <div className="replies">
          {t.replies.map((r) => (
            <div key={r.id} className="reply"><strong>{r.author || "Reply"}</strong>{pdfDate(r.modified || r.created) && <span className="subtle"> · {pdfDate(r.modified || r.created)}</span>}<div>{r.contents}</div></div>
          ))}
        </div>
      )}
      {on && (
        <div className="thread-actions" onClick={(e) => e.stopPropagation()}>
          <textarea className="input sm" rows={2} placeholder="Add a reply" aria-label="Reply" value={reply} onChange={(e) => setReply(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && e.ctrlKey && reply.trim()) { op({ op: "reply", contents: reply.trim() }, "Reply"); setReply(""); } }} />
          <div className="row">
            <select className="input sm" aria-label="Set status" value={t.status?.state || "None"} onChange={(e) => op({ op: "status", state: e.target.value }, "Set status")}>
              {STATES.map((x) => <option key={x} value={x}>{x === "None" ? "Status: none" : `Status: ${x}`}</option>)}
            </select>
            <div className="grow" />
            <button className="btn sm primary" disabled={!reply.trim()} onClick={() => { op({ op: "reply", contents: reply.trim() }, "Reply"); setReply(""); }}><MessageSquareReply size={14} /> Reply</button>
          </div>
        </div>
      )}
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

function authorName() {
  return useApp.getState().settings.reviewerName || undefined;
}
