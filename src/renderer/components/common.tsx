import React, { useCallback, useEffect, useRef, useState } from "react";
import { create } from "zustand";
import { ArrowDown, ArrowUp, ExternalLink, FileText, FolderOpen, GripVertical, Lock, Plus, RotateCcw, Trash2, X } from "lucide-react";
import type { JobInfo } from "@shared/ipc";
import { api, baseName, dirName, formatBytes, NayanError, on } from "../api";
import { errorText, useApp } from "../store";
import { Notice, PasswordPrompt, ProgressBar, Spinner } from "./ui";

// ------------------------------------------------------------ password prompts
interface PromptState {
  req: { fileName: string; invalid?: boolean; purpose?: string; resolve: (v: string | null) => void } | null;
  ask: (fileName: string, invalid?: boolean, purpose?: string) => Promise<string | null>;
  close: () => void;
}
export const usePrompt = create<PromptState>((set) => ({
  req: null,
  ask: (fileName, invalid, purpose) => new Promise((resolve) => set({ req: { fileName, invalid, purpose, resolve } })),
  close: () => set({ req: null }),
}));

export function PromptHost() {
  const req = usePrompt((s) => s.req);
  const close = usePrompt((s) => s.close);
  if (!req) return null;
  return (
    <PasswordPrompt
      fileName={req.fileName}
      invalid={req.invalid}
      purpose={req.purpose}
      onSubmit={(pw) => { req.resolve(pw); close(); }}
      onCancel={() => { req.resolve(null); close(); }}
    />
  );
}

/** Open a PDF in a new document tab, prompting for a password if needed. */
export async function openDocument(path: string, initialMode?: string): Promise<boolean> {
  const st = useApp.getState();
  const existing = Object.values(st.docs).find((d) => d.state.originalPath === path || d.state.savedPath === path);
  if (existing) {
    st.setView({ kind: "doc", sessionId: existing.state.sessionId });
    return true;
  }
  let password: string | undefined;
  let invalid = false;
  for (let attempt = 0; attempt < 6; attempt++) {
    try {
      const r = await api.openDoc(path, password);
      if (r.state) {
        st.addDoc({ state: r.state, info: r.info, password, initialMode });
        if (r.repaired) st.toast({ kind: "warning", title: "Document was repaired while opening", body: "The file's structure was damaged. Review its content; saving will write a repaired copy." });
        api.recent().then(st.setRecent).catch(() => undefined);
        return true;
      }
      if (r.needsPassword) {
        const pw = await usePrompt.getState().ask(baseName(path), invalid || (!!password && r.code === "PASSWORD_INVALID"));
        if (pw === null) return false;
        password = pw;
        invalid = true;
        continue;
      }
      st.toast({ kind: "error", title: `Cannot open ${baseName(path)}`, body: r.message || r.code });
      return false;
    } catch (e: any) {
      st.toast({ kind: "error", title: `Cannot open ${baseName(path)}`, body: errorText(e) });
      return false;
    }
  }
  return false;
}

export async function pickAndOpen() {
  const files = await api.openFiles("pdf", true, "Open PDF");
  for (const f of files) await openDocument(f);
}

// ------------------------------------------------------------ job runner hook
export function useJob() {
  const [job, setJob] = useState<JobInfo | null>(null);
  const idRef = useRef<string | null>(null);
  useEffect(
    () =>
      on("job:update", (j: JobInfo) => {
        if (j.id === idRef.current) setJob(j);
      }),
    [],
  );
  const run = useCallback(async (method: string, params: Record<string, any>, label: string) => {
    try {
      const j = await api.startJob(method, params, label);
      idRef.current = j.id;
      setJob(j);
      return j;
    } catch (e: any) {
      const failed: JobInfo = { id: "local", label, method, status: "failed", createdAt: Date.now(), error: { code: e.code || "ERROR", message: errorText(e) } };
      setJob(failed);
      return failed;
    }
  }, []);
  const reset = useCallback(() => {
    idRef.current = null;
    setJob(null);
  }, []);
  const running = job?.status === "queued" || job?.status === "running";
  return { job, run, reset, running };
}

