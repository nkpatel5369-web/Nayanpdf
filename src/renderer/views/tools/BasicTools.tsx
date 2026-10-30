import React, { useState } from "react";
import { api, baseName, dirName, formatBytes, joinPath, stem } from "../../api";
import { useApp } from "../../store";
import { FileEntry, FileList, JobView, OutputDir, useJob, openDocument } from "../../components/common";
import { Check, Field, Notice, Seg } from "../../components/ui";
import { StampForm, StampSpec } from "../../components/StampForm";
import { toolById } from "../../tools";
import { RunBar, SourcePicker, Src, ToolPage, outDirOf, outPath, srcParams, useInitialSource } from "./shared";

// ---------------------------------------------------------------- merge
export function MergeTool() {
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [outDir, setOutDir] = useState<string | null>(null);
  const [name, setName] = useState("merged");
  const [bm, setBm] = useState(true);
  const { job, run, running } = useJob();
  const ready = files.length >= 2 && files.every((f) => !f.locked && !f.error);
  const go = () => {
    const dir = outDir || useApp.getState().settings.defaultOutputDir || dirName(files[0].path);
    run("pages.merge", {
      files: files.map((f) => ({ path: f.path, password: f.password, pages: f.pageSel?.trim() || undefined })),
      output: joinPath(dir, (name.trim() || "merged") + ".pdf"), bookmarksPerFile: bm, keepBookmarks: true,
    }, `Merge ${files.length} files`);
  };
  const total = files.reduce((a, f) => a + (f.pages || 0), 0);
  return (
    <ToolPage tool={toolById("merge")!} side={
      <div className="card card-pad">
        <Field label="Output file name"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <OutputDir value={outDir} onChange={setOutDir} />
        <Check checked={bm} onChange={setBm}>Add a bookmark for each file</Check>
        <div className="subtle">Total: {total} page(s). The result is checked page by page against the intended order.</div>
        <RunBar disabled={!ready} running={running} onRun={go} label="Merge" />
        <JobView job={job} />
      </div>
    }>
      <div className="card card-pad">
        <div className="label" style={{ marginBottom: 8 }}>Files in merge order (drag to reorder; optional page selection per file)</div>
        <FileList files={files} onChange={setFiles} reorder pageSelection addLabel="Add PDFs" />
      </div>
    </ToolPage>
  );
}

// ---------------------------------------------------------------- split
export function SplitTool(props: { args?: any }) {
  const [src, setSrc] = useInitialSource(props.args);
  const [mode, setMode] = useState<"every" | "ranges" | "single" | "oddEven" | "bookmarks">("every");
  const [every, setEvery] = useState(5);
  const [ranges, setRanges] = useState("1-5\n6-10");
  const [outDir, setOutDir] = useState<string | null>(null);
  const { job, run, running } = useJob();
  const preview = () => {
    const n = src?.pages || 0;
    if (!n) return "";
    if (mode === "every") return `${Math.ceil(n / Math.max(1, every))} file(s): ` + Array.from({ length: Math.min(6, Math.ceil(n / every)) }, (_, k) => `${k * every + 1}-${Math.min(n, (k + 1) * every)}`).join(", ") + (Math.ceil(n / every) > 6 ? ", ..." : "");
    if (mode === "single") return `${n} file(s), one page each`;
    if (mode === "oddEven") return "2 files: odd pages, even pages";
    if (mode === "ranges") return `${ranges.split(/\n|;/).filter((x) => x.trim()).length} file(s)`;
    return "One file per top-level bookmark";
  };
  return (
    <ToolPage tool={toolById("split")!} side={
      <div className="card card-pad">
        <OutputDir value={outDir} onChange={setOutDir} />
        <div className="subtle">Files are named "{src ? stem(src.displayPath) : "name"}_part1.pdf", "_part2.pdf", ...</div>
        <RunBar disabled={!src} running={running} label="Split" onRun={() => run("pages.split", {
          ...srcParams(src!), outputDir: outDirOf(src!, outDir), mode, every, ranges: ranges.split(/\n|;/).map((x) => x.trim()).filter(Boolean),
          nameTemplate: `${stem(src!.displayPath)}_{part}`,
        }, `Split ${src!.name}`)} />
        <JobView job={job} />
      </div>
    }>
      <SourcePicker value={src} onChange={setSrc} />
      <div className="card card-pad">
        <Field label="Split method">
          <Seg value={mode} onChange={setMode} options={[
            { value: "every", label: "Every N pages" }, { value: "ranges", label: "By ranges" }, { value: "single", label: "Each page" },
            { value: "oddEven", label: "Odd / even" }, { value: "bookmarks", label: "By bookmarks" },
          ]} />
        </Field>
        {mode === "every" && <Field label="Pages per file"><input className="input" type="number" min={1} style={{ width: 120 }} value={every} onChange={(e) => setEvery(Math.max(1, parseInt(e.target.value) || 1))} /></Field>}
        {mode === "ranges" && <Field label="One range per line (each becomes a file)" hint="Examples: 1-3   5   8-   2,4,6"><textarea className="input" rows={5} value={ranges} onChange={(e) => setRanges(e.target.value)} /></Field>}
        {src?.pages && <Notice kind="info">Result: {preview()}</Notice>}
      </div>
    </ToolPage>
  );
}

