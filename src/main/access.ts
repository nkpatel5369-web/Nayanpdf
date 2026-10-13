// File-system access control.
//
// The renderer never gets general file-system access. It can only refer to
// files the user explicitly provided (open dialog, drag and drop, recent list,
// folder scans of a chosen folder) or that NayanPDF itself produced, and it can
// only write into folders the user chose or into the folder of a document
// they opened. Every path inside an engine request is checked against this
// registry before the request is forwarded.
import { existsSync, realpathSync, statSync } from "node:fs";
import { dirname, isAbsolute, normalize, resolve, sep } from "node:path";
import { appTempRoot } from "./paths";

const IS_WIN = process.platform === "win32";

function canon(p: string): string {
  let r = resolve(normalize(p));
  try {
    if (existsSync(r)) r = realpathSync.native(r);
  } catch {
    /* keep resolved */
  }
  return IS_WIN ? r.toLowerCase() : r;
}

class AccessRegistry {
  private inputs = new Set<string>();
  private outputDirs = new Set<string>();

  allowInput(p: string): void {
    if (!p || !isAbsolute(p)) return;
    this.inputs.add(canon(p));
    // Writing next to a document the user opened is the default output location.
    this.allowOutputDir(dirname(p));
  }

  allowOutputDir(d: string): void {
    if (!d || !isAbsolute(d)) return;
    this.outputDirs.add(canon(d));
  }

  isTemp(p: string): boolean {
    const c = canon(p);
    const t = canon(appTempRoot());
    return c === t || c.startsWith(t + (IS_WIN ? "\\" : sep));
  }

  canRead(p: string): boolean {
    if (typeof p !== "string" || !isAbsolute(p)) return false;
    if (this.isTemp(p)) return true;
    return this.inputs.has(canon(p));
  }

  canWrite(p: string, isDir = false): boolean {
    if (typeof p !== "string" || !isAbsolute(p)) return false;
    if (this.isTemp(p)) return true;
    const c = canon(p);
    const dir = isDir ? c : canon(dirname(p));
    for (const d of this.outputDirs) {
      if (dir === d || dir.startsWith(d + (IS_WIN ? "\\" : sep))) return true;
    }
    return false;
  }

  canReadDir(d: string): boolean {
    return this.canWrite(d, true);
  }
}

export const access = new AccessRegistry();

// Parameter names that carry paths in engine requests.
const INPUT_KEYS = new Set(["path", "pathA", "pathB", "insertPath", "replacementPath", "imagePath", "pfxPath", "pkcs11Lib"]);
const INPUT_LIST_KEYS = new Set(["images", "trustedCerts"]);
const OUTPUT_KEYS = new Set(["output", "textOutput"]);
const OUTPUT_DIR_KEYS = new Set(["outputDir"]);

export class AccessDenied extends Error {
  code = "ACCESS_DENIED";
}

/** Throws if a request touches a path the user has not authorised. */
export function checkParams(params: any): void {
  const walk = (obj: any, depth: number) => {
    if (depth > 6 || obj === null || typeof obj !== "object") return;
    if (Array.isArray(obj)) {
      for (const v of obj) walk(v, depth + 1);
      return;
    }
    for (const [k, v] of Object.entries(obj)) {
      if (INPUT_KEYS.has(k) && typeof v === "string" && v) {
        if (!access.canRead(v)) throw new AccessDenied(`Access to this file was not granted: ${baseName(v)}`);
      } else if (INPUT_LIST_KEYS.has(k) && Array.isArray(v)) {
        for (const p of v) if (typeof p === "string" && !access.canRead(p)) throw new AccessDenied(`Access to this file was not granted: ${baseName(p)}`);
      } else if (k === "files" && Array.isArray(v)) {
        for (const f of v) {
          const p = typeof f === "string" ? f : f?.path;
          if (typeof p === "string" && !access.canRead(p)) throw new AccessDenied(`Access to this file was not granted: ${baseName(p)}`);
          if (typeof f === "string" && !isAbsolute(f)) throw new AccessDenied("Invalid file reference.");
        }
      } else if (OUTPUT_KEYS.has(k) && typeof v === "string" && v) {
        if (!access.canWrite(v)) throw new AccessDenied(`NayanPDF may not write to ${dirname(v)}. Choose an output folder first.`);
      } else if (OUTPUT_DIR_KEYS.has(k) && typeof v === "string" && v) {
        if (!access.canWrite(v, true)) throw new AccessDenied(`NayanPDF may not write to ${v}. Choose an output folder first.`);
      } else if (typeof v === "object") {
        walk(v, depth + 1);
      }
    }
  };
  walk(params, 0);
}

function baseName(p: string): string {
  return p.split(/[\\/]/).pop() || p;
}

export function isFile(p: string): boolean {
  try {
    return statSync(p).isFile();
  } catch {
    return false;
  }
}
