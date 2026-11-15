package npdf.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import npdf.scan.Pt
import npdf.scan.Quad

/**
 * Draws the document outline found in the live camera picture. Corners glide
 * to each new detection, so the outline moves smoothly rather than jumping.
 */
class ScanOverlay(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.color(ctx, R.color.scan_fill); style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.color(ctx, R.color.scan_outline); style = Paint.Style.STROKE; strokeWidth = Kit.dp(ctx, 3f); strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.color(ctx, R.color.scan_outline); style = Paint.Style.STROKE; strokeWidth = Kit.dp(ctx, 2.5f) }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = Kit.dp(ctx, 2f); pathEffect = DashPathEffect(floatArrayOf(Kit.dp(ctx, 8f), Kit.dp(ctx, 6f)), 0f) }

    /** Size of the camera frame (upright) that quads refer to. */
    var frameW = 0; var frameH = 0
    private var shown: List<Pt>? = null
    private var target: List<Pt>? = null
    private var anim: ValueAnimator? = null
    var bookDivider = false
    var cardGuide = false

    /** Area of the view where the camera picture is drawn (fit to the view, centred). */
    fun contentRect(): RectF {
        if (frameW == 0 || frameH == 0 || width == 0) return RectF(0f, 0f, width.toFloat(), height.toFloat())
        val s = minOf(width.toFloat() / frameW, height.toFloat() / frameH)
        val w = frameW * s; val h = frameH * s
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun toView(p: Pt): Pt {
        val r = contentRect()
        val s = r.width() / frameW.coerceAtLeast(1)
        return Pt(r.left + p.x * s, r.top + p.y * s)
    }

    fun show(q: Quad?) {
        val next = q?.corners
        if (next == null) { if (shown == null && target == null) return; target = null; shown = null; anim?.cancel(); invalidate(); return }
        val t0 = target
        // The same outline as last frame (within half a pixel): nothing to redraw.
        if (t0 != null && shown != null && t0.zip(next).all { (a, b) -> kotlin.math.abs(a.x - b.x) < 0.5f && kotlin.math.abs(a.y - b.y) < 0.5f }) return
        val from = shown ?: next
        target = next
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 120
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                shown = from.zip(next) { p, n -> Pt(p.x + (n.x - p.x) * t, p.y + (n.y - p.y) * t) }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = contentRect()
        if (bookDivider) canvas.drawLine(r.centerX(), r.top + r.height() * 0.08f, r.centerX(), r.bottom - r.height() * 0.08f, guide)
        if (cardGuide) {
            // An ID-1 card (85.6 x 54 mm) outline as a framing guide.
            val w = r.width() * 0.78f; val h = w / 1.586f
            canvas.drawRoundRect(RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2), Kit.dp(context, 14f), Kit.dp(context, 14f), guide)
        }
        val pts = shown?.map(::toView) ?: return
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until 4) lineTo(pts[i].x, pts[i].y); close() }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, line)
        val rad = Kit.dp(context, 7f)
        for (p in pts) { canvas.drawCircle(p.x, p.y, rad, dot); canvas.drawCircle(p.x, p.y, rad, dotRing) }
    }
}

/** The round shutter: a white ring and button, with a progress ring while auto-capture waits for a steady picture. */
class ShutterButton(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = Kit.dp(ctx, 4f) }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.color(ctx, R.color.tint); style = Paint.Style.STROKE; strokeWidth = Kit.dp(ctx, 4f); strokeCap = Paint.Cap.ROUND }
    var progress = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }

    init {
        isClickable = true; isFocusable = true
        contentDescription = "Capture"
    }

    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - ring.strokeWidth
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx, cy, r - Kit.dp(context, 6f) - if (isPressed) Kit.dp(context, 3f) else 0f, inner)
        if (progress > 0f) canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * progress, false, prog)
    }
}
