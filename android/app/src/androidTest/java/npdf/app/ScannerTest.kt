package npdf.app

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.artifex.mupdf.fitz.PDFPage
import kotlinx.coroutines.runBlocking
import npdf.engine.DocInfo
import npdf.engine.Pdf
import npdf.scan.Filter
import npdf.scan.Pt
import npdf.scan.Quad
import npdf.scan.TextInfo
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.hypot

/** The document scanner: pipeline on synthetic photos, the camera on the emulator's camera, saving, export. */
@RunWith(AndroidJUnit4::class)
class ScannerTest {

    @get:Rule val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val ctx get() = TestKit.ctx
    private val created = mutableListOf<Uri>()

    @Before fun clean() { ScanSession.root(ctx).deleteRecursively(); Prefs(ctx).autoCapture = true; Prefs(ctx).darkPages = false }

    @After fun tidy() { created.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }; ScanSession.root(ctx).deleteRecursively() }

    /** A page of a tax invoice, drawn in black on white. */
    private fun document(): Bitmap {
        val b = Bitmap.createBitmap(850, 1100, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 54f }
        c.drawText("TAX INVOICE", 80f, 140f, p)
        p.typeface = Typeface.SANS_SERIF; p.textSize = 34f
        c.drawText("ACME Traders Private Limited", 80f, 230f, p)
        c.drawText("Invoice No 123   Date 31/03/2026", 80f, 290f, p)
        for (i in 0 until 8) c.drawText("Item ${i + 1}     Consulting services     1,000.00", 80f, 400f + i * 60f, p)
        p.strokeWidth = 3f
        c.drawLine(80f, 900f, 770f, 900f, p)
        c.drawText("Total     8,000.00", 80f, 960f, p)
        return b
    }

    /** [doc] photographed on a dark desk, tilted, at [quad]. */
    private fun photo(doc: Bitmap, quad: Quad, w: Int = 1600, h: Int = 1200): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.rgb(70, 52, 40))
        val m = Matrix()
        val src = floatArrayOf(0f, 0f, doc.width.toFloat(), 0f, doc.width.toFloat(), doc.height.toFloat(), 0f, doc.height.toFloat())
        val dst = quad.corners.flatMap { listOf(it.x, it.y) }.toFloatArray()
        m.setPolyToPoly(src, 0, dst, 0, 4)
        c.drawBitmap(doc, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private val truth = Quad(Pt(330f, 140f), Pt(1240f, 190f), Pt(1190f, 1090f), Pt(270f, 1040f))

    private fun mediaPath(uri: Uri): String? = ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    private fun copyToFile(uri: Uri, ext: String): File {
        val f = File(ctx.cacheDir, "check_${System.nanoTime()}.$ext")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    @Test
    fun scannerFindsCorrectsRecognisesAndSavesAPage() {
        val session = ScanSession.create(ctx)
        val t0 = SystemClock.elapsedRealtime()
        val part = ScanProcessor.addPhoto(session, photo(document(), truth), ScanKind.DOCUMENT)
        val tDetect = SystemClock.elapsedRealtime() - t0
        assertNotNull("outline found", part.quad)
        val err = part.quad!!.maxCornerDistance(truth) / hypot(1600f, 1200f)
        Log.i("NPDF-SCAN", "phone pipeline: outline found in $tDetect ms (corner error ${"%.2f".format(err * 100)} % of the diagonal)")
        assertTrue("corner error $err", err < 0.012f)
        val page = ScanProcessor.newPage(ScanKind.DOCUMENT, part, Filter.AUTO)
        session.pages.add(page)
        val t1 = SystemClock.elapsedRealtime()
        val out = ScanProcessor.cached(session, page)
        Log.i("NPDF-SCAN", "phone pipeline: page corrected and filtered in ${SystemClock.elapsedRealtime() - t1} ms (${out.width} x ${out.height})")
        assertEquals("page proportions kept", 850f / 1100f, out.width.toFloat() / out.height, 0.03f)
        // Smart name from the page's own text.
        val lines = ScanStore.firstPageLines(ctx, session, listOf("eng"))
        val names = TextInfo.nameSuggestions(lines, "Scan")
        Log.i("NPDF-SCAN", "name suggestions: $names")
        assertEquals("Tax Invoice 2026-03-31", names.first())
        // Save as a searchable PDF in Documents/N PDF.
        val t2 = SystemClock.elapsedRealtime()
        val uri = ScanStore.savePdf(ctx, session, "NPDF test ${System.nanoTime()}", ScanStore.Options(ocr = true, languages = listOf("eng"), quality = 1)) { _, _ -> }
        created.add(uri)
        Log.i("NPDF-SCAN", "phone pipeline: saved with OCR in ${SystemClock.elapsedRealtime() - t2} ms")
        assertEquals("Documents/N PDF/", mediaPath(uri))
        val d = Pdf.open(copyToFile(uri, "pdf").absolutePath)
        assertEquals(1, d.countPages())
        val text = DocInfo.pageText(d, 1)
        assertTrue("searchable: $text", text.contains("INVOICE") && text.contains("ACME"))
    }

    @Test
    fun bookIdCardAndEditsRender() {
        val session = ScanSession.create(ctx)
        val doc = document()
        val part = ScanProcessor.addPhoto(session, photo(doc, truth), ScanKind.BOOK)
        val left = ScanProcessor.newPage(ScanKind.BOOK, part, Filter.ORIGINAL, half = 1)
        val right = ScanProcessor.newPage(ScanKind.BOOK, Part(part.file, part.quad, part.w, part.h), Filter.ORIGINAL, half = 2)
        val l = ScanProcessor.render(session, left); val r = ScanProcessor.render(session, right)
        assertTrue("two halves", abs(l.width - r.width) <= 2 && l.height == r.height)
        // ID card: two sides on one A4 page at true size.
        val card = ScanPage("id", ScanKind.IDCARD, mutableListOf(part, Part(part.file, part.quad, part.w, part.h)), filter = Filter.ORIGINAL)
        val a4 = ScanProcessor.render(session, card)
        assertEquals(2480, a4.width); assertEquals(3508, a4.height)
        // Clean-up and markup change the page where they are drawn.
        val page = ScanProcessor.newPage(ScanKind.DOCUMENT, part, Filter.ORIGINAL)
        val before = ScanProcessor.render(session, page)
        page.edits.add(Edit.Ink(listOf(Pt(0.1f, 0.95f), Pt(0.9f, 0.95f)), Color.RED, 0.01f))
        page.edits.add(Edit.Erase(listOf(Pt(0.3f, 0.13f)), 0.05f, Color.WHITE))
        val after = ScanProcessor.render(session, page)
        val y = (0.95f * after.height).toInt()
        assertEquals("red ink drawn", Color.RED, after.getPixel(after.width / 2, y) or 0xFF000000.toInt())
        val ex = (0.3f * after.width).toInt(); val ey = (0.13f * after.height).toInt()
        assertTrue("erased to white", Color.red(after.getPixel(ex, ey)) > 240 && before.getPixel(ex, ey) != after.getPixel(ex, ey) || Color.red(before.getPixel(ex, ey)) > 240)
        // The scan survives the app being closed.
        session.pages.addAll(listOf(left, right, page)); session.name = "Reloaded"; session.save()
        val again = ScanSession.load(session.dir)!!
        assertEquals(3, again.pages.size)
        assertEquals("Reloaded", again.name)
        assertEquals(2, again.pages[1].half)
        assertEquals(2, again.pages[2].edits.size)
        assertTrue(again.pages[0].parts[0].quad!!.maxCornerDistance(part.quad!!) < 0.01f)
    }

    @Test
    fun cameraCapturesAPageAndOpensTheReview() {
        Prefs(ctx).autoCapture = false
        val cam = TestKit.start(ScanActivity::class.java)
        TestKit.waitFor("camera ready", 30_000) { TestKit.main { cam.cameraReady } }
        TestKit.tap(cam, "Capture")
        TestKit.waitFor("page captured", 30_000) { TestKit.main { cam.pageCount } == 1 }
        TestKit.tap(cam, "Done")
        TestKit.waitFor("camera closed", 30_000) { TestKit.main { cam.isDestroyed } }
        TestKit.waitFor("review open") { runCatching { onView(allOf(withText("Save"), isDisplayed())).check(matches(isDisplayed())); true }.getOrDefault(false) }
        TestKit.finish(TestKit.resumed(ScanReviewActivity::class.java))
    }

    @Test
    fun importedPhotoIsSavedFromTheReviewScreen() {
        val f = File(ctx.cacheDir, "photo_${System.nanoTime()}.jpg")
        f.outputStream().use { photo(document(), truth).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val i = Intent(ctx, ScanReviewActivity::class.java).putParcelableArrayListExtra(ScanReviewActivity.EXTRA_IMPORT, arrayListOf(Uri.fromFile(f)))
        val sc = ActivityScenario.launch<ScanReviewActivity>(i)
        TestKit.waitFor("photo imported", 30_000) { var n = 0; sc.onActivity { n = it.pageCount }; n == 1 }
        val started = System.currentTimeMillis() / 1000 - 5
        onView(allOf(withText("Save"), isDisplayed())).perform(click())
        onView(withText("Save")).inRoot(isDialog()).perform(click())
        TestKit.waitFor("saved", 60_000) { runCatching { onView(withText("Done")).inRoot(isDialog()).check(matches(isDisplayed())); true }.getOrDefault(false) }
        // The new PDF is in Documents/N PDF.
        val found = ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?", arrayOf("Documents/N PDF/", started.toString()), null)?.use { c ->
            val out = ArrayList<Pair<Uri, String>>()
            while (c.moveToNext()) out.add(ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), c.getLong(0)) to c.getString(1))
            out
        } ?: emptyList()
        created.addAll(found.map { it.first })
        Log.i("NPDF-SCAN", "saved from the review screen: ${found.map { it.second }}")
        assertTrue("a scan PDF was saved: $found", found.any { it.second.startsWith("Scan ") && it.second.endsWith(".pdf") })
        onView(withText("Done")).inRoot(isDialog()).perform(click())
        sc.close()
    }

    @Test
    fun readerExportsWordToDocuments() {
        val sc = TestKit.openReader(TestKit.asset("financial.pdf"))
        TestKit.waitOpen(sc)
        openActionBarOverflowOrOptionsMenu(TestKit.ctx)
        onView(withText("Export to Word")).perform(click())
        TestKit.waitFor("exported", 60_000) { runCatching { onView(withText("Done")).inRoot(isDialog()).check(matches(isDisplayed())); true }.getOrDefault(false) }
        val docx = ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("Documents/N PDF/", "%.docx"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), c.getLong(0)) else null
        }
        assertNotNull("docx saved", docx)
        created.add(docx!!)
        var xml = ""
        ZipInputStream(ctx.contentResolver.openInputStream(docx)!!).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == "word/document.xml") xml = String(z.readBytes()) }
        }
        assertTrue("Word text", xml.contains("Revenue from operations") && xml.contains("<w:tbl>"))
        onView(withText("Done")).inRoot(isDialog()).perform(click())
        sc.close()
    }

    @Test
    fun homeTabsShowToolsAndSettings() {
        val sc = ActivityScenario.launch(MainActivity::class.java)
        onView(withContentDescription("Tools")).perform(click())
        onView(allOf(withText("Export to Word"), isDisplayed())).check(matches(isDisplayed()))
        onView(withContentDescription("Settings")).perform(click())
        onView(allOf(withText("Auto-capture"), isDisplayed())).check(matches(isDisplayed()))
        onView(withContentDescription("Files")).perform(click())
        onView(allOf(withText("Scan"), isDisplayed())).check(matches(isDisplayed()))
        sc.close()
    }

    @Test
    fun darkPagesAreAReadingOption() {
        val sc = TestKit.openReader(TestKit.asset("a.pdf"))
        TestKit.waitOpen(sc)
        openActionBarOverflowOrOptionsMenu(TestKit.ctx)
        onView(withText("Dark pages")).perform(click())
        TestKit.waitFor("dark pages on") { TestKit.on(sc) { it.b.pdf.darkPages } }
        assertTrue(Prefs(ctx).darkPages)
        openActionBarOverflowOrOptionsMenu(TestKit.ctx)
        onView(withText("Dark pages")).perform(click())
        TestKit.waitFor("dark pages off") { !TestKit.on(sc) { it.b.pdf.darkPages } }
        assertFalse(Prefs(ctx).darkPages)
        sc.close()
    }

    @Suppress("unused")
    private fun unused(p: PDFPage) = runBlocking { p }
}
