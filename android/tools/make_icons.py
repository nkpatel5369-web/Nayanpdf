"""Generates the app's line icons (24 x 24 viewport) as Android vector drawables.

Original drawings for N PDF in one style: 1.6-unit strokes with round caps and
joins, small filled details where a line would be too thin. Every path's syntax
is checked so a malformed path cannot crash the app at runtime. With --sheet,
also writes an SVG contact sheet for visual review.

Run: python android/tools/make_icons.py [--sheet out.svg]
"""
import math
import os
import re
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable")
W = 1.6  # default stroke width


def circle(cx, cy, r):
    return f"M{cx + r:g},{cy:g} A{r:g},{r:g} 0 1 1 {cx - r:g},{cy:g} A{r:g},{r:g} 0 1 1 {cx + r:g},{cy:g} Z"


def rrect(x0, y0, x1, y1, r):
    return (f"M{x0 + r:g},{y0:g} L{x1 - r:g},{y0:g} A{r:g},{r:g} 0 0 1 {x1:g},{y0 + r:g} L{x1:g},{y1 - r:g} "
            f"A{r:g},{r:g} 0 0 1 {x1 - r:g},{y1:g} L{x0 + r:g},{y1:g} A{r:g},{r:g} 0 0 1 {x0:g},{y1 - r:g} "
            f"L{x0:g},{y0 + r:g} A{r:g},{r:g} 0 0 1 {x0 + r:g},{y0:g} Z")


def gear(cx=12, cy=12, outer=9.0, inner=7.0, teeth=8):
    pts = []
    step = 2 * math.pi / teeth
    for k in range(teeth):
        a = k * step
        for frac, r in ((-0.30, inner), (-0.17, outer), (0.17, outer), (0.30, inner)):
            t = a + frac * step
            pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
    d = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + " Z"
    return d


def page(x0=5, y0=3, x1=19, y1=21, fold=5):
    return f"M{x0 + 1:g},{y0:g} L{x1 - fold:g},{y0:g} L{x1:g},{y0 + fold:g} L{x1:g},{y1 - 1:g} A1,1 0 0 1 {x1 - 1:g},{y1:g} L{x0 + 1:g},{y1:g} A1,1 0 0 1 {x0:g},{y1 - 1:g} L{x0:g},{y0 + 1:g} A1,1 0 0 1 {x0 + 1:g},{y0:g} Z"


def corners(m=4, n=20, k=4.5, r=2):
    """Viewfinder corners."""
    return [f"M{m:g},{m + k:g} L{m:g},{m + r:g} A{r:g},{r:g} 0 0 1 {m + r:g},{m:g} L{m + k:g},{m:g}",
            f"M{n - k:g},{m:g} L{n - r:g},{m:g} A{r:g},{r:g} 0 0 1 {n:g},{m + r:g} L{n:g},{m + k:g}",
            f"M{n:g},{n - k:g} L{n:g},{n - r:g} A{r:g},{r:g} 0 0 1 {n - r:g},{n:g} L{n - k:g},{n:g}",
            f"M{m + k:g},{n:g} L{m + r:g},{n:g} A{r:g},{r:g} 0 0 1 {m:g},{n - r:g} L{m:g},{n - k:g}"]


S = lambda *paths, w=W: [("s", p, w) for p in paths]  # noqa: E731
F = lambda *paths: [("f", p, 0) for p in paths]  # noqa: E731

BOLT = "M13.5,2.5 L5.5,13.5 L11.5,13.5 L10.5,21.5 L18.5,10.5 L12.5,10.5 Z"

