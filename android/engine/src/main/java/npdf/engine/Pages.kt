package npdf.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import java.io.File

/** Page organisation, merging, splitting and new PDFs from pictures. Page numbers are 1-based. */
object Pages {

    private fun assemble(doc: PDFDocument) = Pdf.require(doc, Document.PERMISSION_ASSEMBLE, "changing pages")

    /** Rotates pages by a multiple of 90 degrees (clockwise). */
    fun rotate(doc: PDFDocument, pages: List<Int>, degrees: Int) {
        assemble(doc)
        if (degrees % 90 != 0) throw PdfError(Codes.INVALID, "Rotation must be a multiple of 90 degrees.")
        for (n in pages) {
            val obj = doc.findPage(n - 1)
            val cur = obj.getInheritable("Rotate").takeIf { it.isNumber }?.asInteger() ?: 0
            obj.put("Rotate", ((cur + degrees) % 360 + 360) % 360)
        }
    }

    fun delete(doc: PDFDocument, pages: List<Int>) {
        assemble(doc)
        val count = doc.countPages()
        val set = pages.toSortedSet()
        if (set.isEmpty()) return
        if (set.size >= count) throw PdfError(Codes.INVALID, "A PDF needs at least one page: you cannot delete them all.")
        for (n in set.reversed()) {
            if (n < 1 || n > count) throw PdfError(Codes.INVALID, "Page $n does not exist.")
            doc.deletePage(n - 1)
        }
    }

    /** Puts the pages in [order] (1-based, a permutation of all pages, or a selection to keep). */
    fun reorder(doc: PDFDocument, order: List<Int>) {
        assemble(doc)
        val count = doc.countPages()
        if (order.isEmpty() || order.any { it < 1 || it > count }) throw PdfError(Codes.INVALID, "Invalid page order.")
        doc.rearrangePages(order.map { it - 1 }.toIntArray())
        if (doc.countPages() != order.size) throw PdfError(Codes.VERIFY_FAILED, "The page order could not be applied.")
    }

    /** Inserts a blank page after page [after] (0 = at the start), the size of the neighbouring page. */
    fun insertBlank(doc: PDFDocument, after: Int, size: Rect? = null) {
        assemble(doc)
        val count = doc.countPages()
        val ref = Pdf.page(doc, after.coerceIn(1, count))
        val box = size ?: ref.bounds
        val obj = doc.addPage(Rect(0f, 0f, box.x1 - box.x0, box.y1 - box.y0), 0, doc.newDictionary(), "")
        doc.insertPage(after, obj)
    }

    /** Inserts pages of another PDF after page [after] (0 = at the start). */
    fun insertFrom(doc: PDFDocument, after: Int, src: PDFDocument, pages: String? = null) {
        assemble(doc)
        val sel = Pdf.parsePages(pages, src.countPages())
        var at = after
        val map = doc.newPDFGraftMap()
        for (i in sel) {
            map.graftPage(at, src, i)
            at++
        }
    }

    /** Writes [pages] of [doc] to a new PDF at [dest]. */
    fun extract(doc: PDFDocument, pages: List<Int>, dest: File) {
        Pdf.require(doc, Document.PERMISSION_ASSEMBLE, "extracting pages")
        val out = PDFDocument()
        try {
            val map = out.newPDFGraftMap()
            pages.forEachIndexed { k, n -> map.graftPage(k, doc, n - 1) }
            Pdf.save(out, dest, "garbage=compact,compress")
        } finally {
            out.destroy()
        }
        verify(dest, pages.size)
    }

    class Input(val path: String, val password: String? = null, val pages: String? = null)

    /** Combines PDFs (and pictures, one page each) in the given order into [dest]. */
    fun merge(inputs: List<Input>, dest: File, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
        if (inputs.isEmpty()) throw PdfError(Codes.INVALID, "Add the files to combine.")
        val out = PDFDocument()
        var expected = 0
        try {
            inputs.forEachIndexed { k, input ->
                progress(k, inputs.size)
                if (isImage(input.path)) {
                    addImagePage(out, File(input.path).readBytes(), PageSize.FIT, 0f)
                    expected++
                } else {
                    val src = Pdf.open(input.path, input.password)
                    try {
                        Pdf.require(src, Document.PERMISSION_ASSEMBLE, "combining ${File(input.path).name}")
                        val sel = Pdf.parsePages(input.pages, src.countPages())
                        val map = out.newPDFGraftMap()
                        for (i in sel) map.graftPage(out.countPages(), src, i)
                        expected += sel.size
                    } finally {
                        src.destroy()
                    }
                }
            }
            Pdf.save(out, dest, "garbage=compact,compress")
        } finally {
            out.destroy()
        }
        verify(dest, expected)
        return expected
    }

