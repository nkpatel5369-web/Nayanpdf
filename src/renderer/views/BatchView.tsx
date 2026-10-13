import React, { useEffect, useMemo, useState } from "react";
import { CheckCircle2, CircleSlash, FolderOpen, FolderPlus, Play, Plus, RotateCcw, Square, Trash2, XCircle } from "lucide-react";
import type { JobInfo } from "@shared/ipc";
import { api, baseName, dirName, joinPath, stem } from "../api";
import { useApp } from "../store";
import { Check, Field, Notice, ProgressBar, Seg, Spinner } from "../components/ui";
import { StampForm, StampSpec } from "../components/StampForm";
import { LANG_NAMES } from "./tools/BasicTools";

type Op = "compress" | "ocr" | "word" | "excel" | "ppt" | "images" | "text" | "stamp" | "metaRemove" | "extract" | "protect" | "pdfa" | "officeToPdf" | "imagesToPdf";

const OPS: { id: Op; label: string; suffix: string; ext: string; input: "pdf" | "office" | "images" }[] = [
  { id: "compress", label: "Compress", suffix: "_compressed", ext: "pdf", input: "pdf" },
  { id: "ocr", label: "OCR (searchable PDF)", suffix: "_ocr", ext: "pdf", input: "pdf" },
  { id: "word", label: "Convert to Word", suffix: "", ext: "docx", input: "pdf" },
  { id: "excel", label: "Convert to Excel", suffix: "", ext: "xlsx", input: "pdf" },
  { id: "ppt", label: "Convert to PowerPoint", suffix: "", ext: "pptx", input: "pdf" },
  { id: "images", label: "Export pages as images", suffix: "_images", ext: "", input: "pdf" },
  { id: "text", label: "Extract text", suffix: "", ext: "txt", input: "pdf" },
  { id: "stamp", label: "Watermark / numbers / Bates", suffix: "_stamped", ext: "pdf", input: "pdf" },
  { id: "metaRemove", label: "Remove metadata", suffix: "_clean", ext: "pdf", input: "pdf" },
  { id: "extract", label: "Extract pages", suffix: "_pages", ext: "pdf", input: "pdf" },
  { id: "protect", label: "Add password", suffix: "_protected", ext: "pdf", input: "pdf" },
  { id: "pdfa", label: "Convert to PDF/A", suffix: "_pdfa", ext: "pdf", input: "pdf" },
  { id: "officeToPdf", label: "Office files to PDF", suffix: "", ext: "pdf", input: "office" },
  { id: "imagesToPdf", label: "Images to PDF (one per image)", suffix: "", ext: "pdf", input: "images" },
];

const EXT: Record<string, string[]> = {
  pdf: ["pdf"],
  office: ["doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp"],
  images: ["png", "jpg", "jpeg", "tif", "tiff", "bmp", "webp"],
};

interface Row {
  path: string;
  jobId?: string;
}

