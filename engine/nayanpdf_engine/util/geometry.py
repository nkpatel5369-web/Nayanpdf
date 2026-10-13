"""Coordinate conversion.

The UI exchanges geometry in PDF user space (points, y axis up, origin at the
MediaBox origin) because that is what PDF.js reports and it is independent of
page rotation and zoom. PyMuPDF works in its own unrotated, y-down space.
"""

from __future__ import annotations

import pymupdf


def pdf_to_fitz_rect(page: pymupdf.Page, r) -> pymupdf.Rect:
    rect = pymupdf.Rect(r) * page.transformation_matrix
    rect.normalize()
    return rect


def fitz_to_pdf_rect(page: pymupdf.Page, r) -> list[float]:
    rect = pymupdf.Rect(r) * ~page.transformation_matrix
    rect.normalize()
    return [round(rect.x0, 3), round(rect.y0, 3), round(rect.x1, 3), round(rect.y1, 3)]


def pdf_to_fitz_point(page: pymupdf.Page, p) -> pymupdf.Point:
    return pymupdf.Point(p) * page.transformation_matrix


def fitz_to_pdf_point(page: pymupdf.Page, p) -> list[float]:
    q = pymupdf.Point(p) * ~page.transformation_matrix
    return [round(q.x, 3), round(q.y, 3)]


def to_fitz_rect(page: pymupdf.Page, r, space: str = "pdf") -> pymupdf.Rect:
    if space == "pdf":
        return pdf_to_fitz_rect(page, r)
    return pymupdf.Rect(r)


def to_fitz_point(page: pymupdf.Page, p, space: str = "pdf") -> pymupdf.Point:
    if space == "pdf":
        return pdf_to_fitz_point(page, p)
    return pymupdf.Point(p)
