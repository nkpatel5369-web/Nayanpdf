package npdf.app

import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.suspendCancellableCoroutine
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
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Dialogs.dp(a, 16), Dialogs.dp(a, 8), Dialogs.dp(a, 16), 0) }
        val tabs = TabLayout(a)
        tabs.addTab(tabs.newTab().setText("Draw"))
        tabs.addTab(tabs.newTab().setText("Type"))
        if (saved.isNotEmpty()) tabs.addTab(tabs.newTab().setText("Saved"))
        root.addView(tabs)

        val pad = SignaturePad(a)
        val padBox = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Dialogs.dp(a, 180)))
        }
        val (typeTil, typeEt) = Dialogs.field(a, "Your name", Prefs(a).author)
        val typePreview = ImageView(a).apply { minimumHeight = Dialogs.dp(a, 80) }
        val typeBox = Dialogs.column(a, typeTil, typePreview).apply { visibility = View.GONE }
        typeEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                val t = e?.toString().orEmpty().trim()
                if (t.isEmpty()) typePreview.setImageDrawable(null) else {
                    val png = SignatureStore.typed(t)
                    typePreview.setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.size))
                }
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, aft: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        var chosen: File? = null
        val savedBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val views = mutableListOf<ImageView>()
        for (f in saved) {
            val png = store.load(f) ?: continue
            val iv = ImageView(a).apply {
                setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.size))
                adjustViewBounds = true
                maxHeight = Dialogs.dp(a, 70)
                setPadding(8, 8, 8, 8)
                setOnClickListener { chosen = f; views.forEach { it.setBackgroundColor(0) }; setBackgroundColor(0x333F51D9) }
                setOnLongClickListener {
                    store.delete(f); visibility = View.GONE; true
                }
            }
            views.add(iv)
            savedBox.addView(iv)
        }
        val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(padBox); addView(typeBox); addView(savedBox) }
        root.addView(content)
        val remember = Dialogs.check(a, "Save this signature on this phone (encrypted)", true)
        root.addView(remember)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                padBox.visibility = if (t.position == 0) View.VISIBLE else View.GONE
                typeBox.visibility = if (t.position == 1) View.VISIBLE else View.GONE
                savedBox.visibility = if (t.position == 2) View.VISIBLE else View.GONE
                remember.visibility = if (t.position == 2) View.GONE else View.VISIBLE
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        val d = MaterialAlertDialogBuilder(a).setTitle("Signature").setView(root)
            .setPositiveButton("Place", null)
            .setNeutralButton("Clear", null)
            .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(null) }
            .setOnCancelListener { if (cont.isActive) cont.resume(null) }
            .create()
        d.setOnShowListener {
            d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { pad.clear(); typeEt.setText("") }
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val png = when (tabs.selectedTabPosition) {
                    0 -> pad.toPng()
                    1 -> typeEt.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { SignatureStore.typed(it) }
                    else -> chosen?.let { store.load(it) }
                }
                if (png == null) { Ui.toast(a, "Draw, type or choose a signature first."); return@setOnClickListener }
                if (remember.isChecked && tabs.selectedTabPosition != 2) store.save(png)
                if (cont.isActive) cont.resume(png)
                d.dismiss()
            }
        }
        d.show()
        cont.invokeOnCancellation { d.dismiss() }
    }
}
