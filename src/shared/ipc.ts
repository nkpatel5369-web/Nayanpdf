// Contract between the renderer (UI) and the main process.
// The preload script only forwards the channels and events listed here.

export const INVOKE_CHANNELS = [
  "app:info",
  "app:selftest",
  "dialog:openFiles",
  "dialog:openFolder",
  "dialog:saveFile",
  "dialog:pickOutputDir",
  "files:scanFolder",
  "files:reveal",
  "files:openWithSystem",
  "files:exists",
  "recent:list",
  "recent:remove",
  "recent:clear",
  "settings:get",
  "settings:set",
  "temp:usage",
  "temp:clear",
  "doc:open",
  "doc:query",
  "doc:apply",
  "doc:undo",
  "doc:redo",
  "doc:save",
  "doc:saveAs",
  "doc:close",
  "doc:state",
  "doc:preview",
  "doc:printPlan", "print:printers",
  "job:start",
  "job:cancel",
  "job:retry",
  "job:list",
  "job:clear",
  "engine:query",
  "signatures:list",
  "signatures:save",
  "signatures:delete",
  "convert:htmlToPdf",
  "window:confirm",
] as const;
export type InvokeChannel = (typeof INVOKE_CHANNELS)[number];

export const EVENT_CHANNELS = [
  "job:update",
  "doc:changed",
  "files:dropped",
  "app:openFiles",
  "settings:changed",
  "menu:command",
] as const;
export type EventChannel = (typeof EVENT_CHANNELS)[number];

export type Theme = "system" | "light" | "dark";

export interface Settings {
  theme: Theme;
  defaultOutputDir: string; // "" = next to the source file
  ocrLanguages: string[];
  compressionPreset: "maximum" | "high" | "balanced" | "strong";
  imageFormat: "png" | "jpg" | "tiff" | "bmp" | "webp";
  imageDpi: number;
  imageQuality: number;
  wordMode: "layout" | "flow";
  pptMode: "image" | "hybrid" | "editable";
  excelDateOrder: "DMY" | "MDY";
  recentLimit: number;
  reviewerName: string;
  reviewerInitials: string;
  batchConcurrency: number;
  allowOverwriteOriginal: boolean;
  clearTempOnExit: boolean;
  printDpi: number;
}

export const DEFAULT_SETTINGS: Settings = {
  theme: "system",
  defaultOutputDir: "",
  ocrLanguages: ["eng"],
  compressionPreset: "balanced",
  imageFormat: "png",
  imageDpi: 150,
  imageQuality: 90,
  wordMode: "layout",
  pptMode: "hybrid",
  excelDateOrder: "DMY",
  recentLimit: 15,
  reviewerName: "",
  reviewerInitials: "",
  batchConcurrency: 2,
  allowOverwriteOriginal: false,
  clearTempOnExit: true,
  printDpi: 300,
};

export interface RecentFile {
  path: string;
  name: string;
  openedAt: number;
  pages?: number;
  size?: number;
}

export type JobStatus = "queued" | "running" | "succeeded" | "failed" | "cancelled";

export interface JobInfo {
  id: string;
  batchId?: string;
  label: string;
  method: string;
  input?: string;
  status: JobStatus;
  progress?: { current: number; total: number; message?: string };
  statusMessage?: string;
  result?: any;
  error?: { code: string; message: string; details?: any };
  warnings?: string[];
  outputs?: string[];
  createdAt: number;
  startedAt?: number;
  finishedAt?: number;
}

export interface DocState {
  sessionId: string;
  originalPath: string;
  name: string;
  url: string; // nayan-doc:// URL of the current revision
  revision: number;
  revisions: number;
  canUndo: boolean;
  canRedo: boolean;
  dirty: boolean;
  savedPath?: string;
  hasPassword: boolean;
  lastAction?: string;
}

export interface EngineErrorShape {
  code: string;
  message: string;
  details?: any;
}

export type IpcResult<T> = { ok: true; data: T } | { ok: false; error: EngineErrorShape };

export interface FileFilterSpec {
  name: string;
  extensions: string[];
}

export const PDF_FILTER: FileFilterSpec[] = [{ name: "PDF documents", extensions: ["pdf"] }];
export const IMAGE_EXT = ["png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp", "jfif"];
export const OFFICE_EXT = ["doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "docm", "xlsm", "pptm"];
