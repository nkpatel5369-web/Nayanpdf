import React, { useEffect, useRef, useState } from "react";

/** Lazily rendered page thumbnail (renders only when scrolled into view). */
export function PageThumb(props: { pdf: any; page: number; width: number; rotation?: number; className?: string; onClick?: () => void; extraRotate?: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const holder = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<{ w: number; h: number }>({ w: props.width, h: Math.round(props.width * 1.414) });
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const el = holder.current;
    if (!el) return;
    const io = new IntersectionObserver((ents) => ents.forEach((e) => e.isIntersecting && setVisible(true)), { rootMargin: "300px" });
    io.observe(el);
    return () => io.disconnect();
  }, []);

  useEffect(() => {
    // After pages are deleted the list can briefly hold numbers past the new end.
    if (!visible || !props.pdf || props.page > props.pdf.numPages) return;
    let task: any = null;
    let alive = true;
    props.pdf.getPage(props.page).catch(() => null).then((page: any) => {
      if (!page) return;
      if (!alive) return;
      const rot = ((page.rotate || 0) + (props.rotation || 0) + (props.extraRotate || 0)) % 360;
      const vp1 = page.getViewport({ scale: 1, rotation: rot });
      const scale = props.width / vp1.width;
      const vp = page.getViewport({ scale: scale * (window.devicePixelRatio || 1), rotation: rot });
      const c = ref.current;
      if (!c) return;
      c.width = Math.floor(vp.width);
      c.height = Math.floor(vp.height);
      c.style.width = props.width + "px";
      c.style.height = Math.round(vp1.height * scale) + "px";
      setSize({ w: props.width, h: Math.round(vp1.height * scale) });
      const ctx = c.getContext("2d")!;
      task = page.render({ canvasContext: ctx, viewport: vp, annotationMode: 1 });
      task.promise.catch(() => undefined);
    });
    return () => {
      alive = false;
      try {
        task?.cancel();
      } catch {
        /* ignore */
      }
    };
  }, [visible, props.pdf, props.page, props.width, props.rotation, props.extraRotate]);

  return (
    <div ref={holder} onClick={props.onClick} className={props.className}>
      {visible ? <canvas ref={ref} style={{ width: size.w, height: size.h }} /> : <div className="thumb-ph" style={{ width: size.w, height: size.h }} />}
    </div>
  );
}

export function ThumbnailPanel(props: { pdf: any; count: number; current: number; rotation: number; onSelect: (p: number) => void }) {
  const listRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = listRef.current?.querySelector(`[data-page="${props.current}"]`);
    el?.scrollIntoView({ block: "nearest" });
  }, [props.current]);
  if (!props.pdf) return <div className="empty">Loading...</div>;
  return (
    <div className="thumbs" ref={listRef}>
      {Array.from({ length: props.count }, (_, i) => i + 1).map((n) => (
        <div key={n} data-page={n} className={"thumb-item" + (n === props.current ? " on" : "")} onClick={() => props.onSelect(n)}>
          <PageThumb pdf={props.pdf} page={n} width={140} rotation={props.rotation} />
          <span>{n}</span>
        </div>
      ))}
    </div>
  );
}