// ---------------------------------------------------------------- compress
export function CompressTool(props: { args?: any }) {
  const settings = useApp((s) => s.settings);
  const [src, setSrc] = useInitialSource(props.args);
  const [preset, setPreset] = useState<string>(settings.compressionPreset);
  const [dpi, setDpi] = useState(150);
  const [quality, setQuality] = useState(75);
  const [gray, setGray] = useState(false);
  const [rmMeta, setRmMeta] = useState(false);
  const [target, setTarget] = useState<string>("");
  const [outDir, setOutDir] = useState<string | null>(null);
  const [analysis, setAnalysis] = useState<any>(null);
  const { job, run, running } = useJob();
  const presets = [
    { value: "maximum", label: "Maximum quality", desc: "Lossless only: removes unused data, compresses streams. Images untouched." },
    { value: "high", label: "High quality", desc: "Images above 300 dpi reduced to 220 dpi, JPEG quality 88." },
    { value: "balanced", label: "Balanced", desc: "Images above 200 dpi reduced to 150 dpi, JPEG quality 75. Good for email." },
    { value: "strong", label: "High compression", desc: "Images reduced to 96 dpi, JPEG quality 55. Small, visibly softer images." },
    { value: "custom", label: "Custom", desc: "Choose resolution and quality." },
  ];
  const analyze = async (s: Src | null) => {
    setSrc(s);
    setAnalysis(null);
    if (s && s.path) api.query("compress.analyze", { path: s.path, password: s.password }).then(setAnalysis).catch(() => undefined);
  };
  const go = () =>
    run("compress.run", {
      ...srcParams(src!), output: outPath(src!, outDir, "_compressed", "pdf"), preset, dpiTarget: dpi, dpiThreshold: dpi + 50, quality,
      grayscale: gray, removeMetadata: rmMeta, targetSizeKb: target ? parseFloat(target) : undefined,
    }, `Compress ${src!.name}`);
  return (
    <ToolPage tool={toolById("compress")!} side={
      <div className="card card-pad">
        <OutputDir value={outDir} onChange={setOutDir} />
        <RunBar disabled={!src} running={running} onRun={go} label="Compress" />
        <JobView job={job}>
          {(r) => (
            <>
              <div className="stat-grid">
                <div className="stat"><div className="k">Original</div><div className="v">{formatBytes(r.originalSize)}</div></div>
                <div className="stat"><div className="k">Compressed</div><div className="v">{formatBytes(r.finalSize)}</div></div>
                <div className="stat"><div className="k">{r.larger ? "Increase" : "Saved"}</div><div className="v" style={{ color: r.larger ? "var(--danger)" : "var(--success)" }}>{formatBytes(Math.abs(r.reductionBytes))}</div></div>
                <div className="stat"><div className="k">Reduction</div><div className="v">{r.reductionPercent.toFixed(1)}%</div></div>
              </div>
              <div className="subtle">Preset: {r.presetLabel}. Reduction % = (original - final) / original x 100.</div>
              {r.larger && <Notice kind="warn">The result is larger than the original: this file is already well optimised. Keep the original.</Notice>}
            </>
          )}
        </JobView>
      </div>
    }>
      <SourcePicker value={src} onChange={analyze} />
      {analysis && (
        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <div className="label">What takes space</div>
          <div className="stat-grid">
            <div className="stat"><div className="k">File</div><div className="v">{formatBytes(analysis.fileSize)}</div></div>
            <div className="stat"><div className="k">{analysis.images} images</div><div className="v">{formatBytes(analysis.imageBytes)}</div></div>
            <div className="stat"><div className="k">{analysis.fonts} fonts</div><div className="v">{formatBytes(analysis.fontBytes)}</div></div>
            <div className="stat"><div className="k">Other</div><div className="v">{formatBytes(analysis.otherBytes)}</div></div>
          </div>
        </div>
      )}
      <div className="card card-pad">
        {presets.map((p) => (
          <label key={p.value} className="check" style={{ alignItems: "flex-start", marginBottom: 10 }}>
            <input type="radio" checked={preset === p.value} onChange={() => setPreset(p.value)} style={{ marginTop: 3 }} />
            <div><div style={{ fontWeight: 600 }}>{p.label}</div><div className="subtle">{p.desc}</div></div>
          </label>
        ))}
        {preset === "custom" && (
          <div className="grid2">
            <Field label={`Image resolution: ${dpi} dpi`}><input type="range" min={50} max={300} step={10} value={dpi} onChange={(e) => setDpi(parseInt(e.target.value))} /></Field>
            <Field label={`JPEG quality: ${quality}`}><input type="range" min={20} max={95} value={quality} onChange={(e) => setQuality(parseInt(e.target.value))} /></Field>
          </div>
        )}
        <div className="divider" />
        <Field label="Target size (optional, KB)" hint="N PDF tries progressively stronger settings and stops at the first result under the target. Some files cannot reach a small target without unacceptable quality loss; you are told if so.">
          <input className="input" style={{ width: 160 }} type="number" placeholder="e.g. 900" value={target} onChange={(e) => setTarget(e.target.value)} />
        </Field>
        <Check checked={gray} onChange={setGray}>Convert images to greyscale</Check>
        <Check checked={rmMeta} onChange={setRmMeta}>Remove metadata (title, author, XMP)</Check>
        <Notice kind="info">Text and vector graphics are never rasterised, so text stays sharp and searchable. Only embedded pictures are re-sampled.</Notice>
      </div>
    </ToolPage>
  );
}

