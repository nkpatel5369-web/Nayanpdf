// Acrobat-style text selection: drag across text to select it, then use the
// toolbar that appears (copy, highlight, underline, strike through, squiggly,
// add a note, mark for redaction) or right-click for the same actions. With a
// Comment markup tool active, dragging across text marks it directly.
import React, { useEffect, useRef, useState } from "react";
import {
  Copy, Highlighter, MessageSquarePlus, MoveHorizontal, Printer, RotateCw, Shredder, Strikethrough, TextSelect, Underline, Waves, ZoomIn, ZoomOut,
} from "lucide-react";
import { useApp } from "../store";
import { useWs } from "./context";
import { cssRectToPdf } from "./Viewer";

export const MARKUP_TOOLS = new Set(["highlight", "underline", "strikeout", "squiggly"]);
/** Comment tools that work on the text selection instead of drawing on the page. */
export const TEXT_TOOLS = new Set(["select", ...MARKUP_TOOLS]);

type Markup = "highlight" | "underline" | "strikeout" | "squiggly";
const LABEL: Record<Markup, string> = { highlight: "Highlight", underline: "Underline", strikeout: "Strikethrough", squiggly: "Squiggly underline" };

interface PageRects {
  page: number;
  rects: number[][]; // PDF user space
}
interface SelInfo {
  text: string;
  pages: PageRects[];
  x: number; // toolbar anchor, relative to the viewer wrapper
  y: number;
}

/** Client rectangles of the selected text only (text nodes, not layout helpers). */
function textRects(range: Range): DOMRect[] {
  const root = range.commonAncestorContainer;
  const scope = root.nodeType === Node.TEXT_NODE ? root.parentNode! : root;
  const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT);
  const out: DOMRect[] = [];
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (!range.intersectsNode(n)) continue;
    const t = n as Text;
    const r = document.createRange();
    r.selectNodeContents(t);
    if (t === range.startContainer) r.setStart(t, range.startOffset);
    if (t === range.endContainer) r.setEnd(t, range.endOffset);
    for (const cr of Array.from(r.getClientRects())) if (cr.width >= 1 && cr.height >= 1) out.push(cr);
  }
  return out;
}

interface Caret {
  node: Text;
  offset: number;
}

/** Text position nearest to a point inside a page's text layer (same line first). */
function nearestCaret(layer: HTMLElement, x: number, y: number): Caret | null {
  let best: { span: HTMLElement; r: DOMRect; score: number } | null = null;
  for (const span of Array.from(layer.querySelectorAll<HTMLElement>("span:not(.markedContent)"))) {
    const t = span.firstChild;
    if (!t || t.nodeType !== Node.TEXT_NODE || !(t as Text).data.trim()) continue;
    const r = span.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) continue;
    const dy = y < r.top ? r.top - y : y > r.bottom ? y - r.bottom : 0;
    const dx = x < r.left ? r.left - x : x > r.right ? x - r.right : 0;
    const score = dy * 1000 + dx; // the line under the pointer wins over horizontal distance
    if (!best || score < best.score) best = { span, r, score };
  }
  if (!best) return null;
  const node = best.span.firstChild as Text;
  if (x <= best.r.left) return { node, offset: 0 };
  if (x >= best.r.right) return { node, offset: node.data.length };
  const cr = (document as any).caretRangeFromPoint?.(x, best.r.top + best.r.height / 2) as Range | null;
  if (cr && cr.startContainer === node) return { node, offset: cr.startOffset };
  return { node, offset: Math.round(((x - best.r.left) / best.r.width) * node.data.length) };
}

