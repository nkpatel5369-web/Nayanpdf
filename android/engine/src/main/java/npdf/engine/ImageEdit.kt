package npdf.engine

import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect

/**
 * Pictures in the page content: insert, and move, resize, delete or replace an
 * existing one (port of the Windows engine's edit.insertImage, edit.image and
 * edit.listImages). Rectangles are in page space.
 */
object ImageEdit {

    class Placement(val index: Int, val rect: Rect, val name: String)
    class EditResult(val oldRect: Rect, val imageDataRemoved: Boolean?)

    /** Maps the unit square of an image with EXIF [orientation] onto [box] (PDF user space, y up). */
    fun placementMatrix(box: Rect, orientation: Int): Matrix {
        val bx = box.x0; val by = box.y0
        val w = box.x1 - box.x0; val h = box.y1 - box.y0
        return when (orientation) {
            2 -> Matrix(-w, 0f, 0f, h, bx + w, by)
            3 -> Matrix(-w, 0f, 0f, -h, bx + w, by + h)
            4 -> Matrix(w, 0f, 0f, -h, bx, by + h)
            5 -> Matrix(0f, -h, -w, 0f, bx + w, by + h)
            6 -> Matrix(0f, -h, w, 0f, bx, by + h)
            7 -> Matrix(0f, h, w, 0f, bx, by)
            8 -> Matrix(0f, h, -w, 0f, bx + w, by)
            else -> Matrix(w, 0f, 0f, h, bx, by)
        }
    }

    /** Width and height of the image as displayed (EXIF orientations 5-8 swap them). */
    fun displaySize(image: Image): Pair<Float, Float> {
        val o = image.orientation
        return if (o in 5..8) image.height.toFloat() to image.width.toFloat() else image.width.toFloat() to image.height.toFloat()
    }

