// NayanPDF main process.
import { app, BrowserWindow, dialog, Menu, protocol, screen, session, shell } from "electron";
import { existsSync, readdirSync, rmSync, statSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { extname, join, normalize, resolve, sep } from "node:path";
import { access } from "./access";
import * as docs from "./docs";
import { enginePool } from "./engine";
import { jobs } from "./jobs";
import { registerIpc } from "./ipc";
import { log } from "./log";
import { appTempBase, appTempRoot, preloadPath, rendererDir } from "./paths";
import { getSettings } from "./store";
import { testAnswer } from "./testhooks";

// Automated UI tests use a throw-away profile so they never touch a real user's
// settings or saved signatures. Ignored by the installed (packaged) application.
if (!app.isPackaged && process.env.NAYANPDF_TEST_USERDATA) app.setPath("userData", process.env.NAYANPDF_TEST_USERDATA);

// ---------------------------------------------------------------- hardening
// Linux test containers run as root, where Chromium's sandbox cannot start; this
// relaxation applies only to unpackaged development/test runs on Linux.
const ROOT_LINUX_DEV = process.platform === "linux" && !app.isPackaged && typeof process.getuid === "function" && process.getuid() === 0;
if (ROOT_LINUX_DEV) app.commandLine.appendSwitch("no-sandbox");
else app.enableSandbox();
// No background network activity of any kind.
for (const sw of [
  "disable-background-networking",
  "disable-component-update",
  "disable-domain-reliability",
  "disable-sync",
  "no-pings",
  "disable-client-side-phishing-detection",
  "disable-default-apps",
  "no-first-run",
]) {
  app.commandLine.appendSwitch(sw);
}
app.commandLine.appendSwitch("disable-features", "AutofillServerCommunication,MediaRouter,OptimizationHints,Translate,SpareRendererForSitePerProcess,CertificateTransparencyComponentUpdater");
if (process.env.NAYANPDF_SOFTWARE_GL === "1") app.disableHardwareAcceleration();

protocol.registerSchemesAsPrivileged([
  { scheme: "app", privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } },
  { scheme: "nayan-doc", privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true } },
]);

const DEV = process.argv.includes("--dev") && !app.isPackaged;
let mainWindow: BrowserWindow | null = null;
const pendingOpen: string[] = [];

function pdfArgs(argv: string[]): string[] {
  return argv.filter((a) => !a.startsWith("-") && extname(a).toLowerCase() === ".pdf" && existsSync(a) && statSync(a).isFile()).map((a) => resolve(a));
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", (_e, argv) => {
    const files = pdfArgs(argv.slice(1));
    files.forEach((f) => access.allowInput(f));
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
      if (files.length) mainWindow.webContents.send("app:openFiles", files);
    }
  });
}

const MIME: Record<string, string> = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css",
  ".json": "application/json", ".wasm": "application/wasm", ".svg": "image/svg+xml", ".png": "image/png",
  ".gif": "image/gif", ".jpg": "image/jpeg", ".woff2": "font/woff2", ".woff": "font/woff", ".ttf": "font/ttf",
  ".pfb": "application/octet-stream", ".bcmap": "application/octet-stream", ".icc": "application/vnd.iccprofile",
};

function registerAppProtocol() {
  const root = rendererDir();
  protocol.handle("app", async (request) => {
    const url = new URL(request.url);
    let rel = decodeURIComponent(url.pathname);
    if (rel === "/" || rel === "") rel = "/index.html";
    const file = normalize(join(root, rel));
    if (!file.startsWith(root + sep) && file !== root) return new Response("Forbidden", { status: 403 });
    if (!existsSync(file) || !statSync(file).isFile()) return new Response("Not found", { status: 404 });
    // Served straight from the app bundle (never through the network stack).
    const body = await readFile(file);
    const headers = new Headers({ "Content-Type": MIME[extname(file).toLowerCase()] || "application/octet-stream", "Cache-Control": "no-cache" });
    if (file.endsWith(".html")) {
      headers.set(
        "Content-Security-Policy",
        [
          "default-src 'self'",
          "script-src 'self' 'wasm-unsafe-eval'",
          "style-src 'self' 'unsafe-inline'",
          "img-src 'self' data: blob: nayan-doc:",
          "font-src 'self' data:",
          "connect-src 'self' nayan-doc: data: blob:",
          "worker-src 'self' blob:",
          "object-src 'none'",
          "base-uri 'none'",
          "form-action 'none'",
          "frame-src 'none'",
        ].join("; "),
      );
    }
    return new Response(body, { status: 200, headers });
  });
}