export function BatchView() {
  const settings = useApp((s) => s.settings);
  const caps = useApp((s) => s.caps);
  const jobs = useApp((s) => s.jobs);
  const [op, setOp] = useState<Op>("compress");
  const [rows, setRows] = useState<Row[]>([]);
  const [outDir, setOutDir] = useState<string>("");
  const [naming, setNaming] = useState("{name}_compressed");
  const [recursive, setRecursive] = useState(false);
  const [preset, setPreset] = useState<string>(settings.compressionPreset);
  const [langs, setLangs] = useState<string[]>(settings.ocrLanguages);
  const [pages, setPages] = useState("1");
  const [pw, setPw] = useState("");
  const [stamp, setStamp] = useState<StampSpec | null>(null);
  const [batchId, setBatchId] = useState<string | null>(null);
  const [wordMode, setWordMode] = useState(settings.wordMode);
  const [imgFmt, setImgFmt] = useState(settings.imageFormat);
  const def = OPS.find((o) => o.id === op)!;

  useEffect(() => {
    setNaming(def.suffix ? `{name}${def.suffix}` : "{name}");
  }, [op]);

  useEffect(() => window.nayan.onFilesDropped((paths) => addPaths(paths)), [op]);

  const accepted = (p: string) => EXT[def.input].includes(p.split(".").pop()!.toLowerCase());
  const addPaths = (paths: string[]) => {
    const ok = paths.filter(accepted);
    setRows((r) => [...r, ...ok.filter((p) => !r.some((x) => x.path === p)).map((p) => ({ path: p }))]);
    if (ok.length < paths.length) useApp.getState().toast({ kind: "warning", title: `${paths.length - ok.length} file(s) skipped`, body: `Not a ${def.input} file for "${def.label}".` });
  };
  const addFolder = async () => {
    const d = await api.openFolder("Choose a folder of files");
    if (!d) return;
    const files = await api.scanFolder(d, EXT[def.input], recursive);
    addPaths(files);
    if (!outDir) setOutDir("");
  };

  const jobMap = useMemo(() => Object.fromEntries(jobs.map((j) => [j.id, j])), [jobs]);
  const batchJobs = rows.map((r) => (r.jobId ? jobMap[r.jobId] : undefined));
  const counts = batchJobs.reduce((a, j) => { if (j) a[j.status] = (a[j.status] || 0) + 1; return a; }, {} as Record<string, number>);
  const running = (counts.running || 0) + (counts.queued || 0) > 0;

  const nameFor = (path: string) => {
    const n = naming.replace("{name}", stem(path)).replace("{date}", new Date().toISOString().slice(0, 10));
    return n.replace(/[<>:"/\\|?*]/g, "_");
  };
  const outFor = (path: string, ext: string) => joinPath(outDir || settings.defaultOutputDir || dirName(path), nameFor(path) + (ext ? "." + ext : ""));

  const start = async () => {
    const id = "b" + Date.now().toString(36);
    setBatchId(id);
    let batesNext = stamp?.method === "stamp.bates" ? stamp.params.startNumber : 0;
    const next: Row[] = [];
    for (const r of rows) {
      if (r.jobId && jobMap[r.jobId]?.status === "succeeded") {
        next.push(r);
        continue;
      }
      const p = r.path;
      let method = "";
      let params: Record<string, any> = { path: p };
      switch (op) {
        case "compress": method = "compress.run"; params = { ...params, preset, output: outFor(p, "pdf") }; break;
        case "ocr": method = "ocr.run"; params = { ...params, languages: langs, output: outFor(p, "pdf") }; break;
        case "word": method = "convert.toWord"; params = { ...params, mode: wordMode, output: outFor(p, "docx") }; break;
        case "excel": method = "convert.toExcel"; params = { ...params, dateOrder: settings.excelDateOrder, output: outFor(p, "xlsx") }; break;
        case "ppt": method = "convert.toPowerPoint"; params = { ...params, mode: settings.pptMode, output: outFor(p, "pptx") }; break;
        case "images": method = "convert.toImages"; params = { ...params, format: imgFmt, dpi: settings.imageDpi, quality: settings.imageQuality, outputDir: outFor(p, ""), nameTemplate: "{name}_page_{page:03d}" }; break;
        case "text": method = "convert.toText"; params = { ...params, output: outFor(p, "txt") }; break;
        case "stamp":
          if (!stamp) return;
          method = stamp.method;
          params = { ...params, ...stamp.params, output: outFor(p, "pdf") };
          if (method === "stamp.bates") {
            // Continue Bates numbering across files: each file starts where the previous ended.
            const probe = await api.query("doc.probe", { path: p }).catch(() => null);
            params.startNumber = batesNext;
            batesNext += probe?.pages || 0;
          }
          break;
        case "metaRemove": method = "meta.remove"; params = { ...params, removeXmp: true, output: outFor(p, "pdf") }; break;
        case "extract": method = "pages.extract"; params = { ...params, pages, output: outFor(p, "pdf") }; break;
        case "protect": method = "security.encrypt"; params = { ...params, userPassword: pw, ownerPassword: pw, encryption: "AES-256", output: outFor(p, "pdf") }; break;
        case "pdfa": method = "util.pdfa"; params = { ...params, level: "2b", output: outFor(p, "pdf") }; break;
        case "officeToPdf": method = "convert.officeToPdf"; params = { ...params, output: outFor(p, "pdf") }; break;
        case "imagesToPdf": method = "create.fromImages"; params = { images: [p], output: outFor(p, "pdf") }; break;
      }
      try {
        const j = await api.startJob(method, params, `${def.label}: ${baseName(p)}`, id);
        next.push({ path: p, jobId: j.id });
      } catch (e: any) {
        next.push({ path: p });
        useApp.getState().toast({ kind: "error", title: `Could not queue ${baseName(p)}`, body: e.message });
      }
    }
    setRows(next);
  };
  const cancelAll = () => batchJobs.forEach((j) => j && (j.status === "running" || j.status === "queued") && api.cancelJob(j.id));
  const retryFailed = async () => {
    const next = rows.slice();
    for (let i = 0; i < next.length; i++) {
      const j = next[i].jobId ? jobMap[next[i].jobId!] : undefined;
      if (j && (j.status === "failed" || j.status === "cancelled")) {
        const n = await api.retryJob(j.id);
        if (n) next[i] = { ...next[i], jobId: n.id };
      }
    }
    setRows(next);
  };
  const ready = rows.length > 0 && !running && (op !== "protect" || pw.length >= 4) && (op !== "stamp" || !!stamp);

  return (
    <div className="page">
      <div className="page-narrow" style={{ maxWidth: 1240 }}>
        <div className="tool-head">
          <div className="ic" style={{ background: "var(--accent-soft)", color: "var(--accent)" }}><Play size={22} /></div>
          <div><h1>Batch processing</h1><div className="muted">Apply one operation to many files. Each file is processed independently; one failure does not stop the others.</div></div>
        </div>
        <div className="tool-body" style={{ gridTemplateColumns: "minmax(0,1fr) 360px" }}>
          <div className="card">
            <div className="row panel-pad" style={{ borderBottom: "1px solid var(--border)" }}>
              <button className="btn" onClick={async () => addPaths(await api.openFiles(def.input === "pdf" ? "pdf" : def.input, true))}><Plus size={15} /> Add files</button>
              <button className="btn" onClick={addFolder}><FolderPlus size={15} /> Add folder</button>
              <Check checked={recursive} onChange={setRecursive}>Include sub-folders</Check>
              <div className="grow" />
              {rows.length > 0 && !running && <button className="btn sm ghost" onClick={() => setRows([])}><Trash2 size={14} /> Clear</button>}
            </div>
            <div style={{ maxHeight: "calc(100vh - 330px)", overflow: "auto" }}>
              <table className="tbl">
                <thead><tr><th>File</th><th style={{ width: 200 }}>Status</th><th style={{ width: 70 }}></th></tr></thead>
                <tbody>
                  {rows.length === 0 && <tr><td colSpan={3}><div className="empty">Add files or a folder, or drop files here.</div></td></tr>}
                  {rows.map((r, i) => {
                    const j: JobInfo | undefined = r.jobId ? jobMap[r.jobId] : undefined;
                    return (
                      <tr key={r.path}>
                        <td style={{ maxWidth: 0 }}>
                          <div className="ellipsis" title={r.path}>{baseName(r.path)}</div>
                          <div className="subtle ellipsis">{dirName(r.path)}</div>
                          {j?.status === "succeeded" && j.outputs?.[0] && <div className="subtle ellipsis" title={j.outputs[0]}>&rarr; {baseName(j.outputs[0])}{j.outputs.length > 1 ? ` (+${j.outputs.length - 1})` : ""}</div>}
                          {j?.status === "failed" && <div className="subtle" style={{ color: "var(--danger)" }}>{j.error?.message}</div>}
                        </td>
                        <td>
                          {!j && <span className="subtle">Ready</span>}
                          {j?.status === "queued" && <span className="badge">Queued</span>}
                          {j?.status === "running" && (<><div className="row" style={{ gap: 6 }}><Spinner size={13} /><span className="subtle ellipsis">{j.progress?.message || j.statusMessage || "Working"}</span></div><ProgressBar current={j.progress?.current} total={j.progress?.total} /></>)}
                          {j?.status === "succeeded" && <span className="badge ok"><CheckCircle2 size={12} /> Done{j.warnings?.length ? ` · ${j.warnings.length} note(s)` : ""}</span>}
                          {j?.status === "failed" && <span className="badge err"><XCircle size={12} /> Failed</span>}
                          {j?.status === "cancelled" && <span className="badge"><CircleSlash size={12} /> Cancelled</span>}
                        </td>
                        <td>
                          {j?.status === "succeeded" && j.outputs?.[0] && <button className="icon-btn" title="Show in folder" onClick={() => api.reveal(j.outputs![0])}><FolderOpen size={15} /></button>}
                          {!j && <button className="icon-btn" title="Remove" onClick={() => setRows(rows.filter((_, k) => k !== i))}><Trash2 size={15} /></button>}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {batchId && (
              <div className="row panel-pad" style={{ borderTop: "1px solid var(--border)" }}>
                <span className="badge ok">{counts.succeeded || 0} succeeded</span>
                <span className="badge err">{counts.failed || 0} failed</span>
                <span className="badge">{counts.cancelled || 0} cancelled</span>
                <span className="badge info">{(counts.running || 0) + (counts.queued || 0)} remaining</span>
                <div className="grow" />
                {running && <button className="btn sm" onClick={cancelAll}><Square size={13} /> Cancel all</button>}
                {!running && (counts.failed || counts.cancelled) ? <button className="btn sm" onClick={retryFailed}><RotateCcw size={13} /> Retry failed</button> : null}
              </div>
            )}
          </div>
          <div className="card card-pad">
            <Field label="Operation">
              <select className="input" value={op} disabled={running} onChange={(e) => { setOp(e.target.value as Op); setRows([]); setBatchId(null); }}>
                {OPS.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
              </select>
            </Field>
            {op === "compress" && (
              <Field label="Preset"><select className="input" value={preset} onChange={(e) => setPreset(e.target.value)}><option value="maximum">Maximum quality</option><option value="high">High quality</option><option value="balanced">Balanced</option><option value="strong">High compression</option></select></Field>
            )}
            {op === "ocr" && (
              <Field label="Languages">
                <div className="row wrap">{(caps?.capabilities?.ocrLanguages || ["eng"]).filter((l: string) => l !== "osd").map((l: string) => <Check key={l} checked={langs.includes(l)} onChange={(v) => setLangs(v ? [...langs, l] : langs.filter((x) => x !== l))}>{LANG_NAMES[l] || l}</Check>)}</div>
              </Field>
            )}
            {op === "word" && <Field label="Style"><Seg value={wordMode} onChange={setWordMode} options={[{ value: "layout", label: "Keep layout" }, { value: "flow", label: "Easy editing" }]} /></Field>}
            {op === "images" && <Field label="Format"><select className="input" value={imgFmt} onChange={(e) => setImgFmt(e.target.value as any)}><option value="png">PNG</option><option value="jpg">JPEG</option><option value="tiff">TIFF</option><option value="webp">WebP</option><option value="bmp">BMP</option></select></Field>}
            {op === "extract" && <Field label="Pages to extract from each file" hint="e.g. 1   or 1-2, last"><input className="input" value={pages} onChange={(e) => setPages(e.target.value)} /></Field>}
            {op === "protect" && <Field label="Password (same for all files, min 4)"><input className="input" type="password" value={pw} onChange={(e) => setPw(e.target.value)} /></Field>}
            {op === "stamp" && <StampForm onChange={setStamp} />}
            {op === "stamp" && stamp?.method === "stamp.bates" && <Notice kind="info">Bates numbers continue from one file to the next, in the order listed.</Notice>}
            <div className="divider" />
            <Field label="Output folder">
              <div className="row">
                <div className="input ellipsis" style={{ display: "flex", alignItems: "center", color: outDir ? "var(--text)" : "var(--text-3)" }} title={outDir}>{outDir || settings.defaultOutputDir || "Next to each source file"}</div>
                <button className="btn" onClick={async () => { const d = await api.pickOutputDir(); if (d) setOutDir(d); }}>Change</button>
              </div>
            </Field>
            <Field label="Name rule" hint="{name} = source name, {date} = today. Existing files are never overwritten.">
              <input className="input" value={naming} onChange={(e) => setNaming(e.target.value)} />
            </Field>
            <div className="subtle">Running {settings.batchConcurrency} file(s) at a time (Settings).</div>
            <button className="btn primary lg" style={{ width: "100%", marginTop: 12 }} disabled={!ready} onClick={start}>
              <Play size={16} /> Start {rows.length ? `(${rows.filter((r) => !r.jobId || jobMap[r.jobId]?.status !== "succeeded").length} files)` : ""}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
