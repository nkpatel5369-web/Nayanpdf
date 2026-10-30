import React, { useEffect, useState } from "react";
import { FileImage, FileSpreadsheet, FileText, Presentation, FileCode2, Image, Type, Globe, FileType2 } from "lucide-react";
import { api, dirName, joinPath, stem } from "../../api";
import { useApp } from "../../store";
import { FileEntry, FileList, JobView, OutputDir, probeFile, useJob } from "../../components/common";
import { Check, Field, Notice, Seg } from "../../components/ui";
import { toolById } from "../../tools";
import { RunBar, SourcePicker, Src, ToolPage, outDirOf, outPath, srcParams, useInitialSource } from "./shared";
import { LANG_NAMES } from "./BasicTools";

type Target = "docx" | "xlsx" | "pptx" | "images" | "txt" | "html" | "md";
type FromKind = "office" | "images" | "text" | "html";

const TARGETS: { id: Target; label: string; icon: any; desc: string }[] = [
  { id: "docx", label: "Word", icon: FileText, desc: "Editable .docx" },
  { id: "xlsx", label: "Excel / CSV", icon: FileSpreadsheet, desc: "Tables to cells" },
  { id: "pptx", label: "PowerPoint", icon: Presentation, desc: "Slides" },
  { id: "images", label: "Images", icon: FileImage, desc: "PNG, JPG, TIFF..." },
  { id: "txt", label: "Text", icon: Type, desc: "Plain text" },
  { id: "html", label: "HTML", icon: Globe, desc: "Web page" },
  { id: "md", label: "Markdown", icon: FileCode2, desc: ".md" },
];

const FROM: { id: FromKind; label: string; icon: any; desc: string }[] = [
  { id: "office", label: "Word / Excel / PowerPoint", icon: FileType2, desc: "DOCX, XLSX, PPTX, ODT, RTF, CSV..." },
  { id: "images", label: "Images", icon: Image, desc: "JPG, PNG, TIFF scans" },
  { id: "text", label: "Text file", icon: Type, desc: ".txt" },
  { id: "html", label: "HTML page", icon: Globe, desc: ".html saved page" },
];

export function ConvertTool(props: { args?: any }) {
  const [dir, setDir] = useState<"from" | "to">(props.args?.direction === "to" ? "to" : "from");
  return (
    <ToolPage tool={toolById("convert")!} subtitle="All conversions run on this computer with bundled engines.">
      <div style={{ marginBottom: 16 }}>
        <Seg value={dir} onChange={setDir} options={[{ value: "from", label: "PDF to other formats" }, { value: "to", label: "Other formats to PDF" }]} />
      </div>
      {dir === "from" ? <FromPdf args={props.args} /> : <ToPdf />}
    </ToolPage>
  );
}