// Chromium's spell checker fetches dictionaries from Google on Linux as soon as a
// session exists. Every session is hardened the moment it is created: spell
// checking off, no languages, and any dictionary URL pointed at a dead local port.
function hardenSession(ses: Electron.Session) {
  try {
    ses.setSpellCheckerEnabled(false);
    ses.setSpellCheckerDictionaryDownloadURL("http://127.0.0.1:9/");
    if (process.platform === "linux") ses.setSpellCheckerLanguages([]);
  } catch {
    /* not supported on this platform */
  }
}
app.on("session-created", hardenSession);

function lockDownDefaultSession() {
  const ses = session.defaultSession;
  hardenSession(ses);
  ses.setPermissionRequestHandler((_wc, _perm, cb) => cb(false));
  ses.setPermissionCheckHandler(() => false);
  const devUrl = process.env.NAYANPDF_DEV_URL;
  ses.webRequest.onBeforeRequest((details, cb) => {
    const u = details.url;
    const allowed =
      u.startsWith("app://") ||
      u.startsWith("nayan-doc://") ||
      u.startsWith("data:") ||
      u.startsWith("blob:") ||
      u.startsWith("devtools://") ||
      (devUrl ? u.startsWith(devUrl) : false);
    if (!allowed) log.warn(`blocked request to ${u.split("?")[0].slice(0, 80)}`);
    cb({ cancel: !allowed });
  });
}

function createWindow() {
  // Fit the screen: laptops at 1366x768 (or CI desktops at 1024x768) get a
  // maximised window instead of one larger than the display.
  const wa = screen.getPrimaryDisplay().workAreaSize;
  const fits = wa.width >= 1440 && wa.height >= 920;
  mainWindow = new BrowserWindow({
    width: Math.min(1440, wa.width),
    height: Math.min(920, wa.height),
    minWidth: 1000,
    minHeight: 640,
    title: "NayanPDF",
    show: false,
    backgroundColor: "#0f172a",
    autoHideMenuBar: true,
    icon: app.isPackaged ? join(process.resourcesPath, "icon.png") : join(__dirname, "..", "..", "build", "icon.png"),
    webPreferences: {
      preload: preloadPath(),
      contextIsolation: true,
      nodeIntegration: false,
      nodeIntegrationInWorker: false,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
      spellcheck: false,
      devTools: !app.isPackaged,
    },
  });
  mainWindow.once("ready-to-show", () => {
    if (!fits) mainWindow?.maximize();
    mainWindow?.show();
  });
  const url = process.env.NAYANPDF_DEV_URL || "app://nayan/index.html";
  mainWindow.loadURL(url);
  mainWindow.webContents.on("did-finish-load", () => {
    if (pendingOpen.length && mainWindow) {
      mainWindow.webContents.send("app:openFiles", pendingOpen.splice(0));
    }
  });
  mainWindow.on("close", async (e) => {
    const dirty = docs.dirtySessions();
    if (dirty.length && !(mainWindow as any).__forceClose) {
      e.preventDefault();
      const t = testAnswer("closeConfirm");
      const r = t.hit ? { response: t.value as number } : await dialog.showMessageBox(mainWindow!, {
        type: "warning",
        buttons: ["Discard changes and exit", "Cancel"],
        defaultId: 1,
        cancelId: 1,
        title: "Unsaved changes",
        message: `${dirty.length} document(s) have unsaved changes.`,
        detail: "Your original files are untouched. Edits that were not saved will be lost.",
      });
      if (r.response === 0) {
        (mainWindow as any).__forceClose = true;
        mainWindow!.close();
      }
    }
  });
  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

app.on("web-contents-created", (_e, contents) => {
  contents.setWindowOpenHandler(() => ({ action: "deny" }));
  contents.on("will-navigate", (e, url) => {
    const dev = process.env.NAYANPDF_DEV_URL;
    if (!url.startsWith("app://") && !(dev && url.startsWith(dev))) e.preventDefault();
  });
  contents.on("will-attach-webview", (e) => e.preventDefault());
  if (app.isPackaged) {
    contents.on("before-input-event", (e, input) => {
      if ((input.control && input.shift && input.key.toLowerCase() === "i") || input.key === "F12") e.preventDefault();
    });
  }
});

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  lockDownDefaultSession();
  registerAppProtocol();
  docs.registerDocProtocol();
  registerIpc();
  getSettings();
  const files = pdfArgs(process.argv.slice(app.isPackaged ? 1 : 2));
  files.forEach((f) => access.allowInput(f));
  pendingOpen.push(...files);
  cleanupOldTemp();
  createWindow();
  enginePool.warmUp();
  if (process.env.NAYANPDF_SMOKE === "1") runSmoke();
});

