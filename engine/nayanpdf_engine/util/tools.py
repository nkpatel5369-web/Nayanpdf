"""Locate and run bundled command-line engines (Tesseract, LibreOffice,
Ghostscript).

The Electron host passes the exact locations of bundled executables through
environment variables. For development on a workstation the PATH is used as
a fallback. Processes are always started with an argument list (never via a
shell), without a console window, and with a timeout.
"""

from __future__ import annotations

import glob
import os
import shutil
import subprocess
import sys

from ..errors import TOOL_MISSING, EngineError

IS_WIN = sys.platform == "win32"
CREATE_NO_WINDOW = 0x08000000 if IS_WIN else 0


def _env_path(var: str) -> str | None:
    p = os.environ.get(var)
    if p and os.path.exists(p):
        return p
    return None


def tesseract_exe() -> str | None:
    return _env_path("NAYANPDF_TESSERACT") or shutil.which("tesseract")


def tessdata_dir() -> str | None:
    d = _env_path("NAYANPDF_TESSDATA")
    if d:
        return d
    exe = tesseract_exe()
    candidates = []
    if exe:
        base = os.path.dirname(os.path.realpath(exe))
        candidates += [os.path.join(base, "tessdata"), os.path.join(base, "..", "share", "tessdata")]
    candidates += glob.glob("/usr/share/tesseract-ocr/*/tessdata") + ["/usr/share/tessdata", "/usr/local/share/tessdata"]
    for c in candidates:
        if os.path.isdir(c) and glob.glob(os.path.join(c, "*.traineddata")):
            return os.path.abspath(c)
    return None


def ocr_languages() -> list[str]:
    d = tessdata_dir()
    if not d:
        return []
    langs = sorted(os.path.splitext(os.path.basename(p))[0] for p in glob.glob(os.path.join(d, "*.traineddata")))
    return langs


def soffice_exe() -> str | None:
    p = _env_path("NAYANPDF_SOFFICE")
    if p:
        return p
    for name in ("soffice", "libreoffice"):
        w = shutil.which(name)
        if w:
            return w
    return None


def ghostscript_exe() -> str | None:
    p = _env_path("NAYANPDF_GS")
    if p:
        return p
    for name in ("gswin64c", "gs"):
        w = shutil.which(name)
        if w:
            return w
    return None


def resources_dir() -> str:
    d = os.environ.get("NAYANPDF_RESOURCES")
    if d and os.path.isdir(d):
        return d
    return os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "resources"))


def require_tool(path: str | None, name: str) -> str:
    if not path:
        raise EngineError(TOOL_MISSING, f"The {name} component is not installed with this copy of X PDF.")
    return path


def run(args: list[str], timeout: float = 600, cwd: str | None = None, env: dict | None = None,
        input_bytes: bytes | None = None) -> subprocess.CompletedProcess:
    """Run a bundled tool without a shell and without a console window."""
    full_env = os.environ.copy()
    if env:
        full_env.update(env)
    try:
        return subprocess.run(
            args,
            cwd=cwd,
            env=full_env,
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            creationflags=CREATE_NO_WINDOW,
            check=False,
        )
    except subprocess.TimeoutExpired:
        raise EngineError("OPERATION_FAILED", f"{os.path.basename(args[0])} did not finish within {int(timeout)} seconds.")
    except FileNotFoundError:
        raise EngineError(TOOL_MISSING, f"{os.path.basename(args[0])} could not be started.")
