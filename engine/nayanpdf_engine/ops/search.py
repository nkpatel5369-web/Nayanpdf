"""Search across many PDFs (Acrobat's Advanced Search over a folder).

The host passes the list of files (from a folder the user picked), so the
engine never walks the disk on its own. Text is matched on each page's
extracted text with runs of white space treated as one space, so a phrase
broken across lines is still found. Comments can be searched as well.
"""

from __future__ import annotations

import os
import re

import pymupdf

from ..errors import invalid
from ..registry import method

MAX_FILES = 5000
MAX_RESULTS = 5000


def _pattern(query: str, case_sensitive: bool, whole_word: bool) -> re.Pattern:
    q = " ".join(query.split())
    if not q:
        raise invalid("Type something to search for.")
    if len(q) > 500:
        raise invalid("The search text is too long.")
    body = r"\s+".join(re.escape(w) for w in q.split(" "))
    if whole_word:
        body = rf"(?<!\w){body}(?!\w)"
    return re.compile(body, 0 if case_sensitive else re.IGNORECASE)


def _context(text: str, m: re.Match, width: int) -> dict:
    a, b = m.start(), m.end()
    lo, hi = max(0, a - width), min(len(text), b + width)
    norm = lambda s: " ".join(s.split())  # noqa: E731
    return {"before": ("…" if lo > 0 else "") + norm(text[lo:a]), "match": norm(text[a:b]),
            "after": norm(text[b:hi]) + ("…" if hi < len(text) else "")}


@method("search.files")
def search_files(ctx, paths: list, query: str, caseSensitive: bool = False, wholeWord: bool = False,
                 inComments: bool = False, maxResults: int = 2000, contextChars: int = 60):
    if not isinstance(paths, list) or not paths:
        raise invalid("Choose a folder or files to search.")
    if len(paths) > MAX_FILES:
        raise invalid(f"Too many files ({len(paths)}); search at most {MAX_FILES} at a time.")
    pat = _pattern(str(query), bool(caseSensitive), bool(wholeWord))
    limit = max(1, min(int(maxResults), MAX_RESULTS))
    width = max(10, min(int(contextChars), 200))
    files, skipped = [], []
    scanned_without_text = 0
    total = 0
    truncated = False
    for i, p in enumerate(paths, 1):
        ctx.progress(i, len(paths), os.path.basename(p))
        if truncated:
            break
        try:
            doc = pymupdf.open(p)
        except Exception:  # noqa: BLE001
            skipped.append({"path": p, "reason": "not a readable PDF"})
            continue
        try:
            if doc.needs_pass:
                skipped.append({"path": p, "reason": "password protected"})
                continue
            hits = []
            any_text = False
            for page in doc:
                text = page.get_text("text")
                any_text = any_text or bool(text.strip())
                for m in pat.finditer(text):
                    hits.append({"page": page.number + 1, "where": "text", **_context(text, m, width)})
                    total += 1
                    if total >= limit:
                        truncated = True
                        break
                if truncated:
                    break
                if inComments:
                    for a in page.annots():
                        c = a.info.get("content", "") or ""
                        for m in pat.finditer(c):
                            hits.append({"page": page.number + 1, "where": "comment", **_context(c, m, width)})
                            total += 1
                if truncated:
                    break
            if hits:
                files.append({"path": p, "name": os.path.basename(p), "pages": doc.page_count, "matches": hits})
            elif not doc.page_count:
                skipped.append({"path": p, "reason": "no pages"})
            elif not any_text and not truncated:
                scanned_without_text += 1
                skipped.append({"path": p, "reason": "no text layer (scanned; run OCR to make it searchable)"})
        finally:
            doc.close()
    return {"query": " ".join(str(query).split()), "files": files, "total": total, "searched": len(paths),
            "skipped": skipped, "truncated": truncated, "scannedWithoutText": scanned_without_text}
