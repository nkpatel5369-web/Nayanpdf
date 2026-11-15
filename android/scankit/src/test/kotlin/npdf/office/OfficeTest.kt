package npdf.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

class OfficeTest {

    private fun jpeg(w: Int, h: Int, c: Color): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = c; g.fillRect(0, 0, w, h); g.color = Color.WHITE; g.drawString("Page", 10, 20); g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
    }

    private fun span(t: String, x0: Float, size: Float = 11f, bold: Boolean = false) = TSpan(t, x0, x0 + t.length * size * 0.5f, size, bold)
    private fun line(t: String, x0: Float, y: Float, size: Float = 11f, bold: Boolean = false): TLine {
        val s = span(t, x0, size, bold)
        return TLine(s.x0, y, s.x1, y + size * 1.2f, listOf(s))
    }

    /** A statement page: heading, a wrapped paragraph, a table of amounts, a footer line. */
    private fun statementLines(): List<TLine> {
        val l = ArrayList<TLine>()
        l.add(line("ACME Traders Private Limited", 160f, 60f, 20f, true))
        l.add(line("Statement of Profit and Loss for the year ended 31 March 2026, prepared under the Com-", 54f, 100f))
        l.add(line("panies Act and presented in Indian rupees, with comparative figures for the previous", 54f, 114f))
        l.add(line("year.", 54f, 128f))
        val rows = listOf(listOf("Particulars", "FY 2025-26", "FY 2024-25"), listOf("Revenue from operations", "1,23,456.78", "98,765.43"),
            listOf("Other expenses", "(45,000.00)", "(40,500.10)"), listOf("Depreciation", "12,000.00", "11,250.00"), listOf("Net profit", "66,456.78", "47,015.33"))
        var y = 170f
        for (r in rows) { l.add(line(r[0], 54f, y)); l.add(line(r[1], 300f, y)); l.add(line(r[2], 420f, y)); y += 16f }
        l.add(line("Account 000123456789", 54f, 300f))
        return l
    }

    @Test
    fun layoutFindsHeadingParagraphAndTable() {
        val blocks = Layout.blocks(statementLines(), emptyList(), 595f, 842f)
        val heading = blocks[0] as Para
        assertEquals(1, heading.heading)
        assertEquals(Align.CENTER, heading.align)
        val para = blocks[1] as Para
        assertTrue(para.text, para.text.contains("Companies Act") && para.text.contains("previous year."))
        val table = blocks[2] as Table
        assertEquals(5, table.rows.size)
        assertEquals(listOf("Revenue from operations", "1,23,456.78", "98,765.43"), table.rows[1])
        assertEquals("Account 000123456789", (blocks[3] as Para).text.trim())
    }

    @Test
    fun spreadsheetRowsKeepColumns() {
        val rows = Layout.sheet(statementLines())
        val revenue = rows.first { it[0] == "Revenue from operations" }
        assertEquals(listOf("Revenue from operations", "1,23,456.78", "98,765.43"), revenue)
    }

    @Test
    fun amountsBecomeNumbersAndIdentifiersStayText() {
        assertEquals(123456.78 to 1, X.number("1,23,456.78"))
        assertEquals(-45000.0 to 1, X.number("(45,000.00)"))
        assertEquals(-12.5 to 1, X.number("-12.5"))
        assertEquals(0.125 to 3, X.number("12.5%"))
        assertEquals(2025.0 to 0, X.number("2025"))
        assertEquals(1000.0 to 1, X.number("Rs. 1,000"))
        assertEquals(1500.0 to 1, X.number("₹1,500.00"))
        assertNull(X.number("000123456789"))
        assertNull(X.number("1234567890123456789"))
        assertNull(X.number("FY 2025-26"))
        assertNull(X.number("27ABCDE1234F1Z5"))
    }

    private fun parts(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() }
        }
        return out
    }

    private fun assertWellFormed(parts: Map<String, ByteArray>) {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((name, b) in parts) if (name.endsWith(".xml") || name.endsWith(".rels")) {
            f.newDocumentBuilder().parse(ByteArrayInputStream(b)) // throws on malformed XML
        }
    }

    private fun sampleDoc(): Doc {
        val p1 = Page(595f, 842f, Layout.blocks(statementLines(), listOf(TImage(54f, 330f, 254f, 430f, jpeg(200, 100, Color.BLUE), false)), 595f, 842f),
            Picture(jpeg(595, 842, Color.DARK_GRAY), false, 595f, 842f))
        val p2 = Page(595f, 842f, listOf(Para(listOf(Run("Second page: ₹ amounts, हिंदी text & <symbols> \"quoted\"", 11f, italic = true, rgb = 0xC8102E)))),
            Picture(jpeg(595, 842, Color.GRAY), false, 595f, 842f))
        return Doc("Statement & Notes", listOf(p1, p2))
    }

    @Test
    fun wordFileIsWellFormed() {
        val out = ByteArrayOutputStream(); Docx.write(sampleDoc(), out)
        val p = parts(out.toByteArray())
        assertWellFormed(p)
        assertTrue(p.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels", "word/media/image1.jpeg")))
        val doc = String(p["word/document.xml"]!!)
        assertTrue(doc.contains("1,23,456.78") && doc.contains("w:tbl") && doc.contains("w:br w:type=\"page\"") && doc.contains("हिंदी") && doc.contains("&lt;symbols&gt;"))
        save("sample.docx", out.toByteArray())
    }

    @Test
    fun excelFileIsWellFormedWithNumbers() {
        val out = ByteArrayOutputStream()
        Xlsx.write("Statement", listOf("Page 1" to Layout.sheet(statementLines()), "Page: 1?" to listOf(listOf("x", "1.5"))), out)
        val p = parts(out.toByteArray())
        assertWellFormed(p)
        val s1 = String(p["xl/worksheets/sheet1.xml"]!!)
        assertTrue(s1, s1.contains("<v>123456.78</v>") && s1.contains("<v>-45000.0</v>") && s1.contains(">000123456789<").not())
        assertTrue(s1.contains("Account 000123456789"))
        val wb = String(p["xl/workbook.xml"]!!)
        assertTrue(wb, wb.contains("name=\"Page 1\"") && wb.contains("name=\"Page  1\"") )
        save("sample.xlsx", out.toByteArray())
    }

    @Test
    fun powerPointFileIsWellFormed() {
        val out = ByteArrayOutputStream(); Pptx.write(sampleDoc(), out)
        val p = parts(out.toByteArray())
        assertWellFormed(p)
        assertTrue(p.keys.containsAll(listOf("ppt/presentation.xml", "ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/media/image1.jpeg", "ppt/theme/theme1.xml",
            "ppt/slideMasters/slideMaster1.xml", "ppt/slideLayouts/slideLayout1.xml")))
        save("sample.pptx", out.toByteArray())
    }

    @Test
    fun rtfIsAsciiWithUnicodeEscapes() {
        val out = ByteArrayOutputStream(); Rtf.write(sampleDoc(), out)
        val s = String(out.toByteArray(), Charsets.US_ASCII)
        assertTrue(s.startsWith("{\\rtf1") && s.endsWith("}"))
        assertTrue(s.contains("\\u8377?")) // the rupee sign, U+20B9
        assertTrue(s.contains("\\u2361?")) // Devanagari HA, U+0939
        assertTrue(s.contains("\\trowd") && s.contains("\\jpegblip") && s.contains("\\page"))
        // Groups balance (escaped braces are text).
        var depth = 0; var i = 0
        while (i < s.length) { when (s[i]) { '\\' -> i++; '{' -> depth++; '}' -> depth-- }; i++ }
        assertEquals(0, depth)
        save("sample.rtf", out.toByteArray())
    }

    /** Writes samples for the LibreOffice cross-check when -Dnpdf.officeOut=<dir> is given. */
    private fun save(name: String, bytes: ByteArray) {
        val dir = System.getProperty("npdf.officeOut") ?: return
        File(dir).apply { mkdirs() }.resolve(name).writeBytes(bytes)
    }
}
