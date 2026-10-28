import React, { useEffect, useRef, useState } from "react";
import { Check, X } from "lucide-react";
import { api } from "../api";
import { useApp } from "../store";
import { useWs } from "./context";
import { cssPointToPdf, cssRectToPdf, PageCtx, pdfRectToCss } from "./Viewer";
import { TEXT_TOOLS } from "./Selection";
import { FIELD_TOOLS, FIELD_TYPE_OF } from "./panels/PreparePanel";

// Tools that are drawn as a rectangle.
const RECT_TOOLS = new Set(["highlight", "underline", "strikeout", "squiggly", "freetext", "callout", "rect", "circle", "stamp",
  "initials", "addText", "addImage", "redactArea", "placeSignature", "digitalBox", "textstamp", "link",
  "textField", "checkField", "radioField", "comboField", "listField", "sigField"]);
// Size of a field placed with a click instead of a drag, in points.
const FIELD_SIZE: Record<string, [number, number]> = {
  textField: [150, 18], checkField: [12, 12], radioField: [12, 12], comboField: [120, 18], listField: [120, 60], sigField: [160, 40],
};
const LINE_TOOLS = new Set(["line", "arrow"]);

interface Drag {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

function rel(e: React.MouseEvent | MouseEvent, el: HTMLElement) {
  const r = el.getBoundingClientRect();
  return { x: e.clientX - r.left, y: e.clientY - r.top };
}

// Cache of per-page engine queries keyed by revision.
const cache = new Map<string, Promise<any>>();
function cached<T>(key: string, fn: () => Promise<T>): Promise<T> {
  if (!cache.has(key)) {
    const p = fn().catch((e) => {
      cache.delete(key);
      throw e;
    });
    cache.set(key, p);
  }
  return cache.get(key)!;
}

export function PageOverlay({ ctx }: { ctx: PageCtx }) {
  const ws = useWs();
  const ref = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [ink, setInk] = useState<number[][]>([]);
  const [pending, setPending] = useState<{ kind: string; rect: number[]; css: Drag; p1?: number[]; p2?: number[] } | null>(null);
  const vp = ctx.viewport;
  const tool = ws.tool;
  // Comment "Select" and the text markup tools work on the text selection, so the
  // overlay lets the mouse through to the text layer (annotation boxes stay clickable).
  const interactive = ws.mode !== "view" && ws.mode !== "pages" && tool !== "none" && !(ws.mode === "comment" && TEXT_TOOLS.has(tool));

  const onDown = (e: React.MouseEvent) => {
    if (e.button !== 0 || !ref.current || pending) return;
    if ((e.target as HTMLElement).closest("[data-noprop]")) return;
    const p = rel(e, ref.current);
    if (tool === "fieldSelect") {
      ws.setSelected(null);
      return;
    }
    if (tool === "ink") {
      setInk([[p.x, p.y]]);
    } else if (RECT_TOOLS.has(tool) || LINE_TOOLS.has(tool) || tool === "note") {
      setDrag({ x0: p.x, y0: p.y, x1: p.x, y1: p.y });
    } else {
      return;
    }
    const move = (ev: MouseEvent) => {
      const q = rel(ev, ref.current!);
      if (tool === "ink") setInk((pts) => [...pts, [q.x, q.y]]);
      else setDrag((d) => (d ? { ...d, x1: q.x, y1: q.y } : d));
    };
    const up = (ev: MouseEvent) => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
      const q = rel(ev, ref.current!);
      if (tool === "ink") {
        setInk((pts) => {
          const all = [...pts, [q.x, q.y]];
          if (all.length > 2) commitInk(all);
          return [];
        });
        return;
      }
      setDrag(null);
      const d = { x0: p.x, y0: p.y, x1: q.x, y1: q.y };
      const small = Math.abs(d.x1 - d.x0) < 4 && Math.abs(d.y1 - d.y0) < 4;
      finishDrag(d, small);
    };
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
  };

  const commitInk = (pts: number[][]) => {
    const pdfPts = pts.filter((_, i) => i % 2 === 0 || i === pts.length - 1).map(([x, y]) => cssPointToPdf(vp, x, y));
    ws.apply("annot.apply", { ops: [{ op: "add", page: ctx.pageNumber, kind: "ink", strokes: [pdfPts], color: ws.opts.color, width: ws.opts.width ?? 2, opacity: ws.opts.opacity ?? 1 }], author: authorName() }, "Draw");
  };

