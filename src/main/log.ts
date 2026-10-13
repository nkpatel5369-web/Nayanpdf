// Minimal local log. Never logs document content, passwords or full paths of
// user documents; only operational messages.
import { appendFileSync, mkdirSync, statSync, renameSync } from "node:fs";
import { join } from "node:path";
import { app } from "electron";

let file: string | null = null;

function target(): string | null {
  if (file) return file;
  try {
    const dir = join(app.getPath("userData"), "logs");
    mkdirSync(dir, { recursive: true });
    file = join(dir, "nayanpdf.log");
    try {
      if (statSync(file).size > 2 * 1024 * 1024) renameSync(file, file + ".old");
    } catch {
      /* no log yet */
    }
    return file;
  } catch {
    return null;
  }
}

function write(level: string, msg: string) {
  const line = `${new Date().toISOString()} ${level} ${msg}\n`;
  if (!app.isPackaged) process.stderr.write(line);
  const t = target();
  if (t) {
    try {
      appendFileSync(t, line);
    } catch {
      /* ignore */
    }
  }
}

export const log = {
  info: (m: string) => write("INFO", m),
  warn: (m: string) => write("WARN", m),
  error: (m: string) => write("ERROR", m),
};