// ---------------------------------------------------------------- OCR
export function OcrTool(props: { args?: any }) {
  const caps = useApp((s) => s.caps);
  const settings = useApp((s) => s.settings);
  const [src, setSrc] = useInitialSource(props.args);
  const langsAvail: string[] = caps?.capabilities?.ocrLanguages?.filter((l: string) => l !== "osd") || ["eng"];
  const [langs, setLangs] = useState<string[]>(settings.ocrLanguages);
  const [pages, setPages] = useState("");
  const [skip, setSkip] = useState(true);
  const [rotate, setRotate] = useState(true);
  const [dpi, setDpi] = useState(300);
  const [txt, setTxt] = useState(false);
  const [outDir, setOutDir] = useState<string | null>(null);
  const { job, run, running } = useJob();
  const go = () =>
    run("ocr.run", {
      ...srcParams(src!), output: outPath(src!, outDir, "_ocr", "pdf"), languages: langs, pages: pages || undefined, skipText: skip,
      autoRotate: rotate, dpi, textOutput: txt ? outPath(src!, outDir, "_ocr", "txt") : undefined,
    }, `OCR ${src!.name}`);
  return (
    <ToolPage tool={toolById("ocr")!} side={
      <div className="card card-pad">
        <OutputDir value={outDir} onChange={setOutDir} />
        <RunBar disabled={!src || !langs.length} running={running} onRun={go} label="Run OCR" />
        <JobView job={job}>
          {(r) => (
            <>
              <div className="subtle">Recognised {r.processed.length} page(s); skipped {r.skipped.length} page(s) that already had text. Searchability verified: {r.searchVerified === false ? "no" : "yes"}.</div>
              {r.reviewPages?.length > 0 && <Notice kind="warn">Low confidence on page(s) {r.reviewPages.join(", ")}. Check numbers and identifiers on these pages.</Notice>}
              {r.rotated?.length > 0 && <div className="subtle">Auto-rotated: {r.rotated.map((x: any) => `p${x.page}`).join(", ")}</div>}
            </>
          )}
        </JobView>
      </div>
    }>
      <SourcePicker value={src} onChange={setSrc} />
      <div className="card card-pad">
        <Field label="Document language(s)">
          <div className="row wrap">
            {langsAvail.map((l) => (
              <Check key={l} checked={langs.includes(l)} onChange={(v) => setLangs(v ? [...langs, l] : langs.filter((x) => x !== l))}>{LANG_NAMES[l] || l}</Check>
            ))}
          </div>
        </Field>
        <div className="grid2">
          <Field label="Pages" hint="Empty = all pages"><input className="input" value={pages} onChange={(e) => setPages(e.target.value)} placeholder="All pages" /></Field>
          <Field label="Recognition resolution">
            <select className="input" value={dpi} onChange={(e) => setDpi(parseInt(e.target.value))}>
              <option value={200}>200 dpi (faster)</option><option value={300}>300 dpi (recommended)</option><option value={400}>400 dpi (small print)</option>
            </select>
          </Field>
        </div>
        <Check checked={skip} onChange={setSkip}>Skip pages that already contain text</Check>
        <Check checked={rotate} onChange={setRotate}>Detect and fix page orientation</Check>
        <Check checked={txt} onChange={setTxt}>Also save the recognised text as a .txt file</Check>
        <Notice kind="info">The scanned image is kept exactly as it is; an invisible, searchable text layer is added on top. OCR can misread characters, so verify figures and identifiers that matter.</Notice>
      </div>
    </ToolPage>
  );
}

