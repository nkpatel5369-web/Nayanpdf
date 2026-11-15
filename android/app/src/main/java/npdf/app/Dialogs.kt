package npdf.app

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
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
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Dialogs that suspend until the user answers (null or false when cancelled), drawn
 * with Material 3: questions as dialogs, forms as modal bottom sheets, one choice
 * among several as a radio dialog, and lists of actions as bottom sheets.
 */
object Dialogs {

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    class Btn(val label: String, val bold: Boolean = false, val destructive: Boolean = false, val action: () -> Unit)

    private val DESTRUCTIVE = setOf("Delete", "Remove", "Discard", "Discard it", "Clear", "Erase", "Delete pages", "Discard changes",
        "Apply redactions", "Delete page", "Delete file")

    // ------------------------------------------------------------------ building blocks

    /** An outlined Material text field whose label floats above the text once it is filled in. */
    fun field(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1): Pair<View, EditText> {
        val til = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { this.hint = hint }
        val et = TextInputEditText(til.context).apply {
            inputType = type
            if (lines > 1) {
                inputType = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3; maxLines = lines; gravity = Gravity.TOP or Gravity.START
            } else isSingleLine = true
            setText(value)
            setSelection(value.length)
            contentDescription = hint
        }
        til.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return til to et
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
        Kit.text(ctx, text, 14f, 500, R.color.label2).apply { setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), 0) }

    /** An on/off option: its label with a Material switch at the end. */
    fun check(ctx: Context, text: String, checked: Boolean): CompoundButton = MaterialSwitch(ctx).apply {
        this.text = text
        isChecked = checked
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Kit.color(ctx, R.color.label))
        minHeight = dp(ctx, 48)
        switchPadding = dp(ctx, 16)
        setOnCheckedChangeListener { b, _ -> Kit.tick(b) }
    }

    /** One choice among a few: segmented buttons for short labels, otherwise radio buttons. */
    fun radios(ctx: Context, items: List<String>, checked: Int): RadioGroup =
        if (items.size <= 3 && items.all { it.length <= 14 }) Kit.segmented(ctx, items, checked) else Kit.checklist(ctx, items, checked)

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

    /**
     * A Material dialog: title, supporting text, optional content and up to three text
     * buttons at the end (the main action last). Destructive actions are in the error colour.
     */
    fun alert(ctx: Context, title: String, message: String?, content: View?, buttons: List<Btn>, onCancel: () -> Unit): Dialog {
        val b = MaterialAlertDialogBuilder(ctx).setTitle(title)
        if (!message.isNullOrBlank()) b.setMessage(message)
        if (content != null) b.setView(FrameLayout(ctx).apply {
            setPadding(dp(ctx, 24), dp(ctx, if (message.isNullOrBlank()) 8 else 0), dp(ctx, 24), 0)
            addView(content)
        })
        // Material places the positive button at the end, the negative before it and the neutral apart.
        val slots = when (buttons.size) {
            1 -> listOf(DialogInterface.BUTTON_POSITIVE)
            2 -> listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_POSITIVE)
            else -> listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEUTRAL, DialogInterface.BUTTON_NEGATIVE)
        }
        buttons.take(3).forEachIndexed { i, btn ->
            val l = DialogInterface.OnClickListener { _, _ -> btn.action() }
            when (slots[i]) {
                DialogInterface.BUTTON_POSITIVE -> b.setPositiveButton(btn.label, l)
                DialogInterface.BUTTON_NEGATIVE -> b.setNegativeButton(btn.label, l)
                else -> b.setNeutralButton(btn.label, l)
            }
        }
        b.setOnCancelListener { onCancel() }
        val d = b.create()
        d.setOnShowListener {
            buttons.take(3).forEachIndexed { i, btn ->
                if (btn.destructive || btn.label in DESTRUCTIVE) d.getButton(slots[i])?.setTextColor(Kit.color(ctx, R.color.red))
            }
        }
        d.show()
        return d
    }

    /** A modal bottom sheet holding [content], with its title, Cancel and the main action. */
    fun sheet(ctx: Context, title: String, content: View, ok: String, onOk: () -> Unit, onCancel: () -> Unit, check: (() -> Boolean)? = null): Dialog {
        val d = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(BottomSheetDragHandleView(ctx))
        root.addView(Kit.text(ctx, title, 22f, 400).apply { setPadding(dp(ctx, 24), 0, dp(ctx, 24), dp(ctx, 12)) })
        val scroll = NestedScrollView(ctx).apply {
            addView(FrameLayout(ctx).apply { setPadding(dp(ctx, 24), dp(ctx, 4), dp(ctx, 24), dp(ctx, 8)); addView(content) })
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 16))
        }
        actions.addView(Kit.textButton(ctx, "Cancel") { d.cancel() })
        actions.addView(Kit.textButton(ctx, ok, bold = true, destructive = ok in DESTRUCTIVE) {
            if (check == null || check()) { d.dismiss(); onOk() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 8) })
        root.addView(actions)
        d.setContentView(root)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        d.setOnCancelListener { onCancel() }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.show()
        return d
    }

    /**
     * Choices: with one already chosen ([checked]), a dialog of radio buttons (choosing
     * closes it); otherwise a bottom sheet listing the actions.
     */
    fun actionSheet(ctx: Context, title: String?, items: List<String>, checked: Int, onPick: (Int?) -> Unit): Dialog {
        if (checked >= 0) {
            var done = false
            val d = MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setSingleChoiceItems(items.toTypedArray(), checked) { dlg, which -> done = true; dlg.dismiss(); onPick(which) }
                .setNegativeButton("Cancel") { _, _ -> done = true; onPick(null) }
                .setOnCancelListener { if (!done) { done = true; onPick(null) } }
                .create()
            d.show()
            return d
        }
        val d = BottomSheetDialog(ctx)
        var picked = false
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(ctx, 16)) }
        root.addView(BottomSheetDragHandleView(ctx))
        if (!title.isNullOrBlank()) root.addView(Kit.text(ctx, title, 16f, 500, R.color.label2).apply {
            setPadding(dp(ctx, 24), 0, dp(ctx, 24), dp(ctx, 8)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, label ->
            list.addView(Kit.text(ctx, label, 16f, 400, if (label in DESTRUCTIVE) R.color.red else R.color.label).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(ctx, 56)
                setPadding(dp(ctx, 24), 0, dp(ctx, 24), 0)
                background = Kit.cellBackground(ctx)
                isClickable = true; isFocusable = true
                contentDescription = label
                setOnClickListener { picked = true; d.dismiss(); onPick(i) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(NestedScrollView(ctx).apply { addView(list) })
        d.setContentView(root)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        d.setOnDismissListener { if (!picked) { picked = true; onPick(null) } }
        d.show()
        return d
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
        (box as? TextInputLayout)?.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
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

    /** Text that explains, in a dialog with Done. */
    fun info(ctx: Context, title: String, body: CharSequence) {
        val t = Kit.text(ctx, body, 14f, 400, R.color.label2).apply { setLineSpacing(0f, 1.2f); setTextIsSelectable(true) }
        alert(ctx, title, null, NestedScrollView(ctx).apply { addView(t) }, listOf(Btn("Done", bold = true) {})) {}
    }
}
