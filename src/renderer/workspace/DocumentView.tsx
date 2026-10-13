import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ChevronDown, ChevronLeft, ChevronRight, Eye, FilePen, FormInput, Highlighter, LayoutGrid, Maximize, MoveHorizontal, PanelLeft, PanelRight,
  Printer, Redo2, RotateCw, Save, Search, Shredder, Signature, Stamp, Undo2, X, ZoomIn, ZoomOut, Info, FileDown, ArrowLeftRight, Lock,
} from "lucide-react";
import { api, baseName, dirName, joinPath, stem, waitForJob } from "../api";
import { errorText, useApp } from "../store";
import { Viewer, ViewerHandle } from "./Viewer";
import { ThumbnailPanel } from "./Thumbnails";
import { Mode, RedactMark, WsApi, WsContext } from "./context";
import { PageOverlay, clearOverlayCache } from "./Overlay";
import { EditPanel } from "./panels/EditPanel";
import { CommentPanel } from "./panels/CommentPanel";
import { FormsPanel } from "./panels/FormsPanel";
import { RedactPanel } from "./panels/RedactPanel";
import { SignPanel } from "./panels/SignPanel";
import { AttachmentsPanel, BookmarksPanel, PropertiesPanel } from "./panels/InfoPanels";
import { OrganizePages } from "./OrganizePages";
import { Check, Field, Modal, Notice, Spinner } from "../components/ui";
import { StampForm, StampSpec } from "../components/StampForm";
import { openDocument, usePrompt } from "../components/common";

const MODES: { id: Mode; label: string; icon: any; defaultTool: string }[] = [
  { id: "view", label: "View", icon: Eye, defaultTool: "none" },
  { id: "edit", label: "Edit", icon: FilePen, defaultTool: "text" },
  { id: "comment", label: "Comment", icon: Highlighter, defaultTool: "highlight" },
  { id: "pages", label: "Pages", icon: LayoutGrid, defaultTool: "none" },
  { id: "forms", label: "Forms", icon: FormInput, defaultTool: "fields" },
  { id: "redact", label: "Redact", icon: Shredder, defaultTool: "redactArea" },
  { id: "sign", label: "Sign", icon: Signature, defaultTool: "placeSignature" },
];