// ------------------------------------------------------------ job status view
export function JobView(props: { job: JobInfo | null; children?: (result: any) => React.ReactNode; openable?: boolean }) {
  const j = props.job;
  if (!j) return null;
  const running = j.status === "queued" || j.status === "running";
  return (
    <div className="card card-pad result-box">
      <div className="row">
        {running && <Spinner />}
        <strong className="grow">
          {j.status === "queued" && "Waiting to start..."}
          {j.status === "running" && (j.progress?.message || j.statusMessage || "Processing...")}
          {j.status === "succeeded" && "Completed"}
          {j.status === "failed" && "Failed"}
          {j.status === "cancelled" && "Cancelled"}
        </strong>
        {running && j.id !== "local" && (
          <button className="btn sm" onClick={() => api.cancelJob(j.id)}>
            <X size={14} /> Cancel
          </button>
        )}
        {(j.status === "failed" || j.status === "cancelled") && j.id !== "local" && (
          <button className="btn sm" onClick={() => api.retryJob(j.id)}>
            <RotateCcw size={14} /> Retry
          </button>
        )}
      </div>
      {running && (
        <div style={{ marginTop: 10 }}>
          <ProgressBar current={j.progress?.current} total={j.progress?.total} />
          {j.progress && j.progress.total > 0 && (
            <div className="subtle" style={{ marginTop: 4 }}>
              {j.progress.current} of {j.progress.total}
            </div>
          )}
        </div>
      )}
      {j.status === "failed" && j.error && <ErrorBox error={j.error} />}
      {j.status === "succeeded" && (
        <>
          {props.children?.(j.result)}
          <Outputs paths={j.outputs || []} openable={props.openable !== false} />
          {(j.warnings || []).map((w, i) => (
            <Notice key={i} kind="warn">{w}</Notice>
          ))}
        </>
      )}
    </div>
  );
}

export function ErrorBox(props: { error: { code: string; message: string; details?: any } }) {
  const e = props.error;
  const report = e.details?.report;
  return (
    <Notice kind="err">
      <div>{e.message}</div>
      {e.code && <div className="subtle mono">{e.code}</div>}
      {report && (
        <div className="subtle" style={{ marginTop: 6 }}>
          {report.textFound?.length ? `Text still found on page(s): ${[...new Set(report.textFound.map((x: any) => x.page))].join(", ")}. ` : ""}
          {report.streamsFound ? `Found in ${report.streamsFound} internal stream(s). ` : ""}
          {report.areasNotCovered?.length ? `${report.areasNotCovered.length} area(s) not visibly covered.` : ""}
        </div>
      )}
    </Notice>
  );
}

export function Outputs(props: { paths: string[]; openable?: boolean }) {
  if (!props.paths.length) return null;
  const many = props.paths.length > 8;
  const shown = many ? props.paths.slice(0, 8) : props.paths;
  return (
    <div style={{ marginTop: 10 }}>
      <div className="label" style={{ marginBottom: 4 }}>Output{props.paths.length > 1 ? `s (${props.paths.length})` : ""}</div>
      {shown.map((p) => (
        <div key={p} className="output-row">
          <FileText size={16} className="muted" />
          <span className="grow ellipsis" title={p}>{baseName(p)}</span>
          {props.openable !== false && p.toLowerCase().endsWith(".pdf") && (
            <button className="btn sm" onClick={() => openDocument(p)}>Open</button>
          )}
          {!p.toLowerCase().endsWith(".pdf") && (
            <button className="btn sm" title="Open with the default application" onClick={() => api.openWithSystem(p).catch((e) => useApp.getState().toast({ kind: "error", title: "Cannot open", body: errorText(e) }))}>
              <ExternalLink size={13} /> Open
            </button>
          )}
          <button className="icon-btn" title="Show in folder" onClick={() => api.reveal(p)}>
            <FolderOpen size={16} />
          </button>
        </div>
      ))}
      {many && (
        <div className="row" style={{ marginTop: 6 }}>
          <span className="subtle grow">and {props.paths.length - 8} more in {dirName(props.paths[0])}</span>
          <button className="btn sm" onClick={() => api.reveal(props.paths[0])}>Show folder</button>
        </div>
      )}
    </div>
  );
}

// ------------------------------------------------------------ output location
export function OutputDir(props: { value: string | null; onChange: (v: string | null) => void; sourceHint?: string }) {
  const settings = useApp((s) => s.settings);
  const effective = props.value || settings.defaultOutputDir || "";
  return (
    <div className="field">
      <label>Save to</label>
      <div className="row">
        <div className="input ellipsis" style={{ display: "flex", alignItems: "center", color: effective ? "var(--text)" : "var(--text-3)" }} title={effective}>
          {effective || "Same folder as the source file"}
        </div>
        <button className="btn" onClick={async () => { const d = await api.pickOutputDir(); if (d) props.onChange(d); }}>
          <FolderOpen size={15} /> Change
        </button>
        {props.value && (
          <button className="icon-btn" title="Use the default location" onClick={() => props.onChange(null)}>
            <X size={15} />
          </button>
        )}
      </div>
      <div className="hint">Existing files are never overwritten; a numbered name is used instead.</div>
    </div>
  );
}

/** Resolve the output folder for a source file. */
export function outDirFor(source: string, chosen: string | null): string {
  return chosen || useApp.getState().settings.defaultOutputDir || dirName(source);
}

// ------------------------------------------------------------ file list
export interface FileEntry {
  path: string;
  password?: string;
  pages?: number;
  size?: number;
  thumb?: string;
  pageSel?: string;
  error?: string;
  locked?: boolean;
}

