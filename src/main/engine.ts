// Local processing engine processes (bundled Python). Communication is over
// stdin/stdout only - no ports, no sockets, no network.
import { ChildProcessWithoutNullStreams, spawn } from "node:child_process";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { appTempRoot, engineScript, pythonExe, toolPaths } from "./paths";
import { log } from "./log";

export interface EngineEvent {
  event: "progress" | "status" | "partial";
  current?: number;
  total?: number;
  message?: string;
  path?: string;
}

export class EngineError extends Error {
  constructor(public code: string, message: string, public details?: any) {
    super(message);
  }
}

interface Pending {
  resolve: (v: any) => void;
  reject: (e: EngineError) => void;
  onEvent?: (e: EngineEvent) => void;
}

let seq = 0;

export function engineEnv(jobTmp: string): NodeJS.ProcessEnv {
  const t = toolPaths();
  const env: NodeJS.ProcessEnv = {
    // Minimal, explicit environment. Proxy variables are deliberately not passed on.
    SYSTEMROOT: process.env.SYSTEMROOT,
    WINDIR: process.env.WINDIR,
    PATH: process.env.PATH,
    HOME: process.env.HOME,
    USERPROFILE: process.env.USERPROFILE,
    LOCALAPPDATA: process.env.LOCALAPPDATA,
    APPDATA: process.env.APPDATA,
    LANG: process.env.LANG,
    TEMP: jobTmp,
    TMP: jobTmp,
    TMPDIR: jobTmp,
    PYTHONDONTWRITEBYTECODE: "1",
    PYTHONNOUSERSITE: "1",
    PYTHONIOENCODING: "utf-8",
    PYTHONUTF8: "1",
    NAYANPDF_JOB_TMP: jobTmp,
    NAYANPDF_RESOURCES: t.resources,
  };
  if (t.tesseract) env.NAYANPDF_TESSERACT = t.tesseract;
  if (t.tessdata) env.NAYANPDF_TESSDATA = t.tessdata;
  if (t.soffice) env.NAYANPDF_SOFFICE = t.soffice;
  if (t.ghostscript) env.NAYANPDF_GS = t.ghostscript;
  if (t.gsIcc) env.NAYANPDF_GS_ICC = t.gsIcc;
  if (process.platform !== "win32") {
    env.SAL_USE_VCLPLUGIN = "svp";
  }
  for (const k of Object.keys(env)) if (env[k] === undefined) delete env[k];
  return env;
}

export class EngineProcess {
  private proc: ChildProcessWithoutNullStreams | null = null;
  private buf = "";
  private pending = new Map<number, Pending>();
  private readyPromise: Promise<void> | null = null;
  private stderrTail: string[] = [];
  readonly tmp: string;
  exited = false;

  constructor(public name: string) {
    this.tmp = join(appTempRoot(), `eng-${name}-${Date.now().toString(36)}`);
    mkdirSync(this.tmp, { recursive: true });
  }

