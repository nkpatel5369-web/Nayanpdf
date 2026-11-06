package npdf.engine

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import kotlin.math.abs

/**
 * Permanent redaction (same rules as the Windows engine): areas are first
 * marked, then applied. Applying removes the text, image pixels and line art
 * under each mark from the file itself and draws a black box; the result is
 * then checked: the marked text must be gone and each area must render dark.
 */
object Redaction {

    /** Built-in patterns, identical to the Windows engine's. */
    val PATTERNS = linkedMapOf(
        "pan" to (Regex("(?<![A-Z])[A-Z]{5}[0-9]{4}[A-Z](?![A-Z])") to "PAN (India)"),
        "gstin" to (Regex("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\\b") to "GSTIN (India)"),
        "aadhaar" to (Regex("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b") to "Aadhaar-like 12-digit number"),
        "ifsc" to (Regex("\\b[A-Z]{4}0[A-Z0-9]{6}\\b") to "IFSC code"),
        "email" to (Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "E-mail address"),
        "phone" to (Regex("(?<!\\d)(?:\\+91[ -]?)?[6-9]\\d{4}[ -]?\\d{5}(?!\\d)") to "Mobile number (India)"),
    )

    class Match(val page: Int, val text: String, val quads: List<Quad>, val label: String)
    class Report(
        val pages: Int, val areas: Int, val textRemaining: List<String>, val darkAreas: Int, val checkedAreas: Int,
        val textMoved: List<String> = emptyList(),
    ) {
        val passed: Boolean get() = textRemaining.isEmpty() && textMoved.isEmpty() && darkAreas == checkedAreas
    }