function FromPdf(props: { args?: any }) {
  const settings = useApp((s) => s.settings);
  const caps = useApp((s) => s.caps);
  const [src, setSrc] = useInitialSource(props.args);
  const [target, setTarget] = useState<Target>("docx");
  const [pages, setPages] = useState("");
  const [ocr, setOcr] = useState<"auto" | "off">("auto");
  const [langs, setLangs] = useState<string[]>(settings.ocrLanguages);
  const [wordMode, setWordMode] = useState(settings.wordMode);
  const [xlFormat, setXlFormat] = useState<"xlsx" | "csv">("xlsx");
  const [strategy, setStrategy] = useState("auto");
  const [layout, setLayout] = useState("sheetPerTable");
  const [merge, setMerge] = useState(true);
  const [numbers, setNumbers] = useState(true);
  const [dateOrder, setDateOrder] = useState(settings.excelDateOrder);
  const [csvMode, setCsvMode] = useState("combined");
  const [pptMode, setPptMode] = useState(settings.pptMode);
  const [imgFmt, setImgFmt] = useState(settings.imageFormat);
  const [dpi, setDpi] = useState(settings.imageDpi);
  const [quality, setQuality] = useState(settings.imageQuality);
  const [gray, setGray] = useState(false);
  const [tiffMulti, setTiffMulti] = useState(false);
  const [naming, setNaming] = useState("{name}_page_{page:03d}");
  const [txtLayout, setTxtLayout] = useState(true);
  const [htmlMode, setHtmlMode] = useState("semantic");
  const [outDir, setOutDir] = useState<string | null>(null);
  const { job, run, reset, running } = useJob();
  const langsAvail: string[] = caps?.capabilities?.ocrLanguages?.filter((l: string) => l !== "osd") || ["eng"];
  const pg = pages.trim() || undefined;
  const go = () => {
    const s = src!;
    const base = { ...srcParams(s), pages: pg };
    const ocrp = { ocr, languages: langs };
    switch (target) {
      case "docx": return run("convert.toWord", { ...base, ...ocrp, mode: wordMode, output: outPath(s, outDir, "", "docx") }, `To Word: ${s.name}`);
      case "xlsx": return run("convert.toExcel", { ...base, ...ocrp, strategy, layout, mergeAcrossPages: merge, convertNumbers: numbers, dateOrder, csvMode, output: outPath(s, outDir, "", xlFormat) }, `To ${xlFormat.toUpperCase()}: ${s.name}`);
      case "pptx": return run("convert.toPowerPoint", { ...base, mode: pptMode, dpi: 200, output: outPath(s, outDir, "", "pptx") }, `To PowerPoint: ${s.name}`);
      case "images": return run("convert.toImages", { ...base, format: imgFmt, dpi, quality, grayscale: gray, multiPageTiff: tiffMulti, nameTemplate: naming, outputDir: joinPath(outDirOf(s, outDir), stem(s.displayPath) + "_images") }, `To images: ${s.name}`);
      case "txt": return run("convert.toText", { ...base, ...ocrp, layout: txtLayout, output: outPath(s, outDir, "", "txt") }, `To text: ${s.name}`);
      case "html": return run("convert.toHtml", { ...base, mode: htmlMode, output: outPath(s, outDir, "", "html") }, `To HTML: ${s.name}`);
      case "md": return run("convert.toMarkdown", { ...base, ...ocrp, output: outPath(s, outDir, "", "md") }, `To Markdown: ${s.name}`);
    }
  };
  return (
    <div className="tool-body">
      <div>
        <SourcePicker value={src} onChange={setSrc} />
        <div className="card card-pad">
          <div className="label" style={{ marginBottom: 8 }}>Convert to</div>
          <div className="tool-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(130px, 1fr))", marginBottom: 14 }}>
            {TARGETS.map((t) => (
              <button key={t.id} className="tool-card" style={target === t.id ? { borderColor: "var(--accent)", background: "var(--accent-soft)" } : undefined} onClick={() => { if (t.id !== target && !running) reset(); setTarget(t.id); }}>
                <t.icon size={20} color="var(--accent)" /><div><div className="tt">{t.label}</div><div className="td">{t.desc}</div></div>
              </button>
            ))}
          </div>
          <Field label="Pages" hint="Empty = all pages. Examples: 1-3, 5, 8-"><input className="input" style={{ width: 220 }} value={pages} onChange={(e) => setPages(e.target.value)} placeholder="All pages" /></Field>
          {target === "docx" && (
            <Field label="Output style">
              <Seg value={wordMode} onChange={setWordMode} options={[{ value: "layout", label: "Keep layout" }, { value: "flow", label: "Easy editing (flowing text)" }]} />
              <div className="hint">{wordMode === "layout" ? "Reproduces positions, columns, tables and images as closely as possible." : "Simpler structure: headings, paragraphs and tables in reading order. Best for rewriting text."}</div>
            </Field>
          )}
          {target === "xlsx" && (
            <>
              <div className="grid2">
                <Field label="Format"><Seg value={xlFormat} onChange={setXlFormat} options={[{ value: "xlsx", label: "Excel (.xlsx)" }, { value: "csv", label: "CSV" }]} /></Field>
                <Field label="Table detection">
                  <select className="input" value={strategy} onChange={(e) => setStrategy(e.target.value)}>
                    <option value="auto">Automatic (ruled lines, then alignment)</option><option value="lines">Ruled tables only</option><option value="text">Borderless (by alignment)</option>
                  </select>
                </Field>
              </div>
              {xlFormat === "xlsx" ? (
                <>
                  <div className="grid2">
                    <Field label="Sheets">
                      <select className="input" value={layout} onChange={(e) => setLayout(e.target.value)}>
                        <option value="sheetPerTable">One sheet per table</option><option value="sheetPerPage">One sheet per page</option><option value="singleSheet">All tables on one sheet</option>
                      </select>
                    </Field>
                    <Field label="Dates are written as">
                      <select className="input" value={dateOrder} onChange={(e) => setDateOrder(e.target.value as any)}><option value="DMY">DD/MM/YYYY (India, UK)</option><option value="MDY">MM/DD/YYYY (US)</option></select>
                    </Field>
                  </div>
                  <Check checked={numbers} onChange={setNumbers}>Convert numbers and dates into real Excel values</Check>
                </>
              ) : (
                <Field label="CSV files"><Seg value={csvMode} onChange={setCsvMode} options={[{ value: "combined", label: "One CSV" }, { value: "perTable", label: "One CSV per table" }]} /></Field>
              )}
              <Check checked={merge} onChange={setMerge}>Join tables that continue across pages</Check>
              <Notice kind="info">
                Numbers are converted only when the text is an exact number (Indian 1,23,456.78 and Western grouping, (negative) in brackets). Identifiers with leading zeros, long account/reference numbers and GSTIN/PAN stay as text. Dr/Cr markers are kept in the cell format; signs are never guessed. An "Extraction Report" sheet lists per page how many printed digits were captured and flags pages to review.
              </Notice>
            </>
          )}
          {target === "pptx" && (
            <Field label="Slide type">
              <Seg value={pptMode} onChange={setPptMode} options={[{ value: "hybrid", label: "Editable text + picture background" }, { value: "editable", label: "Fully editable objects" }, { value: "image", label: "Page pictures (not editable)" }]} />
              <div className="hint">{pptMode === "image" ? "Each slide is a picture of the page: exact look, no editing." : pptMode === "hybrid" ? "Text boxes are editable; graphics and images form a picture behind them. Best balance." : "Text, images and simple shapes become separate objects. Complex graphics may differ from the PDF."}</div>
            </Field>
          )}
          {target === "images" && (
            <>
              <div className="grid3">
                <Field label="Format"><select className="input" value={imgFmt} onChange={(e) => setImgFmt(e.target.value as any)}><option value="png">PNG</option><option value="jpg">JPEG</option><option value="tiff">TIFF</option><option value="bmp">BMP</option><option value="webp">WebP</option></select></Field>
                <Field label="Resolution (dpi)"><select className="input" value={dpi} onChange={(e) => setDpi(parseInt(e.target.value))}>{[72, 96, 150, 200, 300, 400, 600].map((d) => <option key={d} value={d}>{d}</option>)}</select></Field>
                {(imgFmt === "jpg" || imgFmt === "webp") && <Field label={`Quality: ${quality}`}><input type="range" min={30} max={100} value={quality} onChange={(e) => setQuality(parseInt(e.target.value))} /></Field>}
              </div>
              <Field label="File names" hint="{name} = PDF name, {page} = page number, {page:03d} = zero-padded"><input className="input" value={naming} onChange={(e) => setNaming(e.target.value)} /></Field>
              <Check checked={gray} onChange={setGray}>Greyscale</Check>
              {imgFmt === "tiff" && <Check checked={tiffMulti} onChange={setTiffMulti}>Single multi-page TIFF</Check>}
            </>
          )}
          {target === "txt" && <Check checked={txtLayout} onChange={setTxtLayout}>Keep layout (columns aligned with spaces)</Check>}
          {target === "html" && <Field label="HTML style"><Seg value={htmlMode} onChange={setHtmlMode} options={[{ value: "semantic", label: "Flowing (headings, paragraphs)" }, { value: "layout", label: "Positioned like the PDF" }]} /></Field>}
          {["docx", "xlsx", "txt", "md"].includes(target) && (
            <>
              <div className="divider" />
              <Check checked={ocr === "auto"} onChange={(v) => setOcr(v ? "auto" : "off")}>Use OCR for scanned pages (pages without a text layer)</Check>
              {ocr === "auto" && (
                <div className="row wrap" style={{ marginLeft: 22 }}>
                  {langsAvail.map((l) => <Check key={l} checked={langs.includes(l)} onChange={(v) => setLangs(v ? [...langs, l] : langs.filter((x) => x !== l))}>{LANG_NAMES[l] || l}</Check>)}
                </div>
              )}
            </>
          )}
        </div>
      </div>
      <div className="card card-pad">
        <OutputDir value={outDir} onChange={setOutDir} />
        <RunBar disabled={!src} running={running} onRun={go} label="Convert" />
        <JobView job={job}>
          {(r) => (
            <>
              {r.report && <div className="subtle">{r.tables} table(s) on {r.sheets - 1} sheet(s); {r.numbersConverted} values converted to numbers/dates.</div>}
              {r.reviewPages?.length > 0 && <Notice kind="warn">Review page(s) {r.reviewPages.join(", ")} against the PDF (see the Extraction Report sheet).</Notice>}
              {r.description && <div className="subtle">{r.description}</div>}
              {r.ocr && <Notice kind="warn">OCR was used on page(s) {r.ocr.ocrPages.join(", ")}. Verify recognised figures.</Notice>}
            </>
          )}
        </JobView>
        <Notice kind="info">Converted files are a starting point, not a verified copy of the source. Check figures before relying on them.</Notice>
      </div>
    </div>
  );
}

