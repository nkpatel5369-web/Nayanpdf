package npdf.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An ARGB image (0xAARRGGBB per pixel, row by row), independent of any platform. */
class Raster(val width: Int, val height: Int, val px: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "Empty image" }
        require(px.size == width * height) { "Pixel count does not match the size" }
    }

    operator fun get(x: Int, y: Int): Int = px[y * width + x]
    operator fun set(x: Int, y: Int, c: Int) { px[y * width + x] = c }

    fun copy(): Raster = Raster(width, height, px.copyOf())

    /** Luminance 0..255 (Rec. 601 weights). */
    fun gray(): Gray {
        val out = FloatArray(width * height)
        for (i in px.indices) out[i] = luma(px[i])
        return Gray(width, height, out)
    }

    /** This image reduced so that its longer side is at most [maxSide] (area averaging). */
    fun scaledToMax(maxSide: Int): Raster {
        val longest = max(width, height)
        if (longest <= maxSide) return this
        val s = maxSide.toFloat() / longest
        return resize(max(1, (width * s).roundToInt()), max(1, (height * s).roundToInt()))
    }

    /** Resized to [w] x [h]: area averaging when shrinking, bilinear when enlarging. */
    fun resize(w: Int, h: Int): Raster {
        if (w == width && h == height) return copy()
        val out = Raster(w, h)
        if (w <= width && h <= height) {
            val sx = width.toFloat() / w
            val sy = height.toFloat() / h
            for (y in 0 until h) {
                val y0 = (y * sy).toInt(); val y1 = min(height, max(y0 + 1, ((y + 1) * sy).toInt()))
                for (x in 0 until w) {
                    val x0 = (x * sx).toInt(); val x1 = min(width, max(x0 + 1, ((x + 1) * sx).toInt()))
                    var a = 0L; var r = 0L; var g = 0L; var b = 0L
                    for (yy in y0 until y1) {
                        val row = yy * width
                        for (xx in x0 until x1) {
                            val c = px[row + xx]
                            a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                        }
                    }
                    val n = ((y1 - y0) * (x1 - x0)).toLong()
                    out.px[y * w + x] = argb((a / n).toInt(), (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
                }
            }
        } else {
            val sx = width.toFloat() / w
            val sy = height.toFloat() / h
            for (y in 0 until h) for (x in 0 until w) {
                out.px[y * w + x] = sample((x + 0.5f) * sx - 0.5f, (y + 0.5f) * sy - 0.5f)
            }
        }
        return out
    }

    /** Bilinear sample at a sub-pixel position (clamped to the image). */
    fun sample(fx: Float, fy: Float): Int {
        val x = fx.coerceIn(0f, (width - 1).toFloat())
        val y = fy.coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val ax = x - x0; val ay = y - y0
        val c00 = px[y0 * width + x0]; val c10 = px[y0 * width + x1]
        val c01 = px[y1 * width + x0]; val c11 = px[y1 * width + x1]
        fun ch(sh: Int): Int {
            val v00 = (c00 shr sh) and 0xFF; val v10 = (c10 shr sh) and 0xFF
            val v01 = (c01 shr sh) and 0xFF; val v11 = (c11 shr sh) and 0xFF
            val top = v00 + (v10 - v00) * ax
            val bot = v01 + (v11 - v01) * ax
            return (top + (bot - top) * ay + 0.5f).toInt().coerceIn(0, 255)
        }
        return argb(ch(24), ch(16), ch(8), ch(0))
    }

    /** Rotated clockwise by [quarterTurns] x 90 degrees. */
    fun rotated(quarterTurns: Int): Raster {
        val q = ((quarterTurns % 4) + 4) % 4
        if (q == 0) return copy()
        val w2 = if (q % 2 == 0) width else height
        val h2 = if (q % 2 == 0) height else width
        val out = Raster(w2, h2)
        for (y in 0 until height) for (x in 0 until width) {
            val c = px[y * width + x]
            when (q) {
                1 -> out.px[x * w2 + (height - 1 - y)] = c
                2 -> out.px[(height - 1 - y) * w2 + (width - 1 - x)] = c
                else -> out.px[(width - 1 - x) * w2 + y] = c
            }
        }
        return out
    }

    /** The part of the image inside [x0, x1) x [y0, y1). */
    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Raster {
        val a = x0.coerceIn(0, width - 1); val b = y0.coerceIn(0, height - 1)
        val c = x1.coerceIn(a + 1, width); val d = y1.coerceIn(b + 1, height)
        val out = Raster(c - a, d - b)
        for (y in b until d) System.arraycopy(px, y * width + a, out.px, (y - b) * out.width, c - a)
        return out
    }

    companion object {
        fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
        fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        fun luma(c: Int): Float = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        fun filled(w: Int, h: Int, c: Int) = Raster(w, h, IntArray(w * h) { c })
    }
}

/** A single-channel image (values usually 0..255). */
class Gray(val width: Int, val height: Int, val v: FloatArray = FloatArray(width * height)) {
    operator fun get(x: Int, y: Int): Float = v[y * width + x]

    /** Reduced so that the longer side is at most [maxSide] (area averaging); returns the scale used. */
    fun scaledToMax(maxSide: Int): Pair<Gray, Float> {
        val longest = max(width, height)
        if (longest <= maxSide) return this to 1f
        val s = maxSide.toFloat() / longest
        val w = max(1, (width * s).roundToInt()); val h = max(1, (height * s).roundToInt())
        val out = Gray(w, h)
        val sx = width.toFloat() / w; val sy = height.toFloat() / h
        for (y in 0 until h) {
            val y0 = (y * sy).toInt(); val y1 = min(height, max(y0 + 1, ((y + 1) * sy).toInt()))
            for (x in 0 until w) {
                val x0 = (x * sx).toInt(); val x1 = min(width, max(x0 + 1, ((x + 1) * sx).toInt()))
                var sum = 0f
                for (yy in y0 until y1) { val row = yy * width; for (xx in x0 until x1) sum += v[row + xx] }
                out.v[y * w + x] = sum / ((y1 - y0) * (x1 - x0))
            }
        }
        return out to (w.toFloat() / width)
    }

    /** Separable blur with the 5-tap binomial kernel [1 4 6 4 1] / 16, applied [passes] times. */
    fun blurred(passes: Int = 1): Gray {
        var src = v
        val tmp = FloatArray(v.size)
        repeat(passes) {
            val out = FloatArray(v.size)
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    fun at(xx: Int) = src[row + xx.coerceIn(0, width - 1)]
                    tmp[row + x] = (at(x - 2) + 4 * at(x - 1) + 6 * at(x) + 4 * at(x + 1) + at(x + 2)) / 16f
                }
            }
            for (y in 0 until height) for (x in 0 until width) {
                fun at(yy: Int) = tmp[yy.coerceIn(0, height - 1) * width + x]
                out[y * width + x] = (at(y - 2) + 4 * at(y - 1) + 6 * at(y) + 4 * at(y + 1) + at(y + 2)) / 16f
            }
            src = out
        }
        return Gray(width, height, src)
    }

    /** Bilinear sample at a sub-pixel position (clamped). */
    fun sample(fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, (width - 1).toFloat()); val y = fy.coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val ax = x - x0; val ay = y - y0
        val top = v[y0 * width + x0] * (1 - ax) + v[y0 * width + x1] * ax
        val bot = v[y1 * width + x0] * (1 - ax) + v[y1 * width + x1] * ax
        return top * (1 - ay) + bot * ay
    }

    companion object {
        /** From an 8-bit luminance plane (for example a camera frame's Y plane) with a row stride. */
        fun fromLuminance(bytes: ByteArray, width: Int, height: Int, rowStride: Int = width): Gray {
            val out = Gray(width, height)
            for (y in 0 until height) {
                val row = y * rowStride
                for (x in 0 until width) out.v[y * width + x] = (bytes[row + x].toInt() and 0xFF).toFloat()
            }
            return out
        }
    }
}
