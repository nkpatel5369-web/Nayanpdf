package npdf.engine

import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker

/** One character as extracted, in page space (top-left origin). */
class TextChar(val c: Int, val origin: Point, val quad: Quad) {
    val bbox: Rect get() = Pdf.quadRect(quad)
}

/** A run of characters with the same font, size and colour on one line. */
class Span(
    val fontName: String,
    val size: Float,
    val argb: Int,
    val bold: Boolean,
    val italic: Boolean,
    val serif: Boolean,
    val mono: Boolean,
) {
    val chars = mutableListOf<TextChar>()
    val text: String get() = buildString { chars.forEach { appendCodePoint(it.c) } }
    val origin: Point get() = chars.first().origin
    val bbox: Rect get() = chars.map { it.bbox }.reduce(Pdf::union)

    /** The font name as PDFs write it, without a subset prefix such as "ABCDEF+". */
    val cleanFontName: String get() = stripSubset(fontName)

    companion object {
        fun stripSubset(name: String): String =
            if (name.length > 7 && name[6] == '+' && name.substring(0, 6).all { it in 'A'..'Z' }) name.substring(7) else name
    }
}

class Line(val bbox: Rect, val dir: Point) {
    val spans = mutableListOf<Span>()
    val text: String get() = spans.joinToString("") { it.text }

    /**
     * True when the line reads left to right along the page's horizontal axis,
     * judged from where its characters actually are (the reported direction is
     * not reliable on pages stored rotated).
     */
    val horizontal: Boolean
        get() {
            val chars = spans.flatMap { it.chars }.filter { it.c != ' '.code }
            if (chars.size < 2) {
                val q = chars.firstOrNull()?.quad ?: return true
                return kotlin.math.abs(q.ur_y - q.ul_y) <= kotlin.math.abs(q.ur_x - q.ul_x) * 0.05f + 0.01f && q.ur_x > q.ul_x
            }
            val a = chars.first().origin; val b = chars.last().origin
            val size = spans.first().size.coerceAtLeast(1f)
            return b.x > a.x && kotlin.math.abs(b.y - a.y) < size * 0.2f
        }
}

class Block(val bbox: Rect) {
    val lines = mutableListOf<Line>()
}

/** Text of a page grouped into blocks, lines and spans, as the editor needs it. */
class PageText(val blocks: List<Block>) {
    val lines: List<Line> get() = blocks.flatMap { it.lines }

    /** Lines whose box centre lies inside [rect], keeping only the spans whose centre lies inside too. */
    fun linesIn(rect: Rect): List<Line> {
        val out = mutableListOf<Line>()
        for (ln in lines) {
            val c = center(ln.bbox)
            if (!Pdf.contains(rect, c.x, c.y)) continue
            val keep = ln.spans.filter { val sc = center(it.bbox); Pdf.contains(rect, sc.x, sc.y) }
            if (keep.isNotEmpty()) {
                val copy = Line(ln.bbox, ln.dir)
                copy.spans.addAll(keep)
                out.add(copy)
            }
        }
        return out
    }

    /** All characters whose centre lies in [rect], in reading order, lines joined with spaces. */
    fun textIn(rect: Rect): String = buildString {
        for (ln in lines) {
            val part = StringBuilder()
            for (sp in ln.spans) for (ch in sp.chars) {
                val c = center(ch.bbox)
                if (Pdf.contains(rect, c.x, c.y)) part.appendCodePoint(ch.c)
            }
            if (part.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(part)
            }
        }
    }

    fun allText(): String = blocks.joinToString("\n\n") { b -> b.lines.joinToString("\n") { it.text } }

    /** Characters in reading order, each with the index of its line. */
    private val ordered: List<Pair<TextChar, Int>> by lazy {
        val out = ArrayList<Pair<TextChar, Int>>()
        lines.forEachIndexed { li, ln -> ln.spans.forEach { sp -> sp.chars.forEach { out.add(it to li) } } }
        out
    }

    /**
     * Finds [query] (whitespace-insensitive, optionally ignoring case). Lines of
     * a block are searched as one run, so a phrase may continue on the next line.
     * Each hit is one quad per line it covers.
     */
    fun search(query: String, matchCase: Boolean = false): List<Array<Quad>> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return emptyList()
        val re = Regex(Regex.escape(q).replace(" ", "\\E\\s+\\Q"), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
        val out = mutableListOf<Array<Quad>>()
        for (b in blocks) {
            val sb = StringBuilder()
            val map = ArrayList<TextChar?>()
            val lineOf = ArrayList<Int>()
            b.lines.forEachIndexed { li, ln ->
                if (li > 0) { sb.append(' '); map.add(null); lineOf.add(-1) }
                for (sp in ln.spans) for (ch in sp.chars) {
                    val str = String(Character.toChars(ch.c))
                    sb.append(str)
                    repeat(str.length) { map.add(ch); lineOf.add(li) }
                }
            }
            for (m in re.findAll(sb)) {
                val byLine = LinkedHashMap<Int, Rect>()
                for (k in m.range) {
                    val ch = map[k] ?: continue
                    val li = lineOf[k]
                    byLine[li] = byLine[li]?.let { Pdf.union(it, ch.bbox) } ?: ch.bbox
                }
                if (byLine.isNotEmpty()) out.add(byLine.values.map { Pdf.rectQuad(it) }.toTypedArray())
            }
        }
        return out
    }

