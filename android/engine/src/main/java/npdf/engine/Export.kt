package npdf.engine

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Pixmap
import npdf.office.Doc
import npdf.office.Docx
import npdf.office.Layout
import npdf.office.Page
import npdf.office.Picture
import npdf.office.Pptx
import npdf.office.Rtf
import npdf.office.TImage
import npdf.office.TLine
import npdf.office.TSpan
import npdf.office.Xlsx
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * PDF to Word, Excel, PowerPoint and RTF on the device. Text comes from the
 * PDF itself or from a recognised (OCR) text layer; layout is rebuilt as
 * paragraphs and tables, so the result is editable but not a copy of the page.
 */
object Export {

    enum class Format(val ext: String, val mime: String, val label: String) {
        WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
        EXCEL("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel"),
        POWERPOINT("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint"),
        RTF("rtf", "application/rtf", "Rich Text"),
    }

    /** Pages (1-based) that carry no text at all: these need OCR before their text can be exported. */
    fun pagesWithoutText(doc: PDFDocument): List<Int> =
        (1..doc.countPages()).filter { !TextLayer.hasText(doc, it) }

    fun write(doc: PDFDocument, format: Format, title: String, out: OutputStream, pages: String? = null) {
        Pdf.require(doc, com.artifex.mupdf.fitz.Document.PERMISSION_COPY, "export")
        val list = Pdf.parsePages(pages, doc.countPages())
        when (format) {
            Format.EXCEL -> Xlsx.write(title, list.map { i -> "Page ${i + 1}" to Layout.sheet(lines(doc.loadPage(i) as PDFPage)) }, out)
            Format.POWERPOINT -> Pptx.write(Doc(title, list.map { i -> val p = doc.loadPage(i) as PDFPage; size(p).let { (w, h) -> Page(w, h, emptyList(), render(p)) } }), out)
            Format.WORD -> Docx.write(model(doc, list, title), out)
            Format.RTF -> Rtf.write(model(doc, list, title), out)
        }
    }

    private fun size(p: PDFPage): Pair<Float, Float> { val b = p.bounds; return (b.x1 - b.x0) to (b.y1 - b.y0) }

    fun model(doc: PDFDocument, pages: List<Int>, title: String): Doc = Doc(title, pages.map { i ->
        val p = doc.loadPage(i) as PDFPage
        val (w, h) = size(p)
        Page(w, h, Layout.blocks(lines(p), images(doc, p), w, h))
    })

    /** The page's text as lines, each split into parts where a wide gap separates columns. */
    fun lines(page: PDFPage): List<TLine> {
        val out = ArrayList<TLine>()
        for (ln in PageText.of(page).lines) {
            if (!ln.horizontal) {
                val t = ln.text.trim()
                if (t.isNotEmpty()) { val s = ln.spans.first(); out.add(TLine(ln.bbox.x0, ln.bbox.y0, ln.bbox.x1, ln.bbox.y1, listOf(TSpan(t, ln.bbox.x0, ln.bbox.x1, s.size, s.bold, s.italic, s.argb)))) }
                continue
            }
            // Characters with their span, split at gaps wider than 1.2 x the text size.
            val chunk = ArrayList<Pair<Span, TextChar>>()
            fun flush() {
                val visible = chunk.filter { !Character.isWhitespace(it.second.c) }
                if (visible.isEmpty()) { chunk.clear(); return }
                val spans = ArrayList<TSpan>()
                var cur: Span? = null; val sb = StringBuilder(); var sx0 = 0f; var sx1 = 0f
                for ((sp, ch) in chunk) {
                    if (sp !== cur) {
                        if (cur != null && sb.isNotEmpty()) spans.add(TSpan(sb.toString(), sx0, sx1, cur.size, cur.bold, cur.italic, cur.argb))
                        cur = sp; sb.setLength(0); sx0 = ch.bbox.x0
                    }
                    sb.appendCodePoint(ch.c); sx1 = ch.bbox.x1
                }
                if (cur != null && sb.isNotEmpty()) spans.add(TSpan(sb.toString(), sx0, sx1, cur.size, cur.bold, cur.italic, cur.argb))
                val x0 = visible.minOf { it.second.bbox.x0 }; val x1 = visible.maxOf { it.second.bbox.x1 }
                val y0 = visible.minOf { it.second.bbox.y0 }; val y1 = visible.maxOf { it.second.bbox.y1 }
                // Trim spaces at both ends of the part.
                val trimmed = spans.mapIndexed { k, s ->
                    var t = s.text
                    if (k == 0) t = t.trimStart()
                    if (k == spans.size - 1) t = t.trimEnd()
                    s.copy(text = t)
                }.filter { it.text.isNotEmpty() }
                out.add(TLine(x0, y0, x1, y1, trimmed))
                chunk.clear()
            }
            var lastRight = Float.NaN
            for (sp in ln.spans) for (ch in sp.chars) {
                if (!Character.isWhitespace(ch.c)) {
                    if (!lastRight.isNaN() && ch.bbox.x0 - lastRight > 1.2f * sp.size) flush()
                    lastRight = ch.bbox.x1
                }
                chunk.add(sp to ch)
            }
            flush()
        }
        return out
    }

