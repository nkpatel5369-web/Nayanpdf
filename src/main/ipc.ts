// IPC handlers. Every handler validates its input; the renderer can only reach
// the operations listed here and the engine methods allow-listed in jobs.ts /
// docs.ts / ENGINE_QUERIES below.
import { app, BrowserWindow, dialog, ipcMain, shell, IpcMainInvokeEvent } from "electron";
import { existsSync, readdirSync, rmSync, statSync } from "node:fs";
import { basename, dirname, extname, join } from "node:path";
import { INVOKE_CHANNELS, IpcResult, Settings } from "../shared/ipc";
import { access, AccessDenied, checkParams } from "./access";
import * as docs from "./docs";
import { EngineError, EngineProcess, enginePool } from "./engine";
import { jobs, cleanupDir } from "./jobs";
import { log } from "./log";
import { htmlToPdf, printDocument } from "./output";
import { appTempBase, appTempRoot } from "./paths";
import * as store from "./store";

const FILTERS: Record<string, Electron.FileFilter[]> = {
  pdf: [{ name: "PDF documents", extensions: ["pdf"] }],
  images: [{ name: "Images", extensions: ["png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp", "jfif"] }],
  office: [{ name: "Office documents", extensions: ["doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "docm", "xlsm", "pptm"] }],
  text: [{ name: "Text files", extensions: ["txt", "text", "log", "md", "csv"] }],
  html: [{ name: "HTML files", extensions: ["html", "htm", "xhtml"] }],
  pfx: [{ name: "Certificates (PKCS#12)", extensions: ["pfx", "p12"] }],
  cert: [{ name: "Certificates", extensions: ["cer", "crt", "pem", "der"] }],
  dll: [{ name: "PKCS#11 driver", extensions: ["dll", "so", "dylib"] }],
  any: [
    { name: "Supported files", extensions: ["pdf", "png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "txt", "html", "htm"] },
  ],
  all: [{ name: "All files", extensions: ["*"] }],
};

// Read-only engine queries on files the user has granted.
const ENGINE_QUERIES = new Set([
  "doc.probe", "doc.info", "pages.thumbnail", "forms.list", "sign.certificateInfo", "sign.pkcs11List", "ocr.languages",
  "engine.capabilities", "redact.patterns", "stamp.presets", "sign.typedImage", "sign.cleanImage", "compare.run",
  "security.inspect", "sign.verify", "compress.analyze", "meta.get", "attachments.list", "bookmarks.get", "doc.pageSizes",
  "security.integrity", "util.pdfaCheck", "blank.detect", "redact.search",
]);

// Files NayanPDF will hand to the operating system to open. Executables and
// scripts (e.g. extracted attachments) are never launched.
const OPENABLE = new Set([".pdf", ".docx", ".doc", ".xlsx", ".xls", ".csv", ".pptx", ".ppt", ".txt", ".html", ".htm", ".md",
  ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".webp", ".gif", ".odt", ".ods", ".odp", ".rtf"]);

type Handler = (e: IpcMainInvokeEvent, payload: any) => Promise<any> | any;

function win(e: IpcMainInvokeEvent): BrowserWindow {
  return BrowserWindow.fromWebContents(e.sender)!;
}

function str(v: any, name: string, max = 4000): string {
  if (typeof v !== "string" || v.length === 0 || v.length > max) throw new EngineError("INVALID_ARGUMENT", `Invalid ${name}.`);
  return v;
}

function obj(v: any): Record<string, any> {
  if (v === undefined || v === null) return {};
  if (typeof v !== "object" || Array.isArray(v)) throw new EngineError("INVALID_ARGUMENT", "Invalid parameters.");
  return v;
}

function dirSize(d: string): number {
  let total = 0;
  try {
    for (const name of readdirSync(d)) {
      const p = join(d, name);
      try {
        const st = statSync(p);
        total += st.isDirectory() ? dirSize(p) : st.size;
      } catch {
        /* ignore */
      }
    }
  } catch {
    /* ignore */
  }
  return total;
}

const handlers: Record<string, Handler> = {
  "app:info": async () => {
    const caps = await enginePool.queryCall("engine.capabilities", {}).catch((e) => ({ error: e.message }));
    return {
      name: "NayanPDF",
      version: app.getVersion(),
      electron: process.versions.electron,
      chrome: process.versions.chrome,
      platform: `${process.platform} ${process.arch}`,
      capabilities: caps,
      signaturesProtected: store.signaturesProtected(),
      userData: app.getPath("userData"),
    };
  },
  "app:selftest": async () => {
    const p = new EngineProcess("selftest");
    try {
      return await p.call("selftest.run", {});
    } finally {
      p.stop();
      cleanupDir(p.tmp);
    }
  },
  "dialog:openFiles": async (e, payload) => {
    const p = obj(payload);
    const kind = typeof p.kind === "string" && FILTERS[p.kind] ? p.kind : "pdf";
    const r = await dialog.showOpenDialog(win(e), {
      title: p.title || "Open",
      properties: p.multi ? ["openFile", "multiSelections"] : ["openFile"],
      filters: [...FILTERS[kind], ...FILTERS.all],
    });
    if (r.canceled) return [];
    r.filePaths.forEach((f) => access.allowInput(f));
    return r.filePaths;
  },
  "dialog:openFolder": async (e, payload) => {
    const p = obj(payload);
    const r = await dialog.showOpenDialog(win(e), { title: p.title || "Choose folder", properties: ["openDirectory", "createDirectory"] });
    if (r.canceled || !r.filePaths[0]) return null;
    access.allowOutputDir(r.filePaths[0]);
    return r.filePaths[0];
  },
  "dialog:pickOutputDir": async (e, payload) => {
    const p = obj(payload);
    const r = await dialog.showOpenDialog(win(e), { title: "Choose output folder", properties: ["openDirectory", "createDirectory"] });
    if (r.canceled || !r.filePaths[0]) return null;
    access.allowOutputDir(r.filePaths[0]);
    if (p.setDefault) store.setDefaultOutputDir(r.filePaths[0]);
    return r.filePaths[0];
  },
  "dialog:saveFile": async (e, payload) => {
    const p = obj(payload);
    const kind = typeof p.kind === "string" && FILTERS[p.kind] ? p.kind : undefined;
    const ext = typeof p.extension === "string" ? p.extension.replace(/^\./, "") : "pdf";
    const r = await dialog.showSaveDialog(win(e), {
      title: p.title || "Save as",
      defaultPath: typeof p.defaultPath === "string" ? p.defaultPath : undefined,
      filters: kind ? FILTERS[kind] : [{ name: ext.toUpperCase(), extensions: [ext] }],
      properties: ["showOverwriteConfirmation", "createDirectory"],
    });
    if (r.canceled || !r.filePath) return null;
    let fp = r.filePath;
    if (!extname(fp)) fp += "." + ext;
    access.allowOutputDir(dirname(fp));
    return fp;
  },
  "files:scanFolder": async (_e, payload) => {
    const p = obj(payload);
    const dir = str(p.dir, "folder");
    if (!access.canReadDir(dir)) throw new AccessDenied("Choose the folder with the folder picker first.");
    const exts: string[] = Array.isArray(p.extensions) ? p.extensions.map((x: string) => "." + String(x).toLowerCase()) : [".pdf"];
    const out: string[] = [];
    const walk = (d: string, depth: number) => {
      if (depth > 8 || out.length > 5000) return;
      for (const name of readdirSync(d)) {
        const fp = join(d, name);
        let st;
        try {
          st = statSync(fp);
        } catch {
          continue;
        }
        if (st.isDirectory()) {
          if (p.recursive) walk(fp, depth + 1);
        } else if (exts.includes(extname(name).toLowerCase())) {
          out.push(fp);
        }
      }
    };
    walk(dir, 0);
    out.forEach((f) => access.allowInput(f));
    return out.sort();
  },
  "files:reveal": (_e, payload) => {
    const p = str(payload, "path");
    if (!access.canRead(p) && !access.canWrite(p)) throw new AccessDenied("Not permitted.");
    shell.showItemInFolder(p);
    return true;
  },
  "files:openWithSystem": async (_e, payload) => {
    const p = str(payload, "path");
    if (!access.canRead(p)) throw new AccessDenied("Not permitted.");
    if (!OPENABLE.has(extname(p).toLowerCase())) throw new EngineError("NOT_SUPPORTED", "For safety, NayanPDF only opens document and image files. Use 'Show in folder' instead.");
    const err = await shell.openPath(p);
    if (err) throw new EngineError("OPERATION_FAILED", err);
    return true;
  },
  "files:exists": (_e, payload) => {
    if (!Array.isArray(payload)) return [];
    return payload.map((p) => typeof p === "string" && (access.canRead(p) || access.canWrite(p)) && existsSync(p));
  },
  "recent:list": () => store.listRecent(),
  "recent:remove": (_e, payload) => {
    store.removeRecent(str(payload, "path"));
    return store.listRecent();
  },
  "recent:clear": () => {
    store.clearRecent();
    return [];
  },
  "settings:get": () => store.getSettings(),
  "settings:set": (_e, payload) => {
    const s = store.setSettings(obj(payload) as Partial<Settings>);
    for (const w of BrowserWindow.getAllWindows()) w.webContents.send("settings:changed", s);
    return s;
  },
  "temp:usage": () => ({ bytes: dirSize(appTempBase()), path: appTempBase() }),
  "temp:clear": () => {
    // Remove temp folders of previous sessions; keep this session's live documents.
    const base = appTempBase();
    const current = basename(appTempRoot());
    let removed = 0;
    try {
      for (const name of readdirSync(base)) {
        if (name === current) continue;
        rmSync(join(base, name), { recursive: true, force: true });
        removed++;
      }
    } catch {
      /* ignore */
    }
    return { removed, bytes: dirSize(base) };
  },
  "doc:open": async (_e, payload) => {
    const p = obj(payload);
    return docs.openDoc(str(p.path, "path"), typeof p.password === "string" ? p.password : undefined);
  },
  "doc:query": (_e, payload) => {
    const p = obj(payload);
    return docs.queryDoc(str(p.sessionId, "session"), str(p.method, "method", 64), obj(p.params));
  },
  "doc:apply": (_e, payload) => {
    const p = obj(payload);
    return docs.applyEdit(str(p.sessionId, "session"), str(p.method, "method", 64), obj(p.params), typeof p.label === "string" ? p.label : "");
  },
  "doc:undo": (_e, payload) => docs.undo(str(obj(payload).sessionId, "session")),
  "doc:redo": (_e, payload) => docs.redo(str(obj(payload).sessionId, "session")),
  "doc:save": (e, payload) => docs.save(win(e), str(obj(payload).sessionId, "session")),
  "doc:saveAs": (e, payload) => docs.saveAs(win(e), str(obj(payload).sessionId, "session")),
  "doc:close": (_e, payload) => {
    docs.closeDoc(str(obj(payload).sessionId, "session"));
    return true;
  },
  "doc:state": (_e, payload) => docs.sessionState(str(obj(payload).sessionId, "session")),
  "doc:preview": async (_e, payload) => {
    // Run a stamping operation on one page of the current revision into a temp
    // file and return a picture of the result. The document is not changed.
    const p = obj(payload);
    const method = str(p.method, "method", 64);
    if (!["stamp.watermark", "stamp.headerFooter", "stamp.pageNumbers", "stamp.bates", "stamp.background"].includes(method)) {
      throw new EngineError("INVALID_ARGUMENT", "Preview is not available for this operation.");
    }
    const { path, password } = docs.currentPath(str(p.sessionId, "session"));
    const page = Number(p.page) || 1;
    const tmpDir = join(appTempRoot(), "preview");
    const out = join(tmpDir, `preview-${Date.now().toString(36)}.pdf`);
    const params: Record<string, any> = { ...obj(p.params), path, password, output: out, overwrite: true };
    if (method !== "stamp.bates") params.pages = String(page);
    checkParams(params);
    const r = await enginePool.editCall(method, params);
    const thumb = await enginePool.queryCall("pages.thumbnail", { path: r.output, page, width: Number(p.width) || 520, password });
    rmSync(r.output, { force: true });
    return thumb;
  },
  "doc:print": async (e, payload) => {
    const p = obj(payload);
    const { path, password } = docs.currentPath(str(p.sessionId, "session"));
    const dpi = Math.max(100, Math.min(600, Number(p.dpi) || store.getSettings().printDpi));
    return printDocument(win(e), path, password, typeof p.pages === "string" && p.pages ? p.pages : undefined, dpi, !!p.grayscale);
  },
  "job:start": (_e, payload) => {
    const p = obj(payload);
    const params = { ...obj(p.params) };
    // Operations on an open document use its current (possibly edited) revision.
    if (typeof params.sessionId === "string") {
      const cur = docs.currentPath(params.sessionId);
      delete params.sessionId;
      params.path = cur.path;
      if (cur.password && params.password === undefined) params.password = cur.password;
    }
    return jobs.start(str(p.method, "method", 64), params, typeof p.label === "string" ? p.label : p.method,
      typeof p.batchId === "string" ? p.batchId : undefined);
  },
  "job:cancel": (_e, payload) => {
    jobs.cancel(str(payload, "job"));
    return true;
  },
  "job:retry": (_e, payload) => jobs.retry(str(payload, "job")),
  "job:list": () => jobs.list(),
  "job:clear": () => {
    jobs.clearFinished();
    return jobs.list();
  },
  "engine:query": (_e, payload) => {
    const p = obj(payload);
    const method = str(p.method, "method", 64);
    if (!ENGINE_QUERIES.has(method)) throw new EngineError("INVALID_ARGUMENT", `Unsupported query: ${method}`);
    const params = obj(p.params);
    checkParams(params);
    return enginePool.queryCall(method, params);
  },
  "signatures:list": () => store.listSignatures(),
  "signatures:save": (_e, payload) => {
    const p = obj(payload);
    const png = str(p.png, "image", 8_000_000);
    if (!/^[A-Za-z0-9+/=]+$/.test(png.replace(/^data:image\/png;base64,/, ""))) throw new EngineError("INVALID_ARGUMENT", "Invalid image.");
    return store.saveSignature({ name: String(p.name || "Signature").slice(0, 60), kind: p.kind === "initials" ? "initials" : "signature", png: png.replace(/^data:image\/png;base64,/, "") });
  },
  "signatures:delete": (_e, payload) => {
    store.deleteSignature(str(payload, "id"));
    return store.listSignatures();
  },
  "convert:htmlToPdf": (_e, payload) => {
    const p = obj(payload);
    return htmlToPdf(str(p.path, "path"), str(p.output, "output"), { pageSize: p.pageSize, landscape: !!p.landscape, margin: Number(p.margin), overwrite: !!p.overwrite });
  },
  "window:confirm": async (e, payload) => {
    const p = obj(payload);
    const buttons: string[] = Array.isArray(p.buttons) && p.buttons.length ? p.buttons.map(String).slice(0, 3) : ["OK", "Cancel"];
    const r = await dialog.showMessageBox(win(e), {
      type: p.type === "warning" ? "warning" : "question",
      title: String(p.title || "NayanPDF"),
      message: String(p.message || ""),
      detail: p.detail ? String(p.detail) : undefined,
      buttons,
      defaultId: buttons.length - 1,
      cancelId: buttons.length - 1,
    });
    return r.response;
  },
};

export function registerIpc() {
  for (const ch of INVOKE_CHANNELS) {
    const h = handlers[ch];
    if (!h) throw new Error(`missing IPC handler ${ch}`);
    ipcMain.handle(ch, async (e, payload): Promise<IpcResult<any>> => {
      // Only accept calls from our own renderer.
      const url = e.senderFrame?.url || "";
      if (!url.startsWith("app://") && !(process.env.NAYANPDF_DEV_URL && url.startsWith(process.env.NAYANPDF_DEV_URL))) {
        return { ok: false, error: { code: "ACCESS_DENIED", message: "Untrusted sender." } };
      }
      try {
        return { ok: true, data: await h(e, payload) };
      } catch (err: any) {
        const code = err?.code || "OPERATION_FAILED";
        if (code === "OPERATION_FAILED") log.error(`ipc ${ch}: ${err?.message}`);
        return { ok: false, error: { code, message: err?.message || String(err), details: err?.details } };
      }
    });
  }
  // Files dropped onto the window: the preload (isolated world) reports real
  // OS paths of dropped File objects; only those become accessible.
  ipcMain.handle("files:registerDropped", (e, payload) => {
    if (!Array.isArray(payload)) return [];
    const ok = payload.filter((p) => typeof p === "string" && p.length < 4000 && existsSync(p) && statSync(p).isFile());
    ok.forEach((p) => access.allowInput(p));
    return ok;
  });
}
