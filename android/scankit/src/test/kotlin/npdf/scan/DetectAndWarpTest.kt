package npdf.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class DetectAndWarpTest {

    private val doc = Synth.document()

    private fun err(q: Quad, truth: Quad, w: Int, h: Int) = q.maxCornerDistance(truth) / hypot(w.toFloat(), h.toFloat())

    /** 150 photos on five kinds of surface; reports the success rate per surface and overall. */
    @Test
    fun findsDocumentOutlinesInVariedPhotos() {
        var ok = 0; var total = 0
        val worst = ArrayList<String>()
        for (bg in Synth.Bg.values()) {
            var okBg = 0
            for (seed in 1..30) {
                val p = Synth.photo(doc, bg, seed * 7 + bg.ordinal)
                val r = DocDetector.detect(p.raster.gray())
                total++
                val e = r?.let { err(DocDetector.refine(p.raster.gray(), it.quad), p.truth, p.raster.width, p.raster.height) }
                if (e != null && e < 0.01f) { ok++; okBg++ } else worst.add("$bg/$seed: ${if (e == null) "not found" else "%.3f".format(e)}")
            }
            println("NPDF-SCAN detection on $bg: $okBg / 30 within 1 % of the diagonal")
        }
        println("NPDF-SCAN detection overall: $ok / $total; misses: $worst")
        // Speed on this machine at camera-preview size (640 x 480), for comparison with the phone measurements.
        val frame = Synth.photo(doc, Synth.Bg.CLUTTER, 5, 640, 480).raster.gray()
        repeat(3) { DocDetector.detect(frame) }
        val t0 = System.nanoTime(); repeat(10) { DocDetector.detect(frame) }
        println("NPDF-SCAN detection time per 640x480 frame on the JVM: ${(System.nanoTime() - t0) / 10_000_000} ms")
        assertTrue("detected $ok of $total: $worst", ok >= total * 0.95)
    }

    @Test
    fun refinementIsSubPixelAccurate() {
        val errs = ArrayList<Float>()
        for (seed in 1..10) {
            val p = Synth.photo(doc, Synth.Bg.DARK_WOOD, 500 + seed, shadow = false)
            val g = p.raster.gray()
            val r = DocDetector.detect(g) ?: continue
            val q = DocDetector.refine(g, r.quad)
            errs.add(q.maxCornerDistance(p.truth))
        }
        println("NPDF-SCAN refined corner error (px, 1600x1200 photo): ${errs.map { "%.1f".format(it) }}")
        assertEquals(10, errs.size)
        assertTrue("max error ${errs.max()} px", errs.max() < 6f)
        assertTrue("median error", errs.sorted()[5] < 3f)
    }

    @Test
    fun noDocumentMeansNoOutline() {
        val r = Random(3)
        // Plain noise and a uniform surface: nothing to find.
        val noise = Raster(800, 600, IntArray(800 * 600) { val v = 100 + r.nextInt(-20, 21); Raster.rgb(v, v, v) })
        assertNull(DocDetector.detect(noise.gray()))
        val flat = Raster.filled(800, 600, Raster.rgb(140, 140, 140))
        assertNull(DocDetector.detect(flat.gray()))
    }

    @Test
    fun correctedPageMatchesTheOriginal() {
        for (seed in 1..6) {
            val p = Synth.photo(doc, Synth.Bg.GREY_DESK, 900 + seed)
            val g = p.raster.gray()
            val q = DocDetector.refine(g, DocDetector.detect(g)!!.quad)
            val out = Warp.warp(p.raster, q, doc.width, doc.height)
            val s = Synth.ncc(Filters.apply(out, Filter.GRAY), Filters.apply(doc, Filter.GRAY))
            println("NPDF-SCAN corrected vs original, seed $seed: NCC %.3f".format(s))
            assertTrue("similarity $s", s > 0.9)
        }
    }

    @Test
    fun warpOfTheTrueOutlineRecoversTheDocument() {
        val p = Synth.photo(doc, Synth.Bg.CLUTTER, 77, shadow = false)
        val out = Warp.warp(p.raster, p.truth, doc.width, doc.height)
        val s = Synth.ncc(out, doc)
        assertTrue("similarity $s", s > 0.95)
    }

    @Test
    fun outputSizeKeepsProportionsAndCap() {
        val q = Quad(Pt(0f, 0f), Pt(2000f, 0f), Pt(2000f, 2828f), Pt(0f, 2828f))
        val (w, h) = Warp.outputSize(q)
        assertEquals(2828f / 2000f, h.toFloat() / w, 0.01f)
        val big = q.scaled(3f)
        val (w2, h2) = Warp.outputSize(big)
        assertEquals(Warp.MAX_SIDE, maxOf(w2, h2))
    }

    /** A curved top edge (book page) is detected and straightened. */
    @Test
    fun curvedEdgeIsEstimatedAndStraightened() {
        // Draw a page whose top and bottom edges bow outwards by 30 px.
        val w = 1400; val h = 1000
        val photo = Raster.filled(w, h, Raster.rgb(60, 60, 70))
        val tl = Pt(300f, 150f); val tr = Pt(1100f, 150f); val br = Pt(1100f, 850f); val bl = Pt(300f, 850f)
        val bulge = 30f
        for (y in 0 until h) for (x in 0 until w) {
            if (x < tl.x || x > tr.x) continue
            val u = (x - tl.x) / (tr.x - tl.x)
            val top = tl.y - bulge * 4 * u * (1 - u); val bot = bl.y + bulge * 4 * u * (1 - u)
            if (y in top.toInt()..bot.toInt()) {
                // Horizontal text-like stripes that follow the curve.
                val v = (y - top) / (bot - top)
                // Text-like stripes with a page margin of 40 px on each side, as printed pages have.
                val stripe = ((v * 20).toInt() % 2 == 0) && (x - tl.x) % 60 < 45 && x > tl.x + 40 && x < tr.x - 40
                photo[x, y] = if (stripe && v > 0.05 && v < 0.95) Raster.rgb(40, 40, 40) else Raster.rgb(245, 245, 240)
            }
        }
        val g = photo.gray()
        val r = DocDetector.detect(g, curvedEdges = true)
        assertNotNull(r)
        val q = DocDetector.refine(g, r!!.quad, curved = true)
        println("NPDF-SCAN curved edge bulges (top,right,bottom,left): ${q.bulge.map { "%.1f".format(it) }} detected ${r.quad.corners.map { "(%.0f,%.0f)".format(it.x, it.y) }} refined ${q.corners.map { "(%.0f,%.0f)".format(it.x, it.y) }}")
        assertTrue("top bulge ${q.bulge[0]}", q.bulge[0] in 18f..42f)
        assertTrue("bottom bulge ${q.bulge[2]}", q.bulge[2] in 18f..42f)
        assertTrue("straight sides stay straight", kotlin.math.abs(q.bulge[1]) < 8f && kotlin.math.abs(q.bulge[3]) < 8f)
        val truth = Quad(tl, tr, br, bl)
        assertTrue("corners ${q.corners}", q.maxCornerDistance(truth) < 4f)
        // After the warp the first stripe boundary is level: compare its row at the left, centre and right.
        val out = Warp.warp(photo, q, 800, 700)
        // Skip the first rows: they lie on the page boundary itself.
        fun firstDarkRow(x: Int): Int { for (y in 12 until out.height) if (Raster.luma(out[x, y]) < 128) return y; return -1 }
        val rows = listOf(firstDarkRow(80), firstDarkRow(380), firstDarkRow(740)) // columns inside text stripes
        println("NPDF-SCAN first text row after straightening at left/centre/right: $rows")
        assertTrue("level text: $rows", rows.max() - rows.min() <= 6)
    }

    @Test
    fun quadOrderingAndGeometry() {
        val q = Quad.ordered(listOf(Pt(10f, 90f), Pt(90f, 10f), Pt(10f, 10f), Pt(90f, 90f)))
        assertEquals(Pt(10f, 10f), q.tl); assertEquals(Pt(90f, 10f), q.tr); assertEquals(Pt(90f, 90f), q.br); assertEquals(Pt(10f, 90f), q.bl)
        assertEquals(6400f, q.area(), 0.01f)
        assertTrue(q.isConvex())
        val hm = Homography.fromPoints(listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f)), q.corners)
        val m = hm.map(0.5f, 0.5f)
        assertEquals(50f, m.x, 0.01f); assertEquals(50f, m.y, 0.01f)
    }

    @Test
    fun maxAreaQuadMatchesBruteForce() {
        val r = Random(11)
        repeat(60) {
            val n = r.nextInt(5, 40)
            val pts = List(n) { val a = r.nextDouble(0.0, Math.PI * 2); val rad = r.nextDouble(50.0, 100.0); Pt((rad * kotlin.math.cos(a)).toFloat(), (rad * kotlin.math.sin(a)).toFloat()) }
            val hull = Geo.hull(pts)
            if (hull.size < 4) return@repeat
            val fast = Geo.maxAreaQuad(hull)!!
            var best = 0f
            val m = hull.size
            for (a in 0 until m) for (b in a + 1 until m) for (c in b + 1 until m) for (d in c + 1 until m) {
                best = maxOf(best, Quad(hull[a], hull[b], hull[c], hull[d]).area())
            }
            assertEquals(best, Quad(fast[0], fast[1], fast[2], fast[3]).area(), best * 0.001f)
        }
    }
}
