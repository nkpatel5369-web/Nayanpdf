import React, { useEffect, useState } from "react";
import { ArrowLeft, ArrowRight, RefreshCw, ScanLine, Square, Trash2 } from "lucide-react";
import { api, on, waitForJob } from "../../api";
import { errorText, useApp } from "../../store";
import { toolById } from "../../tools";
import { Check, Field, Notice, Seg, Spinner } from "../../components/ui";
import { openDocument } from "../../components/common";
import { ToolPage } from "./shared";
import { LANG_NAMES } from "./BasicTools";

interface Page { path: string; thumb: string; width: number; height: number }

/** Scan to PDF: pages from a scanner on this PC (Windows Image Acquisition), into one PDF, searchable if asked. */
export function ScanTool() {
  const settings = useApp((s) => s.settings);
  const caps = useApp((s) => s.caps);
  const toast = useApp((s) => s.toast);
  const [devices, setDevices] = useState<{ id: string; name: string }[] | null>(null);
  const [reason, setReason] = useState<string | null>(null);
  const [deviceId, setDeviceId] = useState("");
  const [source, setSource] = useState<"flatbed" | "feeder">("flatbed");
  const [colour, setColour] = useState<"color" | "gray" | "bw">("gray");
  const [dpi, setDpi] = useState(300);
  const [ocr, setOcr] = useState(true);
  const [langs, setLangs] = useState<string[]>(settings.ocrLanguages);
  const [pageSize, setPageSize] = useState("A4");
  const [pages, setPages] = useState<Page[]>([]);
  const [scanning, setScanning] = useState(false);
  const [live, setLive] = useState(0);
  const [making, setMaking] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const langsAvail: string[] = (caps?.capabilities?.ocrLanguages || ["eng"]).filter((l: string) => l !== "osd");

  const find = async () => {
    setDevices(null);
    setReason(null);
    try {
      const r = await api.scanners();
      setDevices(r.devices);
      setReason(r.available ? (r.devices.length ? null : "No scanner was found. Check that it is switched on and installed in Windows (Settings > Bluetooth & devices > Printers & scanners).") : r.reason || "Scanning is not available.");
      if (r.devices[0] && !r.devices.some((d) => d.id === deviceId)) setDeviceId(r.devices[0].id);
    } catch (e) {
      setDevices([]);
      setReason(errorText(e));
    }
  };
  useEffect(() => {
    find();
    return on("scan:page", (d: any) => setLive(d.page));
  }, []);

  const doScan = async () => {
    setError(null);
    setScanning(true);
    setLive(0);
    try {
      const r = await api.scan({ deviceId, source, colour, dpi });
      setPages((p) => [...p, ...r.pages]);
      if (!r.pages.length) setError("The scanner returned no pages.");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setScanning(false);
    }
  };
  const move = (i: number, d: number) => setPages((p) => {
    const q = p.slice();
    const j = i + d;
    if (j < 0 || j >= q.length) return p;
    [q[i], q[j]] = [q[j], q[i]];
    return q;
  });
  const make = async () => {
    const stamp = new Date().toISOString().slice(0, 16).replace("T", " ").replace(":", ".");
    const out = await api.saveFile(`Scan ${stamp}.pdf`, "pdf", "Save the scanned PDF");
    if (!out) return;
    setMaking(ocr ? "Making the PDF and recognising text..." : "Making the PDF...");
    try {
      const j = await api.startJob("create.fromImages", { images: pages.map((p) => p.path), output: out, pageSize, fit: "fit", quality: "original", ocr, languages: ocr ? langs : undefined }, `Scan to PDF (${pages.length} page${pages.length === 1 ? "" : "s"})`);
      const done = await waitForJob(j.id);
      if (done.status !== "succeeded") throw new Error(done.error?.message || "The PDF was not created.");
      toast({ kind: "success", title: "Scanned PDF saved", body: done.outputs?.[0] || out });
      setPages([]);
      await openDocument(done.outputs?.[0] || out);
    } catch (e) {
      toast({ kind: "error", title: "Could not make the PDF", body: errorText(e) });
    } finally {
      setMaking(null);
    }
  };

  const ready = !!deviceId && !!devices?.length && !scanning && !making;
  return (
    <ToolPage tool={toolById("scan")!} subtitle="Scan paper into a PDF with the scanner connected to this computer, and make it searchable. Uses Windows' own scanner support; nothing is sent anywhere.">
      <div className="scan-grid">
        <div className="card card-pad">
          <Field label="Scanner">
            <div className="row">
              <select className="input grow" aria-label="Scanner" value={deviceId} disabled={!devices?.length} onChange={(e) => setDeviceId(e.target.value)}>
                {!devices && <option>Looking for scanners...</option>}
                {devices?.length === 0 && <option value="">No scanner found</option>}
                {devices?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
              </select>
              <button className="btn" title="Look for scanners again" onClick={find}><RefreshCw size={14} /></button>
            </div>
          </Field>
          {reason && <Notice kind="warn">{reason} Paper already scanned to a folder or by email can be turned into a searchable PDF with Create PDF &gt; Images and OCR.</Notice>}
          <Field label="Paper source"><Seg value={source} onChange={setSource} options={[{ value: "flatbed", label: "Flatbed (glass)" }, { value: "feeder", label: "Document feeder" }]} /></Field>
          <Field label="Colour"><Seg value={colour} onChange={setColour} options={[{ value: "color", label: "Colour" }, { value: "gray", label: "Greyscale" }, { value: "bw", label: "Black & white" }]} /></Field>
          <Field label="Resolution">
            <select className="input" aria-label="Resolution" value={dpi} onChange={(e) => setDpi(parseInt(e.target.value))}>
              {[150, 200, 300, 400, 600].map((d) => <option key={d} value={d}>{d} dpi{d === 300 ? " (recommended for OCR)" : ""}</option>)}
            </select>
          </Field>
          <Check checked={ocr} onChange={setOcr}>Make it searchable (OCR)</Check>
          {ocr && <div className="row wrap" style={{ margin: "4px 0 8px 24px" }}>{langsAvail.map((l) => <Check key={l} checked={langs.includes(l)} onChange={(v) => setLangs(v ? [...langs, l] : langs.filter((x) => x !== l))}>{LANG_NAMES[l] || l}</Check>)}</div>}
          <Field label="Page size in the PDF">
            <select className="input" aria-label="Page size" value={pageSize} onChange={(e) => setPageSize(e.target.value)}>
              <option value="A4">A4</option><option value="Letter">Letter</option><option value="Legal">Legal</option><option value="fit">Same as the scan</option>
            </select>
          </Field>
          <div className="row" style={{ marginTop: 8 }}>
            {scanning ? (
              <>
                <Spinner /> <span className="grow">Scanning{live ? ` page ${live}` : ""}...</span>
                <button className="btn" onClick={() => api.cancelScan()}><Square size={13} /> Stop</button>
              </>
            ) : (
              <button className="btn primary lg grow" disabled={!ready} onClick={doScan}><ScanLine size={16} /> {pages.length ? "Scan more pages" : "Scan"}</button>
            )}
          </div>
          {error && <Notice kind="err">{error}</Notice>}
        </div>
        <div className="card card-pad scan-pages" aria-label="Scanned pages">
          <div className="row">
            <strong className="grow">{pages.length ? `${pages.length} page${pages.length === 1 ? "" : "s"} scanned` : "Scanned pages appear here"}</strong>
            {pages.length > 0 && !making && <button className="btn sm ghost" onClick={() => setPages([])}><Trash2 size={13} /> Clear</button>}
          </div>
          <div className="scan-thumbs">
            {pages.map((p, i) => (
              <div key={p.path} className="scan-thumb">
                {p.thumb ? <img src={p.thumb} alt={`Page ${i + 1}`} /> : <div className="scan-noimg">Page {i + 1}</div>}
                <div className="row scan-thumb-bar">
                  <span className="grow">{i + 1}</span>
                  <button className="icon-btn xs" title="Move earlier" disabled={i === 0} onClick={() => move(i, -1)}><ArrowLeft size={13} /></button>
                  <button className="icon-btn xs" title="Move later" disabled={i === pages.length - 1} onClick={() => move(i, 1)}><ArrowRight size={13} /></button>
                  <button className="icon-btn xs" title="Remove page" onClick={() => setPages((q) => q.filter((_, k) => k !== i))}><Trash2 size={13} /></button>
                </div>
              </div>
            ))}
          </div>
          <button className="btn primary lg" style={{ width: "100%", marginTop: 12 }} disabled={!pages.length || !!making || scanning} onClick={make}>
            {making ? <><Spinner /> {making}</> : `Save as PDF${pages.length ? ` (${pages.length} page${pages.length === 1 ? "" : "s"})` : ""}`}
          </button>
        </div>
      </div>
    </ToolPage>
  );
}