app.on("window-all-closed", () => {
  app.quit();
});

app.on("before-quit", () => {
  jobs.shutdown();
  enginePool.shutdown();
});

app.on("will-quit", () => {
  if (getSettings().clearTempOnExit) {
    try {
      rmSync(appTempRoot(), { recursive: true, force: true });
    } catch {
      /* files in use: removed on next start */
    }
  }
});

function cleanupOldTemp() {
  // Remove temp folders left by earlier sessions that are no longer running.
  const base = appTempBase();
  try {
    for (const name of readdirSync(base)) {
      const m = /^s(\d+)$/.exec(name);
      if (!m || Number(m[1]) === process.pid) continue;
      let alive = false;
      try {
        process.kill(Number(m[1]), 0);
        alive = true;
      } catch {
        alive = false;
      }
      if (!alive) rmSync(join(base, name), { recursive: true, force: true });
    }
  } catch {
    /* nothing to clean */
  }
}

// Used by automated acceptance tests (CI): loads the UI, captures screenshots,
// runs the engine self-test through the packaged app's own resource paths and,
// when a PDF was passed on the command line, checks that it is displayed.
async function runSmoke() {
  const out = process.env.NAYANPDF_SMOKE_OUT || join(app.getPath("temp"), "nayanpdf-smoke");
  const { mkdirSync, writeFileSync } = await import("node:fs");
  mkdirSync(out, { recursive: true });
  const result: any = { started: new Date().toISOString(), packaged: app.isPackaged };
  const hadDoc = pendingOpen.length > 0 || process.argv.some((a) => a.toLowerCase().endsWith(".pdf"));
  try {
    await new Promise((r) => setTimeout(r, 5000));
    writeFileSync(join(out, "home.png"), (await mainWindow!.webContents.capturePage()).toPNG());
    result.title = mainWindow!.getTitle();
    result.ok = true;
    const { EngineProcess } = await import("./engine");
    const p = new EngineProcess("smoke");
    try {
      result.engine = await p.call("selftest.run", { quick: true });
    } catch (e: any) {
      result.engine = { ok: false, error: e.message };
    } finally {
      p.stop();
    }
    if (hadDoc) {
      let pages = 0;
      for (let i = 0; i < 30 && pages === 0; i++) {
        await new Promise((r) => setTimeout(r, 1000));
        pages = await mainWindow!.webContents.executeJavaScript("document.querySelectorAll('.pdfViewer .page canvas').length");
      }
      await new Promise((r) => setTimeout(r, 1500));
      writeFileSync(join(out, "document.png"), (await mainWindow!.webContents.capturePage()).toPNG());
      const tabs = await mainWindow!.webContents.executeJavaScript("Array.from(document.querySelectorAll('.tab .name')).map(e => e.textContent)");
      result.document = { ok: pages > 0, detail: `${pages} rendered page canvas(es); tabs: ${JSON.stringify(tabs)}` };
      // Edit the open document through the same guarded path the UI uses (access
      // checks, temp revisions, edit worker), then undo. Nothing is saved.
      const id = docs.sessionIds()[0];
      if (id) {
        try {
          await docs.applyEdit(id, "pages.rotate", { pages: "1", angle: 90 }, "Self-check: rotate");
          const r = await docs.applyEdit(id, "annot.apply", { ops: [{ op: "add", page: 1, kind: "note", point: [72, 72], contents: "self-check" }] }, "Self-check: note");
          docs.undo(id);
          docs.undo(id);
          result.edit = { ok: r.state.revisions === 3, detail: `${r.state.revisions - 1} edits applied as temporary revisions and undone` };
        } catch (e: any) {
          result.edit = { ok: false, detail: e.message };
        }
      } else {
        result.edit = { ok: false, detail: "no open document session" };
      }
    }
  } catch (e: any) {
    result.ok = false;
    result.error = e.message;
  }
  writeFileSync(join(out, "smoke.json"), JSON.stringify(result, null, 2));
  if (process.env.NAYANPDF_SMOKE_KEEP !== "1") app.exit(result.ok ? 0 : 1);
}

process.on("uncaughtException", (err) => log.error(`uncaught: ${err.stack || err.message}`));
void shell;
