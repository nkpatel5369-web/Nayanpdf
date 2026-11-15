package npdf.scan

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Flattens the document outlined by a [Quad] into an upright rectangle:
 * a projective (perspective) correction, plus a smooth sideways displacement
 * for edges that curve (book pages), so curved lines of text come out straight.
 */
object Warp {

    /** Longest side of a corrected page (A4 at 300 dpi). */
    const val MAX_SIDE = 3508

    /**
     * Size of the corrected image, capped at [maxSide]: the real page's proportions recovered
     * from the perspective when the photo's size ([imgW] x [imgH]) is known, otherwise the
     * outline's own proportions.
     */
    fun outputSize(q: Quad, maxSide: Int = MAX_SIDE, imgW: Int = 0, imgH: Int = 0): Pair<Int, Int> {
        val (w, h) = if (imgW > 0 && imgH > 0) q.pageSize(imgW, imgH) else q.naturalSize()
        val s = minOf(1f, maxSide / max(w, h).coerceAtLeast(1f))
        return max(16, (w * s).roundToInt()) to max(16, (h * s).roundToInt())
    }

    fun warp(src: Raster, q: Quad, outW: Int, outH: Int): Raster {
        val unit = listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))
        val hm = Homography.fromPoints(unit, q.corners)
        val normals = outwardNormals(q)
        val hasBulge = q.bulge.any { it != 0f }
        val out = Raster(outW, outH)
        for (y in 0 until outH) {
            val v = (y + 0.5f) / outH
            val pv = 4 * v * (1 - v)
            for (x in 0 until outW) {
                val u = (x + 0.5f) / outW
                var p = hm.map(u, v)
                if (hasBulge) {
                    val pu = 4 * u * (1 - u)
                    val top = q.bulge[0] * pu; val right = q.bulge[1] * pv
                    val bottom = q.bulge[2] * pu; val left = q.bulge[3] * pv
                    val dx = (1 - v) * top * normals[0].x + v * bottom * normals[2].x + (1 - u) * left * normals[3].x + u * right * normals[1].x
                    val dy = (1 - v) * top * normals[0].y + v * bottom * normals[2].y + (1 - u) * left * normals[3].y + u * right * normals[1].y
                    p = Pt(p.x + dx, p.y + dy)
                }
                out.px[y * outW + x] = src.sample(p.x - 0.5f, p.y - 0.5f)
            }
        }
        return out
    }

    /** Corrects to the page's own proportions (capped at [maxSide]). */
    fun warp(src: Raster, q: Quad, maxSide: Int = MAX_SIDE): Raster {
        val (w, h) = outputSize(q, maxSide, src.width, src.height)
        return warp(src, q, w, h)
    }

    /** Unit normals of the edges top, right, bottom, left, pointing away from the outline's centre. */
    fun outwardNormals(q: Quad): List<Pt> {
        val c = q.corners
        val cx = c.sumOf { it.x.toDouble() }.toFloat() / 4; val cy = c.sumOf { it.y.toDouble() }.toFloat() / 4
        return List(4) { i ->
            val a = c[i]; val b = c[(i + 1) % 4]
            val d = b - a; val len = d.len().coerceAtLeast(1e-3f)
            var n = Pt(-d.y / len, d.x / len)
            val mx = (a.x + b.x) / 2 - cx; val my = (a.y + b.y) / 2 - cy
            if (n.x * mx + n.y * my < 0) n = Pt(-n.x, -n.y)
            n
        }
    }

    /** The point on edge [i] (0 top, 1 right, 2 bottom, 3 left) at its middle, including the bulge. */
    fun edgeMid(q: Quad, i: Int): Pt {
        val c = q.corners
        val a = c[i]; val b = c[(i + 1) % 4]
        val n = outwardNormals(q)[i]
        return Pt((a.x + b.x) / 2 + n.x * q.bulge[i], (a.y + b.y) / 2 + n.y * q.bulge[i])
    }

    /** [q] with edge [i]'s bulge set so that its middle passes through [p]. */
    fun withEdgeMid(q: Quad, i: Int, p: Pt): Quad {
        val c = q.corners
        val a = c[i]; val b = c[(i + 1) % 4]
        val n = outwardNormals(q)[i]
        val h = (p.x - (a.x + b.x) / 2) * n.x + (p.y - (a.y + b.y) / 2) * n.y
        val nb = q.bulge.copyOf(); nb[i] = h
        return q.copy(bulge = nb)
    }
}
