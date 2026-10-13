"""Method registry and per-request context.

Only functions decorated with ``@method`` are reachable from the UI. The
server rejects any other method name, so the renderer can never ask the
engine to run arbitrary code.
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
from typing import Any, Callable

METHODS: dict[str, Callable[..., Any]] = {}


def method(name: str):
    def deco(fn):
        if name in METHODS:
            raise RuntimeError(f"duplicate engine method {name}")
        METHODS[name] = fn
        return fn

    return deco


class Context:
    """Passed to every handler. Emits progress and tracks temp files."""

    def __init__(self, request_id: Any, emit: Callable[[dict], None] | None = None):
        self.request_id = request_id
        self._emit = emit
        self.warnings: list[str] = []
        self._tmp_root = os.environ.get("NAYANPDF_JOB_TMP") or tempfile.gettempdir()
        self._tmpdir: str | None = None
        self._lock = threading.Lock()

    # ---- progress -------------------------------------------------------
    def progress(self, current: int, total: int, message: str = "") -> None:
        """Report measurable progress (e.g. pages processed of total)."""
        if self._emit:
            self._emit({"id": self.request_id, "event": "progress", "current": int(current),
                        "total": int(total), "message": message})

    def status(self, message: str) -> None:
        """Report a phase change where no reliable percentage exists."""
        if self._emit:
            self._emit({"id": self.request_id, "event": "status", "message": message})

    def partial(self, path: str) -> None:
        """Tell the host about a partial output so it can be removed if the
        job is cancelled mid-write."""
        if self._emit:
            self._emit({"id": self.request_id, "event": "partial", "path": path})

    def warn(self, message: str) -> None:
        if message not in self.warnings:
            self.warnings.append(message)

    # ---- temp files -----------------------------------------------------
    @property
    def tmpdir(self) -> str:
        with self._lock:
            if self._tmpdir is None:
                os.makedirs(self._tmp_root, exist_ok=True)
                self._tmpdir = tempfile.mkdtemp(prefix="job-", dir=self._tmp_root)
            return self._tmpdir

    def tmp_path(self, suffix: str = "") -> str:
        fd, path = tempfile.mkstemp(suffix=suffix, dir=self.tmpdir)
        os.close(fd)
        return path

    def cleanup(self) -> None:
        if self._tmpdir and os.path.isdir(self._tmpdir):
            import shutil

            shutil.rmtree(self._tmpdir, ignore_errors=True)
            self._tmpdir = None


def log(*parts) -> None:
    """Diagnostic log to stderr. Never pass document text or passwords."""
    print("[engine]", *parts, file=sys.stderr, flush=True)


def dumps(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, default=_json_default)


def _json_default(o):
    if isinstance(o, (set, tuple)):
        return list(o)
    if hasattr(o, "__float__"):
        return float(o)
    return str(o)
