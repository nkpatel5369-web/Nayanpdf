package npdf.scan

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds the outline of a document (paper, card, whiteboard, book) in a photo or
 * camera frame. Works on luminance only, so it runs on a camera frame's Y plane.
 *
 * Candidates come from two independent sources: the edge map (Canny style) and a
 * bright-region segmentation (paper is usually lighter than what it lies on).
 * Each candidate outline is scored by how much of its perimeter lies on image
 * edges and by its size; the best one above the acceptance threshold wins.
 */
object DocDetector {

    /** Working size for detection (longer side, pixels). */
    const val WORK = 360

    class Result(val quad: Quad, val confidence: Float)

    /**
     * Detects a document in [g]. Coordinates of the result are in [g]'s pixels.
     * [minArea] is the smallest accepted outline as a fraction of the image.
     */
    fun detect(g: Gray, minArea: Float = 0.10f, curvedEdges: Boolean = false): Result? {
        val (small, s) = g.scaledToMax(WORK)
        val img = small.blurred(1)
        val w = img.width; val h = img.height
        val edges = canny(img)
        val dil = dilate(edges, w, h, 1)
        val support = dilate(edges, w, h, 2)
        val cands = ArrayList<Quad>()
        cands += componentQuads(dil, w, h, minArea)
        cands += brightRegionQuads(img, minArea)
        // Straight-line candidates do not suit curved pages (book mode tolerates bowed sides instead).
        if (!curvedEdges) cands += houghQuads(edges, w, h, minArea, edges.count { it }.toFloat() / edges.size)
        var best: Quad? = null
        var bestScore = 0f
        var bestConf = 0f
        val imgArea = (w * h).toFloat()
        // Support is judged against how busy the picture is: in noise or texture every line looks "supported".
        val density = support.count { it }.toFloat() / support.size
        if (density > 0.6f) return null
        for (q in cands) {
            if (!q.isConvex() || q.minAngle() < 40f) continue
            val a = q.area() / imgArea
            if (a < minArea) continue
            val sides = sideSupport(q, support, w, h, if (curvedEdges) 0.06f else 0f)
            // A side lying on the frame border is a document running out of view: allowed for one
            // side at most, and it counts as weak evidence, so noise or a bare frame is never a page.
            val onBorder = sides.count { it < 0f }
            if (onBorder > 1) continue
            for (k in 0 until 4) sides[k] = if (sides[k] < 0f) 0.5f else ((sides[k] - density) / (1f - density)).coerceAtLeast(0f)
            val minSide = sides.min()
            val mean = sides.average().toFloat()
            if (minSide < 0.35f || mean < 0.6f) continue
            // A page differs from what surrounds it: brightness must step across every visible side.
            val contrast = sideContrast(q, img)
            if (contrast.count { it >= 0f && it < 5f } > 0 || contrast.filter { it >= 0f }.average() < 10.0) continue
            val score = a * mean * mean * (0.5f + 0.5f * minSide)
            if (score > bestScore) { bestScore = score; best = q; bestConf = mean }
        }
        val q = best ?: return null
        return Result(q.scaled(1f / s), bestConf)
    }

