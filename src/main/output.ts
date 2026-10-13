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

export async function printDocument(parent: BrowserWindow, path: string, password: string | undefined, pages: string | undefined,
  dpi: number, grayscale: boolean): Promise<{ printed: boolean; reason?: string; pages: number }> {
  const dir = join(appTempRoot(), `print-${Date.now().toString(36)}`);
  mkdirSync(dir, { recursive: true });
  const proc = new EngineProcess("print");
  try {
    const res = await proc.call("print.prepare", { path, password, pages, outputDir: dir, dpi, grayscale });
    const imgs: { file: string; widthPt: number; heightPt: number }[] = res.pages;
    const html = [
      "<!DOCTYPE html><html><head><meta charset='utf-8'><style>",
      "@page { margin: 0 } @page land { size: landscape; margin: 0 } @page port { size: portrait; margin: 0 }",
      "html,body{margin:0;padding:0;background:#fff}",
      ".pg{width:100%;height:100vh;display:flex;align-items:center;justify-content:center;break-after:page;page-break-after:always;overflow:hidden}",
      ".pg:last-child{break-after:auto;page-break-after:auto}",
      ".land{page:land}.port{page:port}",
      "img{max-width:100%;max-height:100%;object-fit:contain}",
      "</style></head><body>",
      ...imgs.map((p) => `<div class="pg ${p.widthPt > p.heightPt ? "land" : "port"}"><img src="${pathToFileURL(p.file).href}"></div>`),
      "</body></html>",
    ].join("");
    const htmlFile = join(dir, "print.html");
    writeFileSync(htmlFile, html, "utf8");
    lockDownSession("nayan-print", [dir]);
    const win = new BrowserWindow({
      show: false,
      parent,
      webPreferences: { partition: "nayan-print", sandbox: true, contextIsolation: true, javascript: false, spellcheck: false },
    });
    await win.loadFile(htmlFile);
    const result = await new Promise<{ printed: boolean; reason?: string }>((resolve) => {
      win.webContents.print({ silent: false, printBackground: true, margins: { marginType: "none" } }, (success, reason) =>
        resolve({ printed: success, reason: success ? undefined : reason }),
      );
    });
    win.destroy();
    return { ...result, pages: imgs.length };
  } finally {
    proc.stop();
    setTimeout(() => rmSync(dir, { recursive: true, force: true }), 60000).unref();
  }
}

export async function htmlToPdf(input: string, output: string, opts: { pageSize?: string; landscape?: boolean; margin?: number; overwrite?: boolean }) {
  if (!access.canRead(input)) throw new EngineError("ACCESS_DENIED", "Access to this file was not granted.");
  if (!access.canWrite(output)) throw new EngineError("ACCESS_DENIED", "NayanPDF may not write to that folder.");
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
