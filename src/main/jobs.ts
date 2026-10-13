// Job queue for tool operations and batch processing. Each running job owns
// its engine process, so cancelling kills only that job (including child tools)
// and partial outputs are removed. Progress is forwarded exactly as the engine
// measures it (pages processed etc.) - never estimated.
import { BrowserWindow } from "electron";
import { existsSync, rmSync, unlinkSync } from "node:fs";
import { JobInfo } from "../shared/ipc";
import { access, checkParams } from "./access";
import { EngineError, EngineProcess } from "./engine";
import { getSettings } from "./store";
import { log } from "./log";

interface JobRecord extends JobInfo {
  params: Record<string, any>;
  proc?: EngineProcess;
  partials: string[];
}

const JOB_METHODS = new Set([
  "pages.merge", "pages.split", "pages.extract", "pages.delete", "pages.rotate", "pages.reverse", "pages.assemble",
  "pages.insertBlank", "pages.insertFromFile", "pages.replace", "pages.duplicate", "pages.interleave",
  "compress.run", "convert.toWord", "convert.toExcel", "convert.toPowerPoint", "convert.toImages", "convert.toText",
  "convert.toHtml", "convert.toMarkdown", "convert.officeToPdf", "create.fromImages", "create.fromText", "create.assemble",
  "create.blank", "ocr.run", "ocr.text", "stamp.watermark", "stamp.headerFooter", "stamp.pageNumbers", "stamp.bates",
  "stamp.background", "security.encrypt", "security.decrypt", "meta.set", "meta.remove", "redact.apply",
  "forms.fill", "annot.flatten", "compare.report", "sign.digital", "sign.placeImage", "sign.createCertificate",
  "util.repair", "util.linearize", "util.pdfa", "bookmarks.set", "labels.set", "attachments.add",
  "attachments.extract", "attachments.delete", "images.extract", "pagesize.standardize", "pagesize.crop",
  "compare.run", "edit.replaceAll", "edit.text", "edit.insertText", "edit.insertImage", "edit.image", "annot.apply", "selftest.run",
]);

class JobManager {
  private jobs = new Map<string, JobRecord>();
  private order: string[] = [];
  private running = 0;

  private emit(job: JobRecord) {
    const info = this.publicInfo(job);
    for (const w of BrowserWindow.getAllWindows()) {
      if (!w.isDestroyed()) w.webContents.send("job:update", info);
    }
  }

  publicInfo(j: JobRecord): JobInfo {
    const { params, proc, partials, ...rest } = j;
    return { ...rest, input: rest.input };
  }

  list(): JobInfo[] {
    return this.order.map((id) => this.publicInfo(this.jobs.get(id)!)).reverse();
  }

  start(method: string, params: Record<string, any>, label: string, batchId?: string): JobInfo {
    if (!JOB_METHODS.has(method)) throw new EngineError("INVALID_ARGUMENT", `Unsupported operation: ${method}`);
    checkParams(params);
    const id = `job-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
    const job: JobRecord = {
      id,
      batchId,
      label,
      method,
      input: typeof params.path === "string" ? params.path : typeof params.pathA === "string" ? params.pathA : undefined,
      status: "queued",
      createdAt: Date.now(),
      params,
      partials: [],
    };
    this.jobs.set(id, job);
    this.order.push(id);
    this.emit(job);
    this.pump();
    return this.publicInfo(job);
  }

  private pump() {
    const limit = Math.max(1, getSettings().batchConcurrency);
    for (const id of this.order) {
      if (this.running >= limit) break;
      const j = this.jobs.get(id)!;
      if (j.status === "queued") this.run(j);
    }
  }

  private async run(job: JobRecord) {
    this.running++;
    job.status = "running";
    job.startedAt = Date.now();
    const proc = new EngineProcess(`job`);
    job.proc = proc;
    this.emit(job);
    try {
      const result = await proc.call(job.method, job.params, (ev) => {
        if (ev.event === "progress") {
          job.progress = { current: ev.current ?? 0, total: ev.total ?? 0, message: ev.message };
        } else if (ev.event === "status") {
          job.statusMessage = ev.message;
        } else if (ev.event === "partial" && ev.path) {
          job.partials.push(ev.path);
        }
        this.emit(job);
      });
      if ((job.status as string) === "cancelled") return;
      job.status = "succeeded";
      job.result = result;
      job.warnings = result?.warnings || [];
      job.outputs = collectOutputs(result);
      for (const o of job.outputs) access.allowInput(o);
    } catch (e: any) {
      if ((job.status as string) === "cancelled") return;
      job.status = "failed";
      job.error = { code: e.code || "OPERATION_FAILED", message: e.message || String(e), details: e.details };
      log.warn(`job ${job.method} failed: ${job.error.code}`);
    } finally {
      job.finishedAt = Date.now();
      job.proc = undefined;
      proc.stop();
      cleanupDir(proc.tmp);
      this.running--;
      this.emit(job);
      this.pump();
    }
  }

  cancel(id: string): void {
    const j = this.jobs.get(id);
    if (!j) return;
    if (j.status === "queued") {
      j.status = "cancelled";
      j.finishedAt = Date.now();
      this.emit(j);
      return;
    }
    if (j.status === "running") {
      j.status = "cancelled";
      j.finishedAt = Date.now();
      j.proc?.kill();
      for (const p of j.partials) {
        try {
          if (existsSync(p)) unlinkSync(p);
        } catch {
          /* ignore */
        }
      }
      this.emit(j);
    }
  }

  retry(id: string): JobInfo | null {
    const j = this.jobs.get(id);
    if (!j || (j.status !== "failed" && j.status !== "cancelled")) return null;
    return this.start(j.method, j.params, j.label, j.batchId);
  }

  clearFinished(): void {
    this.order = this.order.filter((id) => {
      const j = this.jobs.get(id)!;
      if (j.status === "queued" || j.status === "running") return true;
      this.jobs.delete(id);
      return false;
    });
  }

  shutdown(): void {
    for (const j of this.jobs.values()) if (j.status === "running") j.proc?.kill();
  }
}

export function collectOutputs(result: any): string[] {
  const out: string[] = [];
  if (!result || typeof result !== "object") return out;
  if (typeof result.output === "string") out.push(result.output);
  if (Array.isArray(result.outputs)) {
    for (const o of result.outputs) {
      if (typeof o === "string") out.push(o);
      else if (o && typeof o.output === "string") out.push(o.output);
    }
  }
  return [...new Set(out)];
}

export function cleanupDir(d: string) {
  try {
    rmSync(d, { recursive: true, force: true });
  } catch {
    /* ignore */
  }
}

export const jobs = new JobManager();