    /**
     * Refines [q] (in [g]'s pixels) by fitting each side to the strongest edge
     * near it at a higher resolution. With [curved], each side may also bow
     * (book pages): the edge is followed from both ends towards the middle and a
     * smooth bulge is fitted. Sides that cannot be measured keep their position.
     */
    fun refine(g: Gray, q: Quad, curved: Boolean = false): Quad {
        val (img0, s) = g.scaledToMax(1400)
        val img = img0.blurred(1)
        val c = q.scaled(s).corners
        val diag = hypot(img.width.toFloat(), img.height.toFloat())
        val reach = max(4f, diag * 0.012f)
        val fits = arrayOfNulls<FloatArray>(4) // o0, o1, h per side
        for (i in 0 until 4) {
            val a = c[i]; val b = c[(i + 1) % 4]
            val d = b - a; val len = d.len()
            if (len < 20f) continue
            val nx = -d.y / len; val ny = d.x / len
            fun grad(t: Float, o: Float): Float {
                val x = a.x + d.x * t + nx * o; val y = a.y + d.y * t + ny * o
                if (x < 2 || y < 2 || x > img.width - 3 || y > img.height - 3) return 0f
                return img.sample(x + nx, y + ny) - img.sample(x - nx, y - ny)
            }
            fun best(t: Float, lo: Float, hi: Float, sign: Int): Pair<Float, Float>? {
                var bv = 0f; var bo = 0f
                var o = lo
                while (o <= hi) {
                    val gv = grad(t, o)
                    val v = if (sign == 0) abs(gv) else gv * sign
                    if (v > bv) { bv = v; bo = o }
                    o += 0.5f
                }
                return if (bv > 6f) bo to bv else null
            }
            val ts = (3..45).map { it / 48f }
            // Polarity of this side's edge, from the brightness just outside and just inside the page near the corners
            // (where even a curved edge is close to the chord).
            val cxq = c.sumOf { it.x.toDouble() }.toFloat() / 4; val cyq = c.sumOf { it.y.toDouble() }.toFloat() / 4
            val outward = if (nx * ((a.x + b.x) / 2 - cxq) + ny * ((a.y + b.y) / 2 - cyq) >= 0) 1f else -1f
            var diff = 0f
            for (t in floatArrayOf(0.08f, 0.14f, 0.2f, 0.8f, 0.86f, 0.92f)) {
                val bx = a.x + d.x * t; val by = a.y + d.y * t
                val o = reach * 0.8f * outward
                diff += img.sample(bx + nx * o, by + ny * o) - img.sample(bx - nx * o, by - ny * o)
            }
            val sign = if (diff * outward >= 0) 1 else -1
            val straightPts = ArrayList<FloatArray>() // t, offset
            for (t in ts) best(t, -reach, reach, sign)?.let { (o, _) -> straightPts.add(floatArrayOf(t, o)) }
            var fit: FloatArray? = if (straightPts.size >= 8) fitSide(straightPts, false) else null
            if (curved) {
                // Follow the edge from both ends towards the middle, then keep a curve only when it
                // explains the edge clearly better than a straight line.
                val track = ArrayList<FloatArray>()
                val limit = len * 0.15f
                for (dir in intArrayOf(1, -1)) {
                    var t = if (dir > 0) 0.06f else 0.94f
                    var start: Float? = null
                    while (start == null && (if (dir > 0) t < 0.22f else t > 0.78f)) {
                        start = best(t, -reach, reach, sign)?.first
                        if (start == null) t += dir / 64f
                    }
                    var prev = start ?: continue
                    var misses = 0
                    while (if (dir > 0) t <= 0.5f else t >= 0.5f) {
                        val found = best(t, (prev - 3f).coerceAtLeast(-limit), (prev + 3f).coerceAtMost(limit), sign)
                        if (found != null) { track.add(floatArrayOf(t, found.first)); prev = found.first; misses = 0 } else if (++misses > 5) break
                        t += dir / 64f
                    }
                }
                val covers = track.any { it[0] < 0.3f } && track.any { it[0] > 0.7f } && track.any { it[0] in 0.35f..0.65f }
                if (covers && track.size >= 12) {
                    val cf = fitSide(track, true)
                    val sf = fitSide(track, false)
                    if (abs(cf[2]) > len * 0.006f && rms(track, cf) < 0.6f * rms(track, sf)) fit = cf
                }
            }
            if (fit == null) continue
            fits[i] = fit
        }
        // New corners where neighbouring fitted sides cross.
        val lines = Array(4) { i ->
            val f = fits[i] ?: return@Array null
            val a = c[i]; val b = c[(i + 1) % 4]
            val d = b - a; val len = d.len()
            val n = Pt(-d.y / len, d.x / len)
            Geo.fitLine(listOf(a + n * f[0], b + n * f[1]))
        }
        val out = Array(4) { c[it] }
        for (i in 0 until 4) {
            val prev = lines[(i + 3) % 4]; val cur = lines[i]
            if (prev != null && cur != null) {
                val p = prev.intersect(cur)
                if (p != null && p.dist(c[i]) < reach * 3f) out[i] = p
            }
        }
        val bulge = FloatArray(4)
        if (curved) {
            val cx = c.sumOf { it.x.toDouble() }.toFloat() / 4; val cy = c.sumOf { it.y.toDouble() }.toFloat() / 4
            for (i in 0 until 4) {
                val f = fits[i] ?: continue
                val a = c[i]; val b = c[(i + 1) % 4]
                val d = b - a; val len = d.len()
                val nx = -d.y / len; val ny = d.x / len
                val outward = if (nx * ((a.x + b.x) / 2 - cx) + ny * ((a.y + b.y) / 2 - cy) >= 0) 1f else -1f
                if (abs(f[2]) > len * 0.006f) bulge[i] = f[2] * outward
            }
        }
        return Quad(out[0], out[1], out[2], out[3], bulge).scaled(1f / s)
    }

