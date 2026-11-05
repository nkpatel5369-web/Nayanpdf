package npdf.engine

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Rect
import java.util.Locale

/**
 * Invisible, searchable text over scanned pages, built the way Tesseract's own
 * PDF renderer builds it: a "glyphless" font whose character codes are the
 * Unicode values, so copy and search give exactly the recognised text in any
 * language. The page image and all other content are left untouched.
 */
object TextLayer {

    /** A recognised word: its text, its box in page space and Tesseract's confidence (0-100). */
    class Word(val text: String, val box: Rect, val confidence: Float)

    private var cachedFont: ByteArray? = null

    private fun fontFile(): ByteArray = cachedFont ?: run {
        val s = TextLayer::class.java.getResourceAsStream("/npdf/engine/glyphless.ttf")
            ?: throw IllegalStateException("glyphless font missing from the app")
        s.use { it.readBytes() }.also { cachedFont = it }
    }

    /** The glyphless Type0 font (one per document). */
    private fun font(doc: PDFDocument): PDFObject {
        val ff = doc.addStream(Buffer().apply { writeBytes(fontFile()) })
        val descriptor = doc.newDictionary().apply {
            put("Type", doc.newName("FontDescriptor"))
            put("FontName", doc.newName("GlyphLessFont"))
            put("Flags", 5)
            put("FontBBox", com.artifex.mupdf.fitz.Rect(0f, 0f, 500f, 1000f))
            put("ItalicAngle", 0)
            put("Ascent", 1000)
            put("Descent", -1)
            put("CapHeight", 1000)
            put("StemV", 80)
            put("FontFile2", ff)
        }
        // Every 2-byte code (the UTF-16 value) maps to glyph 1.
        val gidMap = ByteArray(65536 * 2) { if (it % 2 == 1) 1 else 0 }
        val cidToGid = doc.addStream(Buffer().apply { writeBytes(gidMap) })
        val cid = doc.newDictionary().apply {
            put("Type", doc.newName("Font"))
            put("Subtype", doc.newName("CIDFontType2"))
            put("BaseFont", doc.newName("GlyphLessFont"))
            put("CIDToGIDMap", cidToGid)
            put("CIDSystemInfo", doc.newDictionary().apply {
                put("Registry", doc.newString("Adobe"))
                put("Ordering", doc.newString("Identity"))
                put("Supplement", 0)
            })
            put("FontDescriptor", doc.addObject(descriptor))
            put("DW", 500)
        }
        val toUnicode = doc.addStream(
            "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n" +
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n" +
                "/CMapName /Adobe-Identify-UCS def\n/CMapType 2 def\n" +
                "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n" +
                "1 beginbfrange\n<0000> <FFFF> <0000>\nendbfrange\n" +
                "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n",
        )
        val type0 = doc.newDictionary().apply {
            put("Type", doc.newName("Font"))
            put("Subtype", doc.newName("Type0"))
            put("BaseFont", doc.newName("GlyphLessFont"))
            put("Encoding", doc.newName("Identity-H"))
            put("DescendantFonts", doc.newArray().apply { push(doc.addObject(cid)) })
            put("ToUnicode", toUnicode)
        }
        return doc.addObject(type0)
    }

    private fun utf16Hex(s: String): String = buildString {
        append('<')
        for (ch in s) append(String.format(Locale.ROOT, "%04X", ch.code))
        append('>')
    }

    /** Adds [words] as invisible text to page [pageNumber]. Returns the number of words written. */
    fun addWords(doc: PDFDocument, pageNumber: Int, words: List<Word>, fontObj: PDFObject? = null): Int {
        val page = Pdf.page(doc, pageNumber)
        val usable = words.filter { it.text.isNotBlank() && it.box.x1 > it.box.x0 && it.box.y1 > it.box.y0 }
        if (usable.isEmpty()) return 0
        val f = fontObj ?: font(doc)
        val name = Pdf.addResource(doc, page, "Font", "NpOcr", f)
        val inv = com.artifex.mupdf.fitz.Matrix.Inverted(page.getTransform())
        val sb = StringBuilder("BT 3 Tr\n")
        for (w in usable) {
            val text = w.text.trim()
            val height = w.box.y1 - w.box.y0
            val width = w.box.x1 - w.box.x0
            // Glyph boxes run from the baseline up by one font size: baseline at the word's bottom.
            val origin = com.artifex.mupdf.fitz.Point(w.box.x0, w.box.y1).transform(inv)
            val x1 = com.artifex.mupdf.fitz.Point(w.box.x0 + 1f, w.box.y1).transform(inv)
            val up = com.artifex.mupdf.fitz.Point(w.box.x0, w.box.y1 - 1f).transform(inv)
            val ux = x1.x - origin.x; val uy = x1.y - origin.y
            val vx = up.x - origin.x; val vy = up.y - origin.y
            val chars = text.length
            // Each glyph advances half an em: stretch horizontally so the word fills its box.
            val tz = 100f * width / (height * chars * 0.5f)
            sb.append("/").append(name).append(' ').append(Pdf.n(height)).append(" Tf ")
                .append(Pdf.n(tz)).append(" Tz ")
                .append("${Pdf.n(ux)} ${Pdf.n(uy)} ${Pdf.n(vx)} ${Pdf.n(vy)} ${Pdf.n(origin.x)} ${Pdf.n(origin.y)} Tm ")
                .append(utf16Hex(text)).append(" Tj\n")
        }
        sb.append("ET\n")
        Pdf.appendContent(doc, page, sb.toString())
        return usable.size
    }

    /** One shared font object for a whole document's OCR. */
    fun newFont(doc: PDFDocument): PDFObject = font(doc)

    /** True when a page already has extractable text (OCR is then usually not needed). */
    fun hasText(doc: PDFDocument, pageNumber: Int): Boolean =
        PageText.of(Pdf.page(doc, pageNumber)).allText().count { !it.isWhitespace() } >= 10
}
