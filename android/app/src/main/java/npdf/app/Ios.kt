package npdf.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * N PDF's interface kit: grouped lists, rows, switches, segmented controls,
 * search fields, a tab bar, a status banner and the colour and type helpers they
 * share. Everything follows the same principles: clarity (legible type, 44 dp
 * touch targets), deference (content first, quiet chrome) and depth (layers,
 * gentle motion, haptic confirmation).
 */
object Ios {

    fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    fun dpi(ctx: Context, v: Int) = dp(ctx, v.toFloat()).toInt()
    fun color(ctx: Context, res: Int) = ContextCompat.getColor(ctx, res)

    fun font(ctx: Context, weight: Int): Typeface = when {
        weight >= 700 -> ResourcesCompat.getFont(ctx, R.font.inter_bold)
        weight >= 600 -> ResourcesCompat.getFont(ctx, R.font.inter_semibold)
        weight >= 500 -> ResourcesCompat.getFont(ctx, R.font.inter_medium)
        else -> ResourcesCompat.getFont(ctx, R.font.inter_regular)
    } ?: Typeface.DEFAULT

    fun rounded(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 0f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp)
        if (strokeColor != null) setStroke(dp(ctx, strokeDp).toInt().coerceAtLeast(1), strokeColor)
    }

    /** A background that darkens while pressed, as table cells do. */
    fun cellBackground(ctx: Context, radii: FloatArray? = null): Drawable {
        fun shape(c: Int) = GradientDrawable().apply { setColor(c); if (radii != null) cornerRadii = radii }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(color(ctx, R.color.bg_cell_pressed)))
            addState(intArrayOf(), shape(color(ctx, R.color.bg_cell)))
        }
    }

    fun text(ctx: Context, s: CharSequence, sizeSp: Float, weight: Int = 400, colorRes: Int = R.color.label): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = font(ctx, weight)
        setTextColor(color(ctx, colorRes))
    }

    /** A one-pixel separator. */
    fun hairline(ctx: Context, vertical: Boolean = false, insetStartDp: Int = 0): View = View(ctx).apply {
        setBackgroundColor(color(ctx, R.color.separator))
        val px = maxOf(1, (ctx.resources.displayMetrics.density * 0.5f).toInt())
        layoutParams = if (vertical) LinearLayout.LayoutParams(px, ViewGroup.LayoutParams.MATCH_PARENT)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px).apply { marginStart = dpi(ctx, insetStartDp) }
    }

    /** Light confirmation, the way a switch or a shutter feels. */
    fun tick(v: View) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    /** Text buttons dim while pressed rather than showing a ripple. */
    @SuppressLint("ClickableViewAccessibility")
    fun dimOnPress(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.alpha = 0.35f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().alpha(1f).setDuration(160).start()
            }
            false
        }
    }

    /** A plain text button in the tint colour (bars, sheets, alerts). */
    fun textButton(ctx: Context, label: String, bold: Boolean = false, destructive: Boolean = false, onClick: (View) -> Unit): TextView =
        text(ctx, label, 17f, if (bold) 600 else 400, if (destructive) R.color.red else R.color.tint).apply {
            gravity = Gravity.CENTER
            minHeight = dpi(ctx, 44)
            setPadding(dpi(ctx, 8), 0, dpi(ctx, 8), 0)
            isClickable = true; isFocusable = true
            contentDescription = label
            dimOnPress(this)
            setOnClickListener(onClick)
        }

    /** Square tile with a white glyph, as used beside rows in grouped lists. */
    fun iconTile(ctx: Context, icon: Int, tileColor: Int, sizeDp: Int = 30): View = FrameLayout(ctx).apply {
        background = rounded(ctx, tileColor, sizeDp * 0.24f)
        val img = ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        val pad = dpi(ctx, (sizeDp * 0.18f).toInt())
        addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(pad, pad, pad, pad) })
        layoutParams = LinearLayout.LayoutParams(dpi(ctx, sizeDp), dpi(ctx, sizeDp))
    }

    // ------------------------------------------------------------------ grouped lists

    /** One inset, rounded group of rows with an optional header above and footer below. */
    class Section(val ctx: Context, parent: LinearLayout, header: String?, footer: String?) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, color(ctx, R.color.bg_cell), 12f)
            clipToOutline = true
        }
        private val rows = ArrayList<View>()

        init {
            val m = dpi(ctx, 16)
            if (header != null) parent.addView(text(ctx, header.uppercase(), 13f, 400, R.color.label2).apply {
                setPadding(m * 2, dpi(ctx, 22), m, dpi(ctx, 7)); letterSpacing = 0.02f
            })
            parent.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(m, if (header == null) dpi(ctx, 20) else 0, m, 0)
            })
            if (footer != null) parent.addView(text(ctx, footer, 13f, 400, R.color.label2).apply { setPadding(m * 2, dpi(ctx, 7), m * 2, 0) })
        }

        private fun add(v: View, separatorInset: Int) {
            if (rows.isNotEmpty()) box.addView(hairline(ctx, insetStartDp = separatorInset))
            rows.add(v)
            box.addView(v)
        }

        /** A row: optional icon tile, title, optional value on the right, and a chevron when it leads somewhere. */
        fun row(
            title: String, icon: Int? = null, tile: Int = R.color.gray, value: String? = null, chevron: Boolean = true,
            destructive: Boolean = false, tintTitle: Boolean = false, onClick: ((Row) -> Unit)? = null,
        ): Row {
            val r = Row(ctx, title, icon, color(ctx, tile), value, chevron && onClick != null, destructive, tintTitle)
            if (onClick != null) { r.view.isClickable = true; r.view.setOnClickListener { onClick(r) } }
            add(r.view, if (icon != null) 58 else 16)
            return r
        }

        /** A row with a switch on the right. */
        fun toggle(title: String, checked: Boolean, icon: Int? = null, tile: Int = R.color.green, subtitle: String? = null, onChange: (Boolean) -> Unit): SwitchCompat {
            val sw = SwitchCompat(ctx).apply { isChecked = checked; contentDescription = title }
            val r = Row(ctx, title, icon, color(ctx, tile), null, false, false, false, subtitle, accessory = sw)
            r.view.setOnClickListener { sw.toggle() }
            sw.setOnCheckedChangeListener { b, v -> tick(b); onChange(v) }
            add(r.view, if (icon != null) 58 else 16)
            return sw
        }

        fun custom(v: View, separatorInset: Int = 16) = add(v, separatorInset)
    }

    class Row(
        ctx: Context, title: String, icon: Int?, tileColor: Int, value: String?, chevron: Boolean,
        destructive: Boolean, tintTitle: Boolean, subtitle: String? = null, accessory: View? = null,
    ) {
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(ctx, 48)
            background = cellBackground(ctx)
            setPadding(dpi(ctx, 16), dpi(ctx, 6), dpi(ctx, 14), dpi(ctx, 6))
            contentDescription = title
        }
        val title: TextView = text(ctx, title, 17f, 400, if (destructive) R.color.red else if (tintTitle) R.color.tint else R.color.label).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val value: TextView? = value?.let { text(ctx, it, 17f, 400, R.color.label2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE } }

        init {
            if (icon != null) {
                view.addView(iconTile(ctx, icon, tileColor))
                (view.getChildAt(0).layoutParams as LinearLayout.LayoutParams).marginEnd = dpi(ctx, 12)
            }
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(this.title)
            if (subtitle != null) texts.addView(text(ctx, subtitle, 13f, 400, R.color.label2))
            view.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            this.value?.let { view.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(ctx, 8) }) }
            accessory?.let { view.addView(it) }
            if (chevron) view.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(color(ctx, R.color.label3))
            }, LinearLayout.LayoutParams(dpi(ctx, 18), dpi(ctx, 18)).apply { marginStart = dpi(ctx, 6) })
        }

        fun setValue(s: String) { value?.text = s }
    }

    // ------------------------------------------------------------------ controls

    /**
     * A segmented control: a grey track with a raised white segment for the
     * choice. A RadioGroup underneath, so selection logic stays standard.
     */
    fun segmented(ctx: Context, items: List<String>, checked: Int): RadioGroup = RadioGroup(ctx).apply {
        orientation = RadioGroup.HORIZONTAL
        background = rounded(ctx, color(ctx, R.color.fill), 9f)
        val p = dpi(ctx, 2); setPadding(p, p, p, p)
        items.forEachIndexed { i, t ->
            val rb = RadioButton(ctx).apply {
                id = View.generateViewId()
                text = t
                buttonDrawable = null
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = font(ctx, 500)
                setTextColor(color(ctx, R.color.label))
                minHeight = dpi(ctx, 32)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), rounded(ctx, color(ctx, R.color.bg_cell), 7f, color(ctx, R.color.separator), 0.5f))
                    addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
                }
                isChecked = i == checked
            }
            addView(rb, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        setOnCheckedChangeListener { g, _ -> tick(g) }
    }

    /** A vertical list of choices with a checkmark on the chosen one. */
    fun checklist(ctx: Context, items: List<String>, checked: Int): RadioGroup = RadioGroup(ctx).apply {
        orientation = RadioGroup.VERTICAL
        background = rounded(ctx, color(ctx, R.color.bg_cell), 10f)
        val check = ContextCompat.getDrawable(ctx, R.drawable.ic_check)!!.mutate().apply { setTint(color(ctx, R.color.tint)) }
        items.forEachIndexed { i, t ->
            if (i > 0) addView(hairline(ctx, insetStartDp = 14))
            val rb = RadioButton(ctx).apply {
                id = View.generateViewId()
                text = t
                buttonDrawable = null
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = font(ctx, 400)
                setTextColor(color(ctx, R.color.label))
                minHeight = dpi(ctx, 44)
                setPadding(dpi(ctx, 14), 0, dpi(ctx, 12), 0)
                background = cellBackground(ctx)
                val sl = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), check)
                    addState(intArrayOf(), GradientDrawable().apply { setSize(check.intrinsicWidth, check.intrinsicHeight); setColor(Color.TRANSPARENT) })
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, sl, null)
                isChecked = i == checked
            }
            addView(rb, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setOnCheckedChangeListener { g, _ -> tick(g) }
    }

    /** A rounded search field with a magnifier; [onChange] runs as the user types. */
    fun searchField(ctx: Context, hint: String, onChange: (String) -> Unit): Pair<View, EditText> {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(ctx, color(ctx, R.color.fill), 10f)
            setPadding(dpi(ctx, 8), 0, dpi(ctx, 8), 0)
        }
        box.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(color(ctx, R.color.label2)) },
            LinearLayout.LayoutParams(dpi(ctx, 18), dpi(ctx, 18)))
        val et = EditText(ctx).apply {
            this.hint = hint
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = font(ctx, 400)
            setTextColor(color(ctx, R.color.label))
            setHintTextColor(color(ctx, R.color.label2))
            setPadding(dpi(ctx, 6), dpi(ctx, 8), dpi(ctx, 6), dpi(ctx, 8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
            })
        }
        box.addView(et, LinearLayout.LayoutParams(0, dpi(ctx, 38), 1f))
        return box to et
    }

    // ------------------------------------------------------------------ tab bar

    /** A bottom tab bar: icon over a small label, the selected tab in the tint colour. */
    class TabBar(ctx: Context) : LinearLayout(ctx) {
        private val items = LinkedHashMap<Int, LinearLayout>()
        var onSelect: ((Int) -> Unit)? = null
        var selected: Int = View.NO_ID
            private set

        init {
            orientation = VERTICAL
            setBackgroundColor(color(ctx, R.color.bar))
            addView(hairline(ctx))
            addView(LinearLayout(ctx).apply { orientation = HORIZONTAL }, LayoutParams(LayoutParams.MATCH_PARENT, dpi(ctx, 52)))
        }

        private val row get() = getChildAt(1) as LinearLayout

        fun add(id: Int, label: String, icon: Int) {
            val ctx = context
            val tint = ContextCompat.getColorStateList(ctx, R.color.tab_item)
            val item = LinearLayout(ctx).apply {
                this.id = id
                orientation = VERTICAL
                gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                contentDescription = label
                addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = tint; isDuplicateParentStateEnabled = true }, LayoutParams(dpi(ctx, 25), dpi(ctx, 25)))
                addView(text(ctx, label, 10.5f, 500).apply { setTextColor(tint); isDuplicateParentStateEnabled = true; setPadding(0, dpi(ctx, 2), 0, 0); gravity = Gravity.CENTER_HORIZONTAL; maxLines = 1 },
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                setOnClickListener { select(id, true) }
            }
            items[id] = item
            row.addView(item, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        fun select(id: Int, fromUser: Boolean = false) {
            if (fromUser && id != selected) tick(this)
            for ((k, v) in items) v.isSelected = k == id
            val changed = id != selected
            selected = id
            if (changed || fromUser) onSelect?.invoke(id)
        }
    }

    // ------------------------------------------------------------------ status banner

    /**
     * A short status message in a rounded banner near the bottom of the screen,
     * which fades away by itself (instead of a system toast).
     */
    fun banner(a: Activity, message: String, icon: Int? = null, millis: Long = 2400) {
        val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val ctx = a
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(ctx, color(ctx, R.color.hud_bg), 22f)
            setPadding(dpi(ctx, 18), dpi(ctx, 12), dpi(ctx, 18), dpi(ctx, 12))
            elevation = dp(ctx, 8f)
            alpha = 0f
            translationY = dp(ctx, 16f)
            if (icon != null) addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(color(ctx, R.color.hud_text)) },
                LinearLayout.LayoutParams(dpi(ctx, 20), dpi(ctx, 20)).apply { marginEnd = dpi(ctx, 10) })
            addView(text(ctx, message, 15f, 500, R.color.hud_text).apply { maxLines = 4 })
            contentDescription = message
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dpi(ctx, 110); leftMargin = dpi(ctx, 24); rightMargin = dpi(ctx, 24)
        }
        (root as? FrameLayout)?.addView(box, lp) ?: root.addView(box)
        box.animate().alpha(1f).translationY(0f).setDuration(220).start()
        box.postDelayed({ box.animate().alpha(0f).setDuration(260).withEndAction { root.removeView(box) }.start() }, millis)
    }
}
