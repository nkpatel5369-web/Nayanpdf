import React, { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import { createPortal } from "react-dom";
import * as pdfjsLib from "pdfjs-dist";
import { EventBus, PDFFindController, PDFLinkService, PDFViewer, ScrollMode, SpreadMode } from "pdfjs-dist/web/pdf_viewer.mjs";

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

/** Acrobat's View > Page Display options. */
export type PageLayout = "single" | "continuous" | "two" | "twoContinuous";
export interface DisplayMode { layout: PageLayout; cover: boolean }

/** What the PDF asks for when it is opened (Acrobat honours these). */
export interface InitialView { pageMode: string | null; pageLayout: string | null }

/** Map the PDF's /PageLayout to Acrobat's page display. */
export function layoutFromPdf(v: string | null): DisplayMode | null {
  switch (v) {
    case "SinglePage": return { layout: "single", cover: false };
    case "OneColumn": return { layout: "continuous", cover: false };
    case "TwoColumnLeft": return { layout: "twoContinuous", cover: false };
    case "TwoColumnRight": return { layout: "twoContinuous", cover: true };
    case "TwoPageLeft": return { layout: "two", cover: false };
    case "TwoPageRight": return { layout: "two", cover: true };
    default: return null;
  }
}

export interface ViewerHandle {
  goTo(page: number): void;
  /** Next / previous page, or spread in two-page view. */
  next(): void;
  prev(): void;
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
  /** Current viewport of a page (CSS pixels), for mapping selections to PDF space. */
  pageViewport(page: number): any;
  setDisplay(d: DisplayMode): void;
  display(): DisplayMode;
  /** Acrobat's Full Screen mode (Ctrl+L): one page at a time, fitted, nothing else on screen. */
  fullScreen(): void;
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
  /** Zoom used when the document is first shown ("auto" is Acrobat's Automatic). */
  initialZoom?: string;
  /** Page display used when the document is first shown; null = the document's own setting. */
  initialDisplay?: DisplayMode | null;
  onInitialView?: (v: InitialView) => void;
  onDisplayChange?: (d: DisplayMode) => void;
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
  const displayRef = useRef<DisplayMode>({ layout: "continuous", cover: false });
  const openDestRef = useRef<any>(null);
  const fsRestoreRef = useRef<{ display: DisplayMode; scale: string } | null>(null);
  const propsRef = useRef(props);
  propsRef.current = props;
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
      imageResourcesPath: base + "images/",
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
      const hidden = (containerRef.current?.clientWidth || 0) < 50;
      // Loading a new revision resets the page display; put the user's choice back.
      applyDisplay(viewer, displayRef.current);
      if (!keep) {
        // First display: the user's default zoom, then the document's own opening
        // page and magnification if it defines one (as Acrobat does).
        viewer.currentScaleValue = hidden ? "page-width" : propsRef.current.initialZoom || "auto";
        const dest = openDestRef.current;
        openDestRef.current = null;
        if (dest) Promise.resolve(linkService.goToDestination(dest)).catch(() => undefined);
      } else {
        const numeric = keep.scale && !isNaN(Number(keep.scale)) ? Number(keep.scale) : null;
        viewer.currentScaleValue = hidden || !keep.scale || (numeric !== null && numeric <= 0.05) ? "page-width" : keep.scale;
        if (keep.page) viewer.currentPageNumber = Math.min(keep.page, viewer.pagesCount);
      }
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
    // Re-fit when the viewer becomes visible or is resized (it is hidden in Pages mode).
    const ro = new ResizeObserver(() => {
      const c = containerRef.current;
      if (!c || c.clientWidth < 50 || !pdfRef.current) return;
      const val = viewer.currentScaleValue;
      if (["page-width", "page-fit", "auto", "page-actual"].includes(val)) viewer.currentScaleValue = val;
      else if (!(viewer.currentScale > 0.05)) viewer.currentScaleValue = "page-width";
    });
    ro.observe(containerRef.current!);
    // Full Screen: one fitted page at a time on black; leaving it restores the view.
    const onFs = () => {
      const wrap = containerRef.current?.parentElement;
      const r = fsRestoreRef.current;
      if (document.fullscreenElement && document.fullscreenElement === wrap) return;
      if (r) {
        fsRestoreRef.current = null;
        displayRef.current = r.display;
        applyDisplay(viewer, r.display);
        viewer.currentScaleValue = r.scale;
        propsRef.current.onDisplayChange?.(r.display);
      }
    };
    document.addEventListener("fullscreenchange", onFs);
    // Hold the space bar and drag to move the page (Acrobat's temporary Hand tool).
    const c = containerRef.current!;
    let space = false;
    let drag: { x: number; y: number; l: number; t: number } | null = null;
    const typing = (t: any) => t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable);
    const kd = (e: KeyboardEvent) => {
      if (e.code !== "Space" || typing(e.target) || document.fullscreenElement || c.offsetParent === null) return;
      e.preventDefault();
      if (!space) {
        space = true;
        c.classList.add("pan-ready");
      }
    };
    const ku = (e: KeyboardEvent) => {
      if (e.code !== "Space") return;
      space = false;
      c.classList.remove("pan-ready");
    };
    const md = (e: MouseEvent) => {
      if (!space || e.button !== 0) return;
      e.preventDefault();
      e.stopPropagation();
      drag = { x: e.clientX, y: e.clientY, l: c.scrollLeft, t: c.scrollTop };
      c.classList.add("panning");
    };
    const mm = (e: MouseEvent) => {
      if (!drag) return;
      c.scrollLeft = drag.l - (e.clientX - drag.x);
      c.scrollTop = drag.t - (e.clientY - drag.y);
    };
    const mu = () => {
      if (!drag) return;
      drag = null;
      c.classList.remove("panning");
    };
    const blur = () => { space = false; drag = null; c.classList.remove("pan-ready", "panning"); };
    window.addEventListener("keydown", kd, true);
    window.addEventListener("keyup", ku, true);
    window.addEventListener("blur", blur);
    c.addEventListener("mousedown", md, true);
    window.addEventListener("mousemove", mm);
    window.addEventListener("mouseup", mu);
    return () => {
      document.removeEventListener("fullscreenchange", onFs);
      window.removeEventListener("keydown", kd, true);
      window.removeEventListener("keyup", ku, true);
      window.removeEventListener("blur", blur);
      c.removeEventListener("mousedown", md, true);
      window.removeEventListener("mousemove", mm);
      window.removeEventListener("mouseup", mu);
      ro.disconnect();
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
      .then(async (pdf: any) => {
        if (cancelled) {
          pdf.destroy();
          return;
        }
        const old = pdfRef.current;
        if (!old) {
          // First load: the document's own initial view (page display, panels, opening page).
          const [pageMode, pageLayout, openAction] = await Promise.all([
            pdf.getPageMode().catch(() => null),
            pdf.getPageLayout().catch(() => null),
            pdf.getOpenAction().catch(() => null),
          ]);
          if (cancelled) return pdf.destroy();
          displayRef.current = props.initialDisplay || layoutFromPdf(pageLayout) || { layout: "continuous", cover: false };
          openDestRef.current = Array.isArray(openAction?.dest) ? openAction.dest : null;
          props.onInitialView?.({ pageMode, pageLayout });
          props.onDisplayChange?.(displayRef.current);
        }
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
    next: () => viewerRef.current?.nextPage(),
    prev: () => viewerRef.current?.previousPage(),
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
    pageViewport: (page) => viewerRef.current?.getPageView(page - 1)?.viewport || null,
    setDisplay: (d) => {
      displayRef.current = d;
      const v = viewerRef.current;
      if (v?.pagesCount) applyDisplay(v, d);
      props.onDisplayChange?.(d);
    },
    display: () => displayRef.current,
    fullScreen: () => {
      const v = viewerRef.current;
      const wrap = containerRef.current?.parentElement;
      if (!v || !wrap || document.fullscreenElement) return;
      fsRestoreRef.current = { display: displayRef.current, scale: v.currentScaleValue };
      wrap.requestFullscreen().then(() => {
        displayRef.current = { layout: "single", cover: false };
        applyDisplay(v, displayRef.current);
        setTimeout(() => { v.currentScaleValue = "page-fit"; }, 50);
      }).catch(() => { fsRestoreRef.current = null; });
    },
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

function applyDisplay(v: any, d: DisplayMode) {
  const page = v.currentPageNumber;
  v.scrollMode = d.layout === "single" || d.layout === "two" ? ScrollMode.PAGE : ScrollMode.VERTICAL;
  v.spreadMode = d.layout === "two" || d.layout === "twoContinuous" ? (d.cover ? SpreadMode.EVEN : SpreadMode.ODD) : SpreadMode.NONE;
  if (page) v.currentPageNumber = page;
}

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