    /** Pictures drawn by the page's own content, in page space (at most 2000 px on the long side). */
    fun images(doc: PDFDocument, page: PDFPage): List<TImage> {
        val (_, found) = ContentStream.images(Pdf.pageContent(page), page.getObject().getInheritable("Resources"))
        val out = ArrayList<TImage>()
        for (p in found.filter { it.topLevel && it.obj != null }) {
            val obj = p.obj!!
            val box = ImageEdit.toPage(page, p.bbox)
            if (box.x1 - box.x0 < 4 || box.y1 - box.y0 < 4) continue
            val bytes = runCatching {
                val filter = obj.get("Filter")
                val dct = (filter.isName && filter.asName() == "DCTDecode") || (filter.isArray && filter.size() == 1 && filter.get(0).asName() == "DCTDecode")
                val cs = obj.get("ColorSpace")
                val simple = cs.isName && (cs.asName() == "DeviceRGB" || cs.asName() == "DeviceGray")
                if (dct && simple && obj.get("SMask").isNull && obj.get("Decode").isNull) obj.readRawStream() to false
                else encode(doc, obj)
            }.getOrNull() ?: continue
            out.add(TImage(box.x0, box.y0, box.x1, box.y1, bytes.first, bytes.second))
        }
        return out
    }

    /** Decodes any image (any colour space, masks drawn on white) and encodes it as JPEG. */
    private fun encode(doc: PDFDocument, obj: com.artifex.mupdf.fitz.PDFObject): Pair<ByteArray, Boolean> {
        val image = doc.loadImage(obj)
        val s = min(1f, 2000f / max(image.width, image.height))
        val w = max(1, (image.width * s).toInt()); val h = max(1, (image.height * s).toInt())
        val pix = Pixmap(ColorSpace.DeviceRGB, 0, 0, w, h, false)
        try {
            pix.clear(255)
            val dev = DrawDevice(pix)
            try { dev.fillImage(image, Matrix(w.toFloat(), 0f, 0f, h.toFloat(), 0f, 0f), 1f, 0); dev.close() } finally { dev.destroy() }
            return pix.asJPEG(88, false).asByteArray() to false
        } finally {
            pix.destroy()
        }
    }

    /** The page drawn as a JPEG at [dpi] (for slides). */
    fun render(page: PDFPage, dpi: Float = 150f): Picture {
        val (w, h) = size(page)
        var scale = dpi / 72f
        if (max(w, h) * scale > 2400f) scale = 2400f / max(w, h)
        val pix = page.toPixmap(Matrix.Scale(scale), ColorSpace.DeviceRGB, false, true)
        try {
            return Picture(pix.asJPEG(85, false).asByteArray(), false, w, h)
        } finally {
            pix.destroy()
        }
    }
}
