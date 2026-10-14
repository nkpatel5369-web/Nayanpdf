// Copies PDF.js runtime assets (worker, CMaps, standard fonts, wasm, ICC profiles)
// into the renderer's public folder so the viewer never fetches anything remotely.
import { cpSync, mkdirSync, rmSync, existsSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const src = resolve(root, "node_modules/pdfjs-dist");
const dst = resolve(root, "src/renderer/public/pdfjs");
rmSync(dst, { recursive: true, force: true });
mkdirSync(dst, { recursive: true });
cpSync(resolve(src, "build/pdf.worker.min.mjs"), resolve(dst, "pdf.worker.min.mjs"));
for (const d of ["cmaps", "standard_fonts", "wasm", "iccs"]) {
  if (existsSync(resolve(src, d))) cpSync(resolve(src, d), resolve(dst, d), { recursive: true });
}
cpSync(resolve(src, "web/images"), resolve(dst, "images"), { recursive: true });
cpSync(resolve(src, "LICENSE"), resolve(dst, "LICENSE"));
console.log("PDF.js assets copied to", dst);
