package npdf.app

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar

/**
 * A page under Material 3's large top app bar: the title starts large and collapses
 * into the bar as the content scrolls, and the bar takes the surface-container colour
 * once content passes beneath it.
 */
class LargeTitlePage(ctx: Context, title: String) {
    val root = CoordinatorLayout(ctx).apply { fitsSystemWindows = true }
    val appBar = AppBarLayout(ctx).apply { fitsSystemWindows = true; isLiftOnScroll = true; setBackgroundColor(Color.TRANSPARENT) }
    private val collapsing = CollapsingToolbarLayout(ctx, null, com.google.android.material.R.attr.collapsingToolbarLayoutLargeStyle).apply {
        fitsSystemWindows = true
        this.title = title
    }
    /** The bar: add menu items or a navigation icon here. */
    val toolbar = MaterialToolbar(ctx).apply { setBackgroundColor(Color.TRANSPARENT) }
    val scroll = NestedScrollView(ctx).apply { isFillViewport = true; clipToPadding = false }
    val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, Kit.dpi(ctx, 24)) }

    init {
        val tv = TypedValue()
        val barH = if (ctx.theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true))
            TypedValue.complexToDimensionPixelSize(tv.data, ctx.resources.displayMetrics) else Kit.dpi(ctx, 64)
        val largeH = if (ctx.theme.resolveAttribute(com.google.android.material.R.attr.collapsingToolbarLayoutLargeSize, tv, true))
            TypedValue.complexToDimensionPixelSize(tv.data, ctx.resources.displayMetrics) else Kit.dpi(ctx, 152)
        collapsing.addView(toolbar, CollapsingToolbarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH).apply {
            collapseMode = CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PIN
        })
        appBar.addView(collapsing, AppBarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, largeH).apply {
            scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED or
                AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
        })
        root.addView(appBar, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            behavior = AppBarLayout.ScrollingViewBehavior()
        })
        appBar.liftOnScrollTargetViewId = View.generateViewId().also { scroll.id = it }
    }

    fun setTitle(s: String) { collapsing.title = s }

    fun add(v: View, marginDp: Int = 0) = content.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        val m = Kit.dpi(content.context, marginDp); setMargins(m, 0, m, 0)
    })

    fun section(header: String? = null, footer: String? = null) = Kit.Section(content.context, content, header, footer)
}
