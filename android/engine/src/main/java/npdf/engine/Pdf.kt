package npdf.engine

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import java.io.File
import java.util.Locale

/** A failure the user can act on. [code] is stable; [message] is shown as is. */
class PdfError(val code: String, message: String) : Exception(message)

object Codes {
    const val PASSWORD_REQUIRED = "PASSWORD_REQUIRED"
    const val PASSWORD_WRONG = "PASSWORD_WRONG"
    const val NOT_PDF = "NOT_PDF"
    const val DAMAGED = "DAMAGED"
    const val RESTRICTED = "PERMISSION_RESTRICTED"
    const val NOT_SUPPORTED = "NOT_SUPPORTED"
    const val INVALID = "INVALID_INPUT"
    const val VERIFY_FAILED = "VALIDATION_FAILED"
}

/** Opening and saving PDFs, and the page-level helpers every operation shares. */
object Pdf {

    /** Opens a PDF file; asks for [password] only when the file needs one. */
    fun open(path: String, password: String? = null): PDFDocument {
        val doc = try {
            Document.openDocument(path)
        } catch (e: RuntimeException) {
            throw PdfError(Codes.DAMAGED, "The file could not be opened as a PDF (${e.message ?: "unreadable"}).")
        }
        return asPdf(doc, password)
    }

    fun openBytes(bytes: ByteArray, password: String? = null): PDFDocument {
        val doc = try {
            Document.openDocument(bytes, "application/pdf")
        } catch (e: RuntimeException) {
            throw PdfError(Codes.DAMAGED, "The file could not be opened as a PDF (${e.message ?: "unreadable"}).")
        }
        return asPdf(doc, password)
    }

    private fun asPdf(doc: Document, password: String?): PDFDocument {
        val pdf = doc as? PDFDocument ?: doc.asPDF()
        if (pdf == null) {
            doc.destroy()
            throw PdfError(Codes.NOT_PDF, "This file is not a PDF.")
        }
        if (pdf.needsPassword()) {
            if (password.isNullOrEmpty()) throw PdfError(Codes.PASSWORD_REQUIRED, "This PDF is protected. Enter its password.")
            if (!pdf.authenticatePassword(password)) throw PdfError(Codes.PASSWORD_WRONG, "The password is not correct.")
        }
        return pdf
    }

    /** Refuses an action the document's permissions do not allow (an owner password lifts them). */
    fun require(doc: PDFDocument, permission: Int, what: String) {
        if (!doc.hasPermission(permission)) {
            throw PdfError(Codes.RESTRICTED, "The document's permissions do not allow $what. Open it with the permissions (owner) password.")
        }
    }