export async function probeFile(path: string): Promise<FileEntry> {
  const e: FileEntry = { path };
  if (!path.toLowerCase().endsWith(".pdf")) return e;
  try {
    const p = await api.query("doc.probe", { path });
    if (!p.ok && p.needsPassword) {
      e.locked = true;
      return e;
    }
    if (!p.ok) {
      e.error = p.message || "Cannot open";
      return e;
    }
    e.pages = p.pages;
    const t = await api.query("pages.thumbnail", { path, page: 1, width: 68 });
    e.thumb = "data:image/png;base64," + t.png;
  } catch (err: any) {
    e.error = errorText(err);
  }
  return e;
}

export function FileList(props: {
  files: FileEntry[];
  onChange: (f: FileEntry[]) => void;
  kind?: "pdf" | "images" | "office" | "any";
  addLabel?: string;
  reorder?: boolean;
  pageSelection?: boolean;
  max?: number;
}) {
  const [drag, setDrag] = useState<number | null>(null);
  const filesRef = useRef(props.files);
  filesRef.current = props.files;

  const add = async (paths: string[]) => {
    const existing = new Set(filesRef.current.map((f) => f.path));
    const fresh = paths.filter((p) => !existing.has(p));
    let next = [...filesRef.current, ...fresh.map((p) => ({ path: p }))];
    if (props.max) next = next.slice(-props.max);
    props.onChange(next);
    for (const p of fresh) {
      const info = await probeFile(p);
      props.onChange(filesRef.current.map((f) => (f.path === p ? { ...f, ...info } : f)));
    }
  };

  useEffect(() => window.nayan.onFilesDropped((paths) => add(paths)), [props.max]);

  const unlock = async (f: FileEntry) => {
    const pw = await usePrompt.getState().ask(baseName(f.path));
    if (pw === null) return;
    const p = await api.query("doc.probe", { path: f.path, password: pw });
    if (!p.ok) {
      useApp.getState().toast({ kind: "error", title: "Incorrect password" });
      return;
    }
    const t = await api.query("pages.thumbnail", { path: f.path, page: 1, width: 68, password: pw });
    props.onChange(filesRef.current.map((x) => (x.path === f.path ? { ...x, password: pw, locked: false, pages: p.pages, thumb: "data:image/png;base64," + t.png } : x)));
  };

  const move = (from: number, to: number) => {
    if (to < 0 || to >= props.files.length) return;
    const n = props.files.slice();
    const [it] = n.splice(from, 1);
    n.splice(to, 0, it);
    props.onChange(n);
  };

  return (
    <div>
      <div className="file-list">
        {props.files.map((f, i) => (
          <div
            key={f.path}
            className={"file-row" + (drag === i ? " dragging" : "")}
            draggable={props.reorder}
            onDragStart={() => setDrag(i)}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => { e.preventDefault(); e.stopPropagation(); if (drag !== null) move(drag, i); setDrag(null); }}
            onDragEnd={() => setDrag(null)}
          >
            {props.reorder && <GripVertical size={16} className="muted" style={{ cursor: "grab" }} />}
            {f.thumb ? <img className="thumb" src={f.thumb} /> : <div className="thumb" />}
            <div className="grow" style={{ minWidth: 0 }}>
              <div className="ellipsis" title={f.path}>{baseName(f.path)}</div>
              <div className="subtle">
                {f.error ? <span style={{ color: "var(--danger)" }}>{f.error}</span> : f.locked ? "Password protected" : f.pages ? `${f.pages} page${f.pages > 1 ? "s" : ""}` : dirName(f.path)}
              </div>
            </div>
            {props.pageSelection && f.pages && (
              <input className="input sm" style={{ width: 110 }} placeholder="All pages" title="Pages to include, e.g. 1-3, 5" value={f.pageSel || ""}
                onChange={(e) => props.onChange(props.files.map((x) => (x.path === f.path ? { ...x, pageSel: e.target.value } : x)))} />
            )}
            {f.locked && (
              <button className="btn sm" onClick={() => unlock(f)}>
                <Lock size={13} /> Unlock
              </button>
            )}
            {props.reorder && (
              <>
                <button className="icon-btn" title="Move up" onClick={() => move(i, i - 1)}><ArrowUp size={15} /></button>
                <button className="icon-btn" title="Move down" onClick={() => move(i, i + 1)}><ArrowDown size={15} /></button>
              </>
            )}
            <button className="icon-btn" title="Remove" onClick={() => props.onChange(props.files.filter((x) => x.path !== f.path))}>
              <Trash2 size={15} />
            </button>
          </div>
        ))}
      </div>
      <div className="row" style={{ marginTop: 10 }}>
        <button className="btn" onClick={async () => add(await api.openFiles(props.kind || "pdf", true))}>
          <Plus size={15} /> {props.addLabel || "Add files"}
        </button>
        <span className="subtle">or drop files here</span>
      </div>
    </div>
  );
}

export function useEngineQuery<T>(fn: () => Promise<T>, deps: any[]): { data: T | null; error: string | null; loading: boolean; reload: () => void } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    fn()
      .then((d) => alive && (setData(d), setError(null)))
      .catch((e) => alive && setError(e instanceof NayanError ? e.message : String(e)))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [...deps, tick]);
  return { data, error, loading, reload: () => setTick((t) => t + 1) };
}

export { formatBytes };
