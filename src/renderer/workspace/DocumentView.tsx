import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ChevronDown, ChevronLeft, ChevronRight, Eye, FilePen, FormInput, Highlighter, LayoutGrid, Maximize, MoveHorizontal, PanelLeft, PanelRight,
  Printer, Redo2, RotateCw, RotateCcw, Save, Search, Shredder, Signature, Stamp, Undo2, X, ZoomIn, ZoomOut, Info, FileDown, ArrowLeftRight, Lock,
  BookOpen, Check as CheckIcon, Expand, GalleryThumbnails, Bookmark, Paperclip, MessageSquare, MousePointer2, StickyNote, PenLine, Type,
  ArrowLeft, Combine, FileSearch, ScanText, Scissors, Images, Tags, Wrench, FilePlus2, Layers,
} from "lucide-react";
import { api, baseName, dirName, joinPath, stem, waitForJob } from "../api";
import { errorText, useApp } from "../store";
import { DisplayMode, InitialView, PageLayout, Viewer, ViewerHandle } from "./Viewer";
import { ThumbnailPanel } from "./Thumbnails";
import { Mode, RedactMark, WsApi, WsContext } from "./context";
import { PageOverlay, clearOverlayCache } from "./Overlay";
import { SelectionTools, TEXT_TOOLS } from "./Selection";
import { PrintDialog } from "./PrintDialog";
import { EditPanel } from "./panels/EditPanel";
import { CommentPanel, CommentsList } from "./panels/CommentPanel";
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
  { id: "edit", label: "Edit text & images", icon: FilePen, defaultTool: "text" },
  { id: "comment", label: "Add comments", icon: Highlighter, defaultTool: "highlight" },
  { id: "pages", label: "Organize pages", icon: LayoutGrid, defaultTool: "none" },
  { id: "forms", label: "Fill in a form", icon: FormInput, defaultTool: "fields" },
  { id: "redact", label: "Redact", icon: Shredder, defaultTool: "redactArea" },
  { id: "sign", label: "Sign", icon: Signature, defaultTool: "placeSignature" },
];

