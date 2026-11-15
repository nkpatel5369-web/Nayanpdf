package npdf.engine

import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import java.io.File
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Editing of existing text (port of the Windows engine's edit.text,
 * edit.replaceAll and edit.insertText).
 *
 * Edits change the page content itself; nothing is laid over the old text:
 *  1. the original glyphs are removed from the content (MuPDF's redaction in
 *     text-only mode, so pictures and lines under the text are untouched);
 *  2. the new text is written into the page content in the original embedded
 *     font when its subset has every character needed, otherwise in a similar
 *     substitute font, which the result names;
 *  3. the edited area is read back and compared with the requested text.
 *
 * All rectangles and points are in page space (MuPDF: origin top-left, y down),
 * the space the viewer works in. Callers wrap each call in a journal operation
 * and abandon it when a [PdfError] is thrown, so a failed edit changes nothing.
 */
object TextEdit {

    class Result(val font: String, val fontKind: String, val warnings: List<String>, val newBox: Rect)
    class ReplaceResult(val replaced: Int, val found: Int, val skipped: List<String>, val warnings: List<String>)

    private val WINANSI: Charset = Charset.forName("windows-1252")

    // ------------------------------------------------------------------ writers

    interface Writer {
        val label: String
        val kind: String
        var size: Float
        var hscale: Float
        fun canWrite(text: String): Boolean
        fun width(text: String): Float
        fun ops(origin: Point, text: String): String
    }

    /** Text matrix that draws upright in page space at [origin], whatever the page rotation. */
    private fun textMatrix(page: PDFPage, origin: Point, hscale: Float): String {
        val inv = Matrix.Inverted(page.getTransform())
        val o = Point(origin).transform(inv)
        var ux = inv.a; var uy = inv.b
        var vx = -inv.c; var vy = -inv.d
        val lu = hypot(ux, uy).takeIf { it > 0f } ?: 1f
        val lv = hypot(vx, vy).takeIf { it > 0f } ?: 1f
        ux /= lu; uy /= lu; vx /= lv; vy /= lv
        val tz = if (abs(hscale - 100f) > 0.01f) "${Pdf.n(hscale)} Tz " else ""
        return "$tz${Pdf.n(ux)} ${Pdf.n(uy)} ${Pdf.n(vx)} ${Pdf.n(vy)} ${Pdf.n(o.x)} ${Pdf.n(o.y)} Tm"
    }

    private fun colorOps(argb: Int): String {
        val c = Pdf.rgb(argb)
        return "${Pdf.n(c[0])} ${Pdf.n(c[1])} ${Pdf.n(c[2])} rg"
    }

    /** Writes with the document's own embedded font. */
    class OriginalFontWriter(
        private val doc: PDFDocument, private val page: PDFPage, val codec: FontCodec,
        override var size: Float, private val argb: Int, spaceWidthUnits: Float?,
    ) : Writer {
        override val kind = "original"
        override val label = Span.stripSubset(codec.baseFont).ifEmpty { "original font" }
        override var hscale = 100f
        private val resName = Pdf.addResource(doc, page, "Font", "NpE", codec.font)
        val emulateSpace = " " !in codec.uniToCode
        private val spaceUnits = if (emulateSpace) (spaceWidthUnits ?: 250f) else codec.charWidth(" ")

        override fun canWrite(text: String) = codec.canEncode(if (emulateSpace) text.replace(" ", "") else text)

        override fun width(text: String): Float {
            var units = 0f
            text.codePoints().forEach { cp ->
                val ch = String(Character.toChars(cp))
                units += if (ch == " " && emulateSpace) spaceUnits else codec.charWidth(ch)
            }
            return units / 1000f * size * hscale / 100f
        }

        override fun ops(origin: Point, text: String): String {
            val show = if (emulateSpace) {
                val parts = mutableListOf<String>()
                text.split(" ").forEachIndexed { k, chunk ->
                    if (k > 0) parts.add(Pdf.n(-spaceUnits))
                    if (chunk.isNotEmpty()) parts.add(Pdf.hex(codec.encode(chunk)))
                }
                "[${parts.joinToString(" ")}] TJ"
            } else "${Pdf.hex(codec.encode(text))} Tj"
            return "q BT /$resName ${Pdf.n(size)} Tf ${colorOps(argb)} ${textMatrix(page, origin, hscale)} $show ET Q\n"
        }
    }

