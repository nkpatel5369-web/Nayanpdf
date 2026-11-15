package npdf.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Looks for corrected pages, as in a document scanner. */
enum class Filter(val label: String) {
    ORIGINAL("Original"),
    AUTO("Auto colour"),
    LIGHT_TEXT("Light text"),
    GRAY("Greyscale"),
    BW("Black & white"),
    WHITEBOARD("Whiteboard"),
}

/**
 * Image clean-up for scanned pages. Every filter except Original first evens out
 * the lighting: the paper's own brightness is estimated across the page (text and
 * small marks are removed from that estimate) and each pixel is divided by it,
 * which removes shadows, uneven light and paper tint.
 */
object Filters {

    /** Paper brightness per channel on a coarse grid, with bilinear lookup. */
    class Background(val w: Int, val h: Int, val sx: Float, val sy: Float, val ch: Array<FloatArray>) {
        fun at(c: Int, x: Float, y: Float): Float {
            val fx = (x * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
            val fy = (y * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val ax = fx - x0; val ay = fy - y0
            val a = ch[c]
            val top = a[y0 * w + x0] * (1 - ax) + a[y0 * w + x1] * ax
            val bot = a[y1 * w + x0] * (1 - ax) + a[y1 * w + x1] * ax
            return top * (1 - ay) + bot * ay
        }
    }

    /**
     * Estimates the paper brightness. [radiusFrac] sets how large a dark mark is
     * still treated as content rather than shadow (fraction of the shorter side).
     */
    fun background(src: Raster, radiusFrac: Float = 0.04f): Background {
        val small = src.scaledToMax(220)
        val w = small.width; val h = small.height
        val r = max(2, (min(w, h) * radiusFrac).roundToInt())
        val chans = Array(3) { c ->
            val sh = 16 - 8 * c
            FloatArray(w * h) { ((small.px[it] shr sh) and 0xFF).toFloat() }
        }
        // Paper level: bright percentile of the luminance.
        val lum = FloatArray(w * h) { Raster.luma(small.px[it]) }
        val paper = percentile(lum, 0.9f).coerceAtLeast(40f)
        val out = Array(3) { c ->
            var a = maxFilter(chans[c], w, h, r)
            a = boxBlur(a, w, h, r)
            a = boxBlur(a, w, h, r)
            // A large dark area (a photo) must not be mistaken for shadow and washed out.
            val floor = paper * 0.55f
            for (i in a.indices) a[i] = max(a[i], floor).coerceAtLeast(1f)
            a
        }
        return Background(w, h, w.toFloat() / src.width, h.toFloat() / src.height, out)
    }

    fun apply(src: Raster, f: Filter, brightness: Float = 0f, contrast: Float = 1f): Raster {
        val out = when (f) {
            Filter.ORIGINAL -> src.copy()
            Filter.AUTO -> colour(src, gamma = 1.15f, sat = 1.2f, white = 0.97f)
            Filter.LIGHT_TEXT -> colour(src, gamma = 1.9f, sat = 1.1f, white = 0.96f)
            Filter.GRAY -> gray(src, gamma = 1.25f, bw = false)
            Filter.BW -> gray(src, gamma = 1f, bw = true)
            Filter.WHITEBOARD -> colour(src, gamma = 1.4f, sat = 1.8f, white = 0.82f, radius = 0.08f)
        }
        if (brightness != 0f || contrast != 1f) adjust(out, brightness, contrast)
        return out
    }

    /** Brightness (-100..100) and contrast (0.5..2) in place. */
    fun adjust(r: Raster, brightness: Float, contrast: Float) {
        val lut = IntArray(256) { (((it - 128) * contrast + 128 + brightness).roundToInt()).coerceIn(0, 255) }
        for (i in r.px.indices) {
            val c = r.px[i]
            r.px[i] = Raster.argb(c ushr 24, lut[(c shr 16) and 0xFF], lut[(c shr 8) and 0xFF], lut[c and 0xFF])
        }
    }

    private fun colour(src: Raster, gamma: Float, sat: Float, white: Float, radius: Float = 0.04f): Raster {
        val bg = background(src, radius)
        val w = src.width; val h = src.height
        val out = Raster(w, h)
        // First pass: normalise, collect a luminance sample for the black point.
        val norm = IntArray(w * h)
        val sample = FloatArray(min(w * h, 40000))
        val step = max(1, (w * h) / sample.size)
        var si = 0
        for (y in 0 until h) {
            val fy = y + 0.5f
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.px[i]
                val fx = x + 0.5f
                val r = (((c shr 16) and 0xFF) / bg.at(0, fx, fy) * 255f).coerceIn(0f, 255f)
                val g = (((c shr 8) and 0xFF) / bg.at(1, fx, fy) * 255f).coerceIn(0f, 255f)
                val b = ((c and 0xFF) / bg.at(2, fx, fy) * 255f).coerceIn(0f, 255f)
                norm[i] = Raster.rgb(r.toInt(), g.toInt(), b.toInt())
                if (i % step == 0 && si < sample.size) sample[si++] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
        val black = percentile(sample.copyOf(si), 0.01f).coerceIn(0f, 90f)
        val top = 255f * white
        val lut = FloatArray(256) {
            val t = ((it - black) / (top - black)).coerceIn(0f, 1f)
            t.pow(gamma) * 255f
        }
        for (i in norm.indices) {
            val c = norm[i]
            var r = lut[(c shr 16) and 0xFF]; var g = lut[(c shr 8) and 0xFF]; var b = lut[c and 0xFF]
            if (sat != 1f) {
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                r = l + (r - l) * sat; g = l + (g - l) * sat; b = l + (b - l) * sat
            }
            out.px[i] = Raster.rgb(r.roundToInt(), g.roundToInt(), b.roundToInt())
        }
        return out
    }

    private fun gray(src: Raster, gamma: Float, bw: Boolean): Raster {
        val bg = background(src)
        val w = src.width; val h = src.height
        val lum = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val c = src.px[i]
            val fx = x + 0.5f; val fy = y + 0.5f
            val bl = 0.299f * bg.at(0, fx, fy) + 0.587f * bg.at(1, fx, fy) + 0.114f * bg.at(2, fx, fy)
            lum[i] = (Raster.luma(c) / bl * 255f).coerceIn(0f, 255f)
        }
        val out = Raster(w, h)
        if (bw) {
            // After lighting is evened out, paper is near 255 everywhere, so one threshold suits the whole page.
            val t = 0.72f * 255f
            for (i in lum.indices) {
                val v = ((lum[i] - (t - 14f)) / 28f).coerceIn(0f, 1f) * 255f
                val g = v.roundToInt(); out.px[i] = Raster.rgb(g, g, g)
            }
        } else {
            val sample = lum.copyOf()
            val black = percentile(sample, 0.01f).coerceIn(0f, 90f)
            for (i in lum.indices) {
                val t = ((lum[i] - black) / (250f - black)).coerceIn(0f, 1f)
                val g = (t.pow(gamma) * 255f).roundToInt(); out.px[i] = Raster.rgb(g, g, g)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    fun percentile(v: FloatArray, p: Float): Float {
        if (v.isEmpty()) return 0f
        val hist = IntArray(256)
        for (x in v) hist[x.roundToInt().coerceIn(0, 255)]++
        val target = (v.size * p).toInt()
        var acc = 0
        for (i in 0 until 256) { acc += hist[i]; if (acc > target) return i.toFloat() }
        return 255f
    }

    private fun maxFilter(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (k in max(0, x - r)..min(w - 1, x + r)) m = max(m, a[y * w + k])
            tmp[y * w + x] = m
        }
        val out = FloatArray(a.size)
        for (x in 0 until w) for (y in 0 until h) {
            var m = 0f
            for (k in max(0, y - r)..min(h - 1, y + r)) m = max(m, tmp[k * w + x])
            out[y * w + x] = m
        }
        return out
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (k in max(0, x - r)..min(w - 1, x + r)) { s += a[y * w + k]; n++ }
            tmp[y * w + x] = s / n
        }
        val out = FloatArray(a.size)
        for (x in 0 until w) for (y in 0 until h) {
            var s = 0f; var n = 0
            for (k in max(0, y - r)..min(h - 1, y + r)) { s += tmp[k * w + x]; n++ }
            out[y * w + x] = s / n
        }
        return out
    }
}
