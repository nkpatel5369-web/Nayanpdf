package npdf.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import npdf.scan.Pt
import npdf.scan.Quad
import npdf.scan.Warp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Crop editor: the photo with the document outline. Drag a corner to move it,
 * or an edge's middle handle to move that edge; with [curved] on, the middle
 * handle bends the edge instead (for curved book pages). A magnifier shows the
 * area under the finger.
 */
@SuppressLint("ViewConstructor")
class CropView(ctx: Context, private val photo: Bitmap, photoW: Int, photoH: Int, start: Quad?) : View(ctx) {
    /** Photo-space size the outline refers to (the full photo, which [photo] may show smaller). */
    private val pw = photoW.toFloat(); private val ph = photoH.toFloat()
    var quad: Quad = start ?: inset(Quad.full(photoW, photoH))
        private set
    var curved = false
        set(v) { field = v; if (!v) quad = quad.copy(bulge = FloatArray(4)); invalidate() }

    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ios.color(ctx, R.color.scan_outline); style = Paint.Style.STROKE; strokeWidth = Ios.dp(ctx, 2.5f) }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ios.color(ctx, R.color.scan_outline); style = Paint.Style.STROKE; strokeWidth = Ios.dp(ctx, 2.5f) }
    private val loupeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = Ios.dp(ctx, 3f) }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ios.color(ctx, R.color.scan_outline); strokeWidth = Ios.dp(ctx, 1.5f) }
    private val img = RectF()
    private var drag = -1 // 0..3 corners, 4..7 edge middles
    private var touch: Pt? = null
    private val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val loupe = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@CropView.shader }

    private fun inset(q: Quad): Quad { val dx = pw * 0.06f; val dy = ph * 0.06f; return Quad(Pt(dx, dy), Pt(pw - dx, dy), Pt(pw - dx, ph - dy), Pt(dx, ph - dy)) }

    fun set(q: Quad?) { quad = q ?: Quad.full(pw.toInt(), ph.toInt()); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val pad = Ios.dp(context, 24f)
        val s = min((w - 2 * pad) / photo.width, (h - 2 * pad) / photo.height)
        val iw = photo.width * s; val ih = photo.height * s
        img.set((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2)
    }

    private fun toView(p: Pt) = Pt(img.left + p.x / pw * img.width(), img.top + p.y / ph * img.height())
    private fun toPhoto(x: Float, y: Float) = Pt(((x - img.left) / img.width() * pw).coerceIn(0f, pw), ((y - img.top) / img.height() * ph).coerceIn(0f, ph))

    private fun handles(): List<Pt> {
        val c = quad.corners.map(::toView)
        val mids = (0 until 4).map { toView(Warp.edgeMid(quad, it)) }
        return c + mids
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(photo, null, img, null)
        val v = quad.corners.map(::toView)
        val path = outlinePath()
        // Dim outside the outline.
        val outside = Path().apply { addRect(img, Path.Direction.CW); addPath(path); fillType = Path.FillType.EVEN_ODD }
        canvas.drawPath(outside, dim)
        canvas.drawPath(path, line)
        val r = Ios.dp(context, 11f)
        handles().forEachIndexed { i, p ->
            if (i < 4) { canvas.drawCircle(p.x, p.y, r, knob); canvas.drawCircle(p.x, p.y, r, knobRing) }
            else { canvas.drawCircle(p.x, p.y, r * 0.7f, knob); canvas.drawCircle(p.x, p.y, r * 0.7f, knobRing) }
        }
        touch?.let { t -> drawLoupe(canvas, t) }
        if (v.isEmpty()) return
    }

    private fun outlinePath(): Path {
        val path = Path()
        val n = 24
        for (i in 0 until 4) {
            val a = quad.corners[i]; val b = quad.corners[(i + 1) % 4]
            val mid = Warp.edgeMid(quad, i)
            // Quadratic through the edge's middle point.
            val ctrl = Pt(2 * mid.x - (a.x + b.x) / 2, 2 * mid.y - (a.y + b.y) / 2)
            for (k in 0..n) {
                val t = k.toFloat() / n
                val x = (1 - t) * (1 - t) * a.x + 2 * t * (1 - t) * ctrl.x + t * t * b.x
                val y = (1 - t) * (1 - t) * a.y + 2 * t * (1 - t) * ctrl.y + t * t * b.y
                val p = toView(Pt(x, y))
                if (i == 0 && k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
        }
        path.close()
        return path
    }

    private fun drawLoupe(canvas: Canvas, t: Pt) {
        val size = Ios.dp(context, 96f)
        val zoom = 2.5f
        // Keep the loupe away from the finger: top-left, or top-right when the finger is there.
        val cx = if (t.x < width / 2f) width - size * 0.75f else size * 0.75f
        val cy = size * 0.75f
        val photoPt = toPhoto(t.x, t.y)
        val sx = img.width() / photo.width
        val m = Matrix()
        m.setScale(sx * zoom, sx * zoom)
        m.postTranslate(cx - photoPt.x / pw * photo.width * sx * zoom, cy - photoPt.y / ph * photo.height * sx * zoom)
        shader.setLocalMatrix(m)
        canvas.drawCircle(cx, cy, size / 2, loupe)
        canvas.drawCircle(cx, cy, size / 2, loupeRing)
        canvas.drawLine(cx - 10, cy, cx + 10, cy, cross); canvas.drawLine(cx, cy - 10, cx, cy + 10, cross)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val h = handles()
                var best = -1; var bd = Ios.dp(context, 36f)
                h.forEachIndexed { i, p -> val d = hypot(p.x - e.x, p.y - e.y); if (d < bd) { bd = d; best = i } }
                drag = best
                if (drag < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                touch = Pt(e.x, e.y)
                Ios.tick(this)
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag < 0) return false
                val p = toPhoto(e.x, e.y)
                touch = toView(p)
                val c = quad.corners.toMutableList()
                if (drag < 4) {
                    c[drag] = p
                    quad = Quad(c[0], c[1], c[2], c[3], quad.bulge)
                } else {
                    val i = drag - 4
                    if (curved) quad = Warp.withEdgeMid(quad, i, p)
                    else {
                        // Move the edge along its normal.
                        val mid = Warp.edgeMid(quad, i)
                        val n = Warp.outwardNormals(quad)[i]
                        val d = (p.x - mid.x) * n.x + (p.y - mid.y) * n.y
                        val a = c[i]; val b = c[(i + 1) % 4]
                        c[i] = Pt((a.x + n.x * d).coerceIn(0f, pw), (a.y + n.y * d).coerceIn(0f, ph))
                        c[(i + 1) % 4] = Pt((b.x + n.x * d).coerceIn(0f, pw), (b.y + n.y * d).coerceIn(0f, ph))
                        quad = Quad(c[0], c[1], c[2], c[3], quad.bulge)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = -1; touch = null; invalidate() }
        }
        return true
    }

    /** False when the outline is too small or folded over itself to be usable. */
    fun valid() = quad.isConvex() && quad.area() > pw * ph * 0.01f
}

/**
 * Drawing on a corrected page, for clean-up (eraser) and markup (pen and
 * shapes). Strokes are kept in page fractions so they apply to the full-size
 * page exactly as drawn on the preview.
 */
@SuppressLint("ViewConstructor")
class PaintView(ctx: Context, private val page: Bitmap) : View(ctx) {
    enum class Tool { ERASE, INK, RECT, ELLIPSE, LINE, ARROW, PICK }

    var tool = Tool.ERASE
    var color = Color.RED
    /** Brush radius or pen width as a fraction of the page width. */
    var size = 0.02f
    /** Eraser colour (null: fill from the surroundings). */
    var eraseColor: Int? = null
    var onPicked: ((Int) -> Unit)? = null

    val edits = mutableListOf<Edit>()
    private val redo = mutableListOf<Edit>()
    private val img = RectF()
    private var current = mutableListOf<Pt>()
    private var start: Pt? = null
    private var end: Pt? = null
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val eraseShow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0x99FFFFFF.toInt() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = min(w.toFloat() / page.width, h.toFloat() / page.height)
        val iw = page.width * s; val ih = page.height * s
        img.set((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2)
    }

    private fun frac(x: Float, y: Float) = Pt(((x - img.left) / img.width()).coerceIn(0f, 1f), ((y - img.top) / img.height()).coerceIn(0f, 1f))
    private fun view(p: Pt) = Pt(img.left + p.x * img.width(), img.top + p.y * img.height())

    fun undo() { if (edits.isNotEmpty()) { redo.add(edits.removeAt(edits.size - 1)); invalidate() } }
    fun redo() { if (redo.isNotEmpty()) { edits.add(redo.removeAt(redo.size - 1)); invalidate() } }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(page, null, img, null)
        val w = img.width()
        fun drawEdit(e: Edit) {
            when (e) {
                is Edit.Erase -> {
                    eraseShow.strokeWidth = e.radius * 2 * w
                    eraseShow.color = e.color?.let { (it and 0xFFFFFF) or 0xCC000000.toInt() } ?: 0xB3FFFFFF.toInt()
                    val path = Path(); e.pts.forEachIndexed { i, p -> val v = view(p); if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y) }
                    if (e.pts.size == 1) { val v = view(e.pts[0]); canvas.drawPoint(v.x, v.y, eraseShow) } else canvas.drawPath(path, eraseShow)
                }
                is Edit.Ink -> {
                    stroke.color = e.color; stroke.strokeWidth = e.width * w
                    val path = Path(); e.pts.forEachIndexed { i, p -> val v = view(p); if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y) }
                    canvas.drawPath(path, stroke)
                }
                is Edit.Shape -> {
                    stroke.color = e.color; stroke.strokeWidth = e.width * w
                    val a = view(e.a); val b = view(e.b)
                    val r = RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
                    when (e.type) {
                        Edit.RECT -> canvas.drawRect(r, stroke)
                        Edit.ELLIPSE -> canvas.drawOval(r, stroke)
                        else -> {
                            canvas.drawLine(a.x, a.y, b.x, b.y, stroke)
                            if (e.type == Edit.ARROW) {
                                val ang = Math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()); val len = stroke.strokeWidth * 5
                                for (d in doubleArrayOf(2.6, -2.6)) canvas.drawLine(b.x, b.y, (b.x + len * Math.cos(ang + d)).toFloat(), (b.y + len * Math.sin(ang + d)).toFloat(), stroke)
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        edits.forEach(::drawEdit)
        pending()?.let(::drawEdit)
    }

    private fun pending(): Edit? = when (tool) {
        Tool.ERASE -> if (current.isEmpty()) null else Edit.Erase(current.toList(), size, eraseColor)
        Tool.INK -> if (current.isEmpty()) null else Edit.Ink(current.toList(), color, size * 0.4f)
        Tool.RECT, Tool.ELLIPSE, Tool.LINE, Tool.ARROW -> {
            val a = start; val b = end
            if (a == null || b == null) null
            else Edit.Shape(when (tool) { Tool.RECT -> Edit.RECT; Tool.ELLIPSE -> Edit.ELLIPSE; Tool.LINE -> Edit.LINE; else -> Edit.ARROW }, a, b, color, size * 0.3f)
        }
        Tool.PICK -> null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = frac(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (tool == Tool.PICK) {
                    val x = (p.x * (page.width - 1)).toInt(); val y = (p.y * (page.height - 1)).toInt()
                    onPicked?.invoke(page.getPixel(x, y) or 0xFF000000.toInt())
                    return true
                }
                current = mutableListOf(p); start = p; end = p
            }
            MotionEvent.ACTION_MOVE -> { if (tool == Tool.PICK) return true; current.add(p); end = p }
            MotionEvent.ACTION_UP -> {
                if (tool == Tool.PICK) return true
                end = p
                pending()?.let { edits.add(it); redo.clear() }
                current = mutableListOf(); start = null; end = null
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { current = mutableListOf(); start = null; end = null }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
