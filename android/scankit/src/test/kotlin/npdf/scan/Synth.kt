package npdf.scan

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic documents and "photos" of them, with known outlines, for the scanner tests. */
object Synth {

    fun toRaster(img: BufferedImage): Raster {
        val w = img.width; val h = img.height
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
        return Raster(w, h, px)
    }

    /** A page of text with a heading, a ruled table and a grey logo, like a statement. */
    fun document(w: Int = 850, h: Int = 1100, seed: Int = 1): Raster {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(250, 249, 245); g.fillRect(0, 0, w, h)
        g.color = Color(30, 30, 30)
        g.font = Font(Font.SERIF, Font.BOLD, (h * 0.03).toInt())
        g.drawString("ACME Traders Private Limited", (w * 0.08).toInt(), (h * 0.08).toInt())
        g.font = Font(Font.SERIF, Font.PLAIN, (h * 0.018).toInt())
        val r = Random(seed)
        val words = listOf("revenue", "operations", "expenses", "depreciation", "profit", "tax", "the", "and", "for", "year", "ended", "March", "amounts", "statement")
        var y = (h * 0.14).toInt()
        while (y < h * 0.55) {
            val line = (0 until 9).joinToString(" ") { words[r.nextInt(words.size)] }
            g.drawString(line, (w * 0.08).toInt(), y); y += (h * 0.028).toInt()
        }
        g.stroke = BasicStroke(2f)
        val tx = (w * 0.08).toInt(); val tw = (w * 0.84).toInt(); var ty = (h * 0.6).toInt()
        for (row in 0..5) { g.drawLine(tx, ty, tx + tw, ty); if (row < 5) g.drawString("Row ${row + 1}    1,23,456.78    98,765.43", tx + 8, ty + (h * 0.03).toInt()); ty += (h * 0.04).toInt() }
        g.color = Color(120, 120, 130); g.fillRect((w * 0.75).toInt(), (h * 0.03).toInt(), (w * 0.15).toInt(), (h * 0.06).toInt())
        g.dispose()
        return toRaster(img)
    }

    enum class Bg { DARK_WOOD, GREY_DESK, BEIGE, CLUTTER, WHITE_DESK }

    private fun background(kind: Bg, w: Int, h: Int, r: Random): Raster {
        val out = Raster(w, h)
        val blobs = List(25) { Triple(r.nextInt(w), r.nextInt(h), r.nextInt(30, 160)) }
        val blobColors = List(25) { Raster.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)) }
        for (y in 0 until h) for (x in 0 until w) {
            val n = r.nextInt(-6, 7)
            out[x, y] = when (kind) {
                Bg.DARK_WOOD -> { val s = (sin(x * 0.02 + sin(y * 0.01) * 3) * 12).toInt(); Raster.rgb(95 + s + n, 60 + s / 2 + n, 35 + n) }
                Bg.GREY_DESK -> Raster.rgb(120 + n, 122 + n, 128 + n)
                Bg.BEIGE -> Raster.rgb(205 + n, 195 + n, 175 + n)
                Bg.WHITE_DESK -> Raster.rgb(222 + n, 222 + n, 226 + n)
                Bg.CLUTTER -> {
                    var c = Raster.rgb(70 + n, 80 + n, 90 + n)
                    for ((k, b) in blobs.withIndex()) { val dx = x - b.first; val dy = y - b.second; if (dx * dx + dy * dy < b.third * b.third / 4) c = blobColors[k] }
                    c
                }
            }
        }
        return out
    }

    class Photo(val raster: Raster, val truth: Quad)

    /**
     * A photo of [doc] lying on [bg]: random size (40-75 % of the frame), rotation
     * up to [maxRotDeg], perspective tilt, uneven light, sensor noise and slight blur.
     */
    fun photo(doc: Raster, bg: Bg, seed: Int, w: Int = 1600, h: Int = 1200, maxRotDeg: Double = 25.0, shadow: Boolean = true): Photo {
        val r = Random(seed)
        val aspect = doc.width.toDouble() / doc.height
        // Draw outlines until one lies wholly inside the frame with a 3 % margin, as when a user frames a page.
        var truth: Quad
        do {
            val area = r.nextDouble(0.30, 0.65)
            val dh = kotlin.math.sqrt(area * w * h / aspect)
            val dw = dh * aspect
            val cx = w / 2.0 + r.nextDouble(-0.08, 0.08) * w; val cy = h / 2.0 + r.nextDouble(-0.08, 0.08) * h
            val a = Math.toRadians(r.nextDouble(-maxRotDeg, maxRotDeg))
            fun corner(sx: Double, sy: Double): Pt {
                val px = sx * dw / 2; val py = sy * dh / 2
                val jx = r.nextDouble(-0.06, 0.06) * dw; val jy = r.nextDouble(-0.06, 0.06) * dh
                return Pt((cx + (px + jx) * cos(a) - (py + jy) * sin(a)).toFloat(), (cy + (px + jx) * sin(a) + (py + jy) * cos(a)).toFloat())
            }
            truth = Quad(corner(-1.0, -1.0), corner(1.0, -1.0), corner(1.0, 1.0), corner(-1.0, 1.0))
            val m = 0.03f * minOf(w, h)
        } while (truth.corners.any { it.x < m || it.y < m || it.x > w - m || it.y > h - m })
        val back = background(bg, w, h, r)
        // Inverse map: photo pixel -> document pixel.
        val inv = Homography.fromPoints(truth.corners, listOf(Pt(0f, 0f), Pt(doc.width.toFloat(), 0f), Pt(doc.width.toFloat(), doc.height.toFloat()), Pt(0f, doc.height.toFloat())))
        val sx0 = r.nextDouble(0.0, w.toDouble()); val sy0 = r.nextDouble(0.0, h.toDouble())
        val out = Raster(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = inv.map(x + 0.5f, y + 0.5f)
            var c = if (p.x >= 0 && p.y >= 0 && p.x < doc.width && p.y < doc.height) doc.sample(p.x - 0.5f, p.y - 0.5f) else back[x, y]
            if (shadow) {
                val d = kotlin.math.hypot(x - sx0, y - sy0) / max(w, h)
                val f = (0.62 + 0.38 * min(1.0, d * 1.4)).toFloat()
                c = Raster.rgb((((c shr 16) and 0xFF) * f).toInt(), (((c shr 8) and 0xFF) * f).toInt(), ((c and 0xFF) * f).toInt())
            }
            val n = r.nextInt(-5, 6)
            out[x, y] = Raster.rgb(((c shr 16) and 0xFF) + n, ((c shr 8) and 0xFF) + n, (c and 0xFF) + n)
        }
        // Slight lens blur: 3x3 box.
        val blurred = Raster(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            var rr = 0; var gg = 0; var bb = 0; var k = 0
            for (dy in -1..1) for (dx in -1..1) {
                val qx = (x + dx).coerceIn(0, w - 1); val qy = (y + dy).coerceIn(0, h - 1)
                val c = out[qx, qy]; rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF; k++
            }
            blurred[x, y] = Raster.rgb(rr / k, gg / k, bb / k)
        }
        return Photo(blurred, truth)
    }

    /** Normalised cross-correlation of two images' luminance after scaling both to [size] x [size]. */
    fun ncc(a: Raster, b: Raster, size: Int = 160): Double {
        val ga = a.resize(size, size).gray().v; val gb = b.resize(size, size).gray().v
        val ma = ga.average(); val mb = gb.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in ga.indices) { val x = ga[i] - ma; val y = gb[i] - mb; num += x * y; da += x * x; db += y * y }
        return num / kotlin.math.sqrt(da * db)
    }
}
