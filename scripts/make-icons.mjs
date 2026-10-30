// Renders build/icon.svg to build/icon.png (512 px) and the Windows icon
// build/icon.ico (16-256 px, each size drawn from the vector, not scaled down
// from one bitmap). Sizes of 32 px and below use build/icon-small.svg, which
// leaves out the lettering that cannot be read that small. Uses the Chromium
// that Playwright drives for the UI tests and Python's Pillow to pack the .ico.
// Run: node scripts/make-icons.mjs
import { createRequire } from "node:module";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const svg = readFileSync(join(root, "build", "icon.svg"), "utf8");
const svgSmall = readFileSync(join(root, "build", "icon-small.svg"), "utf8");
const work = mkdtempSync(join(tmpdir(), "icons-"));
const sizes = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256, 512];
// Playwright with its own Chromium if installed; otherwise playwright-core (a
// dev dependency) with a Chromium named by CHROMIUM_PATH.
const req = createRequire(import.meta.url);
let pw;
try {
  pw = req("playwright");
} catch {
  pw = req("playwright-core");
}
const browser = await pw.chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const page = await browser.newPage();
for (const s of sizes) {
  await page.setViewportSize({ width: s, height: s });
  const src = s <= 32 ? svgSmall : svg;
  await page.setContent(`<html><body style="margin:0;background:transparent">${src.replace('width="1024" height="1024"', `width="${s}" height="${s}"`)}</body></html>`);
  await page.screenshot({ path: join(work, `${s}.png`), omitBackground: true, clip: { x: 0, y: 0, width: s, height: s } });
}
await browser.close();
const py = process.env.PYTHON || "python3";
execFileSync(py, ["-c", `
import sys
from PIL import Image
work, out = sys.argv[1], sys.argv[2]
Image.open(f"{work}/512.png").save(f"{out}/icon.png")
ico = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]
imgs = [Image.open(f"{work}/{s}.png").convert("RGBA") for s in ico]
imgs[-1].save(f"{out}/icon.ico", format="ICO", sizes=[(s, s) for s in ico], append_images=imgs[:-1])
print("icon.png 512 px; icon.ico", ", ".join(map(str, ico)))
`, work, join(root, "build")], { stdio: "inherit" });
rmSync(work, { recursive: true, force: true });