    /**
     * Splits into several PDFs: [ranges] such as "1-3,4-6" (one file per range),
     * or every [everyN] pages. Files are named base_1.pdf, base_2.pdf and so on.
     */
    fun split(doc: PDFDocument, dir: File, base: String, ranges: String? = null, everyN: Int? = null): List<File> {
        Pdf.require(doc, Document.PERMISSION_ASSEMBLE, "splitting")
        val count = doc.countPages()
        val groups: List<List<Int>> = when {
            !ranges.isNullOrBlank() -> ranges.split(',').map { part -> Pdf.parsePages(part, count).map { it + 1 } }
            everyN != null && everyN > 0 -> (1..count).chunked(everyN)
            else -> throw PdfError(Codes.INVALID, "Choose page ranges or a number of pages per file.")
        }
        val files = mutableListOf<File>()
        groups.forEachIndexed { k, g ->
            val f = uniqueFile(dir, "${base}_${k + 1}.pdf")
            extract(doc, g, f)
            files.add(f)
        }
        return files
    }

    enum class PageSize(val w: Float, val h: Float) { FIT(0f, 0f), A4(595.28f, 841.89f), LETTER(612f, 792f) }

    /** Adds one page showing a picture. JPEG data is kept as is; EXIF orientation is honoured. */
    fun addImagePage(doc: PDFDocument, bytes: ByteArray, size: PageSize, margin: Float) {
        val image = try { Image(bytes) } catch (e: RuntimeException) { throw PdfError(Codes.INVALID, "A picture could not be read.") }
        val (dw, dh) = ImageEdit.displaySize(image)
        // Pictures without resolution information are treated as 96 dpi.
        val xres = image.xResolution.takeIf { it > 0 } ?: 96
        val yres = image.yResolution.takeIf { it > 0 } ?: 96
        val natW = dw * 72f / (if (image.orientation in 5..8) yres else xres)
        val natH = dh * 72f / (if (image.orientation in 5..8) xres else yres)
        val (pw, ph) = when (size) {
            PageSize.FIT -> (natW + 2 * margin) to (natH + 2 * margin)
            else -> if (dw > dh) size.h to size.w else size.w to size.h
        }
        val availW = pw - 2 * margin; val availH = ph - 2 * margin
        val s = minOf(availW / natW, availH / natH, if (size == PageSize.FIT) 1f else Float.MAX_VALUE)
        val w = natW * s; val h = natH * s
        val box = Rect((pw - w) / 2, (ph - h) / 2, (pw - w) / 2 + w, (ph - h) / 2 + h)
        val m = ImageEdit.placementMatrix(box, image.orientation)
        val res = doc.newDictionary()
        val xo = doc.newDictionary()
        xo.put("Im1", doc.addImage(image))
        res.put("XObject", xo)
        val ops = "q ${Pdf.n(m.a)} ${Pdf.n(m.b)} ${Pdf.n(m.c)} ${Pdf.n(m.d)} ${Pdf.n(m.e)} ${Pdf.n(m.f)} cm /Im1 Do Q\n"
        val page = doc.addPage(Rect(0f, 0f, pw, ph), 0, res, ops)
        doc.insertPage(-1, page)
    }

    /** A new PDF from pictures, one per page. */
    fun imagesToPdf(images: List<ByteArray>, dest: File, size: PageSize = PageSize.A4, margin: Float = 18f, progress: (Int, Int) -> Unit = { _, _ -> }) {
        if (images.isEmpty()) throw PdfError(Codes.INVALID, "Add at least one picture.")
        val out = PDFDocument()
        try {
            images.forEachIndexed { k, b -> progress(k, images.size); addImagePage(out, b, size, margin) }
            Pdf.save(out, dest, "garbage=compact,compress")
        } finally {
            out.destroy()
        }
        verify(dest, images.size)
    }

    fun isImage(path: String) = path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "webp")

    /** Re-opens a written file and checks its page count. */
    fun verify(file: File, pages: Int) {
        val d = try { Pdf.open(file.absolutePath, null) } catch (e: PdfError) {
            if (e.code == Codes.PASSWORD_REQUIRED) return else throw PdfError(Codes.VERIFY_FAILED, "The new file could not be read back.")
        }
        try {
            if (d.countPages() != pages) throw PdfError(Codes.VERIFY_FAILED, "The new file has ${d.countPages()} pages instead of $pages.")
        } finally {
            d.destroy()
        }
    }

    /** [name] in [dir], with " (1)", " (2)"... added when a file of that name exists. Nothing is ever overwritten. */
    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var k = 1
        while (true) {
            f = File(dir, if (ext.isEmpty()) "$stem ($k)" else "$stem ($k).$ext")
            if (!f.exists()) return f
            k++
        }
    }
}
