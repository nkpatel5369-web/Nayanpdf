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
    val horizontal: Boolean get() = kotlin.math.abs(dir.y) <= 0.01f && dir.x > 0
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
