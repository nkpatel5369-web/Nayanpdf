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
import { htmlToPdf, printPlan, PrintJob } from "./output";
import { appTempBase, appTempRoot } from "./paths";
import * as store from "./store";
import { testAnswer } from "./testhooks";
import { cancelScan, listScanners, scan } from "./scan";

const FILTERS: Record<string, Electron.FileFilter[]> = {
  pdf: [{ name: "PDF documents", extensions: ["pdf"] }],
  images: [{ name: "Images", extensions: ["png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp", "jfif"] }],
  office: [{ name: "Office documents", extensions: ["doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "docm", "xlsm", "pptm"] }],
  text: [{ name: "Text files", extensions: ["txt", "text", "log", "md", "csv"] }],
  html: [{ name: "HTML files", extensions: ["html", "htm", "xhtml"] }],
  pfx: [{ name: "Certificates (PKCS#12)", extensions: ["pfx", "p12"] }],
  cert: [{ name: "Certificates", extensions: ["cer", "crt", "pem", "der"] }],
  dll: [{ name: "PKCS#11 driver", extensions: ["dll", "so", "dylib"] }],
  xfdf: [{ name: "Comments (XFDF)", extensions: ["xfdf"] }],
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
  "security.integrity", "util.pdfaCheck", "blank.detect", "redact.search", "action.validate",
]);

// Files X PDF will hand to the operating system to open. Executables and
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
      name: "X PDF",
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
    const t = testAnswer("openFiles");
    if (t.hit) {
      (t.value as string[]).forEach((f) => access.allowInput(f));
      return t.value;
    }
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
    const t = testAnswer("openFolder");
    if (t.hit) {
      access.allowOutputDir(t.value);
      return t.value;
    }
    const r = await dialog.showOpenDialog(win(e), { title: p.title || "Choose folder", properties: ["openDirectory", "createDirectory"] });
    if (r.canceled || !r.filePaths[0]) return null;
    access.allowOutputDir(r.filePaths[0]);
    return r.filePaths[0];
  },
  "dialog:pickOutputDir": async (e, payload) => {
    const p = obj(payload);
    const t = testAnswer("pickOutputDir");
    if (t.hit) {
      access.allowOutputDir(t.value);
      if (p.setDefault) store.setDefaultOutputDir(t.value);
      return t.value;
    }
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
    const t = testAnswer("saveFile");
    if (t.hit) {
      access.allowOutputDir(dirname(t.value));
      return t.value;
    }
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
    if (!OPENABLE.has(extname(p).toLowerCase())) throw new EngineError("NOT_SUPPORTED", "For safety, X PDF only opens document and image files. Use 'Show in folder' instead.");
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
  "print:printers": async (e) => {
    const t = testAnswer("printers");
    if (t.hit) return t.value;
    const list = await win(e).webContents.getPrintersAsync();
    return list.map((p) => ({ name: p.name, displayName: p.displayName || p.name, description: p.description || "" }));
  },
  "doc:printPlan": async (e, payload) => {
    const p = obj(payload);
    const { path, password } = docs.currentPath(str(p.sessionId, "session"));
    const probe = await enginePool.queryCall("doc.probe", { path, password });
    const job = sanitizePrintJob(obj(p.job), Number(probe.pages) || 0);
    const t = testAnswer("printToPDF");
    if (t.hit && typeof t.value === "string") access.allowOutputDir(dirname(t.value));
    return printPlan(win(e), path, password, job, t.hit ? String(t.value) : undefined);
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
  "scan:devices": () => listScanners(),
  "scan:start": (e, payload) => {
    const p = obj(payload);
    const source = p.source === "feeder" ? "feeder" : "flatbed";
    const colour = p.colour === "gray" || p.colour === "bw" ? p.colour : "color";
    const dpi = [100, 150, 200, 300, 400, 600].includes(Number(p.dpi)) ? Number(p.dpi) : 300;
    return scan({ deviceId: str(p.deviceId, "scanner", 300), source, colour, dpi }, BrowserWindow.fromWebContents(e.sender));
  },
  "scan:cancel": () => cancelScan(),
  "actions:list": () => store.listActions(),
  "actions:save": (_e, payload) => {
    const p = obj(payload);
    const steps = Array.isArray(p.steps) ? p.steps : [];
    if (!steps.length || steps.length > 20 || !steps.every((s: any) => s && typeof s === "object" && typeof s.op === "string")) {
      throw new EngineError("INVALID_ARGUMENT", "An action needs 1 to 20 steps.");
    }
    if (JSON.stringify(steps).length > 64_000) throw new EngineError("INVALID_ARGUMENT", "The action is too large.");
    return store.saveAction({
      id: typeof p.id === "string" ? p.id.slice(0, 20) : undefined,
      name: str(p.name, "name", 80).trim() || "Action",
      description: typeof p.description === "string" ? p.description.slice(0, 300) : "",
      steps,
      naming: typeof p.naming === "string" && p.naming.trim() ? p.naming.slice(0, 120) : "{name}_processed",
    });
  },
  "actions:delete": (_e, payload) => store.deleteAction(str(payload, "id", 20)),
  "convert:htmlToPdf": (_e, payload) => {
    const p = obj(payload);
    return htmlToPdf(str(p.path, "path"), str(p.output, "output"), { pageSize: p.pageSize, landscape: !!p.landscape, margin: Number(p.margin), overwrite: !!p.overwrite });
  },
  "window:confirm": async (e, payload) => {
    const p = obj(payload);
    const buttons: string[] = Array.isArray(p.buttons) && p.buttons.length ? p.buttons.map(String).slice(0, 3) : ["OK", "Cancel"];
    const t = testAnswer("confirm");
    if (t.hit) return t.value;
    const r = await dialog.showMessageBox(win(e), {
      type: p.type === "warning" ? "warning" : "question",
      title: String(p.title || "X PDF"),
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


/** Validate a print job coming from the renderer before anything is rendered or printed. */
function sanitizePrintJob(j: Record<string, any>, pageCount: number): PrintJob {
  const bad = (m: string): never => {
    throw Object.assign(new Error(`Invalid print settings: ${m}`), { code: "INVALID_ARGUMENT" });
  };
  const num = (v: any, lo: number, hi: number, name: string) => {
    const n = Number(v);
    if (!Number.isFinite(n) || n < lo || n > hi) bad(name);
    return n;
  };
  const oneOf = <T extends string>(v: any, set: readonly T[], name: string): T => (set.includes(v) ? v : bad(name));
  if (!Array.isArray(j.sheets) || j.sheets.length === 0 || j.sheets.length > 20000) bad("sheets");
  const sheets = (j.sheets as any[]).map((sheet) => {
    if (!Array.isArray(sheet) || sheet.length === 0 || sheet.length > 16) bad("sheet");
    return sheet.map((it: any) => ({
      page: Math.round(num(it?.page, 1, pageCount, "page")),
      x: num(it.x, -20000, 20000, "x"),
      y: num(it.y, -20000, 20000, "y"),
      w: num(it.w, 0.1, 20000, "w"),
      h: num(it.h, 0.1, 20000, "h"),
      rotate: it.rotate === 90 ? (90 as const) : (0 as const),
      border: !!it.border,
    }));
  });
  return {
    printer: typeof j.printer === "string" && j.printer.length < 300 ? j.printer : "",
    copies: Math.round(num(j.copies ?? 1, 1, 999, "copies")),
    collate: j.collate !== false,
    duplex: oneOf(j.duplex ?? "simplex", ["simplex", "longEdge", "shortEdge"] as const, "duplex"),
    grayscale: !!j.grayscale,
    dpi: Math.round(num(j.dpi ?? 300, 100, 600, "dpi")),
    annots: oneOf(j.annots ?? "all", ["all", "none", "forms"] as const, "annots"),
    paper: oneOf(j.paper ?? "A4", ["A3", "A4", "A5", "Letter", "Legal"] as const, "paper"),
    sheetW: num(j.sheetW, 100, 5000, "sheet width"),
    sheetH: num(j.sheetH, 100, 5000, "sheet height"),
    landscape: !!j.landscape,
    sheets,
    systemDialog: !!j.systemDialog,
  };
}
