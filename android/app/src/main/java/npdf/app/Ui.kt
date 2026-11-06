package npdf.app

import android.app.Activity
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import npdf.engine.PdfError
import kotlin.math.max

object Ui {
    /** Keeps content clear of the status bar, navigation bar, display cutout and keyboard. */
    fun fitSystemBars(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    /** A compact icon-over-label button for tool rows. */
    fun toolButton(a: Activity, label: String, icon: Int, onClick: (com.google.android.material.button.MaterialButton) -> Unit) =
        com.google.android.material.button.MaterialButton(a, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label
            setIconResource(icon)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TOP
            iconPadding = 0
            textSize = 11f
            isAllCaps = false
            minWidth = Dialogs.dp(a, 64)
            setPadding(Dialogs.dp(a, 6), paddingTop, Dialogs.dp(a, 6), paddingBottom)
            contentDescription = label
            setOnClickListener { onClick(this) }
        }

    fun markSelected(btn: com.google.android.material.button.MaterialButton?, on: Boolean) {
        btn ?: return
        val color = if (on) com.google.android.material.color.MaterialColors.getColor(btn, com.google.android.material.R.attr.colorSecondaryContainer)
        else android.graphics.Color.TRANSPARENT
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    fun toast(a: Activity, msg: String) = Toast.makeText(a, msg, Toast.LENGTH_LONG).show()

    /** A message for any failure: the engine's own wording, or a plain fallback. */
    fun describe(e: Throwable): String = when (e) {
        is PdfError -> e.message ?: "The operation failed."
        is OutOfMemoryError -> "The phone ran out of memory for this operation. Close other apps and try again, or work on fewer pages."
        is java.io.IOException -> "The file could not be read or written (${e.message ?: "input/output error"})."
        else -> "The operation failed (${e.message ?: e.javaClass.simpleName})."
    }
}
