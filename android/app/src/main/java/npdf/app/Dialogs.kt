package npdf.app

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Dialogs that suspend until the user answers (null or false when cancelled). */
object Dialogs {

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun field(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1): Pair<TextInputLayout, TextInputEditText> {
        val til = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = hint
        val et = TextInputEditText(til.context)
        et.inputType = type
        if (lines > 1) {
            et.inputType = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            et.minLines = 2; et.maxLines = lines
        } else et.isSingleLine = true
        et.setText(value)
        et.setSelection(value.length)
        til.addView(et)
        if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) til.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        return til to et
    }

    fun column(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 20)
        setPadding(p, dp(ctx, 8), p, 0)
        views.forEach { v ->
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 8)
            })
        }
    }

    suspend fun text(ctx: Context, title: String, hint: String, value: String = "", multiline: Boolean = false,
                     type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, ok: String = "OK"): String? =
        suspendCancellableCoroutine { cont ->
            val (til, et) = field(ctx, hint, value, type, if (multiline) 6 else 1)
            val d = MaterialAlertDialogBuilder(ctx).setTitle(title).setView(column(ctx, til))
                .setPositiveButton(ok) { _, _ -> if (cont.isActive) cont.resume(et.text?.toString() ?: "") }
                .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(null) }
                .setOnCancelListener { if (cont.isActive) cont.resume(null) }
                .create()
            d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            d.show()
            et.requestFocus()
            cont.invokeOnCancellation { d.dismiss() }
        }

    suspend fun password(ctx: Context, title: String, message: String? = null): String? = suspendCancellableCoroutine { cont ->
        val (til, et) = field(ctx, "Password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val b = MaterialAlertDialogBuilder(ctx).setTitle(title).setView(column(ctx, til))
        message?.let { b.setMessage(it) }
        val d = b.setPositiveButton("Open") { _, _ -> if (cont.isActive) cont.resume(et.text?.toString() ?: "") }
            .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(null) }
            .setOnCancelListener { if (cont.isActive) cont.resume(null) }
            .create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        et.requestFocus()
        cont.invokeOnCancellation { d.dismiss() }
    }

    suspend fun confirm(ctx: Context, title: String, message: String, yes: String = "OK", no: String = "Cancel"): Boolean =
        suspendCancellableCoroutine { cont ->
            val d = MaterialAlertDialogBuilder(ctx).setTitle(title).setMessage(message)
                .setPositiveButton(yes) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(no) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .show()
            cont.invokeOnCancellation { d.dismiss() }
        }

    /** Three-way question: returns 0 (positive), 1 (neutral), 2 (negative) or -1 when cancelled. */
    suspend fun ask3(ctx: Context, title: String, message: String, positive: String, neutral: String, negative: String): Int =
        suspendCancellableCoroutine { cont ->
            val d = MaterialAlertDialogBuilder(ctx).setTitle(title).setMessage(message)
                .setPositiveButton(positive) { _, _ -> if (cont.isActive) cont.resume(0) }
                .setNeutralButton(neutral) { _, _ -> if (cont.isActive) cont.resume(1) }
                .setNegativeButton(negative) { _, _ -> if (cont.isActive) cont.resume(2) }
                .setOnCancelListener { if (cont.isActive) cont.resume(-1) }
                .show()
            cont.invokeOnCancellation { d.dismiss() }
        }

    suspend fun choose(ctx: Context, title: String, items: List<String>, checked: Int = -1): Int? = suspendCancellableCoroutine { cont ->
        val b = MaterialAlertDialogBuilder(ctx).setTitle(title)
        if (checked >= 0) {
            b.setSingleChoiceItems(items.toTypedArray(), checked) { d, which -> if (cont.isActive) cont.resume(which); d.dismiss() }
        } else {
            b.setItems(items.toTypedArray()) { _, which -> if (cont.isActive) cont.resume(which) }
        }
        val d = b.setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(null) }
            .setOnCancelListener { if (cont.isActive) cont.resume(null) }
            .show()
        cont.invokeOnCancellation { d.dismiss() }
    }

    fun message(ctx: Context, title: String, message: String) {
        MaterialAlertDialogBuilder(ctx).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    /** A dialog with a custom view; returns true when OK was pressed. */
    suspend fun custom(ctx: Context, title: String, view: View, ok: String = "OK", onShow: ((AlertDialog) -> Unit)? = null): Boolean =
        suspendCancellableCoroutine { cont ->
            val d = MaterialAlertDialogBuilder(ctx).setTitle(title).setView(view)
                .setPositiveButton(ok) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .create()
            d.show()
            onShow?.invoke(d)
            cont.invokeOnCancellation { d.dismiss() }
        }

    fun label(ctx: Context, text: String, style: Int = com.google.android.material.R.style.TextAppearance_Material3_BodyMedium) =
        TextView(ctx).apply { this.text = text; setTextAppearance(style) }

    fun check(ctx: Context, text: String, checked: Boolean) = CheckBox(ctx).apply { this.text = text; isChecked = checked }

    fun radios(ctx: Context, items: List<String>, checked: Int): RadioGroup = RadioGroup(ctx).apply {
        items.forEachIndexed { i, t -> addView(RadioButton(ctx).apply { id = View.generateViewId(); text = t; isChecked = i == checked }) }
    }

    fun RadioGroup.selectedIndex(): Int {
        for (i in 0 until childCount) if ((getChildAt(i) as RadioButton).isChecked) return i
        return -1
    }

    fun EditText.value() = text?.toString() ?: ""
}
