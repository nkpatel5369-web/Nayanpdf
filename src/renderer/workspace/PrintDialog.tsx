// Print dialog modelled on Acrobat's: printer, copies, pages, page sizing and
// handling (fit, actual size, shrink, custom scale, multiple pages per sheet),
// orientation, paper, two-sided printing, comments & forms, with a live preview
// of each sheet. Printing goes straight to the chosen printer; the Windows
// dialog is available for printer-specific driver options.
import React, { useEffect, useMemo, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, Printer, Settings2 } from "lucide-react";
import { api } from "../api";
import { errorText, useApp } from "../store";
import { Check, Field, Modal, Seg, Spinner } from "../components/ui";
import { useWs } from "./context";
import { computePlan, Orientation, PAPERS, parseRange, PER_SHEET, Placement, selectPages, Sizing, Subset } from "./printLayout";

interface PrinterInfo {
  name: string;
  displayName: string;
  description: string;
}

const LS_PRINTER = "nayan.print.printer";
const LS_PAPER = "nayan.print.paper";

function ls(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
function lsSet(key: string, v: string) {
  try {
    localStorage.setItem(key, v);
  } catch {
    /* preferences are a convenience only */
  }
}

/** The standard paper closest to a page size (points), else A4. */
function paperFor(w: number, h: number): string {
  const [a, b] = [Math.min(w, h), Math.max(w, h)];
  for (const [k, p] of Object.entries(PAPERS)) if (Math.abs(p.w - a) < 8 && Math.abs(p.h - b) < 8) return k;
  return "A4";
}

export function PrintDialog({ onClose }: { onClose: () => void }) {
  const ws = useWs();
  const settings = useApp((s) => s.settings);
  const toast = useApp((s) => s.toast);
  const count = ws.viewer?.pageCount() || ws.pdf?.numPages || 1;

  const [printers, setPrinters] = useState<PrinterInfo[] | null>(null);
  const [printer, setPrinter] = useState<string>(ls(LS_PRINTER) || "");
  const [copies, setCopies] = useState(1);
  const [collate, setCollate] = useState(true);
  const [gray, setGray] = useState(false);
  const [range, setRange] = useState<"all" | "current" | "custom">("all");
  const [custom, setCustom] = useState("");
  const [subset, setSubset] = useState<Subset>("all");
  const [reverse, setReverse] = useState(false);
  const [sizing, setSizing] = useState<Sizing>("fit");
  const [scale, setScale] = useState(100);
  const [perSheet, setPerSheet] = useState(2);
  const [order, setOrder] = useState<"horizontal" | "vertical">("horizontal");
  const [borders, setBorders] = useState(false);
  const [orientation, setOrientation] = useState<Orientation>("auto");
  const [paper, setPaper] = useState<string>(ls(LS_PAPER) || "");
  const [duplex, setDuplex] = useState<"simplex" | "longEdge" | "shortEdge">("simplex");
  const [annots, setAnnots] = useState<"all" | "none" | "forms">("all");
  const [dpi, setDpi] = useState(settings.printDpi);
  const [sizes, setSizes] = useState<Record<number, { w: number; h: number }> | null>(null);
  const [sheet, setSheet] = useState(0);
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    api.printers().then(setPrinters).catch(() => setPrinters([]));
    ws.query<{ pages: { page: number; width: number; height: number }[] }>("doc.pageSizes").then((r) => {
      const m: Record<number, { w: number; h: number }> = {};
      for (const p of r.pages) m[p.page] = { w: p.width, h: p.height };
      setSizes(m);
      if (!ls(LS_PAPER) && r.pages[0]) setPaper(paperFor(r.pages[0].width, r.pages[0].height));
    }).catch((e) => toast({ kind: "error", title: "Cannot read page sizes", body: errorText(e) }));
  }, []);

  const base = range === "all" ? Array.from({ length: count }, (_, i) => i + 1) : range === "current" ? [ws.page] : parseRange(custom, count);
  const pages = base ? selectPages(base, subset, reverse) : null;
  const plan = useMemo(() => {
    if (!sizes || !pages || !pages.length) return null;
    return computePlan({ pages, sizing, customScale: scale, perSheet, order, borders, orientation, paper: paper || "A4" }, (n) => sizes[n] || { w: 595, h: 842 });
  }, [sizes, JSON.stringify(pages), sizing, scale, perSheet, order, borders, orientation, paper]);
  useEffect(() => setSheet(0), [plan?.sheets.length, sizing, perSheet]);
  const current = plan ? Math.min(sheet, plan.sheets.length - 1) : 0;

  const problem = !base ? `Enter pages between 1 and ${count}, for example 1-3, 5` : pages && !pages.length ? "No pages match this selection" : null;

  const run = async (systemDialog: boolean) => {
    if (!plan) return;
    setBusy(`Preparing ${plan.sheets.length} sheet${plan.sheets.length === 1 ? "" : "s"}…`);
    lsSet(LS_PRINTER, printer);
    lsSet(LS_PAPER, paper || "A4");
    try {
      const r = await api.printPlan(ws.sessionId, {
        printer, copies, collate, duplex, grayscale: gray, dpi, annots, paper: paper || "A4",
        sheetW: plan.sheetW, sheetH: plan.sheetH, landscape: plan.landscape, sheets: plan.sheets, systemDialog,
      });
      if (r.printed) {
        const who = printers?.find((p) => p.name === printer)?.displayName || "the default printer";
        toast({ kind: "success", title: "Sent to printer", body: `${r.sheets} sheet${r.sheets === 1 ? "" : "s"}${copies > 1 ? ` × ${copies} copies` : ""} to ${systemDialog ? "the printer chosen in Windows" : who}.` });
        onClose();
      } else if (r.reason && !/cancel/i.test(r.reason)) {
        toast({ kind: "error", title: "Printing failed", body: r.reason });
      }
    } catch (e: any) {
      toast({ kind: "error", title: "Printing failed", body: errorText(e) });
    } finally {
      setBusy(null);
    }
  };

  const sizingOpts: { value: Sizing; label: string }[] = [
    { value: "fit", label: "Fit" },
    { value: "actual", label: "Actual size" },
    { value: "shrink", label: "Shrink oversized" },
    { value: "custom", label: "Custom scale" },
    { value: "multiple", label: "Multiple" },
  ];

  return (
    <Modal wide title="Print" icon={<Printer size={18} />} onClose={busy ? undefined : onClose} footer={
      <>
        <button className="btn ghost" style={{ marginRight: "auto" }} disabled={!plan || !!busy || !!problem} onClick={() => run(true)}
          title="Opens the Windows print dialog with these pages laid out as shown, for printer-specific options">
          <Settings2 size={15} /> Printer settings (Windows dialog)…
        </button>
        <button className="btn" onClick={onClose} disabled={!!busy}>Cancel</button>
        <button className="btn primary" disabled={!plan || !!busy || !!problem} onClick={() => run(false)}>
          {busy ? <Spinner /> : <Printer size={15} />} {busy || "Print"}
        </button>
      </>
    }>
      <div className="print-grid">
        <div className="print-opts">
          <div className="grid2">
            <Field label="Printer">
              <select className="input" value={printer} onChange={(e) => setPrinter(e.target.value)} aria-label="Printer">
                <option value="">Windows default printer</option>
                {(printers || []).map((p) => <option key={p.name} value={p.name}>{p.displayName}</option>)}
              </select>
            </Field>
            <Field label="Copies">
              <div className="row" style={{ gap: 10 }}>
                <input className="input" type="number" min={1} max={999} style={{ width: 80 }} value={copies} aria-label="Copies"
                  onChange={(e) => setCopies(Math.max(1, Math.min(999, parseInt(e.target.value) || 1)))} />
                {copies > 1 && <Check checked={collate} onChange={setCollate}>Collate</Check>}
              </div>
            </Field>
          </div>
          {printers && printers.length === 0 && <p className="subtle" style={{ marginTop: -4 }}>No printers were found. Add one in Windows Settings → Bluetooth &amp; devices → Printers &amp; scanners.</p>}
          <Check checked={gray} onChange={setGray}>Print in grayscale (black and white)</Check>

          <h4 className="print-h">Pages to print</h4>
          <div className="row wrap" style={{ gap: 14 }}>
            <label className="check"><input type="radio" checked={range === "all"} onChange={() => setRange("all")} /> All ({count})</label>
            <label className="check"><input type="radio" checked={range === "current"} onChange={() => setRange("current")} /> Current page ({ws.page})</label>
            <label className="check"><input type="radio" checked={range === "custom"} onChange={() => setRange("custom")} /> Pages</label>
            <input className="input sm" style={{ width: 130 }} placeholder="1-3, 5, 8-" value={custom} aria-label="Pages"
              onFocus={() => setRange("custom")} onChange={(e) => { setRange("custom"); setCustom(e.target.value); }} />
          </div>
          <div className="row wrap" style={{ gap: 14, marginTop: 6 }}>
            <select className="input sm" style={{ width: 170 }} value={subset} onChange={(e) => setSubset(e.target.value as Subset)} aria-label="Odd or even pages">
              <option value="all">All pages in range</option><option value="odd">Odd pages only</option><option value="even">Even pages only</option>
            </select>
            <Check checked={reverse} onChange={setReverse}>Reverse pages</Check>
          </div>
          {problem && <p className="print-err">{problem}</p>}

          <h4 className="print-h">Page sizing &amp; handling</h4>
          <Seg value={sizing} onChange={setSizing} options={sizingOpts} />
          {sizing === "custom" && (
            <div className="row" style={{ gap: 8, marginTop: 8 }}>
              <span>Scale</span>
              <input className="input sm" type="number" min={10} max={400} style={{ width: 80 }} value={scale} aria-label="Scale percent"
                onChange={(e) => setScale(Math.max(10, Math.min(400, parseInt(e.target.value) || 100)))} />
              <span>%</span>
            </div>
          )}
          {sizing === "multiple" && (
            <div className="row wrap" style={{ gap: 12, marginTop: 8 }}>
              <label className="row" style={{ gap: 6 }}>Pages per sheet
                <select className="input sm" style={{ width: 70 }} value={perSheet} onChange={(e) => setPerSheet(parseInt(e.target.value))} aria-label="Pages per sheet">
                  {PER_SHEET.map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </label>
              <label className="row" style={{ gap: 6 }}>Page order
                <select className="input sm" style={{ width: 120 }} value={order} onChange={(e) => setOrder(e.target.value as any)} aria-label="Page order">
                  <option value="horizontal">Horizontal</option><option value="vertical">Vertical</option>
                </select>
              </label>
              <Check checked={borders} onChange={setBorders}>Print page border</Check>
            </div>
          )}
          <p className="subtle" style={{ margin: "6px 0 0" }}>
            {sizing === "fit" ? "Each page is enlarged or reduced to fill the printable area." :
              sizing === "actual" ? "Pages print at 100%; content outside the paper or the printer's margins is cut off." :
                sizing === "shrink" ? "Pages larger than the paper are reduced; smaller pages print at 100%." :
                  sizing === "custom" ? "Pages print at the chosen percentage, centred on the paper." : "Several pages are reduced and arranged on each sheet."}
          </p>

          <div className="grid2" style={{ marginTop: 10 }}>
            <Field label="Orientation">
              <select className="input" value={orientation} onChange={(e) => setOrientation(e.target.value as Orientation)} aria-label="Orientation">
                <option value="auto">Auto portrait/landscape</option><option value="portrait">Portrait</option><option value="landscape">Landscape</option>
              </select>
            </Field>
            <Field label="Paper size">
              <select className="input" value={paper || "A4"} onChange={(e) => setPaper(e.target.value)} aria-label="Paper size">
                {Object.entries(PAPERS).map(([k, p]) => <option key={k} value={k}>{p.label}</option>)}
              </select>
            </Field>
            <Field label="Print on both sides">
              <select className="input" value={duplex} onChange={(e) => setDuplex(e.target.value as any)} aria-label="Print on both sides">
                <option value="simplex">Off (one side)</option><option value="longEdge">Flip on long edge</option><option value="shortEdge">Flip on short edge</option>
              </select>
            </Field>
            <Field label="Comments & forms">
              <select className="input" value={annots} onChange={(e) => setAnnots(e.target.value as any)} aria-label="Comments and forms">
                <option value="all">Document and markups</option><option value="none">Document</option><option value="forms">Form fields only</option>
              </select>
            </Field>
            <Field label="Print quality">
              <select className="input" value={dpi} onChange={(e) => setDpi(parseInt(e.target.value))} aria-label="Print quality">
                <option value={150}>Draft (150 dpi)</option><option value={300}>Standard (300 dpi)</option><option value={600}>High (600 dpi)</option>
              </select>
            </Field>
          </div>
        </div>

        <div className="print-preview">
          <div className="row" style={{ justifyContent: "space-between", marginBottom: 8 }}>
            <strong>Preview</strong>
            {plan && (
              <div className="row" style={{ gap: 4 }}>
                <button className="icon-btn" disabled={current <= 0} onClick={() => setSheet(current - 1)} title="Previous sheet"><ChevronLeft size={16} /></button>
                <span className="subtle" style={{ minWidth: 90, textAlign: "center" }}>Sheet {current + 1} of {plan.sheets.length}</span>
                <button className="icon-btn" disabled={current >= plan.sheets.length - 1} onClick={() => setSheet(current + 1)} title="Next sheet"><ChevronRight size={16} /></button>
              </div>
            )}
          </div>
          {plan ? <SheetPreview plan={plan} index={current} annots={annots} gray={gray} /> : <div className="print-sheet-empty">{sizes ? "Nothing to print" : <Spinner />}</div>}
          {plan && (
            <p className="subtle" style={{ textAlign: "center", marginTop: 8 }}>
              {(PAPERS[paper || "A4"] || PAPERS.A4).label.split(" (")[0]} {plan.landscape ? "landscape" : "portrait"} · {plan.sheets.length} sheet{plan.sheets.length === 1 ? "" : "s"}
              {copies > 1 ? ` × ${copies} copies` : ""}{duplex !== "simplex" ? " · two-sided" : ""}{gray ? " · black and white" : ""}
            </p>
          )}
        </div>
      </div>
    </Modal>
  );
}