export function DocumentView({ sessionId, active }: { sessionId: string; active: boolean }) {
  const doc = useApp((s) => s.docs[sessionId]);
  const viewerRef = useRef<ViewerHandle>(null);
  const [pdf, setPdf] = useState<any>(null);
  const [page, setPage] = useState(1);
  const [count, setCount] = useState(0);
  const [scaleText, setScaleText] = useState("100%");
  const [mode, setModeState] = useState<Mode>((doc?.initialMode as Mode) || "view");
  const [tool, setTool] = useState<string>(MODES.find((m) => m.id === doc?.initialMode)?.defaultTool || "none");
  const [opts, setOptsState] = useState<Record<string, any>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [marks, setMarks] = useState<RedactMark[]>([]);
  const [selected, setSelected] = useState<any>(null);
  const [leftTab, setLeftTab] = useState<"thumbs" | "bookmarks" | "attachments">("thumbs");
  const [showLeft, setShowLeft] = useState(true);
  const [showRight, setShowRight] = useState(true);
  const [rightView, setRightView] = useState<"mode" | "props">("mode");
  const [findOpen, setFindOpen] = useState(false);
  const [findQ, setFindQ] = useState("");
  const [findCase, setFindCase] = useState(false);
  const [findWord, setFindWord] = useState(false);
  const [findCount, setFindCount] = useState<[number, number]>([0, 0]);
  const [dialog, setDialog] = useState<null | "print" | "stamp" | "protect" | "export">(null);
  const [pageInput, setPageInput] = useState("1");
  const [tick, setTick] = useState(0);
  const findInputRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<string | undefined>(doc?.password);

  const state = doc?.state;
  const revision = state?.revision ?? 0;

  const setMode = (m: Mode) => {
    setModeState(m);
    setTool(MODES.find((x) => x.id === m)!.defaultTool);
    setSelected(null);
    setRightView("mode");
    setShowRight(m !== "view" || rightView === "props");
  };

  useEffect(() => {
    const h = (e: any) => {
      if (e.detail.sessionId === sessionId) setMode(e.detail.mode);
    };
    window.addEventListener("nayan:set-mode", h);
    return () => window.removeEventListener("nayan:set-mode", h);
  }, []);

  const toast = useApp((s) => s.toast);
  const apply = useCallback(async (method: string, params: Record<string, any>, label: string) => {
    setBusy(label || "Working");
    try {
      const r = await api.docApply(sessionId, method, params, label);
      clearOverlayCache(sessionId);
      const w = r.result?.warnings || [];
      if (w.length) toast({ kind: "warning", title: label + " applied with notes", body: w.join("\n") });
      return r.result;
    } catch (e: any) {
      if (e.code === "PERMISSION_RESTRICTED") {
        const pw = await usePrompt.getState().ask(state?.name || "", false, "This document restricts this operation. Enter the permissions (owner) password to continue.");
        if (pw) toast({ kind: "info", title: "Re-open the document with the owner password", body: "Close this tab and open the file again, entering the owner password when asked." });
      } else {
        toast({ kind: "error", title: `${label} failed`, body: errorText(e) + (e.details?.report ? "\n(Verification report attached in log)" : "") });
      }
      return null;
    } finally {
      setBusy(null);
    }
  }, [sessionId, state?.name]);

  const query = useCallback(<T,>(method: string, params: Record<string, any> = {}) => api.docQuery<T>(sessionId, method, params), [sessionId]);

  const ws: WsApi | null = useMemo(() => doc ? ({
    sessionId, doc, viewer: viewerRef.current, pdf, page, revision, mode, tool, setTool, opts,
    setOpts: (p) => setOptsState((o) => ({ ...o, ...p })), apply, query, busy, marks, setMarks, selected, setSelected,
    bump: () => setTick((t) => t + 1),
  }) : null, [doc, pdf, page, revision, mode, tool, opts, busy, marks, selected, apply, query, tick]);

  // Keyboard shortcuts for the active document.
  useEffect(() => {
    if (!active) return;
    const k = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      const typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable);
      const key = e.key.toLowerCase();
      if (e.ctrlKey && key === "s") {
        e.preventDefault();
        e.shiftKey ? saveAs() : save();
      } else if (e.ctrlKey && key === "p") {
        e.preventDefault();
        setDialog("print");
      } else if (e.ctrlKey && key === "f") {
        e.preventDefault();
        setFindOpen(true);
        setTimeout(() => findInputRef.current?.select(), 0);
      } else if (typing) {
        return;
      } else if (e.ctrlKey && key === "z" && !e.shiftKey) {
        e.preventDefault();
        undo();
      } else if (e.ctrlKey && (key === "y" || (key === "z" && e.shiftKey))) {
        e.preventDefault();
        redo();
      } else if (e.ctrlKey && (key === "=" || key === "+")) {
        e.preventDefault();
        viewerRef.current?.zoomIn();
      } else if (e.ctrlKey && key === "-") {
        e.preventDefault();
        viewerRef.current?.zoomOut();
      } else if (e.ctrlKey && key === "0") {
        e.preventDefault();
        viewerRef.current?.setScale("page-width");
      } else if (key === "escape") {
        setSelected(null);
        if (findOpen) closeFind();
      } else if ((key === "pagedown" || key === "arrowright") && mode !== "pages" && e.altKey) {
        viewerRef.current?.goTo(page + 1);
      }
    };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [active, page, mode, findOpen, state?.canUndo, state?.canRedo]);

  if (!doc || !state || !ws) return null;

  const undo = async () => {
    if (!state.canUndo) return;
    await api.undo(sessionId);
    clearOverlayCache(sessionId);
  };
  const redo = async () => {
    if (!state.canRedo) return;
    await api.redo(sessionId);
    clearOverlayCache(sessionId);
  };
  const save = async () => {
    try {
      const r = await api.save(sessionId);
      if (r.needsSaveAs) return saveAs();
      if (!r.cancelled) toast({ kind: "success", title: "Saved", body: r.state.savedPath });
    } catch (e: any) {
      toast({ kind: "error", title: "Save failed", body: errorText(e) });
    }
  };
  const saveAs = async () => {
    try {
      const r = await api.saveAs(sessionId);
      if (!r.cancelled) toast({ kind: "success", title: "Saved", body: r.state.savedPath });
    } catch (e: any) {
      toast({ kind: "error", title: "Save failed", body: errorText(e) });
    }
  };
  const closeFind = () => {
    setFindOpen(false);
    viewerRef.current?.clearFind();
    setFindCount([0, 0]);
  };
  const doFind = (prev = false, again = true) => findQ && viewerRef.current?.find(findQ, { caseSensitive: findCase, entireWord: findWord, previous: prev, again });

  const rightPanel = () => {
    if (rightView === "props") return <PropertiesPanel />;
    switch (mode) {
      case "edit": return <EditPanel />;
      case "comment": return <CommentPanel />;
      case "forms": return <FormsPanel />;
      case "redact": return <RedactPanel />;
      case "sign": return <SignPanel />;
      default: return <PropertiesPanel />;
    }
  };

  return (
    <WsContext.Provider value={ws}>
      <div className="ws">
        <div className="ws-toolbar">
          <button className="icon-btn" title="Save (Ctrl+S)" onClick={save}><Save size={17} /></button>
          <button className="btn sm ghost" title="Save As (Ctrl+Shift+S)" onClick={saveAs}>Save As</button>
          <button className="icon-btn" title="Print (Ctrl+P)" onClick={() => setDialog("print")}><Printer size={17} /></button>
          <button className="icon-btn" title="Undo (Ctrl+Z)" disabled={!state.canUndo} onClick={undo}><Undo2 size={17} /></button>
          <button className="icon-btn" title={`Redo (Ctrl+Y)`} disabled={!state.canRedo} onClick={redo}><Redo2 size={17} /></button>
          <div className="sep" />
          <div className="mode-tabs">
            {MODES.map((m) => (
              <button key={m.id} className={mode === m.id ? "on" : ""} onClick={() => setMode(m.id)}>
                <m.icon size={15} /> {m.label}
              </button>
            ))}
          </div>
          <div className="sep" />
          <ToolsMenu onPick={(id) => setDialog(id as any)} sessionId={sessionId} />
          <div className="grow" />
          {mode !== "pages" && (
            <>
              <button className="icon-btn" title="Previous page" onClick={() => viewerRef.current?.goTo(page - 1)}><ChevronLeft size={17} /></button>
              <input className="input sm page-input" value={pageInput} onChange={(e) => setPageInput(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && viewerRef.current?.goTo(parseInt(pageInput) || 1)} onBlur={() => setPageInput(String(page))} />
              <span className="subtle">/ {count}</span>
              <button className="icon-btn" title="Next page" onClick={() => viewerRef.current?.goTo(page + 1)}><ChevronRight size={17} /></button>
              <div className="sep" />
              <button className="icon-btn" title="Zoom out (Ctrl+-)" onClick={() => viewerRef.current?.zoomOut()}><ZoomOut size={17} /></button>
              <span className="zoom-label">{scaleText}</span>
              <button className="icon-btn" title="Zoom in (Ctrl++)" onClick={() => viewerRef.current?.zoomIn()}><ZoomIn size={17} /></button>
              <button className="icon-btn" title="Fit width (Ctrl+0)" onClick={() => viewerRef.current?.setScale("page-width")}><MoveHorizontal size={17} /></button>
              <button className="icon-btn" title="Fit page" onClick={() => viewerRef.current?.setScale("page-fit")}><Maximize size={16} /></button>
              <button className="icon-btn" title="Rotate view (does not change the file)" onClick={() => viewerRef.current?.rotate(90)}><RotateCw size={16} /></button>
              <button className={"icon-btn" + (findOpen ? " active" : "")} title="Search (Ctrl+F)" onClick={() => { setFindOpen(!findOpen); setTimeout(() => findInputRef.current?.focus(), 0); }}><Search size={17} /></button>
              <div className="sep" />
              <button className={"icon-btn" + (showLeft ? " active" : "")} title="Side panel" onClick={() => setShowLeft(!showLeft)}><PanelLeft size={17} /></button>
            </>
          )}
          <button className={"icon-btn" + (rightView === "props" && showRight ? " active" : "")} title="Document properties" onClick={() => { setRightView(rightView === "props" ? "mode" : "props"); setShowRight(true); }}><Info size={17} /></button>
          <button className={"icon-btn" + (showRight ? " active" : "")} title="Tool panel" onClick={() => setShowRight(!showRight)}><PanelRight size={17} /></button>
        </div>
        <div className="ws-body" style={{ gridTemplateColumns: mode === "pages" ? "1fr" : `${showLeft ? "auto" : ""} 1fr ${showRight ? "auto" : ""}`.trim() }}>
          {mode !== "pages" && showLeft && (
            <div className="side">
              <div className="side-tabs">
                <button className={leftTab === "thumbs" ? "on" : ""} onClick={() => setLeftTab("thumbs")}>Pages</button>
                <button className={leftTab === "bookmarks" ? "on" : ""} onClick={() => setLeftTab("bookmarks")}>Bookmarks</button>
                <button className={leftTab === "attachments" ? "on" : ""} onClick={() => setLeftTab("attachments")}>Files</button>
              </div>
              <div className="side-body">
                {leftTab === "thumbs" && <ThumbnailPanel pdf={pdf} count={count} current={page} rotation={viewerRef.current?.rotation() || 0} onSelect={(p) => viewerRef.current?.goTo(p)} />}
                {leftTab === "bookmarks" && <BookmarksPanel />}
                {leftTab === "attachments" && <AttachmentsPanel />}
              </div>
            </div>
          )}
          <div style={{ position: "relative", minWidth: 0, minHeight: 0, display: mode === "pages" ? "none" : "block" }}>
            <Viewer
              ref={viewerRef}
              url={state.url}
              password={passwordRef.current}
              onPassword={async (retry) => {
                const pw = await usePrompt.getState().ask(state.name, retry);
                if (pw) passwordRef.current = pw;
                return pw;
              }}
              onLoaded={(p) => { setPdf(p); setCount(p.numPages); }}
              onPageChange={(p) => { setPage(p); setPageInput(String(p)); }}
              onScaleChange={(s) => setScaleText(Math.round(s * 100) + "%")}
              onFindCount={(c, t) => setFindCount([c, t])}
              onError={(m) => toast({ kind: "error", title: "Cannot display document", body: m })}
              renderOverlay={(ctx) => <PageOverlay ctx={ctx} />}
              overlayVersion={[mode, tool, revision, marks, selected, opts, page]}
              overlayInteractive={mode !== "view"}
            />
            {findOpen && (
              <div className="find-bar">
                <input ref={findInputRef} className="input sm" style={{ width: 200 }} placeholder="Find in document" value={findQ}
                  onChange={(e) => { setFindQ(e.target.value); viewerRef.current?.find(e.target.value, { caseSensitive: findCase, entireWord: findWord }); }}
                  onKeyDown={(e) => { if (e.key === "Enter") doFind(e.shiftKey); if (e.key === "Escape") closeFind(); }} />
                <span className="subtle" style={{ minWidth: 54, textAlign: "center" }}>{findCount[1] ? `${findCount[0]} / ${findCount[1]}` : findQ ? "0" : ""}</span>
                <button className="icon-btn" title="Previous (Shift+Enter)" onClick={() => doFind(true)}><ChevronLeft size={15} /></button>
                <button className="icon-btn" title="Next (Enter)" onClick={() => doFind(false)}><ChevronRight size={15} /></button>
                <label className="check" style={{ margin: 0 }} title="Match case"><input type="checkbox" checked={findCase} onChange={(e) => setFindCase(e.target.checked)} />Aa</label>
                <label className="check" style={{ margin: 0 }} title="Whole words"><input type="checkbox" checked={findWord} onChange={(e) => setFindWord(e.target.checked)} />W</label>
                <button className="icon-btn" onClick={closeFind}><X size={15} /></button>
              </div>
            )}
            {doc.info?.likelyScanned && mode === "view" && (
              <div className="find-bar" style={{ left: 22, right: "auto" }}>
                <span className="subtle">This document has no text layer.</span>
                <button className="btn sm" onClick={() => useApp.getState().setView({ kind: "tool", tool: "ocr", args: { sessionId } })}>Make searchable (OCR)</button>
              </div>
            )}
          </div>
          {mode === "pages" && <OrganizePages />}
          {mode !== "pages" && showRight && <div className="side right">{rightPanel()}</div>}
        </div>
        {busy && (
          <div className="busy-veil">
            <div className="card"><Spinner /> {busy}...</div>
          </div>
        )}
      </div>
      {dialog === "print" && <PrintDialog onClose={() => setDialog(null)} />}
      {dialog === "stamp" && <StampDialog onClose={() => setDialog(null)} />}
      {dialog === "protect" && <ProtectDialog onClose={() => setDialog(null)} />}
    </WsContext.Provider>
  );
}