  const finishDrag = (d: Drag, small: boolean) => {
    const sc = vp.scale;
    if (tool === "note") {
      const [x, y] = cssPointToPdf(vp, d.x0, d.y0);
      setPending({ kind: "note", rect: [x, y - 20, x + 20, y], css: d });
      return;
    }
    if (LINE_TOOLS.has(tool)) {
      if (small) return;
      const p1 = cssPointToPdf(vp, d.x0, d.y0);
      const p2 = cssPointToPdf(vp, d.x1, d.y1);
      ws.apply("annot.apply", { ops: [{ op: "add", page: ctx.pageNumber, kind: tool, p1, p2, color: ws.opts.color, width: ws.opts.width ?? 2, opacity: ws.opts.opacity ?? 1 }], author: authorName() }, tool === "arrow" ? "Arrow" : "Line");
      return;
    }
    let css = d;
    if (small && FIELD_SIZE[tool]) {
      // A click places the field with its top-left corner at the pointer.
      const [w, h] = FIELD_SIZE[tool];
      css = { x0: d.x0, y0: d.y0, x1: d.x0 + w * sc, y1: d.y0 + h * sc };
    } else if (small) {
      // A click places a default-sized box.
      const w = (tool === "placeSignature" ? 160 : tool === "stamp" || tool === "textstamp" ? 150 : tool === "digitalBox" ? 200 : 180) * sc;
      const h = (tool === "placeSignature" ? 60 : tool === "stamp" || tool === "textstamp" ? 50 : tool === "digitalBox" ? 60 : 40) * sc;
      if (["highlight", "underline", "strikeout", "squiggly", "redactArea", "rect", "circle", "link"].includes(tool)) return;
      css = { x0: d.x0, y0: d.y0, x1: d.x0 + w, y1: d.y0 + h };
    }
    const rect = cssRectToPdf(vp, css.x0, css.y0, css.x1, css.y1);
    const page = ctx.pageNumber;
    switch (tool) {
      case "highlight":
      case "underline":
      case "strikeout":
      case "squiggly":
        ws.apply("annot.apply", { ops: [{ op: "add", page, kind: tool, rect, color: ws.opts.color, opacity: ws.opts.opacity ?? 1 }], author: authorName() }, cap(tool));
        break;
      case "rect":
      case "circle":
        ws.apply("annot.apply", { ops: [{ op: "add", page, kind: tool, rect, color: ws.opts.color, fill: ws.opts.fill || undefined, width: ws.opts.width ?? 2, opacity: ws.opts.opacity ?? 1 }], author: authorName() }, cap(tool));
        break;
      case "stamp":
        ws.apply("annot.apply", { ops: [{ op: "add", page, kind: "stamp", rect, stamp: ws.opts.stamp || "Approved", text: ws.opts.stampText, withDate: ws.opts.stampDate, color: ws.opts.stampColor || "#c92a2a", imagePath: ws.opts.stampImage }], author: authorName() }, "Stamp");
        break;
      case "initials": {
        const s = useApp.getState().settings;
        if (!s.reviewerInitials) {
          useApp.getState().toast({ kind: "warning", title: "Set your initials first", body: "Settings > Reviewer initials" });
          return;
        }
        ws.apply("annot.apply", { ops: [{ op: "add", page, kind: "initials", rect, initials: s.reviewerInitials, color: ws.opts.color || "#c92a2a" }], author: authorName() }, "Initials");
        break;
      }
      case "freetext":
      case "callout":
      case "addText":
      case "link":
        setPending({ kind: tool, rect, css });
        break;
      case "addImage":
        api.openFiles("images", false, "Choose image").then((f) => {
          if (f[0]) ws.apply("edit.insertImage", { page, rect, imagePath: f[0], keepAspect: true }, "Insert image");
        });
        break;
      case "redactArea":
        ws.setMarks((m) => [...m, { id: Math.random().toString(36).slice(2), page, rect, label: "Area" }]);
        break;
      case "placeSignature":
        if (!ws.opts.signaturePng) {
          useApp.getState().toast({ kind: "warning", title: "Choose or create a signature first" });
          return;
        }
        ws.apply("sign.placeImage", {
          page, rect, imageBase64: ws.opts.signaturePng, pages: ws.opts.signPages || undefined,
          dateText: ws.opts.signDate ? `Date: ${new Date().toLocaleDateString("en-GB")}` : undefined, flatten: ws.opts.signFlatten !== false,
        }, "Signature");
        break;
      case "digitalBox":
        window.dispatchEvent(new CustomEvent("nayan:digital-box", { detail: { sessionId: ws.sessionId, page, rect } }));
        break;
      case "radioField":
        setPending({ kind: "radio", rect, css });
        break;
      case "textField":
      case "checkField":
      case "comboField":
      case "listField":
      case "sigField": {
        const label = FIELD_TOOLS.find((t) => t.id === tool)!.label;
        ws.apply("forms.design", { ops: [{ op: "add", type: FIELD_TYPE_OF[tool], page, rect }] }, `Add ${label.toLowerCase()}`).then((r) => {
          const c = r?.created?.[0];
          if (!c) return;
          // As in Acrobat, the new field is selected so its properties can be set.
          ws.setTool("fieldSelect");
          ws.setSelected({ kind: "dfield", name: c.name, xref: c.xref, page });
        });
        break;
      }
    }
  };

