import React, { useEffect, useState } from "react";
import { CheckCircle2, XCircle } from "lucide-react";
import type { Settings } from "@shared/ipc";
import { api, formatBytes } from "../api";
import { useApp } from "../store";
import { Check, Field, Notice, Seg, Spinner } from "../components/ui";
import { LANG_NAMES } from "./tools/BasicTools";
import { speechVoices } from "../workspace/ReadAloud";

export function SettingsView() {
  const settings = useApp((s) => s.settings);
  const setSettings = useApp((s) => s.setSettings);
  const caps = useApp((s) => s.caps);
  const [temp, setTemp] = useState<{ bytes: number; path: string } | null>(null);
  const [diag, setDiag] = useState<any>(null);
  const [running, setRunning] = useState(false);
  const save = async (patch: Partial<Settings>) => setSettings(await api.setSettings(patch));
  useEffect(() => {
    api.tempUsage().then(setTemp).catch(() => undefined);
  }, []);
  const langs: string[] = caps?.capabilities?.ocrLanguages?.filter((l: string) => l !== "osd") || [];
  const runDiag = async () => {
    setRunning(true);
    try {
      setDiag(await api.selfTest());
    } catch (e: any) {
      setDiag({ ok: false, results: [{ check: "Self-test", ok: false, detail: e.message }] });
    } finally {
      setRunning(false);
    }
  };
  return (
    <div className="page">
      <div className="page-narrow" style={{ maxWidth: 860 }}>
        <h1 style={{ marginBottom: 18 }}>Settings</h1>

        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <h3 style={{ marginBottom: 12 }}>Appearance</h3>
          <Field label="Theme"><Seg value={settings.theme} onChange={(v) => save({ theme: v })} options={[{ value: "system", label: "Follow Windows" }, { value: "light", label: "Light" }, { value: "dark", label: "Dark" }]} /></Field>
        </div>

        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <h3 style={{ marginBottom: 12 }}>Page display</h3>
          <div className="grid2">
            <Field label="Default zoom" hint="Automatic fits the page width up to 125% (landscape pages: the whole page).">
              <select className="input" aria-label="Default zoom" value={settings.defaultZoom} onChange={(e) => save({ defaultZoom: e.target.value as Settings["defaultZoom"] })}>
                <option value="auto">Automatic</option><option value="page-fit">Fit page</option><option value="page-width">Fit width</option><option value="page-actual">Actual size (100%)</option>
              </select>
            </Field>
            <Field label="Default page layout" hint="Automatic uses the layout saved in the PDF, otherwise single page continuous.">
              <select className="input" aria-label="Default page layout" value={settings.defaultLayout} onChange={(e) => save({ defaultLayout: e.target.value as Settings["defaultLayout"] })}>
                <option value="auto">Automatic</option><option value="single">Single page</option><option value="continuous">Single page continuous</option><option value="two">Two page</option><option value="twoContinuous">Two page continuous</option>
              </select>
            </Field>
          </div>
          <Field label="Navigation panel when a document opens" hint="Many PDFs made by Word or LibreOffice ask to open with their bookmarks showing; Acrobat honours that. F4 shows or hides the panel at any time, Shift+F4 the tools panel.">
            <Seg value={settings.navPanelOnOpen} onChange={(v) => save({ navPanelOnOpen: v })} options={[{ value: "document", label: "As the PDF requests" }, { value: "never", label: "Never" }, { value: "always", label: "Always" }]} />
          </Field>
        </div>

        <ReadAloudSettings settings={settings} save={save} />

        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <h3 style={{ marginBottom: 12 }}>Files and folders</h3>
          <Field label="Default output folder" hint="Where results are saved unless you choose otherwise.">
            <div className="row">
              <div className="input ellipsis" style={{ display: "flex", alignItems: "center", color: settings.defaultOutputDir ? "var(--text)" : "var(--text-3)" }}>{settings.defaultOutputDir || "Same folder as the source file"}</div>
              <button className="btn" onClick={async () => { const d = await api.pickOutputDir(true); if (d) setSettings(await api.getSettings()); }}>Choose...</button>
              {settings.defaultOutputDir && <button className="btn" onClick={() => save({ defaultOutputDir: "" })}>Reset</button>}
            </div>
          </Field>
          <Check checked={settings.allowOverwriteOriginal} onChange={(v) => save({ allowOverwriteOriginal: v })}>Allow "Save" to overwrite the original file without an extra warning</Check>
          <div className="grid2">
            <Field label="Recent documents to remember"><input className="input" type="number" min={0} max={50} value={settings.recentLimit} onChange={(e) => save({ recentLimit: Math.max(0, Math.min(50, parseInt(e.target.value) || 0)) })} /></Field>
            <Field label="Files processed at once in batches"><input className="input" type="number" min={1} max={8} value={settings.batchConcurrency} onChange={(e) => save({ batchConcurrency: Math.max(1, Math.min(8, parseInt(e.target.value) || 1)) })} /></Field>
          </div>
          <div className="row">
            <button className="btn" onClick={() => api.clearRecent().then(useApp.getState().setRecent)}>Clear recent documents</button>
          </div>
        </div>

        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <h3 style={{ marginBottom: 12 }}>Reviewer identity</h3>
          <div className="grid2">
            <Field label="Name (shown on comments)"><input className="input" value={settings.reviewerName} onChange={(e) => save({ reviewerName: e.target.value })} /></Field>
            <Field label="Initials (for the Initials tool)"><input className="input" value={settings.reviewerInitials} onChange={(e) => save({ reviewerInitials: e.target.value })} /></Field>
          </div>
        </div>

        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <h3 style={{ marginBottom: 12 }}>Defaults</h3>
          <Field label="OCR languages">
            <div className="row wrap">{langs.map((l) => <Check key={l} checked={settings.ocrLanguages.includes(l)} onChange={(v) => save({ ocrLanguages: v ? [...settings.ocrLanguages, l] : settings.ocrLanguages.filter((x) => x !== l).length ? settings.ocrLanguages.filter((x) => x !== l) : ["eng"] })}>{LANG_NAMES[l] || l}</Check>)}</div>
            <div className="hint">Installed offline: {langs.map((l) => LANG_NAMES[l] || l).join(", ") || "none"}. Additional Tesseract .traineddata files can be copied into the tessdata folder of the installation.</div>
          </Field>
          <div className="grid2">
            <Field label="Compression preset"><select className="input" value={settings.compressionPreset} onChange={(e) => save({ compressionPreset: e.target.value as any })}><option value="maximum">Maximum quality</option><option value="high">High quality</option><option value="balanced">Balanced</option><option value="strong">High compression</option></select></Field>
            <Field label="PDF to Word style"><select className="input" value={settings.wordMode} onChange={(e) => save({ wordMode: e.target.value as any })}><option value="layout">Keep layout</option><option value="flow">Easy editing</option></select></Field>
            <Field label="PDF to PowerPoint"><select className="input" value={settings.pptMode} onChange={(e) => save({ pptMode: e.target.value as any })}><option value="hybrid">Editable text + background</option><option value="editable">Fully editable objects</option><option value="image">Page pictures</option></select></Field>
            <Field label="Dates in tables"><select className="input" value={settings.excelDateOrder} onChange={(e) => save({ excelDateOrder: e.target.value as any })}><option value="DMY">DD/MM/YYYY</option><option value="MDY">MM/DD/YYYY</option></select></Field>
            <Field label="Image export format"><select className="input" value={settings.imageFormat} onChange={(e) => save({ imageFormat: e.target.value as any })}><option value="png">PNG</option><option value="jpg">JPEG</option><option value="tiff">TIFF</option><option value="bmp">BMP</option><option value="webp">WebP</option></select></Field>
            <Field label="Image export resolution (dpi)"><input className="input" type="number" value={settings.imageDpi} onChange={(e) => save({ imageDpi: Math.max(36, Math.min(1200, parseInt(e.target.value) || 150)) })} /></Field>
            <Field label="JPEG/WebP quality"><input className="input" type="number" value={settings.imageQuality} onChange={(e) => save({ imageQuality: Math.max(10, Math.min(100, parseInt(e.target.value) || 90)) })} /></Field>
            <Field label="Print quality (dpi)"><select className="input" value={settings.printDpi} onChange={(e) => save({ printDpi: parseInt(e.target.value) })}><option value={150}>150</option><option value={300}>300</option><option value={600}>600</option></select></Field>
          </div>
        </div>

        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <h3 style={{ marginBottom: 12 }}>Temporary files</h3>
          <p className="muted" style={{ marginTop: 0 }}>Edits are kept as temporary revisions while documents are open so you can undo. {temp ? `Currently ${formatBytes(temp.bytes)}.` : ""}</p>
          <Check checked={settings.clearTempOnExit} onChange={(v) => save({ clearTempOnExit: v })}>Delete temporary files when NayanPDF closes</Check>
          <button className="btn" onClick={async () => { await api.tempClear(); setTemp(await api.tempUsage()); }}>Clean up leftovers now</button>
        </div>

        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <h3 style={{ marginBottom: 12 }}>Diagnostics</h3>
          <p className="muted" style={{ marginTop: 0 }}>Runs every bundled engine on a generated test document (offline).</p>
          <button className="btn" disabled={running} onClick={runDiag}>{running ? <Spinner /> : null} Run self-test</button>
          {diag && (
            <table className="tbl" style={{ marginTop: 10 }}>
              <tbody>
                {diag.results.map((r: any) => (
                  <tr key={r.check}><td style={{ width: 24 }}>{r.ok ? <CheckCircle2 size={16} color="var(--success)" /> : <XCircle size={16} color="var(--danger)" />}</td><td>{r.check}</td><td className="subtle">{r.detail}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div className="card card-pad">
          <h3 style={{ marginBottom: 12 }}>About</h3>
          <table className="tbl"><tbody>
            <tr><td>NayanPDF</td><td>{caps?.version}</td></tr>
            <tr><td>Platform</td><td>{caps?.platform} · Electron {caps?.electron} · Chromium {caps?.chrome}</td></tr>
            <tr><td>PDF engine</td><td>PyMuPDF {caps?.capabilities?.pymupdf} / MuPDF {caps?.capabilities?.mupdf}</td></tr>
            <tr><td>OCR</td><td>{caps?.capabilities?.tesseract ? "Tesseract (bundled)" : "Not available"}</td></tr>
            <tr><td>Office conversion</td><td>{caps?.capabilities?.libreoffice ? "LibreOffice (bundled)" : "Not available"}</td></tr>
            <tr><td>PDF/A</td><td>{caps?.capabilities?.ghostscript ? "Ghostscript (bundled)" : "Not available"}</td></tr>
            <tr><td>Saved signatures</td><td>{caps?.signaturesProtected ? "Encrypted with your Windows account" : "Stored unencrypted (OS key store unavailable)"}</td></tr>
          </tbody></table>
          <Notice kind="ok">NayanPDF works fully offline: no account, no activation, no telemetry, no update checks.</Notice>
          <p className="subtle">NayanPDF is free software under the GNU AGPL v3. Third-party component licences are in the installation folder (resources/licenses).</p>
        </div>
      </div>
    </div>
  );
}

function ReadAloudSettings({ settings, save }: { settings: Settings; save: (p: Partial<Settings>) => void }) {
  const [voices, setVoices] = useState<SpeechSynthesisVoice[] | null>(null);
  useEffect(() => {
    speechVoices().then(setVoices).catch(() => setVoices([]));
  }, []);
  return (
    <div className="card card-pad" style={{ marginBottom: 14 }}>
      <h3 style={{ marginBottom: 12 }}>Read Out Loud</h3>
      {voices && voices.length === 0 && <Notice kind="info">No speech voices are installed on this computer. Windows lists its voices under Settings &gt; Time &amp; language &gt; Speech.</Notice>}
      {voices && voices.length > 0 && (
        <div className="grid2">
          <Field label="Voice" hint="Voices installed in Windows. They work offline.">
            <select className="input" aria-label="Read Out Loud voice" value={settings.readVoice} onChange={(e) => save({ readVoice: e.target.value })}>
              <option value="">System default</option>
              {voices.map((v) => <option key={v.name} value={v.name}>{v.name} ({v.lang})</option>)}
            </select>
          </Field>
          <Field label={`Speed: ${settings.readRate.toFixed(1)}x`}>
            <input type="range" min={0.5} max={2} step={0.1} value={settings.readRate} onChange={(e) => save({ readRate: parseFloat(e.target.value) })} />
          </Field>
        </div>
      )}
      <p className="subtle">In a document: View &gt; Read out loud, or Ctrl+Shift+V (this page), Ctrl+Shift+B (to the end), Ctrl+Shift+C (pause), Ctrl+Shift+E (stop).</p>
    </div>
  );
}
