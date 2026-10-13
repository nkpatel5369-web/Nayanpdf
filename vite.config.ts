import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";

// The renderer is served from the app:// scheme in production; all assets are local.
export default defineConfig({
  root: resolve(__dirname, "src/renderer"),
  base: "./",
  plugins: [react()],
  resolve: { alias: { "@shared": resolve(__dirname, "src/shared") } },
  build: {
    outDir: resolve(__dirname, "dist/renderer"),
    emptyOutDir: true,
    target: "chrome130",
    sourcemap: false,
    chunkSizeWarningLimit: 4000,
    assetsInlineLimit: 0,
  },
  worker: { format: "es" },
});