/** Right-hand panels, opened from the icon rail (one at a time, as in Acrobat). */
type RightTab = "comments" | "thumbs" | "bookmarks" | "attachments" | "props";
const RIGHT_TABS: { id: RightTab; label: string; icon: any }[] = [
  { id: "comments", label: "Comments", icon: MessageSquare },
  { id: "thumbs", label: "Page thumbnails", icon: GalleryThumbnails },
  { id: "bookmarks", label: "Bookmarks", icon: Bookmark },
  { id: "attachments", label: "Attachments", icon: Paperclip },
  { id: "props", label: "Document properties (Ctrl+D)", icon: Info },
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
  const settings = useApp((s) => s.settings);
  // Layout as in the current Acrobat: tools on the left, navigation panels on the
  // right. A document opens with only its pages on screen; the right panel opens
  // when the PDF asks for it (or when Settings say so), the tools pane when a tool
  // is chosen from it.
  const startTool = !!doc?.initialMode && doc.initialMode !== "view";
  const [showTools, setShowTools] = useState(startTool);
  const [rightTab, setRightTabState] = useState<RightTab | null>(doc?.initialMode === "comment" ? "comments" : null);
  const lastRight = useRef<RightTab>("thumbs");
  const setRightTab = (t: RightTab | null) => {
    if (t) lastRight.current = t;
    setRightTabState(t);
  };
  const toggleRight = (t: RightTab) => setRightTab(rightTab === t ? null : t);
  const [display, setDisplay] = useState<DisplayMode>({ layout: "continuous", cover: false });
  const [readMode, setReadMode] = useState(false);
  const pageInputRef = useRef<HTMLInputElement>(null);
  const [findOpen, setFindOpen] = useState(false);
  const [findQ, setFindQ] = useState("");
  const [findCase, setFindCase] = useState(false);
  const [findWord, setFindWord] = useState(false);
  const [findCount, setFindCount] = useState<[number, number]>([0, 0]);
  const [dialog, setDialog] = useState<null | "print" | "stamp" | "protect" | "export">(null);
  const [pageInput, setPageInput] = useState("1");
  const [tick, setTick] = useState(0);
  const findInputRef = useRef<HTMLInputElement>(null);
  const viewWrapRef = useRef<HTMLDivElement>(null);
  const passwordRef = useRef<string | undefined>(doc?.password);

  const state = doc?.state;
  const revision = state?.revision ?? 0;

  /**
   * Switch tool. From the tools pane the tool's options open there (and the
   * comments list on the right for commenting, as in Acrobat); from the quick
   * action toolbar the tool simply becomes active.
   */
  const setMode = (m: Mode, from: "pane" | "quick" = "pane", toolId?: string) => {
    setModeState(m);
    setTool(toolId || MODES.find((x) => x.id === m)!.defaultTool);
    setSelected(null);
    if (from === "pane" && m !== "view") {
      setShowTools(true);
      if (m === "comment") setRightTab("comments");
    }
  };

  const onInitialView = (v: InitialView) => {
    const pref = useApp.getState().settings.navPanelOnOpen;
    if (pref === "never") return;
    const tab: RightTab | null = v.pageMode === "UseOutlines" ? "bookmarks" : v.pageMode === "UseAttachments" ? "attachments" : v.pageMode === "UseThumbs" ? "thumbs" : null;
    if (tab && pref === "document") setRightTab(tab);
    else if (pref === "always") setRightTab(tab || "thumbs");
  };
  const toggleProps = () => toggleRight("props");

  // Clicking a comment on the page shows it in the Comments panel.
  useEffect(() => {
    if (selected?.kind === "annot") setRightTab("comments");
  }, [selected?.kind, selected?.id]);

  // Commands from the application menu (Menu > Save, Print, ...).
  useEffect(() => {
    const h = (e: any) => {
      if (e.detail?.sessionId !== sessionId) return;
      const c = e.detail.cmd;
      if (c === "save") save();
      else if (c === "saveAs") saveAs();
      else if (c === "print") setDialog("print");
      else if (c === "protect") setDialog("protect");
      else if (c === "props") setRightTab("props");
      else if (c === "tools") setShowTools(true);
    };
    window.addEventListener("nayan:doc-command", h);
    return () => window.removeEventListener("nayan:doc-command", h);
  });

  // Read mode (Ctrl+H) hides the application's toolbars and panels.
  useEffect(() => {
    document.body.classList.toggle("read-mode", readMode && active);
    return () => document.body.classList.remove("read-mode");
  }, [readMode, active]);

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
        toast({ kind: "error", title: `${label} failed`, body: errorText(e) + describeReport(e.details?.report) });
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
      const v = viewerRef.current;
      const fs = !!document.fullscreenElement;
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
      } else if (e.key === "F4" && !e.ctrlKey && !e.altKey) {
        // Acrobat: F4 navigation panel, Shift+F4 tools panel.
        e.preventDefault();
        if (e.shiftKey) setShowTools((x) => !x);
        else setRightTabState((x) => (x ? null : lastRight.current));
      } else if (e.ctrlKey && !e.shiftKey && key === "d") {
        e.preventDefault();
        toggleProps();
      } else if (e.ctrlKey && !e.shiftKey && key === "h") {
        e.preventDefault();
        setReadMode((x) => !x);
      } else if (e.ctrlKey && !e.shiftKey && key === "l") {
        e.preventDefault();
        if (mode !== "pages") v?.fullScreen();
      } else if (e.ctrlKey && e.shiftKey && key === "n") {
        e.preventDefault();
        if (mode === "pages") return;
        pageInputRef.current?.focus();
        pageInputRef.current?.select();
      } else if (fs && key === "escape") {
        document.exitFullscreen().catch(() => undefined);
      } else if (typing) {
        return;
      } else if (fs && ["arrowright", "arrowdown", "pagedown", "enter", " "].includes(key)) {
        // Full Screen: one page per step, as in Acrobat.
        e.preventDefault();
        v?.next();
      } else if (fs && ["arrowleft", "arrowup", "pageup", "backspace"].includes(key)) {
        e.preventDefault();
        v?.prev();
      } else if (e.ctrlKey && e.shiftKey && (e.code === "Equal" || e.code === "NumpadAdd")) {
        e.preventDefault();
        v?.rotate(90);
      } else if (e.ctrlKey && e.shiftKey && (e.code === "Minus" || e.code === "NumpadSubtract")) {
        e.preventDefault();
        v?.rotate(-90);
      } else if (e.ctrlKey && !e.shiftKey && key === "0") {
        e.preventDefault();
        v?.setScale("page-fit");
      } else if (e.ctrlKey && !e.shiftKey && key === "1") {
        e.preventDefault();
        v?.setScale("page-actual");
      } else if (e.ctrlKey && !e.shiftKey && key === "2") {
        e.preventDefault();
        v?.setScale("page-width");
      } else if (!e.ctrlKey && !e.altKey && mode !== "pages" && (key === "home" || key === "end")) {
        e.preventDefault();
        v?.goTo(key === "home" ? 1 : v.pageCount());
      } else if (!e.ctrlKey && !e.altKey && !e.shiftKey && (mode === "view" || mode === "comment") && !selected && (key === "arrowright" || key === "arrowleft")) {
        e.preventDefault();
        key === "arrowright" ? v?.next() : v?.prev();
      } else if (!e.ctrlKey && !e.altKey && mode !== "pages" && (display.layout === "single" || display.layout === "two") && (key === "pagedown" || key === "pageup")) {
        e.preventDefault();
        key === "pagedown" ? v?.next() : v?.prev();
      } else if (key === "escape" && readMode) {
        setReadMode(false);
      } else if (e.ctrlKey && key === "a" && (mode === "view" || mode === "comment")) {
        // Select all text on the current page (pages are rendered on demand).
        e.preventDefault();
        window.dispatchEvent(new CustomEvent("nayan:select-all", { detail: { sessionId, page } }));
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
      } else if (key === "escape") {
        setSelected(null);
        if (findOpen) closeFind();
      } else if ((key === "pagedown" || key === "arrowright") && mode !== "pages" && e.altKey) {
        viewerRef.current?.goTo(page + 1);
      }
    };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [active, page, mode, findOpen, state?.canUndo, state?.canRedo, selected, display, readMode, rightTab, showTools]);

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

  const toolPanel = () => {
    switch (mode) {
      case "edit": return <EditPanel />;
      case "comment": return <CommentPanel />;
      case "forms": return <FormsPanel />;
      case "redact": return <RedactPanel />;
      case "sign": return <SignPanel />;
      case "pages": return <div className="panel-pad subtle">Select pages in the grid, then use the toolbar above it: rotate, delete, duplicate, insert, extract. Drag pages to reorder. Apply changes when you are done.</div>;
      default: return null;
    }
  };
  const rightPanel = () => {
    switch (rightTab) {
      case "comments": return <CommentsList />;
      case "thumbs": return <ThumbnailPanel pdf={pdf} count={count} current={page} rotation={viewerRef.current?.rotation() || 0} onSelect={(p) => viewerRef.current?.goTo(p)} />;
      case "bookmarks": return <BookmarksPanel />;
      case "attachments": return <AttachmentsPanel />;
      case "props": return <PropertiesPanel />;
      default: return null;
    }
  };
  const modeDef = MODES.find((m) => m.id === mode)!;
  const runAction = (id: string) => {
    const setView = useApp.getState().setView;
    switch (id) {
      case "edit": case "comment": case "pages": case "forms": case "redact": case "sign": return setMode(id as Mode);
      case "protect": case "stamp": case "print": return setDialog(id as any);
      case "batch": return setView({ kind: "batch" });
      default: return setView({ kind: "tool", tool: id as any, args: { sessionId } });
    }
  };
  const showRight = !!rightTab && mode !== "pages" && !readMode;
  const cols = [showTools && !readMode ? "auto" : "", "minmax(0, 1fr)", showRight ? "auto" : "", readMode ? "" : "44px"].filter(Boolean).join(" ");

  return (
    <WsContext.Provider value={ws}>
      <div className="ws">
        <div className="ws-toolbar doc-bar">
          <button className={"btn sm ghost all-tools-btn" + (showTools ? " on" : "")} aria-pressed={showTools} title="All tools (Shift+F4)" onClick={() => setShowTools(!showTools)}>
            <PanelLeft size={16} /> All tools
          </button>
          {mode !== "view" && (
            <span className="tool-chip" role="status">
              <modeDef.icon size={15} /> {modeDef.label}
              <button className="icon-btn xs" title="Close tool" onClick={() => setMode("view")}><X size={14} /></button>
            </span>
          )}
          <div className="grow" />
          <button className="icon-btn" title="Undo (Ctrl+Z)" disabled={!state.canUndo} onClick={undo}><Undo2 size={17} /></button>
          <button className="icon-btn" title={`Redo (Ctrl+Y)`} disabled={!state.canRedo} onClick={redo}><Redo2 size={17} /></button>
          <div className="sep" />
          {mode !== "pages" && (
            <>
              <button className={"icon-btn" + (findOpen ? " active" : "")} title="Search (Ctrl+F)" onClick={() => { setFindOpen(!findOpen); setTimeout(() => findInputRef.current?.focus(), 0); }}><Search size={17} /></button>
              <ViewMenu display={display} showLeft={showTools} showRight={!!rightTab}
                onDisplay={(d) => viewerRef.current?.setDisplay(d)}
                onZoom={(z) => viewerRef.current?.setScale(z)}
                onRotate={(d) => viewerRef.current?.rotate(d)}
                onToggleLeft={() => setShowTools(!showTools)}
                onToggleRight={() => setRightTabState(rightTab ? null : lastRight.current)}
                onReadMode={() => setReadMode(true)}
                onFullScreen={() => viewerRef.current?.fullScreen()}
                onGoTo={() => { pageInputRef.current?.focus(); pageInputRef.current?.select(); }} />
              <div className="sep" />
            </>
          )}
          <button className="icon-btn" title="Save (Ctrl+S)" onClick={save}><Save size={17} /></button>
          <button className="btn sm ghost" title="Save As (Ctrl+Shift+S)" onClick={saveAs}>Save As</button>
          <button className="icon-btn" title="Print (Ctrl+P)" onClick={() => setDialog("print")}><Printer size={17} /></button>
        </div>
        <div className="ws-body" style={{ gridTemplateColumns: cols }}>
          {showTools && !readMode && (
            <div className="side tools-pane" role="region" aria-label="Tools">
              {mode === "view" ? (
                <>
                  <div className="pane-head"><strong className="grow">All tools</strong><button className="icon-btn xs" title="Close (Shift+F4)" onClick={() => setShowTools(false)}><X size={15} /></button></div>
                  <AllTools onPick={runAction} />
                </>
              ) : (
                <>
                  <div className="pane-head">
                    <button className="btn sm ghost" title="Back to all tools" onClick={() => setMode("view")}><ArrowLeft size={15} /> All tools</button>
                    <div className="grow" />
                    <button className="icon-btn xs" title="Close" onClick={() => setShowTools(false)}><X size={15} /></button>
                  </div>
                  <div className="side-body">{toolPanel()}</div>
                </>
              )}
            </div>
          )}
          <div ref={viewWrapRef} className="view-area" style={{ position: "relative", minWidth: 0, minHeight: 0, display: mode === "pages" ? "none" : "block" }}>
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
              initialZoom={settings.defaultZoom}
              initialDisplay={settings.defaultLayout === "auto" ? null : { layout: settings.defaultLayout as PageLayout, cover: false }}
              onInitialView={onInitialView}
              onDisplayChange={setDisplay}
              overlayVersion={[mode, tool, revision, marks, selected, opts, page]}
              overlayInteractive={mode !== "view" && !(mode === "comment" && TEXT_TOOLS.has(tool))}
            />
            {active && <SelectionTools wrapRef={viewWrapRef} onPrint={() => setDialog("print")} />}
            {!readMode && (
              <div className="qat" role="toolbar" aria-label="Quick tools">
                <button className={"icon-btn" + (mode === "view" ? " active" : "")} title="Select text and images" onClick={() => setMode("view")}><MousePointer2 size={17} /></button>
                <button className={"icon-btn" + (mode === "comment" && tool === "note" ? " active" : "")} title="Add a sticky note" onClick={() => setMode("comment", "quick", "note")}><StickyNote size={17} /></button>
                <button className={"icon-btn" + (mode === "comment" && tool === "highlight" ? " active" : "")} title="Highlight text" onClick={() => setMode("comment", "quick", "highlight")}><Highlighter size={17} /></button>
                <button className={"icon-btn" + (mode === "comment" && tool === "ink" ? " active" : "")} title="Draw" onClick={() => setMode("comment", "quick", "ink")}><PenLine size={17} /></button>
                <button className={"icon-btn" + (mode === "comment" && tool === "freetext" ? " active" : "")} title="Add a text box" onClick={() => setMode("comment", "quick", "freetext")}><Type size={17} /></button>
                <button className={"icon-btn" + (mode === "sign" ? " active" : "")} title="Sign" onClick={() => setMode("sign")}><Signature size={17} /></button>
              </div>
            )}
            {!readMode && (
              <div className="page-ctrl" role="toolbar" aria-label="Page navigation and zoom">
                <button className="icon-btn" title="Previous page" onClick={() => viewerRef.current?.prev()}><ChevronLeft size={17} /></button>
                <input ref={pageInputRef} className="input sm page-input" title="Go to page (Ctrl+Shift+N)" value={pageInput} onChange={(e) => setPageInput(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && viewerRef.current?.goTo(parseInt(pageInput) || 1)} onBlur={() => setPageInput(String(page))} />
                <span className="subtle page-total">/ {count}</span>
                <button className="icon-btn" title="Next page" onClick={() => viewerRef.current?.next()}><ChevronRight size={17} /></button>
                <div className="sep" />
                <button className="icon-btn" title="Zoom out (Ctrl+-)" onClick={() => viewerRef.current?.zoomOut()}><ZoomOut size={17} /></button>
                <span className="zoom-label">{scaleText}</span>
                <button className="icon-btn" title="Zoom in (Ctrl+=)" onClick={() => viewerRef.current?.zoomIn()}><ZoomIn size={17} /></button>
                <button className="icon-btn" title="Fit width (Ctrl+2)" onClick={() => viewerRef.current?.setScale("page-width")}><MoveHorizontal size={17} /></button>
                <button className="icon-btn" title="Fit page (Ctrl+0)" onClick={() => viewerRef.current?.setScale("page-fit")}><Maximize size={16} /></button>
                <button className="icon-btn" title="Rotate view clockwise (Ctrl+Shift+Plus; does not change the file)" onClick={() => viewerRef.current?.rotate(90)}><RotateCw size={16} /></button>
              </div>
            )}
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
            {readMode && (
              <div className="read-bar" role="toolbar" aria-label="Read mode">
                <button className="icon-btn" title="Previous page" onClick={() => viewerRef.current?.prev()}><ChevronLeft size={17} /></button>
                <span>{page} / {count}</span>
                <button className="icon-btn" title="Next page" onClick={() => viewerRef.current?.next()}><ChevronRight size={17} /></button>
                <div className="sep" />
                <button className="icon-btn" title="Zoom out (Ctrl+-)" onClick={() => viewerRef.current?.zoomOut()}><ZoomOut size={17} /></button>
                <span className="zoom-label">{scaleText}</span>
                <button className="icon-btn" title="Zoom in (Ctrl+=)" onClick={() => viewerRef.current?.zoomIn()}><ZoomIn size={17} /></button>
                <button className="icon-btn" title="Fit page (Ctrl+0)" onClick={() => viewerRef.current?.setScale("page-fit")}><Maximize size={16} /></button>
                <div className="sep" />
                <button className="btn sm" title="Exit read mode (Ctrl+H or Esc)" onClick={() => setReadMode(false)}><X size={14} /> Exit read mode</button>
              </div>
            )}
            {doc.info?.likelyScanned && mode === "view" && (
              <div className="find-bar" style={{ left: 64, right: "auto" }}>
                <span className="subtle">This document has no text layer.</span>
                <button className="btn sm" onClick={() => useApp.getState().setView({ kind: "tool", tool: "ocr", args: { sessionId } })}>Make searchable (OCR)</button>
              </div>
            )}
          </div>
          {mode === "pages" && <OrganizePages />}
          {showRight && <div className="side right" role="region" aria-label={RIGHT_TABS.find((t) => t.id === rightTab)?.label}>{rightPanel()}</div>}
          {!readMode && (
            <nav className="right-rail" aria-label="Panels">
              {RIGHT_TABS.map((t) => (
                <button key={t.id} className={"icon-btn" + (rightTab === t.id && mode !== "pages" ? " active" : "")} aria-pressed={rightTab === t.id} title={t.label} disabled={mode === "pages"} onClick={() => toggleRight(t.id)}>
                  <t.icon size={18} />
                </button>
              ))}
            </nav>
          )}
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

/** The All tools list (left pane), in the order a reviewer reaches for them. */
const ALL_TOOLS: { id: string; label: string; icon: any; more?: boolean }[] = [
  { id: "edit", label: "Edit text & images", icon: FilePen },
  { id: "comment", label: "Add comments", icon: Highlighter },
  { id: "pages", label: "Organize pages", icon: LayoutGrid },
  { id: "forms", label: "Fill in a form", icon: FormInput },
  { id: "sign", label: "Sign", icon: Signature },
  { id: "redact", label: "Redact", icon: Shredder },
  { id: "protect", label: "Protect with a password", icon: Lock },
  { id: "stamp", label: "Watermark, header & page numbers", icon: Stamp },
  { id: "compress", label: "Compress", icon: FileDown },
  { id: "convert", label: "Export / convert", icon: ArrowLeftRight },
  { id: "ocr", label: "Recognize text (OCR)", icon: ScanText },
  { id: "merge", label: "Combine files", icon: Combine },
  { id: "compare", label: "Compare files", icon: FileSearch },
  { id: "print", label: "Print", icon: Printer },
  { id: "split", label: "Split", icon: Scissors, more: true },
  { id: "create", label: "Create PDF", icon: FilePlus2, more: true },
  { id: "extractImages", label: "Extract images", icon: Images, more: true },
  { id: "metadata", label: "Document properties & metadata", icon: Tags, more: true },
  { id: "utilities", label: "More utilities (PDF/A, repair, attachments...)", icon: Wrench, more: true },
  { id: "batch", label: "Batch processing", icon: Layers, more: true },
];

function AllTools({ onPick }: { onPick: (id: string) => void }) {
  const [more, setMore] = useState(false);
  return (
    <div className="side-body all-tools">
      {ALL_TOOLS.filter((t) => more || !t.more).map((t) => (
        <button key={t.id} className="tool-item" onClick={() => onPick(t.id)}>
          <t.icon size={17} /> <span>{t.label}</span>
        </button>
      ))}
      <button className="tool-item more" onClick={() => setMore(!more)}>{more ? "View less" : "View more"} <ChevronDown size={14} style={{ transform: more ? "rotate(180deg)" : undefined }} /></button>
    </div>
  );
}

function describeReport(r: any): string {
  if (!r) return "";
  const parts: string[] = [];
  const byText = new Map<string, Set<number>>();
  for (const f of r.textFound || []) {
    if (!byText.has(f.text)) byText.set(f.text, new Set());
    byText.get(f.text)!.add(f.page);
  }
  for (const [t, pages] of byText) parts.push(`"${t}" is still present on page(s) ${[...pages].join(", ")} (for example inside other text that was not marked)`);
  if (r.annotationsFound?.length) parts.push("it appears in comments");
  if (r.metadataFound?.length) parts.push("it appears in the document properties");
  if (r.bookmarksFound?.length) parts.push("it appears in bookmarks");
  if (r.streamsFound) parts.push(`it remains in ${r.streamsFound} internal object(s)`);
  if (r.areasNotCovered?.length) parts.push(`${r.areasNotCovered.length} area(s) did not render as covered`);
  return parts.length ? "\n" + parts.join("; ") + ". Mark the remaining occurrences (or a larger area) and apply again." : "";
}

/** Acrobat's View menu: page display, zoom, rotate view, panels, Read mode and Full Screen. */
function ViewMenu(props: {
  display: DisplayMode; showLeft: boolean; showRight: boolean;
  onDisplay: (d: DisplayMode) => void; onZoom: (z: string) => void; onRotate: (d: number) => void;
  onToggleLeft: () => void; onToggleRight: () => void; onReadMode: () => void; onFullScreen: () => void; onGoTo: () => void;
}) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [open]);
  const d = props.display;
  const two = d.layout === "two" || d.layout === "twoContinuous";
  const item = (label: string, run: () => void, opts: { on?: boolean; kbd?: string; icon?: any; disabled?: boolean } = {}) => (
    <button key={label} role="menuitemcheckbox" aria-checked={opts.on ?? undefined} disabled={opts.disabled}
      onClick={() => { setOpen(false); run(); }}>
      <span className="mark">{opts.on ? <CheckIcon size={14} /> : opts.icon ? <opts.icon size={14} /> : null}</span>
      {label}{opts.kbd && <span className="kbd">{opts.kbd}</span>}
    </button>
  );
  const layouts: [PageLayout, string][] = [["single", "Single page view"], ["continuous", "Enable scrolling (single page continuous)"], ["two", "Two page view"], ["twoContinuous", "Two page scrolling"]];
  return (
    <div style={{ position: "relative" }}>
      <button className="btn sm" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}>View <ChevronDown size={14} /></button>
      {open && (
        <>
          <div style={{ position: "fixed", inset: 0, zIndex: 50 }} onClick={() => setOpen(false)} />
          <div className="sel-menu view-menu" role="menu" style={{ top: 34, left: 0, zIndex: 51 }}>
            <div className="menu-label">Page display</div>
            {layouts.map(([l, label]) => item(label, () => props.onDisplay({ layout: l, cover: d.cover }), { on: d.layout === l }))}
            {item("Show cover page in two page view", () => props.onDisplay({ layout: d.layout, cover: !d.cover }), { on: two && d.cover, disabled: !two })}
            <div className="sep" />
            <div className="menu-label">Zoom</div>
            {item("Actual size", () => props.onZoom("page-actual"), { kbd: "Ctrl+1" })}
            {item("Fit page", () => props.onZoom("page-fit"), { kbd: "Ctrl+0" })}
            {item("Fit width", () => props.onZoom("page-width"), { kbd: "Ctrl+2" })}
            <div className="sep" />
            {item("Rotate view clockwise", () => props.onRotate(90), { kbd: "Ctrl+Shift+Plus", icon: RotateCw })}
            {item("Rotate view counterclockwise", () => props.onRotate(-90), { kbd: "Ctrl+Shift+Minus", icon: RotateCcw })}
            {item("Go to page...", props.onGoTo, { kbd: "Ctrl+Shift+N" })}
            <div className="sep" />
            {item("All tools panel", props.onToggleLeft, { on: props.showLeft, kbd: "Shift+F4" })}
            {item("Right-hand panel (pages, bookmarks, comments...)", props.onToggleRight, { on: props.showRight, kbd: "F4" })}
            <div className="sep" />
            {item("Read mode", props.onReadMode, { kbd: "Ctrl+H", icon: BookOpen })}
            {item("Full screen mode", props.onFullScreen, { kbd: "Ctrl+L", icon: Expand })}
          </div>
        </>
      )}
    </div>
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
      <div className="stamp-grid" style={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr) 280px", gap: 18 }}>
        <div style={{ minWidth: 0 }}><StampForm onChange={setSpec} /></div>
        <div style={{ minWidth: 0 }}>
          <div className="label">Preview of page {ws.page}</div>
          <div style={{ background: "var(--viewer-bg)", borderRadius: 10, padding: 10, minHeight: 300, display: "grid", placeItems: "center", overflow: "hidden" }}>
            {loading && <Spinner />}
            {!loading && preview && <img src={"data:image/png;base64," + preview} style={{ maxWidth: "100%", maxHeight: 420, height: "auto", objectFit: "contain", boxShadow: "var(--shadow)" }} />}
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
