package npdf.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Removing marks from a scanned page: eraser fill and finger removal. */
object Cleanup {

    /**
     * Fills the pixels where [mask] is true from their surroundings, layer by
     * layer from the edge of the area inwards ("fill with surrounding colour").
     * Returns the number of pixels filled.
     */
    fun fillFromSurroundings(r: Raster, mask: BooleanArray): Int {
        val w = r.width; val h = r.height
        require(mask.size == w * h)
        val known = BooleanArray(w * h) { !mask[it] }
        if (known.none { it }) return 0
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (i in mask.indices) if (mask[i]) { val x = i % w; val y = i / w; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) }
        if (x1 < 0) return 0
        var filled = 0
        var frontier = ArrayList<Int>()
        for (y in y0..y1) for (x in x0..x1) { val i = y * w + x; if (!known[i] && hasKnownNeighbour(known, w, h, x, y)) frontier.add(i) }
        val queued = BooleanArray(w * h)
        for (i in frontier) queued[i] = true
        while (frontier.isNotEmpty()) {
            val values = IntArray(frontier.size)
            for ((k, i) in frontier.withIndex()) {
                val x = i % w; val y = i / w
                var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val qx = x + dx; val qy = y + dy
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                    val q = qy * w + qx
                    if (!known[q]) continue
                    val wt = if (dx == 0 || dy == 0) 1f else 0.7f
                    val c = r.px[q]
                    sr += wt * ((c shr 16) and 0xFF); sg += wt * ((c shr 8) and 0xFF); sb += wt * (c and 0xFF); sw += wt
                }
                values[k] = Raster.rgb((sr / sw).roundToInt(), (sg / sw).roundToInt(), (sb / sw).roundToInt())
            }
            val next = ArrayList<Int>()
            for ((k, i) in frontier.withIndex()) { r.px[i] = values[k]; known[i] = true; filled++ }
            for (i in frontier) {
                val x = i % w; val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val qx = x + dx; val qy = y + dy
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                    val q = qy * w + qx
                    if (!known[q] && !queued[q]) { queued[q] = true; next.add(q) }
                }
            }
            frontier = next
        }
        return filled
    }

    private fun hasKnownNeighbour(known: BooleanArray, w: Int, h: Int, x: Int, y: Int): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            val qx = x + dx; val qy = y + dy
            if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
            if (known[qy * w + qx]) return true
        }
        return false
    }

    /** Paints the masked pixels with [color] ("fill with selected colour"). */
    fun fillColor(r: Raster, mask: BooleanArray, color: Int): Int {
        var n = 0
        for (i in mask.indices) if (mask[i]) { r.px[i] = color or (0xFF shl 24); n++ }
        return n
    }

    /** A round brush stroke through [points] with radius [radius], added to [mask]. */
    fun brush(mask: BooleanArray, w: Int, h: Int, points: List<Pt>, radius: Float) {
        if (points.isEmpty()) return
        val pts = if (points.size == 1) points else {
            // Densify so that a fast stroke has no gaps.
            val out = ArrayList<Pt>()
            for (k in 0 until points.size - 1) {
                val a = points[k]; val b = points[k + 1]
                val n = max(1, (a.dist(b) / (radius * 0.5f)).toInt())
                for (j in 0 until n) out.add(a + (b - a) * (j.toFloat() / n))
            }
            out.add(points.last()); out
        }
        val r2 = radius * radius
        for (p in pts) {
            val xa = max(0, (p.x - radius).toInt()); val xb = min(w - 1, (p.x + radius).toInt())
            val ya = max(0, (p.y - radius).toInt()); val yb = min(h - 1, (p.y + radius).toInt())
            for (y in ya..yb) for (x in xa..xb) {
                val dx = x + 0.5f - p.x; val dy = y + 0.5f - p.y
                if (dx * dx + dy * dy <= r2) mask[y * w + x] = true
            }
        }
    }

    /**
     * Finds fingers holding the page: skin-coloured areas that reach the page
     * border and are not larger than a hand would be. Returns the mask (grown a
     * little to cover the finger's soft outline) or null when nothing is found.
     */
    fun fingerMask(r: Raster): BooleanArray? {
        val w = r.width; val h = r.height
        val skin = BooleanArray(w * h)
        for (i in r.px.indices) {
            val c = r.px[i]
            val rr = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val bb = c and 0xFF
            val y = 0.299f * rr + 0.587f * gg + 0.114f * bb
            val cr = 128 + 0.5f * rr - 0.4187f * gg - 0.0813f * bb
            val cb = 128 - 0.1687f * rr - 0.3313f * gg + 0.5f * bb
            skin[i] = y in 50f..245f && cr in 138f..178f && cb in 80f..128f && rr > gg && rr > bb && (rr - bb) > 25
        }
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        val out = BooleanArray(w * h)
        var found = false
        var next = 0
        val pageArea = w.toLong() * h
        for (s in skin.indices) {
            if (!skin[s] || label[s] != 0) continue
            next++
            var sp = 0; stack[sp++] = s; label[s] = next
            val members = ArrayList<Int>()
            var touchesBorder = false
            while (sp > 0) {
                val p = stack[--sp]; members.add(p)
                val px = p % w; val py = p / w
                if (px <= 1 || py <= 1 || px >= w - 2 || py >= h - 2) touchesBorder = true
                if (px > 0 && skin[p - 1] && label[p - 1] == 0) { label[p - 1] = next; stack[sp++] = p - 1 }
                if (px < w - 1 && skin[p + 1] && label[p + 1] == 0) { label[p + 1] = next; stack[sp++] = p + 1 }
                if (py > 0 && skin[p - w] && label[p - w] == 0) { label[p - w] = next; stack[sp++] = p - w }
                if (py < h - 1 && skin[p + w] && label[p + w] == 0) { label[p + w] = next; stack[sp++] = p + w }
            }
            val area = members.size
            if (touchesBorder && area >= pageArea * 0.0015 && area <= pageArea * 0.15) {
                found = true
                for (p in members) out[p] = true
            }
        }
        if (!found) return null
        // Grow by about 0.6 % of the shorter side.
        val grow = max(2, (min(w, h) * 0.006f).roundToInt())
        return DocDetector.dilate(out, w, h, grow)
    }

    /** Removes fingers found by [fingerMask]; returns true when something was removed. */
    fun removeFingers(r: Raster): Boolean {
        val m = fingerMask(r) ?: return false
        fillFromSurroundings(r, m)
        return true
    }
}
