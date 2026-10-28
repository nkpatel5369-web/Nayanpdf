import React, { useId } from "react";

/** The X PDF mark: a folded page with a two-tone X (the same drawing as build/icon.svg). */
export function BrandMark({ size = 24 }: { size?: number }) {
  const id = useId().replace(/[^a-zA-Z0-9]/g, "");
  return (
    <svg className="brand-mark" width={size} height={size} viewBox="0 0 1024 1024" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id={`${id}p`} x1="0" y1="0" x2="0.35" y2="1">
          <stop offset="0" stopColor="#4f46e5" />
          <stop offset="1" stopColor="#2a2596" />
        </linearGradient>
        <linearGradient id={`${id}f`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#c7d2fe" />
          <stop offset="1" stopColor="#8b9cf7" />
        </linearGradient>
      </defs>
      <path d="M168 64 H704 L960 320 V856 Q960 960 856 960 H168 Q64 960 64 856 V168 Q64 64 168 64 Z" fill={`url(#${id}p)`} />
      <path d="M704 64 V216 Q704 320 808 320 H960 Z" fill={`url(#${id}f)`} />
      <g strokeLinecap="round" strokeWidth="156" fill="none">
        <line x1="290" y1="372" x2="694" y2="800" stroke="#38bdf8" />
        <line x1="694" y1="372" x2="290" y2="800" stroke="#ffffff" />
      </g>
    </svg>
  );
}