    /** Writes with a standard PDF font (Latin) or a Unicode system font. */
    class SubstituteFontWriter(
        private val doc: PDFDocument, private val page: PDFPage, private val sub: Substitute,
        override var size: Float, private val argb: Int,
    ) : Writer {
        override val kind = "substitute"
        override val label = sub.label
        override var hscale = 100f
        private val font: Font = sub.font
        private val resName: String by lazy {
            val obj = if (sub.unicode) doc.addFont(font) else doc.addSimpleFont(font, Font.SIMPLE_ENCODING_LATIN)
            Pdf.addResource(doc, page, "Font", "NpS", obj)
        }

        override fun canWrite(text: String): Boolean {
            if (!sub.unicode && !WINANSI.newEncoder().canEncode(text)) return false
            var ok = true
            text.codePoints().forEach { cp -> if (cp != ' '.code && font.encodeCharacter(cp) <= 0) ok = false }
            return ok
        }

        override fun width(text: String): Float {
            var w = 0f
            text.codePoints().forEach { cp -> w += font.advanceGlyph(font.encodeCharacter(cp), false) }
            return w * size * hscale / 100f
        }

        override fun ops(origin: Point, text: String): String {
            val bytes = if (sub.unicode) {
                val out = java.io.ByteArrayOutputStream()
                text.codePoints().forEach { cp ->
                    val gid = font.encodeCharacter(cp)
                    out.write((gid shr 8) and 0xFF); out.write(gid and 0xFF)
                }
                out.toByteArray()
            } else text.toByteArray(WINANSI)
            return "q BT /$resName ${Pdf.n(size)} Tf ${colorOps(argb)} ${textMatrix(page, origin, hscale)} ${Pdf.hex(bytes)} Tj ET Q\n"
        }
    }

    // -------------------------------------------------------------- substitutes

    class Substitute(val font: Font, val label: String, val unicode: Boolean)

    private val fontCache = HashMap<String, Font?>()

    private fun base14(serif: Boolean, mono: Boolean, bold: Boolean, italic: Boolean): Pair<String, String> {
        val name = when {
            mono -> "Courier" + when { bold && italic -> "-BoldOblique"; bold -> "-Bold"; italic -> "-Oblique"; else -> "" }
            serif -> "Times-" + when { bold && italic -> "BoldItalic"; bold -> "Bold"; italic -> "Italic"; else -> "Roman" }
            else -> "Helvetica" + when { bold && italic -> "-BoldOblique"; bold -> "-Bold"; italic -> "-Oblique"; else -> "" }
        }
        val label = name.replace("-Roman", "").replace("-", " ").replace("BoldOblique", "Bold Oblique").replace("BoldItalic", "Bold Italic")
        return name to label
    }

    /** Directories searched for Unicode fonts (Android's own fonts); tests may add one. */
    val fontDirs = mutableListOf("/system/fonts", "/product/fonts")

    private fun loadFont(path: String): Font? = fontCache.getOrPut(path) {
        try { Font(path) } catch (e: RuntimeException) { null }
    }

    /** A system font that has a glyph for every character of [text], preferring the style asked for. */
    fun unicodeFont(text: String, bold: Boolean, italic: Boolean): Substitute? {
        val cps = text.codePoints().filter { it != ' '.code }.toArray()
        val script = when {
            cps.any { it in 0x0900..0x097F } -> "Devanagari"
            cps.any { it in 0x0A80..0x0AFF } -> "Gujarati"
            else -> null
        }
        val files = fontDirs.flatMap { d -> File(d).listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && (it.name.endsWith(".ttf") || it.name.endsWith(".otf")) && it.length() < 4_000_000 }
        val styleWord = when { bold && italic -> "BoldItalic"; bold -> "Bold"; italic -> "Italic"; else -> "Regular" }
        fun rank(f: File): Int {
            val n = f.name
            var r = 0
            if (script != null) { if (n.contains(script)) r -= 100 else r += 50 }
            else { if (n.startsWith("Roboto-") || n.startsWith("NotoSans-")) r -= 100; if (n.startsWith("RobotoStatic")) r -= 90 }
            if (n.contains(styleWord)) r -= 10
            if (n.contains("UI")) r += 5
            return r
        }
        for (f in files.sortedWith(compareBy({ rank(it) }, { it.name })).take(40)) {
            val font = loadFont(f.absolutePath) ?: continue
            if (cps.all { font.encodeCharacter(it) > 0 }) {
                return Substitute(font, f.nameWithoutExtension.replace("-", " "), unicode = true)
            }
        }
        return null
    }

    private fun chooseSubstitute(span: Span?, bold: Boolean, italic: Boolean, text: String, family: String?): Substitute {
        val latin = WINANSI.newEncoder().canEncode(text)
        if (!latin || family == "unicode") {
            return unicodeFont(text, bold, italic)
                ?: throw PdfError(Codes.NOT_SUPPORTED, "The new text contains characters that none of the available fonts can display.")
        }
        val fontName = span?.fontName?.lowercase() ?: ""
        var serif = when (family) {
            "times", "serif" -> true
            "helvetica", "sans" -> false
            else -> span?.serif == true || listOf("times", "serif", "roman", "cambria", "caladea", "georgia", "garamond", "book").any { it in fontName }
        }
        if (family == null && listOf("arial", "helvetica", "calibri", "carlito", "sans", "verdana", "tahoma", "segoe").any { it in fontName }) serif = false
        val mono = family == "courier" || family == "mono" || (family == null && (span?.mono == true || "courier" in fontName || "mono" in fontName))
        val (name, label) = base14(serif && !mono, mono, bold, italic)
        val font = fontCache.getOrPut(name) { Font(name) }!!
        return Substitute(font, label, unicode = false)
    }