  start(): Promise<void> {
    if (this.readyPromise) return this.readyPromise;
    this.readyPromise = new Promise((resolve, reject) => {
      const args = ["-I", "-X", "utf8", engineScript()];
      const proc = spawn(pythonExe(), args, {
        env: engineEnv(this.tmp),
        windowsHide: true,
        detached: process.platform !== "win32",
        stdio: ["pipe", "pipe", "pipe"],
      });
      this.proc = proc;
      let ready = false;
      const timer = setTimeout(() => {
        if (!ready) reject(new EngineError("ENGINE_START", "The processing engine did not start in time."));
      }, 60000);
      proc.stdout.setEncoding("utf8");
      proc.stdout.on("data", (chunk: string) => {
        this.buf += chunk;
        let idx: number;
        while ((idx = this.buf.indexOf("\n")) >= 0) {
          const line = this.buf.slice(0, idx).trim();
          this.buf = this.buf.slice(idx + 1);
          if (!line) continue;
          let msg: any;
          try {
            msg = JSON.parse(line);
          } catch {
            continue;
          }
          if (msg.event === "ready") {
            ready = true;
            clearTimeout(timer);
            resolve();
            continue;
          }
          const p = this.pending.get(msg.id);
          if (!p) continue;
          if (msg.event) {
            p.onEvent?.(msg as EngineEvent);
          } else if ("result" in msg) {
            this.pending.delete(msg.id);
            p.resolve(msg.result);
          } else if ("error" in msg) {
            this.pending.delete(msg.id);
            p.reject(new EngineError(msg.error.code, msg.error.message, msg.error.details));
          }
        }
      });
      proc.stderr.setEncoding("utf8");
      proc.stderr.on("data", (chunk: string) => {
        // Engine diagnostics only; never contains document text or passwords.
        for (const l of chunk.split(/\r?\n/)) {
          if (!l.trim()) continue;
          this.stderrTail.push(l);
          if (this.stderrTail.length > 40) this.stderrTail.shift();
        }
      });
      proc.on("error", (err) => {
        clearTimeout(timer);
        log.error(`engine ${this.name} failed to start: ${err.message}`);
        reject(new EngineError("ENGINE_START", `The processing engine could not be started (${err.message}).`));
      });
      proc.on("exit", (code, signal) => {
        this.exited = true;
        clearTimeout(timer);
        if (!ready) reject(new EngineError("ENGINE_START", `The processing engine exited during start-up (code ${code}). ${this.stderrTail.slice(-3).join(" ")}`));
        for (const [, p] of this.pending) {
          p.reject(new EngineError(signal ? "CANCELLED" : "ENGINE_EXIT", signal ? "The operation was cancelled." : `The processing engine stopped unexpectedly (code ${code}).`));
        }
        this.pending.clear();
      });
    });
    return this.readyPromise;
  }

  async call<T = any>(method: string, params: Record<string, any>, onEvent?: (e: EngineEvent) => void): Promise<T> {
    await this.start();
    if (!this.proc || this.exited) throw new EngineError("ENGINE_EXIT", "The processing engine is not running.");
    const id = ++seq;
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve, reject, onEvent });
      this.proc!.stdin.write(JSON.stringify({ id, method, params }) + "\n");
    });
  }

  kill(): void {
    if (!this.proc || this.exited) return;
    const pid = this.proc.pid;
    try {
      if (process.platform === "win32" && pid) {
        // Terminate the engine and any child tools (Tesseract, LibreOffice, Ghostscript).
        spawn("taskkill", ["/pid", String(pid), "/T", "/F"], { windowsHide: true, stdio: "ignore" });
      } else if (pid) {
        process.kill(-pid, "SIGKILL");
      }
    } catch {
      try {
        this.proc.kill("SIGKILL");
      } catch {
        /* already gone */
      }
    }
  }

  stop(): void {
    if (!this.proc || this.exited) return;
    try {
      this.proc.stdin.write('"__exit__"\n');
    } catch {
      /* ignore */
    }
    setTimeout(() => this.kill(), 3000).unref();
  }
}

// Long-lived workers for interactive work; jobs get their own process so they
// can be cancelled without affecting the rest of the app.
class EnginePool {
  private query: EngineProcess | null = null;
  private editor: EngineProcess | null = null;

  private get(kind: "query" | "edit"): EngineProcess {
    if (kind === "query") {
      if (!this.query || this.query.exited) this.query = new EngineProcess("query");
      return this.query;
    }
    if (!this.editor || this.editor.exited) this.editor = new EngineProcess("edit");
    return this.editor;
  }

  warmUp(): void {
    this.get("query").start().catch((e) => log.error(`engine warm-up failed: ${e.message}`));
  }

  queryCall<T = any>(method: string, params: Record<string, any>): Promise<T> {
    return this.get("query").call<T>(method, params);
  }

  editCall<T = any>(method: string, params: Record<string, any>, onEvent?: (e: EngineEvent) => void): Promise<T> {
    return this.get("edit").call<T>(method, params, onEvent);
  }

  shutdown(): void {
    this.query?.stop();
    this.editor?.stop();
  }
}

export const enginePool = new EnginePool();
