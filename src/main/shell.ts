// Explorer right-click commands, registered for the current user by the installer:
// "Convert to PDF with X PDF" and "Combine files in X PDF". Explorer starts
// X-PDF.exe once per selected file; the running instance collects the files that
// arrive close together and handles them as one request, as Acrobat does.
import { BrowserWindow } from "electron";
import { existsSync, statSync } from "node:fs";
import { extname, resolve } from "node:path";
import { access } from "./access";

export type ShellVerb = "convert" | "combine";

const FLAGS: Record<string, ShellVerb> = { "--convert-to-pdf": "convert", "--combine": "combine" };
const OFFICE = [".doc", ".docx", ".odt", ".rtf", ".xls", ".xlsx", ".ods", ".csv", ".ppt", ".pptx", ".odp"];
const IMAGES = [".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".gif", ".webp"];
// What each command accepts (the installer registers the commands for the same types).
const ACCEPTS: Record<ShellVerb, Set<string>> = {
  convert: new Set([...OFFICE, ...IMAGES, ".txt", ".htm", ".html"]),
  combine: new Set([...OFFICE, ...IMAGES, ".txt", ".pdf"]),
};
const SETTLE_MS = 1500;

/** The command and its files, or null when the arguments are not a right-click command. */
export function parseShellArgs(argv: string[]): { verb: ShellVerb; files: string[] } | null {
  const flag = argv.find((a) => typeof a === "string" && a in FLAGS);
  if (!flag) return null;
  const verb = FLAGS[flag];
  const files = argv
    .filter((a) => typeof a === "string" && !a.startsWith("-") && ACCEPTS[verb].has(extname(a).toLowerCase()))
    .map((a) => resolve(a))
    .filter((a) => existsSync(a) && statSync(a).isFile());
  return { verb, files };
}

const queue: Record<ShellVerb, string[]> = { convert: [], combine: [] };
let timer: NodeJS.Timeout | null = null;
let ready = false;
let getWindow: () => BrowserWindow | null = () => null;

export function setShellWindow(fn: () => BrowserWindow | null): void {
  getWindow = fn;
}

export function queueShell(verb: ShellVerb, files: string[]): void {
  for (const f of files) {
    access.allowInput(f);
    if (!queue[verb].includes(f)) queue[verb].push(f);
  }
  // Wait until Explorer has started the instance for every selected file.
  if (timer) clearTimeout(timer);
  timer = setTimeout(flush, SETTLE_MS);
}

/** The window's code is listening: commands can be delivered from now on. */
export function shellReady(): void {
  ready = true;
  if (!timer && (queue.convert.length || queue.combine.length)) timer = setTimeout(flush, 200);
}

function flush(): void {
  timer = null;
  const win = getWindow();
  if (!win || win.isDestroyed() || !ready) {
    timer = setTimeout(flush, 300);
    return;
  }
  for (const verb of ["convert", "combine"] as ShellVerb[]) {
    const files = queue[verb].splice(0);
    if (files.length) win.webContents.send("app:shellCommand", { verb, files });
  }
  if (win.isMinimized()) win.restore();
  win.focus();
}
