// Scan to PDF. Pages come from a scanner through Windows Image Acquisition (WIA),
// the scanning interface built into Windows 10 and 11, driven by a fixed
// PowerShell script that ships inside the app; the options are validated here and
// passed as arguments, never pasted into the script. Scanned pages are written to
// the app's temp folder; the PDF itself is made by the engine (create.fromImages,
// with OCR if asked). Network scanners are reached by the Windows WIA service, not
// by X PDF, which makes no network connection of its own.
import { app, BrowserWindow, nativeImage } from "electron";
import { spawn, ChildProcess } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readdirSync, writeFileSync } from "node:fs";
import { extname, join } from "node:path";
import { EngineError } from "./engine";
import { appTempRoot } from "./paths";
import { log } from "./log";

export interface ScanOptions {
  deviceId: string;
  source: "flatbed" | "feeder";
  colour: "color" | "gray" | "bw";
  dpi: number;
}
export interface ScannedPage { path: string; thumb: string; width: number; height: number }

const SCRIPT = String.raw`
param([string]$Action = "list", [string]$DeviceId = "", [string]$Source = "flatbed", [string]$Colour = "color", [int]$Dpi = 300, [string]$OutDir = "", [int]$MaxPages = 300)
$ErrorActionPreference = "Stop"
function Say($o) { [Console]::Out.WriteLine(($o | ConvertTo-Json -Compress -Depth 4)); [Console]::Out.Flush() }
function Hr($e) {
  $x = $e.Exception
  while ($x -and -not ($x -is [System.Runtime.InteropServices.COMException])) { $x = $x.InnerException }
  if ($x) { return ('0x{0:X8}' -f ($x.HResult -band 0xFFFFFFFF)) }
  return ""
}
function SetProp($props, [int]$id, $value) {
  foreach ($p in $props) { if ($p.PropertyID -eq $id) { try { $p.Value = $value; return $true } catch { return $false } } }
  return $false
}
try { $dm = New-Object -ComObject WIA.DeviceManager } catch { Say @{ event = "error"; code = "NO_WIA"; message = "$($_.Exception.Message)" }; exit 0 }
if ($Action -eq "list") {
  $list = @()
  foreach ($d in $dm.DeviceInfos) { if ($d.Type -eq 1) { $list += @{ id = [string]$d.DeviceID; name = [string]$d.Properties.Item("Name").Value } } }
  Say @{ event = "devices"; devices = $list }
  exit 0
}
$info = $null
foreach ($d in $dm.DeviceInfos) { if ($d.Type -eq 1 -and $d.DeviceID -eq $DeviceId) { $info = $d } }
if (-not $info) { Say @{ event = "error"; code = "0x80210015"; message = "scanner not found" }; exit 0 }
try {
  $dev = $info.Connect()
  # WIA_DPS_DOCUMENT_HANDLING_SELECT: 1 = document feeder, 2 = flatbed
  if ($Source -eq "feeder") { [void](SetProp $dev.Properties 3088 1) } else { [void](SetProp $dev.Properties 3088 2) }
  $item = $dev.Items.Item(1)
  # WIA_IPS_CUR_INTENT (1 colour, 2 greyscale, 4 black and white) and resolution
  $intent = @{ color = 1; gray = 2; bw = 4 }[$Colour]
  [void](SetProp $item.Properties 6146 $intent)
  [void](SetProp $item.Properties 6147 $Dpi)
  [void](SetProp $item.Properties 6148 $Dpi)
  $png = "{B96B3CAF-0728-11D3-9D7B-0000F81EF32E}"
  $jpg = "{B96B3CAE-0728-11D3-9D7B-0000F81EF32E}"
  $fmt = if ($Colour -eq "bw") { $png } else { $jpg }
  $ext = if ($Colour -eq "bw") { "png" } else { "jpg" }
  $n = 0
  while ($n -lt $MaxPages) {
    try { $img = $item.Transfer($fmt) }
    catch {
      $h = Hr $_
      # An empty feeder after at least one page is the normal end of a feeder scan.
      if ($Source -eq "feeder" -and $h -eq "0x80210003" -and $n -gt 0) { break }
      Say @{ event = "error"; code = $h; message = "$($_.Exception.Message)"; pages = $n }
      exit 0
    }
    if ($img.FormatID -ne $fmt) {
      $ip = New-Object -ComObject WIA.ImageProcess
      $ip.Filters.Add($ip.FilterInfos.Item("Convert").FilterID)
      $ip.Filters.Item(1).Properties.Item("FormatID").Value = $fmt
      $img = $ip.Apply($img)
    }
    $n++
    $path = Join-Path $OutDir ("page_{0:D3}.{1}" -f $n, $ext)
    if (Test-Path $path) { Remove-Item $path }
    $img.SaveFile($path)
    Say @{ event = "page"; page = $n; path = $path; width = $img.Width; height = $img.Height }
    if ($Source -ne "feeder") { break }
  }
  Say @{ event = "done"; pages = $n }
} catch { Say @{ event = "error"; code = (Hr $_); message = "$($_.Exception.Message)" } }
`;

