package npdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityCompressOcrTest {

    @Test
    fun protectRestrictAndRemoveWithOwnerPassword() {
        val d = Fixtures.open("a.pdf")
        val out = Fixtures.out("protected.pdf")
        Security.protect(d, out, "Open#123", "Own#456", Security.Permissions(print = true, copy = false, edit = false, annotate = false, forms = false, assemble = false))
        try { Pdf.open(out.absolutePath); fail("password must be required") } catch (e: PdfError) { assertEquals(Codes.PASSWORD_REQUIRED, e.code) }
        try { Pdf.open(out.absolutePath, "wrong"); fail("wrong password must fail") } catch (e: PdfError) { assertEquals(Codes.PASSWORD_WRONG, e.code) }
        val user = Pdf.open(out.absolutePath, "Open#123")
        assertTrue(user.hasPermission(Document.PERMISSION_PRINT))
        assertFalse(user.hasPermission(Document.PERMISSION_EDIT))
        assertFalse(Security.hasFullRights(user))
        try { Security.removeProtection(user, Fixtures.out("x.pdf")); fail("must not bypass") } catch (e: PdfError) { assertEquals(Codes.RESTRICTED, e.code) }
        val owner = Pdf.open(out.absolutePath, "Own#456")
        val clear = Fixtures.out("clear.pdf")
        Security.removeProtection(owner, clear)
        val c = Pdf.open(clear.absolutePath)
        assertFalse(c.needsPassword())
        assertTrue(Fixtures.text(c, 1).contains("A page 1"))
    }

    @Test
    fun restrictionsWithoutPermissionsPasswordAreRefused() {
        val d = Fixtures.open("a.pdf")
        try {
            Security.protect(d, Fixtures.out("p.pdf"), "abc", null, Security.Permissions(copy = false))
            fail("expected refusal")
        } catch (e: PdfError) {
            assertEquals(Codes.INVALID, e.code)
        }
    }

    @Test
    fun compressionShrinksPicturesAndKeepsText() {
        val src = Fixtures.file("image_heavy.pdf")
        val d = Pdf.open(src.absolutePath)
        val out = Fixtures.out("small.pdf")
        val r = Compress.compress(d, src, out, "balanced")
        assertTrue(r.kept)
        assertTrue("resampled ${r.imagesResampled}", r.imagesResampled >= 1)
        assertTrue("before ${r.before} after ${r.after}", r.after < r.before * 0.7)
        val c = Pdf.open(out.absolutePath)
        assertTrue(Fixtures.text(c, 1).contains("Image heavy page - text must survive compression"))
        // The picture is still there, at a lower resolution.
        val pl = ImageEdit.list(c, 1)
        assertEquals(1, pl.size)
        android.util.Log.i("NPDF-COMPRESS", "image_heavy.pdf: ${r.before} -> ${r.after} bytes")
    }

    @Test
    fun noGainLeavesNoFile() {
        val src = Fixtures.file("a.pdf")
        val out = Fixtures.out("same.pdf")
        val r = Compress.compress(Pdf.open(src.absolutePath), src, out, "maximum")
        if (!r.kept) assertFalse(out.exists())
    }

    @Test
    fun ocrTextLayerIsInvisibleAndSearchable() {
        val d = Fixtures.open("scanned.pdf")
        assertFalse(TextLayer.hasText(d, 1))
        val page = d.loadPage(0)
        val before = page.toPixmap(Matrix.Scale(0.5f), ColorSpace.DeviceRGB, false, true).computeMD5()
        val words = listOf(
            TextLayer.Word("Particulars", Rect(90f, 129f, 140f, 143f), 95f),
            TextLayer.Word("Revenue", Rect(90f, 142f, 128f, 156f), 95f),
            TextLayer.Word("नमस्ते", Rect(300f, 142f, 340f, 156f), 80f),
            TextLayer.Word("₹1,234", Rect(400f, 142f, 440f, 156f), 80f),
        )
        assertEquals(4, TextLayer.addWords(d, 1, words))
        val saved = Fixtures.roundTrip(d)
        val p2 = saved.loadPage(0)
        val after = p2.toPixmap(Matrix.Scale(0.5f), ColorSpace.DeviceRGB, false, true).computeMD5()
        assertTrue("the page looks exactly the same", before.contentEquals(after))
        val text = Fixtures.text(saved, 1)
        for (w in words) assertTrue("${w.text} in $text", text.contains(w.text))
        val hits = DocInfo.searchPage(saved, 1, "Revenue")
        assertEquals(1, hits.size)
        val hb = Pdf.quadRect(hits[0].quads[0])
        assertTrue("search box matches the word: $hb", Math.abs(hb.x0 - 90f) < 2f && Math.abs(hb.x1 - 128f) < 2f && Math.abs(hb.y1 - 156f) < 2f)
        assertTrue(TextLayer.hasText(saved, 1))
    }

    @Test
    fun searchFindsPhrasesAcrossLinesAndSelectionWorks() {
        val d = Fixtures.open("financial.pdf")
        val hits = DocInfo.searchPage(d, 1, "revenue   FROM operations")
        assertEquals("whitespace and case do not matter", 1, hits.size)
        val t = PageText.of(d.loadPage(0) as com.artifex.mupdf.fitz.PDFPage)
        // Select from the middle of "Revenue" to the middle of "operations": whole words.
        val line = t.lines.first { it.text.startsWith("Revenue from operations") }
        val b = line.bbox
        val r = t.selectionRange(b.x0 + 5f, (b.y0 + b.y1) / 2, b.x1 - 5f, (b.y0 + b.y1) / 2, words = true)!!
        assertEquals("Revenue from operations", t.text(r))
        val q = t.quads(r)
        assertEquals(1, q.size)
        val qr = Pdf.quadRect(q[0])
        assertTrue("selection box $qr inside line $b", qr.x0 >= b.x0 - 0.5f && qr.x1 <= b.x1 + 0.5f)
        // A selection over two lines gives one quad per line and a line break in the text.
        val second = t.lines.first { it.text.trim() == "(45,000.00)" }.bbox
        val r2 = t.selectionRange(b.x0 + 1f, (b.y0 + b.y1) / 2, second.x1 - 1f, (second.y0 + second.y1) / 2, words = true)!!
        assertTrue(t.text(r2).contains("\n"))
        assertTrue(t.quads(r2).size >= 2)
    }

    @Test
    fun metadataAndSearch() {
        val d = Fixtures.open("a.pdf")
        assertEquals("A test document", DocInfo.metadata(d)["Title"])
        DocInfo.setMetadata(d, mapOf("Title" to "Engagement letter", "Author" to "N K Patel"))
        val s = Fixtures.roundTrip(d)
        assertEquals("Engagement letter", DocInfo.metadata(s)["Title"])
        assertEquals("N K Patel", DocInfo.metadata(s)["Author"])
        assertEquals(1, DocInfo.searchPage(s, 1, "marker a-001").size)
        assertEquals(0, DocInfo.searchPage(s, 1, "marker a-001", matchCase = true).size)
    }
}
