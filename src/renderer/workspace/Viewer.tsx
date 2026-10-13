import React, { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import { createPortal } from "react-dom";
import * as pdfjsLib from "pdfjs-dist";
import { EventBus, PDFFindController, PDFLinkService, PDFViewer } from "pdfjs-dist/web/pdf_viewer.mjs";

const base = new URL("./pdfjs/", window.location.href).href;
pdfjsLib.GlobalWorkerOptions.workerSrc = base + "pdf.worker.min.mjs";

export const PDF_OPTS = {
  cMapUrl: base + "cmaps/",
  cMapPacked: true,
  standardFontDataUrl: base + "standard_fonts/",
  wasmUrl: base + "wasm/",
  iccUrl: base + "iccs/",
  isEvalSupported: false,
  enableXfa: false,
  disableAutoFetch: true,
  rangeChunkSize: 262144,
};

export interface PageCtx {
  pageNumber: number;
  viewport: any; // PageViewport (CSS pixels)
  width: number;
  height: number;
}

export interface ViewerHandle {
  goTo(page: number): void;
  zoomIn(): void;
  zoomOut(): void;
  setScale(v: string | number): void;
  rotate(delta: number): void;
  find(query: string, opts: { caseSensitive?: boolean; entireWord?: boolean; previous?: boolean; again?: boolean }): void;
  clearFind(): void;
  pdf(): any;
  pageCount(): number;
  rotation(): number;
  currentPage(): number;
}

interface Props {
  url: string;
  password?: string;
  onPassword?: (retry: boolean) => Promise<string | null>;
  onLoaded?: (pdf: any) => void;
  onPageChange?: (page: number) => void;
  onScaleChange?: (scale: number, value: string) => void;
  onFindCount?: (current: number, total: number) => void;
  onError?: (msg: string) => void;
  renderOverlay?: (ctx: PageCtx) => React.ReactNode;
  overlayVersion?: any;
  overlayInteractive?: boolean;
}

export const Viewer = forwardRef<ViewerHandle, Props>(function Viewer(props, ref) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewerDivRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<any>(null);
  const busRef = useRef<any>(null);
  const findRef = useRef<any>(null);
  const linkRef = useRef<any>(null);
  const pdfRef = useRef<any>(null);
  const hostsRef = useRef(new Map<number, HTMLDivElement>());
  const keepRef = useRef<{ page: number; scale: string } | null>(null);
  const [pagesVersion, setPagesVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const lastQuery = useRef<any>(null);

  const ensureHosts = () => {
    const v = viewerRef.current;
    if (!v || !pdfRef.current) return;
    const n = v.pagesCount;
    for (let i = 1; i <= n; i++) {
      const pv = v.getPageView(i - 1);
      if (!pv?.div) continue;
      let host = hostsRef.current.get(i);
      if (!host) {
        host = document.createElement("div");
        host.className = "overlay-host";
        host.style.position = "absolute";
        host.style.inset = "0";
        host.style.zIndex = "20";
        hostsRef.current.set(i, host);
      }
      if (host.parentElement !== pv.div) pv.div.appendChild(host);
    }
  };

  useEffect(() => {
    const eventBus = new EventBus();
    const linkService = new PDFLinkService({ eventBus });
    const findController = new PDFFindController({ eventBus, linkService });
    const viewer = new PDFViewer({
      container: containerRef.current!,
      viewer: viewerDivRef.current!,
      eventBus,
      linkService,
      findController,
      textLayerMode: 1,
      annotationMode: pdfjsLib.AnnotationMode.ENABLE,
      removePageBorders: false,
      maxCanvasPixels: 2 ** 25,
    } as any);
    linkService.setViewer(viewer);
    viewerRef.current = viewer;
    busRef.current = eventBus;
    findRef.current = findController;
    linkRef.current = linkService;
    // Links to web pages are not followed (offline application).
    (linkService as any).externalLinkEnabled = false;
    eventBus.on("pagesinit", () => {
      const keep = keepRef.current;
      viewer.currentScaleValue = keep?.scale || "page-width";
      if (keep?.page) viewer.currentPageNumber = Math.min(keep.page, viewer.pagesCount);
      ensureHosts();
      setPagesVersion((x) => x + 1);
    });
    const bump = () => {
      ensureHosts();
      setPagesVersion((x) => x + 1);
    };
    eventBus.on("pagerendered", bump);
    eventBus.on("pagerender", () => ensureHosts());
    eventBus.on("rotationchanging", bump);
    eventBus.on("scalechanging", (e: any) => {
      props.onScaleChange?.(e.scale, e.presetValue || String(e.scale));
      setTimeout(bump, 0);
    });
    eventBus.on("pagechanging", (e: any) => props.onPageChange?.(e.pageNumber));
    eventBus.on("updatefindmatchescount", (e: any) => props.onFindCount?.(e.matchesCount.current, e.matchesCount.total));
    eventBus.on("updatefindcontrolstate", (e: any) => e.matchesCount && props.onFindCount?.(e.matchesCount.current, e.matchesCount.total));
    return () => {
      viewer.cleanup?.();
      pdfRef.current?.destroy?.();
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const v = viewerRef.current;
    if (!v) return;
    if (pdfRef.current) keepRef.current = { page: v.currentPageNumber, scale: v.currentScaleValue };
    setLoading(true);
    const task = pdfjsLib.getDocument({ url: props.url, password: props.password, ...PDF_OPTS } as any);
    task.onPassword = async (cb: (pw: string) => void, reason: number) => {
      const pw = props.onPassword ? await props.onPassword(reason === 2) : null;
      if (pw === null) {
        task.destroy();
        return;
      }
      cb(pw);
    };
    task.promise
      .then((pdf: any) => {
        if (cancelled) {
          pdf.destroy();
          return;
        }
        const old = pdfRef.current;
        pdfRef.current = pdf;
        hostsRef.current.forEach((h) => h.remove());
        hostsRef.current.clear();
        v.setDocument(pdf);
        linkRef.current.setDocument(pdf, null);
        if (lastQuery.current) setTimeout(() => busRef.current.dispatch("find", { ...lastQuery.current, type: "again" }), 300);
        setLoading(false);
        props.onLoaded?.(pdf);
        if (old) setTimeout(() => old.destroy(), 0);
      })
      .catch((e: any) => {
        if (cancelled) return;
        setLoading(false);
        props.onError?.(e?.message || String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [props.url]);

  useImperativeHandle(ref, () => ({
    goTo: (page) => {
      const v = viewerRef.current;
      if (v && page >= 1 && page <= v.pagesCount) v.currentPageNumber = page;
    },
    zoomIn: () => {
      const v = viewerRef.current;
      v.currentScale = Math.min(6, v.currentScale * 1.15);
    },
    zoomOut: () => {
      const v = viewerRef.current;
      v.currentScale = Math.max(0.15, v.currentScale / 1.15);
    },
    setScale: (val) => {
      const v = viewerRef.current;
      if (typeof val === "number") v.currentScale = val;
      else v.currentScaleValue = val;
    },
    rotate: (delta) => {
      const v = viewerRef.current;
      v.pagesRotation = (v.pagesRotation + delta + 360) % 360;
    },
    find: (query, o) => {
      const q = { source: null, type: o.again ? "again" : "", query, caseSensitive: !!o.caseSensitive, entireWord: !!o.entireWord, highlightAll: true, findPrevious: !!o.previous, matchDiacritics: false };
      lastQuery.current = q;
      busRef.current.dispatch("find", q);
    },
    clearFind: () => {
      lastQuery.current = null;
      busRef.current.dispatch("findbarclose", { source: null });
    },
    pdf: () => pdfRef.current,
    pageCount: () => viewerRef.current?.pagesCount || 0,
    rotation: () => viewerRef.current?.pagesRotation || 0,
    currentPage: () => viewerRef.current?.currentPageNumber || 1,
  }));

  const portals: React.ReactNode[] = [];
  if (props.renderOverlay && pdfRef.current && viewerRef.current) {
    const v = viewerRef.current;
    for (const [n, host] of hostsRef.current) {
      const pv = v.getPageView(n - 1);
      if (!pv || !host.isConnected) continue;
      // Only pages near the visible area get interactive overlays.
      const cur = v.currentPageNumber;
      if (Math.abs(n - cur) > 3) continue;
      host.style.pointerEvents = props.overlayInteractive ? "auto" : "none";
      portals.push(createPortal(props.renderOverlay({ pageNumber: n, viewport: pv.viewport, width: pv.viewport.width, height: pv.viewport.height }), host, `ov-${n}`));
    }
  }
  void pagesVersion;
  void props.overlayVersion;

  return (
    <div className="viewer-wrap" style={{ height: "100%" }}>
      <div ref={containerRef} className="viewer-container" tabIndex={0}>
        <div ref={viewerDivRef} className="pdfViewer" />
      </div>
      {portals}
      {loading && (
        <div className="busy-veil" style={{ background: "transparent", pointerEvents: "none" }}>
          <div className="card"><span className="spin" style={{ display: "inline-block", width: 14, height: 14, border: "2px solid var(--accent)", borderTopColor: "transparent", borderRadius: "50%" }} /> Loading document...</div>
        </div>
      )}
    </div>
  );
});

/** Convert a drag rectangle in overlay (CSS px) to a PDF user-space rect. */
export function cssRectToPdf(viewport: any, x0: number, y0: number, x1: number, y1: number): number[] {
  const [a, b] = viewport.convertToPdfPoint(x0, y0);
  const [c, d] = viewport.convertToPdfPoint(x1, y1);
  return [Math.min(a, c), Math.min(b, d), Math.max(a, c), Math.max(b, d)];
}

export function pdfRectToCss(viewport: any, r: number[]): { left: number; top: number; width: number; height: number } {
  const [x0, y0, x1, y1] = viewport.convertToViewportRectangle(r);
  return { left: Math.min(x0, x1), top: Math.min(y0, y1), width: Math.abs(x1 - x0), height: Math.abs(y1 - y0) };
}

export function cssPointToPdf(viewport: any, x: number, y: number): number[] {
  return viewport.convertToPdfPoint(x, y);
}
