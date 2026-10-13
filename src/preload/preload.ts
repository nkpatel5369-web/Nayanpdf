// Preload (isolated world). Exposes a narrow, allow-listed bridge to the UI.
import { contextBridge, ipcRenderer, webUtils } from "electron";
import { EVENT_CHANNELS, INVOKE_CHANNELS } from "../shared/ipc";

const invokeSet = new Set<string>(INVOKE_CHANNELS);
const eventSet = new Set<string>(EVENT_CHANNELS);
const dropListeners = new Set<(paths: string[]) => void>();

// Dropped files: resolve real OS paths here (the UI's main world cannot), and
// register them with the main process so they become accessible.
window.addEventListener(
  "drop",
  (e: DragEvent) => {
    const files = e.dataTransfer?.files;
    if (!files || files.length === 0) return;
    const paths: string[] = [];
    for (const f of Array.from(files)) {
      try {
        const p = webUtils.getPathForFile(f);
        if (p) paths.push(p);
      } catch {
        /* not a real file */
      }
    }
    if (!paths.length) return;
    ipcRenderer.invoke("files:registerDropped", paths).then((ok: string[]) => {
      for (const l of dropListeners) l(ok);
    });
  },
  true,
);

contextBridge.exposeInMainWorld("nayan", {
  invoke(channel: string, payload?: unknown) {
    if (!invokeSet.has(channel)) return Promise.resolve({ ok: false, error: { code: "ACCESS_DENIED", message: "Channel not allowed" } });
    return ipcRenderer.invoke(channel, payload);
  },
  on(channel: string, cb: (data: unknown) => void) {
    if (!eventSet.has(channel)) return () => undefined;
    const fn = (_e: unknown, data: unknown) => cb(data);
    ipcRenderer.on(channel, fn);
    return () => ipcRenderer.removeListener(channel, fn);
  },
  onFilesDropped(cb: (paths: string[]) => void) {
    dropListeners.add(cb);
    return () => dropListeners.delete(cb);
  },
  platform: process.platform,
});