    /** Index (in reading order) of the character nearest to page point (x, y). */
    fun nearestChar(x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        ordered.forEachIndexed { i, (ch, _) ->
            val r = ch.bbox
            val dx = if (x < r.x0) r.x0 - x else if (x > r.x1) x - r.x1 else 0f
            val dy = if (y < r.y0) r.y0 - y else if (y > r.y1) y - r.y1 else 0f
            // Vertical distance counts more: a point beside a line belongs to that line.
            val d = dx + dy * 3f
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Range of characters between two page points, optionally widened to whole words. */
    fun selectionRange(ax: Float, ay: Float, bx: Float, by: Float, words: Boolean): IntRange? {
        if (ordered.isEmpty()) return null
        var i = nearestChar(ax, ay); var j = nearestChar(bx, by)
        if (i < 0 || j < 0) return null
        if (i > j) { val t = i; i = j; j = t }
        if (words) {
            fun space(k: Int) = Character.isWhitespace(ordered[k].first.c)
            while (i > 0 && !space(i - 1) && ordered[i - 1].second == ordered[i].second) i--
            while (j + 1 < ordered.size && !space(j + 1) && ordered[j + 1].second == ordered[j].second) j++
        }
        return i..j
    }

    /** One quad per line for a character range. */
    fun quads(range: IntRange): Array<Quad> {
        val byLine = LinkedHashMap<Int, Rect>()
        for (k in range) {
            val (ch, li) = ordered.getOrNull(k) ?: continue
            if (Character.isWhitespace(ch.c) && (k == range.first || k == range.last)) continue
            byLine[li] = byLine[li]?.let { Pdf.union(it, ch.bbox) } ?: ch.bbox
        }
        return byLine.values.map { Pdf.rectQuad(it) }.toTypedArray()
    }

    /** The text of a character range, with line breaks between lines. */
    fun text(range: IntRange): String = buildString {
        var lastLine = -1
        for (k in range) {
            val (ch, li) = ordered.getOrNull(k) ?: continue
            if (lastLine >= 0 && li != lastLine) append('\n')
            appendCodePoint(ch.c)
            lastLine = li
        }
    }.trim()

    companion object {
        fun center(r: Rect) = Point((r.x0 + r.x1) / 2, (r.y0 + r.y1) / 2)

        fun of(page: PDFPage): PageText {
            val st = page.toStructuredText("preserve-whitespace,preserve-ligatures")
            try {
                return of(st)
            } finally {
                st.destroy()
            }
        }

        fun of(st: StructuredText): PageText {
            val blocks = mutableListOf<Block>()
            st.walk(object : StructuredTextWalker {
                var block: Block? = null
                var line: Line? = null
                override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) {}
                override fun beginTextBlock(bbox: Rect, flags: Int) { block = Block(bbox) }
                override fun endTextBlock() { block?.let { if (it.lines.isNotEmpty()) blocks.add(it) }; block = null }
                override fun beginLine(bbox: Rect, wmode: Int, dir: Point) { line = Line(bbox, dir) }
                override fun endLine() { line?.let { if (it.spans.isNotEmpty()) block?.lines?.add(it) }; line = null }
                override fun onChar(c: Int, origin: Point, font: Font, size: Float, q: Quad, argb: Int, flags: Int, bidi: Int) {
                    val ln = line ?: return
                    val name = font.name ?: ""
                    val last = ln.spans.lastOrNull()
                    val rgb = argb and 0xFFFFFF
                    val sp = if (last != null && last.fontName == name && kotlin.math.abs(last.size - size) < 0.01f && last.argb == rgb) last
                    else Span(
                        name, size, rgb,
                        bold = font.isBold || (flags and StructuredText.CHAR_FLAGS_BOLD) != 0 || name.contains("bold", true),
                        italic = font.isItalic || name.contains("italic", true) || name.contains("oblique", true),
                        serif = font.isSerif, mono = font.isMono,
                    ).also { ln.spans.add(it) }
                    sp.chars.add(TextChar(c, Point(origin.x, origin.y), q))
                }
                override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                override fun endStruct() {}
                override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
            })
            return PageText(blocks)
        }
    }
}
