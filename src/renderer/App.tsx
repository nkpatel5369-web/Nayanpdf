import React, { useEffect } from "react";
import { BrandMark } from "./components/BrandMark";
import { ActionsView } from "./views/ActionsView";
import { runShellCommand } from "./shellCommands";
import { Activity, FolderOpen, Home as HomeIcon, Layers, Moon, Settings as SettingsIcon, Sun, Wrench, X, ArrowLeftRight, Menu as MenuIcon } from "lucide-react";
import type { DocState, JobInfo, Settings } from "@shared/ipc";
import { api, on } from "./api";
import { useApp } from "./store";
import { Toasts } from "./components/ui";
import { PromptHost, openDocument, pickAndOpen } from "./components/common";
import { Home } from "./views/Home";
import { ToolView } from "./views/ToolView";
import { BatchView } from "./views/BatchView";
import { SettingsView } from "./views/SettingsView";
import { JobsDrawer } from "./views/JobsDrawer";
import { DocumentView } from "./workspace/DocumentView";

function applyTheme(theme: Settings["theme"]) {
  const dark = theme === "dark" || (theme === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
}

export function App() {
  const { view, setView, docs, docOrder, settings, setSettings, setRecent, upsertJob, setJobs, setCaps, jobsOpen, setJobsOpen, jobs } = useApp();

  useEffect(() => {
    api.getSettings().then((s) => { setSettings(s); applyTheme(s.theme); });
    api.recent().then(setRecent).catch(() => undefined);
    api.jobs().then(setJobs).catch(() => undefined);
    api.appInfo().then((i) => setCaps(i)).catch(() => undefined);
    const offs = [
      on("job:update", (j: JobInfo) => upsertJob(j)),
      on("doc:changed", (s: DocState) => useApp.getState().updateDocState(s)),
      on("settings:changed", (s: Settings) => { setSettings(s); applyTheme(s.theme); }),
      on("app:openFiles", async (files: string[]) => { for (const f of files) await openDocument(f); }),
      on("app:shellCommand", (c: any) => runShellCommand(c)),
    ];
    // Listening now: right-click commands from Explorer can be delivered.
    api.shellReady().catch(() => undefined);
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const mqh = () => applyTheme(useApp.getState().settings.theme);
    mq.addEventListener("change", mqh);
    // Keep the window from navigating to dropped files.
    const prevent = (e: DragEvent) => e.preventDefault();
    window.addEventListener("dragover", prevent);
    window.addEventListener("drop", prevent);
    return () => {
      offs.forEach((o) => o());
      mq.removeEventListener("change", mqh);
      window.removeEventListener("dragover", prevent);
      window.removeEventListener("drop", prevent);
    };
  }, []);

  // Global shortcuts (document-level ones are handled in the workspace).
  useEffect(() => {
    const k = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;
      if (e.ctrlKey && !e.shiftKey && e.key.toLowerCase() === "o") {
        e.preventDefault();
        pickAndOpen();
      }
      if (e.ctrlKey && (e.key.toLowerCase() === "w" || e.key === "F4") && view.kind === "doc") {
        e.preventDefault();
        closeTab(view.sessionId);
      }
      // Acrobat: Ctrl+Tab / Ctrl+Shift+Tab move between open documents, Ctrl+K opens Preferences.
      if (e.ctrlKey && e.key === "Tab") {
        e.preventDefault();
        const order = useApp.getState().docOrder;
        if (!order.length) return;
        const i = view.kind === "doc" ? order.indexOf(view.sessionId) : -1;
        const next = order[(i + (e.shiftKey ? -1 : 1) + order.length) % order.length];
        setView({ kind: "doc", sessionId: next });
      }
      if (e.ctrlKey && !e.shiftKey && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setView({ kind: "settings" });
      }
      if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === "f") {
        // Acrobat: Advanced Search.
        e.preventDefault();
        setView({ kind: "tool", tool: "search" });
      }
      if (e.ctrlKey && !e.shiftKey && e.key.toLowerCase() === "q") {
        // Acrobat: Exit. Closing the window asks about unsaved changes first.
        e.preventDefault();
        window.close();
      }
    };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [view]);

  const closeTab = async (id: string) => {
    const d = useApp.getState().docs[id];
    if (d?.state.dirty) {
      const r = await api.confirm("Close document?", `"${d.state.name}" has unsaved changes.`, "The original file is unchanged. Unsaved edits will be discarded.", ["Discard changes", "Cancel"]);
      if (r !== 0) return;
    }
    await api.closeDoc(id).catch(() => undefined);
    useApp.getState().removeDoc(id);
  };

  const toggleTheme = async () => {
    const dark = document.documentElement.dataset.theme === "dark";
    const s = await api.setSettings({ theme: dark ? "light" : "dark" });
    setSettings(s);
    applyTheme(s.theme);
  };

  const running = jobs.filter((j) => j.status === "running" || j.status === "queued").length;
  const railActive = (k: string) =>
    (k === "home" && view.kind === "home") || (k === "tools" && view.kind === "tool") || (k === "convert" && view.kind === "tool" && view.tool === "convert") ||
    (k === "batch" && view.kind === "batch") || (k === "settings" && view.kind === "settings");

  return (
    <div className="app">
      <div className="topbar">
        <AppMenu closeTab={closeTab} />
        <div className="brand" onClick={() => setView({ kind: "home" })} title="Home">
          <BrandMark />
          N PDF
        </div>
        <div className="tabs">
          <div className={"tab home-tab" + (view.kind === "home" ? " active" : "")} onClick={() => setView({ kind: "home" })} title="Home">
            <HomeIcon size={15} /> <span className="name">Home</span>
          </div>
          {docOrder.map((id) => {
            const d = docs[id];
            const on_ = view.kind === "doc" && view.sessionId === id;
            return (
              <div key={id} className={"tab" + (on_ ? " active" : "")} onClick={() => setView({ kind: "doc", sessionId: id })} title={d.state.savedPath || d.state.originalPath}
                onAuxClick={(e) => e.button === 1 && closeTab(id)}>
                <span className="name">{d.info?.displayDocTitle && d.info?.metadata?.title ? d.info.metadata.title : d.state.name}</span>
                {d.state.dirty && <span className="dirty" title="Unsaved changes" />}
                <span className="x" onClick={(e) => { e.stopPropagation(); closeTab(id); }} title="Close (Ctrl+W)">
                  <X size={14} />
                </span>
              </div>
            );
          })}
        </div>
        <div className="topbar-actions">
          <button className="btn sm ghost" onClick={pickAndOpen} title="Open PDF (Ctrl+O)">
            <FolderOpen size={15} /> Open
          </button>
          <button className={"icon-btn" + (jobsOpen ? " active" : "")} onClick={() => setJobsOpen(!jobsOpen)} title="Activity">
            <Activity size={17} />
            {running > 0 && <span className="badge info" style={{ position: "absolute", marginLeft: 22, marginTop: -18, padding: "0 5px" }}>{running}</span>}
          </button>
          <button className="icon-btn" onClick={toggleTheme} title="Toggle light/dark theme">
            {document.documentElement.dataset.theme === "dark" ? <Sun size={17} /> : <Moon size={17} />}
          </button>
        </div>
      </div>
      <div className={"main" + (view.kind === "doc" ? " doc-view" : "")}>
        {view.kind !== "doc" && <nav className="rail">
          <button className={"rail-btn" + (railActive("home") ? " active" : "")} onClick={() => setView({ kind: "home" })}>
            <HomeIcon size={19} /> Home
          </button>
          <button className={"rail-btn" + (view.kind === "tool" && view.tool !== "convert" ? " active" : "")} onClick={() => setView({ kind: "tool", tool: "merge" })}>
            <Wrench size={19} /> Tools
          </button>
          <button className={"rail-btn" + (railActive("convert") ? " active" : "")} onClick={() => setView({ kind: "tool", tool: "convert" })}>
            <ArrowLeftRight size={19} /> Convert
          </button>
          <button className={"rail-btn" + (railActive("batch") ? " active" : "")} onClick={() => setView({ kind: "batch" })}>
            <Layers size={19} /> Batch
          </button>
          <div className="spacer" />
          <button className={"rail-btn" + (railActive("settings") ? " active" : "")} onClick={() => setView({ kind: "settings" })}>
            <SettingsIcon size={19} /> Settings
          </button>
        </nav>}
        <div className="content">
          {view.kind === "home" && <Home />}
          {view.kind === "tool" && <ToolView tool={view.tool} args={view.args} />}
          {view.kind === "batch" && <BatchView />}
          {view.kind === "actions" && <ActionsView />}
          {view.kind === "settings" && <SettingsView />}
          {docOrder.map((id) => (
            <div key={id} className={"doc-pane" + (view.kind === "doc" && view.sessionId === id ? " active" : "")} style={{ display: view.kind === "doc" && view.sessionId === id ? "block" : "none", height: "100%" }}>
              <DocumentView sessionId={id} active={view.kind === "doc" && view.sessionId === id} />
            </div>
          ))}
        </div>
      </div>
      {jobsOpen && <JobsDrawer onClose={() => setJobsOpen(false)} />}
      <PromptHost />
      <Toasts />
    </div>
  );
}

