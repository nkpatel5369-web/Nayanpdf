// Open-document sessions.
//
// Opening a PDF never modifies it. Every edit produces a new revision file in
// the session's private temp folder; undo/redo move between revisions. Save
// copies the current revision to the destination atomically (temp file in the
// destination folder, then rename). Overwriting the original requires an
// explicit native confirmation dialog.
import { BrowserWindow, dialog, protocol } from "electron";
import { createReadStream, copyFileSync, existsSync, mkdirSync, renameSync, statSync, unlinkSync } from "node:fs";
import { basename, dirname, extname, join } from "node:path";
import { Readable } from "node:stream";
import { DocState } from "../shared/ipc";
import { access, checkParams } from "./access";
import { EngineError, enginePool } from "./engine";
import { appTempRoot } from "./paths";
import { addRecent, getSettings } from "./store";
import { cleanupDir } from "./jobs";
import { testAnswer } from "./testhooks";

interface Session {
  id: string;
  originalPath: string;
  password?: string;
  revisions: string[];
  actions: string[];
  index: number;
  savedIndex: number; // revision index that matches the file on disk
  savedPath?: string;
  // The user has agreed to replace the original file in this session; later
  // saves to it do not ask again.
  overwriteConfirmed?: boolean;
  dir: string;
  tokens: Map<number, string>;
}

const sessions = new Map<string, Session>();
const tokenToPath = new Map<string, string>();

// Edit operations that may be applied to an open document (single PDF output).
const EDIT_METHODS = new Set([
  "edit.text", "edit.replaceAll", "edit.insertText", "edit.insertImage", "edit.image",
  "annot.apply", "annot.flatten",
  "pages.assemble", "pages.rotate", "pages.delete", "pages.insertBlank", "pages.insertFromFile", "pages.replace",
  "pages.duplicate", "pages.reverse",
  "stamp.watermark", "stamp.headerFooter", "stamp.pageNumbers", "stamp.bates", "stamp.background",
  "forms.fill", "forms.design", "meta.set", "meta.remove", "redact.apply", "bookmarks.set", "labels.set",
  "attachments.add", "attachments.delete", "sign.placeImage", "ocr.run", "compress.run",
  "pagesize.standardize", "pagesize.crop", "util.repair",
  "annot.importXfdf", "links.apply", "meta.initialView",
]);

// Read-only queries allowed against an open document.
const QUERY_METHODS = new Set([
  "doc.info", "doc.pageSizes", "page.textLines", "page.words", "page.images", "doc.fonts", "doc.search", "doc.text",
  "annot.list", "forms.list", "meta.get", "bookmarks.get", "labels.get", "attachments.list", "security.inspect",
  "security.integrity", "sign.verify", "redact.search", "edit.listImages", "compress.analyze", "blank.detect",
  "util.pdfaCheck", "pages.thumbnail", "compare.run",
  "links.list", "meta.getInitialView", "forms.detect",
]);

