package npdf.scan

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Float) = Pt(x * s, y * s)
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
    fun len() = hypot(x, y)
}

/**
 * A document outline: four corners in reading order (top-left, top-right,
 * bottom-right, bottom-left) and an optional sideways bulge for each edge
 * (top, right, bottom, left), in pixels, for pages that curve (books).
 * A positive bulge pushes the middle of the edge away from the centre.
 */
data class Quad(
    val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt,
    val bulge: FloatArray = FloatArray(4),
) {
    val corners: List<Pt> get() = listOf(tl, tr, br, bl)

    fun area(): Float {
        val c = corners
        var s = 0f
        for (i in 0 until 4) { val a = c[i]; val b = c[(i + 1) % 4]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2f
    }

    fun scaled(s: Float) = Quad(tl * s, tr * s, br * s, bl * s, FloatArray(4) { bulge[it] * s })

    /**
     * The outline pulled in towards its centre by [fraction] of its size (0.006: 0.3 % on each side),
     * so a corrected page does not carry the sliver of background blended into its edge pixels.
     */
    fun shrunk(fraction: Float): Quad {
        val cx = (tl.x + tr.x + br.x + bl.x) / 4; val cy = (tl.y + tr.y + br.y + bl.y) / 4
        val k = 1f - fraction
        fun p(q: Pt) = Pt(cx + (q.x - cx) * k, cy + (q.y - cy) * k)
        return Quad(p(tl), p(tr), p(br), p(bl), FloatArray(4) { bulge[it] * k })
    }

    fun isConvex(): Boolean {
        val c = corners
        var sign = 0
        for (i in 0 until 4) {
            val a = c[i]; val b = c[(i + 1) % 4]; val d = c[(i + 2) % 4]
            val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
            val sg = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (sg == 0) return false
            if (sign == 0) sign = sg else if (sg != sign) return false
        }
        return true
    }

    /** Smallest interior angle in degrees. */
    fun minAngle(): Float {
        val c = corners
        var m = 180f
        for (i in 0 until 4) {
            val p = c[(i + 3) % 4]; val q = c[i]; val r = c[(i + 1) % 4]
            val a = p - q; val b = r - q
            val cos = ((a.x * b.x + a.y * b.y) / (a.len() * b.len()).coerceAtLeast(1e-6f)).coerceIn(-1f, 1f)
            m = minOf(m, Math.toDegrees(acos(cos.toDouble())).toFloat())
        }
        return m
    }

    /** Largest corner movement compared with [o]. */
    fun maxCornerDistance(o: Quad): Float = corners.zip(o.corners).maxOf { (a, b) -> a.dist(b) }

    /** Output size (width, height) from the outline's longest edges, before any scaling. */
    fun naturalSize(): Pair<Float, Float> {
        val w = max(tl.dist(tr), bl.dist(br))
        val h = max(tl.dist(bl), tr.dist(br))
        return w to h
    }

    /**
     * Width / height of the real page, recovered from the perspective of this outline in a
     * photo of [imgW] x [imgH] (Zhang and He, "Whiteboard scanning and image enhancement",
     * Digital Signal Processing 17, 2007). The camera's principal point is taken as the
     * picture's centre; the focal length is estimated from the outline, or a typical phone
     * lens is assumed when the outline is too close to a parallelogram to tell (the aspect
     * then hardly depends on it). Null when the estimate cannot be trusted.
     */
    fun pageAspect(imgW: Int, imgH: Int): Float? {
        if (imgW <= 0 || imgH <= 0) return null
        val u0 = imgW / 2.0; val v0 = imgH / 2.0
        fun h(p: Pt) = doubleArrayOf(p.x - u0, p.y - v0, 1.0)
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val m1 = h(tl); val m2 = h(tr); val m3 = h(bl); val m4 = h(br)
        val c14 = cross(m1, m4)
        val k2 = dot(c14, m3) / dot(cross(m2, m4), m3)
        val k3 = dot(c14, m2) / dot(cross(m3, m4), m2)
        if (!k2.isFinite() || !k3.isFinite() || k2 <= 0 || k3 <= 0) return null
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        val diag = hypot(imgW.toDouble(), imgH.toDouble())
        // The focal length can be read only when both pairs of opposite edges converge: with a pair
        // (nearly) parallel, as when the page is tilted one way only, the estimate is noise. Then a
        // phone's main lens is assumed: about 0.6 x the diagonal (26 mm equivalent).
        val converging = minOf(edgeAngle(tl, tr, bl, br), edgeAngle(tl, bl, tr, br)) >= 1.0
        val f2 = -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2])
        val ff = if (converging && f2.isFinite() && f2 > 0 && sqrt(f2) in 0.35 * diag..2.0 * diag) f2 else (0.6 * diag) * (0.6 * diag)
        val r2 = (n2[0] * n2[0] + n2[1] * n2[1] + ff * n2[2] * n2[2]) / (n3[0] * n3[0] + n3[1] * n3[1] + ff * n3[2] * n3[2])
        if (!r2.isFinite() || r2 <= 0) return null
        val r = sqrt(r2).toFloat()
        // A result far from the outline's own proportions means the assumptions do not hold
        // (for example a photo cropped before import): keep the edge lengths then.
        val (w0, h0) = naturalSize()
        val ratio = r / (w0 / h0.coerceAtLeast(1f))
        return if (ratio in 0.6f..1.6f) r else null
    }

    /** Angle in degrees between the lines a1-a2 and b1-b2 (0 when parallel). */
    private fun edgeAngle(a1: Pt, a2: Pt, b1: Pt, b2: Pt): Double {
        val a = Math.atan2((a2.y - a1.y).toDouble(), (a2.x - a1.x).toDouble())
        val b = Math.atan2((b2.y - b1.y).toDouble(), (b2.x - b1.x).toDouble())
        val d = abs(a - b) % Math.PI
        return Math.toDegrees(minOf(d, Math.PI - d))
    }

    /**
     * Output size (width, height) of the corrected page in a photo of [imgW] x [imgH]: the
     * real page's proportions where the perspective tells them, at the resolution of the
     * outline's longest edges (the shortened direction is stretched, never the other shrunk).
     */
    fun pageSize(imgW: Int, imgH: Int): Pair<Float, Float> {
        val (w0, h0) = naturalSize()
        val r = pageAspect(imgW, imgH) ?: return w0 to h0
        return if (w0 / h0 > r) w0 to w0 / r else h0 * r to h0
    }

    override fun equals(other: Any?): Boolean =
        other is Quad && tl == other.tl && tr == other.tr && br == other.br && bl == other.bl && bulge.contentEquals(other.bulge)

    override fun hashCode(): Int = corners.hashCode() * 31 + bulge.contentHashCode()

    companion object {
        /** The whole image as a quad. */
        fun full(w: Int, h: Int) = Quad(Pt(0f, 0f), Pt(w.toFloat(), 0f), Pt(w.toFloat(), h.toFloat()), Pt(0f, h.toFloat()))

        /** Orders four points as top-left, top-right, bottom-right, bottom-left. */
        fun ordered(pts: List<Pt>): Quad {
            require(pts.size == 4)
            val cx = pts.sumOf { it.x.toDouble() }.toFloat() / 4; val cy = pts.sumOf { it.y.toDouble() }.toFloat() / 4
            // Sort by angle around the centre, starting from the top-left quadrant, clockwise on screen.
            val sorted = pts.sortedBy { Math.atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
            // atan2 order on screen (y down): -pi..pi goes left -> top -> right -> bottom (clockwise).
            // Pick the start as the point with the smallest x + y.
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            val r = List(4) { sorted[(start + it) % 4] }
            return Quad(r[0], r[1], r[2], r[3])
        }
    }
}

