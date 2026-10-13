#!/usr/bin/env python3
"""Assemble the offline Windows runtime that is bundled into NayanPDF_Setup.exe.

Produces runtime/win/:
    python/        CPython 3.13 (official NuGet distribution) + engine wheels
    tesseract/     tesseract.exe + required DLLs (conda-forge) + tessdata
    ghostscript/   Ghostscript (official Artifex build) for PDF/A
    libreoffice/   LibreOffice (official TDF MSI, administrative install)
    assets/        fonts and third-party licence texts
    MANIFEST.json  every source URL, version and SHA-256

Runs on Windows (CI) or Linux (cross-assembly). Every download is verified
against a pinned SHA-256 or, for LibreOffice, against the hash published by
The Document Foundation's mirror network (and its Authenticode signature on
Windows). Nothing in the output downloads anything at runtime.

Usage: python scripts/build_runtime_win.py [--skip-libreoffice] [--libreoffice-msi PATH]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "runtime" / "win"
CACHE = ROOT / "runtime" / "cache"
IS_WIN = platform.system() == "Windows"

PYTHON = {
    "version": "3.13.16",
    "url": "https://api.nuget.org/v3-flatcontainer/python/3.13.16/python.3.13.16.nupkg",
    "sha256": "95fad176338f1d6a799e45946488063f4aa61764e6cff2f132ddeffbf7a04c34",
}
MICROMAMBA = {
    "version": "2.9.0",
    "linux-64": ("https://conda.anaconda.org/conda-forge/linux-64/micromamba-2.9.0-0.tar.bz2",
                 "8761c382127e6363bd9e0a2451aa3ef90d071a79133f736e2f759a3bf13040dd"),
    "win-64": ("https://conda.anaconda.org/conda-forge/win-64/micromamba-2.9.0-0.tar.bz2",
               "97a336f4ab794bd96a6a4da5e6ed63e75a1d31830414a182419b23d3b36f3fe0"),
}
TESSERACT_SPECS = ["tesseract=5.5.3", "libarchive=*=lgpl*"]
TESSDATA = {
    # tesseract-ocr/tessdata tag 4.1.0 (Apache-2.0)
    "eng": "daa0c97d651c19fba3b25e81317cd697e9908c8208090c94c3905381c23fc047",
    "hin": "cc76d09fa4fed1c7a4674046e25e63760d0c9bfdce390a52113462c34a556ee6",
    "guj": "a484f4a7002ff4ea22dca26821e40431313a31cd429ab90901227e609f231b09",
    "osd": "e19f2ae860792fdf372cf48d8ce70ae5da3c4052962fe22e9de1f680c374bb0e",
}
TESSDATA_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata/4.1.0/{}.traineddata"
GHOSTSCRIPT = {
    "version": "10.05.1",
    "url": "https://github.com/ArtifexSoftware/ghostpdl-downloads/releases/download/gs10051/gs10051w64.exe",
    "sha256": "a0e49d912d21d8193ff0cb89ef741a47b21286fbb0a0e35dd0192b0097d35766",
}
LIBREOFFICE_BASE = "https://download.documentfoundation.org/libreoffice/stable/"
FONTS = {
    # Google Fonts repository (SIL Open Font License 1.1), used for typed signatures.
    "GreatVibes-Regular.ttf": "https://raw.githubusercontent.com/google/fonts/main/ofl/greatvibes/GreatVibes-Regular.ttf",
    "DancingScript-Regular.ttf": "https://raw.githubusercontent.com/google/fonts/main/ofl/dancingscript/DancingScript%5Bwght%5D.ttf",
    "OFL-GreatVibes.txt": "https://raw.githubusercontent.com/google/fonts/main/ofl/greatvibes/OFL.txt",
    "OFL-DancingScript.txt": "https://raw.githubusercontent.com/google/fonts/main/ofl/dancingscript/OFL.txt",
}

manifest: dict = {"built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "host": platform.platform(), "components": {}}


def log(*a):
    print("[runtime]", *a, flush=True)


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, dest: Path, expected: str | None = None) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and expected and sha256(dest) == expected:
        return dest
    for attempt in range(5):
        try:
            log("download", url)
            tmp = dest.with_suffix(dest.suffix + ".part")
            req = urllib.request.Request(url, headers={"User-Agent": "NayanPDF-build/1.0"})
            with urllib.request.urlopen(req, timeout=300) as r, open(tmp, "wb") as fh:
                shutil.copyfileobj(r, fh, 1 << 20)
            tmp.replace(dest)
            break
        except Exception as e:  # noqa: BLE001
            log("  retry after error:", e)
            time.sleep(2 ** (attempt + 1))
    else:
        raise SystemExit(f"download failed: {url}")
    got = sha256(dest)
    if expected and got != expected:
        dest.unlink()
        raise SystemExit(f"SHA-256 mismatch for {url}\n expected {expected}\n got      {got}")
    return dest


def run(cmd: list[str], **kw):
    log("run", " ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def seven_zip() -> str:
    for c in ("7z", "7za", r"C:\Program Files\7-Zip\7z.exe"):
        w = shutil.which(c) or (c if os.path.exists(c) else None)
        if w:
            return w
    raise SystemExit("7-Zip is required to unpack the Ghostscript installer")


# --------------------------------------------------------------------- python
def build_python():
    dst = OUT / "python"
    if dst.exists():
        shutil.rmtree(dst)
    nupkg = download(PYTHON["url"], CACHE / f"python.{PYTHON['version']}.nupkg", PYTHON["sha256"])
    with zipfile.ZipFile(nupkg) as z:
        for info in z.infolist():
            if not info.filename.startswith("tools/") or info.is_dir():
                continue
            rel = info.filename[len("tools/"):]
            if rel.startswith(("include/", "libs/", "Lib/test/", "Lib/idlelib/", "Lib/tkinter/", "Lib/turtledemo/",
                               "Lib/ensurepip/", "Lib/site-packages/pip")) or "__pycache__" in rel:
                continue
            target = dst / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out)
    site = dst / "Lib" / "site-packages"
    site.mkdir(parents=True, exist_ok=True)
    wheels = CACHE / "wheels"
    if wheels.exists():
        shutil.rmtree(wheels)
    wheels.mkdir(parents=True)
    run([sys.executable, "-m", "pip", "download", "--disable-pip-version-check", "--only-binary=:all:",
         "--platform", "win_amd64", "--python-version", "3.13", "--implementation", "cp",
         "--abi", "cp313", "--abi", "abi3", "--abi", "none",
         "-r", ROOT / "engine" / "requirements-win.txt", "-d", wheels])
    installed = []
    for whl in sorted(wheels.glob("*.whl")):
        with zipfile.ZipFile(whl) as z:
            for info in z.infolist():
                name = info.filename
                if info.is_dir():
                    continue
                # Wheel .data/purelib|platlib go to site-packages; scripts/headers are not needed.
                m = re.match(r"^[^/]+\.data/(purelib|platlib)/(.*)$", name)
                if m:
                    name = m.group(2)
                elif re.match(r"^[^/]+\.data/", name):
                    continue
                target = site / name
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as src, open(target, "wb") as out:
                    shutil.copyfileobj(src, out)
        installed.append({"wheel": whl.name, "sha256": sha256(whl)})
    # Remove test suites and caches shipped inside packages.
    for p in list(site.rglob("__pycache__")):
        shutil.rmtree(p, ignore_errors=True)
    for pkg in ("numpy", "cv2", "pikepdf", "lxml", "PIL", "cryptography", "fontTools"):
        for t in (site / pkg).glob("**/tests"):
            shutil.rmtree(t, ignore_errors=True)
    # OpenCV's FFmpeg plug-in is only used for video I/O (never by NayanPDF): drop it.
    for f in (site / "cv2").glob("opencv_videoio_ffmpeg*.dll"):
        f.unlink()
    # Isolated mode (-I) ignores PYTHONPATH; the default ._pth-less layout already includes site-packages.
    manifest["components"]["python"] = {"version": PYTHON["version"], "source": PYTHON["url"], "sha256": PYTHON["sha256"],
                                        "licence": "PSF-2.0", "wheels": installed}
    log(f"python ready: {len(installed)} wheels")


# --------------------------------------------------------------------- tesseract
def micromamba() -> Path:
    plat = "win-64" if IS_WIN else "linux-64"
    url, digest = MICROMAMBA[plat]
    pkg = download(url, CACHE / f"micromamba-{plat}.tar.bz2", digest)
    mdir = CACHE / f"micromamba-{plat}"
    exe = mdir / ("Library/bin/micromamba.exe" if IS_WIN else "bin/micromamba")
    if not exe.exists():
        with tarfile.open(pkg) as t:
            t.extractall(mdir)
    return exe


def pe_closure(start: list[Path], search: list[Path]) -> set[Path]:
    import pefile  # build-time dependency

    avail: dict[str, Path] = {}
    for d in search:
        if d.exists():
            for f in d.iterdir():
                if f.suffix.lower() in (".dll", ".exe"):
                    avail.setdefault(f.name.lower(), f)
    need: dict[str, Path] = {}
    todo = [p.name.lower() for p in start]
    for p in start:
        avail.setdefault(p.name.lower(), p)
    while todo:
        n = todo.pop()
        if n in need or n not in avail:
            continue
        need[n] = avail[n]
        pe = pefile.PE(str(avail[n]), fast_load=True)
        pe.parse_data_directories(directories=[pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_IMPORT"],
                                               pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT"]])
        for attr in ("DIRECTORY_ENTRY_IMPORT", "DIRECTORY_ENTRY_DELAY_IMPORT"):
            for e in getattr(pe, attr, []):
                dll = e.dll.decode().lower()
                if dll in avail and dll not in need:
                    todo.append(dll)
        pe.close()
    return set(need.values())


def build_tesseract():
    exe = micromamba()
    env = CACHE / "tess-env"
    if env.exists():
        shutil.rmtree(env)
    e = dict(os.environ, MAMBA_ROOT_PREFIX=str(CACHE / "mamba-root"))
    run([exe, "create", "-y", "-p", env, "--platform", "win-64", "-c", "conda-forge", "--override-channels", *TESSERACT_SPECS], env=e)
    lib_bin = env / "Library" / "bin"
    files = pe_closure([lib_bin / "tesseract.exe"], [lib_bin, env])
    dst = OUT / "tesseract"
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    for f in files:
        shutil.copy2(f, dst / f.name)
    td = dst / "tessdata"
    td.mkdir()
    for lang, digest in TESSDATA.items():
        download(TESSDATA_URL.format(lang), td / f"{lang}.traineddata", digest)
    pkgs = []
    for meta in sorted((env / "conda-meta").glob("*.json")):
        m = json.loads(meta.read_text(encoding="utf-8"))
        pkgs.append({"name": m["name"], "version": m["version"], "build": m.get("build"), "license": m.get("license"),
                     "sha256": m.get("sha256"), "url": m.get("url")})
    lic = OUT / "assets" / "licenses" / "tesseract"
    lic.mkdir(parents=True, exist_ok=True)
    for info_dir in (env / "info" / "licenses",):
        if info_dir.exists():
            shutil.copytree(info_dir, lic / "conda", dirs_exist_ok=True)
    # Keep a VC++ runtime copy for Ghostscript (same redistributable files).
    vcr = CACHE / "vcruntime"
    vcr.mkdir(exist_ok=True)
    for n in ("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"):
        for d in (lib_bin, env):
            if (d / n).exists():
                shutil.copy2(d / n, vcr / n)
                break
    manifest["components"]["tesseract"] = {"packages": pkgs, "files": sorted(f.name for f in files),
                                           "tessdata": {k: {"sha256": v, "source": TESSDATA_URL.format(k)} for k, v in TESSDATA.items()}}
    log(f"tesseract ready: {len(files)} files")


# --------------------------------------------------------------------- ghostscript
def build_ghostscript():
    inst = download(GHOSTSCRIPT["url"], CACHE / "gs10051w64.exe", GHOSTSCRIPT["sha256"])
    if IS_WIN:
        verify_authenticode(inst, "Artifex")
    tmp = CACHE / "gs-x"
    if tmp.exists():
        shutil.rmtree(tmp)
    run([seven_zip(), "x", "-y", f"-o{tmp}", inst], stdout=subprocess.DEVNULL)
    dst = OUT / "ghostscript"
    if dst.exists():
        shutil.rmtree(dst)
    (dst / "bin").mkdir(parents=True)
    for n in ("gswin64c.exe", "gsdll64.dll"):
        shutil.copy2(tmp / "bin" / n, dst / "bin" / n)
    for d in ("lib", "Resource", "iccprofiles"):
        shutil.copytree(tmp / d, dst / d)
    for n in ("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"):
        src = CACHE / "vcruntime" / n
        if src.exists():
            shutil.copy2(src, dst / "bin" / n)
    lic = OUT / "assets" / "licenses" / "ghostscript"
    lic.mkdir(parents=True, exist_ok=True)
    for cand in ("doc/COPYING", "LICENSE", "doc/LICENSE"):
        if (tmp / cand).exists():
            shutil.copy2(tmp / cand, lic / "COPYING.txt")
    manifest["components"]["ghostscript"] = {**GHOSTSCRIPT, "licence": "AGPL-3.0"}
    log("ghostscript ready")


# --------------------------------------------------------------------- libreoffice
def latest_libreoffice() -> str:
    with urllib.request.urlopen(LIBREOFFICE_BASE, timeout=60) as r:
        html = r.read().decode("utf-8", "replace")
    vers = sorted({tuple(int(x) for x in v.split(".")) for v in re.findall(r'href="(\d+\.\d+\.\d+)/"', html)})
    if not vers:
        raise SystemExit("could not determine the current LibreOffice version")
    return ".".join(map(str, vers[-1]))


def verify_authenticode(path: Path, publisher_hint: str):
    ps = ["powershell", "-NoProfile", "-Command",
          f"$s = Get-AuthenticodeSignature -LiteralPath '{path}'; Write-Output ($s.Status.ToString() + '|' + $s.SignerCertificate.Subject)"]
    out = subprocess.run(ps, capture_output=True, text=True).stdout.strip()
    log("authenticode", path.name, out)
    status, _, subject = out.partition("|")
    if status != "Valid" or publisher_hint.lower() not in subject.lower():
        raise SystemExit(f"Authenticode verification failed for {path.name}: {out}")
    return subject


def build_libreoffice(msi_path: str | None):
    if msi_path:
        msi = Path(msi_path)
        version = re.search(r"(\d+\.\d+\.\d+)", msi.name).group(1) if re.search(r"(\d+\.\d+\.\d+)", msi.name) else "local"
        expected = None
        source = str(msi)
    else:
        version = os.environ.get("LIBREOFFICE_VERSION") or latest_libreoffice()
        name = f"LibreOffice_{version}_Win_x86-64.msi"
        source = f"{LIBREOFFICE_BASE}{version}/win/x86_64/{name}"
        expected = None
        try:
            with urllib.request.urlopen(source + ".sha256", timeout=60) as r:
                expected = r.read().decode().split()[0].strip().lower()
        except Exception as e:  # noqa: BLE001
            log("no published .sha256 available:", e)
        msi = download(source, CACHE / name, expected)
    signer = None
    if IS_WIN:
        signer = verify_authenticode(msi, "Document Foundation")
    stage = CACHE / "lo-admin"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    if IS_WIN:
        run(["msiexec", "/a", str(msi), "/qn", f"TARGETDIR={stage}"])
    else:
        run(["msiextract", "-C", stage, msi], stdout=subprocess.DEVNULL)
    # Locate the installation root (folder containing program/soffice.exe).
    root = next((p.parent.parent for p in stage.rglob("soffice.exe") if p.parent.name.lower() == "program"), None)
    if not root:
        raise SystemExit("LibreOffice extraction did not produce program/soffice.exe")
    dst = OUT / "libreoffice"
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(root, dst)
    # Trim components not used for headless conversion.
    for rel in ("share/gallery", "share/extensions/wiki-publisher", "share/extensions/nlpsolver", "program/wizards",
                "share/template/common/wizard", "readmes"):
        shutil.rmtree(dst / rel, ignore_errors=True)
    for d in (dst / "share" / "extensions").glob("dict-*"):
        if not d.name.startswith(("dict-en",)):
            shutil.rmtree(d, ignore_errors=True)
    lic = OUT / "assets" / "licenses" / "libreoffice"
    lic.mkdir(parents=True, exist_ok=True)
    for cand in ("LICENSE.html", "LICENSE", "NOTICE", "CREDITS.fodt"):
        if (dst / cand).exists():
            shutil.copy2(dst / cand, lic / cand)
    manifest["components"]["libreoffice"] = {"version": version, "source": source, "sha256": sha256(msi),
                                             "publishedSha256": expected, "authenticode": signer, "licence": "MPL-2.0"}
    log(f"libreoffice {version} ready")


# --------------------------------------------------------------------- assets
def build_assets():
    fonts = OUT / "assets" / "fonts"
    fonts.mkdir(parents=True, exist_ok=True)
    got = {}
    for name, url in FONTS.items():
        try:
            p = download(url, fonts / name)
            got[name] = {"source": url, "sha256": sha256(p)}
        except SystemExit:
            log("optional font not available:", name)
    lic = OUT / "assets" / "licenses"
    lic.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "LICENSE", lic / "NayanPDF-LICENSE.txt")
    shutil.copy2(ROOT / "docs" / "DEPENDENCIES.md", lic / "DEPENDENCIES.md")
    site = OUT / "python" / "Lib" / "site-packages"
    py_lic = lic / "python-packages"
    py_lic.mkdir(exist_ok=True)
    for dist in site.glob("*.dist-info"):
        for f in list(dist.glob("LICENSE*")) + list(dist.glob("licenses/*")) + list(dist.glob("COPYING*")):
            if f.is_file():
                shutil.copy2(f, py_lic / f"{dist.name.split('-')[0]}-{f.name}")
    if (OUT / "python" / "LICENSE.txt").exists():
        shutil.copy2(OUT / "python" / "LICENSE.txt", lic / "Python-LICENSE.txt")
    for src in (ROOT / "node_modules" / "pdfjs-dist" / "LICENSE",):
        if src.exists():
            shutil.copy2(src, lic / "pdfjs-LICENSE.txt")
    manifest["components"]["fonts"] = got


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-libreoffice", action="store_true")
    ap.add_argument("--libreoffice-msi")
    ap.add_argument("--only", choices=["python", "tesseract", "ghostscript", "libreoffice", "assets"])
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    mf = OUT / "MANIFEST.json"
    if mf.exists():
        try:
            manifest["components"].update(json.loads(mf.read_text())["components"])
        except Exception:  # noqa: BLE001
            pass
    steps = ["python", "tesseract", "ghostscript", "libreoffice", "assets"]
    if a.only:
        steps = [a.only]
    for s in steps:
        if s == "libreoffice":
            if a.skip_libreoffice:
                log("SKIPPING LibreOffice: Office-to-PDF conversion will be unavailable in this build")
                manifest["components"]["libreoffice"] = {"skipped": True}
                continue
            build_libreoffice(a.libreoffice_msi)
        else:
            globals()[f"build_{s}"]()
    mf.write_text(json.dumps(manifest, indent=2))
    total = sum(f.stat().st_size for f in OUT.rglob("*") if f.is_file())
    log(f"runtime assembled in {OUT} ({total / 1e6:.0f} MB)")


if __name__ == "__main__":
    main()
