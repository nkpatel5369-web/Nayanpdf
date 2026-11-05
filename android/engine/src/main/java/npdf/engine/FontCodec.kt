package npdf.engine

import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import java.nio.charset.Charset

/**
 * Encodes new text with a font that is already embedded in the PDF (port of the
 * Windows engine's FontCodec).
 *
 * Most PDFs embed subset fonts that contain only the glyphs the document uses.
 * To keep edited text in the original typeface the font's ToUnicode map (or its
 * simple encoding) is inverted and the new string is written as the original
 * character codes. Characters with no glyph in the subset are reported so the
 * caller can fall back to a substitute font and say so.
 */
class FontCodec(val font: PDFObject) {
    var ok = false; private set
    var reason = ""; private set
    var twoByte = false; private set
    var baseFont = ""; private set
    var embedded = false; private set
    private val codeToUni = HashMap<Int, String>()
    val uniToCode = HashMap<String, Int>()
    private val widths = HashMap<Int, Float>()
    private var defaultWidth = 0f
    private var metrics: Font? = null

    init {
        try {
            load()
        } catch (e: RuntimeException) {
            ok = false
            reason = "the font could not be analysed (${e.javaClass.simpleName})"
        }
    }

    private fun load() {
        val subtype = font.get("Subtype").takeIf { it.isName }?.asName() ?: ""
        if (subtype == "Type3") { reason = "Type 3 fonts cannot be re-used"; return }
        baseFont = font.get("BaseFont").takeIf { it.isName }?.asName() ?: ""
        val tu = font.get("ToUnicode")
        if (tu.isStream) {
            try {
                codeToUni.putAll(parseToUnicode(String(tu.readStream(), Charsets.ISO_8859_1)))
            } catch (e: RuntimeException) {
                codeToUni.clear()
            }
        }
        if (subtype == "Type0") {
            val enc = font.get("Encoding")
            if (!enc.isName || enc.asName() != "Identity-H") { reason = "the font uses an unsupported CMap"; return }
            twoByte = true
            val df = font.get("DescendantFonts")
            val desc = if (df.isArray && df.size() > 0) df.get(0) else null
            if (desc == null || !desc.isDictionary) { reason = "missing descendant font"; return }
            val dw = desc.get("DW")
            defaultWidth = if (dw.isNumber) dw.asFloat() else 1000f
            val w = desc.get("W")
            if (w.isArray) parseCidWidths(w)
            embedded = descriptorEmbedded(desc.get("FontDescriptor"))
            if (codeToUni.isEmpty()) { reason = "the embedded font has no Unicode map"; return }
        } else {
            val fc = font.get("FirstChar")
            val first = if (fc.isNumber) fc.asInteger() else 0
            val w = font.get("Widths")
            if (w.isArray) for (k in 0 until w.size()) widths[first + k] = w.get(k).asFloat()
            val fd = font.get("FontDescriptor")
            embedded = descriptorEmbedded(fd)
            val base14 = fd.isNull && !embedded
            if (codeToUni.isEmpty()) {
                val enc = font.get("Encoding")
                var baseName = "StandardEncoding"
                var diffs: PDFObject? = null
                when {
                    enc.isName -> baseName = enc.asName()
                    enc.isDictionary -> {
                        val be = enc.get("BaseEncoding")
                        if (be.isName) baseName = be.asName()
                        val d = enc.get("Differences")
                        if (d.isArray) diffs = d
                    }
                    base14 -> baseName = "WinAnsiEncoding"
                }
                val table = simpleBaseTable(baseName)
                if (diffs != null) {
                    var code = 0
                    for (i in 0 until diffs.size()) {
                        val item = diffs.get(i)
                        if (item.isNumber) {
                            code = item.asInteger()
                        } else if (item.isName) {
                            GlyphNames.toUnicode(item.asName())?.let { table[code] = it }
                            code++
                        }
                    }
                }
                if (embedded && widths.isNotEmpty()) {
                    // Only codes with a width are likely present in the subset.
                    table.keys.retainAll { (widths[it] ?: 0f) > 0f }
                }
                codeToUni.putAll(table)
            }
        }
        for ((code, uni) in codeToUni.entries.sortedBy { it.key }) {
            if (uni.codePointCount(0, uni.length) == 1 && uni !in uniToCode) uniToCode[uni] = code
        }
        // Standard (non-embedded) fonts usually carry no /Widths: use built-in metrics.
        if (widths.isEmpty() && !twoByte) {
            BASE14[Span.stripSubset(baseFont).replace(",", "-")]?.let { name ->
                metrics = try { Font(name) } catch (e: RuntimeException) { null }
            }
        }
        ok = uniToCode.isNotEmpty()
        if (!ok && reason.isEmpty()) reason = "no usable character map"
    }

