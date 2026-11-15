package npdf.app

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView

/**
 * A page with a large title that scrolls away under a quiet bar: once the big
 * title has scrolled out of view, the bar shows the title in small type and a
 * hairline appears beneath it.
 */
class LargeTitlePage(ctx: Context, title: String) {
    val root = FrameLayout(ctx)
    val scroll = NestedScrollView(ctx).apply { isFillViewport = true; clipToPadding = false }
    val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val bigTitle = Ios.text(ctx, title, 32f, 700).apply {
        letterSpacing = -0.02f
        setPadding(Ios.dpi(ctx, 20), Ios.dpi(ctx, 4), Ios.dpi(ctx, 20), Ios.dpi(ctx, 6))
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    val bar = FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT) }
    private val smallTitle = Ios.text(ctx, title, 17f, 600).apply { gravity = Gravity.CENTER; alpha = 0f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val hair = Ios.hairline(ctx).apply { alpha = 0f }
    /** Buttons on the right of the bar. */
    val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var topInset = 0
    private val barH = Ios.dpi(ctx, 44)

    init {
        content.addView(bigTitle)
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val barInner = FrameLayout(ctx)
        barInner.addView(smallTitle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH).apply { marginStart = Ios.dpi(ctx, 90); marginEnd = Ios.dpi(ctx, 90) })
        barInner.addView(actions, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, barH, Gravity.END).apply { marginEnd = Ios.dpi(ctx, 8) })
        bar.addView(barInner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM))
        bar.addView(hair, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hair.layoutParams.height, Gravity.BOTTOM))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topInset = bars.top
            bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply { height = barH + topInset }
            bar.setPadding(bars.left, 0, bars.right, 0)
            scroll.setPadding(bars.left, barH + topInset, bars.right, Ios.dpi(ctx, 24))
            insets
        }
        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, y, _, _ -> update(y) })
    }

    private fun update(y: Int) {
        val t = ((y - bigTitle.height * 0.4f) / (bigTitle.height * 0.6f)).coerceIn(0f, 1f)
        smallTitle.alpha = t
        hair.alpha = t
        bar.setBackgroundColor(if (t > 0f) Ios.color(bar.context, R.color.bar) else android.graphics.Color.TRANSPARENT)
    }

    fun setTitle(s: String) { bigTitle.text = s; smallTitle.text = s }

    fun add(v: View, marginDp: Int = 0) = content.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        val m = Ios.dpi(content.context, marginDp); setMargins(m, 0, m, 0)
    })

    fun section(header: String? = null, footer: String? = null) = Ios.Section(content.context, content, header, footer)
}