  const submitPending = (text: string) => {
    if (!pending) return;
    const page = ctx.pageNumber;
    if (pending.kind === "note") {
      ws.apply("annot.apply", { ops: [{ op: "add", page, kind: "note", point: [pending.rect[0], pending.rect[3]], contents: text, color: ws.opts.color }], author: authorName() }, "Note");
    } else if (pending.kind === "freetext" || pending.kind === "callout") {
      const r = pending.rect;
      const op: any = { op: "add", page, kind: pending.kind, rect: r, text, fontSize: ws.opts.fontSize || 11, textColor: ws.opts.textColor || "#000000", color: ws.opts.color, fill: ws.opts.fill || undefined };
      if (pending.kind === "callout") op.calloutPoints = [[r[0] - 60, r[1] - 40], [r[0] - 20, (r[1] + r[3]) / 2], [r[0], (r[1] + r[3]) / 2]];
      ws.apply("annot.apply", { ops: [op], author: authorName() }, pending.kind === "callout" ? "Callout" : "Text comment");
    } else if (pending.kind === "addText") {
      ws.apply("edit.insertText", { page, rect: pending.rect, text, fontSize: ws.opts.fontSize || 11, color: ws.opts.textColor || "#000000", fontFamily: ws.opts.fontFamily || "helv", bold: !!ws.opts.bold, italic: !!ws.opts.italic, align: ws.opts.align || "left" }, "Add text");
    }
    setPending(null);
  };

  const cursor = interactive ? (tool === "text" || tool === "image" || tool === "select" || tool === "fields" || tool === "fieldSelect" ? "default" : "crosshair") : "default";

  return (
    <div ref={ref} className={"overlay" + (interactive ? "" : " passive")} style={{ cursor }} onMouseDown={interactive ? onDown : undefined}>
      {ws.mode === "edit" && tool === "text" && <TextLines ctx={ctx} />}
      {ws.mode === "edit" && tool === "image" && <ImageObjects ctx={ctx} />}
      {ws.mode === "edit" && tool === "link" && <LinkBoxes ctx={ctx} />}
      {ws.mode === "comment" && tool === "select" && <AnnotBoxes ctx={ctx} />}
      {ws.mode === "forms" && <FieldBoxes ctx={ctx} />}
      {ws.mode === "prepare" && <DesignBoxes ctx={ctx} />}
      {ws.mode === "prepare" && <SuggestionBoxes ctx={ctx} />}
      {(ws.mode === "redact" || ws.marks.length > 0) && <Marks ctx={ctx} />}
      {drag && !LINE_TOOLS.has(tool) && (
        <div className="ov-drag" style={{ left: Math.min(drag.x0, drag.x1), top: Math.min(drag.y0, drag.y1), width: Math.abs(drag.x1 - drag.x0), height: Math.abs(drag.y1 - drag.y0) }} />
      )}
      {drag && LINE_TOOLS.has(tool) && (
        <svg style={{ position: "absolute", inset: 0, width: "100%", height: "100%", pointerEvents: "none" }}>
          <line x1={drag.x0} y1={drag.y0} x2={drag.x1} y2={drag.y1} stroke={ws.opts.color || "#e03131"} strokeWidth={2} />
        </svg>
      )}
      {ink.length > 1 && (
        <svg style={{ position: "absolute", inset: 0, width: "100%", height: "100%", pointerEvents: "none" }}>
          <polyline points={ink.map((p) => p.join(",")).join(" ")} fill="none" stroke={ws.opts.color || "#1c7ed6"} strokeWidth={(ws.opts.width ?? 2) * vp.scale} strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      )}
      {pending && pending.kind === "link" && (
        <LinkEntry css={pending.css} pageCount={ws.pdf?.numPages || 1} onCancel={() => setPending(null)}
          onSubmit={(target) => {
            ws.apply("links.apply", { ops: [{ op: "add", page: ctx.pageNumber, rect: pending.rect, ...target }] }, "Add link");
            setPending(null);
          }} />
      )}
      {pending && pending.kind === "radio" && (
        <RadioEntry css={pending.css} group={ws.opts.radioGroup || ""} groups={(ws.opts.designFields || []).filter((f: any) => f.type === "radio").map((f: any) => f.name)}
          onCancel={() => setPending(null)}
          onSubmit={(group, value) => {
            const rect = pending.rect;
            setPending(null);
            ws.apply("forms.design", { ops: [{ op: "add", type: "radio", page: ctx.pageNumber, name: group || undefined, buttons: [{ rect, export: value || undefined }] }] }, "Add radio button").then((r) => {
              const c = r?.created?.[0];
              // Stay on the radio tool, adding to the same group, until the user picks another tool.
              if (c) {
                ws.setOpts({ radioGroup: c.name });
                ws.setSelected({ kind: "dfield", name: c.name, xref: c.xref, page: ctx.pageNumber });
              }
            });
          }} />
      )}
      {pending && pending.kind !== "link" && pending.kind !== "radio" && <TextEntry css={pending.css} multiline={pending.kind !== "note"} placeholder={pending.kind === "note" ? "Comment" : "Type text"} onSubmit={submitPending} onCancel={() => setPending(null)} />}
    </div>
  );
}