/** Text position under or nearest to a point anywhere over the pages. */
function caretAt(container: HTMLElement, x: number, y: number): Caret | null {
  const cr = (document as any).caretRangeFromPoint?.(x, y) as Range | null;
  if (cr && cr.startContainer.nodeType === Node.TEXT_NODE && (cr.startContainer.parentElement?.closest(".textLayer"))) {
    return { node: cr.startContainer as Text, offset: cr.startOffset };
  }
  const page = (document.elementsFromPoint(x, y).find((e) => e.classList?.contains("page")) as HTMLElement | undefined)
    || (Array.from(container.querySelectorAll<HTMLElement>(".page[data-page-number]")).find((p) => { const r = p.getBoundingClientRect(); return y >= r.top && y <= r.bottom; }));
  const layer = page?.querySelector<HTMLElement>(".textLayer");
  return layer ? nearestCaret(layer, x, y) : null;
}

function readSelection(wrap: HTMLElement, viewport: (page: number) => any): SelInfo | null {
  const s = window.getSelection();
  if (!s || s.isCollapsed || s.rangeCount === 0) return null;
  const container = wrap.querySelector(".viewer-container");
  const range = s.getRangeAt(0);
  if (!container || !container.contains(range.commonAncestorContainer)) return null;
  const text = s.toString();
  if (!text.trim()) return null;
  const layers = Array.from(container.querySelectorAll<HTMLElement>(".page[data-page-number] .textLayer")).map((l) => ({
    page: Number(l.closest(".page")!.getAttribute("data-page-number")),
    box: l.getBoundingClientRect(),
  }));
  const byPage = new Map<number, number[][]>();
  let last: DOMRect | null = null;
  for (const cr of textRects(range)) {
    const cx = cr.left + cr.width / 2;
    const cy = cr.top + cr.height / 2;
    const l = layers.find((x) => cx >= x.box.left && cx <= x.box.right && cy >= x.box.top && cy <= x.box.bottom);
    const vp = l && viewport(l.page);
    if (!l || !vp) continue;
    const r = cssRectToPdf(vp, cr.left - l.box.left, cr.top - l.box.top, cr.right - l.box.left, cr.bottom - l.box.top);
    byPage.set(l.page, [...(byPage.get(l.page) || []), r]);
    last = cr;
  }
  if (!byPage.size || !last) return null;
  const w = wrap.getBoundingClientRect();
  return { text, pages: [...byPage].map(([page, rects]) => ({ page, rects })), x: last.right - w.left, y: last.bottom - w.top };
}

/** One box per text line (for redaction marks the user then reviews). */
function lineBoxes(rects: number[][]): number[][] {
  const lines: number[][] = [];
  for (const r of [...rects].sort((a, b) => b[3] - a[3] || a[0] - b[0])) {
    const h = r[3] - r[1];
    const hit = lines.find((l) => Math.min(l[3], r[3]) - Math.max(l[1], r[1]) > 0.5 * Math.min(h, l[3] - l[1]));
    if (hit) {
      hit[0] = Math.min(hit[0], r[0]);
      hit[1] = Math.min(hit[1], r[1]);
      hit[2] = Math.max(hit[2], r[2]);
      hit[3] = Math.max(hit[3], r[3]);
    } else lines.push([...r]);
  }
  return lines;
}