    /**
     * Finds text to redact: plain words or phrases in [terms] and the named
     * [patterns]. Matches are located on the page character by character, so
     * each match gets the exact quads of its characters.
     */
    fun find(doc: PDFDocument, terms: List<String>, patterns: List<String>, matchCase: Boolean = false, pages: String? = null): List<Match> {
        val regs = mutableListOf<Pair<Regex, String>>()
        for (t in terms.map { it.trim() }.filter { it.isNotEmpty() }) {
            regs.add(Regex(Regex.escape(t), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)) to t)
        }
        for (p in patterns) {
            val (re, label) = PATTERNS[p] ?: throw PdfError(Codes.INVALID, "Unknown pattern: $p")
            regs.add(re to label)
        }
        val out = mutableListOf<Match>()
        for (i in Pdf.parsePages(pages, doc.countPages())) {
            val page = doc.loadPage(i) as PDFPage
            val text = PageText.of(page)
            for (ln in text.lines) {
                val chars = ln.spans.flatMap { it.chars }
                // Line text with one entry per character so string offsets map to characters.
                val sb = StringBuilder()
                val idx = mutableListOf<Int>()
                chars.forEachIndexed { k, ch ->
                    val s = String(Character.toChars(ch.c))
                    sb.append(s); repeat(s.length) { idx.add(k) }
                }
                val lineText = sb.toString()
                for ((re, label) in regs) {
                    for (m in re.findAll(lineText)) {
                        if (m.value.isBlank()) continue
                        val first = idx[m.range.first]; val last = idx[m.range.last]
                        val quads = chars.subList(first, last + 1).map { it.quad }
                        out.add(Match(i + 1, m.value, listOf(merge(quads)), label))
                    }
                }
            }
        }
        return out
    }

    private fun merge(quads: List<Quad>): Quad {
        val r = quads.map { Pdf.quadRect(it) }.reduce(Pdf::union)
        return Pdf.rectQuad(r)
    }

    /** Marks an area or text (quads) for redaction. Nothing is removed until [apply]. */
    fun mark(doc: PDFDocument, pageNumber: Int, rect: Rect? = null, quads: List<Quad>? = null, note: String? = null): Int {
        Pdf.require(doc, Document.PERMISSION_EDIT, "redaction")
        val page = Pdf.page(doc, pageNumber)
        val a = page.createAnnotation(PDFAnnotation.TYPE_REDACT)
        if (!quads.isNullOrEmpty()) {
            quads.forEach { a.addQuadPoint(it) }
            a.rect = quads.map { Pdf.quadRect(it) }.reduce(Pdf::union)
        } else if (rect != null) {
            a.rect = rect
        } else throw PdfError(Codes.INVALID, "Nothing to mark.")
        note?.let { a.contents = it }
        a.update()
        return a.getObject().asIndirect()
    }

    fun marks(doc: PDFDocument): Map<Int, List<Rect>> {
        val out = linkedMapOf<Int, List<Rect>>()
        for (i in 0 until doc.countPages()) {
            val page = doc.loadPage(i) as PDFPage
            val r = (page.annotations ?: emptyArray()).filter { it.type == PDFAnnotation.TYPE_REDACT }.flatMap { a ->
                if (a.hasQuadPoints() && a.quadPointCount > 0) a.quadPoints.map { Pdf.quadRect(it) } else listOf(a.rect)
            }
            if (r.isNotEmpty()) out[i + 1] = r
        }
        return out
    }

    /**
     * Applies every redaction mark. [secrets] is the text that must be gone
     * afterwards (the marked words); it is checked on every page together with
     * the marked areas themselves.
     */
    fun apply(doc: PDFDocument, secrets: List<String> = emptyList(), removeGraphics: Boolean = true): Report {
        Pdf.require(doc, Document.PERMISSION_EDIT, "redaction")
        val marked = marks(doc)
        if (marked.isEmpty()) throw PdfError(Codes.INVALID, "There are no redaction marks to apply.")
        // Where the characters outside the marks are, to check afterwards that none of them moved.
        val before = marked.mapValues { (pageNo, areas) -> Anchors(PageText.of(Pdf.page(doc, pageNo)), areas) }
        for (pageNo in marked.keys) {
            val page = Pdf.page(doc, pageNo)
            page.applyRedactions(true, PDFPage.REDACT_IMAGE_PIXELS,
                if (removeGraphics) PDFPage.REDACT_LINE_ART_REMOVE_IF_TOUCHED else PDFPage.REDACT_LINE_ART_NONE,
                PDFPage.REDACT_TEXT_REMOVE)
        }
        // Verification.
        val remaining = mutableListOf<String>()
        val moved = mutableListOf<String>()
        var dark = 0
        var checked = 0
        val wanted = secrets.map { it.trim() }.filter { it.isNotEmpty() }
        for (i in 0 until doc.countPages()) {
            val page = doc.loadPage(i) as PDFPage
            val text = PageText.of(page)
            for (s in wanted) if (text.search(s).isNotEmpty()) remaining.add("page ${i + 1}: \"$s\"")
            val areas = marked[i + 1] ?: continue
            before[i + 1]?.moved(text)?.let { moved.add("page ${i + 1}: \"$it\"") }
            for (r in areas) {
                val inside = text.textIn(Pdf.grow(r, -0.5f)).trim()
                if (inside.isNotEmpty()) remaining.add("page ${i + 1}: \"$inside\"")
                checked++
                if (rendersDark(page, r)) dark++
            }
        }
        val report = Report(marked.size, marked.values.sumOf { it.size }, remaining, dark, checked, moved)
        if (!report.passed) {
            throw PdfError(Codes.VERIFY_FAILED, "Redaction could not be verified (" +
                when {
                    remaining.isNotEmpty() -> "text still present: ${remaining.take(3).joinToString("; ")}"
                    moved.isNotEmpty() -> "text next to a marked area moved: ${moved.take(3).joinToString("; ")}"
                    else -> "an area does not render as covered"
                } + "). The document was not changed.")
        }
        return report
    }

    /**
     * The characters of a page that do not touch any marked area, by position.
     * After redaction each of them must still be where it was: removing marked
     * text must never shift the text around it.
     */
    private class Anchors(text: PageText, private val areas: List<Rect>) {
        private val byChar = HashMap<Int, MutableList<Rect>>()

        init {
            forEachKept(text) { ch -> byChar.getOrPut(ch.c) { mutableListOf() }.add(ch.bbox) }
        }

        private fun forEachKept(text: PageText, f: (TextChar) -> Unit) {
            for (ln in text.lines) for (sp in ln.spans) for (ch in sp.chars) {
                if (Character.isWhitespace(ch.c)) continue
                val b = ch.bbox
                if (areas.any { Pdf.area(Pdf.intersect(Pdf.grow(it, 0.5f), b)) > 0f }) continue
                f(ch)
            }
        }

        /** The first character (with its neighbours) found away from where it was, or null. */
        fun moved(after: PageText): String? {
            var found: String? = null
            for (ln in after.lines) {
                val chars = ln.spans.flatMap { it.chars }
                chars.forEachIndexed { k, ch ->
                    if (found != null || Character.isWhitespace(ch.c)) return@forEachIndexed
                    val b = ch.bbox
                    if (areas.any { Pdf.area(Pdf.intersect(Pdf.grow(it, 0.5f), b)) > 0f }) return@forEachIndexed
                    val same = byChar[ch.c]?.any { abs(it.x0 - b.x0) < 0.6f && abs(it.y0 - b.y0) < 0.6f } ?: false
                    if (!same) found = buildString {
                        for (j in maxOf(0, k - 3)..minOf(chars.size - 1, k + 3)) appendCodePoint(chars[j].c)
                    } + " at x ${b.x0.toInt()}"
                }
                if (found != null) break
            }
            return found
        }
    }

    /** True when the centre part of [r] renders (almost) black. */
    private fun rendersDark(page: PDFPage, r: Rect): Boolean {
        val w = r.x1 - r.x0; val h = r.y1 - r.y0
        if (w < 1f || h < 1f) return true
        val inner = Rect(r.x0 + w * 0.2f, r.y0 + h * 0.2f, r.x1 - w * 0.2f, r.y1 - h * 0.2f)
        val scale = minOf(4f, 64f / maxOf(1f, inner.x1 - inner.x0))
        val ix0 = (inner.x0 * scale).toInt(); val iy0 = (inner.y0 * scale).toInt()
        val iw = maxOf(1, ((inner.x1 - inner.x0) * scale).toInt()); val ih = maxOf(1, ((inner.y1 - inner.y0) * scale).toInt())
        val pix = Pixmap(ColorSpace.DeviceRGB, ix0, iy0, iw, ih, false)
        try {
            pix.clear(255)
            val dev = DrawDevice(pix)
            try {
                page.run(dev, Matrix.Scale(scale), null)
                dev.close()
            } finally {
                dev.destroy()
            }
            val samples = pix.samples
            val n = pix.numberOfComponents
            val stride = pix.stride
            var darkCount = 0
            var total = 0
            for (y in 0 until ih) for (x in 0 until iw) {
                val o = y * stride + x * n
                val lum = ((samples[o].toInt() and 0xFF) + (samples[o + 1].toInt() and 0xFF) + (samples[o + 2].toInt() and 0xFF)) / 3
                if (lum < 60) darkCount++
                total++
            }
            return total == 0 || darkCount >= total * 0.95
        } finally {
            pix.destroy()
        }
    }
}
