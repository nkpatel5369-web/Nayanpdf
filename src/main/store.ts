// Local persistence: settings, recent files and saved signatures. Nothing
// leaves the computer. Signatures are encrypted with the OS key store
// (Windows DPAPI via Electron safeStorage) when available.
import { safeStorage } from "electron";
import { existsSync, readFileSync, renameSync, writeFileSync, statSync } from "node:fs";
import { basename } from "node:path";
import { DEFAULT_SETTINGS, RecentFile, Settings } from "../shared/ipc";
import { userDataFile } from "./paths";
import { access } from "./access";

function readJson<T>(file: string, fallback: T): T {
  try {
    if (!existsSync(file)) return fallback;
    return JSON.parse(readFileSync(file, "utf8")) as T;
  } catch {
    return fallback;
  }
}

function writeJsonAtomic(file: string, data: unknown): void {
  const tmp = file + ".tmp";
  writeFileSync(tmp, JSON.stringify(data, null, 2), "utf8");
  renameSync(tmp, file);
}

// ---------------------------------------------------------------- settings
let settingsCache: Settings | null = null;

export function getSettings(): Settings {
  if (!settingsCache) {
    const raw = readJson<Partial<Settings>>(userDataFile("settings.json"), {});
    settingsCache = sanitizeSettings({ ...DEFAULT_SETTINGS, ...raw });
    if (settingsCache.defaultOutputDir) access.allowOutputDir(settingsCache.defaultOutputDir);
  }
  return settingsCache;
}

export function sanitizeSettings(s: any): Settings {
  const d = DEFAULT_SETTINGS;
  const pick = <K extends keyof Settings>(k: K, ok: (v: any) => boolean): Settings[K] => (ok(s[k]) ? s[k] : d[k]);
  const oneOf = (vals: string[]) => (v: any) => typeof v === "string" && vals.includes(v);
  const num = (lo: number, hi: number) => (v: any) => typeof v === "number" && v >= lo && v <= hi;
  return {
    theme: pick("theme", oneOf(["system", "light", "dark"])),
    defaultOutputDir: pick("defaultOutputDir", (v) => typeof v === "string" && v.length < 1000),
    ocrLanguages: pick("ocrLanguages", (v) => Array.isArray(v) && v.length > 0 && v.every((x) => typeof x === "string" && /^[a-z_]{3,12}$/i.test(x))),
    compressionPreset: pick("compressionPreset", oneOf(["maximum", "high", "balanced", "strong"])),
    imageFormat: pick("imageFormat", oneOf(["png", "jpg", "tiff", "bmp", "webp"])),
    imageDpi: pick("imageDpi", num(36, 1200)),
    imageQuality: pick("imageQuality", num(10, 100)),
    wordMode: pick("wordMode", oneOf(["layout", "flow"])),
    pptMode: pick("pptMode", oneOf(["image", "hybrid", "editable"])),
    excelDateOrder: pick("excelDateOrder", oneOf(["DMY", "MDY"])),
    recentLimit: pick("recentLimit", num(0, 50)),
    reviewerName: pick("reviewerName", (v) => typeof v === "string" && v.length < 120),
    reviewerInitials: pick("reviewerInitials", (v) => typeof v === "string" && v.length < 12),
    batchConcurrency: pick("batchConcurrency", num(1, 8)),
    allowOverwriteOriginal: pick("allowOverwriteOriginal", (v) => typeof v === "boolean"),
    clearTempOnExit: pick("clearTempOnExit", (v) => typeof v === "boolean"),
    printDpi: pick("printDpi", num(100, 600)),
    defaultZoom: pick("defaultZoom", oneOf(["auto", "page-fit", "page-width", "page-actual"])),
    defaultLayout: pick("defaultLayout", oneOf(["auto", "single", "continuous", "two", "twoContinuous"])),
    navPanelOnOpen: pick("navPanelOnOpen", oneOf(["document", "always", "never"])),
  };
}

export function setSettings(patch: Partial<Settings>): Settings {
  const cur = getSettings();
  // Output folder can only be set through the folder picker (see ipc.ts).
  const next = sanitizeSettings({ ...cur, ...patch, defaultOutputDir: patch.defaultOutputDir === "" ? "" : cur.defaultOutputDir });
  settingsCache = next;
  writeJsonAtomic(userDataFile("settings.json"), next);
  trimRecent();
  return next;
}

export function setDefaultOutputDir(dir: string): Settings {
  const cur = getSettings();
  settingsCache = { ...cur, defaultOutputDir: dir };
  writeJsonAtomic(userDataFile("settings.json"), settingsCache);
  if (dir) access.allowOutputDir(dir);
  return settingsCache;
}

// ---------------------------------------------------------------- recent files
export function listRecent(): RecentFile[] {
  const list = readJson<RecentFile[]>(userDataFile("recent.json"), []);
  const valid = list.filter((r) => r && typeof r.path === "string" && existsSync(r.path));
  for (const r of valid) access.allowInput(r.path);
  return valid;
}

export function addRecent(path: string, pages?: number): void {
  const limit = getSettings().recentLimit;
  if (limit === 0) return;
  let size: number | undefined;
  try {
    size = statSync(path).size;
  } catch {
    /* ignore */
  }
  const list = readJson<RecentFile[]>(userDataFile("recent.json"), []).filter((r) => r.path !== path);
  list.unshift({ path, name: basename(path), openedAt: Date.now(), pages, size });
  writeJsonAtomic(userDataFile("recent.json"), list.slice(0, limit));
}

export function removeRecent(path: string): void {
  const list = readJson<RecentFile[]>(userDataFile("recent.json"), []).filter((r) => r.path !== path);
  writeJsonAtomic(userDataFile("recent.json"), list);
}

export function clearRecent(): void {
  writeJsonAtomic(userDataFile("recent.json"), []);
}

function trimRecent(): void {
  const limit = getSettings().recentLimit;
  const list = readJson<RecentFile[]>(userDataFile("recent.json"), []);
  if (list.length > limit) writeJsonAtomic(userDataFile("recent.json"), list.slice(0, limit));
}

// ---------------------------------------------------------------- signatures
export interface SavedSignature {
  id: string;
  name: string;
  kind: "signature" | "initials";
  png: string; // base64
  createdAt: number;
}

function sigFile(): string {
  return userDataFile("signatures.dat");
}

export function listSignatures(): SavedSignature[] {
  const f = sigFile();
  if (!existsSync(f)) return [];
  try {
    const raw = readFileSync(f);
    const json = safeStorage.isEncryptionAvailable() && raw[0] !== 0x5b /* '[' */ ? safeStorage.decryptString(raw) : raw.toString("utf8");
    const list = JSON.parse(json);
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

function writeSignatures(list: SavedSignature[]): void {
  const json = JSON.stringify(list);
  const data = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(json) : Buffer.from(json, "utf8");
  const tmp = sigFile() + ".tmp";
  writeFileSync(tmp, data);
  renameSync(tmp, sigFile());
}

export function saveSignature(sig: Omit<SavedSignature, "id" | "createdAt">): SavedSignature {
  const list = listSignatures();
  const item: SavedSignature = { ...sig, id: Math.random().toString(36).slice(2), createdAt: Date.now() };
  list.unshift(item);
  writeSignatures(list.slice(0, 20));
  return item;
}

export function deleteSignature(id: string): void {
  writeSignatures(listSignatures().filter((s) => s.id !== id));
}

export function signaturesProtected(): boolean {
  return safeStorage.isEncryptionAvailable();
}
