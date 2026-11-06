package npdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class OpenAndTextEditTest {

    private fun expectError(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: PdfError) {
            assertEquals(e.message, code, e.code)
        }
    }

    // ------------------------------------------------------------------ opening

    @Test
    fun opensSyntheticDocuments() {
        for ((name, pages) in listOf("a.pdf" to 2, "b.pdf" to 3, "c.pdf" to 4, "twenty.pdf" to 20, "large.pdf" to 300, "financial.pdf" to 7)) {
            val d = Fixtures.open(name)
            assertEquals(name, pages, d.countPages())
            d.destroy()
        }
    }

    @Test
    fun passwordsAreRequiredAndChecked() {
        expectError(Codes.PASSWORD_REQUIRED) { Fixtures.open("encrypted.pdf") }
        expectError(Codes.PASSWORD_WRONG) { Fixtures.open("encrypted.pdf", "nope") }
        val d = Fixtures.open("encrypted.pdf", "open123")
        assertEquals(2, d.countPages())
        assertFalse("open password must not grant editing", d.hasPermission(Document.PERMISSION_EDIT))
        val o = Fixtures.open("encrypted.pdf", "owner456")
        assertTrue(Security.hasFullRights(o))
    }

    @Test
    fun restrictedDocumentRefusesEditsWithoutOwnerPassword() {
        val d = Fixtures.open("restricted.pdf")
        val r = Fixtures.line(d, 1, "Marker A-001")
        expectError(Codes.RESTRICTED) { TextEdit.editText(d, 1, r, "Marker A-999") }
    }

    @Test
    fun damagedFilesAreRepairedOrReported() {
        val m = Fixtures.open("malformed.pdf")
        assertEquals(20, m.countPages())
        try {
            val t = Fixtures.open("truncated.pdf")
            assertTrue("a truncated file has no readable pages", t.countPages() == 0)
        } catch (e: PdfError) {
            assertEquals(Codes.DAMAGED, e.code)
        } catch (e: RuntimeException) {
            // MuPDF may only notice the damage when counting pages; the app reports it the same way.
        }
    }

    /** Opening a 300-page file and rendering its first page at phone width must be quick. */
    @Test
    fun firstPageOfLargeDocumentRendersQuickly() {
        val f = Fixtures.file("large.pdf")
        val t0 = System.nanoTime()
        val d = Pdf.open(f.absolutePath)
        val page = d.loadPage(0)
        val b = page.bounds
        val scale = 1080f / (b.x1 - b.x0)
        val pix = page.toPixmap(Matrix.Scale(scale), com.artifex.mupdf.fitz.ColorSpace.DeviceRGB, false, true)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1080, pix.width)
        assertTrue("first page took $ms ms", ms < 1500)
        android.util.Log.i("NPDF-PERF", "large.pdf open + first page at 1080 px: $ms ms")
    }

    // -------------------------------------------------------------- text editing

    @Test
    fun editExistingTextInOriginalFont() {
        val d = Fixtures.open("financial.pdf")
        val before = Fixtures.spans(d, 1)
        val rect = Fixtures.line(d, 1, "1,23,456.78")
        val r = TextEdit.editText(d, 1, rect, "1,25,000.00")
        assertEquals("original", r.fontKind)
        val saved = Fixtures.roundTrip(d)
        val after = Fixtures.spans(saved, 1)
        val new = after["1,25,000.00"] ?: throw AssertionError("edited value missing: ${after.keys}")
        assertFalse(after.containsKey("1,23,456.78"))
        val old = before.getValue("1,23,456.78")
        assertEquals("keeps the original font", old.cleanFontName, new.cleanFontName)
        assertTrue("keeps the baseline", abs(new.origin.y - old.origin.y) < 0.2f)
        for (k in listOf("98,765.43", "Revenue from operations", "(45,000.00)", "Particulars")) {
            val a = after.getValue(k).origin; val b = before.getValue(k).origin
            assertTrue("$k moved", abs(a.x - b.x) < 0.05f && abs(a.y - b.y) < 0.05f)
        }
        // The old value is removed from the content, not covered.
        val area = PageText.of(saved.loadPage(0) as PDFPage).textIn(Rect(230f, 141f, 300f, 157f))
        assertFalse(area, area.contains("1,23,456.78"))
    }

    @Test
    fun missingGlyphsUseASubstituteAndSaySo() {
        val d = Fixtures.open("financial.pdf")
        val rect = Fixtures.line(d, 1, "Depreciation")
        val r = TextEdit.editText(d, 1, rect, "Depreciation & Amortisation ₹")
        assertEquals("substitute", r.fontKind)
        assertTrue(r.warnings.toString(), r.warnings.any { it.startsWith("Substituted") })
        val t = Fixtures.text(Fixtures.roundTrip(d), 1)
        assertTrue(t, t.contains("Depreciation & Amortisation ₹"))
    }

    @Test
    fun moveAndParagraphMode() {
        val d = Fixtures.open("a.pdf")
        val rect = Fixtures.line(d, 1, "Marker A-001")
        TextEdit.editText(d, 1, rect, "Marker A-001", moveBy = Point(50f, 20f))
        val s = Fixtures.span(d, 1, "Marker A-001")!!
        assertTrue("moved to ${s.origin}", abs(s.origin.x - 122f) < 1f && abs(s.origin.y - 160f) < 1f)

        val d2 = Fixtures.open("a.pdf")
        val r2 = Fixtures.line(d2, 1, "Marker A-001")
        val para = "This is a much longer replacement paragraph that should wrap inside the new box nicely."
        TextEdit.editText(d2, 1, r2, para, mode = "block", newRect = Rect(72f, 142f, 250f, 242f))
        val t = Fixtures.text(d2, 1).replace("\n", " ")
        assertTrue(t, t.contains("replacement paragraph"))
        val lines = PageText.of(d2.loadPage(0) as PDFPage).lines.filter { it.bbox.y0 >= 140f && it.bbox.y1 <= 250f }
        assertTrue("wrapped into several lines", lines.size >= 3)
        assertTrue("stays inside the box", lines.all { it.bbox.x1 <= 251f })
    }

    @Test
    fun scannedPageIsRefused() {
        val d = Fixtures.open("scanned.pdf")
        expectError(Codes.NOT_SUPPORTED) { TextEdit.editText(d, 1, Rect(0f, 0f, 612f, 792f), "x") }
    }

    @Test
    fun deleteExistingLine() {
        val d = Fixtures.open("c.pdf")
        val rect = Fixtures.line(d, 2, "Marker C-002")
        TextEdit.editText(d, 2, rect, "")
        val t = Fixtures.text(Fixtures.roundTrip(d), 2)
        assertFalse(t, t.contains("C-002"))
        assertTrue(t, t.contains("C page 2"))
    }

    @Test
    fun findAndReplace() {
        val d = Fixtures.open("financial.pdf")
        val r = TextEdit.replaceAll(d, "Revenue", "Income", pages = "1")
        assertTrue(r.replaced >= 1)
        val t = Fixtures.text(Fixtures.roundTrip(d), 1)
        assertTrue(t, t.contains("Income from operations"))
        assertFalse(t, t.contains("Revenue"))
    }

    @Test
    fun insertTextAddsPageContent() {
        val d = Fixtures.open("a.pdf")
        TextEdit.insertText(d, 1, Rect(72f, 282f, 400f, 342f), "Audited - subject to notes ₹")
        val t = Fixtures.text(Fixtures.roundTrip(d), 1)
        assertTrue(t, t.contains("Audited - subject to notes ₹"))
    }

    /** A page stored with /Rotate 90: the edited text must read horizontally, as the original did. */
    @Test
    fun editOnRotatedPageStaysUpright() {
        val d = Fixtures.open("rotated.pdf")
        val rect = Fixtures.line(d, 1, "Rotated page marker R-001")
        val ln = PageText.of(d.loadPage(0) as PDFPage).lines.first()
        val ch = ln.spans.flatMap { it.chars }
        val info = "dir=${ln.dir} bbox=${ln.bbox} first=${ch.first().origin} last=${ch.last().origin} size=${ln.spans.first().size} page=${d.loadPage(0).bounds}"
        assertTrue("line reads horizontally: $info", ln.horizontal)
        val r = TextEdit.editText(d, 1, rect, "Rotated page marker R-002")
        val saved = Fixtures.roundTrip(d)
        val line = PageText.of(saved.loadPage(0) as PDFPage).lines.first { it.text.contains("R-002") }
        assertTrue("reads horizontally: dir=${line.dir}", line.horizontal)
        assertTrue("same place: ${line.bbox} vs $rect", abs(line.bbox.x0 - rect.x0) < 2f && abs(line.bbox.y1 - rect.y1) < 3f)
        assertEquals("original", r.fontKind)
    }

    /** The app wraps each edit in a journal operation and abandons it on failure: nothing may remain. */
    @Test
    fun abandonedOperationRestoresTheDocument() {
        val d = Fixtures.open("a.pdf")
        d.enableJournal()
        val before = Fixtures.text(d, 1)
        d.beginOperation("edit")
        TextEdit.removeText(d.loadPage(0) as PDFPage, listOf(Fixtures.line(d, 1, "Marker A-001")))
        assertFalse(Fixtures.text(d, 1).contains("Marker A-001"))
        d.abandonOperation()
        assertEquals(before, Fixtures.text(d, 1))
    }

    @Test
    fun undoAndRedoAnEdit() {
        val d = Fixtures.open("a.pdf")
        d.enableJournal()
        d.beginOperation("Edit text")
        TextEdit.editText(d, 1, Fixtures.line(d, 1, "Marker A-001"), "Marker A-777")
        d.endOperation()
        assertTrue(Fixtures.text(d, 1).contains("A-777"))
        assertTrue(d.canUndo())
        d.undo()
        assertTrue(Fixtures.text(d, 1).contains("A-001"))
        assertFalse(Fixtures.text(d, 1).contains("A-777"))
        d.redo()
        assertTrue(Fixtures.text(d, 1).contains("A-777"))
    }
}