ICONS = {
    # Navigation and common actions
    "ic_back": S("M15,4.5 L7.5,12 L15,19.5", w=2.2),
    "ic_chevron_right": S("M9.5,6 L15.5,12 L9.5,18", w=1.8),
    "ic_up": S("M5.5,15 L12,8.5 L18.5,15", w=1.9),
    "ic_down": S("M5.5,9 L12,15.5 L18.5,9", w=1.9),
    "ic_close": S("M6.5,6.5 L17.5,17.5", "M17.5,6.5 L6.5,17.5", w=1.9),
    "ic_check": S("M5,12.5 L10,17.5 L19.5,7", w=2.0),
    "ic_add": S("M12,5 L12,19", "M5,12 L19,12", w=1.9),
    "ic_search": S(circle(10.5, 10.5, 6.5), "M15.3,15.3 L20,20", w=1.9),
    "ic_more": F(circle(5.5, 12, 1.7), circle(12, 12, 1.7), circle(18.5, 12, 1.7)),
    "ic_share": S("M8.5,9 L6.5,9 A1,1 0 0 0 5.5,10 L5.5,20 A1,1 0 0 0 6.5,21 L17.5,21 A1,1 0 0 0 18.5,20 L18.5,10 A1,1 0 0 0 17.5,9 L15.5,9",
                  "M12,2.8 L12,14.5", "M8.5,6.3 L12,2.8 L15.5,6.3"),
    "ic_undo": S("M8.5,13.5 L4,9 L8.5,4.5", "M4,9 L14.5,9 A5.5,5.5 0 0 1 14.5,20 L11,20"),
    "ic_redo": S("M15.5,13.5 L20,9 L15.5,4.5", "M20,9 L9.5,9 A5.5,5.5 0 0 0 9.5,20 L13,20"),
    "ic_open": S("M3.5,7 A2,2 0 0 1 5.5,5 L9.5,5 L11.5,7.5 L18.5,7.5 A2,2 0 0 1 20.5,9.5 L20.5,17 A2,2 0 0 1 18.5,19 L5.5,19 A2,2 0 0 1 3.5,17 Z"),
    "ic_folder": S("M3.5,7 A2,2 0 0 1 5.5,5 L9.5,5 L11.5,7.5 L18.5,7.5 A2,2 0 0 1 20.5,9.5 L20.5,17 A2,2 0 0 1 18.5,19 L5.5,19 A2,2 0 0 1 3.5,17 Z", "M3.5,10.5 L20.5,10.5"),
    "ic_doc": S(page(), "M14,3 L14,8 L19,8", "M8.5,12.5 L15.5,12.5", "M8.5,16 L15.5,16"),
    "ic_save": S("M12,3.5 L12,14.5", "M7.5,10 L12,14.5 L16.5,10", "M4.5,14.5 L4.5,18.5 A1.5,1.5 0 0 0 6,20 L18,20 A1.5,1.5 0 0 0 19.5,18.5 L19.5,14.5"),
    "ic_delete": S("M4,6.5 L20,6.5", "M9,6.5 L9,4.5 A1,1 0 0 1 10,3.5 L14,3.5 A1,1 0 0 1 15,4.5 L15,6.5",
                   "M6,6.5 L7,19 A2,2 0 0 0 9,21 L15,21 A2,2 0 0 0 17,19 L18,6.5", "M10,10.5 L10,17", "M14,10.5 L14,17"),
    "ic_edit": S("M4.5,19.5 L5.3,15.7 L15.6,5.4 A2.1,2.1 0 0 1 18.6,8.4 L8.3,18.7 Z", "M13.6,7.4 L16.6,10.4"),
    "ic_info": S(circle(12, 12, 8.5), "M12,11 L12,16.5") + F(circle(12, 7.9, 1.1)),
    "ic_list": S("M9,7 L20,7", "M9,12 L20,12", "M9,17 L20,17") + F(circle(5, 7, 1.2), circle(5, 12, 1.2), circle(5, 17, 1.2)),
    "ic_settings": S(gear(), circle(12, 12, 3)),
    "ic_home": S("M4,11 L12,4 L20,11", "M6,9.3 L6,19 A1,1 0 0 0 7,20 L17,20 A1,1 0 0 0 18,19 L18,9.3", "M10,20 L10,14.5 L14,14.5 L14,20"),
    "ic_tools": S(rrect(4, 4, 10.5, 10.5, 2), rrect(13.5, 4, 20, 10.5, 2), rrect(4, 13.5, 10.5, 20, 2), rrect(13.5, 13.5, 20, 20, 2)),
    "ic_clock": S(circle(12, 12, 8.5), "M12,7.5 L12,12 L15,14"),
    "ic_sort": S("M7.5,4.5 L7.5,19.5", "M4.5,7.5 L7.5,4.5 L10.5,7.5", "M16.5,4.5 L16.5,19.5", "M13.5,16.5 L16.5,19.5 L19.5,16.5"),
    "ic_select": S(circle(12, 12, 8.5), "M8,12.3 L10.8,15 L16,9.3"),
    "ic_moon": S("M19.5,14.5 A8,8 0 1 1 9.5,4.5 A6.5,6.5 0 0 0 19.5,14.5 Z"),
    "ic_sun": S(circle(12, 12, 4), "M12,2.5 L12,4.5", "M12,19.5 L12,21.5", "M2.5,12 L4.5,12", "M19.5,12 L21.5,12",
                "M5.3,5.3 L6.7,6.7", "M17.3,17.3 L18.7,18.7", "M5.3,18.7 L6.7,17.3", "M17.3,6.7 L18.7,5.3"),
    "ic_bookmark": S("M6.5,4.5 A1.5,1.5 0 0 1 8,3 L16,3 A1.5,1.5 0 0 1 17.5,4.5 L17.5,21 L12,16.8 L6.5,21 Z"),
    "ic_print": S("M7,9 L7,4 L17,4 L17,9", "M5.5,17 L4.5,17 A1.5,1.5 0 0 1 3,15.5 L3,10.5 A1.5,1.5 0 0 1 4.5,9 L19.5,9 A1.5,1.5 0 0 1 21,10.5 L21,15.5 A1.5,1.5 0 0 1 19.5,17 L18.5,17",
                  "M7,14 L17,14 L17,21 L7,21 Z"),
    "ic_volume": S("M4,9.5 L7.5,9.5 L12,5.5 L12,18.5 L7.5,14.5 L4,14.5 Z", "M15.5,9 A4,4 0 0 1 15.5,15", "M18,6.5 A7.5,7.5 0 0 1 18,17.5"),
    "ic_play": F("M8,5 L19,12 L8,19 Z"),
    "ic_pause": F(rrect(6, 5, 10, 19, 1), rrect(14, 5, 18, 19, 1)),
    "ic_stop": F(rrect(6, 6, 18, 18, 2)),
    "ic_lock": S(rrect(5.5, 10, 18.5, 21, 2), "M8.5,10 L8.5,7 A3.5,3.5 0 0 1 15.5,7 L15.5,10"),
    "ic_unlock": S(rrect(5.5, 10, 18.5, 21, 2), "M8.5,10 L8.5,7 A3.5,3.5 0 0 1 15.4,6.2"),
    "ic_rotate": S("M18.5,12 A6.5,6.5 0 1 1 15.6,6.6", "M15.9,2.8 L15.9,6.9 L11.8,6.9"),
    "ic_rotate_left": S("M5.5,12 A6.5,6.5 0 1 0 8.4,6.6", "M8.1,2.8 L8.1,6.9 L12.2,6.9"),
    "ic_fit_width": S("M3.5,5 L3.5,19", "M20.5,5 L20.5,19", "M7,12 L17,12", "M10,9 L7,12 L10,15", "M14,9 L17,12 L14,15"),
    "ic_date": S(rrect(4, 5.5, 20, 20, 2), "M4,10 L20,10", "M8,3.5 L8,7", "M16,3.5 L16,7") + F(circle(8.5, 14, 1), circle(12, 14, 1), circle(15.5, 14, 1)),
    # Comment and edit tools
    "ic_comment": S("M4.5,6.5 A2,2 0 0 1 6.5,4.5 L17.5,4.5 A2,2 0 0 1 19.5,6.5 L19.5,14.5 A2,2 0 0 1 17.5,16.5 L10.5,16.5 L6.5,20 L6.5,16.5 A2,2 0 0 1 4.5,14.5 Z"),
    "ic_note": S("M5,5 A1,1 0 0 1 6,4 L18,4 A1,1 0 0 1 19,5 L19,14 L14,19 L6,19 A1,1 0 0 1 5,18 Z", "M14,19 L14,14 L19,14", "M8.5,8.5 L15.5,8.5", "M8.5,11.5 L12.5,11.5"),
    "ic_highlight": S("M8.5,15.5 L5,19 L9.5,19", "M7.8,14.2 L15.3,6.7 A2,2 0 0 1 18.3,9.7 L10.8,17.2 Z", "M4,21.3 L20,21.3"),
    "ic_underline": S("M7,4 L7,11 A5,5 0 0 0 17,11 L17,4", "M5,20 L19,20"),
    "ic_strike": S("M16.5,7 C15.6,5.3 13.9,4.5 12,4.5 C9.2,4.5 7.3,6 7.3,8.2 C7.3,9.7 8.2,10.7 9.6,11.3", "M4,12.5 L20,12.5",
                   "M14.6,13.5 C15.9,14.2 16.8,15.3 16.8,16.7 C16.8,18.9 14.8,19.9 12,19.9 C9.9,19.9 8,19.2 7.2,17.6"),
    "ic_text": S("M5.5,7 L5.5,5 L18.5,5 L18.5,7", "M12,5 L12,19", "M9,19 L15,19"),
    "ic_edit_text": S("M3.5,5.5 L3.5,4 L13.5,4 L13.5,5.5", "M8.5,4 L8.5,16", "M6.5,16 L10.5,16",
                      "M13,20.5 L13.4,18.4 L18.8,13 A1.3,1.3 0 0 1 20.6,14.8 L15.2,20.2 Z"),
    "ic_draw": S("M3.5,17 C6,10.5 8,8.5 10,10 C12,11.7 9.3,15.8 11.2,16.8 C13.2,17.9 15.8,9.5 20.5,8.5"),
    "ic_rect": S(rrect(4, 5.5, 20, 18.5, 1.5)),
    "ic_circle": S(circle(12, 12, 8)),
    "ic_arrow": S("M5.5,18.5 L18.5,5.5", "M10,5.5 L18.5,5.5 L18.5,14"),
    "ic_sign": S("M3,17.5 C5,13.5 6,8.5 8,8.5 C10,8.5 8,15.5 10,15.5 C12,15.5 13,10.5 14,10.5 C15,10.5 15,14.5 17,14.5 L21,14.5", "M3,20.5 L21,20.5"),
    "ic_form": S(rrect(4, 4, 20, 20, 2.5), "M8,12.3 L11,15.3 L16.2,9.2"),
    "ic_redact": S("M4,6 L16,6", "M4,18 L12,18") + F(rrect(3.5, 9.5, 20.5, 14.5, 1)),
    "ic_image": S(rrect(3.5, 4.5, 20.5, 19.5, 2), "M3.8,16.5 L8.8,11.5 L13,15.5 L15.5,13 L20.2,17.5") + F(circle(16, 8.8, 1.5)),
    "ic_pages": S(rrect(4, 4, 10.5, 10.5, 1.2), rrect(13.5, 4, 20, 10.5, 1.2), rrect(4, 13.5, 10.5, 20, 1.2), rrect(13.5, 13.5, 20, 20, 1.2)),
    "ic_insert_page": S(page(), "M12,10.5 L12,17.5", "M8.5,14 L15.5,14"),
    "ic_extract": S(page(), "M12,10 L12,17.5", "M9,13 L12,10 L15,13"),
    "ic_export": S("M14,3.5 L7,3.5 A1.5,1.5 0 0 0 5.5,5 L5.5,19 A1.5,1.5 0 0 0 7,20.5 L16,20.5 A1.5,1.5 0 0 0 17.5,19 L17.5,15",
                   "M11,11 L21,11", "M17.5,7.5 L21,11 L17.5,14.5"),
    "ic_merge": S("M6,3.5 L6,8.5 C6,12.5 12,12.5 12,16.5 L12,21", "M18,3.5 L18,8.5 C18,12.5 12,12.5 12,16.5", "M9,18 L12,21 L15,18"),
    "ic_split": S("M12,21 L12,13.5 C12,9.5 6,9.5 6,5.5 L6,3.5", "M12,13.5 C12,9.5 18,9.5 18,5.5 L18,3.5", "M3,6.5 L6,3.5 L9,6.5", "M15,6.5 L18,3.5 L21,6.5"),
    "ic_compress": S("M4,14.5 L9.5,14.5 L9.5,20", "M9.5,14.5 L3.5,20.5", "M20,9.5 L14.5,9.5 L14.5,4", "M14.5,9.5 L20.5,3.5"),
    "ic_ocr": S(*corners(), "M8.5,9 L15.5,9", "M12,9 L12,16"),
    # Scanner
    "ic_scan": S(*corners(), "M8,9.5 L16,9.5", "M8,12.5 L16,12.5", "M8,15.5 L13,15.5"),
    "ic_camera": S("M3.5,8.5 A2,2 0 0 1 5.5,6.5 L7.8,6.5 L9.2,4.5 L14.8,4.5 L16.2,6.5 L18.5,6.5 A2,2 0 0 1 20.5,8.5 L20.5,17.5 A2,2 0 0 1 18.5,19.5 L5.5,19.5 A2,2 0 0 1 3.5,17.5 Z",
                   circle(12, 13, 3.8)),
    "ic_flash": S(BOLT),
    "ic_flash_off": S(BOLT, "M4,4 L20,20"),
    "ic_flash_auto": S("M11.5,2.5 L4.5,12.5 L9.5,12.5 L8.5,20.5 L15.5,10.5 L10.5,10.5 Z", "M15.5,21 L18,14.5 L20.5,21", "M16.4,18.8 L19.6,18.8"),
    "ic_crop": S("M6.5,2.5 L6.5,17.5 L21.5,17.5", "M2.5,6.5 L17.5,6.5 L17.5,21.5"),
    "ic_filter": S(circle(9, 9.5, 5), circle(15, 9.5, 5), circle(12, 14.5, 5)),
    "ic_adjust": S(circle(12, 12, 8.5)) + F("M12,3.5 A8.5,8.5 0 0 1 12,20.5 Z"),
    "ic_eraser": S("M14.3,4.7 L19.3,9.7 A1.3,1.3 0 0 1 19.3,11.5 L11.5,19.3 L7.3,19.3 L4.7,16.7 A1.3,1.3 0 0 1 4.7,14.9 L12.5,4.7 A1.3,1.3 0 0 1 14.3,4.7 Z",
                   "M9.2,9.6 L14.4,14.8", "M11.5,19.3 L20,19.3"),
    "ic_wand": S("M4,20 L14.5,9.5", "M12.8,8 L16,11.2", "M17.5,2.5 L17.5,6.5", "M15.5,4.5 L19.5,4.5", "M20.5,10 L20.5,13", "M19,11.5 L22,11.5", "M7.5,3.5 L7.5,6", "M6.25,4.75 L8.75,4.75"),
    "ic_qr": S(rrect(4, 4, 10, 10, 1), rrect(14, 4, 20, 10, 1), rrect(4, 14, 10, 20, 1), "M14,14 L16.5,14", "M19,14 L20,14 L20,16.5", "M14,17.5 L14,20 L16.5,20", "M19.5,19.5 L20,20")
            + F(rrect(6.2, 6.2, 7.8, 7.8, 0.3), rrect(16.2, 6.2, 17.8, 7.8, 0.3), rrect(6.2, 16.2, 7.8, 17.8, 0.3), rrect(16.2, 16.2, 17.8, 17.8, 0.3)),
    "ic_contact": S(rrect(3, 5, 21, 19, 2), circle(9, 10.5, 2.2), "M5.8,16 A3.4,3.4 0 0 1 12.2,16", "M14.5,10 L18.5,10", "M14.5,13.5 L18.5,13.5"),
    "ic_book": S("M12,6.5 C10,5 7,4.5 3.5,5 L3.5,18.5 C7,18 10,18.5 12,20 C14,18.5 17,18 20.5,18.5 L20.5,5 C17,4.5 14,5 12,6.5 Z", "M12,6.5 L12,20"),
    "ic_idcard": S(rrect(3, 5, 21, 19, 2), circle(8.5, 10.3, 2), "M5.5,16 A3,3 0 0 1 11.5,16", "M14,10 L18.5,10", "M14,13 L18.5,13"),
    "ic_whiteboard": S(rrect(3, 4, 21, 15.5, 1.2), "M8,20.5 L12,15.5 L16,20.5", "M6.5,11.5 C8,8 10,12.5 12,9.5 C13.5,7.5 15,10 17,8.5"),
    "ic_document": S(page(), "M14,3 L14,8 L19,8", "M8.5,11.5 L15.5,11.5", "M8.5,14.5 L15.5,14.5", "M8.5,17.5 L12.5,17.5"),
    "ic_businesscard": S(rrect(2.5, 6, 21.5, 18, 2), "M6,10.5 L13,10.5", "M6,13.5 L11,13.5", rrect(15.5, 9.5, 18.5, 12.5, 0.8)),
    "ic_gallery": S(rrect(6.5, 6.5, 21, 19, 1.8), "M3,15.5 L3,5 A1.5,1.5 0 0 1 4.5,3.5 L17,3.5", "M6.8,16.5 L10.8,12.5 L14,15.5 L16,13.5 L20.7,18") + F(circle(17, 10, 1.3)),
    "ic_resize": S("M4,10 L4,4 L10,4", "M4,4 L10,10", "M20,14 L20,20 L14,20", "M20,20 L14,14"),
    "ic_reorder": S("M4.5,8 L19.5,8", "M4.5,12 L19.5,12", "M4.5,16 L19.5,16"),
    "ic_shapes": S(rrect(3.5, 9.5, 12.5, 18.5, 1), circle(15, 9, 5.5)),
    "ic_eyedropper": S("M15.4,4.6 A2.1,2.1 0 0 1 19.4,8.6 L17,11 L13,7 Z", "M14,8 L5.5,16.5 L5,19 L7.5,18.5 L16,10"),
    "ic_fmt_doc": S(page(), "M14,3 L14,8 L19,8", "M8.5,11.5 L15.5,11.5", "M8.5,14.5 L15.5,14.5", "M8.5,17.5 L15.5,17.5"),
    "ic_fmt_sheet": S(rrect(3.5, 4.5, 20.5, 19.5, 1.5), "M3.5,9.5 L20.5,9.5", "M3.5,14.5 L20.5,14.5", "M9.5,4.5 L9.5,19.5", "M15,4.5 L15,19.5"),
    "ic_fmt_slides": S(rrect(3, 4.5, 21, 16, 1.5), "M12,16 L12,19.5", "M8,20 L16,20", "M7.5,8.5 L13,8.5", "M7.5,11.5 L16.5,11.5"),
    "ic_text_size": S("M3.5,18.5 L8,7 L12.5,18.5", "M5.3,14.5 L10.7,14.5", "M13.5,18.5 L16.8,10.5 L20.5,18.5", "M14.8,15.8 L19.2,15.8"),
}