    /**
     * Least-squares fit of offset(t) = o0 (1 - t) + o1 t [+ h 4t(1 - t)], twice
     * dropping points far from the fit. Returns o0, o1, h.
     */
    private fun fitSide(pts: List<FloatArray>, curved: Boolean): FloatArray {
        var use = pts
        var res = floatArrayOf(0f, 0f, 0f)
        repeat(3) { round ->
            val k = if (curved) 3 else 2
            val ata = Array(k) { DoubleArray(k) }; val atb = DoubleArray(k)
            for (p in use) {
                val t = p[0].toDouble()
                val row = if (curved) doubleArrayOf(1 - t, t, 4 * t * (1 - t)) else doubleArrayOf(1 - t, t)
                for (r in 0 until k) { atb[r] += row[r] * p[1]; for (cc in 0 until k) ata[r][cc] += row[r] * row[cc] }
            }
            val x = solve(ata, atb) ?: return res
            res = floatArrayOf(x[0].toFloat(), x[1].toFloat(), if (curved) x[2].toFloat() else 0f)
            if (round == 2) return res
            val resid = use.map { p -> val t = p[0]; abs(p[1] - (res[0] * (1 - t) + res[1] * t + res[2] * 4 * t * (1 - t))) }
            val mad = resid.sorted()[resid.size / 2]
            val cut = max(1.5f, mad * 3f)
            val kept = use.filterIndexed { j, _ -> resid[j] <= cut }
            if (kept.size >= 8) use = kept
        }
        return res
    }