function ToPdf() {
  const settings = useApp((s) => s.settings);
  const [kind, setKind] = useState<FromKind>("office");
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [html, setHtml] = useState<Src | null>(null);
  const [txt, setTxt] = useState<Src | null>(null);
  const [pageSize, setPageSize] = useState("A4");
  const [orientation, setOrientation] = useState("auto");
  const [margin, setMargin] = useState(0);
  const [fit, setFit] = useState("fit");
  const [quality, setQuality] = useState("original");
  const [ocr, setOcr] = useState(false);
  const [single, setSingle] = useState(true);
  const [sheets, setSheets] = useState(false);
  const [name, setName] = useState("images");
  const [outDir, setOutDir] = useState<string | null>(null);
  const { job, run, running } = useJob();
  const dirFor = (p: string) => outDir || settings.defaultOutputDir || dirName(p);
  const go = async () => {
    if (kind === "office") {
      if (files.length === 1) return run("convert.officeToPdf", { path: files[0].path, output: joinPath(dirFor(files[0].path), stem(files[0].path) + ".pdf"), singlePageSheets: sheets }, `To PDF: ${stem(files[0].path)}`);
      for (const f of files) await api.startJob("convert.officeToPdf", { path: f.path, output: joinPath(dirFor(f.path), stem(f.path) + ".pdf"), singlePageSheets: sheets }, `To PDF: ${stem(f.path)}`);
      useApp.getState().toast({ kind: "info", title: `${files.length} conversions queued`, body: "Track them in Activity." });
      useApp.getState().setJobsOpen(true);
    } else if (kind === "images") {
      if (single || files.length === 1)
        return run("create.fromImages", { images: files.map((f) => f.path), output: joinPath(dirFor(files[0].path), (name || "images") + ".pdf"), pageSize, orientation, margin, fit, quality, ocr, languages: settings.ocrLanguages }, `Images to PDF (${files.length})`);
      for (const f of files) await api.startJob("create.fromImages", { images: [f.path], output: joinPath(dirFor(f.path), stem(f.path) + ".pdf"), pageSize, orientation, margin, fit, quality, ocr, languages: settings.ocrLanguages }, `Image to PDF: ${stem(f.path)}`);
      useApp.getState().setJobsOpen(true);
    } else if (kind === "text") {
      return run("create.fromText", { path: txt!.path, output: outPath(txt!, outDir, "", "pdf"), pageSize: pageSize === "fit" ? "A4" : pageSize, orientation: orientation === "auto" ? "portrait" : orientation }, `Text to PDF`);
    } else if (kind === "html") {
      try {
        const r: any = await api.htmlToPdf(html!.path!, outPath(html!, outDir, "", "pdf"), { pageSize: pageSize === "fit" ? "A4" : pageSize, landscape: orientation === "landscape", margin: margin || 36 });
        useApp.getState().toast({ kind: "success", title: "HTML converted", body: r.output, actions: [{ label: "Open", run: () => import("../../components/common").then((m) => m.openDocument(r.output)) }] });
      } catch (e: any) {
        useApp.getState().toast({ kind: "error", title: "Conversion failed", body: e.message });
      }
    }
  };
  const caps = useApp((s) => s.caps);
  const noLO = caps?.capabilities && caps.capabilities.libreoffice === false;
  const ready = kind === "office" || kind === "images" ? files.length > 0 : kind === "text" ? !!txt : !!html;
  return (
    <div className="tool-body">
      <div>
        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <div className="label" style={{ marginBottom: 8 }}>Convert from</div>
          <div className="tool-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))" }}>
            {FROM.map((t) => (
              <button key={t.id} className="tool-card" style={kind === t.id ? { borderColor: "var(--accent)", background: "var(--accent-soft)" } : undefined} onClick={() => { setKind(t.id); setFiles([]); }}>
                <t.icon size={20} color="var(--accent)" /><div><div className="tt">{t.label}</div><div className="td">{t.desc}</div></div>
              </button>
            ))}
          </div>
        </div>
        <div className="card card-pad">
          {kind === "office" && (
            <>
              {noLO && <Notice kind="err">The LibreOffice component is not installed in this copy, so Office conversion is unavailable.</Notice>}
              <FileList files={files} onChange={setFiles} kind="office" addLabel="Add documents" />
              <Check checked={sheets} onChange={setSheets}>Spreadsheets: put each sheet on one page</Check>
              <Notice kind="info">Office files are rendered by the bundled LibreOffice engine. Fonts that are not installed are substituted, which can change line breaks. Compare the PDF with the original.</Notice>
            </>
          )}
          {kind === "images" && (
            <>
              <FileList files={files} onChange={setFiles} kind="images" reorder addLabel="Add images" />
              <div className="divider" />
              <Check checked={single} onChange={setSingle}>Combine into one PDF</Check>
              {single && <Field label="PDF name"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>}
              <div className="grid3">
                <Field label="Page size"><select className="input" value={pageSize} onChange={(e) => setPageSize(e.target.value)}><option value="A4">A4</option><option value="A3">A3</option><option value="A5">A5</option><option value="Letter">Letter</option><option value="Legal">Legal</option><option value="fit">Same as image</option></select></Field>
                <Field label="Orientation"><select className="input" value={orientation} onChange={(e) => setOrientation(e.target.value)}><option value="auto">Automatic</option><option value="portrait">Portrait</option><option value="landscape">Landscape</option></select></Field>
                <Field label="Margin (pt)"><input className="input" type="number" value={margin} onChange={(e) => setMargin(parseFloat(e.target.value) || 0)} /></Field>
              </div>
              <div className="grid2">
                <Field label="Placement"><select className="input" value={fit} onChange={(e) => setFit(e.target.value)}><option value="fit">Fit to page</option><option value="fill">Fill page</option><option value="actual">Actual size</option></select></Field>
                <Field label="Image quality"><select className="input" value={quality} onChange={(e) => setQuality(e.target.value)}><option value="original">Original (no recompression)</option><option value="high">High</option><option value="medium">Medium</option><option value="low">Small file</option></select></Field>
              </div>
              <Check checked={ocr} onChange={setOcr}>Make searchable with OCR (for scans)</Check>
            </>
          )}
          {kind === "text" && (
            <>
              <SourcePicker value={txt} onChange={setTxt} kind="text" label="Text file" />
              <div className="grid2">
                <Field label="Page size"><select className="input" value={pageSize} onChange={(e) => setPageSize(e.target.value)}><option value="A4">A4</option><option value="Letter">Letter</option><option value="Legal">Legal</option></select></Field>
                <Field label="Orientation"><select className="input" value={orientation} onChange={(e) => setOrientation(e.target.value)}><option value="auto">Portrait</option><option value="landscape">Landscape</option></select></Field>
              </div>
            </>
          )}
          {kind === "html" && (
            <>
              <SourcePicker value={html} onChange={setHtml} kind="html" label="HTML file" />
              <div className="grid3">
                <Field label="Page size"><select className="input" value={pageSize} onChange={(e) => setPageSize(e.target.value)}><option value="A4">A4</option><option value="Letter">Letter</option><option value="Legal">Legal</option><option value="A3">A3</option></select></Field>
                <Field label="Orientation"><select className="input" value={orientation} onChange={(e) => setOrientation(e.target.value)}><option value="auto">Portrait</option><option value="landscape">Landscape</option></select></Field>
                <Field label="Margin (pt)"><input className="input" type="number" value={margin} onChange={(e) => setMargin(parseFloat(e.target.value) || 0)} /></Field>
              </div>
              <Notice kind="info">Pages are rendered offline: scripts are not run and internet resources (web fonts, remote images) are not loaded.</Notice>
            </>
          )}
        </div>
      </div>
      <div className="card card-pad">
        <OutputDir value={outDir} onChange={setOutDir} />
        <RunBar disabled={!ready || (kind === "office" && noLO)} running={running} onRun={go} label="Create PDF" />
        <JobView job={job} />
      </div>
    </div>
  );
}

