package npdf.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuItemCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.snackbar.Snackbar

/**
 * N PDF's interface kit on Material 3, as stock Android draws it: list items with
 * supporting text, Material switches, segmented buttons, a search bar, the navigation
 * bar and snackbars. Colours come from the theme (Material You on Android 12 and
 * later); type is the system font in Material's type scale.
 */
object Kit {

    fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    fun dpi(ctx: Context, v: Int) = dp(ctx, v.toFloat()).toInt()
    fun color(ctx: Context, res: Int) = ContextCompat.getColor(ctx, res)

    /** A colour from the theme, such as com.google.android.material.R.attr.colorPrimary. */
    fun attr(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
    }

    /** The system font (Roboto, or the phone maker's) at a weight. */
    fun font(@Suppress("UNUSED_PARAMETER") ctx: Context, weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight.coerceIn(100, 900), false)
        else if (weight >= 500) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT

    fun rounded(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 0f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp)
        if (strokeColor != null) setStroke(dp(ctx, strokeDp).toInt().coerceAtLeast(1), strokeColor)
    }

    /** A pressable background: Material's ripple over [fill] (transparent by default). */
    fun cellBackground(ctx: Context, radii: FloatArray? = null, fill: Int = Color.TRANSPARENT): Drawable {
        val shape = GradientDrawable().apply { setColor(fill); if (radii != null) cornerRadii = radii }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); if (radii != null) cornerRadii = radii }
        val ripple = ColorStateList.valueOf((attr(ctx, com.google.android.material.R.attr.colorOnSurface) and 0x00FFFFFF) or 0x1F000000)
        return RippleDrawable(ripple, shape, mask)
    }

    /** Text in the system font. Sizes follow Material's type scale (16 sp body, 14 sp supporting). */
    fun text(ctx: Context, s: CharSequence, sizeSp: Float, weight: Int = 400, colorRes: Int = R.color.label): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, when (sizeSp) { 17f -> 16f; 15f -> 14f; 13f -> 14f; else -> sizeSp })
        typeface = font(ctx, if (weight >= 600) 500 else weight)
        setTextColor(color(ctx, colorRes))
    }

    /** A divider (outline variant, one density-independent pixel). */
    fun hairline(ctx: Context, vertical: Boolean = false, insetStartDp: Int = 0): View = View(ctx).apply {
        setBackgroundColor(color(ctx, R.color.separator))
        val px = maxOf(1, dpi(ctx, 1))
        layoutParams = if (vertical) LinearLayout.LayoutParams(px, ViewGroup.LayoutParams.MATCH_PARENT)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px).apply { marginStart = dpi(ctx, insetStartDp) }
    }

    /** Light haptic confirmation. */
    fun tick(v: View) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    /**
     * A Material button: a filled button for the main action ([bold]), otherwise a text
     * button; destructive actions in the error colour.
     */
    fun textButton(ctx: Context, label: String, bold: Boolean = false, destructive: Boolean = false, onClick: (View) -> Unit): TextView {
        val b = if (bold) MaterialButton(ctx) else MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle)
        return b.apply {
            text = label
            isAllCaps = false
            contentDescription = label
            if (destructive) {
                if (bold) backgroundTintList = ColorStateList.valueOf(color(ctx, R.color.red))
                else setTextColor(color(ctx, R.color.red))
            }
            setOnClickListener(onClick)
        }
    }

    /** A tonal button (Material's medium emphasis), optionally with an icon. */
    fun tonalButton(ctx: Context, label: String, icon: Int? = null, onClick: (View) -> Unit): MaterialButton =
        MaterialButton(ctx, null, R.attr.npdfTonalButtonStyle).apply {
            text = label; isAllCaps = false; contentDescription = label
            if (icon != null) setIconResource(icon)
            setOnClickListener(onClick)
        }

    /** An icon button (48 dp touch target, 24 dp icon). */
    fun iconButton(ctx: Context, icon: Int, description: String, tint: Int = color(ctx, R.color.label2), onClick: (View) -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            val p = dpi(ctx, 12); setPadding(p, p, p, p)
            background = cellBackground(ctx, FloatArray(8) { dp(ctx, 24f) })
            contentDescription = description
            isClickable = true; isFocusable = true
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(dpi(ctx, 48), dpi(ctx, 48))
        }

    /** A list item's leading icon (24 dp, on-surface-variant). [tileColor] is not used in Material lists. */
    fun iconTile(ctx: Context, icon: Int, @Suppress("UNUSED_PARAMETER") tileColor: Int, sizeDp: Int = 24): View = ImageView(ctx).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color(ctx, R.color.label2))
        layoutParams = LinearLayout.LayoutParams(dpi(ctx, sizeDp), dpi(ctx, sizeDp))
    }

    // ------------------------------------------------------------------ lists

    /**
     * A group of list items: a category title in the primary colour, the items on a
     * rounded container (as in Android's settings) and an optional footer below.
     */
    class Section(val ctx: Context, parent: LinearLayout, header: String?, footer: String?) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, color(ctx, R.color.bg_cell), 24f)
            clipToOutline = true
        }
        private val rows = ArrayList<View>()

        init {
            val m = dpi(ctx, 16)
            if (header != null) parent.addView(text(ctx, header, 14f, 500, R.color.tint).apply {
                setPadding(m * 2, dpi(ctx, 20), m, dpi(ctx, 8))
                isFocusable = true; contentDescription = header
            })
            parent.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(m, if (header == null) dpi(ctx, 16) else 0, m, 0)
            })
            if (footer != null) parent.addView(text(ctx, footer, 14f, 400, R.color.label2).apply { setPadding(m * 2, dpi(ctx, 8), m * 2, 0) })
        }

        private fun add(v: View, @Suppress("UNUSED_PARAMETER") separatorInset: Int) {
            rows.add(v)
            box.addView(v)
        }

        /** An item: leading icon, headline and, below it, its current value as supporting text. */
        fun row(
            title: String, icon: Int? = null, tile: Int = R.color.gray, value: String? = null, @Suppress("UNUSED_PARAMETER") chevron: Boolean = true,
            destructive: Boolean = false, tintTitle: Boolean = false, onClick: ((Row) -> Unit)? = null,
        ): Row {
            val r = Row(ctx, title, icon, value, destructive, tintTitle)
            if (onClick != null) { r.view.isClickable = true; r.view.isFocusable = true; r.view.setOnClickListener { onClick(r) } }
            add(r.view, 0)
            return r
        }

        /** An item with a Material switch at the end. */
        fun toggle(title: String, checked: Boolean, icon: Int? = null, @Suppress("UNUSED_PARAMETER") tile: Int = R.color.green, subtitle: String? = null, onChange: (Boolean) -> Unit): SwitchCompat {
            val sw = MaterialSwitch(ctx).apply { isChecked = checked; contentDescription = title }
            val r = Row(ctx, title, icon, subtitle, false, false, accessory = sw)
            r.view.isClickable = true
            r.view.setOnClickListener { sw.toggle() }
            sw.setOnCheckedChangeListener { b, v -> tick(b); onChange(v) }
            add(r.view, 0)
            return sw
        }

        fun custom(v: View, separatorInset: Int = 16) = add(v, separatorInset)
    }

    class Row(
        ctx: Context, title: String, icon: Int?, supporting: String?,
        destructive: Boolean, tintTitle: Boolean, accessory: View? = null,
    ) {
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(ctx, if (supporting != null) 72 else 56)
            background = cellBackground(ctx)
            setPadding(dpi(ctx, 16), dpi(ctx, 10), dpi(ctx, 16), dpi(ctx, 10))
            contentDescription = title
        }
        val title: TextView = text(ctx, title, 16f, 400, if (destructive) R.color.red else if (tintTitle) R.color.tint else R.color.label).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val value: TextView? = supporting?.let { text(ctx, it, 14f, 400, R.color.label2).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END } }

        init {
            if (icon != null) view.addView(iconTile(ctx, icon, 0).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dpi(ctx, 16)
            })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(this.title)
            this.value?.let { texts.addView(it) }
            view.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            accessory?.let { view.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(ctx, 16) }) }
        }

        fun setValue(s: String) { value?.text = s }
    }

    // ------------------------------------------------------------------ controls

    /**
     * Material 3 segmented buttons: an outlined pill split into segments, the chosen
     * one filled with the secondary container and marked with a check. A RadioGroup
     * underneath, so selection logic stays standard.
     */
    fun segmented(ctx: Context, items: List<String>, checked: Int): RadioGroup = RadioGroup(ctx).apply {
        orientation = RadioGroup.HORIZONTAL
        val r = dp(ctx, 20f)
        background = rounded(ctx, Color.TRANSPARENT, 20f, color(ctx, R.color.label3), 1f)
        val p = dpi(ctx, 1); setPadding(p, p, p, p)
        val check = ContextCompat.getDrawable(ctx, R.drawable.ic_check)!!.mutate().apply {
            setTint(color(ctx, R.color.on_tint_soft)); setBounds(0, 0, dpi(ctx, 18), dpi(ctx, 18))
        }
        items.forEachIndexed { i, t ->
            if (i > 0) addView(View(ctx).apply { setBackgroundColor(color(ctx, R.color.label3)) }, RadioGroup.LayoutParams(dpi(ctx, 1), ViewGroup.LayoutParams.MATCH_PARENT))
            val radii = when {
                items.size == 1 -> FloatArray(8) { r }
                i == 0 -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                i == items.lastIndex -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                else -> FloatArray(8)
            }
            val rb = RadioButton(ctx).apply {
                id = View.generateViewId()
                text = t
                buttonDrawable = null
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = font(ctx, 500)
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(color(ctx, R.color.on_tint_soft), color(ctx, R.color.label))))
                minHeight = dpi(ctx, 38)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(dpi(ctx, 8), 0, dpi(ctx, 8), 0)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), GradientDrawable().apply { setColor(color(ctx, R.color.tint_soft)); cornerRadii = radii })
                    addState(intArrayOf(), cellBackground(ctx, radii))
                }
                compoundDrawablePadding = dpi(ctx, 6)
                fun mark() = setCompoundDrawablesRelative(if (isChecked) check else null, null, null, null)
                setOnCheckedChangeListener { _, _ -> mark() }
                isChecked = i == checked
                mark()
            }
            addView(rb, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        setOnCheckedChangeListener { g, _ -> tick(g) }
    }

    /** A vertical list of choices with Material radio buttons. */
    fun checklist(ctx: Context, items: List<String>, checked: Int): RadioGroup = RadioGroup(ctx).apply {
        orientation = RadioGroup.VERTICAL
        items.forEachIndexed { i, t ->
            val rb = MaterialRadioButton(ctx).apply {
                id = View.generateViewId()
                text = t
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(color(ctx, R.color.label))
                minHeight = dpi(ctx, 48)
                setPadding(dpi(ctx, 12), 0, dpi(ctx, 8), 0)
                isChecked = i == checked
            }
            addView(rb, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setOnCheckedChangeListener { g, _ -> tick(g) }
    }

    /** Material 3 search bar: a full pill with a leading search icon; [onChange] runs as the user types. */
    fun searchField(ctx: Context, hint: String, onChange: (String) -> Unit): Pair<View, EditText> {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(ctx, color(ctx, R.color.bg_elevated), 28f)
            setPadding(dpi(ctx, 16), 0, dpi(ctx, 16), 0)
            minimumHeight = dpi(ctx, 56)
        }
        box.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(color(ctx, R.color.label)) },
            LinearLayout.LayoutParams(dpi(ctx, 24), dpi(ctx, 24)))
        val et = EditText(ctx).apply {
            this.hint = hint
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(color(ctx, R.color.label))
            setHintTextColor(color(ctx, R.color.label2))
            setPadding(dpi(ctx, 16), dpi(ctx, 8), dpi(ctx, 8), dpi(ctx, 8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
            })
        }
        box.addView(et, LinearLayout.LayoutParams(0, dpi(ctx, 56), 1f))
        return box to et
    }

    // ------------------------------------------------------------------ navigation bar

    /** The Material 3 navigation bar (icon in an active-indicator pill, label below). */
    class TabBar(ctx: Context) : BottomNavigationView(ctx) {
        var onSelect: ((Int) -> Unit)? = null
        var selected: Int = View.NO_ID
            private set

        init {
            labelVisibilityMode = LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener { item ->
                val changed = item.itemId != selected
                if (changed) tick(this)
                selected = item.itemId
                onSelect?.invoke(item.itemId)
                true
            }
        }

        fun add(id: Int, label: String, icon: Int) {
            val item = menu.add(Menu.NONE, id, menu.size(), label).setIcon(icon)
            MenuItemCompat.setContentDescription(item, label)
        }

        fun select(id: Int, @Suppress("UNUSED_PARAMETER") fromUser: Boolean = false) {
            if (selectedItemId == id && selected == id) onSelect?.invoke(id)
            else selectedItemId = id
            selected = id
        }
    }

    // ------------------------------------------------------------------ snackbar

    /**
     * A short message in a Material snackbar at the bottom of the screen, above the
     * screen's bottom bar when it has one (a view tagged [ANCHOR]).
     */
    fun banner(a: Activity, message: String, @Suppress("UNUSED_PARAMETER") icon: Int? = null, millis: Long = 2400) {
        val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val sb = Snackbar.make(root, message, if (millis > 3000) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
        root.findViewWithTag<View>(ANCHOR)?.takeIf { it.isShown }?.let { sb.anchorView = it }
        (sb.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text))?.maxLines = 4
        sb.show()
    }

    /** Tag for the bottom bar a snackbar should sit above. */
    const val ANCHOR = "snackbar_anchor"
}
