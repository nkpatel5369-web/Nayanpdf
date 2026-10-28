import React, { useEffect, useMemo, useState } from "react";
import { ArrowDown, ArrowUp, CheckCircle2, CircleSlash, Copy, FolderOpen, FolderPlus, ListChecks, Play, Plus, Save, Square, Trash2, XCircle } from "lucide-react";
import type { JobInfo } from "@shared/ipc";
import { api, baseName, dirName, joinPath, SavedAction, stem } from "../api";
import { errorText, useApp } from "../store";
import { Check, Field, Notice, ProgressBar, Spinner } from "../components/ui";
import { LANG_NAMES } from "./tools/BasicTools";

/** Step types an action can contain (the engine's action.run checks the same list). */
const STEP_DEFS: { op: string; label: string; defaults: Record<string, any> }[] = [
  { op: "ocr", label: "Recognize text (OCR)", defaults: { languages: ["eng"] } },
  { op: "compress", label: "Compress", defaults: { preset: "balanced" } },
  { op: "watermark", label: "Watermark", defaults: { text: "CONFIDENTIAL", opacity: 0.2, angle: 45, fontSize: 60 } },
  { op: "headerFooter", label: "Header & footer", defaults: { header: { center: "" }, footer: { left: "{filename}", right: "Page {page} of {total}" } } },
  { op: "pageNumbers", label: "Page numbers", defaults: { position: "bottom-center", numberFormat: "pageOf" } },
  { op: "redactPatterns", label: "Redact (PAN, Aadhaar, ...)", defaults: { patterns: ["pan", "aadhaar"], terms: [] } },
  { op: "removeBlankPages", label: "Remove blank pages", defaults: {} },
  { op: "rotate", label: "Rotate pages", defaults: { pages: "all", angle: 90 } },
  { op: "flatten", label: "Flatten comments and fields", defaults: { annotations: true, forms: true } },
  { op: "setMetadata", label: "Set document properties", defaults: { fields: { author: "" } } },
  { op: "removeMetadata", label: "Remove document properties", defaults: {} },
  { op: "pdfa", label: "Save as PDF/A", defaults: { level: "2b" } },
  { op: "protect", label: "Protect with a password", defaults: {} },
];
const LABEL = Object.fromEntries(STEP_DEFS.map((d) => [d.op, d.label]));
const PATTERNS: [string, string][] = [["pan", "PAN"], ["gstin", "GSTIN"], ["aadhaar", "Aadhaar-like number"], ["ifsc", "IFSC"], ["account", "Bank account-like number"], ["phone", "Mobile number"], ["email", "Email address"], ["card", "Card-like number"]];

/** Ready-made actions for common office work. They can be run as they are or saved as a copy and changed. */
const BUILT_IN: SavedAction[] = [
  { id: "b:searchable", name: "Make scans searchable", description: "OCR, then compress.", naming: "{name}_searchable", updatedAt: 0, steps: [{ op: "ocr", languages: ["eng"] }, { op: "compress", preset: "balanced" }] },
  { id: "b:client", name: "Client copy: flatten and clean", description: "Fix comments and form entries into the page, remove document properties, compress.", naming: "{name}_client", updatedAt: 0, steps: [{ op: "flatten", annotations: true, forms: true }, { op: "removeMetadata" }, { op: "compress", preset: "balanced" }] },
  { id: "b:mask", name: "Mask PAN and Aadhaar", description: "Permanently redact PAN and Aadhaar-like numbers, then remove document properties.", naming: "{name}_masked", updatedAt: 0, steps: [{ op: "redactPatterns", patterns: ["pan", "aadhaar"], terms: [] }, { op: "removeMetadata" }] },
  { id: "b:archive", name: "Archive as PDF/A", description: "OCR, then save as PDF/A-2b for long-term keeping.", naming: "{name}_archive", updatedAt: 0, steps: [{ op: "ocr", languages: ["eng"] }, { op: "pdfa", level: "2b" }] },
  { id: "b:confidential", name: "Confidential with page numbers", description: "CONFIDENTIAL watermark and \"Page X of Y\" on every page.", naming: "{name}_confidential", updatedAt: 0, steps: [{ op: "watermark", text: "CONFIDENTIAL", opacity: 0.2, angle: 45, fontSize: 60 }, { op: "pageNumbers", position: "bottom-center", numberFormat: "pageOf" }] },
];

