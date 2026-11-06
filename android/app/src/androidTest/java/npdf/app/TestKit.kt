package npdf.app

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Helpers for the app's instrumented tests. */
object TestKit {
    val instr get() = InstrumentationRegistry.getInstrumentation()
    val ctx get() = instr.targetContext

    /** A fresh copy of a synthetic test document in the app's cache. */
    fun asset(name: String): File {
        val dir = File(ctx.cacheDir, "testdocs").apply { mkdirs() }
        val f = File(dir, "${System.nanoTime()}_$name")
        instr.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    fun openReader(file: File): ActivityScenario<ReaderActivity> =
        ActivityScenario.launch(Intent(ctx, ReaderActivity::class.java).setData(Uri.fromFile(file)))

    /** Polls [cond] until it is true or [ms] pass. */
    fun waitFor(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (try { cond() } catch (e: Throwable) { false }) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    fun <T> on(sc: ActivityScenario<ReaderActivity>, f: (ReaderActivity) -> T): T {
        var r: T? = null
        sc.onActivity { r = f(it) }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    /** Waits until the document is open and its first page is drawn. */
    fun waitOpen(sc: ActivityScenario<ReaderActivity>): DocSession {
        waitFor("document opened and first page drawn", 30_000) { on(sc) { it.session != null && it.lastOpenToFirstPageMs >= 0 } }
        return on(sc) { it.session!! }
    }

    /** Taps page point (x, y) of [page] in the PdfView. */
    fun tapPage(page: Int, x: Float, y: Float): ViewAction = GeneralClickAction(Tap.SINGLE, CoordinatesProvider { view: View ->
        val v = view as PdfView
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        floatArrayOf(loc[0] + v.pageToViewX(page, x), loc[1] + v.pageToViewY(page, y))
    }, Press.FINGER, 0, 0)

    fun <T> engine(s: DocSession, f: (com.artifex.mupdf.fitz.PDFDocument) -> T): T = kotlinx.coroutines.runBlocking { s.read(f) }
}
