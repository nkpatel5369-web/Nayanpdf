import { create } from "zustand";
import type { DocState, JobInfo, RecentFile, Settings } from "@shared/ipc";
import { DEFAULT_SETTINGS } from "@shared/ipc";

export type ToolId =
  | "merge" | "split" | "compress" | "convert" | "organize" | "ocr" | "create" | "annotate" | "sign" | "protect"
  | "redact" | "compare" | "extractImages" | "forms" | "prepareForm" | "edit" | "watermark" | "metadata" | "utilities" | "batch" | "search";

export type MainView = { kind: "home" } | { kind: "tool"; tool: ToolId; args?: any } | { kind: "doc"; sessionId: string }
  | { kind: "batch" } | { kind: "settings" } | { kind: "convert" };

export interface OpenDoc {
  state: DocState;
  info: any;
  password?: string; // kept in memory only, for the viewer
  initialMode?: string;
}

export interface Toast {
  id: number;
  kind: "info" | "success" | "error" | "warning";
  title: string;
  body?: string;
  actions?: { label: string; run: () => void }[];
}

interface AppState {
  view: MainView;
  docs: Record<string, OpenDoc>;
  docOrder: string[];
  settings: Settings;
  recent: RecentFile[];
  jobs: JobInfo[];
  toasts: Toast[];
  caps: any;
  jobsOpen: boolean;
  setView: (v: MainView) => void;
  addDoc: (d: OpenDoc) => void;
  updateDocState: (s: DocState) => void;
  updateDocInfo: (id: string, info: any) => void;
  removeDoc: (id: string) => void;
  setSettings: (s: Settings) => void;
  setRecent: (r: RecentFile[]) => void;
  upsertJob: (j: JobInfo) => void;
  setJobs: (j: JobInfo[]) => void;
  toast: (t: Omit<Toast, "id">) => void;
  dismiss: (id: number) => void;
  setCaps: (c: any) => void;
  setJobsOpen: (o: boolean) => void;
}

let toastSeq = 1;

export const useApp = create<AppState>((set) => ({
  view: { kind: "home" },
  docs: {},
  docOrder: [],
  settings: DEFAULT_SETTINGS,
  recent: [],
  jobs: [],
  toasts: [],
  caps: null,
  jobsOpen: false,
  setView: (view) => set({ view }),
  addDoc: (d) =>
    set((s) => ({ docs: { ...s.docs, [d.state.sessionId]: d }, docOrder: [...s.docOrder, d.state.sessionId], view: { kind: "doc", sessionId: d.state.sessionId } })),
  updateDocState: (st) =>
    set((s) => (s.docs[st.sessionId] ? { docs: { ...s.docs, [st.sessionId]: { ...s.docs[st.sessionId], state: st } } } : {})),
  updateDocInfo: (id, info) => set((s) => (s.docs[id] ? { docs: { ...s.docs, [id]: { ...s.docs[id], info } } } : {})),
  removeDoc: (id) =>
    set((s) => {
      const docs = { ...s.docs };
      delete docs[id];
      const order = s.docOrder.filter((x) => x !== id);
      let view = s.view;
      if (view.kind === "doc" && view.sessionId === id) view = order.length ? { kind: "doc", sessionId: order[order.length - 1] } : { kind: "home" };
      return { docs, docOrder: order, view };
    }),
  setSettings: (settings) => set({ settings }),
  setRecent: (recent) => set({ recent }),
  upsertJob: (j) =>
    set((s) => {
      const idx = s.jobs.findIndex((x) => x.id === j.id);
      if (idx >= 0) {
        const jobs = s.jobs.slice();
        jobs[idx] = j;
        return { jobs };
      }
      return { jobs: [j, ...s.jobs] };
    }),
  setJobs: (jobs) => set({ jobs }),
  toast: (t) => {
    const id = toastSeq++;
    set((s) => ({ toasts: [...s.toasts, { ...t, id }] }));
    if (t.kind !== "error") setTimeout(() => useApp.getState().dismiss(id), t.actions ? 9000 : 5000);
  },
  dismiss: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
  setCaps: (caps) => set({ caps }),
  setJobsOpen: (jobsOpen) => set({ jobsOpen }),
}));

export function errorText(e: any): string {
  if (!e) return "Unknown error";
  return e.message || String(e);
}
