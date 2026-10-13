import React, { useEffect, useState } from "react";
import { FileWarning, Wrench } from "lucide-react";
import { api, dirName, joinPath, stem } from "../../api";
import { useApp } from "../../store";
import { JobView, OutputDir, openDocument, useJob } from "../../components/common";
import { Check, Field, Notice, Seg, Spinner } from "../../components/ui";
import { TOOLS, toolById, ToolDef } from "../../tools";
import { RunBar, SourcePicker, Src, ToolPage, outPath, srcParams, useInitialSource } from "./shared";

// ---------------------------------------------------------------- compare
const COLORS: Record<string, string> = { added: "#2b9e4a", removed: "#dc3030", modified: "#ee8c00", visual: "#1c7ed6", image: "#1c7ed6", layout: "#7c4ddb" };

export function CompareTool() {
  const [a, setA] = useState<Src | null>(null);
  const [b, setB] = useState<Src | null>(null);
  const [text, setText] = useState(true);
  const [visual, setVisual] = useState(true);
  const [sel, setSel] = useState<any>(null);
  const [imgs, setImgs] = useState<{ a?: string; b?: string }>({});
  const { job, run, running } = useJob();
  const res = job?.status === "succeeded" ? job.result : null;
  const go = () => {
    setSel(null);
    run("compare.run", { pathA: a!.path, passwordA: a!.password, pathB: b!.path, passwordB: b!.password, text, visual }, "Compare documents");
  };
  useEffect(() => {
    if (!sel || !a || !b) return;
    setImgs({});
    if (sel.pageA) api.query("pages.thumbnail", { path: a.path, password: a.password, page: sel.pageA, width: 560 }).then((t) => setImgs((x) => ({ ...x, a: t.png })));
    if (sel.pageB) api.query("pages.thumbnail", { path: b.path, password: b.password, page: sel.pageB, width: 560 }).then((t) => setImgs((x) => ({ ...x, b: t.png })));
  }, [sel?.id]);
  const report = async () => {
    const out = await api.saveFile(joinPath(dirName(b!.path!), `comparison_${stem(a!.path!)}_vs_${stem(b!.path!)}.pdf`), "pdf", "Save comparison report");
    if (!out) return;
    const j = await api.startJob("compare.report", { pathA: a!.path, passwordA: a!.password, pathB: b!.path, passwordB: b!.password, output: out, text, visual }, "Comparison report");
    useApp.getState().toast({ kind: "info", title: "Building comparison report", body: "It will open when ready." });
    const { waitForJob } = await import("../../api");
    const d = await waitForJob(j.id);
    if (d.status === "succeeded") openDocument(d.result.output);
    else useApp.getState().toast({ kind: "error", title: "Report failed", body: d.error?.message });
  };
  const sameDiffs = res ? res.differences.filter((d: any) => sel && d.pageA === sel.pageA && d.pageB === sel.pageB) : [];
  return (
    <ToolPage tool={toolById("compare")!}>
      <div className="grid2" style={{ gap: 14 }}>
        <SourcePicker value={a} onChange={setA} label="Original (A)" allowDocs={false} />
        <SourcePicker value={b} onChange={setB} label="Revised (B)" allowDocs={false} />
      </div>
      <div className="card card-pad" style={{ marginBottom: 14 }}>
        <div className="row wrap">
          <Check checked={text} onChange={setText}>Text comparison</Check>
          <Check checked={visual} onChange={setVisual}>Visual comparison (images, layout, graphics)</Check>
          <div className="grow" />
          {res && <button className="btn" onClick={report}>Save report PDF</button>}
          <button className="btn primary" disabled={!a || !b || running} onClick={go}>{running ? "Comparing..." : "Compare"}</button>
        </div>
        {job && job.status !== "succeeded" && <JobView job={job} />}
      </div>
      {res && (
        <div className="card" style={{ display: "grid", gridTemplateColumns: "320px 1fr", minHeight: 500 }}>
          <div style={{ borderRight: "1px solid var(--border)", maxHeight: 640, overflow: "auto" }}>
            <div className="panel-pad">
              <strong>{res.identical ? "No differences found" : `${res.differences.length} difference(s)`}</strong>
              <div className="row wrap" style={{ gap: 4, marginTop: 6 }}>
                {Object.entries(res.summary).map(([k, v]) => <span key={k} className="badge" style={{ color: COLORS[k] }}>{k}: {String(v)}</span>)}
              </div>
              <div className="subtle" style={{ marginTop: 6 }}>A: {res.pageCountA} pages · B: {res.pageCountB} pages. Methods: {res.methods.join(", ")}.</div>
            </div>
            {res.differences.map((d: any) => (
              <div key={d.id} className={"list-item" + (sel?.id === d.id ? " on" : "")} onClick={() => setSel(d)}>
                <div className="row"><span className="badge" style={{ color: COLORS[d.type] || "var(--text-2)" }}>{d.type}</span><span className="subtle">{d.pageA ? `A p${d.pageA}` : ""} {d.pageB ? `B p${d.pageB}` : ""}{d.field ? d.field : ""}</span></div>
                {(d.textA || d.textB) && (
                  <div style={{ fontSize: 12, marginTop: 4 }}>
                    {d.textA && <div style={{ color: "#dc3030", textDecoration: d.type !== "added" ? "line-through" : undefined }} className="ellipsis">{d.textA}</div>}
                    {d.textB && <div style={{ color: "#2b9e4a" }} className="ellipsis">{d.textB}</div>}
                  </div>
                )}
                {d.detail && <div className="subtle">{d.detail}</div>}
              </div>
            ))}
          </div>
          <div style={{ padding: 14, background: "var(--viewer-bg)" }}>
            {!sel && <div className="empty">Select a difference to see it on the pages.</div>}
            {sel && (
              <div className="grid2" style={{ gap: 14 }}>
                {(["a", "b"] as const).map((side) => (
                  <div key={side}>
                    <div className="label" style={{ marginBottom: 6 }}>{side === "a" ? `A${sel.pageA ? " - page " + sel.pageA : ""}` : `B${sel.pageB ? " - page " + sel.pageB : ""}`}</div>
                    {imgs[side] ? (
                      <div className="cmp-page">
                        <img src={"data:image/png;base64," + imgs[side]} style={{ width: "100%" }} />
                        {sameDiffs.flatMap((d: any) => (d[side === "a" ? "relA" : "relB"] || []).map((r: number[], i: number) => (
                          <div key={d.id + "-" + i} className="diff-mark" style={{ left: r[0] * 100 + "%", top: r[1] * 100 + "%", width: (r[2] - r[0]) * 100 + "%", height: (r[3] - r[1]) * 100 + "%",
                            background: (COLORS[d.type] || "#888") + "33", outline: `${d.id === sel.id ? 2 : 1}px solid ${COLORS[d.type] || "#888"}` }} />
                        )))}
                      </div>
                    ) : (sel[side === "a" ? "pageA" : "pageB"] ? <Spinner /> : <div className="empty">No corresponding page</div>)}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
      <Notice kind="info">Comparison finds differences in text, embedded images, page layout, page order and metadata. It cannot guarantee every possible change (e.g. invisible objects or form scripts) is detected.</Notice>
    </ToolPage>
  );
}

// ---------------------------------------------------------------- utilities
type Util = "pdfa" | "repair" | "linearize" | "validate" | "blank" | "pagesize" | "crop" | "labels";

export function UtilitiesTool(props: { args?: any }) {
  const caps = useApp((s) => s.caps);
  const [src, setSrc] = useInitialSource(props.args);
  const [util, setUtil] = useState<Util>("validate");
  const [outDir, setOutDir] = useState<string | null>(null);
  const [level, setLevel] = useState("2b");
  const [size, setSize] = useState("A4");
  const [orient, setOrient] = useState("auto");
  const [fit, setFit] = useState("fit");
  const [crop, setCrop] = useState({ top: 10, right: 10, bottom: 10, left: 10 });
  const [sens, setSens] = useState(0.003);
  const [blank, setBlank] = useState<any>(null);
  const [validation, setValidation] = useState<any>(null);
  const [labels, setLabels] = useState<{ startpage: number; style: string; prefix: string; firstpagenum: number }[]>([{ startpage: 0, style: "r", prefix: "", firstpagenum: 1 }, { startpage: 4, style: "D", prefix: "", firstpagenum: 1 }]);
  const [busy, setBusy] = useState(false);
  const { job, run, running } = useJob();
  const q = (method: string, params: any = {}) => (src!.sessionId ? api.docQuery(src!.sessionId, method, params) : api.query(method, { path: src!.path, password: src!.password, ...params }));
  const utils: { id: Util; label: string; desc: string }[] = [
    { id: "validate", label: "Validate / integrity", desc: "Structural check with qpdf and MuPDF, SHA-256 hash, update history." },
    { id: "repair", label: "Repair damaged PDF", desc: "Rebuild the file structure with two engines and keep the better result." },
    { id: "pdfa", label: "Convert to PDF/A", desc: "Archival format via Ghostscript, with basic pre-flight checks." },
    { id: "linearize", label: "Optimise for web (linearize)", desc: "Fast first-page display when opened over a network." },
    { id: "blank", label: "Find and remove blank pages", desc: "Detect empty pages, review, then remove." },
    { id: "pagesize", label: "Standardise page size", desc: "Scale every page to A4, Letter, etc." },
    { id: "crop", label: "Crop margins", desc: "Trim page edges (content outside stays in the file)." },
    { id: "labels", label: "Page labels", desc: "Roman numerals for front matter, A-1 style numbering..." },
  ];
  const go = async () => {
    const s = src!;
    const base = srcParams(s);
    if (util === "validate") {
      setBusy(true);
      try {
        setValidation(await q("security.integrity"));
      } finally {
        setBusy(false);
      }
      return;
    }
    if (util === "blank" && !blank) {
      setBusy(true);
      try {
        setBlank(await q("blank.detect", { sensitivity: sens }));
      } finally {
        setBusy(false);
      }
      return;
    }
    if (util === "blank") return run("pages.delete", { ...base, pages: blank.blankPages.map((b: any) => b.page), output: outPath(s, outDir, "_noblank", "pdf") }, `Remove blank pages ${s.name}`);
    if (util === "pdfa") return run("util.pdfa", { ...base, level, output: outPath(s, outDir, "_pdfa", "pdf") }, `PDF/A ${s.name}`);
    if (util === "repair") return run("util.repair", { ...base, output: outPath(s, outDir, "_repaired", "pdf") }, `Repair ${s.name}`);
    if (util === "linearize") return run("util.linearize", { ...base, output: outPath(s, outDir, "_web", "pdf") }, `Linearize ${s.name}`);
    if (util === "pagesize") return run("pagesize.standardize", { ...base, size, orientation: orient, fit, output: outPath(s, outDir, `_${size}`, "pdf") }, `Page size ${s.name}`);
    if (util === "crop") return run("pagesize.crop", { ...base, ...crop, unit: "mm", output: outPath(s, outDir, "_cropped", "pdf") }, `Crop ${s.name}`);
    if (util === "labels") return run("labels.set", { ...base, rules: labels, output: outPath(s, outDir, "_labels", "pdf") }, `Page labels ${s.name}`);
  };
  const needsGs = util === "pdfa" && caps?.capabilities && !caps.capabilities.ghostscript;
  return (
    <ToolPage tool={toolById("utilities")!} side={
      <div className="card card-pad">
        {!["validate"].includes(util) && <OutputDir value={outDir} onChange={setOutDir} />}
        <RunBar disabled={!src || needsGs || busy} running={running || busy} onRun={go} label={util === "validate" ? "Check" : util === "blank" && !blank ? "Detect blank pages" : "Run"} />
        <JobView job={job}>{(r) => r.checks && (
          <Notice kind={r.checks.passedBasicChecks ? "ok" : "warn"}>
            Pre-flight: XMP PDF/A id {r.checks.xmpPdfaId ? "yes" : "no"}, output intent {r.checks.outputIntent ? "yes" : "no"}, fonts embedded {r.checks.allFontsEmbedded ? "yes" : "no"}. This is not a formal conformance validation.
          </Notice>
        )}</JobView>
      </div>
    }>
      <SourcePicker value={src} onChange={(s) => { setSrc(s); setBlank(null); setValidation(null); }} />
      <div className="card card-pad">
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, marginBottom: 14 }}>
          {utils.map((u) => (
            <button key={u.id} className="tool-card" style={util === u.id ? { borderColor: "var(--accent)", background: "var(--accent-soft)" } : undefined} onClick={() => { setUtil(u.id); setBlank(null); }}>
              <Wrench size={17} color="var(--accent)" /><div><div className="tt">{u.label}</div><div className="td">{u.desc}</div></div>
            </button>
          ))}
        </div>
        {util === "pdfa" && (
          <>
            {needsGs && <Notice kind="err">The Ghostscript component is not installed, so PDF/A conversion is unavailable.</Notice>}
            <Field label="Conformance level"><Seg value={level} onChange={setLevel} options={[{ value: "1b", label: "PDF/A-1b" }, { value: "2b", label: "PDF/A-2b" }, { value: "3b", label: "PDF/A-3b" }]} /></Field>
            <Notice kind="warn">The document is rebuilt: form fields, digital signatures, encryption and JavaScript are removed. NayanPDF checks key PDF/A properties but does not perform a formal (veraPDF) validation.</Notice>
          </>
        )}
        {util === "validate" && validation && (
          <>
            <Notice kind={validation.ok ? "ok" : "warn"}>{validation.ok ? "No structural problems found." : "Problems were found (details below)."}</Notice>
            <table className="tbl"><tbody>
              <tr><td>SHA-256</td><td className="mono" style={{ wordBreak: "break-all" }}>{validation.sha256}</td></tr>
              <tr><td>qpdf check</td><td>{validation.qpdfOk === null ? "-" : validation.qpdfOk ? "Passed" : "Failed"}</td></tr>
              <tr><td>Repaired when opened</td><td>{validation.repairedOnOpen ? "Yes (damaged cross-reference table)" : "No"}</td></tr>
              <tr><td>Revisions</td><td>{validation.revisions}{validation.incrementallyUpdated ? " (modified after creation)" : ""}</td></tr>
            </tbody></table>
            {[...(validation.problems || []), ...(validation.pageErrors || []), ...(validation.mupdfWarnings || [])].slice(0, 30).map((p: string, i: number) => <div key={i} className="subtle mono"><FileWarning size={12} /> {p}</div>)}
          </>
        )}
        {util === "blank" && (
          <>
            <Field label={`Sensitivity (ink below ${(sens * 100).toFixed(2)}% of the page counts as blank)`}><input type="range" min={0.0005} max={0.02} step={0.0005} value={sens} onChange={(e) => { setSens(parseFloat(e.target.value)); setBlank(null); }} /></Field>
            {blank && (blank.blankPages.length ? <Notice kind="warn">Blank page(s): {blank.blankPages.map((b: any) => b.page).join(", ")}. Click Run to remove them (a new file is created).</Notice> : <Notice kind="ok">No blank pages found.</Notice>)}
          </>
        )}
        {util === "pagesize" && (
          <div className="grid3">
            <Field label="Size"><select className="input" value={size} onChange={(e) => setSize(e.target.value)}>{["A4", "A3", "A5", "Letter", "Legal"].map((x) => <option key={x}>{x}</option>)}</select></Field>
            <Field label="Orientation"><select className="input" value={orient} onChange={(e) => setOrient(e.target.value)}><option value="auto">Keep</option><option value="portrait">Portrait</option><option value="landscape">Landscape</option></select></Field>
            <Field label="Scaling"><select className="input" value={fit} onChange={(e) => setFit(e.target.value)}><option value="fit">Fit (no cropping)</option><option value="fill">Fill (may crop)</option></select></Field>
          </div>
        )}
        {util === "crop" && (
          <div className="grid2">
            {(["top", "bottom", "left", "right"] as const).map((k) => (
              <Field key={k} label={`${k[0].toUpperCase() + k.slice(1)} (mm)`}><input className="input" type="number" value={crop[k]} onChange={(e) => setCrop({ ...crop, [k]: parseFloat(e.target.value) || 0 })} /></Field>
            ))}
          </div>
        )}
        {util === "labels" && (
          <>
            {labels.map((l, i) => (
              <div key={i} className="row" style={{ marginBottom: 6 }}>
                <span className="subtle">From page</span>
                <input className="input sm" type="number" style={{ width: 70 }} value={l.startpage + 1} onChange={(e) => setLabels(labels.map((x, k) => (k === i ? { ...x, startpage: Math.max(0, (parseInt(e.target.value) || 1) - 1) } : x)))} />
                <select className="input sm" style={{ width: 130 }} value={l.style} onChange={(e) => setLabels(labels.map((x, k) => (k === i ? { ...x, style: e.target.value } : x)))}>
                  <option value="D">1, 2, 3</option><option value="r">i, ii, iii</option><option value="R">I, II, III</option><option value="a">a, b, c</option><option value="A">A, B, C</option><option value="">Prefix only</option>
                </select>
                <input className="input sm" style={{ width: 80 }} placeholder="Prefix" value={l.prefix} onChange={(e) => setLabels(labels.map((x, k) => (k === i ? { ...x, prefix: e.target.value } : x)))} />
                <span className="subtle">start</span>
                <input className="input sm" type="number" style={{ width: 60 }} value={l.firstpagenum} onChange={(e) => setLabels(labels.map((x, k) => (k === i ? { ...x, firstpagenum: parseInt(e.target.value) || 1 } : x)))} />
                <button className="btn sm ghost" onClick={() => setLabels(labels.filter((_, k) => k !== i))}>Remove</button>
              </div>
            ))}
            <button className="btn sm" onClick={() => setLabels([...labels, { startpage: 0, style: "D", prefix: "", firstpagenum: 1 }])}>Add rule</button>
          </>
        )}
      </div>
    </ToolPage>
  );
}

// ---------------------------------------------------------------- document-mode tools
export function DocModeTool(props: { tool: ToolDef }) {
  const t = props.tool;
  const docs = useApp((s) => s.docs);
  const open = async () => {
    const f = await api.openFiles("pdf", false, `Choose a PDF`);
    if (f[0]) await openDocument(f[0], t.docMode);
  };
  return (
    <ToolPage tool={t}>
      <div className="card card-pad" style={{ maxWidth: 640 }}>
        <p style={{ marginTop: 0 }}>{t.title} works inside the document viewer.</p>
        <button className="btn primary lg" onClick={open}>Open a PDF</button>
        {Object.values(docs).length > 0 && (
          <>
            <div className="divider" />
            <div className="subtle" style={{ marginBottom: 6 }}>Or continue with an open document:</div>
            <div className="row wrap">
              {Object.values(docs).map((d) => (
                <button key={d.state.sessionId} className="btn" onClick={() => { window.dispatchEvent(new CustomEvent("nayan:set-mode", { detail: { sessionId: d.state.sessionId, mode: t.docMode } })); useApp.getState().setView({ kind: "doc", sessionId: d.state.sessionId }); }}>{d.state.name}</button>
              ))}
            </div>
          </>
        )}
      </div>
    </ToolPage>
  );
}

export function ToolIndex() {
  const setView = useApp((s) => s.setView);
  return (
    <div className="tool-grid">
      {TOOLS.map((t) => (
        <button key={t.id} className="tool-card" onClick={() => setView({ kind: "tool", tool: t.id })}>
          <div className="ic" style={{ background: t.color + "1a", color: t.color }}><t.icon size={20} /></div>
          <div><div className="tt">{t.title}</div><div className="td">{t.desc}</div></div>
        </button>
      ))}
    </div>
  );
}