export function CreateTool(props: { args?: any }) {
  const [files, setFiles] = useState<FileEntry[]>(() => (props.args?.files || []).map((p: string) => ({ path: p })));
  const [name, setName] = useState("combined");
  // "Combine files in N PDF" from Explorer while this tool is already open: take the new selection.
  useEffect(() => {
    if (props.args?.from !== "explorer") return;
    const paths: string[] = props.args.files || [];
    setFiles(paths.map((p) => ({ path: p })));
    // Same checks as files added by hand: page count, thumbnail, password-protected files flagged.
    let alive = true;
    (async () => {
      for (const p of paths) {
        const info = await probeFile(p);
        if (alive) setFiles((fs) => fs.map((f) => (f.path === p ? { ...f, ...info } : f)));
      }
    })();
    return () => {
      alive = false;
    };
  }, [props.args?.at]);
  const [bm, setBm] = useState(true);
  const [pageSize, setPageSize] = useState("A4");
  const [outDir, setOutDir] = useState<string | null>(null);
  const { job, run, running } = useJob();
  return (
    <ToolPage tool={toolById("create")!} subtitle="Combine PDFs, Office documents, images and text files into one PDF, in the order you choose." side={
      <div className="card card-pad">
        <Field label="Output file name"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Page size for images and text"><select className="input" value={pageSize} onChange={(e) => setPageSize(e.target.value)}><option value="A4">A4</option><option value="Letter">Letter</option><option value="Legal">Legal</option><option value="A3">A3</option><option value="fit">Same as image</option></select></Field>
        <Check checked={bm} onChange={setBm}>Bookmark each source file</Check>
        <OutputDir value={outDir} onChange={setOutDir} />
        <RunBar disabled={!files.length} running={running} label="Create PDF" onRun={() => run("create.assemble", { files: files.map((f) => ({ path: f.path, password: f.password })), output: joinPath(outDir || useApp.getState().settings.defaultOutputDir || dirName(files[0].path), (name || "combined") + ".pdf"), pageSize, bookmarks: bm }, `Create PDF from ${files.length} file(s)`)} />
        <JobView job={job} />
      </div>
    }>
      <div className="card card-pad">
        <div className="label" style={{ marginBottom: 8 }}>Source files (drag to reorder)</div>
        <FileList files={files} onChange={setFiles} kind="any" reorder addLabel="Add files" />
        <p className="subtle">For single conversions with more options, use Convert &gt; Other formats to PDF.</p>
      </div>
    </ToolPage>
  );
}