    /** Page-space rectangle to the PDF user-space box it covers. */
    fun toUser(page: PDFPage, r: Rect): Rect {
        val inv = Matrix.Inverted(page.getTransform())
        val pts = listOf(Point(r.x0, r.y0), Point(r.x1, r.y0), Point(r.x0, r.y1), Point(r.x1, r.y1)).map { it.transform(inv) }
        return Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    fun toPage(page: PDFPage, r: Rect): Rect {
        val m = page.getTransform()
        val pts = listOf(Point(r.x0, r.y0), Point(r.x1, r.y0), Point(r.x0, r.y1), Point(r.x1, r.y1)).map { it.transform(m) }
        return Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    private fun fit(box: Rect, w: Float, h: Float): Rect {
        val bw = box.x1 - box.x0; val bh = box.y1 - box.y0
        val s = minOf(bw / w, bh / h)
        val nw = w * s; val nh = h * s
        val x = box.x0 + (bw - nw) / 2; val y = box.y0 + (bh - nh) / 2
        return Rect(x, y, x + nw, y + nh)
    }

    /**
     * Draws an image (JPEG, PNG and other formats MuPDF reads) into the page
     * content inside [rect]. JPEG data is embedded as is; EXIF orientation is
     * honoured.
     */
    fun insertImage(doc: PDFDocument, pageNumber: Int, rect: Rect, imageBytes: ByteArray, keepAspect: Boolean = true, behind: Boolean = false): Rect {
        Pdf.require(doc, com.artifex.mupdf.fitz.Document.PERMISSION_EDIT, "editing")
        val page = Pdf.page(doc, pageNumber)
        val image = try { Image(imageBytes) } catch (e: RuntimeException) {
            throw PdfError(Codes.INVALID, "The picture could not be read.")
        }
        var userBox = toUser(page, rect)
        // Orientation of the page itself: keep the picture upright as the page is shown.
        val rot = ((page.getObject().getInheritable("Rotate").takeIf { it.isNumber }?.asInteger() ?: 0) % 360 + 360) % 360
        val (dw, dh) = displaySize(image)
        if (keepAspect) userBox = if (rot == 90 || rot == 270) fit(userBox, dh, dw) else fit(userBox, dw, dh)
        val m = Matrix(placementMatrix(Rect(0f, 0f, 1f, 1f), image.orientation), pageUprightMatrix(rot, userBox))
        val name = Pdf.addResource(doc, page, "XObject", "NpI", doc.addImage(image))
        val ops = "q ${Pdf.n(m.a)} ${Pdf.n(m.b)} ${Pdf.n(m.c)} ${Pdf.n(m.d)} ${Pdf.n(m.e)} ${Pdf.n(m.f)} cm /$name Do Q\n"
        if (behind) {
            val old = Pdf.pageContent(page)
            Pdf.setPageContent(doc, page, ops.toByteArray(Charsets.ISO_8859_1) + "\n".toByteArray() + old)
        } else {
            Pdf.appendContent(doc, page, ops)
        }
        return toPage(page, userBox)
    }

    /**
     * Matrix from the unit square to [box] such that content upright in the unit
     * square appears upright on a page shown with /Rotate [rot].
     */
    private fun pageUprightMatrix(rot: Int, box: Rect): Matrix {
        val w = box.x1 - box.x0; val h = box.y1 - box.y0
        return when (rot) {
            90 -> Matrix(0f, h, -w, 0f, box.x1, box.y0)
            180 -> Matrix(-w, 0f, 0f, -h, box.x1, box.y1)
            270 -> Matrix(0f, -h, w, 0f, box.x0, box.y1)
            else -> Matrix(w, 0f, 0f, h, box.x0, box.y0)
        }
    }

    /** Directly editable image placements on a page (drawn by the page's own content), in page space. */
    fun list(doc: PDFDocument, pageNumber: Int): List<Placement> {
        val page = Pdf.page(doc, pageNumber)
        val (_, found) = ContentStream.images(Pdf.pageContent(page), page.getObject().getInheritable("Resources"))
        return found.filter { it.topLevel }.mapIndexed { k, p -> Placement(k, toPage(page, p.bbox), p.name) }
    }

    /**
     * Moves or resizes ("transform"), deletes or replaces the placement whose
     * box best matches [rect]. [newRect] is the new box for "transform".
     */
    fun edit(doc: PDFDocument, pageNumber: Int, rect: Rect, action: String, newRect: Rect? = null, replacement: ByteArray? = null): EditResult {
        if (action !in setOf("transform", "delete", "replace")) throw PdfError(Codes.INVALID, "Unknown image action.")
        Pdf.require(doc, com.artifex.mupdf.fitz.Document.PERMISSION_EDIT, "editing")
        val page = Pdf.page(doc, pageNumber)
        val data = Pdf.pageContent(page)
        val resources = page.getObject().getInheritable("Resources")
        val (_, found) = ContentStream.images(data, resources)
        val target = toUser(page, rect)
        var best: ContentStream.ImagePlacement? = null
        var bestScore = 0f
        for (p in found.filter { it.topLevel }) {
            val bb = p.bbox
            val inter = Pdf.area(Pdf.intersect(bb, target))
            if (inter <= 0f) continue
            val score = inter / maxOf(1e-6f, Pdf.area(Pdf.union(bb, target)))
            if (score > bestScore) { bestScore = score; best = p }
        }
        val hit = best
        if (hit == null || bestScore < 0.6f) {
            throw PdfError(Codes.NOT_SUPPORTED, "The selected picture is not directly editable (it may be part of a form object, a pattern or a scanned page background). Use Add Image to add a replacement.")
        }
        val oldBox = hit.bbox
        val before = data.copyOfRange(0, hit.start)
        val after = data.copyOfRange(hit.end, data.size)
        val middle: ByteArray = when (action) {
            "transform" -> {
                val nb = toUser(page, newRect ?: throw PdfError(Codes.INVALID, "A new position is required to move or resize a picture."))
                val ow = maxOf(1e-6f, oldBox.x1 - oldBox.x0); val oh = maxOf(1e-6f, oldBox.y1 - oldBox.y0)
                // T maps the old box onto the new one; A = C T C^-1 so that A x C = C x T.
                val t = Matrix(Matrix(Matrix.Translate(-oldBox.x0, -oldBox.y0), Matrix.Scale((nb.x1 - nb.x0) / ow, (nb.y1 - nb.y0) / oh)), Matrix.Translate(nb.x0, nb.y0))
                val a = Matrix(Matrix(hit.ctm, t), Matrix.Inverted(hit.ctm))
                val orig = data.copyOfRange(hit.start, hit.end)
                "q ${Pdf.n(a.a)} ${Pdf.n(a.b)} ${Pdf.n(a.c)} ${Pdf.n(a.d)} ${Pdf.n(a.e)} ${Pdf.n(a.f)} cm ".toByteArray(Charsets.ISO_8859_1) + orig + " Q".toByteArray()
            }
            else -> ByteArray(0)
        }
        val newData = before + " ".toByteArray() + middle + " ".toByteArray() + after
        Pdf.setPageContent(doc, page, newData)
        var removed: Boolean? = null
        if (action != "transform") {
            if (hit.name == "inline") removed = true else {
                // Drop the picture from this page's resources when nothing on the page still
                // draws it, so its pixels are not left in the saved file.
                val stillUsed = ContentStream.parse(newData).any { it.name == "Do" && (it.operands.firstOrNull() as? ContentStream.Name)?.value == hit.name }
                removed = false
                if (!stillUsed) {
                    val res = Pdf.resources(doc, page)
                    val xo = res.get("XObject")
                    if (xo.isDictionary) {
                        val copy = doc.newDictionary()
                        for (k in Pdf.dictKeys(xo)) if (k != hit.name) copy.put(k, xo.get(k))
                        res.put("XObject", copy)
                        removed = true
                    }
                }
            }
        }
        if (action == "replace") {
            val bytes = replacement ?: throw PdfError(Codes.INVALID, "Choose the replacement picture.")
            insertImage(doc, pageNumber, toPage(page, oldBox), bytes, keepAspect = true)
        }
        return EditResult(toPage(page, oldBox), removed)
    }
}
