"""Generates the app's 24dp vector icons (Material Design shapes, Apache-2.0) and
checks every path's syntax, so a malformed path cannot crash the app at runtime.
Run: python android/tools/make_icons.py"""
import os
import re

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable")

FILL = {
    "ic_search": "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
    "ic_undo": "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
    "ic_redo": "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z",
    "ic_more": "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
    "ic_close": "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
    "ic_check": "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z",
    "ic_delete": "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z",
    "ic_edit": "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z",
    "ic_share": "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92 1.61,0 2.92,-1.31 2.92,-2.92s-1.31,-2.92 -2.92,-2.92z",
    "ic_comment": "M21.99,4c0,-1.1 -0.89,-2 -1.99,-2H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h14l4,4 -0.01,-18zM18,14H6v-2h12v2zM18,11H6V9h12v2zM18,8H6V6h12v2z",
    "ic_open": "M20,6h-8l-2,-2H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2zM20,18H4V8h16v10z",
    "ic_add": "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
    "ic_back": "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z",
    "ic_up": "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z",
    "ic_down": "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z",
    "ic_lock": "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
    "ic_print": "M19,8H5c-1.66,0 -3,1.34 -3,3v6h4v4h12v-4h4v-6c0,-1.66 -1.34,-3 -3,-3zM16,19H8v-5h8v5zM19,12c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1zM18,3H6v4h12V3z",
    "ic_camera": "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,8.8c-1.77,0 -3.2,1.43 -3.2,3.2s1.43,3.2 3.2,3.2 3.2,-1.43 3.2,-3.2 -1.43,-3.2 -3.2,-3.2z",
    "ic_image": "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
    "ic_volume": "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
    "ic_save": "M17,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V7l-4,-4zM12,19c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM15,9H5V5h10v4z",
    "ic_play": "M8,5v14l11,-7z",
    "ic_pause": "M6,19h4V5H6v14zM14,5v14h4V5h-4z",
    "ic_stop": "M6,6h12v12H6z",
    "ic_info": "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z",
    "ic_list": "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z",
    "ic_pages": "M3,3h8v8H3zM13,3h8v8h-8zM3,13h8v8H3zM13,13h8v8h-8z",
    "ic_rotate": "M15.55,5.55L11,1v3.07C7.06,4.56 4,7.92 4,12s3.05,7.44 7,7.93v-2.02c-2.84,-0.48 -5,-2.94 -5,-5.91s2.16,-5.43 5,-5.91V10l4.55,-4.45zM19.93,11c-0.17,-1.39 -0.72,-2.73 -1.62,-3.89l-1.42,1.42c0.54,0.75 0.88,1.6 1.02,2.47h2.02zM13,17.9v2.02c1.39,-0.17 2.74,-0.71 3.9,-1.61l-1.44,-1.44c-0.75,0.54 -1.59,0.89 -2.46,1.03zM16.89,15.48l1.42,1.41c0.9,-1.16 1.45,-2.5 1.62,-3.89h-2.02c-0.14,0.87 -0.48,1.72 -1.02,2.48z",
}