ARGS = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}
NUM = r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"


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


HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n    android:height="24dp"\n    android:tint="?attr/colorControlNormal"\n'
        '    android:viewportWidth="24"\n    android:viewportHeight="24">\n')


def vector(items, name):
    body = ""
    for kind, d, w in items:
        check(d, name)
        if kind == "f":
            body += f'    <path\n        android:fillColor="@android:color/white"\n        android:pathData="{d}" />\n'
        else:
            body += (f'    <path\n        android:pathData="{d}"\n        android:strokeColor="@android:color/white"\n'
                     f'        android:strokeLineCap="round"\n        android:strokeLineJoin="round"\n        android:strokeWidth="{w:g}" />\n')
    return HEAD + body + "</vector>\n"


def sheet(path):
    names = sorted(ICONS)
    cols = 10
    cell = 96
    rows = (len(names) + cols - 1) // cols
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{cols * cell}" height="{rows * (cell + 18)}" viewBox="0 0 {cols * cell} {rows * (cell + 18)}">',
           f'<rect width="100%" height="100%" fill="white"/>']
    for k, n in enumerate(names):
        x = (k % cols) * cell; y = (k // cols) * (cell + 18)
        out.append(f'<g transform="translate({x + 12},{y + 8}) scale(3)">')
        for kind, d, w in ICONS[n]:
            if kind == "f":
                out.append(f'<path d="{d}" fill="#1c1c1e"/>')
            else:
                out.append(f'<path d="{d}" fill="none" stroke="#1c1c1e" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round"/>')
        out.append("</g>")
        out.append(f'<text x="{x + cell / 2}" y="{y + cell + 10}" font-size="10" text-anchor="middle" font-family="sans-serif">{n[3:]}</text>')
    out.append("</svg>")
    with open(path, "w") as f:
        f.write("\n".join(out))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, items in ICONS.items():
        with open(os.path.join(OUT, name + ".xml"), "w") as f:
            f.write(vector(items, name))
    # White copies without a theme tint, for launcher shortcuts (drawn outside the app's theme).
    for name in ("ic_scan", "ic_open", "ic_gallery"):
        with open(os.path.join(OUT, name + "_white.xml"), "w") as f:
            f.write(vector(ICONS[name], name).replace('    android:tint="?attr/colorControlNormal"\n', ""))
    if "--sheet" in sys.argv:
        sheet(sys.argv[sys.argv.index("--sheet") + 1])
    print(f"{len(ICONS)} icons written and checked")
