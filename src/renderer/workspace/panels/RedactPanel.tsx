import React, { useEffect, useState } from "react";
import { Search, Square, Trash2 } from "lucide-react";
import { useWs } from "../context";
import { Check, Field, Notice } from "../../components/ui";
import { api } from "../../api";
import { useApp } from "../../store";

export function RedactPanel() {
  const ws = useWs();
  const [terms, setTerms] = useState("");
  const [patterns, setPatterns] = useState<{ id: string; label: string }[]>([]);
  const [chosen, setChosen] = useState<string[]>([]);
  const [matchCase, setMatchCase] = useState(false);
  const [busy, setBusy] = useState(false);
  const [lastReport, setLastReport] = useState<any>(null);
  const [overlayText, setOverlayText] = useState("");
  const [sanitize, setSanitize] = useState(true);
  useEffect(() => {
    api.query("redact.patterns").then((r: any) => setPatterns(r.patterns)).catch(() => undefined);
    if (ws.tool === "none" || !ws.tool) ws.setTool("redactArea");
  }, []);
  const search = async () => {
    setBusy(true);
    try {
      const r: any = await ws.query("redact.search", { terms: terms.split("\n").map((t) => t.trim()).filter(Boolean), patterns: chosen, matchCase });
      ws.setMarks((m) => [...m, ...r.hits.map((h: any) => ({ id: Math.random().toString(36).slice(2), page: h.page, rect: h.rect, text: h.text, label: h.label }))]);
      useApp.getState().toast({ kind: r.count ? "info" : "warning", title: r.count ? `${r.count} match(es) marked` : "No matches found", body: r.pagesWithoutText?.length ? `${r.pagesWithoutText.length} page(s) have no text layer (scanned). Run OCR first or mark those areas by hand.` : undefined });
    } catch (e: any) {
      useApp.getState().toast({ kind: "error", title: "Search failed", body: e.message });
    } finally {
      setBusy(false);
    }
  };
  const apply = async () => {
    const r = await api.confirm(
      "Apply redactions permanently?",
      `${ws.marks.length} marked area(s) will be removed from the document content.`,
      "Text, image pixels and graphics under the marks are deleted, not hidden. This cannot be reversed in the saved file. Your original file stays unchanged until you save over it.",
      ["Redact", "Cancel"],
    );
    if (r !== 0) return;
    const res = await ws.apply("redact.apply", {
      areas: ws.marks.map((m) => ({ page: m.page, rect: m.rect, text: m.text })),
      verifyTerms: terms.split("\n").map((t) => t.trim()).filter(Boolean),
      overlayText, sanitizeMetadata: sanitize, sanitizeAttachments: sanitize, sanitizeJavaScript: true, sanitizeThumbnails: true,
    }, `Redact ${ws.marks.length} area(s)`);
    if (res) {
      setLastReport(res.verification);
      ws.setMarks([]);
    }
  };
  const byPage = ws.marks.reduce<Record<number, number>>((a, m) => ((a[m.page] = (a[m.page] || 0) + 1), a), {});
  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <div className="side-body panel-pad">
        <h3 style={{ marginBottom: 8 }}>Redact</h3>
        <p className="subtle" style={{ marginTop: 0 }}>Mark content first (nothing is removed yet), review the red boxes, then apply.</p>
        <button className={"btn" + (ws.tool === "redactArea" ? " primary" : "")} style={{ width: "100%" }} onClick={() => ws.setTool("redactArea")}>
          <Square size={15} /> Mark area by dragging
        </button>
        <div className="divider" />
        <Field label="Find text to redact (one per line)">
          <textarea className="input" rows={3} value={terms} onChange={(e) => setTerms(e.target.value)} placeholder={"e.g. client name\naccount number"} />
        </Field>
        <Field label="Find patterns">
          {patterns.map((p) => (
            <Check key={p.id} checked={chosen.includes(p.id)} onChange={(v) => setChosen((c) => (v ? [...c, p.id] : c.filter((x) => x !== p.id)))}>{p.label}</Check>
          ))}
        </Field>
        <Check checked={matchCase} onChange={setMatchCase}>Match case</Check>
        <button className="btn" style={{ width: "100%" }} disabled={busy || (!terms.trim() && !chosen.length)} onClick={search}>
          <Search size={15} /> Find and mark
        </button>
        <div className="divider" />
        <div className="row"><strong className="grow">Marked: {ws.marks.length}</strong>
          {ws.marks.length > 0 && <button className="btn sm ghost" onClick={() => ws.setMarks([])}><Trash2 size={13} /> Clear</button>}
        </div>
        {Object.entries(byPage).map(([p, n]) => (
          <div key={p} className="list-item" onClick={() => ws.viewer?.goTo(Number(p))}>Page {p}: {n} area(s)</div>
        ))}
        <div className="divider" />
        <Field label="Text on redaction boxes (optional)"><input className="input" value={overlayText} onChange={(e) => setOverlayText(e.target.value)} placeholder="e.g. REDACTED" /></Field>
        <Check checked={sanitize} onChange={setSanitize}>Also remove metadata, XMP, attachments and thumbnails</Check>
        {lastReport && (
          <Notice kind={lastReport.passed ? "ok" : "err"}>
            {lastReport.passed ? "Verified: the redacted text was not found in text extraction, search, comments, bookmarks, metadata or internal streams, and marked areas render as filled." : "Verification failed."}
          </Notice>
        )}
      </div>
      <div className="panel-pad" style={{ borderTop: "1px solid var(--border)" }}>
        <button className="btn danger" style={{ width: "100%" }} disabled={!ws.marks.length} onClick={apply}>Apply redactions</button>
        <p className="subtle">After applying, Save As a new file. Verify anything you redact before sharing.</p>
      </div>
    </div>
  );
}