    /**
     * Writes [doc] to [dest] through a temporary file in the same folder, so a
     * failed save never leaves a half-written file. Encryption is kept unless
     * [options] says otherwise.
     */
    fun save(doc: PDFDocument, dest: File, options: String = "garbage=compact,compress") {
        val tmp = File(dest.parentFile, ".${dest.name}.${System.nanoTime()}.part")
        try {
            doc.save(tmp.absolutePath, options)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun pageCount(doc: PDFDocument): Int = doc.countPages()

    fun page(doc: PDFDocument, number: Int): PDFPage {
        if (number < 1 || number > doc.countPages()) throw PdfError(Codes.INVALID, "Page $number does not exist.")
        return doc.loadPage(number - 1) as PDFPage
    }

    /** Parses "1-3,5,8-" style ranges into zero-based indexes, in order, without duplicates. */
    fun parsePages(spec: String?, count: Int): List<Int> {
        if (spec.isNullOrBlank() || spec.trim().equals("all", true)) return (0 until count).toList()
        val out = LinkedHashSet<Int>()
        for (raw in spec.split(',')) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val m = Regex("""^(\d*)\s*-\s*(\d*)$""").find(part)
            if (m != null) {
                val a = m.groupValues[1].ifEmpty { "1" }.toInt()
                val b = m.groupValues[2].ifEmpty { count.toString() }.toInt()
                if (a < 1 || b > count || a > b) throw PdfError(Codes.INVALID, "Page range \"$part\" is outside 1-$count.")
                for (i in a..b) out.add(i - 1)
            } else {
                val n = part.toIntOrNull() ?: throw PdfError(Codes.INVALID, "\"$part\" is not a page number.")
                if (n < 1 || n > count) throw PdfError(Codes.INVALID, "Page $n is outside 1-$count.")
                out.add(n - 1)
            }
        }
        if (out.isEmpty()) throw PdfError(Codes.INVALID, "No pages selected.")
        return out.toList()
    }

    // ---------------------------------------------------------------- content

    /**
     * Appends [ops] to the page content. The existing content is first wrapped
     * in q/Q so that whatever graphics state it leaves behind cannot change how
     * the new operators draw.
     */
    fun appendContent(doc: PDFDocument, page: PDFPage, ops: String) {
        val pageObj = page.getObject()
        val contents = pageObj.get("Contents")
        val arr = doc.newArray()
        val existing = mutableListOf<PDFObject>()
        if (contents.isArray) {
            for (i in 0 until contents.size()) existing.add(contents.get(i))
        } else if (!contents.isNull) {
            existing.add(contents)
        }
        if (existing.isNotEmpty() && !isWrapped(existing)) {
            arr.push(doc.addStream("q\n"))
            existing.forEach { arr.push(it) }
            arr.push(doc.addStream("\nQ\n"))
        } else {
            existing.forEach { arr.push(it) }
        }
        arr.push(doc.addStream(ops))
        pageObj.put("Contents", arr)
    }

    private fun isWrapped(streams: List<PDFObject>): Boolean {
        if (streams.size < 2) return false
        val first = String(streams.first().readStream(), Charsets.ISO_8859_1).trim()
        val last = String(streams.last().readStream(), Charsets.ISO_8859_1).trim()
        return first == "q" && last.endsWith("Q") && last.length <= 2
    }

    /** All content streams of the page as one byte array (separated by newlines). */
    fun pageContent(page: PDFPage): ByteArray {
        val contents = page.getObject().get("Contents")
        val out = java.io.ByteArrayOutputStream()
        if (contents.isArray) {
            for (i in 0 until contents.size()) {
                out.write(contents.get(i).readStream()); out.write('\n'.code)
            }
        } else if (!contents.isNull) {
            out.write(contents.readStream())
        }
        return out.toByteArray()
    }

    /** Replaces all page content streams with one stream. */
    fun setPageContent(doc: PDFDocument, page: PDFPage, data: ByteArray) {
        page.getObject().put("Contents", doc.addStream(Buffer().apply { writeBytes(data) }))
    }

    /** The page's own (possibly inherited) resource dictionary, made local and writable. */
    fun resources(doc: PDFDocument, page: PDFPage): PDFObject {
        val pageObj = page.getObject()
        val own = pageObj.get("Resources")
        if (own.isDictionary) return own
        val inherited = pageObj.getInheritable("Resources")
        val res = doc.newDictionary()
        if (inherited.isDictionary) {
            for (key in dictKeys(inherited)) res.put(key, inherited.get(key))
        }
        pageObj.put("Resources", doc.addObject(res))
        return pageObj.get("Resources")
    }

    /** Registers [obj] under a new name in the page's [category] resources ("Font", "XObject", "ExtGState"). */
    fun addResource(doc: PDFDocument, page: PDFPage, category: String, prefix: String, obj: PDFObject): String {
        val res = resources(doc, page)
        var dict = res.get(category)
        if (!dict.isDictionary) {
            res.put(category, doc.newDictionary())
            dict = res.get(category)
        }
        // Reuse an existing entry for the same object.
        for (key in dictKeys(dict)) {
            val v = dict.get(key)
            if (v.isIndirect && obj.isIndirect && v.asIndirect() == obj.asIndirect()) return key
        }
        var n = 1
        while (!dict.get("$prefix$n").isNull) n++
        dict.put("$prefix$n", obj)
        return "$prefix$n"
    }

    fun dictKeys(dict: PDFObject): List<String> {
        val keys = mutableListOf<String>()
        if (!dict.isDictionary) return keys
        // PDFObject iteration over a dictionary yields its keys as name objects.
        val it = dict.iterator()
        while (it.hasNext()) keys.add(it.next().asName())
        return keys
    }

    // --------------------------------------------------------------- geometry

    /** Page space (MuPDF: origin top-left, y down) to PDF user space (origin bottom-left, y up). */
    fun toPdfSpace(page: PDFPage, p: Point): Point {
        val inv = Matrix.Inverted(page.getTransform())
        return Point(p).transform(inv)
    }

    fun toPageSpace(page: PDFPage, p: Point): Point = Point(p).transform(page.getTransform())

    fun union(a: Rect, b: Rect): Rect = Rect(minOf(a.x0, b.x0), minOf(a.y0, b.y0), maxOf(a.x1, b.x1), maxOf(a.y1, b.y1))

    fun intersect(a: Rect, b: Rect): Rect = Rect(maxOf(a.x0, b.x0), maxOf(a.y0, b.y0), minOf(a.x1, b.x1), minOf(a.y1, b.y1))

    fun area(r: Rect): Float = if (r.x1 <= r.x0 || r.y1 <= r.y0) 0f else (r.x1 - r.x0) * (r.y1 - r.y0)

    fun contains(r: Rect, x: Float, y: Float): Boolean = x >= r.x0 && x <= r.x1 && y >= r.y0 && y <= r.y1

    fun grow(r: Rect, d: Float): Rect = Rect(r.x0 - d, r.y0 - d, r.x1 + d, r.y1 + d)

    fun quadRect(q: Quad): Rect = Rect(
        minOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x), minOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y),
        maxOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x), maxOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y),
    )

    fun rectQuad(r: Rect): Quad = Quad(r.x0, r.y0, r.x1, r.y0, r.x0, r.y1, r.x1, r.y1)

    /** Number formatting for content streams: no locale, no exponent. */
    fun n(v: Float): String = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    fun rgb(argb: Int): FloatArray = floatArrayOf(
        ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f,
    )

    /** Literal PDF string with the characters that need escaping escaped. */
    fun literal(bytes: ByteArray): String {
        val sb = StringBuilder("(")
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when (c) {
                '('.code, ')'.code, '\\'.code -> sb.append('\\').append(c.toChar())
                in 32..126 -> sb.append(c.toChar())
                else -> sb.append('\\').append(String.format(Locale.ROOT, "%03o", c))
            }
        }
        return sb.append(')').toString()
    }

    fun hex(bytes: ByteArray): String = buildString {
        append('<')
        for (b in bytes) append(String.format(Locale.ROOT, "%02X", b.toInt() and 0xFF))
        append('>')
    }
}
