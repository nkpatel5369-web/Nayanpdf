package npdf.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import java.util.Date

/**
 * Comments: real PDF annotation objects that other readers (Acrobat included)
 * show, edit and delete. Coordinates are in page space.
 */
object Annotations {

    class Info(
        val id: Int, val type: Int, val typeName: String, val rect: Rect, val contents: String,
        val author: String, val argb: Int,
    )

    private fun colorOf(argb: Int) = Pdf.rgb(argb)

    private fun annotatable(doc: PDFDocument) = Pdf.require(doc, Document.PERMISSION_ANNOTATE, "adding comments")

    private fun finish(a: PDFAnnotation, author: String?, contents: String?): Int {
        author?.takeIf { it.isNotBlank() }?.let { a.author = it }
        contents?.let { a.contents = it }
        a.modificationDate = Date()
        a.update()
        return a.getObject().asIndirect()
    }

    /** Highlight, underline, strike-out or squiggly over text quads (from a text selection). */
    fun addMarkup(doc: PDFDocument, pageNumber: Int, type: Int, quads: List<Quad>, argb: Int, author: String? = null, note: String? = null): Int {
        annotatable(doc)
        require(type in setOf(PDFAnnotation.TYPE_HIGHLIGHT, PDFAnnotation.TYPE_UNDERLINE, PDFAnnotation.TYPE_STRIKE_OUT, PDFAnnotation.TYPE_SQUIGGLY))
        if (quads.isEmpty()) throw PdfError(Codes.INVALID, "Select some text first.")
        val page = Pdf.page(doc, pageNumber)
        val a = page.createAnnotation(type)
        a.color = colorOf(argb)
        quads.forEach { a.addQuadPoint(it) }
        if (type == PDFAnnotation.TYPE_HIGHLIGHT) a.opacity = 1f
        return finish(a, author, note)
    }

    /** A sticky note at [point]. */
    fun addNote(doc: PDFDocument, pageNumber: Int, point: Point, text: String, argb: Int = 0xFFFFD400.toInt(), author: String? = null): Int {
        annotatable(doc)
        val page = Pdf.page(doc, pageNumber)
        val a = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
        a.rect = Rect(point.x, point.y, point.x + 20f, point.y + 20f)
        a.icon = "Comment"
        a.color = colorOf(argb)
        return finish(a, author, text)
    }

    /** A text box comment. */
    fun addTextBox(doc: PDFDocument, pageNumber: Int, rect: Rect, text: String, fontSize: Float = 12f, argb: Int = 0xFF000000.toInt(), author: String? = null): Int {
        annotatable(doc)
        if (text.isBlank()) throw PdfError(Codes.INVALID, "Enter the text for the box.")
        val page = Pdf.page(doc, pageNumber)
        val a = page.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
        a.rect = rect
        a.setDefaultAppearance("Helv", fontSize, colorOf(argb))
        return finish(a, author, text)
    }

    /** Freehand drawing; each stroke is a list of points. */
    fun addInk(doc: PDFDocument, pageNumber: Int, strokes: List<List<Point>>, argb: Int, width: Float = 2f, author: String? = null): Int {
        annotatable(doc)
        val usable = strokes.filter { it.size >= 2 }
        if (usable.isEmpty()) throw PdfError(Codes.INVALID, "Draw on the page first.")
        val page = Pdf.page(doc, pageNumber)
        val a = page.createAnnotation(PDFAnnotation.TYPE_INK)
        a.color = colorOf(argb)
        a.borderWidth = width
        a.inkList = usable.map { it.toTypedArray() }.toTypedArray()
        return finish(a, author, null)
    }

    /** Rectangle (TYPE_SQUARE) or ellipse (TYPE_CIRCLE). */
    fun addShape(doc: PDFDocument, pageNumber: Int, type: Int, rect: Rect, argb: Int, width: Float = 2f, fillArgb: Int? = null, author: String? = null): Int {
        annotatable(doc)
        require(type == PDFAnnotation.TYPE_SQUARE || type == PDFAnnotation.TYPE_CIRCLE)
        val page = Pdf.page(doc, pageNumber)
        val a = page.createAnnotation(type)
        a.rect = rect
        a.color = colorOf(argb)
        a.borderWidth = width
        fillArgb?.let { a.interiorColor = colorOf(it) }
        return finish(a, author, null)
    }

    /** A line, optionally ending in an arrow. */
    fun addLine(doc: PDFDocument, pageNumber: Int, from: Point, to: Point, argb: Int, width: Float = 2f, arrow: Boolean = false, author: String? = null): Int {
        annotatable(doc)
        val page = Pdf.page(doc, pageNumber)
        val a = page.createAnnotation(PDFAnnotation.TYPE_LINE)
        a.setLine(from, to)
        a.color = colorOf(argb)
        a.borderWidth = width
        if (arrow) a.setLineEndingStyles(PDFAnnotation.LINE_ENDING_NONE, PDFAnnotation.LINE_ENDING_CLOSED_ARROW)
        return finish(a, author, null)
    }

    /**
     * A signature picture (drawn or typed) as a stamp annotation. This is a
     * picture of a signature, not a digital signature: it does not protect the
     * document against later changes.
     */
    fun addSignatureImage(doc: PDFDocument, pageNumber: Int, rect: Rect, png: ByteArray, author: String? = null): Int {
        annotatable(doc)
        val page = Pdf.page(doc, pageNumber)
        val image = try { Image(png) } catch (e: RuntimeException) { throw PdfError(Codes.INVALID, "The signature picture could not be read.") }
        val a = page.createAnnotation(PDFAnnotation.TYPE_STAMP)
        a.rect = rect
        a.setStampImage(image)
        return finish(a, author, "Signature image")
    }

