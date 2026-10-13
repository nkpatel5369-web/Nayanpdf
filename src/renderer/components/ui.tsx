import React, { ReactNode, useEffect, useState } from "react";
import { AlertTriangle, CheckCircle2, Info, Loader2, X, XCircle } from "lucide-react";
import { useApp } from "../store";

export function Modal(props: { title: ReactNode; onClose?: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean; icon?: ReactNode }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => {
      if (e.key === "Escape" && props.onClose) props.onClose();
    };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [props.onClose]);
  return (
    <div className="modal-back" onMouseDown={(e) => e.target === e.currentTarget && props.onClose?.()}>
      <div className={"modal" + (props.wide ? " wide" : "")} role="dialog" aria-modal="true">
        <div className="modal-head">
          {props.icon}
          <h2>{props.title}</h2>
          {props.onClose && (
            <button className="icon-btn" onClick={props.onClose} title="Close (Esc)">
              <X size={18} />
            </button>
          )}
        </div>
        <div className="modal-body">{props.children}</div>
        {props.footer && <div className="modal-foot">{props.footer}</div>}
      </div>
    </div>
  );
}

export function Field(props: { label: ReactNode; children: ReactNode; hint?: ReactNode }) {
  return (
    <div className="field">
      <label>{props.label}</label>
      {props.children}
      {props.hint && <div className="hint">{props.hint}</div>}
    </div>
  );
}

export function Notice(props: { kind?: "info" | "warn" | "err" | "ok"; children: ReactNode }) {
  const k = props.kind || "info";
  const Icon = k === "warn" ? AlertTriangle : k === "err" ? XCircle : k === "ok" ? CheckCircle2 : Info;
  const color = k === "warn" ? "var(--warning)" : k === "err" ? "var(--danger)" : k === "ok" ? "var(--success)" : "var(--accent)";
  return (
    <div className={`notice ${k}`}>
      <Icon size={16} color={color} />
      <div>{props.children}</div>
    </div>
  );
}

export function Seg<T extends string>(props: { value: T; options: { value: T; label: ReactNode; title?: string }[]; onChange: (v: T) => void }) {
  return (
    <div className="seg" role="radiogroup">
      {props.options.map((o) => (
        <button key={o.value} className={o.value === props.value ? "on" : ""} title={o.title} onClick={() => props.onChange(o.value)} type="button">
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Check(props: { checked: boolean; onChange: (v: boolean) => void; children: ReactNode; title?: string }) {
  return (
    <label className="check" title={props.title}>
      <input type="checkbox" checked={props.checked} onChange={(e) => props.onChange(e.target.checked)} />
      <span>{props.children}</span>
    </label>
  );
}

export function Spinner({ size = 16 }: { size?: number }) {
  return <Loader2 size={size} className="spin" />;
}

export function ProgressBar(props: { current?: number; total?: number }) {
  const known = props.total !== undefined && props.total > 0;
  const pct = known ? Math.min(100, Math.round(((props.current || 0) / props.total!) * 100)) : 0;
  return (
    <div className={"progress" + (known ? "" : " indeterminate")}>
      <div style={known ? { width: pct + "%" } : undefined} />
    </div>
  );
}

export function Toasts() {
  const toasts = useApp((s) => s.toasts);
  const dismiss = useApp((s) => s.dismiss);
  return (
    <div className="toasts">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.kind}`}>
          <div className="t">
            <span className="grow">{t.title}</span>
            <button className="icon-btn" style={{ width: 22, height: 22 }} onClick={() => dismiss(t.id)}>
              <X size={14} />
            </button>
          </div>
          {t.body && <div className="b">{t.body}</div>}
          {t.actions && (
            <div className="row" style={{ marginTop: 8 }}>
              {t.actions.map((a) => (
                <button key={a.label} className="btn sm" onClick={() => { a.run(); dismiss(t.id); }}>
                  {a.label}
                </button>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

export function PasswordPrompt(props: { fileName: string; invalid?: boolean; onSubmit: (pw: string) => void; onCancel: () => void; purpose?: string }) {
  const [pw, setPw] = useState("");
  return (
    <Modal
      title="Password required"
      onClose={props.onCancel}
      footer={
        <>
          <button className="btn" onClick={props.onCancel}>Cancel</button>
          <button className="btn primary" disabled={!pw} onClick={() => props.onSubmit(pw)}>Unlock</button>
        </>
      }
    >
      <p className="muted" style={{ marginTop: 0 }}>
        {props.purpose || <>"{props.fileName}" is password protected.</>}
      </p>
      {props.invalid && <Notice kind="err">The password is incorrect.</Notice>}
      <input
        className="input"
        type="password"
        autoFocus
        value={pw}
        placeholder="Password"
        onChange={(e) => setPw(e.target.value)}
        onKeyDown={(e) => e.key === "Enter" && pw && props.onSubmit(pw)}
      />
      <p className="subtle">The password is used only in memory for this session and is never stored.</p>
    </Modal>
  );
}

export const PALETTE = ["#e03131", "#f08c00", "#ffd43b", "#2f9e44", "#1c7ed6", "#7048e8", "#000000", "#868e96"];

export function ColorPicker(props: { value: string; onChange: (c: string) => void; colors?: string[] }) {
  const colors = props.colors || PALETTE;
  return (
    <div className="row" style={{ gap: 6 }}>
      {colors.map((c) => (
        <span key={c} className={"color-dot" + (c.toLowerCase() === props.value.toLowerCase() ? " on" : "")} style={{ background: c }} onClick={() => props.onChange(c)} />
      ))}
      <input type="color" value={props.value} onChange={(e) => props.onChange(e.target.value)} style={{ width: 26, height: 24, border: "none", background: "none", padding: 0 }} />
    </div>
  );
}

export function NumberInput(props: { value: number; onChange: (v: number) => void; min?: number; max?: number; step?: number; width?: number; small?: boolean }) {
  return (
    <input
      className={"input" + (props.small ? " sm" : "")}
      type="number"
      style={{ width: props.width || 90 }}
      value={Number.isFinite(props.value) ? props.value : ""}
      min={props.min}
      max={props.max}
      step={props.step || 1}
      onChange={(e) => props.onChange(parseFloat(e.target.value))}
    />
  );
}