export const LANG_NAMES: Record<string, string> = { eng: "English", hin: "Hindi", guj: "Gujarati", mar: "Marathi", ben: "Bengali", tam: "Tamil", tel: "Telugu", kan: "Kannada", mal: "Malayalam", pan: "Punjabi", urd: "Urdu", san: "Sanskrit", deu: "German", fra: "French", spa: "Spanish" };

// ---------------------------------------------------------------- protect
export function ProtectTool(props: { args?: any }) {
  const [src, setSrc] = useInitialSource(props.args);
  const [mode, setMode] = useState<"protect" | "unlock" | "inspect">("protect");
  const [userPw, setUserPw] = useState("");
  const [userPw2, setUserPw2] = useState("");
  const [ownerPw, setOwnerPw] = useState("");
  const [restrict, setRestrict] = useState(false);
  const [perms, setPerms] = useState({ print: true, printHighQuality: true, copy: false, modify: false, annotate: true, form: true, assemble: false, accessibility: true });
  const [method, setMethod] = useState("AES-256");
  const [unlockPw, setUnlockPw] = useState("");
  const [outDir, setOutDir] = useState<string | null>(null);
  const [inspect, setInspect] = useState<any>(null);
  const { job, run, running } = useJob();
  const go = () => {
    if (mode === "protect")
      run("security.encrypt", { ...srcParams(src!), output: outPath(src!, outDir, "_protected", "pdf"), userPassword: userPw, ownerPassword: restrict ? ownerPw : ownerPw || userPw, encryption: method, permissions: restrict ? perms : undefined }, `Protect ${src!.name}`);
    else if (mode === "unlock") run("security.decrypt", { ...srcParams(src!), password: unlockPw || src!.password, output: outPath(src!, outDir, "_unlocked", "pdf") }, `Remove protection ${src!.name}`);
  };
  const doInspect = async () => {
    try {
      setInspect(src!.sessionId ? await api.docQuery(src!.sessionId, "security.inspect") : await api.query("security.inspect", { path: src!.path, password: src!.password || unlockPw || undefined }));
    } catch (e: any) {
      setInspect({ error: e.message });
    }
  };
  const valid = mode === "protect" ? (userPw ? userPw === userPw2 : true) && (userPw || (restrict && ownerPw)) && (!restrict || !!ownerPw) : mode === "unlock" ? !!(unlockPw || src?.password) : false;
  return (
    <ToolPage tool={toolById("protect")!} side={mode !== "inspect" ? (
      <div className="card card-pad">
        <OutputDir value={outDir} onChange={setOutDir} />
        <RunBar disabled={!src || !valid} running={running} onRun={go} label={mode === "protect" ? "Protect" : "Remove protection"} />
        <JobView job={job}>{(r) => r.verified && <Notice kind="ok">{mode === "protect" ? "Verified: the output requires the password and applies the chosen permissions." : "Verified: the output is no longer encrypted."}</Notice>}</JobView>
      </div>
    ) : undefined}>
      <SourcePicker value={src} onChange={(s) => { setSrc(s); setInspect(null); }} allowDocs={mode !== "unlock"} />
      <div className="card card-pad">
        <Field label="Action">
          <Seg value={mode} onChange={setMode} options={[{ value: "protect", label: "Add password / restrictions" }, { value: "unlock", label: "Remove protection" }, { value: "inspect", label: "Inspect security" }]} />
        </Field>
        {mode === "protect" && (
          <>
            <Field label="Password to open"><div className="grid2"><input className="input" type="password" value={userPw} onChange={(e) => setUserPw(e.target.value)} placeholder="Open password" /><input className="input" type="password" value={userPw2} onChange={(e) => setUserPw2(e.target.value)} placeholder="Repeat" /></div></Field>
            {userPw && userPw2 && userPw !== userPw2 && <Notice kind="err">Passwords do not match.</Notice>}
            <Check checked={restrict} onChange={setRestrict}>Restrict what others can do</Check>
            {restrict && (
              <>
                <Field label="Permissions password"><input className="input" type="password" value={ownerPw} onChange={(e) => setOwnerPw(e.target.value)} /></Field>
                <div className="grid2">
                  {Object.entries({ print: "Print", printHighQuality: "High-quality print", copy: "Copy text/images", modify: "Edit content", annotate: "Add comments", form: "Fill forms", assemble: "Insert/rotate/delete pages", accessibility: "Screen readers" }).map(([k, l]) => (
                    <Check key={k} checked={(perms as any)[k]} onChange={(v) => setPerms({ ...perms, [k]: v })}>{l}</Check>
                  ))}
                </div>
              </>
            )}
            <Field label="Encryption">
              <select className="input" style={{ width: 240 }} value={method} onChange={(e) => setMethod(e.target.value)}>
                <option value="AES-256">AES-256 (recommended)</option><option value="AES-128">AES-128 (Acrobat 7+)</option><option value="RC4-128">RC4-128 (legacy, weak)</option>
              </select>
            </Field>
          </>
        )}
        {mode === "unlock" && (
          <>
            <Field label="Document password" hint="To remove all protection you need the permissions (owner) password, or an open password when no separate restrictions exist. N PDF does not crack or bypass passwords.">
              <input className="input" type="password" value={unlockPw} onChange={(e) => setUnlockPw(e.target.value)} />
            </Field>
          </>
        )}
        {mode === "inspect" && (
          <>
            <button className="btn" disabled={!src} onClick={doInspect}>Inspect</button>
            {inspect?.error && <Notice kind="err">{inspect.error}</Notice>}
            {inspect && !inspect.error && (
              <table className="tbl" style={{ marginTop: 10 }}>
                <tbody>
                  <tr><td>Encrypted</td><td>{inspect.encrypted ? `Yes (${inspect.method || "password required to read details"})` : "No"}</td></tr>
                  {inspect.permissions && Object.entries(inspect.permissions).map(([k, v]) => <tr key={k}><td>{k}</td><td>{v ? "Allowed" : <span className="badge err">Restricted</span>}</td></tr>)}
                  {"signed" in inspect && <tr><td>Digitally signed</td><td>{inspect.signed ? "Yes" : "No"}</td></tr>}
                  {"javascript" in inspect && <tr><td>Contains JavaScript</td><td>{inspect.javascript ? <span className="badge warn">Yes</span> : "No"}</td></tr>}
                  {"launchActions" in inspect && <tr><td>Launch actions</td><td>{inspect.launchActions ? <span className="badge err">Yes</span> : "No"}</td></tr>}
                  {"embeddedFiles" in inspect && <tr><td>Embedded files</td><td>{inspect.embeddedFiles}</td></tr>}
                </tbody>
              </table>
            )}
          </>
        )}
      </div>
    </ToolPage>
  );
}

