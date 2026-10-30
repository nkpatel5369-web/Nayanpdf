// Explorer right-click commands, delivered by the main process (src/main/shell.ts).
// "Convert to PDF": each file becomes a PDF next to it (an existing PDF of that name
// is never overwritten; a number is added) and opens in a tab.
// "Combine files": the Create PDF tool opens with the selected files, in the order
// Explorer passed them, to reorder and combine.
import { api, baseName, dirName, joinPath, stem, waitForJob } from "./api";
import { errorText, useApp } from "./store";
import { openDocument } from "./components/common";

const OFFICE = [".doc", ".docx", ".odt", ".rtf", ".xls", ".xlsx", ".ods", ".csv", ".ppt", ".pptx", ".odp"];
const IMAGES = [".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".gif", ".webp"];
const OPEN_AT_MOST = 5;

function ext(p: string) {
  const m = /\.[^.\\/]+$/.exec(p);
  return m ? m[0].toLowerCase() : "";
}

async function convertOne(path: string): Promise<string> {
  const output = joinPath(dirName(path), stem(path) + ".pdf");
  const e = ext(path);
  if (e === ".htm" || e === ".html") {
    const r: any = await api.htmlToPdf(path, output, {});
    return r.output;
  }
  const [method, params] = OFFICE.includes(e)
    ? ["convert.officeToPdf", { path, output }]
    : IMAGES.includes(e)
      ? ["create.fromImages", { images: [path], output }]
      : ["create.fromText", { path, output }];
  const j = await api.startJob(method, params, `Convert to PDF: ${baseName(path)}`);
  const done = await waitForJob(j.id);
  if (done.status !== "succeeded") throw new Error(done.error?.message || "The conversion did not complete.");
  return done.outputs?.[0] || output;
}

export async function runShellCommand(c: { verb: "convert" | "combine"; files: string[] }) {
  const { toast, setView } = useApp.getState();
  if (!c.files.length) return;
  if (c.verb === "combine") {
    setView({ kind: "tool", tool: "create", args: { files: c.files, from: "explorer", at: Date.now() } });
    return;
  }
  toast({ kind: "info", title: `Converting ${c.files.length} file${c.files.length === 1 ? "" : "s"} to PDF`, body: "Each PDF is saved next to its original." });
  const made: string[] = [];
  const failed: string[] = [];
  for (const f of c.files) {
    try {
      made.push(await convertOne(f));
    } catch (e) {
      failed.push(`${baseName(f)}: ${errorText(e)}`);
    }
  }
  for (const p of made.slice(0, OPEN_AT_MOST)) await openDocument(p);
  if (made.length) {
    toast({ kind: "success", title: `${made.length} PDF${made.length === 1 ? "" : "s"} created`, body: made.length > OPEN_AT_MOST ? `The first ${OPEN_AT_MOST} are open; all are next to their originals.` : made.map(baseName).join(", ") });
  }
  if (failed.length) toast({ kind: "error", title: `${failed.length} file${failed.length === 1 ? "" : "s"} could not be converted`, body: failed.join("\n") });
}