function cap(s: string) {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function authorName() {
  return useApp.getState().settings.reviewerName || undefined;
}

/** Where a new link goes: a page of this document, or a web address (kept for readers; NayanPDF never opens it). */
function LinkEntry(props: { css: Drag; pageCount: number; onSubmit: (t: { toPage?: number; uri?: string }) => void; onCancel: () => void }) {
  const [kind, setKind] = useState<"page" | "uri">("page");
  const [pageNo, setPageNo] = useState("1");
  const [uri, setUri] = useState("");
  const left = Math.min(props.css.x0, props.css.x1);
  const top = Math.min(props.css.y0, props.css.y1) + Math.abs(props.css.y1 - props.css.y0) + 6;
  const n = parseInt(pageNo);
  const ok = kind === "page" ? n >= 1 && n <= props.pageCount : uri.trim().length > 3;
  const submit = () => ok && props.onSubmit(kind === "page" ? { toPage: n } : { uri: uri.trim() });
  return (
    <div className="edit-pop link-pop" data-noprop style={{ left, top, width: 300 }} onMouseDown={(e) => e.stopPropagation()}
      onKeyDown={(e) => { if (e.key === "Escape") props.onCancel(); if (e.key === "Enter") submit(); }}>
      <strong>Link to</strong>
      <label className="check"><input type="radio" name="lk" checked={kind === "page"} onChange={() => setKind("page")} /> A page in this document</label>
      {kind === "page" && <input className="input sm" aria-label="Link target page" type="number" min={1} max={props.pageCount} value={pageNo} onChange={(e) => setPageNo(e.target.value)} autoFocus />}
      <label className="check"><input type="radio" name="lk" checked={kind === "uri"} onChange={() => setKind("uri")} /> A web address</label>
      {kind === "uri" && <input className="input sm" aria-label="Link web address" placeholder="https://..." value={uri} onChange={(e) => setUri(e.target.value)} autoFocus />}
      {kind === "uri" && <span className="subtle">Saved in the PDF for readers. NayanPDF itself never opens web addresses.</span>}
      <div className="row" style={{ width: "100%", justifyContent: "flex-end" }}>
        <button className="btn sm" onClick={props.onCancel}><X size={13} /> Cancel</button>
        <button className="btn sm primary" disabled={!ok} onClick={submit}><Check size={13} /> Add link</button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------ links (Edit > Link)
function LinkBoxes({ ctx }: { ctx: PageCtx }) {
  const ws = useWs();
  const [list, setList] = useState<any[]>([]);
  useEffect(() => {
    let alive = true;
    cached(`${ws.sessionId}:${ws.revision}:links:${ctx.pageNumber}`, () => ws.query("links.list", { page: ctx.pageNumber }))
      .then((d: any) => alive && setList(d.links || []))
      .catch(() => alive && setList([]));
    return () => {
      alive = false;
    };
  }, [ws.revision, ctx.pageNumber]);
  return (
    <>
      {list.map((l) => (
        <div key={l.index} className="ov-link" style={pdfRectToCss(ctx.viewport, l.rect)} data-noprop
          title={l.kind === "page" ? `Link to page ${l.targetPage}` : l.uri ? `Link to ${l.uri}` : "Link"}>
          <button className="rm" title="Remove link" onMouseDown={(e) => e.stopPropagation()}
            onClick={() => ws.apply("links.apply", { ops: [{ op: "delete", page: ctx.pageNumber, index: l.index }] }, "Remove link")}>×</button>
        </div>
      ))}
    </>
  );
}

function TextEntry(props: { css: Drag; multiline: boolean; placeholder: string; onSubmit: (t: string) => void; onCancel: () => void; initial?: string }) {
  const [t, setT] = useState(props.initial || "");
  const left = Math.min(props.css.x0, props.css.x1);
  const top = Math.min(props.css.y0, props.css.y1);
  return (
    <div className="edit-pop" data-noprop style={{ left, top: top + Math.abs(props.css.y1 - props.css.y0) + 6 }} onMouseDown={(e) => e.stopPropagation()}>
      <textarea className="input" autoFocus rows={props.multiline ? 3 : 2} style={{ width: 280 }} placeholder={props.placeholder} value={t}
        onChange={(e) => setT(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Escape") props.onCancel();
          if (e.key === "Enter" && (e.ctrlKey || !props.multiline)) { e.preventDefault(); if (t.trim()) props.onSubmit(t); }
        }} />
      <div className="row" style={{ width: "100%", justifyContent: "flex-end" }}>
        <span className="subtle grow">{props.multiline ? "Ctrl+Enter to apply" : "Enter to apply"}</span>
        <button className="btn sm" onClick={props.onCancel}><X size={13} /> Cancel</button>
        <button className="btn sm primary" disabled={!t.trim()} onClick={() => props.onSubmit(t)}><Check size={13} /> Apply</button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------ edit text
function TextLines({ ctx }: { ctx: PageCtx }) {
  const ws = useWs();
  const [data, setData] = useState<any>(null);
  const [sel, setSel] = useState<any>(null);
  const [moving, setMoving] = useState<{ dx: number; dy: number } | null>(null);
  useEffect(() => {
    let alive = true;
    cached(`${ws.sessionId}:${ws.revision}:lines:${ctx.pageNumber}`, () => ws.query("page.textLines", { page: ctx.pageNumber }))
      .then((d) => alive && setData(d))
      .catch(() => alive && setData({ blocks: [] }));
    setSel(null);
    return () => {
      alive = false;
    };
  }, [ws.revision, ctx.pageNumber]);
  if (!data) return null;
  if (!data.hasText) {
    return ctx.pageNumber === ws.page ? (
      <div data-noprop style={{ position: "absolute", left: 10, top: 10, right: 10 }} className="notice warn">
        This page has no editable text (it may be a scanned image). Run OCR to make it searchable, or use Add Text.
      </div>
    ) : null;
  }
  const paragraph = ws.opts.editUnit === "paragraph";
  const units: any[] = [];
  for (const b of data.blocks) {
    if (paragraph) {
      units.push({ id: "b" + b.id, rect: b.pdfBbox, text: b.lines.map((l: any) => l.text).join(" "), span: b.lines[0].spans[0], mode: "block", editable: b.lines.every((l: any) => l.editable) });
    } else {
      for (const l of b.lines) units.push({ id: l.id, rect: l.pdfBbox, text: l.text, span: l.spans[0], mode: "line", editable: l.editable });
    }
  }
  const startMove = (e: React.MouseEvent, u: any) => {
    if (sel?.id !== u.id) return;
    e.stopPropagation();
    const sx = e.clientX;
    const sy = e.clientY;
    let moved = false;
    const mm = (ev: MouseEvent) => {
      const dx = ev.clientX - sx;
      const dy = ev.clientY - sy;
      if (Math.abs(dx) + Math.abs(dy) > 3) moved = true;
      setMoving({ dx, dy });
    };
    const mu = (ev: MouseEvent) => {
      window.removeEventListener("mousemove", mm);
      window.removeEventListener("mouseup", mu);
      setMoving(null);
      if (!moved) return;
      const css = pdfRectToCss(ctx.viewport, u.rect);
      const a = ctx.viewport.convertToPdfPoint(css.left, css.top);
      const b = ctx.viewport.convertToPdfPoint(css.left + ev.clientX - sx, css.top + ev.clientY - sy);
      ws.apply("edit.text", { page: ctx.pageNumber, rect: u.rect, newText: u.text, mode: u.mode, moveBy: [b[0] - a[0], b[1] - a[1]], anchor: "left" }, "Move text");
    };
    window.addEventListener("mousemove", mm);
    window.addEventListener("mouseup", mu);
  };
  return (
    <>
      {units.map((u) => {
        const css = pdfRectToCss(ctx.viewport, u.rect);
        const isSel = sel?.id === u.id;
        const style: React.CSSProperties = { ...css, transform: isSel && moving ? `translate(${moving.dx}px, ${moving.dy}px)` : undefined };
        return (
          <div key={u.id} data-text={u.text} className={"ov-line" + (isSel ? " sel" : "")} style={{ ...style, cursor: isSel ? "move" : u.editable ? "text" : "not-allowed" }} title={u.editable ? "Click to edit, drag to move" : "Rotated text cannot be edited in place"}
            onMouseDown={(e) => startMove(e, u)}
            onClick={(e) => { e.stopPropagation(); if (u.editable && !moving) setSel(u); }} />
        );
      })}
      {sel && !moving && <LineEditor ctx={ctx} unit={sel} onClose={() => setSel(null)} />}
    </>
  );
}

function LineEditor({ ctx, unit, onClose }: { ctx: PageCtx; unit: any; onClose: () => void }) {
  const ws = useWs();
  const [text, setText] = useState(unit.text);
  const [size, setSize] = useState<number>(unit.span?.size || 11);
  const [color, setColor] = useState<string>(unit.span?.color || "#000000");
  const [bold, setBold] = useState<boolean>(!!unit.span?.bold);
  const [italic, setItalic] = useState<boolean>(!!unit.span?.italic);
  const [anchor, setAnchor] = useState("auto");
  const [fit, setFit] = useState("none");
  const css = pdfRectToCss(ctx.viewport, unit.rect);
  const commit = () => {
    const params: any = { page: ctx.pageNumber, rect: unit.rect, newText: text, mode: unit.mode, anchor, fit };
    if (Math.abs(size - (unit.span?.size || 11)) > 0.01) params.fontSize = size;
    if (color.toLowerCase() !== (unit.span?.color || "#000000").toLowerCase()) params.color = color;
    if (bold !== !!unit.span?.bold) params.bold = bold;
    if (italic !== !!unit.span?.italic) params.italic = italic;
    if (text === unit.text && Object.keys(params).length === 6 && anchor === "auto" && fit === "none") return onClose();
    ws.apply("edit.text", params, "Edit text");
    onClose();
  };
  return (
    <div data-noprop onMouseDown={(e) => e.stopPropagation()} onClick={(e) => e.stopPropagation()}>
      <div className="inline-editor" style={{ left: css.left - 4, top: css.top - 4, width: Math.max(css.width + 40, 160), background: "#fff" }}>
        <textarea autoFocus rows={unit.mode === "block" ? Math.max(2, Math.round(css.height / (size * ctx.viewport.scale * 1.2))) : 1}
          style={{ fontSize: Math.max(10, size * ctx.viewport.scale * 0.92), fontWeight: bold ? 700 : 400, fontStyle: italic ? "italic" : "normal", color }}
          value={text} onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Escape") onClose();
            if (e.key === "Enter" && (unit.mode === "line" || e.ctrlKey)) { e.preventDefault(); commit(); }
          }} />
      </div>
      <div className="edit-pop" style={{ left: css.left - 4, top: css.top + css.height + 8 }}>
        <span className="label">Size</span>
        <input className="input sm" type="number" step={0.5} style={{ width: 62 }} value={size} onChange={(e) => setSize(parseFloat(e.target.value) || size)} />
        <input type="color" value={color} onChange={(e) => setColor(e.target.value)} title="Text colour" style={{ width: 28, height: 24, border: "none", background: "none" }} />
        <button className={"btn sm" + (bold ? " primary" : "")} onClick={() => setBold(!bold)} title="Bold (uses a substitute font)"><b>B</b></button>
        <button className={"btn sm" + (italic ? " primary" : "")} onClick={() => setItalic(!italic)} title="Italic (uses a substitute font)"><i>I</i></button>
        {unit.mode === "line" && (
          <>
            <select className="input sm" style={{ width: 120 }} value={anchor} onChange={(e) => setAnchor(e.target.value)} title="Which edge stays fixed when the length changes">
              <option value="auto">Align: auto</option>
              <option value="left">Keep left edge</option>
              <option value="right">Keep right edge</option>
              <option value="center">Keep centre</option>
            </select>
            <select className="input sm" style={{ width: 120 }} value={fit} onChange={(e) => setFit(e.target.value)} title="If the new text is longer than the original">
              <option value="none">Overflow: allow</option>
              <option value="shrink">Shrink font to fit</option>
              <option value="condense">Condense to fit</option>
            </select>
          </>
        )}
        <div className="grow" />
        <button className="btn sm" onClick={onClose}>Cancel</button>
        <button className="btn sm primary" onClick={commit}><Check size={13} /> Apply</button>
        <div className="subtle" style={{ width: "100%" }}>Edits replace the original text in the page content. The original font is reused when it contains every character; otherwise a similar font is substituted and you are told.</div>
      </div>
    </div>
  );
}

// ------------------------------------------------------------ image objects
function ImageObjects({ ctx }: { ctx: PageCtx }) {
  const ws = useWs();
  const [imgs, setImgs] = useState<any[]>([]);
  const [sel, setSel] = useState<number | null>(null);
  const [delta, setDelta] = useState<{ dx: number; dy: number; dw: number; dh: number } | null>(null);
  useEffect(() => {
    let alive = true;
    cached(`${ws.sessionId}:${ws.revision}:imgs:${ctx.pageNumber}`, () => ws.query("edit.listImages", { page: ctx.pageNumber }))
      .then((d: any) => alive && setImgs(d.images || []))
      .catch(() => alive && setImgs([]));
    setSel(null);
    return () => {
      alive = false;
    };
  }, [ws.revision, ctx.pageNumber]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (sel === null || (e.target as HTMLElement).tagName === "INPUT") return;
      if (e.key === "Delete") {
        ws.apply("edit.image", { page: ctx.pageNumber, rect: imgs[sel].rect, action: "delete" }, "Delete image");
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [sel, imgs]);
  useEffect(() => {
    ws.setSelected(sel !== null ? { kind: "image", page: ctx.pageNumber, rect: imgs[sel]?.rect } : null);
  }, [sel]);

  const begin = (e: React.MouseEvent, i: number, resize: boolean) => {
    e.stopPropagation();
    setSel(i);
    const sx = e.clientX;
    const sy = e.clientY;
    let moved = false;
    const mm = (ev: MouseEvent) => {
      const dx = ev.clientX - sx;
      const dy = ev.clientY - sy;
      if (Math.abs(dx) + Math.abs(dy) > 2) moved = true;
      setDelta(resize ? { dx: 0, dy: 0, dw: dx, dh: dy } : { dx, dy, dw: 0, dh: 0 });
    };
    const mu = (ev: MouseEvent) => {
      window.removeEventListener("mousemove", mm);
      window.removeEventListener("mouseup", mu);
      const dx = ev.clientX - sx;
      const dy = ev.clientY - sy;
      setDelta(null);
      if (!moved) return;
      const css = pdfRectToCss(ctx.viewport, imgs[i].rect);
      const n = resize
        ? { x0: css.left, y0: css.top, x1: css.left + Math.max(8, css.width + dx), y1: css.top + Math.max(8, css.height + dy) }
        : { x0: css.left + dx, y0: css.top + dy, x1: css.left + dx + css.width, y1: css.top + dy + css.height };
      const newRect = cssRectToPdf(ctx.viewport, n.x0, n.y0, n.x1, n.y1);
      ws.apply("edit.image", { page: ctx.pageNumber, rect: imgs[i].rect, action: "transform", newRect }, resize ? "Resize image" : "Move image");
    };
    window.addEventListener("mousemove", mm);
    window.addEventListener("mouseup", mu);
  };

  return (
    <>
      {imgs.map((im, i) => {
        const css = pdfRectToCss(ctx.viewport, im.rect);
        const d = sel === i && delta ? delta : null;
        return (
          <div key={i} className={"ov-img" + (sel === i ? " sel" : "")} title="Drag to move, drag the corner to resize, Delete to remove"
            style={{ left: css.left + (d?.dx || 0), top: css.top + (d?.dy || 0), width: Math.max(8, css.width + (d?.dw || 0)), height: Math.max(8, css.height + (d?.dh || 0)) }}
            onMouseDown={(e) => begin(e, i, false)}>
            {sel === i && <div className="ov-handle" onMouseDown={(e) => begin(e, i, true)} />}
          </div>
        );
      })}
      {imgs.length === 0 && ctx.pageNumber === ws.page && (
        <div data-noprop className="notice info" style={{ position: "absolute", left: 10, top: 10, right: 10 }}>No directly editable images on this page.</div>
      )}
    </>
  );
}

// ------------------------------------------------------------ annotations
function AnnotBoxes({ ctx }: { ctx: PageCtx }) {
  const ws = useWs();
  const [list, setList] = useState<any[]>([]);
  useEffect(() => {
    let alive = true;
    cached(`${ws.sessionId}:${ws.revision}:annots:${ctx.pageNumber}`, () => ws.query("annot.list", { page: ctx.pageNumber }))
      .then((d: any) => alive && setList(d.annotations || []))
      .catch(() => alive && setList([]));
    return () => {
      alive = false;
    };
  }, [ws.revision, ctx.pageNumber]);
  return (
    <>
      {list.filter((a) => !a.irt).map((a) => {
        const css = pdfRectToCss(ctx.viewport, a.rect);
        const on = ws.selected?.kind === "annot" && ws.selected.id === a.id;
        return <div key={a.id} className={"ov-annot" + (on ? " sel" : "")} style={css} title={`${a.typeName}${a.contents ? ": " + a.contents : ""}`}
          onMouseDown={(e) => { e.stopPropagation(); ws.setSelected({ kind: "annot", ...a }); }} />;
      })}
    </>
  );
}

// ------------------------------------------------------------ redaction marks
function Marks({ ctx }: { ctx: PageCtx }) {
  const ws = useWs();
  return (
    <>
      {ws.marks.filter((m) => m.page === ctx.pageNumber).map((m) => {
        const css = pdfRectToCss(ctx.viewport, m.rect);
        return (
          <div key={m.id} className="ov-redact" style={css} title={m.text ? `${m.label || ""}: ${m.text}` : "Area marked for redaction"} data-noprop>
            {ws.mode === "redact" && (
              <button className="rm" title="Remove mark" onMouseDown={(e) => e.stopPropagation()} onClick={() => ws.setMarks((ms) => ms.filter((x) => x.id !== m.id))}>×</button>
            )}
          </div>
        );
      })}
    </>
  );
}

// ------------------------------------------------------------ form fields
function FieldBoxes({ ctx }: { ctx: PageCtx }) {
  const ws = useWs();
  const fields: any[] = ws.opts.fields || [];
  return (
    <>
      {fields.flatMap((f) =>
        f.widgets.filter((w: any) => w.page === ctx.pageNumber).map((w: any) => {
          const css = pdfRectToCss(ctx.viewport, w.rect);
          const on = ws.selected?.kind === "field" && ws.selected.name === f.name;
          return <div key={w.xref} className={"ov-field" + (on ? " on" : "")} style={css} title={f.label || f.name} data-noprop
            onMouseDown={(e) => { e.stopPropagation(); ws.setSelected({ kind: "field", name: f.name }); }} />;
        }),
      )}
    </>
  );
}

// ------------------------------------------------------------ prepare a form
/** Name the group a new radio button belongs to, and the value it stands for. */
function RadioEntry(props: { css: Drag; group: string; groups: string[]; onSubmit: (group: string, value: string) => void; onCancel: () => void }) {
  const [group, setGroup] = useState(props.group);
  const [value, setValue] = useState("");
  const left = Math.min(props.css.x0, props.css.x1);
  const top = Math.max(props.css.y0, props.css.y1) + 6;
  const submit = () => props.onSubmit(group.trim(), value.trim());
  return (
    <div className="edit-pop link-pop" data-noprop style={{ left, top, width: 280 }} onMouseDown={(e) => e.stopPropagation()}
      onKeyDown={(e) => { if (e.key === "Escape") props.onCancel(); if (e.key === "Enter") submit(); }}>
      <strong>Radio button</strong>
      <label className="subtle">Group name (buttons in one group switch each other off)</label>
      <input className="input sm" aria-label="Radio group name" list="radio-groups" placeholder="For example: GST scheme" value={group} onChange={(e) => setGroup(e.target.value)} autoFocus={!props.group} />
      <datalist id="radio-groups">{props.groups.map((g) => <option key={g} value={g} />)}</datalist>
      <label className="subtle">This button's choice</label>
      <input className="input sm" aria-label="Radio button choice" placeholder="For example: Composition" value={value} onChange={(e) => setValue(e.target.value)} autoFocus={!!props.group} />
      <div className="row" style={{ width: "100%", justifyContent: "flex-end" }}>
        <button className="btn sm" onClick={props.onCancel}><X size={13} /> Cancel</button>
        <button className="btn sm primary" onClick={submit}><Check size={13} /> Add button</button>
      </div>
    </div>
  );
}

/** Fields being prepared: click to select, drag to move, corner to resize, Delete to remove. */
function DesignBoxes({ ctx }: { ctx: PageCtx }) {
  const ws = useWs();
  const fields: any[] = ws.opts.designFields || [];
  const [delta, setDelta] = useState<{ xref: number; dx: number; dy: number; dw: number; dh: number } | null>(null);
  const sel = ws.selected?.kind === "dfield" ? ws.selected : null;
  useEffect(() => {
    if (!sel || sel.page !== ctx.pageNumber || !sel.xref) return;
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT")) return;
      if (e.key === "Delete") {
        e.preventDefault();
        ws.apply("forms.design", { ops: [{ op: "deleteWidget", page: ctx.pageNumber, xref: sel.xref }] }, "Delete field").then((r) => r && ws.setSelected(null));
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [sel?.xref, sel?.page, ctx.pageNumber]);

  const begin = (e: React.MouseEvent, f: any, w: any, resize: boolean) => {
    if (ws.tool !== "fieldSelect") return;
    e.stopPropagation();
    ws.setSelected({ kind: "dfield", name: f.name, xref: w.xref, page: w.page });
    const sx = e.clientX;
    const sy = e.clientY;
    let moved = false;
    const mm = (ev: MouseEvent) => {
      const dx = ev.clientX - sx;
      const dy = ev.clientY - sy;
      if (Math.abs(dx) + Math.abs(dy) > 2) moved = true;
      setDelta(resize ? { xref: w.xref, dx: 0, dy: 0, dw: dx, dh: dy } : { xref: w.xref, dx, dy, dw: 0, dh: 0 });
    };
    const mu = (ev: MouseEvent) => {
      window.removeEventListener("mousemove", mm);
      window.removeEventListener("mouseup", mu);
      setDelta(null);
      if (!moved) return;
      const dx = ev.clientX - sx;
      const dy = ev.clientY - sy;
      const css = pdfRectToCss(ctx.viewport, w.rect);
      const n = resize
        ? { x0: css.left, y0: css.top, x1: css.left + Math.max(6, css.width + dx), y1: css.top + Math.max(6, css.height + dy) }
        : { x0: css.left + dx, y0: css.top + dy, x1: css.left + dx + css.width, y1: css.top + dy + css.height };
      ws.apply("forms.design", { ops: [{ op: "move", page: ctx.pageNumber, xref: w.xref, rect: cssRectToPdf(ctx.viewport, n.x0, n.y0, n.x1, n.y1) }] }, resize ? "Resize field" : "Move field");
    };
    window.addEventListener("mousemove", mm);
    window.addEventListener("mouseup", mu);
  };

  return (
    <>
      {fields.flatMap((f) =>
        f.widgets.filter((w: any) => w.page === ctx.pageNumber).map((w: any) => {
          const css = pdfRectToCss(ctx.viewport, w.rect);
          const d = delta?.xref === w.xref ? delta : null;
          const on = sel?.name === f.name;
          return (
            <div key={w.xref} className={"ov-dfield" + (on ? " on" : "") + (sel?.xref === w.xref ? " sel" : "")} data-noprop data-field={f.name}
              title={`${f.name}${f.label ? " (" + f.label + ")" : ""}${f.type === "radio" ? " = " + w.onState : ""}`}
              style={{ left: css.left + (d?.dx || 0), top: css.top + (d?.dy || 0), width: Math.max(6, css.width + (d?.dw || 0)), height: Math.max(6, css.height + (d?.dh || 0)), cursor: ws.tool === "fieldSelect" ? "move" : undefined }}
              onMouseDown={(e) => begin(e, f, w, false)}>
              <span className="ov-dname">{f.type === "radio" ? `${f.name}: ${w.onState}` : f.name}</span>
              {sel?.xref === w.xref && <div className="ov-handle" onMouseDown={(e) => begin(e, f, w, true)} />}
            </div>
          );
        }),
      )}
    </>
  );
}

/** Fields suggested by Detect form fields, each to accept or discard. */
function SuggestionBoxes({ ctx }: { ctx: PageCtx }) {
  const ws = useWs();
  const all: any[] = ws.opts.suggestions || [];
  const drop = (s: any) => ws.setOpts({ suggestions: all.filter((x) => x !== s) });
  const accept = async (s: any) => {
    const r = await ws.apply("forms.design", { ops: [{ op: "add", type: s.type, page: s.page, rect: s.rect, name: s.name, tooltip: s.label || undefined }] }, "Add field");
    if (r) ws.setOpts({ suggestions: (ws.opts.suggestions || all).filter((x: any) => x !== s) });
  };
  return (
    <>
      {all.filter((s) => s.page === ctx.pageNumber).map((s, i) => {
        const css = pdfRectToCss(ctx.viewport, s.rect);
        // Small boxes (check boxes) have their label on the right: keep the buttons off it.
        return (
        <div key={i} className={"ov-suggest" + (css.width < 40 ? " narrow" : "")} data-noprop style={css} title={`Suggested ${s.type === "checkbox" ? "check box" : "text field"}: ${s.name}`}>
          <span className="ov-dname">{s.name}</span>
          <span className="ov-suggest-acts">
            <button title="Add this field" aria-label={`Add field ${s.name}`} onMouseDown={(e) => e.stopPropagation()} onClick={() => accept(s)}><Check size={11} /></button>
            <button title="Not a field" aria-label={`Discard field ${s.name}`} onMouseDown={(e) => e.stopPropagation()} onClick={() => drop(s)}><X size={11} /></button>
          </span>
        </div>
        );
      })}
    </>
  );
}

export function clearOverlayCache(sessionId: string) {
  for (const k of [...cache.keys()]) if (k.startsWith(sessionId + ":")) cache.delete(k);
}
