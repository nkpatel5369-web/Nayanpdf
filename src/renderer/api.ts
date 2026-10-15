// Typed wrappers around the preload bridge. Errors from the main process or
// engine arrive as NayanError with a stable code.
import type { DocState, JobInfo, RecentFile, Settings } from "@shared/ipc";

export class NayanError extends Error {
  constructor(public code: string, message: string, public details?: any) {
    super(message);
  }
}

async function call<T = any>(channel: string, payload?: unknown): Promise<T> {
  const r = await window.nayan.invoke(channel, payload);
  if (!r.ok) throw new NayanError(r.error?.code || "ERROR", r.error?.message || "Unknown error", r.error?.details);
  return r.data as T;
}

export type DialogKind = "pdf" | "images" | "office" | "text" | "html" | "pfx" | "cert" | "dll" | "any" | "all";

export const api = {
  appInfo: () => call("app:info"),
  selfTest: () => call("app:selftest"),
  openFiles: (kind: DialogKind = "pdf", multi = false, title?: string) => call<string[]>("dialog:openFiles", { kind, multi, title }),
  openFolder: (title?: string) => call<string | null>("dialog:openFolder", { title }),
  pickOutputDir: (setDefault = false) => call<string | null>("dialog:pickOutputDir", { setDefault }),
  saveFile: (defaultPath: string, extension: string, title?: string) => call<string | null>("dialog:saveFile", { defaultPath, extension, title }),
  scanFolder: (dir: string, extensions: string[], recursive: boolean) => call<string[]>("files:scanFolder", { dir, extensions, recursive }),
  reveal: (path: string) => call("files:reveal", path),
  openWithSystem: (path: string) => call("files:openWithSystem", path),
  exists: (paths: string[]) => call<boolean[]>("files:exists", paths),
  recent: () => call<RecentFile[]>("recent:list"),
  removeRecent: (path: string) => call<RecentFile[]>("recent:remove", path),
  clearRecent: () => call<RecentFile[]>("recent:clear"),
  getSettings: () => call<Settings>("settings:get"),
  setSettings: (patch: Partial<Settings>) => call<Settings>("settings:set", patch),
  tempUsage: () => call<{ bytes: number; path: string }>("temp:usage"),
  tempClear: () => call<{ removed: number; bytes: number }>("temp:clear"),
  openDoc: (path: string, password?: string) => call<{ state?: DocState; info?: any; needsPassword?: boolean; code?: string; message?: string; repaired?: boolean }>("doc:open", { path, password }),
  docQuery: <T = any>(sessionId: string, method: string, params: Record<string, any> = {}) => call<T>("doc:query", { sessionId, method, params }),
  docApply: (sessionId: string, method: string, params: Record<string, any>, label: string) => call<{ state: DocState; result: any }>("doc:apply", { sessionId, method, params, label }),
  undo: (sessionId: string) => call<DocState>("doc:undo", { sessionId }),
  redo: (sessionId: string) => call<DocState>("doc:redo", { sessionId }),
  save: (sessionId: string) => call<{ state: DocState; needsSaveAs?: boolean; cancelled?: boolean }>("doc:save", { sessionId }),
  saveAs: (sessionId: string) => call<{ state: DocState; cancelled?: boolean }>("doc:saveAs", { sessionId }),
  closeDoc: (sessionId: string) => call("doc:close", { sessionId }),
  docState: (sessionId: string) => call<DocState>("doc:state", { sessionId }),
  preview: (sessionId: string, method: string, params: Record<string, any>, page: number) => call<{ png: string; width: number; height: number }>("doc:preview", { sessionId, method, params, page }),
  printers: () => call<{ name: string; displayName: string; description: string }[]>("print:printers"),
  printPlan: (sessionId: string, job: Record<string, any>) => call<{ printed: boolean; reason?: string; sheets: number; testOutput?: string }>("doc:printPlan", { sessionId, job }),
  startJob: (method: string, params: Record<string, any>, label: string, batchId?: string) => call<JobInfo>("job:start", { method, params, label, batchId }),
  cancelJob: (id: string) => call("job:cancel", id),
  retryJob: (id: string) => call<JobInfo | null>("job:retry", id),
  jobs: () => call<JobInfo[]>("job:list"),
  clearJobs: () => call<JobInfo[]>("job:clear"),
  query: <T = any>(method: string, params: Record<string, any> = {}) => call<T>("engine:query", { method, params }),
  signatures: () => call<any[]>("signatures:list"),
  saveSignature: (name: string, kind: "signature" | "initials", png: string) => call("signatures:save", { name, kind, png }),
  deleteSignature: (id: string) => call<any[]>("signatures:delete", id),
  htmlToPdf: (path: string, output: string, opts: Record<string, any>) => call("convert:htmlToPdf", { path, output, ...opts }),
  confirm: (title: string, message: string, detail?: string, buttons?: string[], type: "warning" | "question" = "warning") =>
    call<number>("window:confirm", { title, message, detail, buttons, type }),
};

export function on(channel: string, cb: (data: any) => void): () => void {
  return window.nayan.on(channel, cb);
}

/** Wait for a job to finish. */
export function waitForJob(id: string, onUpdate?: (j: JobInfo) => void): Promise<JobInfo> {
  return new Promise((resolve) => {
    const off = on("job:update", (j: JobInfo) => {
      if (j.id !== id) return;
      onUpdate?.(j);
      if (j.status === "succeeded" || j.status === "failed" || j.status === "cancelled") {
        off();
        resolve(j);
      }
    });
  });
}

export function baseName(p: string): string {
  return p.split(/[\\/]/).pop() || p;
}

export function dirName(p: string): string {
  const i = Math.max(p.lastIndexOf("/"), p.lastIndexOf("\\"));
  return i >= 0 ? p.slice(0, i) : p;
}

export function stem(p: string): string {
  const b = baseName(p);
  const i = b.lastIndexOf(".");
  return i > 0 ? b.slice(0, i) : b;
}

export function joinPath(dir: string, name: string): string {
  const sep = dir.includes("\\") ? "\\" : "/";
  return dir.endsWith(sep) ? dir + name : dir + sep + name;
}

export function formatBytes(n?: number): string {
  if (n === undefined || n === null || isNaN(n)) return "-";
  const abs = Math.abs(n);
  if (abs < 1024) return `${n} B`;
  if (abs < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (abs < 1024 ** 3) return `${(n / 1024 / 1024).toFixed(2)} MB`;
  return `${(n / 1024 ** 3).toFixed(2)} GB`;
}
