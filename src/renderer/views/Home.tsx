import React, { useEffect, useState } from "react";
import { Clock, FileText, FolderOpen, ShieldCheck, Trash2, WifiOff } from "lucide-react";
import { api, baseName, dirName, formatBytes } from "../api";
import { useApp } from "../store";
import { openDocument, pickAndOpen } from "../components/common";
import { TOOLS, ToolDef } from "../tools";

export async function launchTool(t: ToolDef) {
  const st = useApp.getState();
  if (t.docMode) {
    // Document tools work on the active document, or ask for one.
    let id = st.view.kind === "doc" ? st.view.sessionId : st.docOrder[st.docOrder.length - 1];
    if (!id) {
      const files = await api.openFiles("pdf", false, `Choose a PDF to ${t.title.toLowerCase()}`);
      if (!files[0]) return;
      const ok = await openDocument(files[0], t.docMode);
      return ok;
    }
    window.dispatchEvent(new CustomEvent("nayan:set-mode", { detail: { sessionId: id, mode: t.docMode } }));
    st.setView({ kind: "doc", sessionId: id });
    return;
  }
  st.setView({ kind: "tool", tool: t.id });
}

export function Home() {
  const recent = useApp((s) => s.recent);
  const setRecent = useApp((s) => s.setRecent);
  const [over, setOver] = useState(false);

  useEffect(() => {
    api.recent().then(setRecent).catch(() => undefined);
    return window.nayan.onFilesDropped(async (paths) => {
      for (const p of paths.filter((x) => x.toLowerCase().endsWith(".pdf"))) await openDocument(p);
      const other = paths.filter((x) => !x.toLowerCase().endsWith(".pdf"));
      if (other.length) useApp.getState().setView({ kind: "tool", tool: "create", args: { files: other } });
    });
  }, []);

  return (
    <div className="page">
      <div className="page-narrow">
        <div className="hero">
          <div
            className={"dropzone" + (over ? " over" : "")}
            onDragOver={(e) => { e.preventDefault(); setOver(true); }}
            onDragLeave={() => setOver(false)}
            onDrop={(e) => { e.preventDefault(); setOver(false); }}
          >
            <FileText size={38} color="var(--accent)" />
            <h2>Open a PDF to view, edit and sign</h2>
            <div className="muted">Drag files here, or</div>
            <button className="btn primary lg" onClick={pickAndOpen}>
              <FolderOpen size={17} /> Open file
            </button>
            <div className="subtle">Ctrl+O · Other file types dropped here go to Create PDF</div>
          </div>
          <div className="card card-pad" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            <h3>Your documents stay on this computer</h3>
            <div className="row" style={{ alignItems: "flex-start" }}>
              <WifiOff size={18} color="var(--success)" />
              <div className="muted">All processing (conversion, OCR, compression, editing) runs locally. NayanPDF makes no network connections, has no account and no telemetry.</div>
            </div>
            <div className="row" style={{ alignItems: "flex-start" }}>
              <ShieldCheck size={18} color="var(--success)" />
              <div className="muted">Originals are never overwritten unless you explicitly choose to. Results are saved as new files and checked before they are reported as done.</div>
            </div>
          </div>
        </div>

        <div className="section-title"><h2>Tools</h2></div>
        <div className="tool-grid">
          {TOOLS.map((t) => (
            <button key={t.id} className="tool-card" onClick={() => launchTool(t)}>
              <div className="ic" style={{ background: t.color + "1a", color: t.color }}>
                <t.icon size={20} />
              </div>
              <div>
                <div className="tt">{t.title}</div>
                <div className="td">{t.desc}</div>
              </div>
            </button>
          ))}
        </div>

        <div className="section-title">
          <h2>Recent documents</h2>
          {recent.length > 0 && (
            <button className="btn sm ghost" onClick={() => api.clearRecent().then(setRecent)}>Clear list</button>
          )}
        </div>
        <div className="card" style={{ padding: 6 }}>
          {recent.length === 0 && <div className="empty">Documents you open will appear here.</div>}
          <div className="recent-list">
            {recent.map((r) => (
              <div key={r.path} className="recent-item" onClick={() => openDocument(r.path)}>
                <FileText size={20} color="var(--brand)" />
                <div className="grow" style={{ minWidth: 0 }}>
                  <div className="nm ellipsis">{r.name || baseName(r.path)}</div>
                  <div className="subtle ellipsis">{dirName(r.path)}</div>
                </div>
                <span className="subtle">{r.pages ? `${r.pages} pages · ` : ""}{formatBytes(r.size)}</span>
                <span className="subtle row" style={{ gap: 4 }}><Clock size={12} />{new Date(r.openedAt).toLocaleDateString()}</span>
                <button className="icon-btn" title="Remove from list" onClick={(e) => { e.stopPropagation(); api.removeRecent(r.path).then(setRecent); }}>
                  <Trash2 size={15} />
                </button>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