interface Row { path: string; jobId?: string }

export function ActionsView() {
  const settings = useApp((s) => s.settings);
  const caps = useApp((s) => s.caps);
  const jobs = useApp((s) => s.jobs);
  const toast = useApp((s) => s.toast);
  const [saved, setSaved] = useState<SavedAction[]>([]);
  const [selId, setSelId] = useState<string>(BUILT_IN[0].id);
  const [draft, setDraft] = useState<SavedAction>(BUILT_IN[0]);
  const [dirty, setDirty] = useState(false);
  const [rows, setRows] = useState<Row[]>([]);
  const [outDir, setOutDir] = useState("");
  const [recursive, setRecursive] = useState(false);
  const [pw, setPw] = useState("");
  const [pw2, setPw2] = useState("");
  const [addOp, setAddOp] = useState("ocr");
  const langsAvail: string[] = (caps?.capabilities?.ocrLanguages || ["eng"]).filter((l: string) => l !== "osd");

  useEffect(() => {
    api.actions().then(setSaved).catch(() => setSaved([]));
  }, []);
  useEffect(() => window.nayan.onFilesDropped((paths) => addPaths(paths)), []);

  const builtIn = draft.id.startsWith("b:");
  const discardOk = async () => !dirty || (await api.confirm("Discard changes?", `Discard the changes to "${draft.name}"?`, "The saved version is kept.", ["Discard", "Cancel"])) === 0;
  const pick = async (a: SavedAction) => {
    if (!(await discardOk())) return;
    setSelId(a.id);
    setDraft(JSON.parse(JSON.stringify(a)));
    setDirty(false);
  };
  const change = (patch: Partial<SavedAction>) => {
    setDraft((d) => ({ ...d, ...patch }));
    setDirty(true);
  };
  const setStep = (i: number, patch: Record<string, any>) => change({ steps: draft.steps.map((s, k) => (k === i ? { ...s, ...patch } : s)) });
  const moveStep = (i: number, d: number) => {
    const st = draft.steps.slice();
    const j = i + d;
    if (j < 0 || j >= st.length) return;
    [st[i], st[j]] = [st[j], st[i]];
    change({ steps: st });
  };
  const addStep = () => {
    const def = STEP_DEFS.find((d) => d.op === addOp)!;
    const st = [...draft.steps, { op: def.op, ...JSON.parse(JSON.stringify(def.defaults)) }];
    // Protection has to come last: keep it there when a step is added after it.
    const prot = st.findIndex((s) => s.op === "protect");
    if (prot >= 0 && prot !== st.length - 1) st.push(st.splice(prot, 1)[0]);
    change({ steps: st });
  };
  const validate = async (steps: any[]) => {
    try {
      await api.query("action.validate", { steps });
      return true;
    } catch (e) {
      toast({ kind: "error", title: "The action cannot run", body: errorText(e) });
      return false;
    }
  };
  const save = async (asNew = false) => {
    if (!(await validate(draft.steps))) return;
    const list = await api.saveAction({ ...draft, id: asNew || builtIn ? undefined : draft.id, name: asNew || builtIn ? `${draft.name}${builtIn ? "" : " (copy)"}` : draft.name });
    setSaved(list);
    setSelId(list[0].id);
    setDraft(JSON.parse(JSON.stringify(list[0])));
    setDirty(false);
    toast({ kind: "success", title: `Action "${list[0].name}" saved`, body: "Passwords are never saved with an action." });
  };
  const remove = async () => {
    if (builtIn || (await api.confirm("Delete action?", `Delete the action "${draft.name}"?`, "Files it produced are not affected.", ["Delete", "Cancel"])) !== 0) return;
    setSaved(await api.deleteAction(draft.id));
    setDirty(false);
    setSelId(BUILT_IN[0].id);
    setDraft(JSON.parse(JSON.stringify(BUILT_IN[0])));
  };
  const newAction = async () => {
    if (!(await discardOk())) return;
    setSelId("new");
    setDraft({ id: "new", name: "New action", description: "", naming: "{name}_processed", updatedAt: 0, steps: [] });
    setDirty(true);
  };

  // ------------------------------------------------------------ files and run
  const addPaths = (paths: string[]) => {
    const ok = paths.filter((p) => /\.pdf$/i.test(p));
    setRows((r) => [...r, ...ok.filter((p) => !r.some((x) => x.path === p)).map((p) => ({ path: p }))]);
    if (ok.length < paths.length) toast({ kind: "warning", title: `${paths.length - ok.length} file(s) skipped`, body: "Actions run on PDF files." });
  };
  const addFolder = async () => {
    const d = await api.openFolder("Choose a folder of PDF files");
    if (d) addPaths(await api.scanFolder(d, ["pdf"], recursive));
  };
  const jobMap = useMemo(() => Object.fromEntries(jobs.map((j) => [j.id, j])), [jobs]);
  const rowJobs = rows.map((r) => (r.jobId ? jobMap[r.jobId] : undefined));
  const running = rowJobs.some((j) => j && (j.status === "running" || j.status === "queued"));
  const counts = rowJobs.reduce((a, j) => { if (j) a[j.status] = (a[j.status] || 0) + 1; return a; }, {} as Record<string, number>);
  const needsPw = draft.steps.some((s) => s.op === "protect");
  const pwOk = !needsPw || (pw.length >= 4 && pw === pw2);
  const outFor = (p: string) => {
    const n = (draft.naming || "{name}_processed").replace("{name}", stem(p)).replace("{date}", new Date().toISOString().slice(0, 10)).replace(/[<>:"/\\|?*]/g, "_");
    return joinPath(outDir || settings.defaultOutputDir || dirName(p), n + ".pdf");
  };
  const run = async () => {
    if (!(await validate(draft.steps))) return;
    // The password goes to the jobs in memory only; it is not saved or logged.
    const steps = draft.steps.map((s) => (s.op === "protect" ? { ...s, userPassword: pw, ownerPassword: pw } : s));
    const batch = "a" + Date.now().toString(36);
    const next: Row[] = [];
    for (const r of rows) {
      if (r.jobId && jobMap[r.jobId]?.status === "succeeded") {
        next.push(r);
        continue;
      }
      try {
        const j = await api.startJob("action.run", { path: r.path, output: outFor(r.path), steps, name: draft.name }, `${draft.name}: ${baseName(r.path)}`, batch);
        next.push({ path: r.path, jobId: j.id });
      } catch (e) {
        next.push({ path: r.path });
        toast({ kind: "error", title: `Could not queue ${baseName(r.path)}`, body: errorText(e) });
      }
    }
    setRows(next);
  };
  const cancelAll = () => rowJobs.forEach((j) => j && (j.status === "running" || j.status === "queued") && api.cancelJob(j.id));

  const list = [...saved, ...BUILT_IN];
  return (
    <div className="page">
      <div className="page-narrow" style={{ maxWidth: 1320 }}>
        <div className="tool-head">
          <div className="ic" style={{ background: "var(--accent-soft)", color: "var(--accent)" }}><ListChecks size={22} /></div>
          <div><h1>Saved actions</h1><div className="muted">Run the same steps, in order, on one file or a whole folder, and keep the list for next time. Originals are never changed.</div></div>
        </div>
        <div className="actions-grid">
          <div className="card actions-list" aria-label="Actions">
            <div className="row panel-pad" style={{ borderBottom: "1px solid var(--border)" }}>
              <strong className="grow">Actions</strong>
              <button className="btn sm" onClick={newAction}><Plus size={14} /> New</button>
            </div>
            {saved.length > 0 && <div className="list-head subtle">Yours</div>}
            {list.map((a, i) => (
              <React.Fragment key={a.id}>
                {i === saved.length && <div className="list-head subtle">Ready-made</div>}
                <button className={"action-item" + (selId === a.id ? " on" : "")} onClick={() => pick(a)}>
                  <span className="ellipsis">{a.name}</span>
                  <span className="subtle ellipsis">{a.steps.map((s) => LABEL[s.op] || s.op).join(" → ")}</span>
                </button>
              </React.Fragment>
            ))}
          </div>

          <div className="card card-pad action-editor" aria-label="Action steps">
            <div className="row">
              <input className="input grow" aria-label="Action name" value={draft.name} onChange={(e) => change({ name: e.target.value })} />
            </div>
            <input className="input" aria-label="Action description" style={{ marginTop: 6 }} placeholder="What this action is for (optional)" value={draft.description} onChange={(e) => change({ description: e.target.value })} />
            <ol className="step-list">
              {draft.steps.map((s, i) => (
                <li key={i} className="step-card">
                  <div className="row">
                    <span className="step-no">{i + 1}</span>
                    <strong className="grow">{LABEL[s.op] || s.op}</strong>
                    <button className="icon-btn xs" title="Move up" disabled={i === 0} onClick={() => moveStep(i, -1)}><ArrowUp size={14} /></button>
                    <button className="icon-btn xs" title="Move down" disabled={i === draft.steps.length - 1} onClick={() => moveStep(i, 1)}><ArrowDown size={14} /></button>
                    <button className="icon-btn xs" title="Remove step" onClick={() => change({ steps: draft.steps.filter((_, k) => k !== i) })}><Trash2 size={14} /></button>
                  </div>
                  <StepOptions step={s} langs={langsAvail} onChange={(p) => setStep(i, p)} />
                </li>
              ))}
            </ol>
            {!draft.steps.length && <div className="empty">No steps yet. Add the first one below.</div>}
            <div className="row" style={{ marginTop: 8 }}>
              <select className="input grow" aria-label="Step to add" value={addOp} onChange={(e) => setAddOp(e.target.value)}>
                {STEP_DEFS.map((d) => <option key={d.op} value={d.op}>{d.label}</option>)}
              </select>
              <button className="btn" onClick={addStep}><Plus size={14} /> Add step</button>
            </div>
            <div style={{ height: 10 }} />
            <Field label="Name of each result" hint="{name} = the source file's name, {date} = today. An existing file is never overwritten.">
              <input className="input" aria-label="Result name rule" value={draft.naming} onChange={(e) => change({ naming: e.target.value })} />
            </Field>
            <div className="row" style={{ marginTop: 6 }}>
              {!builtIn && <button className="btn sm" title="Delete this action" onClick={remove}><Trash2 size={14} /> Delete</button>}
              <div className="grow" />
              {!builtIn && draft.id !== "new" && <button className="btn sm" onClick={() => save(true)}><Copy size={14} /> Save as copy</button>}
              <button className="btn sm primary" disabled={!draft.steps.length || !draft.name.trim() || (!dirty && !builtIn)} onClick={() => save(false)}>
                <Save size={14} /> {builtIn ? "Save as my action" : "Save action"}
              </button>
            </div>
          </div>

          <div className="card action-run">
            <div className="row wrap panel-pad" style={{ borderBottom: "1px solid var(--border)", gap: 8 }}>
              <button className="btn" onClick={async () => addPaths(await api.openFiles("pdf", true))}><Plus size={15} /> Add files</button>
              <button className="btn" onClick={addFolder}><FolderPlus size={15} /> Add folder</button>
              <Check checked={recursive} onChange={setRecursive}><span style={{ whiteSpace: "nowrap" }}>Sub-folders</span></Check>
              <div className="grow" />
              {rows.length > 0 && !running && <button className="btn sm ghost" onClick={() => setRows([])}><Trash2 size={14} /> Clear</button>}
            </div>
            <div style={{ maxHeight: "calc(100vh - 460px)", minHeight: 120, overflow: "auto" }}>
              <table className="tbl action-files">
                <tbody>
                  {rows.length === 0 && <tr><td><div className="empty">Add PDF files or a folder, or drop files here.</div></td></tr>}
                  {rows.map((r, i) => {
                    const j: JobInfo | undefined = r.jobId ? jobMap[r.jobId] : undefined;
                    return (
                      <tr key={r.path}>
                        <td style={{ maxWidth: 0 }}>
                          <div className="ellipsis" title={r.path}>{baseName(r.path)}</div>
                          {j?.status === "running" && <><div className="subtle ellipsis">{j.progress?.message || j.statusMessage || "Working"}</div><ProgressBar current={j.progress?.current} total={j.progress?.total} /></>}
                          {j?.status === "succeeded" && j.outputs?.[0] && <div className="subtle ellipsis" title={j.outputs[0]}>&rarr; {baseName(j.outputs[0])}</div>}
                          {j?.status === "failed" && <div className="subtle" style={{ color: "var(--danger)" }}>{j.error?.message}</div>}
                        </td>
                        <td style={{ width: 110 }}>
                          {!j && <span className="subtle">Ready</span>}
                          {j?.status === "queued" && <span className="badge">Queued</span>}
                          {j?.status === "running" && <Spinner size={13} />}
                          {j?.status === "succeeded" && <span className="badge ok"><CheckCircle2 size={12} /> Done</span>}
                          {j?.status === "failed" && <span className="badge err"><XCircle size={12} /> Failed</span>}
                          {j?.status === "cancelled" && <span className="badge"><CircleSlash size={12} /> Cancelled</span>}
                        </td>
                        <td style={{ width: 40 }}>
                          {j?.status === "succeeded" && j.outputs?.[0] && <button className="icon-btn" title="Show in folder" onClick={() => api.reveal(j.outputs![0])}><FolderOpen size={15} /></button>}
                          {!j && <button className="icon-btn" title="Remove" onClick={() => setRows(rows.filter((_, k) => k !== i))}><Trash2 size={15} /></button>}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <div className="panel-pad" style={{ borderTop: "1px solid var(--border)" }}>
              <Field label="Save results in">
                <div className="row">
                  <div className="input ellipsis" style={{ display: "flex", alignItems: "center", color: outDir ? "var(--text)" : "var(--text-3)" }} title={outDir}>{outDir || settings.defaultOutputDir || "The folder of each file"}</div>
                  <button className="btn" onClick={async () => { const d = await api.pickOutputDir(); if (d) setOutDir(d); }}>Change</button>
                </div>
              </Field>
              {needsPw && (
                <div className="grid2">
                  <Field label="Password for the results"><input className="input" type="password" aria-label="Action password" value={pw} onChange={(e) => setPw(e.target.value)} /></Field>
                  <Field label="Repeat password"><input className="input" type="password" aria-label="Repeat action password" value={pw2} onChange={(e) => setPw2(e.target.value)} /></Field>
                </div>
              )}
              {needsPw && !pwOk && <div className="subtle">At least 4 characters, typed the same twice. It is used for this run only and never saved.</div>}
              {Object.keys(counts).length > 0 && (
                <div className="row" style={{ margin: "6px 0" }}>
                  <span className="badge ok">{counts.succeeded || 0} done</span>
                  <span className="badge err">{counts.failed || 0} failed</span>
                  <span className="badge info">{(counts.running || 0) + (counts.queued || 0)} remaining</span>
                  <div className="grow" />
                  {running && <button className="btn sm" onClick={cancelAll}><Square size={13} /> Cancel all</button>}
                </div>
              )}
              <button className="btn primary lg" style={{ width: "100%" }} disabled={!rows.length || running || !draft.steps.length || !pwOk} onClick={run}>
                <Play size={16} /> Run "{draft.name}"{rows.length ? ` on ${rows.length} file${rows.length === 1 ? "" : "s"}` : ""}
              </button>
              {dirty && !builtIn && <Notice kind="info">This run uses the steps as shown; save the action to keep them.</Notice>}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/** The few options each step needs; the rest use the same defaults as the single tools. */
function StepOptions({ step, langs, onChange }: { step: Record<string, any>; langs: string[]; onChange: (p: Record<string, any>) => void }) {
  switch (step.op) {
    case "ocr":
      return (
        <div className="row wrap step-opts">
          {langs.map((l) => (
            <Check key={l} checked={(step.languages || []).includes(l)} onChange={(v) => onChange({ languages: v ? [...(step.languages || []), l] : (step.languages || []).filter((x: string) => x !== l) })}>{LANG_NAMES[l] || l}</Check>
          ))}
          <span className="subtle">Pages that already have text are left as they are.</span>
        </div>
      );
    case "compress":
      return (
        <div className="step-opts">
          <select className="input sm" aria-label="Compression" value={step.preset || "balanced"} onChange={(e) => onChange({ preset: e.target.value })}>
            <option value="maximum">Maximum quality</option><option value="high">High quality</option><option value="balanced">Balanced</option><option value="strong">High compression</option>
          </select>
        </div>
      );
    case "watermark":
      return (
        <div className="row wrap step-opts">
          <input className="input sm grow" aria-label="Watermark text" value={step.text || ""} onChange={(e) => onChange({ text: e.target.value })} />
          <label className="subtle">Opacity <input className="input sm" type="number" min={5} max={100} style={{ width: 64 }} value={Math.round((step.opacity ?? 0.2) * 100)} onChange={(e) => onChange({ opacity: Math.max(5, Math.min(100, parseInt(e.target.value) || 20)) / 100 })} />%</label>
          <label className="subtle">Angle <input className="input sm" type="number" style={{ width: 64 }} value={step.angle ?? 45} onChange={(e) => onChange({ angle: parseInt(e.target.value) || 0 })} /></label>
        </div>
      );
    case "headerFooter":
      return (
        <div className="step-opts">
          <div className="grid3">
            {(["left", "center", "right"] as const).map((k) => <input key={"h" + k} className="input sm" aria-label={`Header ${k}`} placeholder={`Header ${k}`} value={step.header?.[k] || ""} onChange={(e) => onChange({ header: { ...(step.header || {}), [k]: e.target.value } })} />)}
            {(["left", "center", "right"] as const).map((k) => <input key={"f" + k} className="input sm" aria-label={`Footer ${k}`} placeholder={`Footer ${k}`} value={step.footer?.[k] || ""} onChange={(e) => onChange({ footer: { ...(step.footer || {}), [k]: e.target.value } })} />)}
          </div>
          <span className="subtle">{"{page} {total} {date} {filename}"} are filled in.</span>
        </div>
      );
    case "pageNumbers":
      return (
        <div className="row wrap step-opts">
          <select className="input sm" aria-label="Number position" value={step.position || "bottom-center"} onChange={(e) => onChange({ position: e.target.value })}>
            {["bottom-center", "bottom-right", "bottom-left", "top-center", "top-right", "top-left"].map((p) => <option key={p} value={p}>{p.replace("-", " ")}</option>)}
          </select>
          <select className="input sm" aria-label="Number format" value={step.numberFormat || "pageOf"} onChange={(e) => onChange({ numberFormat: e.target.value })}>
            <option value="pageOf">Page 1 of 9</option><option value="plain">1</option>
          </select>
        </div>
      );
    case "redactPatterns":
      return (
        <div className="step-opts">
          <div className="row wrap">
            {PATTERNS.map(([id, label]) => (
              <Check key={id} checked={(step.patterns || []).includes(id)} onChange={(v) => onChange({ patterns: v ? [...(step.patterns || []), id] : (step.patterns || []).filter((x: string) => x !== id) })}>{label}</Check>
            ))}
          </div>
          <input className="input sm" aria-label="Words to redact" placeholder="Also redact these words or names (comma separated)" value={(step.terms || []).join(", ")}
            onChange={(e) => onChange({ terms: e.target.value.split(",").map((t) => t.trim()).filter(Boolean) })} />
          <span className="subtle">Permanent: the text is removed from the page, not covered. Scanned pages need an OCR step first.</span>
        </div>
      );
    case "rotate":
      return (
        <div className="row wrap step-opts">
          <select className="input sm" aria-label="Rotate by" value={step.angle ?? 90} onChange={(e) => onChange({ angle: parseInt(e.target.value) })}>
            <option value={90}>90° clockwise</option><option value={180}>180°</option><option value={270}>90° counter-clockwise</option>
          </select>
          <select className="input sm" aria-label="Pages to rotate" value={step.pages || "all"} onChange={(e) => onChange({ pages: e.target.value })}>
            <option value="all">All pages</option><option value="odd">Odd pages</option><option value="even">Even pages</option>
          </select>
        </div>
      );
    case "flatten":
      return <div className="step-opts"><Check checked={step.forms !== false} onChange={(v) => onChange({ forms: v })}>Form fields too</Check></div>;
    case "setMetadata":
      return (
        <div className="grid3 step-opts">
          {(["author", "subject", "keywords"] as const).map((k) => <input key={k} className="input sm" aria-label={`Property ${k}`} placeholder={k[0].toUpperCase() + k.slice(1)} value={step.fields?.[k] || ""} onChange={(e) => onChange({ fields: { ...(step.fields || {}), [k]: e.target.value } })} />)}
        </div>
      );
    case "pdfa":
      return (
        <div className="step-opts">
          <select className="input sm" aria-label="PDF/A level" value={step.level || "2b"} onChange={(e) => onChange({ level: e.target.value })}>
            <option value="1b">PDF/A-1b</option><option value="2b">PDF/A-2b</option><option value="3b">PDF/A-3b</option>
          </select>
        </div>
      );
    case "protect":
      return <div className="step-opts subtle">An open password (AES-256). It is asked for when you run the action and is never saved.</div>;
    default:
      return null;
  }
}
