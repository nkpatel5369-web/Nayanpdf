package npdf.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ExportAndScanEditTest {

    private fun parts(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() } }
        return out
    }

    private fun export(d: PDFDocument, f: Export.Format): ByteArray =
        ByteArrayOutputStream().also { Export.write(d, f, "Statement", it) }.toByteArray()

    @Test
    fun financialStatementExportsToEveryFormat() {
        val d = Fixtures.open("financial.pdf")
        val t0 = System.nanoTime()
        val docx = parts(export(d, Export.Format.WORD))
        val xml = String(docx["word/document.xml"]!!)
        assertTrue("word text", xml.contains("Revenue from operations") && xml.contains(">1,23,456.78<"))
        assertTrue("word table", xml.contains("<w:tbl>"))
        val xlsx = parts(export(d, Export.Format.EXCEL))
        val sheet = String(xlsx["xl/worksheets/sheet1.xml"]!!)
        assertTrue("excel amount as a number: $sheet", sheet.contains("<v>123456.78</v>"))
        assertTrue("excel negative in brackets", sheet.contains("<v>-45000.0</v>"))
        assertTrue("excel text cell", sheet.contains("Revenue from operations"))
        val pptx = parts(export(d, Export.Format.POWERPOINT))
        assertEquals(d.countPages(), pptx.keys.count { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) })
        assertTrue(pptx.keys.any { it.startsWith("ppt/media/image1") })
        val rtf = String(export(d, Export.Format.RTF), Charsets.US_ASCII)
        assertTrue(rtf.startsWith("{\\rtf1") && rtf.contains("Revenue from operations") && rtf.contains("\\trowd"))
        Log.i("NPDF-EXPORT", "financial.pdf to Word, Excel, PowerPoint and RTF: ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    @Test
    fun scannedPagesWithoutTextAreReportedAndKeptAsPictures() {
        val d = Fixtures.open("scanned.pdf")
        assertEquals(listOf(1), Export.pagesWithoutText(d))
        val docx = parts(export(d, Export.Format.WORD))
        assertTrue("the scan is kept as a picture", docx.keys.any { it.startsWith("word/media/") })
    }

    /** OCR words for scanned.pdf, taken from the original document's word positions (the scan was made from it). */
    private fun ocrWords(): List<TextLayer.Word> {
        val src = Fixtures.open("financial.pdf")
        val words = ArrayList<TextLayer.Word>()
        for (ln in PageText.of(src.loadPage(0) as PDFPage).lines) {
            val chars = ln.spans.flatMap { it.chars }
            var cur = ArrayList<TextChar>()
            fun flush() {
                if (cur.isEmpty()) return
                val r = cur.map { it.bbox }.reduce(Pdf::union)
                words.add(TextLayer.Word(cur.joinToString("") { String(Character.toChars(it.c)) }, Rect(r.x0, r.y0 + (r.y1 - r.y0) * 0.15f, r.x1, r.y1 - (r.y1 - r.y0) * 0.05f), 92f))
                cur = ArrayList()
            }
            for (c in chars) if (Character.isWhitespace(c.c)) flush() else cur.add(c)
            flush()
        }
        return words
    }

    @Test
    fun scannedTextCanBeEditedAndTheOldPixelsAreGone() {
        val d = Fixtures.open("scanned.pdf")
        val words = ocrWords()
        assertTrue(TextLayer.addWords(d, 1, words) > 20)
        val page = d.loadPage(0) as PDFPage
        val imageBefore = ContentStream.images(Pdf.pageContent(page), page.getObject().getInheritable("Resources")).second.single().obj!!.readRawStream()
        // The word as tapped: just the table cell, not the whole row.
        val cell = words.first { it.text == "Depreciation" }.box
        val r = TextEdit.editText(d, 1, cell, "Amortisation")
        assertEquals("scan", r.fontKind)
        val saved = Fixtures.roundTrip(d)
        val text = Fixtures.text(saved, 1)
        assertTrue(text, text.contains("Amortisation"))
        assertFalse("old text gone from the text layer", text.contains("Depreciation"))
        assertTrue("the row's amounts are untouched: $text", text.contains("12,000.00") && text.contains("11,250.00"))
        // The scan image itself changed (its pixels under the line were removed).
        val p2 = saved.loadPage(0) as PDFPage
        val imgs = ContentStream.images(Pdf.pageContent(p2), p2.getObject().getInheritable("Resources")).second
        assertTrue(imgs.isNotEmpty())
        assertFalse("image pixels changed", imgs.first().obj!!.readRawStream().contentEquals(imageBefore))
        // The new text is visible: dark pixels inside the new area.
        val a = r.newBox
        val pix = p2.toPixmap(Matrix.Scale(2f), ColorSpace.DeviceRGB, false, true)
        var dark = 0; var total = 0
        val s = pix.samples; val n = pix.numberOfComponents
        for (y in (a.y0 * 2).toInt() until (a.y1 * 2).toInt()) for (x in (a.x0 * 2).toInt() until (a.x1 * 2).toInt()) {
            if (x < 0 || y < 0 || x >= pix.width || y >= pix.height) continue
            val o = y * pix.stride + x * n
            val l = ((s[o].toInt() and 0xFF) + (s[o + 1].toInt() and 0xFF) + (s[o + 2].toInt() and 0xFF)) / 3
            total++; if (l < 110) dark++
        }
        Log.i("NPDF-SCANEDIT", "new text area ${a}: ${dark * 100 / total.coerceAtLeast(1)} % ink")
        assertTrue("visible ink: $dark of $total", dark > total * 0.04)
    }

    @Test
    fun scannedColoursAreSampled() {
        val d = Fixtures.open("scanned.pdf")
        val page = d.loadPage(0) as PDFPage
        val box = Fixtures.line(Fixtures.open("financial.pdf"), 1, "ACME Traders Private Limited")
        val (paper, ink) = TextEdit.sampleColours(page, box)
        fun lum(c: Int) = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
        assertTrue("paper ${Integer.toHexString(paper)}", lum(paper) > 220)
        assertTrue("ink ${Integer.toHexString(ink)}", lum(ink) < 120)
    }
}
