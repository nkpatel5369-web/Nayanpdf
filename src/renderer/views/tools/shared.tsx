import React, { ReactNode, useEffect, useState } from "react";
import { FileText, FolderOpen, Lock, X } from "lucide-react";
import { api, baseName, dirName, joinPath, stem } from "../../api";
import { useApp } from "../../store";
import { usePrompt, probeFile } from "../../components/common";
import type { ToolDef } from "../../tools";

export interface Src {
  path?: string; // a file on disk
  sessionId?: string; // an open document (current revision is used)
  password?: string;
  displayPath: string; // original location, used for naming outputs
  name: string;
  pages?: number;
  dirty?: boolean;
}

export function ToolPage(props: { tool: ToolDef; children: ReactNode; side?: ReactNode; subtitle?: ReactNode }) {
  const t = props.tool;
  return (
    <div className="page">
      <div className="page-narrow">
        <div className="tool-head">
          <div className="ic" style={{ background: t.color + "1a", color: t.color }}><t.icon size={24} /></div>
          <div>
            <h1>{t.title}</h1>
            <div className="muted">{props.subtitle || t.desc}</div>
          </div>
        </div>
        {props.side ? (
          <div className="tool-body">
            <div>{props.children}</div>
            <div>{props.side}</div>
          </div>
        ) : (
          props.children
        )}
      </div>
    </div>
  );
}

/** Pick the input: an open document or a file chosen from disk. */
export function SourcePicker(props: { value: Src | null; onChange: (s: Src | null) => void; kind?: "pdf" | "images" | "office" | "html" | "text" | "any"; label?: string; allowDocs?: boolean }) {
  const docs = useApp((s) => s.docs);
  const docList = Object.values(docs);
  const kind = props.kind || "pdf";
  const choose = async (path: string) => {
    if (kind !== "pdf") {
      props.onChange({ path, displayPath: path, name: baseName(path) });
      return;
    }
    let info = await probeFile(path);
    let password: string | undefined;
    while (info.locked) {
      const pw = await usePrompt.getState().ask(baseName(path), !!password);
      if (pw === null) return;
      password = pw;
      const p = await api.query("doc.probe", { path, password });
      if (p.ok) {
        info = { path, pages: p.pages };
        break;
      }
    }
    if (info.error) {
      useApp.getState().toast({ kind: "error", title: "Cannot use this file", body: info.error });
      return;
    }
    props.onChange({ path, password, displayPath: path, name: baseName(path), pages: info.pages });
  };
  useEffect(() => {
    if (props.value) return;
    return window.nayan.onFilesDropped((paths) => paths[0] && choose(paths[0]));
  }, [props.value]);
  const v = props.value;
  return (
    <div className="card card-pad" style={{ marginBottom: 14 }}>
      <div className="label" style={{ marginBottom: 8 }}>{props.label || "Document"}</div>
      {v ? (
        <div className="row">
          {v.sessionId ? <FileText size={20} color="var(--accent)" /> : v.password ? <Lock size={18} /> : <FileText size={20} color="var(--brand)" />}
          <div className="grow" style={{ minWidth: 0 }}>
            <div className="ellipsis" style={{ fontWeight: 600 }}>{v.name}</div>
            <div className="subtle ellipsis">
              {v.sessionId ? "Open document" + (v.dirty ? " (including unsaved edits)" : "") : dirName(v.displayPath)}
              {v.pages ? ` · ${v.pages} pages` : ""}
            </div>
          </div>
          <button className="icon-btn" title="Change" onClick={() => props.onChange(null)}><X size={16} /></button>
        </div>
      ) : (
        <>
          <div className="row wrap">
            <button className="btn primary" onClick={async () => { const f = await api.openFiles(kind === "text" ? "text" : kind, false); if (f[0]) choose(f[0]); }}>
              <FolderOpen size={15} /> Choose file
            </button>
            <span className="subtle">or drop a file here</span>
          </div>
          {props.allowDocs !== false && kind === "pdf" && docList.length > 0 && (
            <div style={{ marginTop: 10 }}>
              <div className="subtle" style={{ marginBottom: 4 }}>Or use an open document:</div>
              <div className="row wrap" style={{ gap: 6 }}>
                {docList.map((d) => (
                  <button key={d.state.sessionId} className="btn sm" onClick={() => props.onChange(srcFromDoc(d.state.sessionId))}>{d.state.name}{d.state.dirty ? " *" : ""}</button>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

export function srcFromDoc(sessionId: string): Src | null {
  const d = useApp.getState().docs[sessionId];
  if (!d) return null;
  const p = d.state.savedPath || d.state.originalPath;
  return { sessionId, displayPath: p, name: d.state.name, pages: d.info?.pageCount, dirty: d.state.dirty };
}

/** Engine parameters that identify the source. */
export function srcParams(s: Src): Record<string, any> {
  return s.sessionId ? { sessionId: s.sessionId } : { path: s.path, password: s.password };
}

export function outPath(s: Src, outDir: string | null, suffix: string, ext: string): string {
  const dir = outDir || useApp.getState().settings.defaultOutputDir || dirName(s.displayPath);
  return joinPath(dir, `${stem(s.displayPath)}${suffix}.${ext}`);
}

export function outDirOf(s: Src, outDir: string | null): string {
  return outDir || useApp.getState().settings.defaultOutputDir || dirName(s.displayPath);
}

export function useInitialSource(args: any): [Src | null, (s: Src | null) => void] {
  const [src, setSrc] = useState<Src | null>(() => (args?.sessionId ? srcFromDoc(args.sessionId) : null));
  useEffect(() => {
    if (args?.sessionId) setSrc(srcFromDoc(args.sessionId));
  }, [args?.sessionId]);
  return [src, setSrc];
}

export function RunBar(props: { disabled?: boolean; running?: boolean; onRun: () => void; label: string; children?: ReactNode }) {
  return (
    <div className="row" style={{ marginTop: 6 }}>
      {props.children}
      <div className="grow" />
      <button className="btn primary lg" disabled={props.disabled || props.running} onClick={props.onRun}>{props.running ? "Working..." : props.label}</button>
    </div>
  );
}