const thumbCache = new Map<string, Promise<string>>();

function SheetPreview(props: { plan: NonNullable<ReturnType<typeof computePlan>>; index: number; annots: string; gray: boolean }) {
  const ws = useWs();
  const { plan } = props;
  const box = { w: 330, h: 430 };
  const s = Math.min(box.w / plan.sheetW, box.h / plan.sheetH);
  const items = plan.sheets[props.index] || [];
  return (
    <div className="print-sheet" style={{ width: plan.sheetW * s, height: plan.sheetH * s }} data-testid="print-sheet">
      {items.map((it, k) => <PreviewItem key={`${props.index}-${k}-${it.page}`} it={it} s={s} annots={props.annots} gray={props.gray} revision={ws.revision} />)}
    </div>
  );
}

function PreviewItem({ it, s, annots, gray, revision }: { it: Placement; s: number; annots: string; gray: boolean; revision: number }) {
  const ws = useWs();
  const [src, setSrc] = useState<string | null>(null);
  const alive = useRef(true);
  // Rendered by the same engine as the printout, so comments/forms and black-and-white match.
  const width = Math.min(900, Math.max(60, Math.round((it.rotate ? it.h : it.w) * s * 2)));
  useEffect(() => {
    alive.current = true;
    const key = `${ws.sessionId}:${revision}:${it.page}:${width}:${annots}:${gray}`;
    if (!thumbCache.has(key)) {
      thumbCache.set(key, ws.query<{ png: string }>("pages.thumbnail", { page: it.page, width, annots, grayscale: gray })
        .then((r) => "data:image/png;base64," + r.png)
        .catch((e) => {
          thumbCache.delete(key);
          throw e;
        }));
    }
    thumbCache.get(key)!.then((u) => alive.current && setSrc(u)).catch(() => undefined);
    return () => {
      alive.current = false;
    };
  }, [it.page, width, annots, gray, revision]);
  const imgStyle: React.CSSProperties = it.rotate
    ? { width: it.h * s, height: it.w * s, transform: "translate(-50%,-50%) rotate(90deg)" }
    : { width: it.w * s, height: it.h * s, transform: "translate(-50%,-50%)" };
  return (
    <div className={"print-item" + (it.border ? " b" : "")} style={{ left: it.x * s, top: it.y * s, width: it.w * s, height: it.h * s }}>
      {src ? <img src={src} style={imgStyle} alt={`Page ${it.page}`} /> : <div className="print-ph">{it.page}</div>}
    </div>
  );
}