// ---------------------------------------------------------------- extract images
export function ExtractImagesTool(props: { args?: any }) {
  const [src, setSrc] = useInitialSource(props.args);
  const [pages, setPages] = useState("");
  const [min, setMin] = useState(64);
  const [fmt, setFmt] = useState<"native" | "png">("native");
  const [outDir, setOutDir] = useState<string | null>(null);
  const { job, run, running } = useJob();
  return (
    <ToolPage tool={toolById("extractImages")!} side={
      <div className="card card-pad">
        <OutputDir value={outDir} onChange={setOutDir} />
        <RunBar disabled={!src} running={running} label="Extract images" onRun={() => run("images.extract", { ...srcParams(src!), outputDir: joinPath(outDirOf(src!, outDir), stem(src!.displayPath) + "_images"), pages: pages || undefined, minWidth: min, minHeight: min, format: fmt }, `Extract images ${src!.name}`)} />
        <JobView job={job}>{(r) => <div className="subtle">{r.count} image(s) extracted.</div>}</JobView>
      </div>
    }>
      <SourcePicker value={src} onChange={setSrc} />
      <div className="card card-pad">
        <Field label="Pages" hint="Empty = all pages"><input className="input" value={pages} onChange={(e) => setPages(e.target.value)} placeholder="All pages" /></Field>
        <Field label={`Skip images smaller than ${min} px`}><input type="range" min={0} max={400} step={8} value={min} onChange={(e) => setMin(parseInt(e.target.value))} /></Field>
        <Field label="Format"><Seg value={fmt} onChange={setFmt} options={[{ value: "native", label: "Original format (JPEG stays JPEG)" }, { value: "png", label: "Always PNG" }]} /></Field>
        <div className="subtle">Images are saved into a new sub-folder. Each image used several times is saved once.</div>
      </div>
    </ToolPage>
  );
}