# Simple stroked shapes (round caps), drawn here so their geometry is exact.
STROKE = {
    "ic_highlight": ["M4,20 L20,20", "M7,16 L15,4 L19,7 L11,19 Z"],
    "ic_underline": ["M7,4 L7,11 A5,5 0 0 0 17,11 L17,4", "M5,20 L19,20"],
    "ic_strike": ["M17,6.5 C16,4.8 14,4 12,4 C9,4 7,5.6 7,8 C7,9.6 8,10.6 9.5,11", "M4,12 L20,12", "M14.5,13 C16,13.8 17,15 17,16.5 C17,19 14.8,20 12,20 C9.8,20 7.8,19.2 7,17.5"],
    "ic_note": ["M5,4 L19,4 L19,14 L13,20 L5,20 Z", "M13,20 L13,14 L19,14", "M8,8 L16,8", "M8,11 L13,11"],
    "ic_text": ["M5,6 L5,4 L19,4 L19,6", "M12,4 L12,20", "M9,20 L15,20"],
    "ic_draw": ["M3,17 C6,10 8,8 10,10 C12,12 9,16 11,17 C13,18 16,9 21,8"],
    "ic_rect": ["M4,6 L20,6 L20,18 L4,18 Z"],
    "ic_circle": ["M12,4 A8,8 0 1 1 11.99,4 Z"],
    "ic_arrow": ["M5,19 L19,5", "M11,5 L19,5 L19,13"],
    "ic_sign": ["M3,18 C5,14 6,9 8,9 C10,9 8,16 10,16 C12,16 13,11 14,11 C15,11 15,15 17,15 L21,15", "M3,21 L21,21"],
    "ic_redact": ["M3,6 L21,6", "M3,12 L21,12", "M3,18 L13,18"],
    "ic_merge": ["M6,3 L6,9 C6,13 12,13 12,17 L12,21", "M18,3 L18,9 C18,13 12,13 12,17", "M9,18 L12,21 L15,18"],
    "ic_split": ["M12,21 L12,13 C12,9 6,9 6,5", "M12,13 C12,9 18,9 18,5", "M3,6 L6,3 L9,6", "M15,6 L18,3 L21,6"],
    "ic_compress": ["M8,3 L12,7 L16,3", "M12,7 L12,1", "M8,21 L12,17 L16,21", "M12,17 L12,23", "M4,10 L20,10", "M4,14 L20,14"],
    "ic_ocr": ["M3,8 L3,3 L8,3", "M16,3 L21,3 L21,8", "M21,16 L21,21 L16,21", "M8,21 L3,21 L3,16", "M7,9 L17,9", "M7,12 L17,12", "M7,15 L13,15"],
    "ic_form": ["M4,4 L20,4 L20,20 L4,20 Z", "M8,12 L11,15 L16,9"],
    "ic_edit_text": ["M4,5 L4,3 L14,3 L14,5", "M9,3 L9,15", "M7,15 L11,15", "M13,21 L21,13 L19,11 L11,19 L11,21 Z"],
    "ic_unlock": ["M7,11 L7,7 A5,5 0 0 1 16.5,5", "M5,11 L19,11 L19,21 L5,21 Z"],
    "ic_fit_width": ["M3,5 L3,19", "M21,5 L21,19", "M7,12 L17,12", "M10,9 L7,12 L10,15", "M14,9 L17,12 L14,15"],
    "ic_settings": ["M12,8 A4,4 0 1 1 11.99,8 Z", "M12,1 L12,4", "M12,20 L12,23", "M1,12 L4,12", "M20,12 L23,12", "M4.2,4.2 L6.3,6.3", "M17.7,17.7 L19.8,19.8", "M4.2,19.8 L6.3,17.7", "M17.7,6.3 L19.8,4.2"],
    "ic_insert_page": ["M6,2 L14,2 L18,6 L18,22 L6,22 Z", "M12,10 L12,18", "M8,14 L16,14"],
    "ic_extract": ["M6,2 L14,2 L18,6 L18,22 L6,22 Z", "M12,9 L12,18", "M9,12 L12,9 L15,12"],
}

NUM = r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"
ARGS = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}


def check(path: str, name: str) -> None:
    tokens = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|" + NUM, path)
    if "".join(tokens).replace(" ", "") != re.sub(r"[\s,]", "", path):
        raise SystemExit(f"{name}: unexpected characters in path")
    if not tokens or tokens[0] not in "Mm":
        raise SystemExit(f"{name}: path must start with M")
    i = 0
    while i < len(tokens):
        cmd = tokens[i]
        if not cmd.isalpha():
            raise SystemExit(f"{name}: number where a command was expected at token {i}")
        i += 1
        n = ARGS[cmd.upper()]
        nums = []
        while i < len(tokens) and not tokens[i].isalpha():
            nums.append(tokens[i]); i += 1
        if n == 0:
            if nums:
                raise SystemExit(f"{name}: Z takes no numbers")
        elif not nums or len(nums) % n:
            raise SystemExit(f"{name}: {cmd} has {len(nums)} numbers, needs a multiple of {n}")


HEAD = '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp"\n    android:height="24dp"\n    android:tint="?attr/colorControlNormal"\n    android:viewportWidth="24"\n    android:viewportHeight="24">\n'

os.makedirs(OUT, exist_ok=True)
for name, d in FILL.items():
    check(d, name)
    with open(os.path.join(OUT, name + ".xml"), "w") as f:
        f.write(HEAD + f'    <path\n        android:fillColor="@android:color/white"\n        android:pathData="{d}" />\n</vector>\n')
for name, paths in STROKE.items():
    body = ""
    for d in paths:
        check(d, name)
        body += (f'    <path\n        android:pathData="{d}"\n        android:strokeColor="@android:color/white"\n'
                 f'        android:strokeLineCap="round"\n        android:strokeLineJoin="round"\n        android:strokeWidth="2" />\n')
    with open(os.path.join(OUT, name + ".xml"), "w") as f:
        f.write(HEAD + body + "</vector>\n")
print(f"{len(FILL) + len(STROKE)} icons written and checked")