/** A 3x3 projective transform, row-major. */
class Homography(val m: DoubleArray) {
    fun map(x: Float, y: Float): Pt {
        val w = m[6] * x + m[7] * y + m[8]
        return Pt(((m[0] * x + m[1] * y + m[2]) / w).toFloat(), ((m[3] * x + m[4] * y + m[5]) / w).toFloat())
    }

    companion object {
        /** The transform taking each of [src] to the matching point of [dst] (four pairs). */
        fun fromPoints(src: List<Pt>, dst: List<Pt>): Homography {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = src[i].x.toDouble(); val y = src[i].y.toDouble()
                val u = dst[i].x.toDouble(); val v = dst[i].y.toDouble()
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            // Gaussian elimination with partial pivoting.
            for (c in 0 until 8) {
                var p = c
                for (r in c + 1 until 8) if (abs(a[r][c]) > abs(a[p][c])) p = r
                val t = a[c]; a[c] = a[p]; a[p] = t
                val piv = a[c][c]
                require(abs(piv) > 1e-12) { "Degenerate outline" }
                for (k in c until 9) a[c][k] /= piv
                for (r in 0 until 8) if (r != c) {
                    val f = a[r][c]
                    if (f != 0.0) for (k in c until 9) a[r][k] -= f * a[c][k]
                }
            }
            return Homography(doubleArrayOf(a[0][8], a[1][8], a[2][8], a[3][8], a[4][8], a[5][8], a[6][8], a[7][8], 1.0))
        }
    }
}

