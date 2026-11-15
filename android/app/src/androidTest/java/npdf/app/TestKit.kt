package npdf.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.hamcrest.Matcher
import org.hamcrest.Matchers.hasToString
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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

    // The camera screen keeps the main thread busy with preview frames, so Espresso and
    // ActivityScenario.onActivity (both wait for the main thread to go idle) are not used on it.
    // These run work on the main thread directly instead.

    /** Runs [f] on the main thread and returns its result, without waiting for the main thread to go idle. */
    fun <T> main(f: () -> T): T {
        var r: Result<T>? = null
        instr.runOnMainSync { r = runCatching(f) }
        return r!!.getOrThrow()
    }

    /** Starts [cls] without ActivityScenario, whose launch also waits for the main thread to go idle. */
    fun <T : Activity> start(cls: Class<T>): T {
        ctx.startActivity(Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return resumed(cls)
    }

    /** The resumed activity of type [cls]. */
    fun <T : Activity> resumed(cls: Class<T>, ms: Long = 30_000): T {
        var found: T? = null
        waitFor("${cls.simpleName} on screen", ms) {
            found = main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { cls.isInstance(it) }?.let { cls.cast(it) } }
            found != null
        }
        return found!!
    }

    /** Taps the view with content description [label] in [a], as a user would. */
    fun tap(a: Activity, label: String) = main {
        val views = ArrayList<View>()
        a.window.decorView.findViewsWithText(views, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        val v = views.firstOrNull { it.isShown && it.contentDescription?.toString() == label } ?: throw AssertionError("No \"$label\" on screen")
        v.performClick()
    }

    /** Finishes [a] and waits until it is gone, so the scenario can then be closed on an idle main thread. */
    fun finish(a: Activity) {
        main { a.finish() }
        waitFor("${a.javaClass.simpleName} closed", 30_000) { main { a.isDestroyed } }
    }

    /**
     * Picks a command from the reader's overflow menu, through submenus: menu("Export", "Word (.docx)").
     * Items are found in the menu's list whether or not they are scrolled into view.
     */
    fun menu(vararg path: String) {
        openActionBarOverflowOrOptionsMenu(ctx)
        for (label in path) onData(hasToString(label)).inRoot(isPlatformPopup()).perform(click())
    }

    /** Scrolls a view inside any scrolling container (including NestedScrollView) into view. */
    fun reveal(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
        override fun getDescription() = "scroll into view"
        override fun perform(uiController: UiController, view: View) {
            view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), true)
            uiController.loopMainThreadUntilIdle()
        }
    }

    /**
     * The corners of a [pw] x [ph] page as a phone camera sees it (pinhole camera, principal point at the
     * picture's centre, focal length 0.6 x the diagonal), tilted [tiltX] degrees about its horizontal axis
     * (top away), [tiltY] about its vertical axis and turned [roll] degrees, filling about 62 % of the height.
     */
    fun cameraQuad(pw: Double, ph: Double, tiltX: Double, tiltY: Double, roll: Double, w: Int = 1600, h: Int = 1200): npdf.scan.Quad {
        val f = 0.6 * Math.hypot(w.toDouble(), h.toDouble())
        val z0 = f * ph / (0.62 * h)
        val a = Math.toRadians(tiltX); val b = Math.toRadians(tiltY); val c = Math.toRadians(roll)
        fun p(x: Double, y: Double): npdf.scan.Pt {
            val y1 = y * Math.cos(a); val z1 = -y * Math.sin(a)
            val x2 = x * Math.cos(b) + z1 * Math.sin(b); val z2 = -x * Math.sin(b) + z1 * Math.cos(b)
            val x3 = x2 * Math.cos(c) - y1 * Math.sin(c); val y3 = x2 * Math.sin(c) + y1 * Math.cos(c)
            val z = z0 + z2
            return npdf.scan.Pt((w / 2 + f * x3 / z).toFloat(), (h / 2 + f * y3 / z).toFloat())
        }
        return npdf.scan.Quad(p(-pw / 2, -ph / 2), p(pw / 2, -ph / 2), p(pw / 2, ph / 2), p(-pw / 2, ph / 2))
    }
}