// WIA error codes (wiadef.h) in the user's words.
const WIA_ERRORS: Record<string, string> = {
  "0x80210001": "The scanner reported a general error. Turn it off and on, then try again.",
  "0x80210002": "Paper is jammed in the scanner.",
  "0x80210003": "The document feeder is empty. Load the pages, or choose Flatbed.",
  "0x80210004": "The scanner has a problem with the paper.",
  "0x80210005": "The scanner is offline. Check that it is switched on and connected.",
  "0x80210006": "The scanner is busy. Wait for it to finish, then try again.",
  "0x80210007": "The scanner is warming up. Try again in a moment.",
  "0x80210008": "The scanner needs attention (see its display or panel).",
  "0x8021000A": "Windows could not communicate with the scanner. Check the cable or network connection.",
  "0x8021000C": "The scanner does not support these settings. Try another resolution or colour mode.",
  "0x8021000D": "The scanner is locked by another program.",
  "0x80210015": "No scanner is connected, or it is switched off.",
  "0x80210016": "The scanner cover is open.",
  "0x80210017": "The scanner lamp is off.",
  "0x80210064": "Scanning was cancelled on the scanner.",
};

const PS = join(process.env.SystemRoot || "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
let current: ChildProcess | null = null;

/** Development test runs only: a folder of page images stands in for a scanner. */
function simulated(): string | null {
  const d = process.env.NAYANPDF_TEST_SCANNER;
  return !app.isPackaged && d && existsSync(d) ? d : null;
}

function scriptPath(): string {
  const dir = join(appTempRoot(), "scan");
  mkdirSync(dir, { recursive: true });
  const p = join(dir, "wia-scan.ps1");
  writeFileSync(p, SCRIPT, "utf8");
  return p;
}

/** Runs the script; resolves with what it wrote to stderr (empty when all went well). */
function run(args: string[], onEvent: (e: any) => void, timeoutMs: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = spawn(PS, ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", scriptPath(), ...args], { windowsHide: true });
    current = child;
    let buf = "";
    let err = "";
    const t = setTimeout(() => child.kill(), timeoutMs);
    child.stdout.on("data", (d) => {
      buf += d.toString("utf8");
      let i;
      while ((i = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line.startsWith("{")) continue;
        try {
          onEvent(JSON.parse(line));
        } catch {
          /* not one of ours */
        }
      }
    });
    child.stderr.on("data", (d) => (err += d.toString("utf8")));
    child.on("error", (e) => {
      clearTimeout(t);
      current = null;
      reject(new EngineError("TOOL_MISSING", `Windows PowerShell could not be started for scanning: ${e.message}`));
    });
    child.on("close", (code) => {
      clearTimeout(t);
      current = null;
      if (code !== 0 && err.trim()) log.warn(`scan script exit ${code}: ${err.trim().slice(0, 300)}`);
      resolve(err);
    });
  });
}