/** The application menu (top left), as in the current Acrobat. Document commands go to the active document. */
function AppMenu({ closeTab }: { closeTab: (id: string) => void }) {
  const [open, setOpen] = React.useState(false);
  const view = useApp((s) => s.view);
  const setView = useApp((s) => s.setView);
  React.useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [open]);
  const docId = view.kind === "doc" ? view.sessionId : null;
  const doc = (cmd: string) => docId && window.dispatchEvent(new CustomEvent("nayan:doc-command", { detail: { sessionId: docId, cmd } }));
  const items: ({ label: string; kbd?: string; run: () => void; needsDoc?: boolean } | "sep")[] = [
    { label: "Create PDF...", run: () => setView({ kind: "tool", tool: "create" }) },
    { label: "Scan to PDF...", run: () => setView({ kind: "tool", tool: "scan" }) },
    { label: "Open...", kbd: "Ctrl+O", run: () => pickAndOpen() },
    { label: "Save", kbd: "Ctrl+S", needsDoc: true, run: () => doc("save") },
    { label: "Save As...", kbd: "Ctrl+Shift+S", needsDoc: true, run: () => doc("saveAs") },
    { label: "Print...", kbd: "Ctrl+P", needsDoc: true, run: () => doc("print") },
    { label: "Protect with a password...", needsDoc: true, run: () => doc("protect") },
    { label: "Document properties", kbd: "Ctrl+D", needsDoc: true, run: () => doc("props") },
    { label: "Close document", kbd: "Ctrl+W", needsDoc: true, run: () => docId && closeTab(docId) },
    "sep",
    { label: "All tools", run: () => (docId ? doc("tools") : setView({ kind: "tool", tool: "merge" })) },
    { label: "Search across files...", kbd: "Ctrl+Shift+F", run: () => setView({ kind: "tool", tool: "search" }) },
    { label: "Combine files", run: () => setView({ kind: "tool", tool: "merge" }) },
    { label: "Batch processing", run: () => setView({ kind: "batch" }) },
    { label: "Saved actions", run: () => setView({ kind: "actions" }) },
    "sep",
    { label: "Preferences", kbd: "Ctrl+K", run: () => setView({ kind: "settings" }) },
    { label: "Exit", kbd: "Ctrl+Q", run: () => window.close() },
  ];
  return (
    <div style={{ position: "relative" }}>
      <button className={"icon-btn" + (open ? " active" : "")} title="Menu" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}><MenuIcon size={18} /></button>
      {open && (
        <>
          <div style={{ position: "fixed", inset: 0, zIndex: 60 }} onClick={() => setOpen(false)} />
          <div className="sel-menu app-menu" role="menu" style={{ top: 38, left: 0, zIndex: 61 }}>
            {items.map((it, i) => it === "sep" ? <div key={i} className="sep" /> : (
              <button key={it.label} role="menuitem" disabled={it.needsDoc && !docId} onClick={() => { setOpen(false); it.run(); }}>
                {it.label}{it.kbd && <span className="kbd">{it.kbd}</span>}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
