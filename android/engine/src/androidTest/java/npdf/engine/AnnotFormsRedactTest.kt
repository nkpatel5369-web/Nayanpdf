package npdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnnotFormsRedactTest {

    @Test
    fun commentsAddEditMoveDeleteAndFlatten() {
        val d = Fixtures.open("a.pdf")
        val hit = DocInfo.searchPage(d, 1, "A page 1").first()
        val red = 0xFFFF0000.toInt()
        Annotations.addMarkup(d, 1, PDFAnnotation.TYPE_HIGHLIGHT, hit.quads.toList(), 0xFFFFEB3B.toInt(), "Tester")
        val note = Annotations.addNote(d, 1, Point(400f, 100f), "Check this figure", author = "Tester")
        Annotations.addTextBox(d, 1, Rect(72f, 300f, 300f, 340f), "Reviewed by audit", 12f, red)
        Annotations.addInk(d, 1, listOf(listOf(Point(80f, 400f), Point(120f, 420f), Point(160f, 400f))), red)
        Annotations.addShape(d, 1, PDFAnnotation.TYPE_SQUARE, Rect(300f, 400f, 380f, 450f), red)
        Annotations.addLine(d, 1, Point(72f, 500f), Point(200f, 520f), red, arrow = true)
        val list = Annotations.list(d, 1)
        assertEquals(listOf("Highlight", "Note", "Text box", "Drawing", "Rectangle", "Line"), list.map { it.typeName })
        Annotations.setContents(d, 1, note, "Updated note")
        Annotations.move(d, 1, note, 10f, 10f)
        val saved = Fixtures.roundTrip(d)
        val after = Annotations.list(saved, 1)
        assertEquals(6, after.size)
        val n = after.first { it.typeName == "Note" }
        assertEquals("Updated note", n.contents)
        assertEquals("Tester", n.author)
        assertTrue("moved: ${n.rect}", n.rect.x0 >= 409f && n.rect.y0 >= 109f)
        Annotations.delete(saved, 1, n.id)
        assertEquals(5, Annotations.list(saved, 1).size)
        Annotations.flatten(saved)
        val flat = Fixtures.roundTrip(saved)
        assertTrue(Annotations.list(flat, 1).isEmpty())
        val t = Fixtures.text(flat, 1)
        assertTrue("flattened text box is page text: $t", t.contains("Reviewed by audit"))
    }

    @Test
    fun signatureImageIsAStampNotACertificate() {
        val d = Fixtures.open("a.pdf")
        Annotations.addSignatureImage(d, 1, Rect(350f, 650f, 550f, 750f), Fixtures.bytes("logo.png"), "Tester")
        val saved = Fixtures.roundTrip(d)
        val s = Annotations.list(saved, 1).single()
        assertEquals("Stamp", s.typeName)
        assertEquals("Signature image", s.contents)
        assertFalse(TextEdit.isSigned(saved))
    }

    @Test
    fun fillFormAndFlatten() {
        val d = Fixtures.open("form.pdf")
        val fields = Forms.list(d)
        assertEquals(listOf("client_name", "gst_registered", "entity_type", "filing", "filing"), fields.map { it.name })
        assertEquals(listOf(Forms.Kind.TEXT, Forms.Kind.CHECKBOX, Forms.Kind.COMBO, Forms.Kind.RADIO, Forms.Kind.RADIO), fields.map { it.kind })
        assertEquals(listOf("Individual", "Company", "LLP", "Partnership"), fields[2].options)
        Forms.setText(d, 1, fields[0].id, "Acme Traders Pvt Ltd")
        Forms.setChecked(d, 1, fields[1].id, true)
        Forms.setChoice(d, 1, fields[2].id, "Company")
        Forms.setChecked(d, 1, fields[4].id, true)
        val saved = Fixtures.roundTrip(d)
        val f2 = Forms.list(saved)
        assertEquals("Acme Traders Pvt Ltd", f2[0].value)
        assertTrue(f2[1].value != "Off" && f2[1].value.isNotEmpty())
        assertEquals("Company", f2[2].value)
        val radios = f2.filter { it.kind == Forms.Kind.RADIO }
        assertEquals("exactly one radio on: ${radios.map { it.value }}", 1, (saved.loadPage(0) as PDFPage).widgets.count { it.isRadioButton && Forms.isOn(it) })
        Forms.flatten(saved)
        val flat = Fixtures.roundTrip(saved)
        assertTrue(Forms.list(flat).isEmpty())
        val t = Fixtures.text(flat, 1)
        assertTrue("values are now page text: $t", t.contains("Acme Traders Pvt Ltd") && t.contains("Company"))
    }

    @Test
    fun redactPatternsRemovesTextAndVerifies() {
        val d = Fixtures.open("financial.pdf")
        val matches = Redaction.find(d, emptyList(), listOf("pan", "gstin"))
        val texts = matches.map { it.text }
        assertTrue(texts.toString(), texts.contains("ABCDE1234F") && texts.contains("27ABCDE1234F1Z5"))
        for (m in matches) Redaction.mark(d, m.page, quads = m.quads)
        val areas = Redaction.marks(d)[1].orEmpty()
        val report = try {
            Redaction.apply(d, secrets = texts.distinct())
        } catch (e: PdfError) {
            // Show where the remaining characters are relative to the marked areas.
            val t = PageText.of(d.loadPage(0) as PDFPage)
            val line = t.lines.firstOrNull { it.text.contains("Confidential") }
            val chars = line?.spans?.flatMap { it.chars }?.joinToString(" ") { c -> "${String(Character.toChars(c.c))}@${c.bbox.x0.toInt()}-${c.bbox.x1.toInt()}" }
            throw AssertionError("${e.message}\nareas: ${areas.joinToString { "${it.x0.toInt()}-${it.x1.toInt()},${it.y0.toInt()}-${it.y1.toInt()}" }}\nline: $chars")
        }
        assertTrue(report.passed)
        val saved = Fixtures.roundTrip(d)
        for (i in 1..saved.countPages()) {
            val t = Fixtures.text(saved, i)
            assertFalse("page $i still has the PAN", t.contains("ABCDE1234F"))
        }
        assertTrue("neighbouring text kept", Fixtures.text(saved, 1).contains("Confidential"))
        assertTrue("marks consumed", Redaction.marks(saved).isEmpty())
    }

    /** Grey levels of the scan image itself (not the rendered page) on a grid inside [area]. */
    private fun scanSamples(doc: com.artifex.mupdf.fitz.PDFDocument, area: Rect): List<Int> {
        val page = doc.loadPage(0) as PDFPage
        val placement = ContentStream.images(Pdf.pageContent(page), page.getObject().getInheritable("Resources")).second.single()
        val pix = doc.loadImage(placement.obj).toPixmap()
        val pb = page.bounds
        val out = mutableListOf<Int>()
        for (gx in 0 until 12) for (gy in 0 until 6) {
            val x = area.x0 + (area.x1 - area.x0) * (gx + 0.5f) / 12
            val y = area.y0 + (area.y1 - area.y0) * (gy + 0.5f) / 6
            val px = (x / (pb.x1 - pb.x0) * pix.width).toInt()
            val py = (y / (pb.y1 - pb.y0) * pix.height).toInt()
            out.add(pix.samples[py * pix.stride + px * pix.numberOfComponents].toInt() and 0xFF)
        }
        return out
    }

    @Test
    fun redactAreaOfScannedPageRemovesPixels() {
        val d = Fixtures.open("scanned.pdf")
        val area = Rect(90f, 128f, 300f, 160f)
        assertTrue("the area holds text before", scanSamples(d, area).toSet().size > 1)
        Redaction.mark(d, 1, rect = area)
        val report = Redaction.apply(d)
        assertEquals(1, report.checkedAreas)
        assertEquals(1, report.darkAreas)
        val after = scanSamples(Fixtures.roundTrip(d), area)
        assertEquals("pixels under the mark are replaced: $after", 1, after.toSet().size)
    }

    @Test
    fun customTermsAreFoundCaseInsensitively() {
        val d = Fixtures.open("financial.pdf")
        val m = Redaction.find(d, listOf("acme"), emptyList(), matchCase = false)
        assertTrue(m.isNotEmpty())
        assertTrue(m.all { it.text.equals("acme", ignoreCase = true) })
    }

    @Test
    fun redactionOfRestrictedDocumentIsRefused() {
        val d = Fixtures.open("restricted.pdf")
        try {
            Redaction.mark(d, 1, rect = Rect(0f, 0f, 100f, 100f))
            fail("expected a refusal")
        } catch (e: PdfError) {
            assertEquals(Codes.RESTRICTED, e.code)
        }
    }
}