function noAnswer(stderr: string): string {
  if (/execution policy|running scripts is disabled|cannot be loaded because/i.test(stderr)) {
    return "This computer's PowerShell policy (often set by IT) blocks the scanning script. Ask IT to allow it, or scan to a folder on the device and use Create PDF > Images with OCR.";
  }
  if (/language mode|ConstrainedLanguage|Cannot create type/i.test(stderr)) {
    return "This computer's security settings do not allow scanning from apps through PowerShell. Scan to a folder on the device and use Create PDF > Images with OCR.";
  }
  return "Windows did not answer the request for scanners. Try again.";
}

function wiaError(e: { code?: string; message?: string }): EngineError {
  if (e.code === "NO_WIA") {
    return new EngineError("NOT_SUPPORTED", "Windows Image Acquisition (scanning) is not available on this computer. Scan to a folder on the device instead, then use Create PDF > Images with OCR.");
  }
  const known = e.code ? WIA_ERRORS[e.code.toUpperCase().replace("0X", "0x")] : undefined;
  return new EngineError("SCAN_FAILED", known || `The scanner reported an error${e.code ? ` (${e.code})` : ""}: ${e.message || "unknown"}`);
}

export async function listScanners(): Promise<{ available: boolean; reason?: string; devices: { id: string; name: string }[] }> {
  if (simulated()) return { available: true, devices: [{ id: "simulated", name: "Test scanner (simulated)" }] };
  if (process.platform !== "win32") return { available: false, reason: "Scanning uses Windows Image Acquisition and works on Windows.", devices: [] };
  let result: any = null;
  const err = await run(["-Action", "list"], (e) => (result = e), 60_000);
  if (!result) return { available: false, reason: noAnswer(err), devices: [] };
  if (result.event === "error") return { available: false, reason: wiaError(result).message, devices: [] };
  const devices = Array.isArray(result.devices) ? result.devices : result.devices ? [result.devices] : [];
  return { available: true, devices: devices.map((d: any) => ({ id: String(d.id), name: String(d.name || "Scanner") })) };
}

function thumbOf(path: string): { thumb: string; width: number; height: number } {
  const img = nativeImage.createFromPath(path);
  const { width, height } = img.getSize();
  return { thumb: img.isEmpty() ? "" : img.resize({ width: 160 }).toDataURL(), width, height };
}

export async function scan(o: ScanOptions, win: BrowserWindow | null): Promise<{ pages: ScannedPage[] }> {
  if (current) throw new EngineError("BUSY", "A scan is already running.");
  const dir = join(appTempRoot(), "scan", `s${Date.now().toString(36)}`);
  mkdirSync(dir, { recursive: true });
  const pages: ScannedPage[] = [];
  const add = (path: string) => {
    const p = { path, ...thumbOf(path) };
    pages.push(p);
    if (win && !win.isDestroyed()) win.webContents.send("scan:page", { page: pages.length, thumb: p.thumb });
  };
  const sim = simulated();
  if (sim) {
    const files = readdirSync(sim).filter((f) => /\.(png|jpe?g)$/i.test(f)).sort();
    for (const [i, f] of (o.source === "feeder" ? files : files.slice(0, 1)).entries()) {
      const dest = join(dir, `page_${String(i + 1).padStart(3, "0")}${extname(f).toLowerCase()}`);
      copyFileSync(join(sim, f), dest);
      add(dest);
    }
    return { pages };
  }
  if (process.platform !== "win32") throw new EngineError("NOT_SUPPORTED", "Scanning uses Windows Image Acquisition and works on Windows.");
  let failure: any = null;
  const err = await run(
    ["-Action", "scan", "-DeviceId", o.deviceId, "-Source", o.source, "-Colour", o.colour, "-Dpi", String(o.dpi), "-OutDir", dir],
    (e) => {
      if (e.event === "page" && typeof e.path === "string" && e.path.startsWith(dir) && existsSync(e.path)) add(e.path);
      else if (e.event === "error") failure = e;
    },
    30 * 60_000,
  );
  // Pages scanned before an error (a jam halfway through the feeder) are kept.
  if (failure && !pages.length) throw wiaError(failure);
  if (!failure && !pages.length && err.trim()) throw new EngineError("SCAN_FAILED", noAnswer(err));
  if (failure) log.warn(`scan stopped after ${pages.length} page(s): ${failure.code}`);
  return { pages };
}

export function cancelScan(): void {
  current?.kill();
}
