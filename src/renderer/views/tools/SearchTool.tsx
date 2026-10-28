import React, { useState } from "react";
import { FolderOpen } from "lucide-react";
import { api, baseName, waitForJob } from "../../api";
import { errorText, useApp } from "../../store";
import { toolById } from "../../tools";
import { Check, Field, Notice, ProgressBar, Seg } from "../../components/ui";
import { openAt } from "../../components/common";
import { ToolPage } from "./shared";

interface Match { page: number; where: "text" | "comment"; before: string; match: string; after: string }
interface FileHit { path: string; name: string; pages: number; matches: Match[] }

/** Search across files (Acrobat's Advanced Search, Shift+Ctrl+F): a folder of PDFs or all open documents. */
export function SearchTool() {
  const docs = useApp((s) => s.docs);
  const [query, setQuery] = useState("");
  const [where, setWhere] = useState<"folder" | "open">("folder");
  const [folder, setFolder] = useState<string | null>(null);
  const [recursive, setRecursive] = useState(true);
  const [wholeWord, setWholeWord] = useState(false);
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [inComments, setInComments] = useState(false);
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState<{ current: number; total: number } | null>(null);
  const [result, setResult] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const openDocs = Object.values(docs);

  const run = async () => {
    setError(null);
    setResult(null);
    let paths: string[] = [];
    if (where === "folder") {
      if (!folder) return setError("Choose a folder to search.");
      paths = await api.scanFolder(folder, ["pdf"], recursive);
      if (!paths.length) return setError("No PDF files in that folder.");
    } else {
      paths = openDocs.map((d) => d.state.savedPath || d.state.originalPath);
      if (!paths.length) return setError("No documents are open.");
    }
    setRunning(true);
    setProgress({ current: 0, total: paths.length });
    try {
      const j = await api.startJob("search.files", { paths, query, wholeWord, caseSensitive, inComments }, `Search for "${query}"`);
      const done = await waitForJob(j.id, (u: any) => u.progress && setProgress({ current: u.progress.current, total: u.progress.total }));
      if (done.status === "succeeded") setResult(done.result);
      else setError(done.error?.message || "The search did not complete.");
    } catch (e: any) {
      setError(errorText(e));
    } finally {
      setRunning(false);
      setProgress(null);
    }
  };

  return (
    <ToolPage tool={toolById("search")!} subtitle="Find a word, number or phrase in every PDF of a folder, or in all open documents. Runs entirely on this computer.">
      <div className="card card-pad">
        <Field label="Search for">
          <input className="input" aria-label="Search for" autoFocus value={query} placeholder="For example: 194J, ACME Traders, closing balance"
            onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => e.key === "Enter" && query.trim() && !running && run()} />
        </Field>
        <Field label="Look in">
          <Seg value={where} onChange={setWhere} options={[{ value: "folder", label: "A folder" }, { value: "open", label: `All open documents (${openDocs.length})` }]} />
        </Field>
        {where === "folder" && (
          <div className="row" style={{ marginBottom: 10 }}>
            <div className="input ellipsis" style={{ display: "flex", alignItems: "center", color: folder ? "var(--text)" : "var(--text-3)" }} title={folder || ""}>{folder || "No folder chosen"}</div>
            <button className="btn" onClick={async () => { const d = await api.openFolder("Folder to search"); if (d) setFolder(d); }}><FolderOpen size={15} /> Choose folder...</button>
          </div>
        )}
        {where === "folder" && <Check checked={recursive} onChange={setRecursive}>Include subfolders</Check>}
        {where === "open" && <p className="subtle">Open documents are searched as last saved on disk.</p>}
        <div className="row wrap" style={{ gap: 14 }}>
          <Check checked={wholeWord} onChange={setWholeWord}>Whole words only</Check>
          <Check checked={caseSensitive} onChange={setCaseSensitive}>Case-sensitive</Check>
          <Check checked={inComments} onChange={setInComments}>Include comments</Check>
        </div>
        <div className="row" style={{ marginTop: 6 }}>
          <div className="grow" />
          <button className="btn primary lg" disabled={!query.trim() || running} onClick={run}>{running ? "Searching..." : "Search"}</button>
        </div>
        {progress && <div style={{ marginTop: 10 }}><ProgressBar current={progress.current} total={progress.total} /><div className="subtle">{progress.current} of {progress.total} files</div></div>}
        {error && <Notice kind="err">{error}</Notice>}
      </div>
      {result && (
        <div className="card card-pad search-results" style={{ marginTop: 14 }}>
          <div className="row">
            <strong className="grow">{result.total} match{result.total === 1 ? "" : "es"} in {result.files.length} of {result.searched} file{result.searched === 1 ? "" : "s"}</strong>
          </div>
          {result.truncated && <Notice kind="warn">Only the first {result.total} matches are listed. Narrow the search to see the rest.</Notice>}
          {result.skipped.length > 0 && (
            <details className="subtle" style={{ margin: "6px 0" }}>
              <summary>{result.skipped.length} file(s) could not be searched{result.scannedWithoutText ? ` (${result.scannedWithoutText} scanned without a text layer; run OCR first)` : ""}</summary>
              {result.skipped.map((s: any) => <div key={s.path}>{baseName(s.path)}: {s.reason}</div>)}
            </details>
          )}
          {result.files.length === 0 && <div className="empty">No matches.</div>}
          {result.files.map((f: FileHit) => (
            <div key={f.path} className="search-file">
              <div className="row search-file-head" onClick={() => setCollapsed((c) => ({ ...c, [f.path]: !c[f.path] }))} title={f.path}>
                <strong className="grow ellipsis">{f.name}</strong>
                <span className="badge">{f.matches.length}</span>
              </div>
              {!collapsed[f.path] && f.matches.map((m, i) => (
                <button key={i} className="search-hit" onClick={() => openAt(f.path, m.page, m.where === "text" ? result.query : undefined)}>
                  <span className="subtle">p. {m.page}{m.where === "comment" ? " · comment" : ""}</span>
                  <span className="ctx">{m.before} <mark>{m.match}</mark> {m.after}</span>
                </button>
              ))}
            </div>
          ))}
        </div>
      )}
    </ToolPage>
  );
}
