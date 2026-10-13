import React from "react";
import { CheckCircle2, CircleSlash, FolderOpen, RotateCcw, X, XCircle } from "lucide-react";
import { api, baseName } from "../api";
import { useApp } from "../store";
import { ProgressBar, Spinner } from "../components/ui";
import { openDocument } from "../components/common";

export function JobsDrawer({ onClose }: { onClose: () => void }) {
  const jobs = useApp((s) => s.jobs);
  const setJobs = useApp((s) => s.setJobs);
  return (
    <div className="drawer">
      <div className="row panel-pad" style={{ borderBottom: "1px solid var(--border)" }}>
        <h3 className="grow">Activity</h3>
        <button className="btn sm ghost" onClick={() => api.clearJobs().then(setJobs)}>Clear finished</button>
        <button className="icon-btn" onClick={onClose}><X size={17} /></button>
      </div>
      <div className="side-body">
        {jobs.length === 0 && <div className="empty">No operations yet.</div>}
        {jobs.map((j) => {
          const running = j.status === "running" || j.status === "queued";
          return (
            <div key={j.id} className="job-item">
              <div className="hd">
                {running ? <Spinner /> : j.status === "succeeded" ? <CheckCircle2 size={16} color="var(--success)" /> : j.status === "failed" ? <XCircle size={16} color="var(--danger)" /> : <CircleSlash size={16} color="var(--text-3)" />}
                <span className="grow ellipsis" style={{ fontWeight: 550 }} title={j.label}>{j.label}</span>
                {running && <button className="btn sm" onClick={() => api.cancelJob(j.id)}>Cancel</button>}
                {(j.status === "failed" || j.status === "cancelled") && <button className="icon-btn" title="Retry" onClick={() => api.retryJob(j.id)}><RotateCcw size={14} /></button>}
              </div>
              {running && (
                <div style={{ marginTop: 6 }}>
                  <ProgressBar current={j.progress?.current} total={j.progress?.total} />
                  <div className="subtle" style={{ marginTop: 3 }}>{j.status === "queued" ? "Queued" : j.progress?.message || j.statusMessage || "Working..."}</div>
                </div>
              )}
              {j.status === "failed" && <div className="subtle" style={{ color: "var(--danger)", marginTop: 4 }}>{j.error?.message}</div>}
              {j.status === "succeeded" && (j.outputs || []).slice(0, 3).map((o) => (
                <div key={o} className="row" style={{ marginTop: 4 }}>
                  <span className="subtle grow ellipsis" title={o}>{baseName(o)}</span>
                  {o.toLowerCase().endsWith(".pdf") && <button className="btn sm" onClick={() => openDocument(o)}>Open</button>}
                  <button className="icon-btn" title="Show in folder" onClick={() => api.reveal(o)}><FolderOpen size={14} /></button>
                </div>
              ))}
              {j.status === "succeeded" && (j.outputs || []).length > 3 && <div className="subtle">+{(j.outputs || []).length - 3} more</div>}
              {(j.warnings || []).length > 0 && j.status === "succeeded" && <div className="subtle" style={{ color: "var(--warning)", marginTop: 4 }}>{j.warnings!.length} note(s): {j.warnings![0].slice(0, 120)}</div>}
            </div>
          );
        })}
      </div>
    </div>
  );
}
