package npdf.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import npdf.engine.PdfError
import kotlin.math.max

object Ui {
    /** Keeps content clear of the status bar, navigation bar, display cutout and keyboard. */
    /**
     * Draws the screen behind transparent system bars on every Android version (Android 15 and later
     * do this anyway), so the status bar takes the colour of the bar or page beneath it instead of a
     * solid theme colour. [darkBars] keeps light status icons over a dark screen (the camera).
     */
    fun edgeToEdge(a: androidx.activity.ComponentActivity, darkBars: Boolean = false) {
        if (darkBars) a.enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT))
        else a.enableEdgeToEdge()
    }

    fun fitSystemBars(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    /** A Material toolbar item: icon over a short label; the active tool sits in a tonal pill. */
    fun toolButton(a: Activity, label: String, icon: Int, onClick: (MaterialButton) -> Unit) =
        MaterialButton(a, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
            iconPadding = Dialogs.dp(a, 4)
            iconSize = Dialogs.dp(a, 24)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Kit.font(a, 500)
            isAllCaps = false
            maxLines = 1
            minWidth = Dialogs.dp(a, 64)
            minHeight = Dialogs.dp(a, 64)
            cornerRadius = Dialogs.dp(a, 16)
            insetTop = 0; insetBottom = 0
            setPadding(Dialogs.dp(a, 8), Dialogs.dp(a, 8), Dialogs.dp(a, 8), Dialogs.dp(a, 6))
            contentDescription = label
            markSelected(this, false)
            setOnClickListener { Kit.tick(it); onClick(this) }
        }

    /** The chosen tool: on-secondary-container glyph and label on the secondary container. */
    fun markSelected(btn: MaterialButton?, on: Boolean) {
        btn ?: return
        val ctx = btn.context
        val fg = Kit.color(ctx, if (on) R.color.on_tint_soft else R.color.label2)
        btn.iconTint = ColorStateList.valueOf(fg)
        btn.setTextColor(fg)
        btn.backgroundTintList = ColorStateList.valueOf(if (on) Kit.color(ctx, R.color.tint_soft) else Color.TRANSPARENT)
    }

    /** A short status message that disappears by itself. */
    fun toast(a: Activity, msg: String) = Kit.banner(a, msg)

    /** A message for any failure: the engine's own wording, or a plain fallback. */
    fun describe(e: Throwable): String = when (e) {
        is PdfError -> e.message ?: "The operation failed."
        is OutOfMemoryError -> "The phone ran out of memory for this operation. Close other apps and try again, or work on fewer pages."
        is java.io.IOException -> "The file could not be read or written (${e.message ?: "input/output error"})."
        else -> "The operation failed (${e.message ?: e.javaClass.simpleName})."
    }
}
