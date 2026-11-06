package npdf.app

import android.net.Uri
import android.util.Log
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import kotlinx.coroutines.runBlocking
import npdf.engine.Annotations
import npdf.engine.DocInfo
import npdf.engine.Forms
import npdf.engine.PageText
import npdf.engine.Pdf
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The reader driven through its own screens, as a user would. Runs on the release build. */
@RunWith(AndroidJUnit4::class)
class ReaderUiTest {

    private fun text(s: DocSession, page: Int) = TestKit.engine(s) { DocInfo.pageText(it, page) }

    @Test
    fun largeDocumentOpensFast() {
        val sc = TestKit.openReader(TestKit.asset("large.pdf"))
        val s = TestKit.waitOpen(sc)
        val ms = TestKit.on(sc) { it.lastOpenToFirstPageMs }
        Log.i("NPDF-PERF", "UI: large.pdf (300 pages) open to first page on screen: $ms ms")
        assertEquals(300, s.sizes.size)
        assertTrue("first page took $ms ms", ms < 2000)
        // Jump to the last page: it is drawn without loading the pages in between.
        TestKit.on(sc) { it.b.pdf.goToPage(299) }
        TestKit.waitFor("page 300 indicator") { TestKit.on(sc) { it.b.pageChip.text.toString() } == "300 / 300" }
        sc.close()
    }

    @Test
    fun searchFindsAndCountsMatches() {
        val sc = TestKit.openReader(TestKit.asset("financial.pdf"))
        TestKit.waitOpen(sc)
        TestKit.on(sc) { it.openSearch(); it.runSearch("Revenue") }
        TestKit.waitFor("search results") { TestKit.on(sc) { it.b.searchCount.text.toString() }.matches(Regex("\\d+/[1-9]\\d*")) }
        sc.close()
    }

    @Test
    fun addNoteThroughTheCommentTools() {
        val sc = TestKit.openReader(TestKit.asset("a.pdf"))
        val s = TestKit.waitOpen(sc)
        onView(withId(R.id.mode_comment)).perform(click())
        onView(withText("Note")).perform(click())
        onView(withId(R.id.pdf)).perform(TestKit.tapPage(0, 300f, 300f))
        onView(withHint("Your comment")).inRoot(isDialog()).perform(replaceText("Checked by the UI test"))
        onView(withText("OK")).inRoot(isDialog()).perform(click())
        TestKit.waitFor("note added") { TestKit.engine(s) { Annotations.list(it, 1) }.any { it.contents == "Checked by the UI test" } }
        // Undo from the toolbar removes it again.
        onView(withId(R.id.action_undo)).perform(click())
        TestKit.waitFor("note undone") { TestKit.engine(s) { Annotations.list(it, 1) }.isEmpty() }
        sc.close()
    }

    @Test
    fun editExistingTextThroughTheEditTools() {
        val sc = TestKit.openReader(TestKit.asset("a.pdf"))
        val s = TestKit.waitOpen(sc)
        val line = TestKit.engine(s) { PageText.of(it.loadPage(0) as PDFPage).lines.first { l -> l.text.trim() == "Marker A-001" }.bbox }
        onView(withId(R.id.mode_edit)).perform(click())
        onView(withText("Edit text")).perform(click())
        onView(withId(R.id.pdf)).perform(TestKit.tapPage(0, (line.x0 + line.x1) / 2, (line.y0 + line.y1) / 2))
        onView(withHint("Text")).inRoot(isDialog()).perform(replaceText("Marker A-999"))
        onView(withText("Apply")).inRoot(isDialog()).perform(click())
        TestKit.waitFor("text edited") { text(s, 1).contains("Marker A-999") }
        assertFalse(text(s, 1).contains("Marker A-001"))
        assertTrue(TestKit.on(sc) { it.session!!.dirty })
        // Saving writes the edit to the file.
        val out = File(TestKit.ctx.cacheDir, "saved_${System.nanoTime()}.pdf").apply { createNewFile() }
        runBlocking { s.saveTo(Uri.fromFile(out), out.name) }
        val saved = Pdf.open(out.absolutePath)
        assertTrue(DocInfo.pageText(saved, 1).contains("Marker A-999"))
        assertFalse(s.dirty)
        sc.close()
    }

