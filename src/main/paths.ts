// Locations of bundled engines and app data. In the installed app everything
// lives under process.resourcesPath; in development the repo and PATH are used.
import { app } from "electron";
import { existsSync, mkdirSync } from "node:fs";
import { join, resolve } from "node:path";

const IS_WIN = process.platform === "win32";

export function isPackaged(): boolean {
  return app.isPackaged;
}

function repoRoot(): string {
  return resolve(__dirname, "..", "..");
}

export function resourcesRoot(): string {
  return isPackaged() ? process.resourcesPath : repoRoot();
}

export function rendererDir(): string {
  return isPackaged() ? join(app.getAppPath(), "dist", "renderer") : join(repoRoot(), "dist", "renderer");
}

export function preloadPath(): string {
  return isPackaged() ? join(app.getAppPath(), "dist", "preload", "preload.js") : join(repoRoot(), "dist", "preload", "preload.js");
}

export function engineScript(): string {
  return join(resourcesRoot(), "engine", "run_engine.py");
}

function firstExisting(cands: (string | undefined)[]): string | undefined {
  for (const c of cands) if (c && existsSync(c)) return c;
  return undefined;
}

export function pythonExe(): string {
  if (isPackaged()) {
    return join(process.resourcesPath, "python", IS_WIN ? "python.exe" : "bin/python3");
  }
  return (
    firstExisting([process.env.NAYANPDF_PYTHON, "/opt/nayan-venv/bin/python", join(repoRoot(), ".venv", IS_WIN ? "Scripts/python.exe" : "bin/python")]) ||
    (IS_WIN ? "python.exe" : "python3")
  );
}

export interface ToolPaths {
  tesseract?: string;
  tessdata?: string;
  soffice?: string;
  ghostscript?: string;
  gsIcc?: string;
  resources: string;
}

export function toolPaths(): ToolPaths {
  const r = resourcesRoot();
  if (isPackaged() && IS_WIN) {
    return {
      tesseract: firstExisting([join(r, "tesseract", "tesseract.exe")]),
      tessdata: firstExisting([join(r, "tesseract", "tessdata")]),
      soffice: firstExisting([join(r, "libreoffice", "program", "soffice.exe")]),
      ghostscript: firstExisting([join(r, "ghostscript", "bin", "gswin64c.exe")]),
      gsIcc: firstExisting([join(r, "ghostscript", "iccprofiles", "srgb.icc")]),
      resources: join(r, "assets"),
    };
  }
  // Development and automated tests: tools come from PATH, or from explicit
  // NAYANPDF_* variables (used by CI to point at the assembled Windows runtime).
  const e = process.env;
  return {
    tesseract: firstExisting([e.NAYANPDF_TESSERACT]),
    tessdata: firstExisting([e.NAYANPDF_TESSDATA]),
    soffice: firstExisting([e.NAYANPDF_SOFFICE]),
    ghostscript: firstExisting([e.NAYANPDF_GS]),
    gsIcc: firstExisting([e.NAYANPDF_GS_ICC]),
    resources: join(r, "resources"),
  };
}

let tmpRoot: string | undefined;
export function appTempRoot(): string {
  if (!tmpRoot) {
    tmpRoot = join(app.getPath("temp"), "X-PDF", `s${process.pid}`);
    mkdirSync(tmpRoot, { recursive: true });
  }
  return tmpRoot;
}

export function appTempBase(): string {
  return join(app.getPath("temp"), "X-PDF");
}

export function userDataFile(name: string): string {
  const d = app.getPath("userData");
  mkdirSync(d, { recursive: true });
  return join(d, name);
}
