/// <reference types="vite/client" />
interface NayanBridge {
  invoke(channel: string, payload?: unknown): Promise<{ ok: boolean; data?: any; error?: { code: string; message: string; details?: any } }>;
  on(channel: string, cb: (data: any) => void): () => void;
  onFilesDropped(cb: (paths: string[]) => void): () => void;
  platform: string;
}
interface Window {
  nayan: NayanBridge;
}
declare module "*.css";