function newToken(): string {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function tokenFor(s: Session, idx: number): string {
  let t = s.tokens.get(idx);
  if (!t) {
    t = newToken();
    s.tokens.set(idx, t);
    tokenToPath.set(t, s.revisions[idx]);
  }
  return t;
}

export function state(s: Session): DocState {
  const name = basename(s.savedPath || s.originalPath);
  const url = `nayan-doc://doc/${tokenFor(s, s.index)}/${encodeURIComponent(name)}`;
  return {
    sessionId: s.id,
    originalPath: s.originalPath,
    name,
    url,
    revision: s.index,
    revisions: s.revisions.length,
    canUndo: s.index > 0,
    canRedo: s.index < s.revisions.length - 1,
    dirty: s.index !== s.savedIndex,
    savedPath: s.savedPath,
    hasPassword: !!s.password,
    lastAction: s.actions[s.index],
  };
}

function get(id: string): Session {
  const s = sessions.get(id);
  if (!s) throw new EngineError("INVALID_ARGUMENT", "This document is no longer open.");
  return s;
}

function broadcast(s: Session) {
  for (const w of BrowserWindow.getAllWindows()) if (!w.isDestroyed()) w.webContents.send("doc:changed", state(s));
}

export async function openDoc(path: string, password?: string): Promise<any> {
  if (!access.canRead(path)) throw new EngineError("ACCESS_DENIED", "Access to this file was not granted.");
  if (!existsSync(path)) throw new EngineError("FILE_NOT_FOUND", "The file no longer exists.");
  const probe = await enginePool.queryCall("doc.probe", { path, password });
  if (!probe.ok) return { needsPassword: !!probe.needsPassword, code: probe.code, message: probe.message };
  const id = newToken();
  const dir = join(appTempRoot(), `doc-${id}`);
  mkdirSync(dir, { recursive: true });
  const s: Session = { id, originalPath: path, password, revisions: [path], actions: ["Opened"], index: 0, savedIndex: 0, dir, tokens: new Map() };
  sessions.set(id, s);
  addRecent(path, probe.pages);
  const info = await enginePool.queryCall("doc.info", { path, password }).catch(() => null);
  return { state: state(s), info, repaired: probe.repaired };
}

export async function queryDoc(id: string, method: string, params: Record<string, any>): Promise<any> {
  if (!QUERY_METHODS.has(method)) throw new EngineError("INVALID_ARGUMENT", `Unsupported query: ${method}`);
  const s = get(id);
  const p: Record<string, any> = { ...params, path: s.revisions[s.index], password: s.password };
  if (method === "compare.run") {
    delete p.path;
    p.pathA = s.revisions[s.index];
    p.passwordA = s.password;
  }
  checkParams(p);
  return enginePool.queryCall(method, p);
}

export async function applyEdit(id: string, method: string, params: Record<string, any>, label: string): Promise<any> {
  if (!EDIT_METHODS.has(method)) throw new EngineError("INVALID_ARGUMENT", `Unsupported edit: ${method}`);
  const s = get(id);
  const current = s.revisions[s.index];
  const output = join(s.dir, `rev-${s.revisions.length}-${Date.now().toString(36)}.pdf`);
  let p: Record<string, any>;
  if (method === "pages.assemble") {
    const sources = { ...(params.sources || {}) };
    sources.A = { path: current, password: s.password };
    p = { ...params, sources, output, overwrite: true };
  } else {
    p = { ...params, path: current, password: s.password, output, overwrite: true };
  }
  checkParams(p);
  const result = await enginePool.editCall(method, p);
  const out = result?.output;
  if (!out || !existsSync(out)) throw new EngineError("VALIDATION_FAILED", "The edit did not produce a document.");
  // Drop redo history beyond the current revision.
  for (let k = s.index + 1; k < s.revisions.length; k++) dropRevision(s, k);
  s.revisions = s.revisions.slice(0, s.index + 1);
  s.actions = s.actions.slice(0, s.index + 1);
  s.revisions.push(out);
  s.actions.push(label || method);
  s.index = s.revisions.length - 1;
  broadcast(s);
  return { state: state(s), result };
}

function dropRevision(s: Session, k: number) {
  const p = s.revisions[k];
  const t = s.tokens.get(k);
  if (t) tokenToPath.delete(t);
  s.tokens.delete(k);
  if (p && p !== s.originalPath && p.startsWith(s.dir)) {
    try {
      unlinkSync(p);
    } catch {
      /* ignore */
    }
  }
}

export function undo(id: string): DocState {
  const s = get(id);
  if (s.index > 0) s.index--;
  broadcast(s);
  return state(s);
}

export function redo(id: string): DocState {
  const s = get(id);
  if (s.index < s.revisions.length - 1) s.index++;
  broadcast(s);
  return state(s);
}

function atomicCopy(src: string, dest: string) {
  const tmp = join(dirname(dest), `.${basename(dest)}.nayan-saving`);
  copyFileSync(src, tmp);
  const a = statSync(src).size;
  const b = statSync(tmp).size;
  if (a !== b) {
    unlinkSync(tmp);
    throw new EngineError("VALIDATION_FAILED", "The saved copy is incomplete.");
  }
  renameSync(tmp, dest);
}

export async function save(win: BrowserWindow, id: string): Promise<{ state: DocState; needsSaveAs?: boolean; cancelled?: boolean }> {
  const s = get(id);
  if (!s.savedPath) return { state: state(s), needsSaveAs: true };
  if (s.index === s.savedIndex && existsSync(s.savedPath)) return { state: state(s) };
  if (sameFile(s.savedPath, s.originalPath) && !(await confirmOverwriteOriginal(win, s))) return { state: state(s), cancelled: true };
  atomicCopy(s.revisions[s.index], s.savedPath);
  s.savedIndex = s.index;
  access.allowInput(s.savedPath);
  broadcast(s);
  return { state: state(s) };
}

/**
 * Replacing the file the user opened is allowed, but only knowingly: ask once
 * per session (unless switched off in Settings), never on every save.
 */
async function confirmOverwriteOriginal(win: BrowserWindow, s: Session): Promise<boolean> {
  if (s.overwriteConfirmed || getSettings().allowOverwriteOriginal) return true;
  const t = testAnswer("overwriteOriginal");
  const response = t.hit ? Number(t.value) : (await dialog.showMessageBox(win, {
    type: "warning",
    buttons: ["Replace original", "Cancel"],
    defaultId: 1,
    cancelId: 1,
    title: "Replace the original document?",
    message: `"${basename(s.originalPath)}" is the file you opened. Replace it with your edited version?`,
    detail: "NayanPDF will not ask again for this document until you close it. To keep the original, choose Cancel and save under a different name.",
  })).response;
  if (response !== 0) return false;
  s.overwriteConfirmed = true;
  return true;
}

export async function saveAs(win: BrowserWindow, id: string): Promise<{ state: DocState; cancelled?: boolean }> {
  const s = get(id);
  // Offer the document's own name, as Acrobat does; the file is only replaced
  // after the user confirms it.
  const base = s.savedPath || s.originalPath;
  const name = extname(base).toLowerCase() === ".pdf" ? basename(base) : `${basename(base, extname(base))}.pdf`;
  const suggested = join(getSettings().defaultOutputDir || dirname(base), name);
  const t = testAnswer("saveAs", suggested);
  const r = t.hit ? { canceled: !t.value, filePath: t.value as string } : await dialog.showSaveDialog(win, {
    title: "Save PDF as",
    defaultPath: suggested,
    filters: [{ name: "PDF documents", extensions: ["pdf"] }],
    properties: ["showOverwriteConfirmation"],
  });
  if (r.canceled || !r.filePath) return { state: state(s), cancelled: true };
  let target = r.filePath.toLowerCase().endsWith(".pdf") ? r.filePath : r.filePath + ".pdf";
  if (sameFile(target, s.originalPath) && !(await confirmOverwriteOriginal(win, s))) return { state: state(s), cancelled: true };
  access.allowOutputDir(dirname(target));
  atomicCopy(s.revisions[s.index], target);
  s.savedPath = target;
  s.savedIndex = s.index;
  access.allowInput(target);
  addRecent(target);
  broadcast(s);
  return { state: state(s) };
}

export function closeDoc(id: string): void {
  const s = sessions.get(id);
  if (!s) return;
  for (const t of s.tokens.values()) tokenToPath.delete(t);
  sessions.delete(id);
  cleanupDir(s.dir);
}

export function currentPath(id: string): { path: string; password?: string } {
  const s = get(id);
  return { path: s.revisions[s.index], password: s.password };
}

/** Open session ids (used by the packaged-app self-check). */
export function sessionIds(): string[] {
  return [...sessions.keys()];
}

export function sessionState(id: string): DocState {
  return state(get(id));
}

export function dirtySessions(): Session[] {
  return [...sessions.values()].filter((s) => s.index !== s.savedIndex);
}

function sameFile(a: string, b: string): boolean {
  const n = (p: string) => (process.platform === "win32" ? p.toLowerCase() : p);
  return n(a) === n(b);
}

// ------------------------------------------------------------- nayan-doc:// protocol
// Serves only revision files of open sessions, by random token, with HTTP range
// support so PDF.js can load large documents incrementally.
export function registerDocProtocol() {
  protocol.handle("nayan-doc", async (request) => {
    const url = new URL(request.url);
    const token = url.pathname.split("/").filter(Boolean)[0];
    const file = token ? tokenToPath.get(token) : undefined;
    if (!file || !existsSync(file)) return new Response("Not found", { status: 404 });
    const size = statSync(file).size;
    const headers: Record<string, string> = {
      "Content-Type": "application/pdf",
      "Accept-Ranges": "bytes",
      "Cache-Control": "no-store",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Expose-Headers": "Accept-Ranges, Content-Range, Content-Length",
    };
    const range = request.headers.get("Range");
    if (range) {
      const m = /bytes=(\d*)-(\d*)/.exec(range);
      if (m) {
        let start = m[1] ? parseInt(m[1], 10) : 0;
        let end = m[2] ? parseInt(m[2], 10) : size - 1;
        if (!m[1] && m[2]) {
          start = Math.max(0, size - parseInt(m[2], 10));
          end = size - 1;
        }
        end = Math.min(end, size - 1);
        if (start > end || start >= size) {
          return new Response(null, { status: 416, headers: { ...headers, "Content-Range": `bytes */${size}` } });
        }
        const stream = Readable.toWeb(createReadStream(file, { start, end })) as ReadableStream;
        return new Response(stream, {
          status: 206,
          headers: { ...headers, "Content-Range": `bytes ${start}-${end}/${size}`, "Content-Length": String(end - start + 1) },
        });
      }
    }
    const stream = Readable.toWeb(createReadStream(file)) as ReadableStream;
    return new Response(stream, { status: 200, headers: { ...headers, "Content-Length": String(size) } });
  });
}
