package npdf.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Quad
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Continuous vertical page view with pinch zoom.
 *
 * Every page is first drawn at the fit-to-width resolution (one bitmap per
 * page, rendered as soon as the page comes near the screen). When zoomed in,
 * the visible part is additionally drawn in sharp 512-pixel tiles. Work for
 * pages or zoom levels that are no longer on screen is cancelled.
 */
class PdfView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onTap(page: Int, x: Float, y: Float): Boolean = false
        fun onLongPress(page: Int, x: Float, y: Float) {}
        fun onPageChanged(page: Int) {}
        fun onDrawPage(canvas: Canvas, page: Int, view: PdfView) {}
    }

    /** A tool that takes single-finger touches on a page (drawing, marking areas...). */
    interface Tool {
        /** Return true to take this gesture. Coordinates are page points. */
        fun onDown(page: Int, x: Float, y: Float): Boolean
        fun onMove(page: Int, x: Float, y: Float)
        fun onUp(page: Int, x: Float, y: Float)
        fun onCancel() {}
        fun draw(canvas: Canvas, view: PdfView) {}
    }

    var listener: Listener? = null
    var tool: Tool? = null
        set(v) { field?.onCancel(); field = v; invalidate() }

    private var session: DocSession? = null
    private val margin get() = 8f * resources.displayMetrics.density
    private val gap get() = 10f * resources.displayMetrics.density

    // ------------------------------------------------------------------ layout
    private var cumH = FloatArray(1)     // cumulative page heights in points
    private var maxW = 1f                // widest page in points
    var zoom = 1f; private set           // 1 = page width fits the screen
    private var scrollXpx = 0f
    private var scrollYpx = 0f
    var currentPage = 0; private set

    private val baseScale: Float get() = if (width == 0) 1f else max(0.05f, (width - 2 * margin) / maxW)
    val scale: Float get() = baseScale * zoom

    private fun pageTop(i: Int, s: Float = scale) = margin + cumH[i] * s + i * gap
    private fun pageW(i: Int) = session?.sizes?.getOrNull(i)?.w ?: 1f
    private fun pageH(i: Int) = session?.sizes?.getOrNull(i)?.h ?: 1f
    private fun contentWidth(s: Float = scale) = max(width.toFloat(), maxW * s + 2 * margin)
    private fun contentHeight(s: Float = scale): Float {
        val n = pageCount()
        return if (n == 0) 0f else pageTop(n - 1, s) + pageH(n - 1) * s + margin
    }
    private fun pageLeft(i: Int, s: Float = scale) = (contentWidth(s) - pageW(i) * s) / 2f
    fun pageCount() = session?.sizes?.size ?: 0

    /** The page at content position y (binary search on the page tops). */
    private fun pageAtY(y: Float, s: Float = scale): Int {
        val n = pageCount()
        if (n == 0) return 0
        var lo = 0; var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pageTop(mid, s) <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun setSession(s: DocSession?, page: Int = 0) {
        session?.listeners?.remove(sessionListener)
        session = s
        s?.listeners?.add(sessionListener)
        clearCaches()
        relayout()
        zoom = 1f
        post { goToPage(page) }
    }

    private val sessionListener: (Set<Int>?) -> Unit = { pages ->
        if (pages == null) { clearCaches(); relayout() } else {
            pages.forEach { dropPage(it) }
        }
        invalidate()
    }

    private fun relayout() {
        val sizes = session?.sizes ?: emptyList()
        cumH = FloatArray(sizes.size + 1)
        var mw = 1f
        for (i in sizes.indices) {
            cumH[i + 1] = cumH[i] + sizes[i].h
            mw = max(mw, sizes[i].w)
        }
        maxW = mw
        clampScroll()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val keep = currentPage
        val frac = if (pageCount() > 0) (scrollYpx - pageTop(keep, baseScaleFor(oldw) * zoom)) / max(1f, pageH(keep) * baseScaleFor(oldw) * zoom) else 0f
        super.onSizeChanged(w, h, oldw, oldh)
        clearCaches()
        if (pageCount() > 0) {
            scrollYpx = pageTop(keep) + frac * pageH(keep) * scale
            clampScroll()
        }
    }

    private fun baseScaleFor(w: Int) = if (w == 0) 1f else max(0.05f, (w - 2 * margin) / maxW)

    // --------------------------------------------------------------- navigation

    fun goToPage(page: Int) {
        if (pageCount() == 0) return
        val p = page.coerceIn(0, pageCount() - 1)
        scroller.forceFinished(true)
        scrollYpx = pageTop(p) - margin
        clampScroll()
        updateCurrentPage()
        invalidate()
    }

    /** Scrolls so that page point (x, y) is visible, near the top third of the screen. */
    fun reveal(page: Int, x: Float, y: Float) {
        val vx = pageLeft(page) + x * scale
        val vy = pageTop(page) + y * scale
        if (vy < scrollYpx + height * 0.1f || vy > scrollYpx + height * 0.8f) scrollYpx = vy - height / 3f
        if (vx < scrollXpx || vx > scrollXpx + width) scrollXpx = vx - width / 2f
        clampScroll(); updateCurrentPage(); invalidate()
    }

    fun setZoom(z: Float, fx: Float = width / 2f, fy: Float = height / 2f) {
        val nz = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(nz - zoom) < 1e-4f) return
        val old = scale
        // Keep the content point under the focus where it is.
        val cy = scrollYpx + fy
        val page = pageAtY(cy, old)
        val inPageY = (cy - pageTop(page, old)) / old
        val cx = (scrollXpx + fx - pageLeft(page, old)) / old
        zoom = nz
        scrollYpx = pageTop(page) + inPageY * scale - fy
        scrollXpx = pageLeft(page) + cx * scale - fx
        clampScroll()
        updateCurrentPage()
        invalidate()
    }

    private fun clampScroll() {
        val cw = contentWidth(); val ch = contentHeight()
        scrollXpx = if (cw <= width) 0f else scrollXpx.coerceIn(0f, cw - width)
        scrollYpx = if (ch <= height) 0f else scrollYpx.coerceIn(0f, ch - height)
    }

    private fun updateCurrentPage() {
        if (pageCount() == 0) return
        val p = pageAtY(scrollYpx + height / 3f)
        if (p != currentPage) {
            currentPage = p
            listener?.onPageChanged(p)
        }
    }

    // ------------------------------------------------------------ coordinates

    /** View position to (page, page point), or null outside every page. */
    fun viewToPage(vx: Float, vy: Float): Triple<Int, Float, Float>? {
        if (pageCount() == 0) return null
        val cy = vy + scrollYpx
        val p = pageAtY(cy)
        val x = (vx + scrollXpx - pageLeft(p)) / scale
        val y = (cy - pageTop(p)) / scale
        if (x < 0 || y < 0 || x > pageW(p) || y > pageH(p)) return null
        return Triple(p, x, y)
    }

    /** Page index and point for a view position, clamped to the nearest page edge. */
    fun viewToPageClamped(vx: Float, vy: Float, page: Int): PointF {
        val x = ((vx + scrollXpx - pageLeft(page)) / scale).coerceIn(0f, pageW(page))
        val y = ((vy + scrollYpx - pageTop(page)) / scale).coerceIn(0f, pageH(page))
        return PointF(x, y)
    }

    fun pageToViewX(page: Int, x: Float) = pageLeft(page) + x * scale - scrollXpx
    fun pageToViewY(page: Int, y: Float) = pageTop(page) + y * scale - scrollYpx

    fun pageRectToView(page: Int, x0: Float, y0: Float, x1: Float, y1: Float) =
        RectF(pageToViewX(page, x0), pageToViewY(page, y0), pageToViewX(page, x1), pageToViewY(page, y1))

    fun quadPath(page: Int, q: Quad): Path = Path().apply {
        moveTo(pageToViewX(page, q.ul_x), pageToViewY(page, q.ul_y))
        lineTo(pageToViewX(page, q.ur_x), pageToViewY(page, q.ur_y))
        lineTo(pageToViewX(page, q.lr_x), pageToViewY(page, q.lr_y))
        lineTo(pageToViewX(page, q.ll_x), pageToViewY(page, q.ll_y))
        close()
    }

    // ---------------------------------------------------------- highlights

    /** Search results: page to list of hits, each hit being its quads. */
    var searchHits: Map<Int, List<Array<Quad>>> = emptyMap()
        set(v) { field = v; invalidate() }
    var currentHit: Pair<Int, Int>? = null
        set(v) { field = v; invalidate() }
    /** Selected text (page, quads). */
    var selection: Pair<Int, Array<Quad>>? = null
        set(v) { field = v; invalidate() }

    // --------------------------------------------------------------- rendering

    private class PageBmp(val bitmap: Bitmap, val scale: Float, val version: Int, val docVersion: Int)
    private class Tile(val bitmap: Bitmap, val version: Int, val docVersion: Int)

    private val maxMem = Runtime.getRuntime().maxMemory()
    private val pageCache = object : LruCache<Int, PageBmp>((maxMem / 5).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: Int, value: PageBmp) = value.bitmap.byteCount
    }
    private val tileCache = object : LruCache<String, Tile>((maxMem / 6).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: String, value: Tile) = value.bitmap.byteCount
    }
    private val pending = HashMap<String, Cookie>()
    private val ui = Handler(Looper.getMainLooper())
    private var tileScale = 0f
    private var zooming = false

    private fun clearCaches() {
        synchronized(pending) { pending.values.forEach { it.abort() }; pending.clear() }
        pageCache.evictAll()
        tileCache.evictAll()
    }

    private fun dropPage(p: Int) {
        pageCache.remove(p)
        val prefix = "$p:"
        tileCache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { tileCache.remove(it) }
    }

    /** Resolution of the whole-page bitmap: fit-to-width, capped at about 12 megapixels. */
    private fun pageRenderScale(p: Int): Float {
        val px = pageW(p) * pageH(p) * baseScale * baseScale
        return if (px <= MAX_PAGE_PIXELS) baseScale else baseScale * kotlin.math.sqrt(MAX_PAGE_PIXELS / px)
    }

    private fun requestPage(p: Int) {
        val s = session ?: return
        val key = "page:$p"
        val ps = pageRenderScale(p)
        val cached = pageCache.get(p)
        if (cached != null && cached.version == s.pageVersion(p) && cached.docVersion == structureVersion(s) && abs(cached.scale - ps) < 1e-3f) return
        synchronized(pending) { if (pending.containsKey(key)) return }
        val w = max(1, (pageW(p) * ps).roundToInt()); val h = max(1, (pageH(p) * ps).roundToInt())
        val cookie = Cookie()
        synchronized(pending) { pending[key] = cookie }
        val ver = s.pageVersion(p); val dv = structureVersion(s)
        DocSession.renderPool.execute {
            val bmp = try { s.renderTile(p, ps, 0, 0, w, h, cookie) } catch (e: Throwable) { null }
            ui.post {
                synchronized(pending) { pending.remove(key) }
                if (bmp != null && session === s && abs(pageRenderScale(p) - ps) < 1e-3f && ver == s.pageVersion(p)) {
                    pageCache.put(p, PageBmp(bmp, ps, ver, dv))
                    if (p == firstRenderedPage && firstPageAt == 0L) { firstPageAt = System.nanoTime(); onFirstPage?.invoke() }
                    invalidate()
                }
            }
        }
    }

    /** Structural changes (pages added, removed, reordered) bump the session version without page numbers. */
    private fun structureVersion(s: DocSession) = s.version

    private fun requestTile(p: Int, ts: Float, tx: Int, ty: Int) {
        val s = session ?: return
        val key = "$p:${(ts * 1000).roundToInt()}:$tx:$ty"
        val c = tileCache.get(key)
        if (c != null && c.version == s.pageVersion(p) && c.docVersion == s.version) return
        synchronized(pending) { if (pending.containsKey(key)) return }
        val cookie = Cookie()
        synchronized(pending) { pending[key] = cookie }
        val ver = s.pageVersion(p); val dv = s.version
        val pw = ceil(pageW(p) * ts).toInt(); val ph = ceil(pageH(p) * ts).toInt()
        val w = min(TILE, pw - tx * TILE); val h = min(TILE, ph - ty * TILE)
        if (w <= 0 || h <= 0) { synchronized(pending) { pending.remove(key) }; return }
        DocSession.renderPool.execute {
            if (abs(tileScale - ts) > 1e-4f) { ui.post { synchronized(pending) { pending.remove(key) } }; return@execute }
            val bmp = try { s.renderTile(p, ts, tx * TILE, ty * TILE, w, h, cookie) } catch (e: Throwable) { null }
            ui.post {
                synchronized(pending) { pending.remove(key) }
                if (bmp != null && session === s && ver == s.pageVersion(p)) {
                    tileCache.put(key, Tile(bmp, ver, dv))
                    invalidate()
                }
            }
        }
    }

    /** Cancels tile work for zoom levels that are no longer shown. */
    private fun cancelStaleTiles(ts: Float) {
        val keep = ":${(ts * 1000).roundToInt()}:"
        synchronized(pending) {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (!e.key.startsWith("page:") && !e.key.contains(keep)) { e.value.abort(); it.remove() }
            }
        }
    }

    // Startup timing for the speed test: when the first page appeared.
    var firstRenderedPage = 0
    var firstPageAt = 0L
    var onFirstPage: (() -> Unit)? = null

    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val edgePaint = Paint().apply { color = 0x22000000; style = Paint.Style.STROKE; strokeWidth = 1f }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hitPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.search_hit) }
    private val currentPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.search_current) }
    private val selPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.selection) }
    private val src = Rect()
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(ContextCompat.getColor(context, R.color.viewer_bg))
        val s = session ?: return
        val n = pageCount()
        if (n == 0) return
        val sc = scale
        val first = pageAtY(scrollYpx)
        var last = first
        while (last + 1 < n && pageTop(last + 1) < scrollYpx + height) last++
        // Tiles only once the zoom gesture has finished, and only when they add detail.
        val wantTiles = !zooming && zoom > 1.15f
        val ts = if (wantTiles) sc else 0f
        if (ts != tileScale) { tileScale = ts; cancelStaleTiles(ts) }
        for (p in first..last) {
            val left = pageLeft(p) - scrollXpx
            val top = pageTop(p) - scrollYpx
            val pw = pageW(p) * sc; val ph = pageH(p) * sc
            dst.set(left, top, left + pw, top + ph)
            canvas.drawRect(dst, pagePaint)
            canvas.drawRect(dst, edgePaint)
            val pb = pageCache.get(p)
            if (pb != null) canvas.drawBitmap(pb.bitmap, null, dst, bmpPaint)
            if (pb == null || pb.version != s.pageVersion(p) || pb.docVersion != s.version || abs(pb.scale - pageRenderScale(p)) > 1e-3f) requestPage(p)
            if (wantTiles) drawTiles(canvas, p, left, top, pw, ph, sc)
            drawHighlights(canvas, p)
            listener?.onDrawPage(canvas, p, this)
        }
        // Prefetch the neighbours at the base resolution.
        if (first > 0) requestPage(first - 1)
        if (last + 1 < n) requestPage(last + 1)
        tool?.draw(canvas, this)
    }

    private fun drawTiles(canvas: Canvas, p: Int, left: Float, top: Float, pw: Float, ph: Float, ts: Float) {
        val s = session ?: return
        // Visible part of the page in page-pixel coordinates.
        val vx0 = max(0f, -left); val vy0 = max(0f, -top)
        val vx1 = min(pw, width - left); val vy1 = min(ph, height - top)
        if (vx1 <= vx0 || vy1 <= vy0) return
        val t0x = floor(vx0 / TILE).toInt(); val t1x = floor((vx1 - 1) / TILE).toInt()
        val t0y = floor(vy0 / TILE).toInt(); val t1y = floor((vy1 - 1) / TILE).toInt()
        for (ty in t0y..t1y) for (tx in t0x..t1x) {
            val key = "$p:${(ts * 1000).roundToInt()}:$tx:$ty"
            val t = tileCache.get(key)
            if (t != null && t.version == s.pageVersion(p) && t.docVersion == s.version) {
                val x = left + tx * TILE; val y = top + ty * TILE
                canvas.drawBitmap(t.bitmap, x, y, null)
            } else {
                requestTile(p, ts, tx, ty)
            }
        }
    }

    private fun drawHighlights(canvas: Canvas, p: Int) {
        searchHits[p]?.forEachIndexed { k, hit ->
            val paint = if (currentHit == p to k) currentPaint else hitPaint
            hit.forEach { canvas.drawPath(quadPath(p, it), paint) }
        }
        selection?.let { (sp, quads) -> if (sp == p) quads.forEach { canvas.drawPath(quadPath(p, it), selPaint) } }
    }

    // ---------------------------------------------------------------- gestures

    private val scroller = OverScroller(context)
    private var toolActive = false
    private var toolPage = 0
    private var multiTouch = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollXpx += dx; scrollYpx += dy
            clampScroll(); updateCurrentPage(); invalidate()
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val cw = contentWidth(); val ch = contentHeight()
            scroller.fling(scrollXpx.toInt(), scrollYpx.toInt(), -vx.toInt(), -vy.toInt(),
                0, max(0, (cw - width).toInt()), 0, max(0, (ch - height).toInt()))
            postInvalidateOnAnimation()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val hit = viewToPage(e.x, e.y) ?: return false
            return listener?.onTap(hit.first, hit.second, hit.third) ?: false
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateZoom(if (zoom < 1.5f) 2.5f else 1f, e.x, e.y)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (toolActive || multiTouch) return
            val hit = viewToPage(e.x, e.y) ?: return
            listener?.onLongPress(hit.first, hit.second, hit.third)
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { zooming = true; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            setZoom(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { zooming = false; invalidate() }
    })

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        val start = zoom
        zooming = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { setZoom(start + (target - start) * (it.animatedValue as Float), fx, fy) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { zooming = false; invalidate() }
            })
        }.start()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXpx = scroller.currX.toFloat(); scrollYpx = scroller.currY.toFloat()
            clampScroll(); updateCurrentPage()
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount > 1) {
            if (toolActive) { tool?.onCancel(); toolActive = false }
            multiTouch = true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
        val t = tool
        if (t != null && !multiTouch) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = viewToPage(e.x, e.y)
                    if (hit != null && t.onDown(hit.first, hit.second, hit.third)) {
                        toolActive = true; toolPage = hit.first
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> if (toolActive) {
                    val pt = viewToPageClamped(e.x, e.y, toolPage)
                    t.onMove(toolPage, pt.x, pt.y); invalidate(); return true
                }
                MotionEvent.ACTION_UP -> if (toolActive) {
                    val pt = viewToPageClamped(e.x, e.y, toolPage)
                    toolActive = false
                    t.onUp(toolPage, pt.x, pt.y); invalidate(); return true
                }
                MotionEvent.ACTION_CANCEL -> if (toolActive) { toolActive = false; t.onCancel(); invalidate(); return true }
            }
        }
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        session?.listeners?.remove(sessionListener)
        synchronized(pending) { pending.values.forEach { it.abort() }; pending.clear() }
    }

    companion object {
        const val TILE = 512
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 8f
        const val MAX_PAGE_PIXELS = 12_000_000f
    }
}