function ToolsMenu(props: { onPick: (id: string) => void; sessionId: string }) {
  const [open, setOpen] = useState(false);
  const setView = useApp((s) => s.setView);
  const items = [
    { id: "stamp", label: "Watermark, header, page numbers...", icon: Stamp, run: () => props.onPick("stamp") },
    { id: "protect", label: "Protect with password...", icon: Lock, run: () => props.onPick("protect") },
    { id: "compress", label: "Compress...", icon: FileDown, run: () => setView({ kind: "tool", tool: "compress", args: { sessionId: props.sessionId } }) },
    { id: "convert", label: "Convert / export...", icon: ArrowLeftRight, run: () => setView({ kind: "tool", tool: "convert", args: { sessionId: props.sessionId } }) },
    { id: "ocr", label: "OCR (make searchable)...", icon: Search, run: () => setView({ kind: "tool", tool: "ocr", args: { sessionId: props.sessionId } }) },
  ];
  return (
    <div style={{ position: "relative" }}>
      <button className="btn sm" onClick={() => setOpen(!open)}>More tools <ChevronDown size={14} /></button>
      {open && (
        <>
          <div style={{ position: "fixed", inset: 0, zIndex: 50 }} onClick={() => setOpen(false)} />
          <div className="card" style={{ position: "absolute", top: 34, left: 0, zIndex: 51, padding: 4, minWidth: 260 }}>
            {items.map((it) => (
              <button key={it.id} className="btn ghost" style={{ width: "100%", justifyContent: "flex-start" }} onClick={() => { setOpen(false); it.run(); }}>
                <it.icon size={15} /> {it.label}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function PrintDialog({ onClose }: { onClose: () => void }) {
  const ws = (React.useContext(WsContext))!;
  const settings = useApp((s) => s.settings);
  const [range, setRange] = useState<"all" | "current" | "custom">("all");
  const [pages, setPages] = useState("");
  const [dpi, setDpi] = useState(settings.printDpi);
  const [gray, setGray] = useState(false);
  const [busy, setBusy] = useState(false);
  const print = async () => {
    setBusy(true);
    try {
      const spec = range === "all" ? undefined : range === "current" ? String(ws.page) : pages;
      const r = await api.print(ws.sessionId, spec, dpi, gray);
      if (!r.printed && r.reason && r.reason !== "cancelled") useApp.getState().toast({ kind: "error", title: "Printing failed", body: r.reason });
      onClose();
    } catch (e: any) {
      useApp.getState().toast({ kind: "error", title: "Printing failed", body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title="Print" onClose={onClose} footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" disabled={busy} onClick={print}>{busy ? <Spinner /> : <Printer size={15} />} Continue to printer</button></>}>
      <Field label="Pages">
        <div className="row">
          <label className="check"><input type="radio" checked={range === "all"} onChange={() => setRange("all")} /> All</label>
          <label className="check"><input type="radio" checked={range === "current"} onChange={() => setRange("current")} /> Current page ({ws.page})</label>
          <label className="check"><input type="radio" checked={range === "custom"} onChange={() => setRange("custom")} /> Pages</label>
          <input className="input sm" style={{ width: 140 }} disabled={range !== "custom"} placeholder="1-3, 5" value={pages} onChange={(e) => setPages(e.target.value)} />
        </div>
      </Field>
      <div className="grid2">
        <Field label="Print quality">
          <select className="input" value={dpi} onChange={(e) => setDpi(parseInt(e.target.value))}>
            <option value={150}>Draft (150 dpi)</option><option value={300}>Standard (300 dpi)</option><option value={600}>High (600 dpi)</option>
          </select>
        </Field>
        <Field label=" "><Check checked={gray} onChange={setGray}>Black and white</Check></Field>
      </div>
      <p className="subtle">Pages are rendered by NayanPDF's PDF engine and sent to the Windows print dialog, where you choose the printer, copies and paper.</p>
    </Modal>
  );
}

function StampDialog({ onClose }: { onClose: () => void }) {
  const ws = (React.useContext(WsContext))!;
  const [spec, setSpec] = useState<StampSpec | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    if (!spec) return setPreview(null);
    const t = setTimeout(async () => {
      setLoading(true);
      try {
        const r = await api.preview(ws.sessionId, spec.method, spec.params, ws.page);
        setPreview(r.png);
      } catch {
        setPreview(null);
      } finally {
        setLoading(false);
      }
    }, 450);
    return () => clearTimeout(t);
  }, [JSON.stringify(spec)]);
  return (
    <Modal wide title="Watermark, header & footer, page numbers" onClose={onClose} footer={
      <><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" disabled={!spec} onClick={async () => { const r = await ws.apply(spec!.method, spec!.params, "Add " + spec!.method.split(".")[1]); if (r) onClose(); }}>Apply</button></>
    }>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 300px", gap: 18 }}>
        <StampForm onChange={setSpec} />
        <div>
          <div className="label">Preview of page {ws.page}</div>
          <div style={{ background: "var(--viewer-bg)", borderRadius: 10, padding: 10, minHeight: 300, display: "grid", placeItems: "center" }}>
            {loading && <Spinner />}
            {!loading && preview && <img src={"data:image/png;base64," + preview} style={{ maxWidth: "100%", boxShadow: "var(--shadow)" }} />}
            {!loading && !preview && <span className="subtle">No preview</span>}
          </div>
        </div>
      </div>
    </Modal>
  );
}

function ProtectDialog({ onClose }: { onClose: () => void }) {
  const ws = (React.useContext(WsContext))!;
  const st = ws.doc.state;
  const [userPw, setUserPw] = useState("");
  const [userPw2, setUserPw2] = useState("");
  const [ownerPw, setOwnerPw] = useState("");
  const [perms, setPerms] = useState({ print: true, printHighQuality: true, copy: false, modify: false, annotate: true, form: true, assemble: false, accessibility: true });
  const [restrict, setRestrict] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const run = async () => {
    const base = st.savedPath || st.originalPath;
    const out = await api.saveFile(joinPath(dirName(base), `${stem(base)}_protected.pdf`), "pdf", "Save protected PDF as");
    if (!out) return;
    const params: any = { output: out, userPassword: userPw, ownerPassword: restrict ? ownerPw : ownerPw || userPw, encryption: "AES-256", permissions: restrict ? perms : undefined };
    const j = await api.startJob("security.encrypt", { ...params, sessionId: ws.sessionId }, "Protect PDF");
    const done = await waitForJob(j.id);
    if (done.status === "succeeded") {
      useApp.getState().toast({ kind: "success", title: "Protected copy saved (verified)", body: baseName(out), actions: [{ label: "Open", run: () => openDocument(out) }] });
      onClose();
    } else setErr(done.error?.message || "Failed");
  };
  const valid = (userPw ? userPw === userPw2 : true) && (userPw || (restrict && ownerPw)) && (!restrict || ownerPw);
  return (
    <Modal title="Protect PDF" onClose={onClose} footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" disabled={!valid} onClick={run}>Save protected copy</button></>}>
      <Field label="Password to open the document">
        <div className="grid2"><input className="input" type="password" placeholder="Open password" value={userPw} onChange={(e) => setUserPw(e.target.value)} /><input className="input" type="password" placeholder="Repeat" value={userPw2} onChange={(e) => setUserPw2(e.target.value)} /></div>
      </Field>
      <Check checked={restrict} onChange={setRestrict}>Restrict printing, copying and editing</Check>
      {restrict && (
        <>
          <Field label="Permissions password (needed to change restrictions)"><input className="input" type="password" value={ownerPw} onChange={(e) => setOwnerPw(e.target.value)} /></Field>
          <div className="grid2">
            {Object.entries({ print: "Printing", copy: "Copying text and images", modify: "Changing the document", annotate: "Commenting", form: "Filling forms", assemble: "Page assembly" }).map(([k, l]) => (
              <Check key={k} checked={(perms as any)[k]} onChange={(v) => setPerms({ ...perms, [k]: v })}>{l}</Check>
            ))}
          </div>
          <Notice kind="info">Permission restrictions are honoured by reputable PDF software but are not a strong barrier on their own; use an open password for confidential files.</Notice>
        </>
      )}
      <p className="subtle">Encryption: AES-256. The protected copy is reopened and the password and permissions are verified before it is reported as saved.</p>
      {err && <Notice kind="err">{err}</Notice>}
    </Modal>
  );
}