// ---------------------------------------------------------------- watermark / header / numbers
export function WatermarkTool(props: { args?: any }) {
  const [src, setSrc] = useInitialSource(props.args);
  const [spec, setSpec] = useState<StampSpec | null>(null);
  const [outDir, setOutDir] = useState<string | null>(null);
  const { job, run, running } = useJob();
  const suffix: Record<string, string> = { "stamp.watermark": "_watermarked", "stamp.headerFooter": "_hf", "stamp.pageNumbers": "_numbered", "stamp.bates": "_bates", "stamp.background": "_bg" };
  return (
    <ToolPage tool={toolById("watermark")!} side={
      <div className="card card-pad">
        <OutputDir value={outDir} onChange={setOutDir} />
        <div className="subtle">For a live preview, open the document and use More tools &gt; Watermark.</div>
        <RunBar disabled={!src || !spec} running={running} label="Apply" onRun={() => run(spec!.method, { ...spec!.params, ...srcParams(src!), output: outPath(src!, outDir, suffix[spec!.method] || "_stamped", "pdf") }, `${spec!.method.split(".")[1]} ${src!.name}`)} />
        <JobView job={job}>{(r) => r.first && <div className="subtle">Bates range {r.first} - {r.last}. Next number: {r.nextNumber}</div>}</JobView>
      </div>
    }>
      <SourcePicker value={src} onChange={setSrc} />
      <div className="card card-pad"><StampForm onChange={setSpec} /></div>
    </ToolPage>
  );
}

