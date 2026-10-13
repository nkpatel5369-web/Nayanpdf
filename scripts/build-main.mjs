// Bundles the Electron main process and the (sandboxed) preload script.
import { build } from "esbuild";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const common = {
  bundle: true,
  platform: "node",
  target: "node22",
  format: "cjs",
  external: ["electron"],
  sourcemap: false,
  logLevel: "info",
  alias: { "@shared": resolve(root, "src/shared") },
};
await build({ ...common, entryPoints: [resolve(root, "src/main/main.ts")], outfile: resolve(root, "dist/main/main.js") });
await build({ ...common, entryPoints: [resolve(root, "src/preload/preload.ts")], outfile: resolve(root, "dist/preload/preload.js") });
