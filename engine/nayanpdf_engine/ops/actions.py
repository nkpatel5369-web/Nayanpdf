"""Saved actions (Acrobat's Action Wizard): a named list of steps run on a file.

Each step is one of the operations below, run on the output of the step
before it. Intermediate files stay in the job's temp folder; only the final
result is written to the chosen output, and only after it reopens cleanly. The
source file is never changed.

Passwords are never part of a saved action: a "protect" step receives its
password when the action is run, and the host does not store it.
"""

from __future__ import annotations

import shutil

from ..errors import EngineError, invalid
from ..registry import METHODS, method
from ..util.io import OutputFile, validate_pdf

# Step op -> (engine method, label, parameters the step may set)
STEPS = {
    "ocr": ("ocr.run", "Recognize text (OCR)", {"languages", "dpi", "skipText", "autoRotate"}),
    "compress": ("compress.run", "Compress", {"preset", "grayscale", "removeMetadata", "removeAttachments", "removeJavaScript"}),
    "watermark": ("stamp.watermark", "Watermark", {"kind", "text", "fontName", "fontSize", "color", "opacity", "angle",
                                                   "position", "offsetX", "offsetY", "pages", "behind", "imagePath", "scale"}),
    "headerFooter": ("stamp.headerFooter", "Header & footer", {"header", "footer", "fontName", "fontSize", "color", "opacity",
                                                               "margin", "pages", "startNumber", "numberFormat", "dateFormat", "skipFirst"}),
    "pageNumbers": ("stamp.pageNumbers", "Page numbers", {"position", "numberFormat", "startNumber", "fontName", "fontSize",
                                                          "color", "margin", "pages", "skipFirst", "custom"}),
    "removeMetadata": ("meta.remove", "Remove document properties", {"fields", "removeXmp"}),
    "setMetadata": ("meta.set", "Set document properties", {"fields"}),
    "flatten": ("annot.flatten", "Flatten comments and fields", {"annotations", "forms"}),
    "rotate": ("pages.rotate", "Rotate pages", {"pages", "angle"}),
    "removeBlankPages": ("", "Remove blank pages", {"sensitivity"}),
    "redactPatterns": ("", "Redact (PAN, Aadhaar, ...)", {"patterns", "terms", "matchCase", "wholeWord", "overlayText"}),
    "pdfa": ("util.pdfa", "Save as PDF/A", {"level"}),
    "protect": ("security.encrypt", "Protect with a password", {"userPassword", "ownerPassword", "encryption", "permissions"}),
}
MAX_STEPS = 20


def check_steps(steps: list) -> list[dict]:
    """Validate a step list; returns it cleaned. Raises on anything not allowed."""
    if not isinstance(steps, list) or not steps:
        raise invalid("The action has no steps.")
    if len(steps) > MAX_STEPS:
        raise invalid(f"An action can have at most {MAX_STEPS} steps.")
    out = []
    for i, s in enumerate(steps):
        op = (s or {}).get("op")
        if op not in STEPS:
            raise invalid(f"Step {i + 1}: unknown step {op!r}.")
        allowed = STEPS[op][2]
        params = {k: v for k, v in s.items() if k in allowed}
        out.append({"op": op, **params})
    ops = [s["op"] for s in out]
    if "protect" in ops and ops.index("protect") != len(ops) - 1:
        raise invalid("\"Protect with a password\" must be the last step: later steps could not open the protected file.")
    if "protect" in ops and "pdfa" in ops:
        raise invalid("A PDF/A file cannot be password protected. Remove one of the two steps.")
    if ops.count("protect") > 1:
        raise invalid("Only one \"Protect with a password\" step is allowed.")
    return out


class _StepContext:
    """The job's context, with each step's progress labelled "Step 2 of 4: OCR"."""

    def __init__(self, ctx, prefix: str):
        self._ctx = ctx
        self._prefix = prefix

    def progress(self, current, total, message=""):
        self._ctx.progress(current, total, f"{self._prefix}: {message}" if message else self._prefix)

    def status(self, message):
        self._ctx.status(f"{self._prefix}: {message}")

    def __getattr__(self, name):
        return getattr(self._ctx, name)


