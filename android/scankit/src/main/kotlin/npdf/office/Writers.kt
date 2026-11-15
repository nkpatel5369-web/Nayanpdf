package npdf.office

import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Helpers shared by the writers. */
internal object X {
    /** Escapes text for XML and drops characters XML does not allow. */
    fun esc(s: String): String {
        val b = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> b.append("&amp;")
                ch == '<' -> b.append("&lt;")
                ch == '>' -> b.append("&gt;")
                ch == '"' -> b.append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> b.append(' ')
                ch.code < 0x20 || ch == '\uFFFE' || ch == '\uFFFF' -> {}
                else -> b.append(ch)
            }
        }
        return b.toString()
    }

    fun zip(out: OutputStream, parts: List<Pair<String, ByteArray>>) {
        val z = ZipOutputStream(out)
        for ((name, bytes) in parts) {
            z.putNextEntry(ZipEntry(name).apply { time = 315532800000L }) // fixed time: same input, same file
            z.write(bytes)
            z.closeEntry()
        }
        z.finish()
        z.flush()
    }

    fun s(x: String) = x.toByteArray(Charsets.UTF_8)

    const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    const val RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val OREL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun core(title: String): String {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        return HEAD + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>${esc(title)}</dc:title><dc:creator>N PDF</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified></cp:coreProperties>"
    }

    const val APP = HEAD + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>N PDF</Application></Properties>"

    fun emu(pt: Float): Long = (pt * 12700f).roundToLong()
    fun twips(pt: Float): Long = (pt * 20f).roundToLong()

    /** A number written in a table cell, as Excel should store it, or null for text. */
    fun number(cell: String): Pair<Double, Int>? {
        var t = cell.trim()
        if (t.isEmpty()) return null
        var neg = false
        if (t.startsWith("(") && t.endsWith(")")) { neg = true; t = t.substring(1, t.length - 1).trim() }
        t = t.replace("₹", "").replace(Regex("^(?i)(rs\\.?|inr|usd|\\$)\\s*"), "").trim()
        if (t.startsWith("-")) { neg = !neg; t = t.substring(1).trim() }
        val percent = t.endsWith("%")
        if (percent) t = t.dropLast(1).trim()
        // Digits with optional Indian or international grouping and decimals.
        if (!Regex("^\\d{1,3}(,\\d{2,3})*(\\.\\d+)?$|^\\d+(\\.\\d+)?$").matches(t)) return null
        val plain = t.replace(",", "")
        val digits = plain.substringBefore('.')
        // Identifiers, not amounts: leading zeros (account numbers) or more digits than a double keeps exactly.
        if ((digits.length > 1 && digits.startsWith("0")) || plain.count { it.isDigit() } > 15) return null
        var v = plain.toDoubleOrNull() ?: return null
        if (neg) v = -v
        val style = when {
            percent -> { v /= 100.0; 3 }
            t.contains(',') || t.contains('.') || neg -> 1
            else -> 0
        }
        return v to style
    }
}

