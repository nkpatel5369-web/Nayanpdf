"""JSON-lines request server.

Protocol (one JSON object per line):
  request  -> {"id": 1, "method": "pages.merge", "params": {...}}
  response <- {"id": 1, "result": {...}}            on success
  response <- {"id": 1, "error": {code, message}}    on failure
  event    <- {"id": 1, "event": "progress", ...}    zero or more before the response

stdout is reserved for the protocol. Anything a library prints is redirected
to stderr so it cannot corrupt the stream.
"""

from __future__ import annotations

import io
import json
import sys
import threading
import traceback

from . import __version__
from .errors import EngineError
from .registry import METHODS, Context, dumps, log


def _load_ops() -> None:
    # Importing registers every @method.
    from .ops import (  # noqa: F401
        actions,
        annotate,
        compare,
        compress,
        convert,
        create,
        edit,
        formdesign,
        forms,
        info,
        links,
        metadata,
        ocr,
        pages,
        redact,
        review,
        search,
        security,
        selftest,
        sign,
        stamp,
        utilities,
    )


class Server:
    def __init__(self, out_stream):
        self._out = out_stream
        self._lock = threading.Lock()

    def emit(self, obj: dict) -> None:
        line = dumps(obj)
        with self._lock:
            self._out.write(line + "\n")
            self._out.flush()

    def handle(self, raw: str) -> None:
        try:
            req = json.loads(raw)
        except json.JSONDecodeError:
            self.emit({"id": None, "error": {"code": "BAD_REQUEST", "message": "Malformed request"}})
            return
        rid = req.get("id")
        name = req.get("method")
        params = req.get("params") or {}
        fn = METHODS.get(name)
        if fn is None:
            self.emit({"id": rid, "error": {"code": "UNKNOWN_METHOD", "message": f"Unknown method: {name}"}})
            return
        if not isinstance(params, dict):
            self.emit({"id": rid, "error": {"code": "BAD_REQUEST", "message": "params must be an object"}})
            return
        ctx = Context(rid, self.emit)
        try:
            result = fn(ctx, **params)
            if result is None:
                result = {}
            if isinstance(result, dict) and ctx.warnings:
                result.setdefault("warnings", [])
                for w in ctx.warnings:
                    if w not in result["warnings"]:
                        result["warnings"].append(w)
            self.emit({"id": rid, "result": result})
        except EngineError as e:
            self.emit({"id": rid, "error": e.to_dict()})
        except TypeError as e:
            # Usually a bad/missing parameter from the caller.
            log("TypeError in", name, repr(e))
            self.emit({"id": rid, "error": {"code": "INVALID_ARGUMENT",
                                            "message": f"Invalid parameters for {name}: {e}"}})
        except MemoryError:
            self.emit({"id": rid, "error": {"code": "OPERATION_FAILED",
                                            "message": "The operation ran out of memory."}})
        except OSError as e:
            from .util.io import os_error_to_engine

            ee = os_error_to_engine(e)
            self.emit({"id": rid, "error": ee.to_dict()})
        except Exception as e:  # noqa: BLE001 - report everything, never crash the worker
            log("Unhandled error in", name, type(e).__name__)
            traceback.print_exc(file=sys.stderr)
            self.emit({"id": rid, "error": {"code": "OPERATION_FAILED",
                                            "message": f"{type(e).__name__}: {e}"[:500]}})
        finally:
            ctx.cleanup()


def main() -> int:
    # Keep the real stdout for the protocol; send stray prints to stderr.
    proto_out = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", newline="\n", line_buffering=False)
    sys.stdout = sys.stderr
    stdin = io.TextIOWrapper(sys.stdin.buffer, encoding="utf-8")

    _load_ops()
    server = Server(proto_out)
    server.emit({"id": None, "event": "ready", "version": __version__, "methods": sorted(METHODS)})
    for raw in stdin:
        raw = raw.strip()
        if not raw:
            continue
        if raw == '"__exit__"':
            break
        server.handle(raw)
    return 0


def call(name: str, **params):
    """In-process call used by tests and the CLI."""
    _load_ops()
    fn = METHODS[name]
    ctx = Context("local")
    try:
        result = fn(ctx, **params)
        if isinstance(result, dict) and ctx.warnings:
            result.setdefault("warnings", []).extend(w for w in ctx.warnings if w not in result.get("warnings", []))
        return result
    finally:
        ctx.cleanup()