    private fun rms(pts: List<FloatArray>, f: FloatArray): Float {
        var sum = 0f
        for (p in pts) { val t = p[0]; val r = p[1] - (f[0] * (1 - t) + f[1] * t + f[2] * 4 * t * (1 - t)); sum += r * r }
        return sqrt(sum / pts.size)
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { r -> DoubleArray(n + 1) { cc -> if (cc < n) a[r][cc] else b[r] } }
        for (col in 0 until n) {
            var p = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[p][col])) p = r
            if (abs(m[p][col]) < 1e-9) return null
            val t = m[col]; m[col] = m[p]; m[p] = t
            for (r in 0 until n) if (r != col) {
                val f = m[r][col] / m[col][col]
                for (cc in col..n) m[r][cc] -= f * m[col][cc]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    // ------------------------------------------------------------------ edges

    /** Canny edge map: Sobel, non-maximum suppression, hysteresis with automatic thresholds. */
    fun canny(img: Gray): BooleanArray {
        val w = img.width; val h = img.height
        val mag = FloatArray(w * h)
        val dir = ByteArray(w * h)
        val v = img.v
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = (v[i - w + 1] + 2 * v[i + 1] + v[i + w + 1]) - (v[i - w - 1] + 2 * v[i - 1] + v[i + w - 1])
            val gy = (v[i + w - 1] + 2 * v[i + w] + v[i + w + 1]) - (v[i - w - 1] + 2 * v[i - w] + v[i - w + 1])
            mag[i] = hypot(gx, gy)
            val ax = abs(gx); val ay = abs(gy)
            dir[i] = when {
                ay <= ax * 0.4142f -> 0          // horizontal gradient: compare left/right
                ax <= ay * 0.4142f -> 2          // vertical gradient: compare up/down
                (gx > 0) == (gy > 0) -> 1        // diagonal \
                else -> 3                        // diagonal /
            }
        }
        val nms = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val m = mag[i]
            if (m == 0f) continue
            val (a, b) = when (dir[i].toInt()) {
                0 -> mag[i - 1] to mag[i + 1]
                2 -> mag[i - w] to mag[i + w]
                1 -> mag[i - w - 1] to mag[i + w + 1]
                else -> mag[i - w + 1] to mag[i + w - 1]
            }
            if (m >= a && m >= b) nms[i] = m
        }
        // Thresholds relative to the strongest edges in the picture. Text on paper gives stronger edges than
        // paper on a desk, so a share-of-edges threshold could drop a page outline that no text touches.
        val vals = nms.filter { it > 0f }.sorted()
        if (vals.isEmpty()) return BooleanArray(w * h)
        val strong = vals[(vals.size * 0.98f).toInt().coerceAtMost(vals.size - 1)]
        val hi = max(18f, strong * 0.35f)
        val lo = hi * 0.45f
        val out = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        for (i in nms.indices) if (nms[i] >= hi && !out[i]) {
            out[i] = true; stack[sp++] = i
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w; val py = p / w
                for (dy in -1..1) for (dx in -1..1) {
                    val qx = px + dx; val qy = py + dy
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                    val q = qy * w + qx
                    if (!out[q] && nms[q] >= lo) { out[q] = true; stack[sp++] = q }
                }
            }
        }
        return out
    }

    fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return m
        val tmp = BooleanArray(m.size)
        for (y in 0 until h) {
            // Two passes for a horizontal max filter.
            var last = -10000
            for (x in 0 until w) { if (m[y * w + x]) last = x; if (x - last <= r) tmp[y * w + x] = true }
            last = 10000
            for (x in w - 1 downTo 0) { if (m[y * w + x]) last = x; if (last - x <= r) tmp[y * w + x] = true }
        }
        val out = BooleanArray(m.size)
        for (x in 0 until w) {
            var last = -10000
            for (y in 0 until h) { if (tmp[y * w + x]) last = y; if (y - last <= r) out[y * w + x] = true }
            last = 10000
            for (y in h - 1 downTo 0) { if (tmp[y * w + x]) last = y; if (last - y <= r) out[y * w + x] = true }
        }
        return out
    }

    // ------------------------------------------------------------------ candidates

    /** Outlines from connected groups of edge pixels: convex hull, then the largest inscribed quad. */
    private fun componentQuads(e: BooleanArray, w: Int, h: Int, minArea: Float): List<Quad> {
        val label = IntArray(w * h)
        val out = ArrayList<Quad>()
        val stack = IntArray(w * h)
        var next = 0
        val minW = w * 0.2f; val minH = h * 0.2f
        for (start in e.indices) {
            if (!e[start] || label[start] != 0) continue
            next++
            var sp = 0
            stack[sp++] = start; label[start] = next
            // Extreme x per row, for the hull.
            val rowMin = IntArray(h) { Int.MAX_VALUE }; val rowMax = IntArray(h) { -1 }
            var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
            var count = 0
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w; val py = p / w
                count++
                if (px < rowMin[py]) rowMin[py] = px
                if (px > rowMax[py]) rowMax[py] = px
                if (px < x0) x0 = px; if (px > x1) x1 = px; if (py < y0) y0 = py; if (py > y1) y1 = py
                for (dy in -1..1) for (dx in -1..1) {
                    val qx = px + dx; val qy = py + dy
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                    val q = qy * w + qx
                    if (e[q] && label[q] == 0) { label[q] = next; stack[sp++] = q }
                }
            }
            if (x1 - x0 < minW || y1 - y0 < minH || count < 40) continue
            if ((x1 - x0).toFloat() * (y1 - y0) < minArea * w * h) continue
            val pts = ArrayList<Pt>()
            for (y in y0..y1) if (rowMax[y] >= 0) { pts.add(Pt(rowMin[y].toFloat(), y.toFloat())); pts.add(Pt(rowMax[y].toFloat(), y.toFloat())) }
            quadFromPoints(pts)?.let { out.add(it) }
        }
        return out
    }

    /** Outlines from bright regions (Otsu threshold) that do not fill the whole frame. */
    private fun brightRegionQuads(img: Gray, minArea: Float): List<Quad> {
        val w = img.width; val h = img.height
        val t = otsu(img.v)
        val out = ArrayList<Quad>()
        for (bias in floatArrayOf(0f, 12f, -12f)) {
            val m = BooleanArray(w * h) { img.v[it] > t + bias }
            // Largest component.
            val label = IntArray(w * h)
            val stack = IntArray(w * h)
            var next = 0
            var bestCount = 0; var bestLabel = 0
            for (s in m.indices) {
                if (!m[s] || label[s] != 0) continue
                next++
                var sp = 0; stack[sp++] = s; label[s] = next
                var count = 0
                while (sp > 0) {
                    val p = stack[--sp]; count++
                    val px = p % w; val py = p / w
                    if (px > 0 && m[p - 1] && label[p - 1] == 0) { label[p - 1] = next; stack[sp++] = p - 1 }
                    if (px < w - 1 && m[p + 1] && label[p + 1] == 0) { label[p + 1] = next; stack[sp++] = p + 1 }
                    if (py > 0 && m[p - w] && label[p - w] == 0) { label[p - w] = next; stack[sp++] = p - w }
                    if (py < h - 1 && m[p + w] && label[p + w] == 0) { label[p + w] = next; stack[sp++] = p + w }
                }
                if (count > bestCount) { bestCount = count; bestLabel = next }
            }
            if (bestCount < minArea * w * h || bestCount > 0.97f * w * h) continue
            val pts = ArrayList<Pt>()
            for (y in 0 until h) {
                var a = -1; var b = -1
                for (x in 0 until w) if (label[y * w + x] == bestLabel) { if (a < 0) a = x; b = x }
                if (a >= 0) { pts.add(Pt(a.toFloat(), y.toFloat())); pts.add(Pt(b.toFloat(), y.toFloat())) }
            }
            quadFromPoints(pts)?.let { out.add(it) }
        }
        return out
    }

    /**
     * Outlines from the strongest straight lines (Hough transform): two roughly
     * horizontal and two roughly vertical lines make a candidate. This finds the
     * page even when objects touching it merge with its outline in the edge map.
     */
    private fun houghQuads(e: BooleanArray, w: Int, h: Int, minArea: Float, density: Float): List<Quad> {
        val nTheta = 180
        val diag = hypot(w.toFloat(), h.toFloat()).toInt() + 1
        val nRho = 2 * diag + 1
        val acc = IntArray(nTheta * nRho)
        val cosT = FloatArray(nTheta) { Math.cos(Math.PI * it / nTheta).toFloat() }
        val sinT = FloatArray(nTheta) { Math.sin(Math.PI * it / nTheta).toFloat() }
        for (y in 0 until h) for (x in 0 until w) {
            if (!e[y * w + x]) continue
            for (t in 0 until nTheta) {
                val r = (x * cosT[t] + y * sinT[t]).roundToInt() + diag
                acc[t * nRho + r]++
            }
        }
        // Peaks with non-maximum suppression (5 degrees, 6 px).
        // A line must collect clearly more votes than random edges of this density would give it.
        val minVotes = maxOf(25, (min(w, h) * 0.18f).toInt(), (3f * density * min(w, h)).toInt())
        val peaks = ArrayList<IntArray>() // theta, rho, votes
        for (t in 0 until nTheta) for (r in 0 until nRho) {
            val v = acc[t * nRho + r]
            if (v < minVotes) continue
            var isMax = true
            loop@ for (dt in -5..5) for (dr in -6..6) {
                if (dt == 0 && dr == 0) continue
                val tt = (t + dt + nTheta) % nTheta
                // Wrapping theta flips the sign of rho.
                val rr = if (t + dt < 0 || t + dt >= nTheta) 2 * diag - (r + dr) else r + dr
                if (rr < 0 || rr >= nRho) continue
                val o = acc[tt * nRho + rr]
                if (o > v || (o == v && (dt < 0 || (dt == 0 && dr < 0)))) { isMax = false; break@loop }
            }
            if (isMax) peaks.add(intArrayOf(t, r - diag, v))
        }
        peaks.sortByDescending { it[2] }
        // theta near 90 degrees = a near-horizontal line. Lines of text are strong horizontal lines too,
        // so besides the strongest lines keep the outermost ones on each side: page edges are outermost.
        val cx = w / 2f; val cy = h / 2f
        fun pick(list: List<IntArray>): List<IntArray> {
            val top = list.take(24)
            val byOffset = top.sortedBy { cx * cosT[it[0]] + cy * sinT[it[0]] - it[1] }
            return (top.take(8) + byOffset.take(4) + byOffset.takeLast(4)).distinct()
        }
        val hor = pick(peaks.filter { abs(it[0] - 90) <= 40 })
        val ver = pick(peaks.filter { abs(it[0] - 90) > 50 })
        fun line(p: IntArray) = Geo.Line(cosT[p[0]], sinT[p[0]], p[1].toFloat())
        val out = ArrayList<Quad>()
        val margin = 0.05f * max(w, h)
        for (i in hor.indices) for (j in i + 1 until hor.size) for (k in ver.indices) for (l in k + 1 until ver.size) {
            val h1 = line(hor[i]); val h2 = line(hor[j]); val v1 = line(ver[k]); val v2 = line(ver[l])
            val pts = listOfNotNull(h1.intersect(v1), h1.intersect(v2), h2.intersect(v2), h2.intersect(v1))
            if (pts.size < 4) continue
            if (pts.any { it.x < -margin || it.y < -margin || it.x > w + margin || it.y > h + margin }) continue
            val q = Quad.ordered(pts)
            if (q.area() < minArea * w * h) continue
            out.add(q)
        }
        return out
    }

    private fun quadFromPoints(pts: List<Pt>): Quad? {
        if (pts.size < 4) return null
        var hull = Geo.hull(pts)
        if (hull.size < 4) return null
        // Thin the hull so the O(n^2) search stays fast.
        if (hull.size > 120) { val step = hull.size / 120.0; hull = List(120) { hull[(it * step).toInt()] } }
        val q = Geo.maxAreaQuad(hull) ?: return null
        return Quad.ordered(q)
    }

    fun otsu(v: FloatArray): Float {
        val hist = IntArray(256)
        for (x in v) hist[x.roundToInt().coerceIn(0, 255)]++
        val total = v.size
        var sum = 0.0
        for (i in 0 until 256) sum += i.toDouble() * hist[i]
        var sumB = 0.0; var wB = 0; var best = 0.0; var t = 127
        for (i in 0 until 256) {
            wB += hist[i]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = i }
        }
        return t.toFloat()
    }

    /**
     * Mean absolute brightness step across each side (outside strip minus inside
     * strip, 2 to 5 px from the side); -1 for a side on the frame border.
     */
    private fun sideContrast(q: Quad, img: Gray): FloatArray {
        val c = q.corners
        val cx = c.sumOf { it.x.toDouble() }.toFloat() / 4; val cy = c.sumOf { it.y.toDouble() }.toFloat() / 4
        val w = img.width; val h = img.height
        return FloatArray(4) { i ->
            val a = c[i]; val b = c[(i + 1) % 4]
            val len = a.dist(b).coerceAtLeast(1f)
            var nx = -(b.y - a.y) / len; var ny = (b.x - a.x) / len
            if (nx * ((a.x + b.x) / 2 - cx) + ny * ((a.y + b.y) / 2 - cy) < 0) { nx = -nx; ny = -ny }
            var sum = 0f; var n = 0; var border = 0
            for (k in 1..15) {
                val t = k / 16f
                val x = a.x + (b.x - a.x) * t; val y = a.y + (b.y - a.y) * t
                val ox = x + nx * 5; val oy = y + ny * 5
                if (ox < 1 || oy < 1 || ox > w - 2 || oy > h - 2) { border++; continue }
                var out = 0f; var inn = 0f
                for (o in 2..5) { out += img.sample(x + nx * o, y + ny * o); inn += img.sample(x - nx * o, y - ny * o) }
                sum += (out - inn) / 4; n++
            }
            if (border > 8 || n == 0) -1f else kotlin.math.abs(sum / n)
        }
    }

    /**
     * Fraction of each side (top, right, bottom, left) that lies on edge pixels;
     * -1 for a side that runs along the frame border.
     */
    private fun sideSupport(q: Quad, e: BooleanArray, w: Int, h: Int, band: Float = 0f): FloatArray {
        val c = q.corners
        val out = FloatArray(4)
        for (i in 0 until 4) {
            val a = c[i]; val b = c[(i + 1) % 4]
            val len = a.dist(b).coerceAtLeast(1f)
            val nx = -(b.y - a.y) / len; val ny = (b.x - a.x) / len
            val reach = (len * band).toInt()
            val n = max(12, (len / 2).toInt())
            var hit = 0; var tot = 0; var border = 0
            for (k in 1 until n) {
                val t = k.toFloat() / n
                if (t < 0.08f || t > 0.92f) continue
                val x = (a.x + (b.x - a.x) * t).roundToInt(); val y = (a.y + (b.y - a.y) * t).roundToInt()
                tot++
                if (x <= 3 || y <= 3 || x >= w - 4 || y >= h - 4) { border++; continue }
                if (reach == 0) { if (e[y * w + x]) hit++; continue }
                // Curved edges (books): accept an edge within a band on either side of the chord.
                for (o in -reach..reach) {
                    val xx = (x + nx * o).roundToInt(); val yy = (y + ny * o).roundToInt()
                    if (xx in 0 until w && yy in 0 until h && e[yy * w + xx]) { hit++; break }
                }
            }
            out[i] = when {
                tot == 0 -> 0f
                border > tot * 0.6f -> -1f
                else -> hit.toFloat() / (tot - border).coerceAtLeast(1)
            }
        }
        return out
    }

    /** Converts a detection made on a frame of size (fw, fh) rotated by [rotation] degrees to upright coordinates. */
    fun rotateQuad(q: Quad, fw: Int, fh: Int, rotation: Int): Quad {
        fun r(p: Pt): Pt = when (((rotation % 360) + 360) % 360) {
            90 -> Pt(fh - p.y, p.x)
            180 -> Pt(fw - p.x, fh - p.y)
            270 -> Pt(p.y, fw - p.x)
            else -> p
        }
        return Quad.ordered(q.corners.map(::r))
    }

    /** Smallest dimension helper used by callers to judge stability. */
    fun stabilityTolerance(w: Int, h: Int) = min(w, h) * 0.025f
}