export function SelectionTools(props: { wrapRef: React.RefObject<HTMLDivElement | null>; onPrint: () => void }) {
  const ws = useWs();
  const toast = useApp((s) => s.toast);
  const [sel, setSel] = useState<SelInfo | null>(null);
  const [menu, setMenu] = useState<{ x: number; y: number; page: number | null; sel: SelInfo | null } | null>(null);
  const [note, setNote] = useState<{ sel: SelInfo; text: string } | null>(null);
  const wsRef = useRef(ws);
  wsRef.current = ws;

  const viewport = (page: number) => wsRef.current.viewer?.pageViewport(page);
  const markupTool = ws.mode === "comment" && MARKUP_TOOLS.has(ws.tool) ? (ws.tool as Markup) : null;
  // Selection actions are offered where text is not being edited or drawn on.
  const selectable = ws.mode === "view" || (ws.mode === "comment" && TEXT_TOOLS.has(ws.tool));

  const clear = () => {
    window.getSelection()?.removeAllRanges();
    setSel(null);
  };

  const mark = async (kind: Markup, info: SelInfo, contents?: string) => {
    const w = wsRef.current;
    const color = w.mode === "comment" && w.tool === kind ? w.opts.color : undefined;
    const opacity = w.mode === "comment" && w.tool === kind ? w.opts.opacity : undefined;
    clear();
    setMenu(null);
    const ops = info.pages.map((p) => ({ op: "add", page: p.page, kind, quads: p.rects, color, opacity, contents }));
    await w.apply("annot.apply", { ops, author: useApp.getState().settings.reviewerName || undefined }, contents ? "Note on text" : LABEL[kind]);
  };

  const copy = async (info: SelInfo) => {
    let ok = false;
    try {
      ok = document.execCommand("copy");
    } catch {
      ok = false;
    }
    if (!ok) {
      try {
        await navigator.clipboard.writeText(info.text);
        ok = true;
      } catch {
        ok = false;
      }
    }
    setMenu(null);
    toast(ok ? { kind: "success", title: "Copied", body: info.text.length > 80 ? info.text.slice(0, 80) + "…" : info.text } : { kind: "error", title: "Could not copy the text" });
  };

  const redact = (info: SelInfo) => {
    const w = wsRef.current;
    const marks = info.pages.flatMap((p) => lineBoxes(p.rects).map((rect) => ({ id: Math.random().toString(36).slice(2), page: p.page, rect, text: info.text.slice(0, 60), label: "Selected text" })));
    w.setMarks((m) => [...m, ...marks]);
    clear();
    setMenu(null);
    if (w.mode !== "redact") window.dispatchEvent(new CustomEvent("nayan:set-mode", { detail: { sessionId: w.sessionId, mode: "redact" } }));
    toast({ kind: "info", title: `${marks.length} area(s) marked for redaction`, body: "Review the marks, then choose Apply redactions. Nothing is removed until you apply." });
  };

  const selectPage = (page: number) => {
    const wrap = props.wrapRef.current;
    const layer = wrap?.querySelector<HTMLElement>(`.page[data-page-number="${page}"] .textLayer`);
    if (!layer || !layer.textContent?.trim()) {
      toast({ kind: "warning", title: "No selectable text on this page", body: "Scanned pages need OCR first (More tools > OCR)." });
      return;
    }
    const r = document.createRange();
    r.selectNodeContents(layer);
    const s = window.getSelection();
    s?.removeAllRanges();
    s?.addRange(r);
    setTimeout(() => setSel(readSelection(wrap!, viewport)), 0);
  };

  // Mouse-up after a drag: show the toolbar, or mark straight away with a markup tool.
  useEffect(() => {
    const wrap = props.wrapRef.current;
    const container = wrap?.querySelector(".viewer-container");
    if (!wrap || !container) return;
    const finish = () => {
      setTimeout(() => {
        if (!selectable) return;
        const info = readSelection(wrap, viewport);
        if (!info) {
          setSel(null);
          return;
        }
        if (markupTool) void mark(markupTool, info);
        else setSel(info);
      }, 0);
    };
    let customDrag = false;
    const onUp = (e: Event) => {
      if ((e as MouseEvent).button !== 0 || customDrag) return;
      finish();
    };
    // A drag that starts beside the text (margin, gap between words or lines)
    // has no native text anchor, so the selection is driven from the nearest
    // characters instead, as in Acrobat.
    const onDown = (e: Event) => {
      const me = e as MouseEvent;
      if (me.button !== 0 || !selectable || me.detail > 1) return;
      const t = me.target as HTMLElement;
      if (!t.closest?.(".page") || t.closest(".textLayer span") || t.closest(".overlay-host .ov-annot")) return;
      // Links and form fields keep their clicks; text that is already highlighted,
      // underlined or struck through can still be selected again.
      const sec = t.closest(".annotationLayer section");
      if (sec && !/(highlight|underline|squiggly|strikeout)Annotation/i.test(sec.className)) return;
      const anchor = caretAt(container as HTMLElement, me.clientX, me.clientY);
      if (!anchor) return;
      me.preventDefault();
      customDrag = true;
      const s0 = window.getSelection();
      s0?.removeAllRanges();
      let moved = false;
      const move = (ev: MouseEvent) => {
        const f = caretAt(container as HTMLElement, ev.clientX, ev.clientY);
        if (!f) return;
        moved = true;
        window.getSelection()?.setBaseAndExtent(anchor.node, anchor.offset, f.node, f.offset);
      };
      const up = () => {
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
        customDrag = false;
        if (moved) finish();
        else setSel(null);
      };
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    };
    const onScroll = () => {
      setSel(null);
      setMenu(null);
    };
    const onContext = (e: Event) => {
      const me = e as MouseEvent;
      e.preventDefault();
      const w = wrap.getBoundingClientRect();
      const pageEl = (me.target as HTMLElement).closest?.(".page[data-page-number]");
      const info = selectable ? readSelection(wrap, viewport) : null;
      setSel(null);
      setMenu({ x: me.clientX - w.left, y: me.clientY - w.top, page: pageEl ? Number(pageEl.getAttribute("data-page-number")) : null, sel: info });
    };
    container.addEventListener("mousedown", onDown);
    container.addEventListener("mouseup", onUp);
    container.addEventListener("scroll", onScroll);
    container.addEventListener("contextmenu", onContext);
    return () => {
      container.removeEventListener("mousedown", onDown);
      container.removeEventListener("mouseup", onUp);
      container.removeEventListener("scroll", onScroll);
      container.removeEventListener("contextmenu", onContext);
    };
  }, [props.wrapRef.current, selectable, markupTool]);

  // Ctrl+A from the workspace: select all text on the current page.
  useEffect(() => {
    const h = (e: Event) => {
      const d = (e as CustomEvent).detail;
      if (d?.sessionId === wsRef.current.sessionId) selectPage(d.page);
    };
    window.addEventListener("nayan:select-all", h);
    return () => window.removeEventListener("nayan:select-all", h);
  }, []);

  // Hide the toolbar when the selection goes away; close the menu on outside click or Escape.
  useEffect(() => {
    const onSelChange = () => {
      const s = window.getSelection();
      if (!s || s.isCollapsed) setSel(null);
    };
    const onDown = (e: MouseEvent) => {
      if (!(e.target as HTMLElement).closest?.(".sel-menu, .sel-bar")) setMenu(null);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setMenu(null);
        setSel(null);
      }
    };
    document.addEventListener("selectionchange", onSelChange);
    window.addEventListener("mousedown", onDown, true);
    window.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("selectionchange", onSelChange);
      window.removeEventListener("mousedown", onDown, true);
      window.removeEventListener("keydown", onKey);
    };
  }, []);

  // A new revision re-renders the pages; an old selection no longer applies.
  useEffect(() => {
    setSel(null);
    setMenu(null);
  }, [ws.revision, ws.mode]);

  const wrapW = props.wrapRef.current?.clientWidth || 800;
  const wrapH = props.wrapRef.current?.clientHeight || 600;
  const clampX = (x: number, w: number) => Math.max(8, Math.min(x, wrapW - w - 8));
  const clampY = (y: number, h: number) => Math.max(8, Math.min(y, wrapH - h - 8));

  const actions = (info: SelInfo) => [
    { id: "copy", label: "Copy", icon: Copy, run: () => copy(info), hint: "Ctrl+C" },
    { id: "highlight", label: "Highlight", icon: Highlighter, run: () => mark("highlight", info) },
    { id: "underline", label: "Underline", icon: Underline, run: () => mark("underline", info) },
    { id: "strikeout", label: "Strikethrough", icon: Strikethrough, run: () => mark("strikeout", info) },
    { id: "squiggly", label: "Squiggly underline", icon: Waves, run: () => mark("squiggly", info) },
    { id: "note", label: "Add note to text", icon: MessageSquarePlus, run: () => { setMenu(null); setSel(null); setNote({ sel: info, text: "" }); } },
    { id: "redact", label: "Mark for redaction", icon: Shredder, run: () => redact(info) },
  ];

  return (
    <>
      {sel && !menu && !note && (
        <div className="sel-bar" style={{ left: clampX(sel.x - 140, 300), top: clampY(sel.y + 8, 40) }} onMouseDown={(e) => e.preventDefault()}>
          {actions(sel).map((a) => (
            <button key={a.id} className="icon-btn" title={a.label + (a.hint ? ` (${a.hint})` : "")} aria-label={a.label} onClick={a.run}>
              <a.icon size={16} />
            </button>
          ))}
        </div>
      )}
      {menu && (
        <div className="sel-menu" style={{ left: clampX(menu.x, 230), top: clampY(menu.y, menu.sel ? 360 : 230) }} onMouseDown={(e) => e.preventDefault()} role="menu">
          {menu.sel && actions(menu.sel).map((a) => (
            <button key={a.id} role="menuitem" onClick={a.run}><a.icon size={15} /> {a.label}{a.hint && <span className="kbd">{a.hint}</span>}</button>
          ))}
          {menu.sel && <div className="sep" />}
          {menu.page && (
            <button role="menuitem" onClick={() => { const p = menu.page!; setMenu(null); selectPage(p); }}><TextSelect size={15} /> Select all text on this page</button>
          )}
          <button role="menuitem" onClick={() => { setMenu(null); ws.viewer?.zoomIn(); }}><ZoomIn size={15} /> Zoom in<span className="kbd">Ctrl++</span></button>
          <button role="menuitem" onClick={() => { setMenu(null); ws.viewer?.zoomOut(); }}><ZoomOut size={15} /> Zoom out<span className="kbd">Ctrl+-</span></button>
          <button role="menuitem" onClick={() => { setMenu(null); ws.viewer?.setScale("page-width"); }}><MoveHorizontal size={15} /> Fit width<span className="kbd">Ctrl+0</span></button>
          <button role="menuitem" onClick={() => { setMenu(null); ws.viewer?.rotate(90); }}><RotateCw size={15} /> Rotate view</button>
          <div className="sep" />
          <button role="menuitem" onClick={() => { setMenu(null); props.onPrint(); }}><Printer size={15} /> Print...<span className="kbd">Ctrl+P</span></button>
        </div>
      )}
      {note && (
        <div className="sel-menu note-pop" style={{ left: clampX(note.sel.x - 150, 300), top: clampY(note.sel.y + 8, 170) }}>
          <div className="label" style={{ padding: "4px 6px 0" }}>Note on: “{note.sel.text.slice(0, 40)}{note.sel.text.length > 40 ? "…" : ""}”</div>
          <textarea className="input" autoFocus rows={3} value={note.text} onChange={(e) => setNote({ ...note, text: e.target.value })}
            onKeyDown={(e) => { if (e.key === "Enter" && e.ctrlKey && note.text.trim()) { const n = note; setNote(null); void mark("highlight", n.sel, n.text.trim()); } if (e.key === "Escape") setNote(null); }} />
          <div className="row" style={{ justifyContent: "flex-end", gap: 6, padding: 6 }}>
            <button className="btn sm" onClick={() => setNote(null)}>Cancel</button>
            <button className="btn sm primary" disabled={!note.text.trim()} onClick={() => { const n = note; setNote(null); void mark("highlight", n.sel, n.text.trim()); }}>Add note</button>
          </div>
        </div>
      )}
    </>
  );
}