def _blank_pages(ctx, cur, out, password, s):
    found = METHODS["blank.detect"](ctx, path=cur, password=password, **({"sensitivity": s["sensitivity"]} if "sensitivity" in s else {}))
    blanks = [b["page"] if isinstance(b, dict) else int(b) for b in (found.get("blankPages") or [])]
    if not blanks:
        return cur, "no blank pages"
    if len(blanks) >= found.get("pageCount", 0):
        ctx.warn("Every page looked blank, so no page was removed.")
        return cur, "all pages blank; none removed"
    r = METHODS["pages.delete"](ctx, path=cur, pages=",".join(str(p) for p in blanks), output=out, password=password, overwrite=True)
    return r["output"], f"removed page(s) {', '.join(str(p) for p in blanks)}"


def _redact(ctx, cur, out, password, s):
    pats = [p for p in (s.get("patterns") or []) if isinstance(p, str)]
    terms = [t for t in (s.get("terms") or []) if isinstance(t, str) and t.strip()]
    if not pats and not terms:
        raise invalid("The redaction step has nothing to look for.")
    found = METHODS["redact.search"](ctx, path=cur, patterns=pats or None, terms=terms or None,
                                     matchCase=bool(s.get("matchCase")), wholeWord=bool(s.get("wholeWord")), password=password)
    hits = found.get("hits") or []
    if found.get("pagesWithoutText"):
        ctx.warn(f"Redaction could not read text on page(s) {', '.join(str(p) for p in found['pagesWithoutText'][:20])} "
                 "(scanned?). Put an OCR step before it.")
    if not hits:
        return cur, "nothing found to redact"
    r = METHODS["redact.apply"](ctx, path=cur, output=out, areas=[{"page": h["page"], "rect": h["rect"], "text": h["text"]} for h in hits],
                                password=password, overwrite=True, overlayText=str(s.get("overlayText") or ""),
                                verifyTerms=sorted({h["text"] for h in hits}))
    return r["output"], f"{len(hits)} area(s) redacted"


@method("action.run")
def run(ctx, path: str, output: str, steps: list, name: str = "Action", password: str | None = None,
        overwrite: bool = False):
    steps = check_steps(steps)
    cur = path
    cur_password = password
    report = []
    n = len(steps)
    for i, s in enumerate(steps, 1):
        op = s["op"]
        meth, label, _ = STEPS[op]
        sctx = _StepContext(ctx, f"Step {i} of {n}: {label}")
        sctx.progress(i - 1, n)
        step_out = ctx.tmp_path(".pdf")
        params = {k: v for k, v in s.items() if k != "op"}
        try:
            if op == "removeBlankPages":
                cur, note = _blank_pages(sctx, cur, step_out, cur_password, params)
            elif op == "redactPatterns":
                cur, note = _redact(sctx, cur, step_out, cur_password, params)
            else:
                if op == "removeMetadata":
                    params.setdefault("removeXmp", True)
                if op == "ocr":
                    params.setdefault("skipText", True)
                if op == "protect":
                    if not params.get("userPassword") and not params.get("ownerPassword"):
                        raise invalid("Enter the password for the \"Protect with a password\" step when you run the action.")
                    params.setdefault("encryption", "AES-256")
                r = METHODS[meth](sctx, path=cur, output=step_out, password=cur_password, overwrite=True, **params)
                cur = r["output"]
                note = ""
                if op == "compress" and r.get("originalSize") and r.get("finalSize"):
                    note = f"{r['originalSize'] // 1024} KB -> {r['finalSize'] // 1024} KB"
                if op == "protect":
                    cur_password = params.get("userPassword") or params.get("ownerPassword")
        except EngineError as e:
            raise EngineError(e.code, f"Step {i} ({label}) failed: {e.message}", getattr(e, "details", None))
        report.append({"step": i, "op": op, "label": label, "note": note})
    final_out = OutputFile(ctx, output, overwrite, sources=[path])
    shutil.copyfile(cur, final_out.temp)
    final = final_out.commit(validate_pdf(password=cur_password))
    return {"output": final, "name": name, "steps": report}


@method("action.validate")
def validate(ctx, steps: list):
    """Check a step list before it is saved; returns the cleaned steps."""
    return {"steps": check_steps(steps), "labels": {k: v[1] for k, v in STEPS.items()}}
