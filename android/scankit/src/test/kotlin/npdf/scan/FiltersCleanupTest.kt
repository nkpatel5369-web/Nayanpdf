package npdf.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class FiltersCleanupTest {

    /** A page with a strong shadow across it (light falls from 100 % to 55 %). */
    private fun shadowed(): Pair<Raster, Raster> {
        val doc = Synth.document(600, 800)
        val out = doc.copy()
        for (y in 0 until out.height) for (x in 0 until out.width) {
            val f = 0.55f + 0.45f * (x.toFloat() / out.width)
            val c = out[x, y]
            out[x, y] = Raster.rgb((((c shr 16) and 0xFF) * f).toInt(), (((c shr 8) and 0xFF) * f).toInt(), ((c and 0xFF) * f).toInt())
        }
        return doc to out
    }

    /** Paper pixels: where the clean original is near white. */
    private fun paperStats(clean: Raster, img: Raster): Pair<Double, Double> {
        val v = ArrayList<Float>()
        for (i in clean.px.indices) if (Raster.luma(clean.px[i]) > 240) v.add(Raster.luma(img.px[i]))
        val m = v.average(); val sd = sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        return m to sd
    }

    private fun inkMean(clean: Raster, img: Raster): Double {
        val v = ArrayList<Float>()
        for (i in clean.px.indices) if (Raster.luma(clean.px[i]) < 60) v.add(Raster.luma(img.px[i]))
        return v.average()
    }

    @Test
    fun autoColourRemovesShadowAndKeepsText() {
        val (clean, shadow) = shadowed()
        val (m0, sd0) = paperStats(clean, shadow)
        for (f in listOf(Filter.AUTO, Filter.GRAY, Filter.LIGHT_TEXT, Filter.WHITEBOARD)) {
            val out = Filters.apply(shadow, f)
            val (m, sd) = paperStats(clean, out)
            val ink = inkMean(clean, out)
            println("NPDF-SCAN filter ${f.label}: paper %.0f (sd %.1f, was %.0f sd %.1f), ink %.0f".format(m, sd, m0, sd0, ink))
            assertTrue("$f paper brightness $m", m > 235)
            assertTrue("$f paper evenness $sd", sd < 8)
            assertTrue("$f ink $ink", ink < 110)
        }
    }

    @Test
    fun blackAndWhiteIsTwoTone() {
        val (clean, shadow) = shadowed()
        val out = Filters.apply(shadow, Filter.BW)
        val mid = out.px.count { val l = Raster.luma(it); l in 60f..195f }
        assertTrue("grey pixels ${mid.toFloat() / out.px.size}", mid < out.px.size * 0.03)
        val (m, _) = paperStats(clean, out)
        assertTrue(m > 245)
        assertTrue(inkMean(clean, out) < 60)
    }

    @Test
    fun originalIsUnchangedAndAdjustWorks() {
        val (_, shadow) = shadowed()
        val o = Filters.apply(shadow, Filter.ORIGINAL)
        assertTrue(o.px.contentEquals(shadow.px))
        val brighter = Filters.apply(shadow, Filter.ORIGINAL, brightness = 30f)
        assertTrue(brighter.gray().v.average() > shadow.gray().v.average() + 20)
        val flat = Filters.apply(shadow, Filter.ORIGINAL, contrast = 0.5f)
        val sd = { r: Raster -> val v = r.gray().v; val m = v.average(); sqrt(v.sumOf { (it - m) * (it - m) } / v.size) }
        assertTrue(sd(flat) < sd(shadow) * 0.6)
    }

    @Test
    fun aDarkPhotoOnThePageIsNotWashedOut() {
        val page = Raster.filled(600, 800, Raster.rgb(250, 250, 248))
        for (y in 200 until 500) for (x in 100 until 500) page[x, y] = Raster.rgb(40, 70, 120)
        val out = Filters.apply(page, Filter.AUTO)
        val inside = Raster.luma(out[300, 350])
        assertTrue("photo stays dark: $inside", inside < 150)
    }

    @Test
    fun eraserFillsFromSurroundings() {
        val r = Raster.filled(300, 200, Raster.rgb(240, 238, 230))
        for (y in 80 until 120) for (x in 100 until 200) r[x, y] = Raster.rgb(20, 20, 160) // a pen mark
        val mask = BooleanArray(r.px.size)
        Cleanup.brush(mask, r.width, r.height, listOf(Pt(95f, 100f), Pt(205f, 100f)), 25f)
        val n = Cleanup.fillFromSurroundings(r, mask)
        assertTrue(n > 4000)
        for (y in 80 until 120) for (x in 100 until 200) {
            val c = r[x, y]
            assertTrue("pixel $x,$y = ${Integer.toHexString(c)}", abs(((c shr 16) and 0xFF) - 240) < 6 && abs((c and 0xFF) - 230) < 6)
        }
    }

    @Test
    fun eraserWithChosenColour() {
        val r = Raster.filled(100, 100, Raster.rgb(200, 200, 200))
        val mask = BooleanArray(r.px.size)
        Cleanup.brush(mask, 100, 100, listOf(Pt(50f, 50f)), 10f)
        val n = Cleanup.fillColor(r, mask, Raster.rgb(255, 0, 0))
        assertTrue(n in 280..340) // about pi * 10^2
        assertEquals(Raster.rgb(255, 0, 0), r[50, 50])
        assertEquals(Raster.rgb(200, 200, 200), r[5, 5])
    }

    @Test
    fun fingersAtThePageEdgeAreRemoved() {
        val page = Synth.document(600, 800)
        val skin = Raster.rgb(214, 160, 130)
        // A thumb coming in from the left edge, over the margin.
        for (y in 380 until 460) for (x in 0 until 70) { val dy = y - 420; if (x * x / 4900.0 + dy * dy / 1600.0 < 1.0 || x < 30) page[x, y] = skin }
        val mask = Cleanup.fingerMask(page)
        assertNotNull(mask)
        assertTrue(mask!![20 + 420 * 600])
        assertFalse("text is not skin", mask[300 + 100 * 600])
        assertTrue(Cleanup.removeFingers(page))
        val c = page[20, 420]
        assertTrue("filled with paper: ${Integer.toHexString(c)}", Raster.luma(c) > 230)
    }

    @Test
    fun noFingersOnACleanPage() {
        assertNull(Cleanup.fingerMask(Synth.document(600, 800)))
    }

    @Test
    fun idCardPageIsTrueSize() {
        val front = Raster.filled(856, 540, Raster.rgb(30, 90, 160))
        val back = Raster.filled(540, 856, Raster.rgb(160, 30, 30)) // portrait capture of the back
        val page = Compose.idCardPage(front, back)
        assertEquals(Compose.A4_W, page.width); assertEquals(Compose.A4_H, page.height)
        // 85.6 mm at 300 dpi = 1011 px wide: count blue pixels on the middle row of the front card.
        val y = (30f / 25.4f * 300).toInt() + 319
        val blue = (0 until page.width).count { page[it, y] == Raster.rgb(30, 90, 160) }
        assertTrue("card width $blue px", blue in 1005..1015)
        val red = (0 until page.width).count { page[it, y + 638 + 142] == Raster.rgb(160, 30, 30) }
        assertTrue("back turned to lie like the card: $red px", red in 1005..1015)
    }

    @Test
    fun bookSpreadSplitsInReadingOrder() {
        val spread = Raster(200, 100)
        for (y in 0 until 100) for (x in 0 until 200) spread[x, y] = if (x < 100) Raster.rgb(255, 0, 0) else Raster.rgb(0, 0, 255)
        val (l, r) = Compose.splitSpread(spread)
        assertEquals(100, l.width); assertEquals(Raster.rgb(255, 0, 0), l[50, 50]); assertEquals(Raster.rgb(0, 0, 255), r[50, 50])
        val swapped = Compose.splitSpread(spread, rightFirst = true)
        assertEquals(Raster.rgb(0, 0, 255), swapped[0][50, 50])
    }

    @Test
    fun rasterHelpers() {
        val r = Raster(3, 2, intArrayOf(1, 2, 3, 4, 5, 6).map { Raster.rgb(it, 0, 0) }.toIntArray())
        val r1 = r.rotated(1)
        assertEquals(2, r1.width); assertEquals(3, r1.height)
        assertEquals(Raster.rgb(4, 0, 0), r1[0, 0]); assertEquals(Raster.rgb(1, 0, 0), r1[1, 0])
        assertTrue(r.rotated(4).px.contentEquals(r.px))
        assertTrue(r.rotated(1).rotated(3).px.contentEquals(r.px))
        assertEquals(Raster.rgb(5, 0, 0), r.crop(1, 1, 2, 2)[0, 0])
        val big = Raster.filled(1000, 500, Raster.rgb(10, 20, 30)).scaledToMax(100)
        assertEquals(100, big.width); assertEquals(50, big.height); assertEquals(Raster.rgb(10, 20, 30), big[7, 7])
    }
}
