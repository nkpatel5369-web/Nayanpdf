// Print layout (Acrobat-style page sizing and handling). Computes, in points,
// where every page goes on every sheet. The same plan drives the on-screen
// preview and the job sent to the printer, so what you see is what prints.

export type Sizing = "fit" | "actual" | "shrink" | "custom" | "multiple";
export type Orientation = "auto" | "portrait" | "landscape";
export type Subset = "all" | "odd" | "even";

export const PAPERS: Record<string, { label: string; w: number; h: number }> = {
  A4: { label: "A4 (210 × 297 mm)", w: 595.28, h: 841.89 },
  A3: { label: "A3 (297 × 420 mm)", w: 841.89, h: 1190.55 },
  A5: { label: "A5 (148 × 210 mm)", w: 419.53, h: 595.28 },
  Letter: { label: "Letter (8.5 × 11 in)", w: 612, h: 792 },
  Legal: { label: "Legal (8.5 × 14 in)", w: 612, h: 1008 },
};

export const PER_SHEET = [2, 4, 6, 9, 16] as const;

export interface PrintOptions {
  pages: number[]; // pages to print, in order
  sizing: Sizing;
  customScale: number; // percent, for "custom"
  perSheet: number; // for "multiple"
  order: "horizontal" | "vertical";
  borders: boolean;
  orientation: Orientation;
  paper: keyof typeof PAPERS | string;
}

export interface Placement {
  page: number;
  x: number; // points from the sheet's top-left corner
  y: number;
  w: number; // size on the sheet (after any rotation)
  h: number;
  rotate: 0 | 90;
  border: boolean;
}

export interface Plan {
  sheetW: number;
  sheetH: number;
  landscape: boolean;
  paper: string;
  sheets: Placement[][];
}

/** Printers cannot print to the very edge; fitted content keeps clear of it. */
const MARGIN = 18; // 0.25 inch
const GAP = 6;

/** Parse "1-3, 5, 8-" style ranges against a page count. Returns null if invalid. */
export function parseRange(spec: string, count: number): number[] | null {
  const out: number[] = [];
  const parts = spec.split(",").map((s) => s.trim()).filter(Boolean);
  if (!parts.length) return null;
  for (const part of parts) {
    const m = part.match(/^(\d+)?\s*(-)?\s*(\d+)?$/);
    if (!m || (!m[1] && !m[3])) return null;
    const a = m[1] ? parseInt(m[1]) : 1;
    const b = m[2] ? (m[3] ? parseInt(m[3]) : count) : a;
    if (a < 1 || b > count || a > b) return null;
    for (let i = a; i <= b; i++) out.push(i);
  }
  return out;
}

export function selectPages(base: number[], subset: Subset, reverse: boolean): number[] {
  let p = base;
  if (subset === "odd") p = p.filter((n) => n % 2 === 1);
  if (subset === "even") p = p.filter((n) => n % 2 === 0);
  return reverse ? [...p].reverse() : p;
}

function grid(n: number, landscape: boolean): [number, number] {
  const g: Record<number, [number, number]> = { 2: [2, 1], 4: [2, 2], 6: [3, 2], 9: [3, 3], 16: [4, 4] };
  const [c, r] = g[n] || [2, 2];
  return landscape ? [c, r] : [r, c]; // [cols, rows]
}

export function computePlan(opts: PrintOptions, size: (page: number) => { w: number; h: number }): Plan {
  const paper = PAPERS[opts.paper as string] || PAPERS.A4;
  const first = opts.pages.length ? size(opts.pages[0]) : { w: paper.w, h: paper.h };

  if (opts.sizing === "multiple") {
    const per = PER_SHEET.includes(opts.perSheet as any) ? opts.perSheet : 2;
    const cellScale = (land: boolean) => {
      const W = land ? paper.h : paper.w;
      const H = land ? paper.w : paper.h;
      const [cols, rows] = grid(per, land);
      const cw = (W - 2 * MARGIN - (cols - 1) * GAP) / cols;
      const ch = (H - 2 * MARGIN - (rows - 1) * GAP) / rows;
      return Math.min(cw / first.w, ch / first.h);
    };
    const landscape = opts.orientation === "landscape" || (opts.orientation === "auto" && cellScale(true) > cellScale(false));
    const W = landscape ? paper.h : paper.w;
    const H = landscape ? paper.w : paper.h;
    const [cols, rows] = grid(per, landscape);
    const cw = (W - 2 * MARGIN - (cols - 1) * GAP) / cols;
    const ch = (H - 2 * MARGIN - (rows - 1) * GAP) / rows;
    const sheets: Placement[][] = [];
    for (let i = 0; i < opts.pages.length; i += per) {
      sheets.push(opts.pages.slice(i, i + per).map((page, k) => {
        const col = opts.order === "vertical" ? Math.floor(k / rows) : k % cols;
        const row = opts.order === "vertical" ? k % rows : Math.floor(k / cols);
        const s = size(page);
        const sc = Math.min(cw / s.w, ch / s.h);
        const w = s.w * sc;
        const h = s.h * sc;
        return { page, x: MARGIN + col * (cw + GAP) + (cw - w) / 2, y: MARGIN + row * (ch + GAP) + (ch - h) / 2, w, h, rotate: 0, border: opts.borders };
      }));
    }
    return { sheetW: W, sheetH: H, landscape, paper: String(opts.paper), sheets };
  }

  // One page per sheet. "Auto" follows the first page's orientation and turns
  // pages of the other orientation to fit the paper, as Acrobat does.
  const landscape = opts.orientation === "landscape" || (opts.orientation === "auto" && first.w > first.h);
  const W = landscape ? paper.h : paper.w;
  const H = landscape ? paper.w : paper.h;
  const sheets = opts.pages.map((page) => {
    const s = size(page);
    const rotate: 0 | 90 = opts.orientation === "auto" && (s.w > s.h) !== landscape && Math.abs(s.w - s.h) > 1 ? 90 : 0;
    const pw = rotate ? s.h : s.w;
    const ph = rotate ? s.w : s.h;
    const fit = Math.min((W - 2 * MARGIN) / pw, (H - 2 * MARGIN) / ph);
    const sc = opts.sizing === "fit" ? fit : opts.sizing === "shrink" ? Math.min(1, fit) : opts.sizing === "custom" ? Math.max(0.01, opts.customScale / 100) : 1;
    const w = pw * sc;
    const h = ph * sc;
    return [{ page, x: (W - w) / 2, y: (H - h) / 2, w, h, rotate, border: false }];
  });
  return { sheetW: W, sheetH: H, landscape, paper: String(opts.paper), sheets };
}
