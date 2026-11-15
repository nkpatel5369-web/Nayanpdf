#!/usr/bin/env python3
"""
Fetches N PDF's interface icons from Google's Material Symbols (Outlined, weight 400,
24 px; Apache License 2.0, github.com/google/material-design-icons) as Android vector
drawables into app/src/main/res/drawable. Run once when the icon list changes; the
files are committed, so building the app needs no network for icons.

    python3 tools/material_symbols.py            # fetch every icon
    python3 tools/material_symbols.py --check    # only check that each name exists
"""
import re, sys, urllib.request
from pathlib import Path

BASE = "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/{n}/materialsymbolsoutlined/{n}_24px.xml"
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"

# N PDF drawable name -> Material Symbols name.
ICONS = {
    "add": "add", "adjust": "tune", "arrow": "arrow_outward", "back": "arrow_back",
    "book": "menu_book", "bookmark": "bookmark", "businesscard": "contact_page", "camera": "photo_camera",
    "check": "check", "chevron_right": "chevron_right", "circle": "circle", "clock": "schedule",
    "close": "close", "comment": "comment", "compress": "compress", "contact": "person",
    "crop": "crop", "date": "calendar_today", "delete": "delete", "doc": "description",
    "document": "article", "down": "keyboard_arrow_down", "draw": "draw", "edit": "edit",
    "edit_text": "edit_document", "eraser": "ink_eraser", "export": "file_export", "extract": "output",
    "eyedropper": "colorize", "filter": "photo_filter", "fit_width": "fit_width", "flash": "flash_on",
    "flash_auto": "flash_auto", "flash_off": "flash_off", "fmt_doc": "article", "fmt_sheet": "table_view",
    "fmt_slides": "slideshow", "folder": "folder", "form": "dynamic_form", "gallery": "photo_library",
    "highlight": "ink_highlighter", "home": "home", "idcard": "id_card", "image": "image",
    "info": "info", "insert_page": "note_add", "list": "list", "lock": "lock", "merge": "call_merge",
    "moon": "dark_mode", "more": "more_vert", "note": "sticky_note_2", "ocr": "document_search",
    "open": "folder_open", "pages": "grid_view", "pause": "pause", "play": "play_arrow",
    "print": "print", "qr": "qr_code_scanner", "rect": "rectangle", "redact": "format_ink_highlighter",
    "redo": "redo", "reorder": "swap_vert", "resize": "aspect_ratio", "rotate": "rotate_right",
    "rotate_left": "rotate_left", "save": "save", "scan": "document_scanner", "search": "search",
    "select": "text_select_start", "settings": "settings", "shapes": "shapes", "share": "share",
    "sign": "signature", "sort": "sort", "split": "call_split", "stop": "stop",
    "strike": "format_strikethrough", "sun": "light_mode", "text": "text_fields", "text_size": "format_size",
    "tools": "handyman", "underline": "format_underlined", "undo": "undo", "unlock": "lock_open",
    "up": "keyboard_arrow_up", "volume": "volume_up", "wand": "auto_fix_high", "whiteboard": "developer_board",
    # Added with the scanner review and Material 3 design.
    "keep_scanning": "add_a_photo", "swap": "swap_horiz", "line_weight": "line_weight", "opacity": "opacity",
    "fill": "format_color_fill", "select_all": "checklist", "pdf": "picture_as_pdf", "rename": "drive_file_rename_outline",
    "copy": "content_copy", "retake": "replay", "apps": "apps", "done_all": "done_all", "palette": "palette",
    "contrast": "contrast", "brightness": "brightness_6", "auto_fix": "auto_awesome", "grid": "grid_view",
    "jpeg": "imagesmode", "edit_scan": "edit_square", "remove": "do_not_disturb_on", "chevron_left": "chevron_left",
    "fingers": "back_hand", "spellcheck": "spellcheck", "translate": "translate", "storage": "folder_managed",
    "privacy": "shield", "person": "person", "license": "license", "phone": "smartphone", "label": "label",
    "files": "folder_open", "history": "history", "drag": "drag_indicator", "open_in": "open_in_new",
}
# Drawn white, with no theme tint: launcher shortcuts and the camera screen.
WHITE = {"scan_white": "document_scanner", "open_white": "folder_open", "gallery_white": "photo_library"}


def fetch(name: str) -> str:
    with urllib.request.urlopen(BASE.format(n=name), timeout=30) as r:
        return r.read().decode()


def main() -> int:
    check = "--check" in sys.argv
    missing = []
    for ours, sym in list(ICONS.items()) + list(WHITE.items()):
        try:
            xml = fetch(sym)
        except Exception as e:
            missing.append(f"{ours} -> {sym}: {e}")
            continue
        if check:
            continue
        if ours in WHITE:
            xml = re.sub(r'\s*android:tint="[^"]*"', "", xml)
        header = f"<!-- Material Symbols \"{sym}\" (Outlined, 400, 24 px), Apache License 2.0, Google. -->\n"
        if xml.startswith("<?xml"):
            first, rest = xml.split("\n", 1)
            xml = first + "\n" + header + rest
        else:
            xml = header + xml
        (OUT / f"ic_{ours}.xml").write_text(xml)
    for m in missing:
        print("MISSING", m)
    print(f"{len(ICONS) + len(WHITE) - len(missing)} ok, {len(missing)} missing")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