object Geo {
    private fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /** Convex hull (Andrew's monotone chain), counter-clockwise in a y-up sense. */
    fun hull(points: List<Pt>): List<Pt> {
        val pts = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (pts.size < 3) return pts
        val lower = ArrayList<Pt>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<Pt>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** Area of triangle abc. */
    private fun tri(a: Pt, b: Pt, c: Pt) = abs(cross(a, b, c)) / 2f

    /**
     * The quadrilateral of largest area whose corners are vertices of the convex
     * polygon [h] (vertices in order). O(n^2) with monotone pointers.
     */
    fun maxAreaQuad(h: List<Pt>): List<Pt>? {
        val n = h.size
        if (n < 4) return null
        var best = -1f
        var bi = 0; var bj = 1; var bk = 2; var bl = 3
        for (i in 0 until n) {
            var j = i + 1
            var l = i + 3
            for (k in i + 2 until i + n - 1) {
                // Best j between i and k.
                while (j + 1 < k && tri(h[i % n], h[(j + 1) % n], h[k % n]) >= tri(h[i % n], h[j % n], h[k % n])) j++
                if (l <= k) l = k + 1
                while (l + 1 < i + n && tri(h[k % n], h[(l + 1) % n], h[i % n]) >= tri(h[k % n], h[l % n], h[i % n])) l++
                if (l >= i + n) continue
                val area = tri(h[i % n], h[j % n], h[k % n]) + tri(h[k % n], h[l % n], h[i % n])
                if (area > best) { best = area; bi = i % n; bj = j % n; bk = k % n; bl = l % n }
            }
        }
        return listOf(h[bi], h[bj], h[bk], h[bl])
    }

    /** A line a*x + b*y = c with (a, b) of unit length. */
    data class Line(val a: Float, val b: Float, val c: Float) {
        fun intersect(o: Line): Pt? {
            val det = a * o.b - b * o.a
            if (abs(det) < 1e-6f) return null
            return Pt((c * o.b - b * o.c) / det, (a * o.c - c * o.a) / det)
        }
        fun distance(p: Pt) = abs(a * p.x + b * p.y - c)
    }

    /** Total least squares line through [pts]. */
    fun fitLine(pts: List<Pt>): Line? {
        if (pts.size < 2) return null
        val mx = pts.sumOf { it.x.toDouble() } / pts.size
        val my = pts.sumOf { it.y.toDouble() } / pts.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in pts) { val dx = p.x - mx; val dy = p.y - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        // Normal = eigenvector of the smaller eigenvalue of the scatter matrix.
        val theta = 0.5 * Math.atan2(2 * sxy, sxx - syy) // direction of the line
        val nx = -Math.sin(theta); val ny = Math.cos(theta)
        val len = sqrt(nx * nx + ny * ny)
        val a = (nx / len).toFloat(); val b = (ny / len).toFloat()
        return Line(a, b, (a * mx + b * my).toFloat())
    }
}
