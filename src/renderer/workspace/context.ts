import { createContext, useContext } from "react";
import type { OpenDoc } from "../store";
import type { ViewerHandle } from "./Viewer";

export type Mode = "view" | "edit" | "comment" | "pages" | "forms" | "redact" | "sign";

export interface RedactMark {
  id: string;
  page: number;
  rect: number[];
  text?: string;
  label?: string;
}

export interface WsApi {
  sessionId: string;
  doc: OpenDoc;
  viewer: ViewerHandle | null;
  pdf: any;
  page: number;
  revision: number;
  mode: Mode;
  tool: string;
  setTool: (t: string) => void;
  opts: Record<string, any>;
  setOpts: (patch: Record<string, any>) => void;
  apply: (method: string, params: Record<string, any>, label: string) => Promise<any | null>;
  query: <T = any>(method: string, params?: Record<string, any>) => Promise<T>;
  busy: string | null;
  marks: RedactMark[];
  setMarks: (m: RedactMark[] | ((m: RedactMark[]) => RedactMark[])) => void;
  selected: any;
  setSelected: (s: any) => void;
  bump: () => void;
}

export const WsContext = createContext<WsApi | null>(null);

export function useWs(): WsApi {
  const v = useContext(WsContext);
  if (!v) throw new Error("workspace context missing");
  return v;
}
