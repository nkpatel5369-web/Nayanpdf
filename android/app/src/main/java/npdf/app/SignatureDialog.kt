package npdf.app

import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import npdf.app.Dialogs.selectedIndex
import java.io.File
import kotlin.coroutines.resume

/**
 * Draw, type or pick a saved signature. The result is a picture of a
 * signature, placed as a stamp; it is not a certificate-based digital signature.
 */
object SignatureDialog {

    suspend fun show(a: AppCompatActivity): ByteArray? = suspendCancellableCoroutine { cont ->
        val store = SignatureStore(a)
        val saved = store.list()
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val tabs = Ios.segmented(a, if (saved.isNotEmpty()) listOf("Draw", "Type", "Saved") else listOf("Draw", "Type"), 0)
        root.addView(tabs)

        // Drawing area: white card with a signing line, like paper.
        val pad = SignaturePad(a)
        val padBox = FrameLayout(a).apply {
            background = Ios.rounded(a, 0xFFFFFFFF.toInt(), 12f, Ios.color(a, R.color.separator), 1f)
            addView(View(a).apply { setBackgroundColor(0x33000000) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dialogs.dp(a, 1), Gravity.BOTTOM).apply {
                setMargins(Dialogs.dp(a, 20), 0, Dialogs.dp(a, 20), Dialogs.dp(a, 44))
            })
            addView(Ios.text(a, "Sign above the line", 12f, 400, R.color.label3).apply { setTextColor(0x66000000) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Dialogs.dp(a, 22) })
            addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dialogs.dp(a, 190)))
            addView(Ios.textButton(a, "Clear") { pad.clear() }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Dialogs.dp(a, 40), Gravity.TOP or Gravity.END))
        }
        val (typeTil, typeEt) = Dialogs.field(a, "Your name", Prefs(a).author)
        val typePreview = ImageView(a).apply { minimumHeight = Dialogs.dp(a, 80); background = Ios.rounded(a, 0xFFFFFFFF.toInt(), 12f, Ios.color(a, R.color.separator), 1f) }
        val typeBox = Dialogs.column(a, typeTil, typePreview).apply { visibility = View.GONE }
        fun preview() {
            val t = typeEt.text?.toString().orEmpty().trim()
            if (t.isEmpty()) typePreview.setImageDrawable(null) else {
                val png = SignatureStore.typed(t)
                typePreview.setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.size))
            }
        }
        typeEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) = preview()
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, aft: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        preview()
        var chosen: File? = null
        val savedBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val views = mutableListOf<ImageView>()
        for (f in saved) {
            val png = store.load(f) ?: continue
            val iv = ImageView(a).apply {
                setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.size))
                adjustViewBounds = true
                maxHeight = Dialogs.dp(a, 76)
                setPadding(Dialogs.dp(a, 10), Dialogs.dp(a, 8), Dialogs.dp(a, 10), Dialogs.dp(a, 8))
                background = Ios.rounded(a, 0xFFFFFFFF.toInt(), 10f, Ios.color(a, R.color.separator), 1f)
                contentDescription = "Saved signature (long-press to delete)"
                setOnClickListener {
                    chosen = f
                    views.forEach { v -> v.background = Ios.rounded(a, 0xFFFFFFFF.toInt(), 10f, Ios.color(a, R.color.separator), 1f) }
                    background = Ios.rounded(a, 0xFFFFFFFF.toInt(), 10f, Ios.color(a, R.color.tint), 2.5f)
                }
                setOnLongClickListener { store.delete(f); visibility = View.GONE; Ios.banner(a, "Signature deleted from this phone"); true }
            }
            views.add(iv)
            savedBox.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Dialogs.dp(a, 8) })
        }
        val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(padBox); addView(typeBox); addView(savedBox) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Dialogs.dp(a, 12) })
        val remember = Dialogs.check(a, "Save this signature on this phone (encrypted)", true)
        root.addView(remember, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Dialogs.dp(a, 8) })
        root.addView(Dialogs.label(a, "A signature placed this way is a picture of your signature, not a certificate-based digital signature."))
        tabs.setOnCheckedChangeListener { g, _ ->
            Ios.tick(g)
            val t = g.selectedIndex()
            padBox.visibility = if (t == 0) View.VISIBLE else View.GONE
            typeBox.visibility = if (t == 1) View.VISIBLE else View.GONE
            savedBox.visibility = if (t == 2) View.VISIBLE else View.GONE
            remember.visibility = if (t == 2) View.GONE else View.VISIBLE
        }
        var result: ByteArray? = null
        val d = Dialogs.sheet(a, "Signature", root, "Place", { if (cont.isActive) cont.resume(result) }, { if (cont.isActive) cont.resume(null) }) {
            val t = tabs.selectedIndex()
            val png = when (t) {
                0 -> pad.toPng()
                1 -> typeEt.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { SignatureStore.typed(it) }
                else -> chosen?.let { store.load(it) }
            }
            if (png == null) { Ui.toast(a, "Draw, type or choose a signature first."); false }
            else { if (remember.isChecked && t != 2) store.save(png); result = png; true }
        }
        cont.invokeOnCancellation { d.dismiss() }
    }
}