    @Test
    fun fillFormFieldsByTapping() {
        val sc = TestKit.openReader(TestKit.asset("form.pdf"))
        val s = TestKit.waitOpen(sc)
        val fields = TestKit.engine(s) { Forms.list(it) }
        val name = fields.first { it.name == "client_name" }
        onView(withId(R.id.pdf)).perform(TestKit.tapPage(0, (name.rect.x0 + name.rect.x1) / 2, (name.rect.y0 + name.rect.y1) / 2))
        onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).perform(replaceText("Acme Traders"))
        onView(withText("OK")).inRoot(isDialog()).perform(click())
        TestKit.waitFor("text field filled") { TestKit.engine(s) { Forms.list(it) }.first { it.name == "client_name" }.value == "Acme Traders" }
        val box = fields.first { it.name == "gst_registered" }
        onView(withId(R.id.pdf)).perform(TestKit.tapPage(0, (box.rect.x0 + box.rect.x1) / 2, (box.rect.y0 + box.rect.y1) / 2))
        TestKit.waitFor("check box ticked") { TestKit.engine(s) { d -> (d.loadPage(0) as PDFPage).widgets.first { it.name == "gst_registered" }.let { Forms.isOn(it) } } }
        sc.close()
    }

    @Test
    fun drawAndPlaceASignature() {
        val sc = TestKit.openReader(TestKit.asset("a.pdf"))
        val s = TestKit.waitOpen(sc)
        onView(withId(R.id.mode_sign)).perform(click())
        onView(withText("Signature")).perform(click())
        onView(isAssignableFrom(SignaturePad::class.java)).inRoot(isDialog()).perform(swipeRight())
        onView(withText("Place")).inRoot(isDialog()).perform(click())
        onView(withId(R.id.pdf)).perform(TestKit.tapPage(0, 350f, 650f))
        TestKit.waitFor("signature placed") { TestKit.engine(s) { Annotations.list(it, 1) }.any { it.type == PDFAnnotation.TYPE_STAMP } }
        sc.close()
    }

    @Test
    fun deleteAPageInTheOrganiser() {
        val sc = TestKit.openReader(TestKit.asset("c.pdf"))
        val s = TestKit.waitOpen(sc)
        assertEquals(4, s.sizes.size)
        val org = ActivityScenario.launch(OrganizeActivity::class.java)
        onView(withText("2")).perform(click())
        onView(withText("Delete")).perform(click())
        onView(withText("Delete")).inRoot(isDialog()).perform(click())
        TestKit.waitFor("page deleted") { s.sizes.size == 3 }
        assertFalse(text(s, 2).contains("C page 2"))
        org.close()
        sc.close()
    }

    @Test
    fun readOutLoudSpeaksOrSaysWhyNot() {
        val sc = TestKit.openReader(TestKit.asset("a.pdf"))
        TestKit.waitOpen(sc)
        openActionBarOverflowOrOptionsMenu(TestKit.ctx)
        onView(withText("Read Out Loud")).perform(click())
        // Either the reading bar appears, or a dialog explains that no voice is installed.
        TestKit.waitFor("read aloud outcome") {
            TestKit.on(sc) { it.b.ttsBar.visibility == android.view.View.VISIBLE } ||
                runCatching { onView(withText("Read Out Loud")).inRoot(isDialog()).check(matches(isDisplayed())); true }.getOrDefault(false)
        }
        sc.close()
    }

    @Test
    fun homeScreenShowsOpenAndTools() {
        val sc = ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.openBtn)).check(matches(isDisplayed()))
        onView(withText("Photos to PDF")).check(matches(isDisplayed()))
        sc.close()
    }

    @Test
    fun everyIconLoads() {
        for (f in R.drawable::class.java.fields) {
            val id = f.getInt(null)
            assertNotNull("drawable ${f.name}", ContextCompat.getDrawable(TestKit.ctx, id))
        }
    }

    @Test
    fun ocrRecognisesTheScannedPage() {
        val f = TestKit.asset("scanned.pdf")
        val s = runBlocking { DocSession.openFile(TestKit.ctx, f, null, "scanned.pdf", null) }
        try {
            val r = Ocr(TestKit.ctx).recognize(s, 0, listOf("eng"))
            val words = r.words.joinToString(" ") { it.text }
            Log.i("NPDF-OCR", "${r.words.size} words, mean confidence ${r.meanConfidence}: ${words.take(200)}")
            assertTrue(words, words.contains("Revenue") && words.contains("Particulars"))
            assertTrue("confidence ${r.meanConfidence}", r.meanConfidence >= 70)
        } finally {
            s.close()
        }
    }
}
