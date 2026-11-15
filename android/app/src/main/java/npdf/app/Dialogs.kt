package npdf.app

import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Dialogs that suspend until the user answers (null or false when cancelled).
 * Questions appear as compact centred alerts; forms as sheets with Cancel and
 * the action in a top bar; lists of choices as action sheets from the bottom.
 */
object Dialogs {

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    class Btn(val label: String, val bold: Boolean = false, val destructive: Boolean = false, val action: () -> Unit)

    private val DESTRUCTIVE = setOf("Delete", "Remove", "Discard", "Clear", "Erase", "Delete pages", "Discard changes", "Apply redactions")

    // ------------------------------------------------------------------ building blocks

    /**
     * A text field. The hint shows inside the empty field; a caption above it is
     * added when the field starts filled in (so it stays clear what it holds).
     */
    fun field(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1): Pair<View, EditText> {
        val et = EditText(ctx).apply {
            this.hint = hint
            inputType = type
            if (lines > 1) {
                inputType = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3; maxLines = lines; gravity = Gravity.TOP or Gravity.START
            } else isSingleLine = true
            setText(value)
            setSelection(value.length)
            contentDescription = hint
        }
        if (value.isEmpty()) return et to et
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Ios.text(ctx, hint, 13f, 400, R.color.label2).apply { setPadding(dp(ctx, 4), 0, 0, dp(ctx, 4)) })
        box.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box to et
    }

    fun column(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = dp(ctx, 12)
            })
        }
    }

    fun label(ctx: Context, text: String, @Suppress("UNUSED_PARAMETER") style: Int = 0) =
        Ios.text(ctx, text, 13f, 400, R.color.label2).apply { setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), 0) }

    /** An on/off option, shown as a switch with its label. */
    fun check(ctx: Context, text: String, checked: Boolean): CompoundButton = SwitchCompat(ctx).apply {
        this.text = text
        isChecked = checked
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Ios.font(ctx, 400)
        setTextColor(Ios.color(ctx, R.color.label))
        minHeight = dp(ctx, 44)
        switchPadding = dp(ctx, 12)
        setOnCheckedChangeListener { b, _ -> Ios.tick(b) }
    }

    /** One choice among a few: a segmented control for short labels, otherwise a checklist. */
    fun radios(ctx: Context, items: List<String>, checked: Int): RadioGroup =
        if (items.size <= 3 && items.all { it.length <= 14 }) Ios.segmented(ctx, items, checked) else Ios.checklist(ctx, items, checked)

    fun RadioGroup.selectedIndex(): Int {
        var k = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i) as? RadioButton ?: continue
            if (c.isChecked) return k
            k++
        }
        return -1
    }

    fun EditText.value() = text?.toString() ?: ""

    // ------------------------------------------------------------------ presentations

    private fun showDialog(ctx: Context, content: View, widthPx: Int, style: Int, gravity: Int, onCancel: () -> Unit): Dialog {
        val d = Dialog(ctx, style)
        d.setContentView(content, ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        d.window?.apply {
            setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(gravity)
        }
        d.setOnCancelListener { onCancel() }
        d.show()
        return d
    }

    /**
     * A centred alert: title, message, optional content and buttons. Two short
     * buttons sit side by side (the preferred one bold on the right); more stack.
     */
    fun alert(ctx: Context, title: String, message: String?, content: View?, buttons: List<Btn>, onCancel: () -> Unit): Dialog {
        val w = dp(ctx, if (content != null) 300 else 270)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ios.rounded(ctx, Ios.color(ctx, R.color.bg_alert), 14f)
            clipToOutline = true
        }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 16), dp(ctx, 19), dp(ctx, 16), dp(ctx, 16)) }
        body.addView(Ios.text(ctx, title, 17f, 600).apply { gravity = Gravity.CENTER_HORIZONTAL })
        if (!message.isNullOrBlank()) body.addView(Ios.text(ctx, message, 13f, 400).apply {
            gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(ctx, 4), 0, 0); setLineSpacing(0f, 1.15f)
        })
        if (content != null) body.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 14) })
        val scroll = ScrollView(ctx).apply { isFillViewport = false; addView(body) }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lateinit var dialog: Dialog
        fun button(b: Btn) = Ios.textButton(ctx, b.label, b.bold, b.destructive) { dialog.dismiss(); b.action() }
        card.addView(Ios.hairline(ctx))
        val sideBySide = buttons.size == 2 && buttons.all { it.label.length <= 14 }
        if (sideBySide) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button(buttons[0]), LinearLayout.LayoutParams(0, dp(ctx, 44), 1f))
            row.addView(Ios.hairline(ctx, vertical = true))
            row.addView(button(buttons[1]), LinearLayout.LayoutParams(0, dp(ctx, 44), 1f))
            card.addView(row)
        } else {
            buttons.forEachIndexed { i, b ->
                if (i > 0) card.addView(Ios.hairline(ctx))
                card.addView(button(b), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 44)))
            }
        }
        dialog = showDialog(ctx, card, w, R.style.Npdf_Dialog, Gravity.CENTER, onCancel)
        return dialog
    }

    /** A form sheet: Cancel on the left, the title in the middle and the action on the right. */
    fun sheet(ctx: Context, title: String, content: View, ok: String, onOk: () -> Unit, onCancel: () -> Unit, check: (() -> Boolean)? = null): Dialog {
        val dm = ctx.resources.displayMetrics
        val w = min(dm.widthPixels - dp(ctx, 24), dp(ctx, 520))
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ios.rounded(ctx, Ios.color(ctx, R.color.bg_alert), 14f)
            clipToOutline = true
        }
        lateinit var dialog: Dialog
        val bar = FrameLayout(ctx).apply { minimumHeight = dp(ctx, 52); setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0) }
        bar.addView(Ios.textButton(ctx, "Cancel") { dialog.cancel() }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 52), Gravity.START or Gravity.CENTER_VERTICAL))
        bar.addView(Ios.text(ctx, title, 17f, 600).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(ctx, 90), 0, dp(ctx, 90), 0); ellipsize = android.text.TextUtils.TruncateAt.END },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52), Gravity.CENTER))
        bar.addView(Ios.textButton(ctx, ok, bold = true, destructive = ok in DESTRUCTIVE) { if (check == null || check()) { dialog.dismiss(); onOk() } },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 52), Gravity.END or Gravity.CENTER_VERTICAL))
        card.addView(bar)
        card.addView(Ios.hairline(ctx))
        val pad = FrameLayout(ctx).apply { setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 18)); addView(content) }
        val scroll = ScrollView(ctx).apply { addView(pad) }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Never taller than most of the screen; the content scrolls.
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            val max = (dm.heightPixels * 0.72f).toInt()
            if (scroll.height > max) scroll.layoutParams = scroll.layoutParams.apply { height = max }
        }
        dialog = showDialog(ctx, card, w, R.style.Npdf_Dialog, Gravity.CENTER, onCancel)
        return dialog
    }

    /** Choices sliding up from the bottom, with Cancel set apart. */
    fun actionSheet(ctx: Context, title: String?, items: List<String>, checked: Int, onPick: (Int?) -> Unit): Dialog {
        val dm = ctx.resources.displayMetrics
        val w = min(dm.widthPixels, dp(ctx, 540))
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 8)) }
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ios.rounded(ctx, Ios.color(ctx, R.color.bg_alert), 14f)
            clipToOutline = true
        }
        lateinit var dialog: Dialog
        if (!title.isNullOrBlank()) {
            group.addView(Ios.text(ctx, title, 13f, 600, R.color.label2).apply { gravity = Gravity.CENTER; setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14)) })
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, label ->
            if (i > 0 || !title.isNullOrBlank()) list.addView(Ios.hairline(ctx))
            val b = Ios.textButton(ctx, if (i == checked) "✓  $label" else label, bold = i == checked, destructive = label in DESTRUCTIVE) { dialog.dismiss(); onPick(i) }
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            b.contentDescription = label
            list.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 56)))
        }
        val scroll = ScrollView(ctx).apply { addView(list) }
        group.addView(scroll)
        root.addView(group)
        val cancel = Ios.textButton(ctx, "Cancel", bold = true) { dialog.cancel() }.apply {
            background = Ios.rounded(ctx, Ios.color(ctx, R.color.bg_elevated), 14f)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        }
        root.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 56)).apply { topMargin = dp(ctx, 8) })
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            val max = (dm.heightPixels * 0.6f).toInt()
            if (scroll.height > max) scroll.layoutParams = scroll.layoutParams.apply { height = max }
        }
        dialog = showDialog(ctx, root, w, R.style.Npdf_Sheet, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) { onPick(null) }
        dialog.window?.let { win ->
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                v.setPadding(v.paddingLeft, 0, v.paddingRight, dp(ctx, 8) + nav)
                insets
            }
            win.attributes = win.attributes.apply { y = 0 }
        }
        return dialog
    }

    // ------------------------------------------------------------------ suspending questions

    suspend fun text(ctx: Context, title: String, hint: String, value: String = "", multiline: Boolean = false,
                     type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, ok: String = "OK"): String? =
        suspendCancellableCoroutine { cont ->
            val (box, et) = field(ctx, hint, value, type, if (multiline) 6 else 1)
            val d = alert(ctx, title, null, box, listOf(
                Btn("Cancel") { if (cont.isActive) cont.resume(null) },
                Btn(ok, bold = true) { if (cont.isActive) cont.resume(et.text?.toString() ?: "") },
            )) { if (cont.isActive) cont.resume(null) }
            d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            et.requestFocus()
            cont.invokeOnCancellation { d.dismiss() }
        }

    suspend fun password(ctx: Context, title: String, message: String? = null): String? = suspendCancellableCoroutine { cont ->
        val (box, et) = field(ctx, "Password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val d = alert(ctx, title, message, box, listOf(
            Btn("Cancel") { if (cont.isActive) cont.resume(null) },
            Btn("Open", bold = true) { if (cont.isActive) cont.resume(et.text?.toString() ?: "") },
        )) { if (cont.isActive) cont.resume(null) }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        et.requestFocus()
        cont.invokeOnCancellation { d.dismiss() }
    }

    suspend fun confirm(ctx: Context, title: String, message: String, yes: String = "OK", no: String = "Cancel"): Boolean =
        suspendCancellableCoroutine { cont ->
            val d = alert(ctx, title, message, null, listOf(
                Btn(no) { if (cont.isActive) cont.resume(false) },
                Btn(yes, bold = yes !in DESTRUCTIVE, destructive = yes in DESTRUCTIVE) { if (cont.isActive) cont.resume(true) },
            )) { if (cont.isActive) cont.resume(false) }
            cont.invokeOnCancellation { d.dismiss() }
        }

    /** Three-way question: returns 0 (positive), 1 (neutral), 2 (negative) or -1 when cancelled. */
    suspend fun ask3(ctx: Context, title: String, message: String, positive: String, neutral: String, negative: String): Int =
        suspendCancellableCoroutine { cont ->
            val d = alert(ctx, title, message, null, listOf(
                Btn(positive, bold = true) { if (cont.isActive) cont.resume(0) },
                Btn(neutral, destructive = neutral in DESTRUCTIVE) { if (cont.isActive) cont.resume(1) },
                Btn(negative) { if (cont.isActive) cont.resume(2) },
            )) { if (cont.isActive) cont.resume(-1) }
            cont.invokeOnCancellation { d.dismiss() }
        }

    suspend fun choose(ctx: Context, title: String, items: List<String>, checked: Int = -1): Int? = suspendCancellableCoroutine { cont ->
        val d = actionSheet(ctx, title, items, checked) { if (cont.isActive) cont.resume(it) }
        cont.invokeOnCancellation { d.dismiss() }
    }

    fun message(ctx: Context, title: String, message: String) {
        alert(ctx, title, message, null, listOf(Btn("OK", bold = true) {})) {}
    }

    /** A form with a custom view; returns true when the action ([ok]) was pressed. */
    suspend fun custom(ctx: Context, title: String, view: View, ok: String = "OK", onShow: ((Dialog) -> Unit)? = null): Boolean =
        suspendCancellableCoroutine { cont ->
            val d = sheet(ctx, title, view, ok, onOk = { if (cont.isActive) cont.resume(true) }, onCancel = { if (cont.isActive) cont.resume(false) })
            onShow?.invoke(d)
            cont.invokeOnCancellation { d.dismiss() }
        }

    /** Text that explains, in a sheet (no action). */
    fun info(ctx: Context, title: String, body: CharSequence) {
        val t = Ios.text(ctx, body, 15f).apply { setLineSpacing(0f, 1.2f); setTextIsSelectable(true) }
        sheet(ctx, title, t, "Done", onOk = {}, onCancel = {})
    }

    @Suppress("unused")
    private fun asText(v: View) = v as? TextView
}
