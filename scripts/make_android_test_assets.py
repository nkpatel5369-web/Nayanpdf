"""Builds the synthetic documents used by the Android engine tests.

Run the desktop engine tests once first (they generate engine/tests/_fixtures),
then: python scripts/make_android_test_assets.py
Everything here is synthetic; no client document is used.
"""
import io
import os
import shutil

import pymupdf
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIX = os.path.join(ROOT, "engine", "tests", "_fixtures")
OUT = os.path.join(ROOT, "android", "engine", "src", "androidTest", "assets")

COPY = ["a.pdf", "b.pdf", "c.pdf", "twenty.pdf", "large.pdf", "financial.pdf", "form.pdf", "encrypted.pdf",
        "restricted.pdf", "malformed.pdf", "truncated.pdf", "logo.png", "photo.jpg"]


def scanned_page(src: str, dest: str, dpi: int = 200):
    """First page of src as a grayscale scan (JPEG), no text layer."""
    s = pymupdf.open(src)
    d = pymupdf.open()
    p = s[0]
    pix = p.get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY)
    img = Image.frombytes("L", (pix.width, pix.height), pix.samples)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=80)
    np_ = d.new_page(width=p.rect.width, height=p.rect.height)
    np_.insert_image(np_.rect, stream=buf.getvalue())
    d.save(dest, garbage=4, deflate=True)


def image_heavy(dest: str):
    """One page with a 1600 x 2134 photo-like JPEG at quality 95, placed at about 220 dpi."""
    import random
    from PIL import ImageDraw, ImageFilter

    random.seed(7)
    w, h = 1600, 2134
    im = Image.new("RGB", (w, h))
    dr = ImageDraw.Draw(im)
    for y in range(0, h, 4):
        c = int(80 + 120 * y / h)
        dr.rectangle((0, y, w, y + 4), fill=(c, 140, 255 - c))
    for _ in range(150):
        x, y = random.randint(0, w), random.randint(0, h)
        r = random.randint(20, 160)
        dr.ellipse((x - r, y - r, x + r, y + r), fill=tuple(random.randint(0, 255) for _ in range(3)))
    im = im.filter(ImageFilter.GaussianBlur(1.2))
    im = Image.blend(im, Image.effect_noise((w, h), 25).convert("RGB"), 0.12)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=95)
    d = pymupdf.open()
    p = d.new_page()
    p.insert_image(pymupdf.Rect(36, 120, 559, 806), stream=buf.getvalue())
    p.insert_text((36, 80), "Image heavy page - text must survive compression", fontsize=12)
    d.save(dest)


def rotated(dest: str):
    """A page stored with /Rotate 90 that reads upright, for edits on rotated pages."""
    d = pymupdf.open()
    p = d.new_page(width=595, height=842)
    p.set_rotation(90)
    # Written turned by 90 degrees in the stored page, so that it reads left to right,
    # starting 100 pt from the top-left corner, as the page is shown.
    p.insert_text((100, 742), "Rotated page marker R-001", fontsize=14, rotate=90)
    d.save(dest)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name in COPY:
        shutil.copy2(os.path.join(FIX, name), os.path.join(OUT, name))
    scanned_page(os.path.join(FIX, "financial.pdf"), os.path.join(OUT, "scanned.pdf"))
    image_heavy(os.path.join(OUT, "image_heavy.pdf"))
    rotated(os.path.join(OUT, "rotated.pdf"))
    for f in sorted(os.listdir(OUT)):
        print(f"{os.path.getsize(os.path.join(OUT, f)):>9}  {f}")
