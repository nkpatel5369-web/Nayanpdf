import React from "react";

const RED = "#C8102E";
const PAGE = "M206 72 H690 L878 260 V888 Q878 952 814 952 H206 Q142 952 142 888 V136 Q142 72 206 72 Z";
const FOLD = "M690 72 V196 Q690 260 754 260 H878";

/**
 * The N PDF mark: a white page with a folded corner and a red N (the same drawing
 * as build/icon.svg; at 32 px and below, as in build/icon-small.svg, the N alone).
 */
export function BrandMark({ size = 24 }: { size?: number }) {
  const small = size <= 32;
  const outline = small ? 44 : 16;
  return (
    <svg className="brand-mark" width={size} height={size} viewBox="0 0 1024 1024" aria-hidden="true" focusable="false">
      <path d={PAGE} fill="#ffffff" stroke="#c9c9cf" strokeWidth={outline} strokeLinejoin="round" />
      <path d={FOLD} fill="#f1f1f4" stroke="#c9c9cf" strokeWidth={outline} strokeLinejoin="round" />
      <g fill="none" stroke={RED} strokeLinecap="round" strokeLinejoin="round">
        {small ? (
          <path d="M358.8,760 L358.8,340 L661.2,760 L661.2,340" strokeWidth={88} />
        ) : (
          <>
            <path d="M391.2,630 L391.2,300 L628.8,630 L628.8,300" strokeWidth={40} />
            <path d={PDF_PATH} strokeWidth={24} />
          </>
        )}
      </g>
    </svg>
  );
}

const PDF_PATH = (() => {
  const h = 130, gap = 34, r1 = 0.27 * h, r2 = 0.5 * h;
  const wP = 0.33 * h + r1, wD = 0.2 * h + r2, wF = 0.52 * h;
  let x = 510 - (wP + gap + wD + gap + wF) / 2;
  const y = 720;
  const p = `M${x},${y + h} L${x},${y} L${x + 0.33 * h},${y} A${r1},${r1} 0 0 1 ${x + 0.33 * h},${y + 2 * r1} L${x},${y + 2 * r1}`;
  x += wP + gap;
  const d = `M${x},${y} L${x},${y + h} L${x + 0.2 * h},${y + h} A${r2},${r2} 0 0 0 ${x + 0.2 * h},${y} Z`;
  x += wD + gap;
  const f = `M${x + 0.52 * h},${y} L${x},${y} L${x},${y + h} M${x},${y + 0.47 * h} L${x + 0.42 * h},${y + 0.47 * h}`;
  return `${p} ${d} ${f}`;
})();
