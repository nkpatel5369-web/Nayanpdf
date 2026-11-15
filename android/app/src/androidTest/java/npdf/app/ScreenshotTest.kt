package npdf.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matchers.allOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Captures the main screens, light and dark, for visual review (pulled from
 * /sdcard/Download/npdf-screens by CI). Asserts only that each screen opens.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instr get() = InstrumentationRegistry.getInstrumentation()

    private fun shot(name: String, idle: Boolean = true) {
        if (idle) instr.waitForIdleSync()
        SystemClock.sleep(700)
        instr.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/npdf-screens").close()
        instr.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/npdf-screens/$name.png").close()
        SystemClock.sleep(600)
    }

    private fun photoUri(): Uri {
        val doc = Bitmap.createBitmap(850, 1100, Bitmap.Config.ARGB_8888)
        val c = Canvas(doc); c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 48f }
        c.drawText("TAX INVOICE", 80f, 140f, p)
        p.textSize = 30f
        for (i in 0 until 12) c.drawText("Item ${i + 1}   Consulting services   1,000.00", 80f, 240f + i * 56f, p)
        val photo = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val pc = Canvas(photo); pc.drawColor(Color.rgb(70, 52, 40))
        val m = android.graphics.Matrix()
        val q = TestKit.cameraQuad(850.0, 1100.0, 18.0, 10.0, 4.0)
        m.setPolyToPoly(floatArrayOf(0f, 0f, 850f, 0f, 850f, 1100f, 0f, 1100f), 0, q.corners.flatMap { listOf(it.x, it.y) }.toFloatArray(), 0, 4)
        pc.drawBitmap(doc, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val f = File(TestKit.ctx.cacheDir, "screens_photo.jpg")
        f.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Uri.fromFile(f)
    }

    private fun night(mode: Int) {
        instr.runOnMainSync { AppCompatDelegate.setDefaultNightMode(mode) }
        Prefs(TestKit.ctx).nightMode = mode
    }

    private fun capture(suffix: String) {
        ScanSession.root(TestKit.ctx).deleteRecursively()
        // Home: Files with a recent file, Tools, Settings.
        val reader0 = TestKit.openReader(TestKit.asset("financial.pdf")); TestKit.waitOpen(reader0); reader0.close()
        val home = ActivityScenario.launch(MainActivity::class.java)
        shot("01_home_$suffix")
        onView(withId(R.id.tab_tools)).perform(click()); shot("02_tools_$suffix")
        onView(withId(R.id.tab_settings)).perform(click()); shot("03_settings_$suffix")
        home.close()
        // Reader with the comment palette, the menu, an action sheet and an alert.
        val sc = TestKit.openReader(TestKit.asset("financial.pdf"))
        TestKit.waitOpen(sc)
        shot("04_reader_$suffix")
        onView(withId(R.id.mode_comment)).perform(click()); shot("05_reader_comment_$suffix")
        openActionBarOverflowOrOptionsMenu(TestKit.ctx); shot("06_reader_menu_$suffix"); pressBack()
        onView(withId(R.id.mode_edit)).perform(click())
        onView(withText("Edit text")).perform(scrollTo(), click())
        val line = TestKit.engine(TestKit.waitOpen(sc)) { npdf.engine.PageText.of(it.loadPage(0) as com.artifex.mupdf.fitz.PDFPage).lines.first { l -> l.text.contains("Revenue") }.bbox }
        onView(withId(R.id.pdf)).perform(TestKit.tapPage(0, (line.x0 + line.x1) / 2, (line.y0 + line.y1) / 2))
        shot("07_edit_text_sheet_$suffix")
        onView(withText("Cancel")).inRoot(isDialog()).perform(click())
        onView(withId(R.id.mode_sign)).perform(click())
        onView(withText("Signature")).perform(scrollTo(), click())
        shot("08_signature_$suffix")
        onView(withText("Cancel")).inRoot(isDialog()).perform(click())
        TestKit.on(sc) { Dialogs.message(it, "Bookmarks", "This PDF has no bookmarks.") }
        shot("09_alert_$suffix")
        onView(withText("OK")).inRoot(isDialog()).perform(click())
        TestKit.on(sc) { it.lifecycleScope.launch { Dialogs.choose(it, "Page size", listOf("Fit to the scan", "A4", "Letter", "Legal"), 1) } }
        shot("10_action_sheet_$suffix")
        onView(withText("Cancel")).inRoot(isDialog()).perform(click())
        sc.close()
        // Scanner: camera, review with filters, save sheet.
        Prefs(TestKit.ctx).autoCapture = true
        Prefs(TestKit.ctx).adjustAfterCapture = false
        val camAct = TestKit.start(ScanActivity::class.java)
        TestKit.waitFor("camera", 30_000) { TestKit.main { camAct.cameraReady } }
        SystemClock.sleep(1500)
        shot("11_camera_$suffix", idle = false)
        TestKit.finish(camAct)
        val rv = ActivityScenario.launch<ScanReviewActivity>(Intent(TestKit.ctx, ScanReviewActivity::class.java)
            .putParcelableArrayListExtra(ScanReviewActivity.EXTRA_IMPORT, arrayListOf(photoUri())))
        TestKit.waitFor("imported", 30_000) { var n = 0; rv.onActivity { n = it.pageCount }; n == 1 }
        SystemClock.sleep(2500)
        shot("12_review_filters_$suffix")
        onView(withText("Arrange")).perform(scrollTo(), click())
        SystemClock.sleep(1500)
        shot("13_review_arrange_$suffix")
        onView(allOf(withText("Done"), isDisplayed())).perform(click())
        onView(allOf(withText("Save PDF"), isDisplayed())).perform(click())
        SystemClock.sleep(2500)
        shot("14_save_sheet_$suffix")
        onView(withText("Cancel")).inRoot(isDialog()).perform(click())
        rv.close()
        // Organizer.
        val org0 = TestKit.openReader(TestKit.asset("c.pdf")); TestKit.waitOpen(org0)
        TestKit.on(org0) { it.startActivity(Intent(it, OrganizeActivity::class.java)) }
        onView(withText("2")).perform(click())
        shot("15_organize_$suffix")
        org0.close()
        ScanSession.root(TestKit.ctx).deleteRecursively()
    }

    @Test
    fun captureScreensLightAndDark() {
        night(AppCompatDelegate.MODE_NIGHT_NO)
        capture("light")
        night(AppCompatDelegate.MODE_NIGHT_YES)
        capture("dark")
        night(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}