// ---------------------------------------------------------------- metadata
export function MetadataTool(props: { args?: any }) {
  const [src, setSrc] = useInitialSource(props.args);
  const [meta, setMeta] = useState<any>(null);
  const [form, setForm] = useState<Record<string, string>>({});
  const [outDir, setOutDir] = useState<string | null>(null);
  const { job, run, running } = useJob();
  const load = async (s: Src | null) => {
    setSrc(s);
    setMeta(null);
    if (!s) return;
    try {
      const m = s.sessionId ? await api.docQuery(s.sessionId, "meta.get") : await api.query("meta.get", { path: s.path, password: s.password });
      setMeta(m);
      setForm({ title: m.fields.title, author: m.fields.author, subject: m.fields.subject, keywords: m.fields.keywords, creator: m.fields.creator, producer: m.fields.producer, creationDate: (m.fields.creationDateIso || "").slice(0, 16), modDate: (m.fields.modDateIso || "").slice(0, 16) });
    } catch (e: any) {
      useApp.getState().toast({ kind: "error", title: "Cannot read properties", body: e.message });
    }
  };
  React.useEffect(() => { if (src && !meta) load(src); }, []);
  const fields = ["title", "author", "subject", "keywords", "creator", "producer"];
  return (
    <ToolPage tool={toolById("metadata")!} side={
      <div className="card card-pad">
        <OutputDir value={outDir} onChange={setOutDir} />
        <div className="row" style={{ marginTop: 6 }}>
          <button className="btn danger" disabled={!src || running} onClick={() => run("meta.remove", { ...srcParams(src!), output: outPath(src!, outDir, "_nometa", "pdf"), removeXmp: true }, `Remove metadata ${src!.name}`)}>Remove all metadata</button>
          <div className="grow" />
          <button className="btn primary" disabled={!src || !meta || running} onClick={() => run("meta.set", { ...srcParams(src!), output: outPath(src!, outDir, "_props", "pdf"), fields: { ...form, creationDate: form.creationDate || "", modDate: form.modDate || "" } }, `Edit properties ${src!.name}`)}>Save changes</button>
        </div>
        <JobView job={job} />
      </div>
    }>
      <SourcePicker value={src} onChange={load} />
      {meta && (
        <div className="card card-pad">
          {meta.signed && <Notice kind="warn">This document is digitally signed. Changing its properties invalidates the signature.</Notice>}
          <div className="grid2">
            {fields.map((k) => (
              <Field key={k} label={k[0].toUpperCase() + k.slice(1)}><input className="input" value={form[k] || ""} onChange={(e) => setForm({ ...form, [k]: e.target.value })} /></Field>
            ))}
            <Field label="Created"><input className="input" type="datetime-local" value={form.creationDate || ""} onChange={(e) => setForm({ ...form, creationDate: e.target.value })} /></Field>
            <Field label="Modified"><input className="input" type="datetime-local" value={form.modDate || ""} onChange={(e) => setForm({ ...form, modDate: e.target.value })} /></Field>
          </div>
          <div className="subtle">PDF version {meta.format}. {meta.hasXmp ? "XMP metadata present (kept in sync)." : "No XMP metadata."}</div>
        </div>
      )}
    </ToolPage>
  );
}

export { openDocument, baseName };