/** Microsoft Word (.docx). */
object Docx {
    fun write(doc: Doc, out: OutputStream) {
        val media = ArrayList<Pair<String, ByteArray>>()
        val rels = StringBuilder()
        val body = StringBuilder()
        val first = doc.pages.firstOrNull()
        val pw = first?.widthPt ?: 595f; val ph = first?.heightPt ?: 842f
        val margin = 54f
        val contentW = pw - 2 * margin
        var picId = 1
        for ((pi, page) in doc.pages.withIndex()) {
            if (pi > 0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            for (b in page.blocks) when (b) {
                is Para -> body.append(para(b))
                is Table -> body.append(table(b, contentW))
                is Picture -> {
                    val name = "image$picId.${if (b.png) "png" else "jpeg"}"
                    val rid = "rIdImg$picId"
                    media.add("word/media/$name" to b.bytes)
                    rels.append("<Relationship Id=\"$rid\" Type=\"${X.OREL}/image\" Target=\"media/$name\"/>")
                    val s = min(1f, contentW / b.widthPt.coerceAtLeast(1f))
                    body.append(picture(rid, picId, b.widthPt * s, b.heightPt * s))
                    picId++
                }
            }
            if (page.blocks.isEmpty()) body.append("<w:p/>")
        }
        body.append("<w:sectPr><w:pgSz w:w=\"${X.twips(pw)}\" w:h=\"${X.twips(ph)}\"/>" +
            "<w:pgMar w:top=\"${X.twips(margin)}\" w:right=\"${X.twips(margin)}\" w:bottom=\"${X.twips(margin)}\" w:left=\"${X.twips(margin)}\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>")
        val document = X.HEAD + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"${X.OREL}\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>$body</w:body></w:document>"
        val types = X.HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/><Default Extension=\"png\" ContentType=\"image/png\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/></Types>"
        val rootRels = X.HEAD + "<Relationships xmlns=\"${X.RELS}\">" +
            "<Relationship Id=\"rId1\" Type=\"${X.OREL}/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"${X.OREL}/extended-properties\" Target=\"docProps/app.xml\"/></Relationships>"
        val docRels = X.HEAD + "<Relationships xmlns=\"${X.RELS}\"><Relationship Id=\"rIdStyles\" Type=\"${X.OREL}/styles\" Target=\"styles.xml\"/>$rels</Relationships>"
        X.zip(out, listOf(
            "[Content_Types].xml" to X.s(types), "_rels/.rels" to X.s(rootRels),
            "docProps/core.xml" to X.s(X.core(doc.title)), "docProps/app.xml" to X.s(X.APP),
            "word/document.xml" to X.s(document), "word/_rels/document.xml.rels" to X.s(docRels), "word/styles.xml" to X.s(STYLES),
        ) + media)
    }

    private fun runXml(r: Run): String {
        val props = StringBuilder()
        if (r.bold) props.append("<w:b/>")
        if (r.italic) props.append("<w:i/>")
        if (r.rgb and 0xFFFFFF != 0) props.append("<w:color w:val=\"%06X\"/>".format(r.rgb and 0xFFFFFF))
        props.append("<w:sz w:val=\"${(r.size * 2).toInt().coerceIn(8, 144)}\"/>")
        return "<w:r><w:rPr>$props</w:rPr><w:t xml:space=\"preserve\">${X.esc(r.text)}</w:t></w:r>"
    }

    private fun para(p: Para): String {
        val pp = StringBuilder()
        if (p.heading > 0) pp.append("<w:pStyle w:val=\"Heading${p.heading}\"/>")
        when (p.align) { Align.CENTER -> pp.append("<w:jc w:val=\"center\"/>"); Align.RIGHT -> pp.append("<w:jc w:val=\"right\"/>"); else -> {} }
        val runs = p.runs.joinToString("") { runXml(it) }
        return "<w:p>${if (pp.isNotEmpty()) "<w:pPr>$pp</w:pPr>" else ""}$runs</w:p>"
    }

    private fun table(t: Table, width: Float): String {
        val n = max(1, t.columns)
        val colW = X.twips(width / n)
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid>")
        repeat(n) { sb.append("<w:gridCol w:w=\"$colW\"/>") }
        sb.append("</w:tblGrid>")
        for (row in t.rows) {
            sb.append("<w:tr>")
            for (c in 0 until n) {
                val text = row.getOrNull(c) ?: ""
                val jc = if (X.number(text) != null) "<w:pPr><w:jc w:val=\"right\"/></w:pPr>" else ""
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$colW\" w:type=\"dxa\"/></w:tcPr><w:p>$jc<w:r><w:t xml:space=\"preserve\">${X.esc(text)}</w:t></w:r></w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl><w:p/>")
        return sb.toString()
    }

    private fun picture(rid: String, id: Int, wPt: Float, hPt: Float): String {
        val cx = X.emu(wPt); val cy = X.emu(hPt)
        return "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:docPr id=\"$id\" name=\"Picture $id\"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>" +
            "<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"Picture $id\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }

    private val STYLES = X.HEAD + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-IN\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"160\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/></w:rPr></w:style>" +
        "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
        "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:basedOn w:val=\"TableNormal\"/><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:tblPr><w:tblBorders>" +
        "<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/><w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
        "<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
        "</w:tblBorders></w:tblPr></w:style></w:styles>"
}

/** Microsoft Excel (.xlsx): one sheet per page; amounts become numbers. */
object Xlsx {
    fun write(title: String, sheets: List<Pair<String, List<List<String>>>>, out: OutputStream) {
        val names = uniqueNames(sheets.map { it.first })
        val parts = ArrayList<Pair<String, ByteArray>>()
        val wbSheets = StringBuilder(); val wbRels = StringBuilder(); val overrides = StringBuilder()
        for ((i, s) in sheets.withIndex()) {
            val n = i + 1
            wbSheets.append("<sheet name=\"${X.esc(names[i])}\" sheetId=\"$n\" r:id=\"rId$n\"/>")
            wbRels.append("<Relationship Id=\"rId$n\" Type=\"${X.OREL}/worksheet\" Target=\"worksheets/sheet$n.xml\"/>")
            overrides.append("<Override PartName=\"/xl/worksheets/sheet$n.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            parts.add("xl/worksheets/sheet$n.xml" to X.s(sheet(s.second)))
        }
        val styleRel = sheets.size + 1
        wbRels.append("<Relationship Id=\"rId$styleRel\" Type=\"${X.OREL}/styles\" Target=\"styles.xml\"/>")
        val workbook = X.HEAD + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"${X.OREL}\"><sheets>$wbSheets</sheets></workbook>"
        val types = X.HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>$overrides" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/></Types>"
        val rootRels = X.HEAD + "<Relationships xmlns=\"${X.RELS}\"><Relationship Id=\"rId1\" Type=\"${X.OREL}/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"${X.OREL}/extended-properties\" Target=\"docProps/app.xml\"/></Relationships>"
        X.zip(out, listOf(
            "[Content_Types].xml" to X.s(types), "_rels/.rels" to X.s(rootRels),
            "docProps/core.xml" to X.s(X.core(title)), "docProps/app.xml" to X.s(X.APP),
            "xl/workbook.xml" to X.s(workbook), "xl/_rels/workbook.xml.rels" to X.s(X.HEAD + "<Relationships xmlns=\"${X.RELS}\">$wbRels</Relationships>"),
            "xl/styles.xml" to X.s(STYLES),
        ) + parts)
    }

    fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }; return sb.reverse().toString() }

    private fun sheet(rows: List<List<String>>): String {
        val ncol = rows.maxOfOrNull { it.size } ?: 0
        val sb = StringBuilder(X.HEAD + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        if (ncol > 0) {
            sb.append("<cols>")
            for (c in 0 until ncol) {
                val w = (rows.maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 8).coerceIn(8, 60) + 2
                sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$w\" customWidth=\"1\"/>")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        for ((r, row) in rows.withIndex()) {
            sb.append("<row r=\"${r + 1}\">")
            for ((c, text) in row.withIndex()) {
                if (text.isBlank()) continue
                val ref = col(c) + (r + 1)
                val num = X.number(text)
                if (num != null) sb.append("<c r=\"$ref\"${if (num.second != 0) " s=\"${num.second}\"" else ""}><v>${num.first}</v></c>")
                else sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${X.esc(text)}</t></is></c>")
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun uniqueNames(raw: List<String>): List<String> {
        val used = HashSet<String>()
        return raw.mapIndexed { i, n ->
            var base = n.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().ifEmpty { "Sheet ${i + 1}" }.take(28)
            var name = base; var k = 2
            while (!used.add(name.lowercase())) { name = "$base ${k++}".take(31) }
            name
        }
    }

    private const val STYLES = X.HEAD + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00;(#,##0.00)\"/></numFmts>" +
        "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"4\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "<xf numFmtId=\"10\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
}

/** Microsoft PowerPoint (.pptx): one slide per page, showing the page as a picture. */
object Pptx {
    fun write(doc: Doc, out: OutputStream) {
        val pages = doc.pages.filter { it.render != null }
        require(pages.isNotEmpty()) { "No pages to export" }
        val first = pages.first()
        val sx = X.emu(first.widthPt).coerceIn(914400L, 51206400L)
        val sy = X.emu(first.heightPt).coerceIn(914400L, 51206400L)
        val parts = ArrayList<Pair<String, ByteArray>>()
        val sldIds = StringBuilder(); val presRels = StringBuilder(); val overrides = StringBuilder()
        presRels.append("<Relationship Id=\"rId1\" Type=\"${X.OREL}/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
        presRels.append("<Relationship Id=\"rId2\" Type=\"${X.OREL}/theme\" Target=\"theme/theme1.xml\"/>")
        for ((i, p) in pages.withIndex()) {
            val n = i + 1
            val pic = p.render!!
            val ext = if (pic.png) "png" else "jpeg"
            sldIds.append("<p:sldId id=\"${255 + n}\" r:id=\"rId${n + 2}\"/>")
            presRels.append("<Relationship Id=\"rId${n + 2}\" Type=\"${X.OREL}/slide\" Target=\"slides/slide$n.xml\"/>")
            overrides.append("<Override PartName=\"/ppt/slides/slide$n.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
            // Fit the page inside the slide, centred.
            val s = min(sx.toDouble() / X.emu(pic.widthPt), sy.toDouble() / X.emu(pic.heightPt))
            val cx = (X.emu(pic.widthPt) * s).toLong(); val cy = (X.emu(pic.heightPt) * s).toLong()
            val ox = (sx - cx) / 2; val oy = (sy - cy) / 2
            parts.add("ppt/slides/slide$n.xml" to X.s(X.HEAD + "<p:sld xmlns:a=\"$A\" xmlns:r=\"${X.OREL}\" xmlns:p=\"$P\"><p:cSld><p:spTree>$GROUP" +
                "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Page $n\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
                "<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
                "<p:spPr><a:xfrm><a:off x=\"$ox\" y=\"$oy\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>" +
                "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"))
            parts.add("ppt/slides/_rels/slide$n.xml.rels" to X.s(X.HEAD + "<Relationships xmlns=\"${X.RELS}\">" +
                "<Relationship Id=\"rId1\" Type=\"${X.OREL}/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"${X.OREL}/image\" Target=\"../media/image$n.$ext\"/></Relationships>"))
            parts.add("ppt/media/image$n.$ext" to pic.bytes)
        }
        val presentation = X.HEAD + "<p:presentation xmlns:a=\"$A\" xmlns:r=\"${X.OREL}\" xmlns:p=\"$P\" saveSubsetFonts=\"1\">" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>$sldIds</p:sldIdLst>" +
            "<p:sldSz cx=\"$sx\" cy=\"$sy\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/><p:defaultTextStyle/></p:presentation>"
        val master = X.HEAD + "<p:sldMaster xmlns:a=\"$A\" xmlns:r=\"${X.OREL}\" xmlns:p=\"$P\"><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>$GROUP</p:spTree></p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"
        val layout = X.HEAD + "<p:sldLayout xmlns:a=\"$A\" xmlns:r=\"${X.OREL}\" xmlns:p=\"$P\" type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>$GROUP</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"
        val types = X.HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/><Default Extension=\"png\" ContentType=\"image/png\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>$overrides" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/></Types>"
        val rootRels = X.HEAD + "<Relationships xmlns=\"${X.RELS}\"><Relationship Id=\"rId1\" Type=\"${X.OREL}/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"${X.OREL}/extended-properties\" Target=\"docProps/app.xml\"/></Relationships>"
        X.zip(out, listOf(
            "[Content_Types].xml" to X.s(types), "_rels/.rels" to X.s(rootRels),
            "docProps/core.xml" to X.s(X.core(doc.title)), "docProps/app.xml" to X.s(X.APP),
            "ppt/presentation.xml" to X.s(presentation),
            "ppt/_rels/presentation.xml.rels" to X.s(X.HEAD + "<Relationships xmlns=\"${X.RELS}\">$presRels</Relationships>"),
            "ppt/slideMasters/slideMaster1.xml" to X.s(master),
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to X.s(X.HEAD + "<Relationships xmlns=\"${X.RELS}\">" +
                "<Relationship Id=\"rId1\" Type=\"${X.OREL}/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"${X.OREL}/theme\" Target=\"../theme/theme1.xml\"/></Relationships>"),
            "ppt/slideLayouts/slideLayout1.xml" to X.s(layout),
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to X.s(X.HEAD + "<Relationships xmlns=\"${X.RELS}\">" +
                "<Relationship Id=\"rId1\" Type=\"${X.OREL}/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>"),
            "ppt/theme/theme1.xml" to X.s(THEME),
        ) + parts)
    }

    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val GROUP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    private fun fill(c: String) = "<a:solidFill><a:schemeClr val=\"$c\"/></a:solidFill>"
    private val THEME = X.HEAD + "<a:theme xmlns:a=\"$A\" name=\"N PDF\"><a:themeElements>" +
        "<a:clrScheme name=\"N PDF\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"1F1F1F\"/></a:dk2><a:lt2><a:srgbClr val=\"F2F2F2\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"C8102E\"/></a:accent1><a:accent2><a:srgbClr val=\"4472C4\"/></a:accent2><a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3>" +
        "<a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4><a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
        "<a:fontScheme name=\"N PDF\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"N PDF\"><a:fillStyleLst>${fill("phClr")}${fill("phClr")}${fill("phClr")}</a:fillStyleLst>" +
        "<a:lnStyleLst><a:ln w=\"6350\">${fill("phClr")}</a:ln><a:ln w=\"12700\">${fill("phClr")}</a:ln><a:ln w=\"19050\">${fill("phClr")}</a:ln></a:lnStyleLst>" +
        "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
        "<a:bgFillStyleLst>${fill("phClr")}${fill("phClr")}${fill("phClr")}</a:bgFillStyleLst></a:fmtScheme>" +
        "</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
}

/** Rich Text Format (.rtf), readable by every word processor. */
object Rtf {
    fun write(doc: Doc, out: OutputStream) {
        val first = doc.pages.firstOrNull()
        val pw = first?.widthPt ?: 595f; val ph = first?.heightPt ?: 842f
        val colours = LinkedHashMap<Int, Int>() // rgb -> index (1-based)
        for (p in doc.pages) for (b in p.blocks) if (b is Para) for (r in b.runs) if (r.rgb and 0xFFFFFF != 0) colours.getOrPut(r.rgb and 0xFFFFFF) { colours.size + 1 }
        val sb = StringBuilder("{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Calibri;}}")
        sb.append("{\\colortbl;")
        for (c in colours.keys) sb.append("\\red${(c shr 16) and 0xFF}\\green${(c shr 8) and 0xFF}\\blue${c and 0xFF};")
        sb.append("}\\paperw${X.twips(pw)}\\paperh${X.twips(ph)}\\margl1080\\margr1080\\margt1080\\margb1080\n")
        val contentW = pw - 108f
        for ((pi, page) in doc.pages.withIndex()) {
            if (pi > 0) sb.append("\\page\n")
            for (b in page.blocks) when (b) {
                is Para -> {
                    sb.append("\\pard\\sa120")
                    sb.append(when (b.align) { Align.CENTER -> "\\qc"; Align.RIGHT -> "\\qr"; else -> "\\ql" })
                    sb.append(' ')
                    for (r in b.runs) {
                        sb.append("{")
                        if (r.bold || b.heading > 0) sb.append("\\b")
                        if (r.italic) sb.append("\\i")
                        sb.append("\\fs${(r.size * 2).toInt().coerceIn(8, 144)}")
                        colours[r.rgb and 0xFFFFFF]?.let { sb.append("\\cf$it") }
                        sb.append(' ').append(text(r.text)).append("}")
                    }
                    sb.append("\\par\n")
                }
                is Table -> {
                    val n = max(1, b.columns)
                    val w = X.twips(contentW / n)
                    for (row in b.rows) {
                        sb.append("\\trowd\\trgaph108")
                        for (c in 1..n) sb.append("\\clbrdrt\\brdrs\\clbrdrl\\brdrs\\clbrdrb\\brdrs\\clbrdrr\\brdrs\\cellx${w * c}")
                        for (c in 0 until n) {
                            val t = row.getOrNull(c) ?: ""
                            sb.append("\\pard\\intbl${if (X.number(t) != null) "\\qr" else "\\ql"} ").append(text(t)).append("\\cell")
                        }
                        sb.append("\\row\n")
                    }
                    sb.append("\\pard\\par\n")
                }
                is Picture -> {
                    val s = min(1f, contentW / b.widthPt.coerceAtLeast(1f))
                    sb.append("\\pard{\\pict${if (b.png) "\\pngblip" else "\\jpegblip"}\\picwgoal${X.twips(b.widthPt * s)}\\pichgoal${X.twips(b.heightPt * s)}\n")
                    val hex = "0123456789abcdef"
                    for ((i, byte) in b.bytes.withIndex()) {
                        val v = byte.toInt() and 0xFF
                        sb.append(hex[v shr 4]).append(hex[v and 15])
                        if (i % 64 == 63) sb.append('\n')
                    }
                    sb.append("}\\par\n")
                }
            }
        }
        sb.append("}")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    /** RTF text: escapes, and every character beyond ASCII as \\uN? (UTF-16 units, signed). */
    fun text(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '\\' || ch == '{' || ch == '}' -> sb.append('\\').append(ch)
                ch == '\t' -> sb.append("\\tab ")
                ch == '\n' || ch == '\r' -> sb.append("\\line ")
                ch.code < 0x20 -> {}
                ch.code < 0x80 -> sb.append(ch)
                else -> sb.append("\\u").append(ch.code.toShort().toInt()).append('?')
            }
        }
        return sb.toString()
    }
}