    private fun descriptorEmbedded(fd: PDFObject): Boolean {
        if (!fd.isDictionary) return false
        return listOf("FontFile", "FontFile2", "FontFile3").any { !fd.get(it).isNull }
    }

    private fun parseCidWidths(w: PDFObject) {
        var i = 0
        val n = w.size()
        while (i < n) {
            val first = w.get(i)
            if (!first.isNumber) { i++; continue }
            val c = first.asInteger()
            if (i + 1 < n && w.get(i + 1).isArray) {
                val arr = w.get(i + 1)
                for (k in 0 until arr.size()) widths[c + k] = arr.get(k).asFloat()
                i += 2
            } else if (i + 2 < n) {
                val c2 = w.get(i + 1).asInteger()
                val v = w.get(i + 2).asFloat()
                if (c2 - c < 65536) for (cc in c..c2) widths[cc] = v
                i += 3
            } else break
        }
    }

    fun missingChars(text: String): List<String> {
        val miss = mutableListOf<String>()
        text.codePoints().forEach { cp ->
            val s = String(Character.toChars(cp))
            if (s !in uniToCode && s !in miss) miss.add(s)
        }
        return miss
    }

    fun canEncode(text: String) = ok && missingChars(text).isEmpty()

    fun encode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        text.codePoints().forEach { cp ->
            val code = uniToCode[String(Character.toChars(cp))] ?: error("unencodable character")
            if (twoByte) out.write((code shr 8) and 0xFF)
            out.write(code and 0xFF)
        }
        return out.toByteArray()
    }

    /** Advance of one character in thousandths of an em. */
    fun charWidth(ch: String): Float {
        metrics?.let { f ->
            val gid = f.encodeCharacter(ch.codePointAt(0))
            return f.advanceGlyph(gid, false) * 1000f
        }
        val code = uniToCode[ch] ?: return defaultWidth
        return widths[code] ?: defaultWidth
    }

    fun textWidth(text: String, size: Float): Float {
        var units = 0f
        text.codePoints().forEach { units += charWidth(String(Character.toChars(it))) }
        return units / 1000f * size
    }

    companion object {
        private val HEX = Regex("<([0-9A-Fa-f\\s]*)>")

        val BASE14 = mapOf(
            "Helvetica" to "Helvetica", "Helvetica-Bold" to "Helvetica-Bold", "Helvetica-Oblique" to "Helvetica-Oblique",
            "Helvetica-BoldOblique" to "Helvetica-BoldOblique", "Arial" to "Helvetica", "Arial-Bold" to "Helvetica-Bold",
            "Arial-Italic" to "Helvetica-Oblique", "Arial-BoldItalic" to "Helvetica-BoldOblique", "ArialMT" to "Helvetica",
            "Times-Roman" to "Times-Roman", "Times-Bold" to "Times-Bold", "Times-Italic" to "Times-Italic",
            "Times-BoldItalic" to "Times-BoldItalic", "TimesNewRoman" to "Times-Roman", "TimesNewRoman-Bold" to "Times-Bold",
            "Courier" to "Courier", "Courier-Bold" to "Courier-Bold", "Courier-Oblique" to "Courier-Oblique",
            "Courier-BoldOblique" to "Courier-BoldOblique", "Symbol" to "Symbol", "ZapfDingbats" to "ZapfDingbats",
        )

        private fun utf16HexToString(h: String): String {
            var s = h.filterNot { it.isWhitespace() }
            if (s.length % 4 != 0) s = s.padStart((s.length + 3) / 4 * 4, '0')
            val bytes = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return String(bytes, Charsets.UTF_16BE)
        }

        fun parseToUnicode(data: String): Map<Int, String> {
            val out = HashMap<Int, String>()
            for (sect in Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(data)) {
                val hexes = HEX.findAll(sect.groupValues[1]).map { it.groupValues[1] }.toList()
                var i = 0
                while (i + 1 < hexes.size) {
                    val code = hexes[i].filterNot { it.isWhitespace() }.ifEmpty { "0" }.toIntOrNull(16)
                    if (code != null) out[code] = utf16HexToString(hexes[i + 1])
                    i += 2
                }
            }
            val range = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<[0-9A-Fa-f\\s]*>|\\[[^\\]]*\\])")
            for (sect in Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(data)) {
                for (m in range.findAll(sect.groupValues[1])) {
                    val lo = m.groupValues[1].toLongOrNull(16)?.toInt() ?: continue
                    val hi = m.groupValues[2].toLongOrNull(16)?.toInt() ?: continue
                    if (hi - lo > 65535 || hi < lo) continue
                    val dst = m.groupValues[3]
                    if (dst.startsWith("[")) {
                        HEX.findAll(dst).map { it.groupValues[1] }.forEachIndexed { k, h ->
                            if (lo + k <= hi) out[lo + k] = utf16HexToString(h)
                        }
                    } else {
                        val base = utf16HexToString(dst.substring(1, dst.length - 1))
                        if (base.isEmpty()) continue
                        val prefix = base.substring(0, base.length - 1)
                        val last = base.last().code
                        for (k in 0..(hi - lo)) {
                            val cp = last + k
                            if (cp > 0xFFFF) break
                            out[lo + k] = prefix + cp.toChar()
                        }
                    }
                }
            }
            return out
        }

        fun simpleBaseTable(name: String): HashMap<Int, String> {
            val table = HashMap<Int, String>()
            val cs: Charset = when (name) {
                "WinAnsiEncoding" -> Charset.forName("windows-1252")
                "MacRomanEncoding" -> Charset.forName("x-MacRoman")
                else -> Charsets.ISO_8859_1
            }
            val dec = cs.newDecoder()
            for (code in 32..255) {
                try {
                    val s = dec.decode(java.nio.ByteBuffer.wrap(byteArrayOf(code.toByte()))).toString()
                    if (s.isNotEmpty() && s[0] != '�') table[code] = s
                } catch (e: java.nio.charset.CharacterCodingException) {
                    continue
                }
            }
            if (name == "StandardEncoding") {
                table[0x27] = "’"
                table[0x60] = "‘"
            }
            return table
        }

        /**
         * Finds the font object a span was drawn with. Spans only carry the font
         * name, so fonts on the page (and in its form XObjects) are matched by
         * BaseFont; when several match, the one that can encode the span's own
         * text wins.
         */
        fun findFont(page: PDFPage, spanFont: String, sample: String = ""): PDFObject? {
            val want = Span.stripSubset(spanFont)
            val cands = mutableListOf<PDFObject>()
            for (f in pageFonts(page)) {
                val base = f.get("BaseFont").takeIf { it.isName }?.asName() ?: continue
                val name = Span.stripSubset(base)
                if (name == want || base == spanFont || name.replace(" ", "") == want.replace(" ", "")) cands.add(f)
            }
            if (cands.isEmpty()) return null
            if (cands.size == 1 || sample.isEmpty()) return cands[0]
            val probe = sample.replace(" ", "")
            return cands.firstOrNull { FontCodec(it).let { c -> c.ok && c.canEncode(probe) } } ?: cands[0]
        }

        /** Font dictionaries used by the page and by form XObjects it draws. */
        fun pageFonts(page: PDFPage): List<PDFObject> {
            val out = mutableListOf<PDFObject>()
            val seen = HashSet<Int>()
            fun walk(res: PDFObject, depth: Int) {
                if (!res.isDictionary || depth > 8) return
                val fonts = res.get("Font")
                for (k in Pdf.dictKeys(fonts)) {
                    val f = fonts.get(k)
                    val id = if (f.isIndirect) f.asIndirect() else -1
                    if (id >= 0 && !seen.add(id)) continue
                    if (f.isDictionary) out.add(f)
                }
                val xo = res.get("XObject")
                for (k in Pdf.dictKeys(xo)) {
                    val x = xo.get(k)
                    val id = if (x.isIndirect) x.asIndirect() else -1
                    if (id >= 0 && !seen.add(-id - 2)) continue
                    if (x.isStream && x.get("Subtype").takeIf { it.isName }?.asName() == "Form") walk(x.get("Resources"), depth + 1)
                }
            }
            walk(page.getObject().getInheritable("Resources"), 0)
            return out
        }
    }
}