    private fun makeWriter(
        doc: PDFDocument, page: PDFPage, span: Span, text: String, size: Float, argb: Int,
        bold: Boolean?, italic: Boolean?, family: String?, spaceUnits: Float?,
    ): Pair<Writer, String?> {
        val wantBold = bold ?: span.bold
        val wantItalic = italic ?: span.italic
        val reason: String
        if (family.isNullOrEmpty() || family == "original") {
            if (wantBold == span.bold && wantItalic == span.italic) {
                val fontObj = FontCodec.findFont(page, span.fontName, span.text)
                if (fontObj != null) {
                    val codec = FontCodec(fontObj)
                    if (codec.ok) {
                        val w = OriginalFontWriter(doc, page, codec, size, argb, spaceUnits)
                        if (w.canWrite(text)) return w to null
                        val missing = codec.missingChars(if (w.emulateSpace) text.replace(" ", "") else text)
                        reason = "the embedded font subset does not contain: " + missing.take(8).joinToString(" ") { "'$it'" }
                    } else reason = codec.reason
                } else reason = "the original font could not be located"
            } else reason = "a different style (bold/italic) was requested"
        } else reason = "a different font was requested"
        val sub = chooseSubstitute(span, wantBold, wantItalic, text, family?.takeIf { it != "original" })
        val w = SubstituteFontWriter(doc, page, sub, size, argb)
        if (!w.canWrite(text)) throw PdfError(Codes.NOT_SUPPORTED, "The new text contains characters that none of the available fonts can display.")
        return w to reason
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Removes the text glyphs inside [rects] from the page content. Redaction
     * marks the user has drawn but not applied are kept (MuPDF would otherwise
     * apply them at the same time).
     */
    fun removeText(page: PDFPage, rects: List<Rect>, pixels: Boolean = false) {
        class Pending(val rect: Rect, val quads: Array<Quad>?, val color: FloatArray?, val contents: String?, val author: String?)
        val pending = mutableListOf<Pending>()
        for (a in page.annotations ?: emptyArray()) {
            if (a.type != PDFAnnotation.TYPE_REDACT) continue
            pending.add(Pending(a.rect, if (a.hasQuadPoints() && a.quadPointCount > 0) a.quadPoints else null,
                if (a.hasInteriorColor()) a.interiorColor else null, a.contents, if (a.hasAuthor()) a.author else null))
            page.deleteAnnotation(a)
        }
        for (r in rects) {
            val h = r.y1 - r.y0
            val w = r.x1 - r.x0
            // Shrink to the line's core so neighbouring lines and cells are not touched
            // (scans: the whole area, because its pixels are removed too).
            var core = if (pixels) Rect(r) else Rect(r.x0 + minOf(0.4f, w * 0.02f), r.y0 + h * 0.3f, r.x1 - minOf(0.4f, w * 0.02f), r.y1 - h * 0.3f)
            if (core.isEmpty) core = Rect(r)
            val a = page.createAnnotation(PDFAnnotation.TYPE_REDACT)
            a.rect = core
            a.update()
        }
        page.applyRedactions(false, if (pixels) PDFPage.REDACT_IMAGE_PIXELS else PDFPage.REDACT_IMAGE_NONE, PDFPage.REDACT_LINE_ART_NONE, PDFPage.REDACT_TEXT_REMOVE)
        for (p in pending) {
            val a = page.createAnnotation(PDFAnnotation.TYPE_REDACT)
            a.rect = p.rect
            p.quads?.let { a.quadPoints = it }
            p.color?.let { a.interiorColor = it }
            p.contents?.let { a.contents = it }
            p.author?.let { a.author = it }
            a.update()
        }
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** Width of a space in the span's font, in thousandths of an em, measured from the page itself. */
    private fun spaceUnits(line: Line, span: Span): Float? {
        val spaces = line.spans.flatMap { it.chars }.filter { it.c == ' '.code }
        if (spaces.isEmpty() || span.size <= 0f) return null
        val avg = spaces.map { it.bbox.x1 - it.bbox.x0 }.average().toFloat()
        return if (avg > 0f) avg / span.size * 1000f else null
    }

    private val NUMERIC = Regex("""[\s()\-+₹$€£,.\d%]*\d[\s()\-+₹$€£,.\d%]*(?:\s*(?:Cr|Dr|CR|DR))?""")

    /** Whether the edited line sits in a left- or right-aligned column (lines just above and below decide). */
    private fun columnAlignment(text: PageText, box: Rect, s: String): String {
        var left = 0
        var right = 0
        val h = box.y1 - box.y0
        for (ln in text.lines) {
            val r = ln.bbox
            if (abs(r.y0 - box.y0) < 0.5f || abs(r.y0 - box.y0) > h * 8) continue
            if (r.x1 < box.x0 || r.x0 > box.x1) continue
            if (abs(r.x0 - box.x0) < 1f) left++
            if (abs(r.x1 - box.x1) < 1f) right++
        }
        return when {
            left > right -> "left"
            right > left -> "right"
            NUMERIC.matches(s.ifEmpty { "x" }) -> "right"
            else -> "left"
        }
    }

    private fun wrap(writer: Writer, text: String, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        for (para in text.split("\n")) {
            var cur = ""
            for (word in para.split(" ")) {
                val cand = if (cur.isEmpty()) word else "$cur $word"
                if (writer.width(cand) <= maxWidth || cur.isEmpty()) cur = cand else { out.add(cur); cur = word }
            }
            out.add(cur)
        }
        return out
    }

    // ------------------------------------------------------------------- edit

    /**
     * Replaces the text of a line ([mode] "line") or paragraph ("block").
     *
     * [rect]: the original line or paragraph box. [moveBy]: page-space offset.
     * [newRect]: paragraph mode, the box to flow the new text into.
     * [anchor]: left, right, center or auto (numbers keep their right edge).
     * [fit]: none, shrink (smaller font) or condense (horizontal scaling).
     */
    fun editText(
        doc: PDFDocument, pageNumber: Int, rect: Rect, newText: String,
        mode: String = "line", fontSize: Float? = null, argb: Int? = null, bold: Boolean? = null,
        italic: Boolean? = null, family: String? = null, anchor: String = "auto", fit: String = "none",
        moveBy: Point? = null, newRect: Rect? = null, align: String = "left", lineSpacing: Float? = null,
    ): Result {
        Pdf.require(doc, com.artifex.mupdf.fitz.Document.PERMISSION_EDIT, "editing")
        val page = Pdf.page(doc, pageNumber)
        val text = PageText.of(page)
        // Scanned pages: edit just the recognised words inside the area (an OCR row can span table columns).
        val grown = Pdf.grow(rect, 1f)
        val ocrParts = text.lines.mapNotNull { clip(it, grown) }.filter { l -> l.spans.all { isOcrFont(it.fontName) } }
        val printed = text.linesIn(grown).filter { l -> l.spans.any { !isOcrFont(it.fontName) } }
        if (ocrParts.isNotEmpty() && printed.isEmpty()) {
            return editScanned(doc, page, pageNumber, ocrParts, newText, mode, fontSize, argb, bold, italic, family, moveBy)
        }
        val lines = text.linesIn(grown)
        if (lines.isEmpty()) {
            throw PdfError(Codes.NOT_SUPPORTED, "No editable text was found in the selected area. If this is a scanned page, run OCR first or use Add Text.")
        }
        if (lines.any { !it.horizontal }) {
            throw PdfError(Codes.NOT_SUPPORTED, "Rotated or vertical text cannot be edited in place. Use Add Text for a new text box.")
        }
        val warnings = mutableListOf<String>()
        if (isSigned(doc)) warnings.add("This document is digitally signed. Editing its content invalidates the existing signature(s).")
        val first = lines.first().spans.first()
        val oldText = lines.joinToString("\n") { it.spans.joinToString("") { s -> s.text } }
        val size = fontSize ?: first.size
        val color = argb ?: first.argb
        val sw = spaceUnits(lines.first(), first)
        val removeRects = lines.map { it.bbox }
        val oldUnion = removeRects.reduce(Pdf::union)
        val dx = moveBy?.x ?: 0f
        val dy = moveBy?.y ?: 0f

        // Fonts are looked up before the old glyphs are removed.
        val forWriter = if (mode == "line") newText.replace("\n", " ") else newText
        val (writer, reason) = makeWriter(doc, page, first, forWriter.replace("\n", " "), size, color, bold, italic, family, sw)
        reason?.let { warnings.add("Substituted ${writer.label} because $it.") }
        if (writer.kind == "substitute" && forWriter.codePoints().anyMatch { it in 0x0900..0x0AFF }) {
            warnings.add("Hindi and Gujarati text in a substitute font is written letter by letter; joined letters (conjuncts) may not display correctly.")
        }

        removeText(page, removeRects)
        val ops = StringBuilder()
        val targetWidth = oldUnion.x1 - oldUnion.x0
        val newArea: Rect
        if (mode == "line") {
            var origin = Point(first.origin.x + dx, first.origin.y + dy)
            val lineRight = oldUnion.x1 + dx
            var w = writer.width(forWriter)
            if ((fit == "shrink" || fit == "condense") && w > targetWidth && targetWidth > 0) {
                if (fit == "shrink") writer.size = max(4f, writer.size * targetWidth / w)
                else writer.hscale = max(50f, 100f * targetWidth / w)
                w = writer.width(forWriter)
            }
            val effAnchor = if (anchor == "auto") columnAlignment(text, oldUnion, norm(oldText)) else anchor
            origin = when (effAnchor) {
                "right" -> Point(lineRight - w, origin.y)
                "center" -> Point(oldUnion.x0 + dx + (targetWidth - w) / 2, origin.y)
                else -> origin
            }
            if (forWriter.isNotEmpty()) ops.append(writer.ops(origin, forWriter))
            newArea = Rect(origin.x, origin.y - writer.size * 1.05f, origin.x + w, origin.y + writer.size * 0.35f)
            if (fit == "none" && targetWidth > 0 && w > targetWidth + max(3f, targetWidth * 0.08f)) {
                warnings.add("The new text is wider than the original. It may overlap neighbouring content; consider Shrink to fit.")
            }
        } else if (mode == "block") {
            val box = newRect ?: Rect(oldUnion.x0 + dx, oldUnion.y0 + dy, oldUnion.x1 + dx, oldUnion.y1 + dy)
            val baselines = lines.map { Math.round(it.spans.first().origin.y * 100) / 100f }.toSortedSet().toList()
            val leading = when {
                lineSpacing != null -> lineSpacing * writer.size
                baselines.size > 1 -> (baselines.last() - baselines.first()) / (baselines.size - 1)
                else -> writer.size * 1.2f
            }
            val wrapped = wrap(writer, newText, box.x1 - box.x0)
            val firstBase = box.y0 + if (newRect == null) first.origin.y - oldUnion.y0 else writer.size * 0.9f
            wrapped.forEachIndexed { k, ln ->
                val y = firstBase + k * leading
                val w = writer.width(ln)
                val x = when (align) {
                    "right" -> box.x1 - w
                    "center" -> box.x0 + (box.x1 - box.x0 - w) / 2
                    else -> box.x0
                }
                if (ln.isNotEmpty()) ops.append(writer.ops(Point(x, y), ln))
            }
            val lastY = firstBase + (wrapped.size - 1) * leading
            if (lastY > box.y1 + leading * 0.5f) warnings.add("The new text is longer than the original paragraph box and extends below it. Check the layout.")
            newArea = Rect(box.x0, box.y0 - 2, box.x1, lastY + writer.size * 0.4f)
        } else throw PdfError(Codes.INVALID, "Mode must be line or block.")

        if (ops.isNotEmpty()) Pdf.appendContent(doc, page, ops.toString())

        // Verify by reading the edited area back.
        val got = norm(PageText.of(Pdf.page(doc, pageNumber)).textIn(Pdf.grow(newArea, 2f)))
        val want = norm(newText)
        if (want.isNotEmpty() && !got.replace(" ", "").contains(want.replace(" ", ""))) {
            throw PdfError(Codes.VERIFY_FAILED, "The edit could not be verified: the new text was not found where it was written. The document was not changed.")
        }
        return Result(writer.label, writer.kind, warnings, newArea)
    }

    /** The part of [line] whose characters lie inside [r] (null when none do), spaces at the ends dropped. */
    private fun clip(line: Line, r: Rect): Line? {
        val keep = ArrayList<Pair<Span, TextChar>>()
        for (sp in line.spans) for (c in sp.chars) {
            val b = c.bbox
            if (Pdf.contains(r, (b.x0 + b.x1) / 2, (b.y0 + b.y1) / 2)) keep.add(sp to c)
        }
        while (keep.isNotEmpty() && Character.isWhitespace(keep.first().second.c)) keep.removeAt(0)
        while (keep.isNotEmpty() && Character.isWhitespace(keep.last().second.c)) keep.removeAt(keep.size - 1)
        if (keep.isEmpty()) return null
        val out = Line(keep.map { it.second.bbox }.reduce(Pdf::union), line.dir)
        var cur: Span? = null
        for ((sp, c) in keep) {
            if (cur == null || cur.fontName != sp.fontName || cur.size != sp.size) {
                cur = Span(sp.fontName, sp.size, sp.argb, sp.bold, sp.italic, sp.serif, sp.mono).also { out.spans.add(it) }
            }
            cur.chars.add(c)
        }
        return out
    }

    /**
     * For a line recognised by OCR: the run of words around [x] and its box.
     * Words separated by a gap wider than about one text size are different
     * runs (table cells, columns), so an edit touches only what was tapped.
     */
    fun ocrChunkAt(line: Line, x: Float): Pair<String, Rect>? {
        val chars = line.spans.flatMap { sp -> sp.chars.map { sp to it } }
        if (chars.isEmpty()) return null
        val runs = ArrayList<MutableList<TextChar>>()
        var cur = mutableListOf<TextChar>()
        var lastRight = Float.NaN
        for ((sp, c) in chars) {
            if (!Character.isWhitespace(c.c)) {
                if (!lastRight.isNaN() && c.bbox.x0 - lastRight > 1.1f * sp.size) { if (cur.isNotEmpty()) runs.add(cur); cur = mutableListOf() }
                lastRight = c.bbox.x1
            }
            cur.add(c)
        }
        if (cur.isNotEmpty()) runs.add(cur)
        val visible = runs.map { r -> r.filter { !Character.isWhitespace(it.c) } }.filter { it.isNotEmpty() }
        val run = visible.firstOrNull { r -> x >= r.first().bbox.x0 - 2f && x <= r.last().bbox.x1 + 2f }
            ?: visible.minByOrNull { r -> minOf(abs(x - r.first().bbox.x0), abs(x - r.last().bbox.x1)) } ?: return null
        val text = buildString { for (c in runs.first { it.containsAll(run) }) appendCodePoint(c.c) }.trim()
        return text to run.map { it.bbox }.reduce(Pdf::union)
    }

    /** True for the invisible font of a text layer added by OCR. */
    fun isOcrFont(name: String) = Span.stripSubset(name).startsWith("GlyphLess")

    /**
     * Edits text recognised by OCR on a scanned page. The scan's pixels under the
     * line are removed from the image (so the old text cannot be recovered), the
     * area is painted in the paper colour sampled around it, and the new text is
     * written visibly in a similar font, size and ink colour. The page stays searchable.
     */
    private fun editScanned(
        doc: PDFDocument, page: PDFPage, pageNumber: Int, lines: List<Line>, newText: String, mode: String,
        fontSize: Float?, argb: Int?, bold: Boolean?, italic: Boolean?, family: String?, moveBy: Point?,
    ): Result {
        val box = lines.map { it.bbox }.reduce(Pdf::union)
        val (paper, ink) = sampleColours(page, box)
        val textAll = lines.joinToString(" ") { it.text }
        // OCR word boxes are ink boxes: with descenders the ink spans about 0.93 em, without about 0.72 em.
        val lineH = lines.map { it.bbox.y1 - it.bbox.y0 }.sorted()[lines.size / 2]
        val descenders = textAll.any { it in "gjpqy,;()" }
        val size = fontSize ?: (lineH / if (descenders) 0.93f else 0.72f).coerceIn(4f, 96f)
        val text = if (mode == "line") newText.replace("\n", " ") else newText
        val sub = chooseSubstitute(null, bold == true, italic == true, text.replace("\n", " "), family?.takeIf { it != "original" })
        val writer = SubstituteFontWriter(doc, page, sub, size, argb ?: ink)
        if (!writer.canWrite(text.replace("\n", " "))) throw PdfError(Codes.NOT_SUPPORTED, "The new text contains characters that none of the available fonts can display.")
        val pad = maxOf(1.5f, size * 0.12f)
        val area = Rect(box.x0 - pad, box.y0 - pad, box.x1 + pad, box.y1 + pad)
        removeText(page, lines.map { Rect(it.bbox.x0 - pad, it.bbox.y0 - pad, it.bbox.x1 + pad, it.bbox.y1 + pad) }, pixels = true)
        val ops = StringBuilder(fillRect(page, area, paper))
        val dx = moveBy?.x ?: 0f; val dy = moveBy?.y ?: 0f
        val newArea: Rect
        if (mode == "block") {
            val wrapped = wrap(writer, text, box.x1 - box.x0)
            val leading = if (lines.size > 1) (lines.last().bbox.y1 - lines.first().bbox.y1) / (lines.size - 1) else size * 1.2f
            val firstBase = lines.first().bbox.y1 - if (descenders) size * 0.21f else 0f
            wrapped.forEachIndexed { k, ln -> if (ln.isNotEmpty()) ops.append(writer.ops(Point(box.x0 + dx, firstBase + dy + k * leading), ln)) }
            newArea = Rect(box.x0 + dx, box.y0 + dy - 2, box.x1 + dx, firstBase + dy + (wrapped.size - 1) * leading + size * 0.4f)
        } else {
            var w = writer.width(text)
            val target = box.x1 - box.x0
            // Keep the new text inside the scanned line's width so it does not run over the neighbouring text.
            if (w > target * 1.05f && target > 0) { writer.hscale = max(60f, 100f * target / w); w = writer.width(text) }
            val base = lines.first().bbox.y1 - if (descenders) size * 0.21f else 0f
            val origin = Point(box.x0 + dx, base + dy)
            if (text.isNotEmpty()) ops.append(writer.ops(origin, text))
            newArea = Rect(origin.x, origin.y - size * 1.05f, origin.x + w, origin.y + size * 0.35f)
        }
        Pdf.appendContent(doc, page, ops.toString())
        val got = norm(PageText.of(Pdf.page(doc, pageNumber)).textIn(Pdf.grow(newArea, 2f)))
        val want = norm(text)
        if (want.isNotEmpty() && !got.replace(" ", "").contains(want.replace(" ", ""))) {
            throw PdfError(Codes.VERIFY_FAILED, "The edit could not be verified: the new text was not found where it was written. The document was not changed.")
        }
        return Result(writer.label, "scan", listOf("Scanned text: the line was replaced with ${writer.label}, matched to the scan's size and colour. The scan's original pixels under it were removed."), newArea)
    }

    /** Paper colour (around the area) and ink colour (darkest pixels inside it), sampled from the page as drawn. */
    fun sampleColours(page: PDFPage, r: Rect): Pair<Int, Int> {
        val scale = 2f
        val m = 4f
        val x0 = ((r.x0 - m) * scale).toInt(); val y0 = ((r.y0 - m) * scale).toInt()
        val w = max(4, ((r.x1 - r.x0 + 2 * m) * scale).toInt()); val h = max(4, ((r.y1 - r.y0 + 2 * m) * scale).toInt())
        val pix = com.artifex.mupdf.fitz.Pixmap(com.artifex.mupdf.fitz.ColorSpace.DeviceRGB, x0, y0, w, h, false)
        try {
            pix.clear(255)
            val dev = com.artifex.mupdf.fitz.DrawDevice(pix)
            try { page.run(dev, Matrix.Scale(scale), null); dev.close() } finally { dev.destroy() }
            val s = pix.samples; val n = pix.numberOfComponents; val stride = pix.stride
            val ring = ArrayList<Int>(); val inside = ArrayList<Int>()
            val ix0 = (m * scale).toInt(); val iy0 = (m * scale).toInt(); val ix1 = w - ix0; val iy1 = h - iy0
            for (y in 0 until h) for (x in 0 until w) {
                val o = y * stride + x * n
                val c = ((s[o].toInt() and 0xFF) shl 16) or ((s[o + 1].toInt() and 0xFF) shl 8) or (s[o + 2].toInt() and 0xFF)
                if (x < ix0 || y < iy0 || x >= ix1 || y >= iy1) ring.add(c) else inside.add(c)
            }
            fun lum(c: Int) = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            val paper = ring.sortedBy(::lum).let { it[(it.size * 0.6f).toInt().coerceAtMost(it.size - 1)] }
            val dark = inside.sortedBy(::lum).take(max(1, inside.size / 12))
            val ink = (dark.sumOf { (it shr 16) and 0xFF } / dark.size shl 16) or (dark.sumOf { (it shr 8) and 0xFF } / dark.size shl 8) or (dark.sumOf { it and 0xFF } / dark.size)
            // A line with almost no contrast (faint scan): fall back to black ink.
            val inkFinal = if (lum(paper) - lum(ink) < 40f) 0x000000 else ink
            return paper to inkFinal
        } finally {
            pix.destroy()
        }
    }

    /** Content that fills [r] (page space) with [rgb], correct on rotated pages. */
    private fun fillRect(page: PDFPage, r: Rect, rgb: Int): String {
        val inv = Matrix.Inverted(page.getTransform())
        val pts = listOf(Point(r.x0, r.y0), Point(r.x1, r.y0), Point(r.x1, r.y1), Point(r.x0, r.y1)).map { Point(it).transform(inv) }
        val c = Pdf.rgb(rgb)
        return "q ${Pdf.n(c[0])} ${Pdf.n(c[1])} ${Pdf.n(c[2])} rg ${Pdf.n(pts[0].x)} ${Pdf.n(pts[0].y)} m " +
            pts.drop(1).joinToString(" ") { "${Pdf.n(it.x)} ${Pdf.n(it.y)} l" } + " h f Q\n"
    }

    /** Find and replace across pages, span by span, in the original font where possible. */
    fun replaceAll(
        doc: PDFDocument, find: String, replace: String, matchCase: Boolean = true,
        wholeWord: Boolean = false, pages: String? = null,
    ): ReplaceResult {
        if (find.isEmpty()) throw PdfError(Codes.INVALID, "Enter the text to find.")
        Pdf.require(doc, com.artifex.mupdf.fitz.Document.PERMISSION_EDIT, "editing")
        val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val pat = Regex(if (wholeWord) "\\b${Regex.escape(find)}\\b" else Regex.escape(find), opts)
        val sel = Pdf.parsePages(pages, doc.countPages())
        var found = 0
        var replaced = 0
        val skipped = mutableListOf<String>()
        val warnings = LinkedHashSet<String>()
        for (i in sel) {
            val page = doc.loadPage(i) as PDFPage
            val text = PageText.of(page)
            val hits = mutableListOf<Pair<Line, Span>>()
            for (ln in text.lines) for (sp in ln.spans) if (pat.containsMatchIn(sp.text)) hits.add(ln to sp)
            val spanHits = hits.sumOf { pat.findAll(it.second.text).count() }
            val lineHits = text.lines.sumOf { ln -> pat.findAll(ln.text).count() }
            if (lineHits > spanHits) skipped.add("Page ${i + 1}: ${lineHits - spanHits} occurrence(s) split across differently formatted text")
            found += maxOf(lineHits, spanHits)
            val jobs = mutableListOf<Triple<Line, Span, String>>()
            for ((ln, sp) in hits) {
                if (!ln.horizontal) { skipped.add("Page ${i + 1}: rotated text"); continue }
                jobs.add(Triple(ln, sp, pat.replace(sp.text, Regex.escapeReplacement(replace))))
            }
            if (jobs.isEmpty()) continue
            val writers = jobs.map { (ln, sp, new) ->
                val (w, reason) = makeWriter(doc, page, sp, new, sp.size, sp.argb, null, null, null, spaceUnits(ln, sp))
                reason?.let { warnings.add("Substitute font used: ${w.label} ($it).") }
                w
            }
            removeText(page, jobs.map { it.second.bbox })
            val ops = StringBuilder()
            jobs.forEachIndexed { k, (_, sp, new) ->
                val w = writers[k]
                if (new.isNotEmpty()) ops.append(w.ops(sp.origin, new))
                replaced += pat.findAll(sp.text).count()
                if (w.width(new) > (sp.bbox.x1 - sp.bbox.x0) + 1) warnings.add("Page ${i + 1}: a replacement is wider than the original text and may overlap neighbouring content.")
            }
            if (ops.isNotEmpty()) Pdf.appendContent(doc, page, ops.toString())
        }
        if (replaced == 0) {
            throw PdfError(Codes.NOT_SUPPORTED, if (found == 0) "No occurrences were found." else "Occurrences were found but none could be replaced in place.")
        }
        return ReplaceResult(replaced, found, skipped, warnings.toList())
    }

    /** Adds new text to the page content (not a comment), wrapped inside [rect]. */
    fun insertText(
        doc: PDFDocument, pageNumber: Int, rect: Rect, text: String, fontSize: Float = 11f,
        argb: Int = 0xFF000000.toInt(), family: String = "helvetica", bold: Boolean = false,
        italic: Boolean = false, align: String = "left",
    ): String {
        if (text.isBlank()) throw PdfError(Codes.INVALID, "Enter the text to add.")
        Pdf.require(doc, com.artifex.mupdf.fitz.Document.PERMISSION_EDIT, "editing")
        val page = Pdf.page(doc, pageNumber)
        val sub = chooseSubstitute(null, bold, italic, text.replace("\n", " "), family)
        val writer = SubstituteFontWriter(doc, page, sub, fontSize, argb)
        if (!writer.canWrite(text.replace("\n", " "))) throw PdfError(Codes.NOT_SUPPORTED, "The text contains characters that none of the available fonts can display.")
        val width = rect.x1 - rect.x0
        val lines = wrap(writer, text, width)
        val leading = fontSize * 1.2f
        val pageBottom = page.bounds.y1
        val needed = rect.y0 + fontSize * 0.9f + (lines.size - 1) * leading + fontSize * 0.3f
        if (needed > pageBottom) throw PdfError(Codes.INVALID, "The text does not fit on the page. Draw a larger box or reduce the font size.")
        val ops = StringBuilder()
        lines.forEachIndexed { k, ln ->
            val w = writer.width(ln)
            val x = when (align) {
                "right" -> rect.x1 - w
                "center" -> rect.x0 + (width - w) / 2
                else -> rect.x0
            }
            if (ln.isNotEmpty()) ops.append(writer.ops(Point(x, rect.y0 + fontSize * 0.9f + k * leading), ln))
        }
        Pdf.appendContent(doc, page, ops.toString())
        return writer.label
    }

    fun isSigned(doc: PDFDocument): Boolean {
        val n = doc.countPages()
        for (i in 0 until n) {
            val page = doc.loadPage(i) as PDFPage
            for (w in page.widgets ?: emptyArray()) if (w.fieldType == com.artifex.mupdf.fitz.PDFWidget.TYPE_SIGNATURE && w.isSigned) return true
        }
        return false
    }
}