    private val TYPE_NAMES = mapOf(
        PDFAnnotation.TYPE_TEXT to "Note", PDFAnnotation.TYPE_FREE_TEXT to "Text box", PDFAnnotation.TYPE_LINE to "Line",
        PDFAnnotation.TYPE_SQUARE to "Rectangle", PDFAnnotation.TYPE_CIRCLE to "Ellipse", PDFAnnotation.TYPE_HIGHLIGHT to "Highlight",
        PDFAnnotation.TYPE_UNDERLINE to "Underline", PDFAnnotation.TYPE_SQUIGGLY to "Squiggly", PDFAnnotation.TYPE_STRIKE_OUT to "Strike-through",
        PDFAnnotation.TYPE_REDACT to "Redaction mark", PDFAnnotation.TYPE_STAMP to "Stamp", PDFAnnotation.TYPE_INK to "Drawing",
        PDFAnnotation.TYPE_POLYGON to "Polygon", PDFAnnotation.TYPE_POLY_LINE to "Polyline", PDFAnnotation.TYPE_CARET to "Caret",
        PDFAnnotation.TYPE_FILE_ATTACHMENT to "Attachment",
    )

    private fun argbOf(a: PDFAnnotation): Int {
        val c = try { a.color } catch (e: RuntimeException) { null } ?: return 0xFF000000.toInt()
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt()
        return when (c.size) {
            3 -> (0xFF shl 24) or (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
            1 -> (0xFF shl 24) or (ch(c[0]) * 0x010101)
            4 -> {
                val r = 1f - minOf(1f, c[0] + c[3]); val g = 1f - minOf(1f, c[1] + c[3]); val b = 1f - minOf(1f, c[2] + c[3])
                (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
            }
            else -> 0xFF000000.toInt()
        }
    }

    /** Comments on a page (form fields and links excluded). */
    fun list(doc: PDFDocument, pageNumber: Int): List<Info> {
        val page = Pdf.page(doc, pageNumber)
        return (page.annotations ?: emptyArray()).filter { it.type != PDFAnnotation.TYPE_POPUP && it.type != PDFAnnotation.TYPE_LINK && it.type != PDFAnnotation.TYPE_WIDGET }.map { a ->
            Info(a.getObject().asIndirect(), a.type, TYPE_NAMES[a.type] ?: "Comment", a.bounds, a.contents ?: "",
                if (a.hasAuthor()) a.author ?: "" else "", argbOf(a))
        }
    }

    fun find(page: PDFPage, id: Int): PDFAnnotation =
        (page.annotations ?: emptyArray()).firstOrNull { it.getObject().asIndirect() == id }
            ?: throw PdfError(Codes.INVALID, "That comment no longer exists.")

    /** The comment under a page-space point (topmost first), if any. */
    fun at(doc: PDFDocument, pageNumber: Int, point: Point): Info? =
        list(doc, pageNumber).lastOrNull { Pdf.contains(Pdf.grow(it.rect, 4f), point.x, point.y) }

    fun delete(doc: PDFDocument, pageNumber: Int, id: Int) {
        annotatable(doc)
        val page = Pdf.page(doc, pageNumber)
        page.deleteAnnotation(find(page, id))
    }

    fun setContents(doc: PDFDocument, pageNumber: Int, id: Int, text: String) {
        annotatable(doc)
        val page = Pdf.page(doc, pageNumber)
        val a = find(page, id)
        a.contents = text
        a.modificationDate = Date()
        a.update()
    }

    fun setColor(doc: PDFDocument, pageNumber: Int, id: Int, argb: Int) {
        annotatable(doc)
        val page = Pdf.page(doc, pageNumber)
        val a = find(page, id)
        a.color = colorOf(argb)
        a.update()
    }

    /** Moves a comment by a page-space offset. */
    fun move(doc: PDFDocument, pageNumber: Int, id: Int, dx: Float, dy: Float) {
        annotatable(doc)
        val page = Pdf.page(doc, pageNumber)
        val a = find(page, id)
        when {
            a.type == PDFAnnotation.TYPE_INK -> a.inkList = a.inkList.map { s -> s.map { Point(it.x + dx, it.y + dy) }.toTypedArray() }.toTypedArray()
            a.type == PDFAnnotation.TYPE_LINE -> { val l = a.line; a.setLine(Point(l[0].x + dx, l[0].y + dy), Point(l[1].x + dx, l[1].y + dy)) }
            a.hasQuadPoints() -> a.quadPoints = a.quadPoints.map { q -> Quad(q.ul_x + dx, q.ul_y + dy, q.ur_x + dx, q.ur_y + dy, q.ll_x + dx, q.ll_y + dy, q.lr_x + dx, q.lr_y + dy) }.toTypedArray()
            else -> { val r = a.rect; a.rect = Rect(r.x0 + dx, r.y0 + dy, r.x1 + dx, r.y1 + dy) }
        }
        a.update()
    }

    /** Burns comments (and, with [widgets], form fields) into the page content. */
    fun flatten(doc: PDFDocument, widgets: Boolean = false) {
        Pdf.require(doc, Document.PERMISSION_EDIT, "flattening")
        doc.bake(true, widgets)
    }
}
