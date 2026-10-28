// Printing and HTML-to-PDF. Both use isolated, offline browser sessions.
import { BrowserWindow, session } from "electron";
import { existsSync, mkdirSync, rmSync, writeFileSync, renameSync } from "node:fs";
import { dirname, join, basename, extname } from "node:path";
import { pathToFileURL } from "node:url";
import { EngineError, EngineProcess, enginePool } from "./engine";
import { appTempRoot } from "./paths";
import { access } from "./access";

function lockDownSession(partition: string, allowDirs: string[]) {
  const ses = session.fromPartition(partition);
  ses.setSpellCheckerEnabled(false);
  ses.webRequest.onBeforeRequest((details, cb) => {
    const u = details.url;
    if (u.startsWith("file://")) {
      try {
        const p = decodeURIComponent(new URL(u).pathname).replace(/^\/([A-Za-z]:)/, "$1");
        const norm = (x: string) => (process.platform === "win32" ? x.replace(/\//g, "\\").toLowerCase() : x);
        const ok = allowDirs.some((d) => norm(p).startsWith(norm(d)));
        return cb({ cancel: !ok });
      } catch {
        return cb({ cancel: true });
      }
    }
    if (u.startsWith("data:") || u.startsWith("blob:") || u.startsWith("devtools:")) return cb({ cancel: false });
    cb({ cancel: true }); // no network
  });
  ses.setPermissionRequestHandler((_wc, _perm, cb) => cb(false));
  return ses;
}

export interface PrintItem {
  page: number;
  x: number;
  y: number;
  w: number;
  h: number;
  rotate: 0 | 90;
  border: boolean;
}

export interface PrintJob {
  printer: string; // "" = the system default printer
  copies: number;
  collate: boolean;
  duplex: "simplex" | "longEdge" | "shortEdge";
  grayscale: boolean;
  dpi: number;
  annots: "all" | "none" | "forms";
  paper: "A3" | "A4" | "A5" | "Letter" | "Legal";
  sheetW: number; // points
  sheetH: number;
  landscape: boolean;
  sheets: PrintItem[][];
  systemDialog: boolean; // let Windows show its own dialog (driver options)
}

/** HTML of the sheets exactly as laid out by the print dialog (sizes in points). */
function sheetsHtml(job: PrintJob, files: Map<number, string>): string {
  const n = (v: number) => Math.round(v * 100) / 100;
  const items = (sheet: PrintItem[]) => sheet.map((it) => {
    const img = it.rotate
      ? `width:${n(it.h)}pt;height:${n(it.w)}pt;transform:translate(-50%,-50%) rotate(90deg)`
      : `width:${n(it.w)}pt;height:${n(it.h)}pt;transform:translate(-50%,-50%)`;
    return `<div class="it${it.border ? " b" : ""}" style="left:${n(it.x)}pt;top:${n(it.y)}pt;width:${n(it.w)}pt;height:${n(it.h)}pt"><img src="${pathToFileURL(files.get(it.page)!).href}" style="${img}"></div>`;
  }).join("");
  return [
    "<!DOCTYPE html><html><head><meta charset='utf-8'><style>",
    `@page { size: ${n(job.sheetW)}pt ${n(job.sheetH)}pt; margin: 0 }`,
    "html,body{margin:0;padding:0;background:#fff}",
    `.sheet{position:relative;width:${n(job.sheetW)}pt;height:${n(job.sheetH)}pt;overflow:hidden;break-after:page;page-break-after:always}`,
    ".sheet:last-child{break-after:auto;page-break-after:auto}",
    ".it{position:absolute;overflow:hidden}.it.b{outline:0.6pt solid #000}",
    ".it img{position:absolute;left:50%;top:50%;max-width:none}",
    "</style></head><body>",
    ...job.sheets.map((s) => `<div class="sheet">${items(s)}</div>`),
    "</body></html>",
  ].join("");
}

/**
 * Print a laid-out job: pages are rendered by the PDF engine, placed on sheets as
 * planned in X PDF's print dialog and sent to the chosen printer. With
 * systemDialog the Windows print dialog is shown for driver-specific options.
 */
export async function printPlan(parent: BrowserWindow, path: string, password: string | undefined, job: PrintJob,
  testPdfPath?: string): Promise<{ printed: boolean; reason?: string; sheets: number; testOutput?: string }> {
  const dir = join(appTempRoot(), `print-${Date.now().toString(36)}`);
  mkdirSync(dir, { recursive: true });
  const proc = new EngineProcess("print");
  let win: BrowserWindow | null = null;
  try {
    const pages = [...new Set(job.sheets.flat().map((i) => i.page))];
    const res = await proc.call("print.prepare", { path, password, pages, outputDir: dir, dpi: job.dpi, grayscale: job.grayscale, annots: job.annots });
    const files = new Map<number, string>(res.pages.map((p: any) => [p.page, p.file]));
    const htmlFile = join(dir, "print.html");
    writeFileSync(htmlFile, sheetsHtml(job, files), "utf8");
    lockDownSession("nayan-print", [dir]);
    win = new BrowserWindow({
      show: false,
      parent,
      webPreferences: { partition: "nayan-print", sandbox: true, contextIsolation: true, javascript: false, spellcheck: false },
    });
    await win.loadFile(htmlFile);
    if (testPdfPath) {
      // Automated tests: produce the exact sheets as a PDF instead of printing.
      const data = await win.webContents.printToPDF({ pageSize: job.paper, landscape: job.landscape, printBackground: true,
        margins: { top: 0, bottom: 0, left: 0, right: 0 }, preferCSSPageSize: true });
      writeFileSync(testPdfPath, data);
      return { printed: true, sheets: job.sheets.length, testOutput: testPdfPath };
    }
    const w = win;
    const result = await new Promise<{ printed: boolean; reason?: string }>((resolve) => {
      w.webContents.print({
        silent: !job.systemDialog,
        deviceName: job.printer || undefined,
        printBackground: true,
        copies: job.copies,
        collate: job.collate,
        color: !job.grayscale,
        duplexMode: job.duplex,
        landscape: job.landscape,
        pageSize: job.paper,
        margins: { marginType: "none" },
      }, (success, reason) => resolve({ printed: success, reason: success ? undefined : reason }));
    });
    return { ...result, sheets: job.sheets.length };
  } finally {
    win?.destroy();
    proc.stop();
    setTimeout(() => rmSync(dir, { recursive: true, force: true }), 120000).unref();
  }
}

export async function htmlToPdf(input: string, output: string, opts: { pageSize?: string; landscape?: boolean; margin?: number; overwrite?: boolean }) {
  if (!access.canRead(input)) throw new EngineError("ACCESS_DENIED", "Access to this file was not granted.");
  if (!access.canWrite(output)) throw new EngineError("ACCESS_DENIED", "X PDF may not write to that folder.");
  const ext = extname(input).toLowerCase();
  if (![".html", ".htm", ".xhtml"].includes(ext)) throw new EngineError("UNSUPPORTED_FORMAT", "Choose an .html or .htm file.");
  lockDownSession("nayan-html", [dirname(input)]);
  const win = new BrowserWindow({
    show: false,
    webPreferences: { partition: "nayan-html", sandbox: true, contextIsolation: true, javascript: false, spellcheck: false },
  });
  try {
    await win.loadFile(input);
    const m = Math.max(0, Math.min(2, (opts.margin ?? 36) / 72));
    const data = await win.webContents.printToPDF({
      pageSize: (opts.pageSize as any) || "A4",
      landscape: !!opts.landscape,
      printBackground: true,
      margins: { top: m, bottom: m, left: m, right: m },
    });
    const tmp = join(appTempRoot(), `html-${Date.now().toString(36)}.pdf`);
    writeFileSync(tmp, data);
    const probe = await enginePool.queryCall("doc.probe", { path: tmp });
    if (!probe.ok || !probe.pages) throw new EngineError("VALIDATION_FAILED", "The generated PDF could not be reopened.");
    let target = output.toLowerCase().endsWith(".pdf") ? output : output + ".pdf";
    if (existsSync(target) && !opts.overwrite) {
      const stem = join(dirname(target), basename(target, ".pdf"));
      let i = 1;
      while (existsSync(`${stem} (${i}).pdf`)) i++;
      target = `${stem} (${i}).pdf`;
    }
    try {
      renameSync(tmp, target);
    } catch {
      writeFileSync(target, data);
      rmSync(tmp, { force: true });
    }
    access.allowInput(target);
    return { output: target, pageCount: probe.pages, warnings: ["Scripts in the HTML file are not executed and remote resources (web fonts, images on the internet) are not loaded."] };
  } finally {
    win.destroy();
  }
}
