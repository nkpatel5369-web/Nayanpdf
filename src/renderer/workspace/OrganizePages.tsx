import React, { useEffect, useMemo, useState } from "react";
import { Copy, FilePlus2, FileOutput, Replace, RotateCcw, RotateCw, Square, Trash2, Undo2, ArrowDownUp, Check } from "lucide-react";
import { useWs } from "./context";
import { PageThumb } from "./Thumbnails";
import { api, baseName, dirName, joinPath, stem, waitForJob } from "../api";
import { useApp } from "../store";
import { openDocument, usePrompt } from "../components/common";
import { Modal, Notice } from "../components/ui";

interface Item {
  key: string;
  src: string; // "A" = this document
  page: number;
  rotate: number;
  blank?: boolean;
  w?: number;
  h?: number;
}

interface Source {
  path: string;
  password?: string;
  name: string;
  thumbs: Record<number, string>;
}

let keySeq = 1;
const k = () => `i${keySeq++}`;

export function OrganizePages() {
  const ws = useWs();
  const total = ws.viewer?.pageCount() || ws.doc.info?.pageCount || 0;
  const initial = useMemo(() => Array.from({ length: total }, (_, i) => ({ key: k(), src: "A", page: i + 1, rotate: 0 })), [ws.revision, total]);
  const [items, setItems] = useState<Item[]>(initial);
  const [sel, setSel] = useState<Set<string>>(new Set());
  const [anchor, setAnchor] = useState<string | null>(null);
  const [dragKey, setDragKey] = useState<string | null>(null);
  const [dropBefore, setDropBefore] = useState<string | null>(null);
  const [sources, setSources] = useState<Record<string, Source>>({});
  const [review, setReview] = useState(false);
  const [size, setSize] = useState(150);

  useEffect(() => {
    setItems(initial);
    setSel(new Set());
  }, [initial]);

  const changed = useMemo(() => items.length !== total || items.some((it, i) => it.src !== "A" || it.page !== i + 1 || it.rotate || it.blank), [items, total]);

  const click = (e: React.MouseEvent, it: Item) => {
    const s = new Set(e.ctrlKey || e.metaKey ? sel : []);
    if (e.shiftKey && anchor) {
      const a = items.findIndex((x) => x.key === anchor);
      const b = items.findIndex((x) => x.key === it.key);
      for (let i = Math.min(a, b); i <= Math.max(a, b); i++) s.add(items[i].key);
    } else if ((e.ctrlKey || e.metaKey) && sel.has(it.key)) {
      s.delete(it.key);
    } else {
      s.add(it.key);
      setAnchor(it.key);
    }
    setSel(s);
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement).tagName === "INPUT") return;
      if (e.ctrlKey && e.key.toLowerCase() === "a") {
        e.preventDefault();
        setSel(new Set(items.map((i) => i.key)));
      }
      if (e.key === "Delete" && sel.size) removeSel();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const selected = items.filter((i) => sel.has(i.key));
  const lastSelIndex = Math.max(-1, ...items.map((it, i) => (sel.has(it.key) ? i : -1)));

  const rotateSel = (d: number) => setItems(items.map((it) => (sel.has(it.key) ? { ...it, rotate: (it.rotate + d + 360) % 360 } : it)));
  const removeSel = () => {
    if (selected.length >= items.length) {
      useApp.getState().toast({ kind: "warning", title: "A document must keep at least one page" });
      return;
    }
    setItems(items.filter((it) => !sel.has(it.key)));
    setSel(new Set());
  };
  const dupSel = () => {
    const out: Item[] = [];
    for (const it of items) {
      out.push(it);
      if (sel.has(it.key)) out.push({ ...it, key: k() });
    }
    setItems(out);
  };
  const insertAt = (index: number, newItems: Item[]) => {
    const n = items.slice();
    n.splice(index, 0, ...newItems);
    setItems(n);
    setSel(new Set(newItems.map((x) => x.key)));
  };
  const addBlank = () => insertAt(lastSelIndex >= 0 ? lastSelIndex + 1 : items.length, [{ key: k(), src: "A", page: 0, rotate: 0, blank: true }]);
  const reverseSel = () => {
    const idx = items.map((it, i) => (sel.size === 0 || sel.has(it.key) ? i : -1)).filter((i) => i >= 0);
    const vals = idx.map((i) => items[i]).reverse();
    const n = items.slice();
    idx.forEach((i, j) => (n[i] = vals[j]));
    setItems(n);
  };

  const loadSource = async (): Promise<[string, Source, number] | null> => {
    const f = await api.openFiles("pdf", false, "Choose PDF to insert pages from");
    if (!f[0]) return null;
    let pw: string | undefined;
    let probe = await api.query("doc.probe", { path: f[0] });
    while (!probe.ok && probe.needsPassword) {
      const p = await usePrompt.getState().ask(baseName(f[0]), !!pw);
      if (p === null) return null;
      pw = p;
      probe = await api.query("doc.probe", { path: f[0], password: pw });
    }
    if (!probe.ok) {
      useApp.getState().toast({ kind: "error", title: "Cannot open file", body: probe.message });
      return null;
    }
    const id = String.fromCharCode(66 + Object.keys(sources).length); // B, C, D...
    const src: Source = { path: f[0], password: pw, name: baseName(f[0]), thumbs: {} };
    setSources((s) => ({ ...s, [id]: src }));
    // Thumbnails for inserted pages (rendered by the engine).
    (async () => {
      for (let p = 1; p <= Math.min(probe.pages, 400); p++) {
        try {
          const t = await api.query("pages.thumbnail", { path: f[0], page: p, width: 150, password: pw });
          setSources((s) => ({ ...s, [id]: { ...s[id], thumbs: { ...s[id].thumbs, [p]: t.png } } }));
        } catch {
          break;
        }
      }
    })();
    return [id, src, probe.pages];
  };

  const insertFromFile = async () => {
    const r = await loadSource();
    if (!r) return;
    const [id, , pages] = r;
    insertAt(lastSelIndex >= 0 ? lastSelIndex + 1 : items.length, Array.from({ length: pages }, (_, i) => ({ key: k(), src: id, page: i + 1, rotate: 0 })));
  };

  const replaceSel = async () => {
    if (!sel.size) return;
    const r = await loadSource();
    if (!r) return;
    const [id, , pages] = r;
    const idx = items.map((it, i) => (sel.has(it.key) ? i : -1)).filter((i) => i >= 0);
    const n = items.slice();
    idx.forEach((i, j) => {
      if (j < pages) n[i] = { key: k(), src: id, page: j + 1, rotate: 0 };
    });
    if (pages < idx.length) useApp.getState().toast({ kind: "warning", title: `The file has only ${pages} page(s); ${idx.length - pages} selected page(s) were kept.` });
    setItems(n);
  };

  const extractSel = async () => {
    const pages = selected.filter((s) => s.src === "A" && !s.blank).map((s) => s.page);
    if (!pages.length) return;
    const st = ws.doc.state;
    const base = st.savedPath || st.originalPath;
    const out = await api.saveFile(joinPath(dirName(base), `${stem(base)}_pages.pdf`), "pdf", "Save extracted pages as");
    if (!out) return;
    const j = await api.startJob("pages.extract", { sessionId: ws.sessionId, pages, output: out }, "Extract pages");
    const done = await waitForJob(j.id);
    if (done.status === "succeeded") useApp.getState().toast({ kind: "success", title: `Extracted ${pages.length} page(s)`, actions: [{ label: "Open", run: () => openDocument(done.result.output) }] });
    else useApp.getState().toast({ kind: "error", title: "Extract failed", body: done.error?.message });
  };

  const applyPlan = async () => {
    const plan = items.map((it) => (it.blank ? { blank: true } : { src: it.src, page: it.page, rotate: it.rotate }));
    const srcs: Record<string, any> = {};
    for (const [id, s] of Object.entries(sources)) if (items.some((i) => i.src === id)) srcs[id] = { path: s.path, password: s.password };
    const r = await ws.apply("pages.assemble", { plan, sources: srcs, keepBookmarks: true }, "Organize pages");
    if (r) {
      setReview(false);
      useApp.getState().toast({ kind: "success", title: `Pages reorganised (${r.pageCount} pages, order verified)` });
    }
  };

  const onDrop = (target: string | null) => {
    if (!dragKey) return;
    const moving = sel.has(dragKey) ? items.filter((i) => sel.has(i.key)) : items.filter((i) => i.key === dragKey);
    const rest = items.filter((i) => !moving.includes(i));
    let at = target ? rest.findIndex((i) => i.key === target) : rest.length;
    if (at < 0) at = rest.length;
    rest.splice(at, 0, ...moving);
    setItems(rest);
    setDragKey(null);
    setDropBefore(null);
  };

  const label = (it: Item) => (it.blank ? "Blank" : it.src === "A" ? `${it.page}` : `${it.src}:${it.page}`);

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
      <div className="ws-toolbar" style={{ borderTop: "1px solid var(--border)" }}>
        <button className="btn sm" disabled={!sel.size} onClick={() => rotateSel(-90)} title="Rotate left"><RotateCcw size={14} /></button>
        <button className="btn sm" disabled={!sel.size} onClick={() => rotateSel(90)} title="Rotate right"><RotateCw size={14} /></button>
        <button className="btn sm" disabled={!sel.size} onClick={removeSel} title="Delete (Del)"><Trash2 size={14} /> Delete</button>
        <button className="btn sm" disabled={!sel.size} onClick={dupSel}><Copy size={14} /> Duplicate</button>
        <button className="btn sm" onClick={addBlank}><Square size={14} /> Blank page</button>
        <button className="btn sm" onClick={insertFromFile}><FilePlus2 size={14} /> Insert from file</button>
        <button className="btn sm" disabled={!sel.size} onClick={replaceSel}><Replace size={14} /> Replace</button>
        <button className="btn sm" onClick={reverseSel} title="Reverse order (selection or all)"><ArrowDownUp size={14} /> Reverse</button>
        <button className="btn sm" disabled={!selected.some((s) => s.src === "A")} onClick={extractSel}><FileOutput size={14} /> Extract to new PDF</button>
        <div className="sep" />
        <button className="btn sm ghost" onClick={() => setSel(new Set(items.filter((_, i) => i % 2 === 0).map((i) => i.key)))}>Select odd</button>
        <button className="btn sm ghost" onClick={() => setSel(new Set(items.filter((_, i) => i % 2 === 1).map((i) => i.key)))}>Select even</button>
        <div className="grow" />
        <input type="range" min={100} max={260} value={size} onChange={(e) => setSize(parseInt(e.target.value))} title="Thumbnail size" />
        <button className="btn sm" disabled={!changed} onClick={() => { setItems(initial); setSel(new Set()); }}><Undo2 size={14} /> Reset</button>
        <button className="btn sm primary" disabled={!changed} onClick={() => setReview(true)}><Check size={14} /> Apply changes</button>
      </div>
      <div style={{ overflow: "auto", flex: 1, background: "var(--viewer-bg)" }} onDragOver={(e) => e.preventDefault()} onDrop={() => onDrop(null)}
        onClick={(e) => e.target === e.currentTarget && setSel(new Set())}>
        <div className="org-grid" style={{ gridTemplateColumns: `repeat(auto-fill, minmax(${size}px, 1fr))` }}>
          {items.map((it) => (
            <div key={it.key} className={"org-item" + (sel.has(it.key) ? " sel" : "") + (dropBefore === it.key ? " drop-before" : "")}
              draggable onDragStart={() => setDragKey(it.key)}
              onDragOver={(e) => { e.preventDefault(); setDropBefore(it.key); }}
              onDragLeave={() => setDropBefore((d) => (d === it.key ? null : d))}
              onDrop={(e) => { e.preventDefault(); e.stopPropagation(); onDrop(it.key); }}
              onClick={(e) => click(e, it)} onDoubleClick={() => it.src === "A" && !it.blank && ws.viewer?.goTo(it.page)}>
              {it.blank ? (
                <div className="org-blank" style={{ width: size - 20, height: (size - 20) * 1.414 }}>Blank page</div>
              ) : it.src === "A" ? (
                <PageThumb pdf={ws.pdf} page={it.page} width={size - 20} extraRotate={it.rotate} />
              ) : sources[it.src]?.thumbs[it.page] ? (
                <img src={"data:image/png;base64," + sources[it.src].thumbs[it.page]} style={{ width: size - 20, transform: `rotate(${it.rotate}deg)`, background: "#fff", boxShadow: "var(--shadow)" }} />
              ) : (
                <div className="org-blank" style={{ width: size - 20, height: (size - 20) * 1.414 }}>{sources[it.src]?.name}</div>
              )}
              <div className="lbl">
                {label(it)}
                {it.rotate ? <span className="badge">{it.rotate}°</span> : null}
                {it.src !== "A" && !it.blank && <span className="badge info" title={sources[it.src]?.path}>{sources[it.src]?.name.slice(0, 14)}</span>}
              </div>
            </div>
          ))}
        </div>
      </div>
      <div className="status-bar" style={{ position: "static" }}>
        <span>{items.length} page(s) in new order</span>
        <span>{sel.size} selected</span>
        <span>Click to select · Ctrl/Shift for multiple · drag to reorder · Del to delete</span>
      </div>
      {review && (
        <Modal title="Apply new page order?" onClose={() => setReview(false)} footer={
          <><button className="btn" onClick={() => setReview(false)}>Back</button><button className="btn primary" onClick={applyPlan}>Apply</button></>
        }>
          <p className="muted">The document will contain {items.length} page(s) in this sequence:</p>
          <div className="mono" style={{ maxHeight: 180, overflow: "auto", background: "var(--surface-2)", padding: 10, borderRadius: 8, wordBreak: "break-word" }}>
            {items.map((it) => label(it) + (it.rotate ? `(${it.rotate}°)` : "")).join(", ")}
          </div>
          {Object.keys(sources).length > 0 && (
            <div className="subtle" style={{ marginTop: 6 }}>{Object.entries(sources).map(([id, s]) => `${id} = ${s.name}`).join(" · ")}</div>
          )}
          <Notice kind="info">After processing NayanPDF re-reads the result and verifies the page count and order. You can undo before saving.</Notice>
        </Modal>
      )}
    </div>
  );
}
