package npdf.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import npdf.engine.Codes
import npdf.engine.Pages
import npdf.engine.Pdf
import npdf.engine.PdfError

/** Combine PDFs and pictures, in the order shown, into one PDF. */
class MergeActivity : FileListActivity() {
    override val screenTitle = "Combine files"
    override val hintText = "Add PDFs and pictures, put them in order, then choose Combine. The original files are not changed."
    override val goLabel = "Combine"

    private val passwords = HashMap<Uri, String>()

    private val addLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) addUris(uris) }
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) lifecycleScope.launch { combine(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b.add.setOnClickListener { addLauncher.launch(arrayOf("application/pdf", "image/*")) }
        b.go.setOnClickListener { lifecycleScope.launch { prepareAndSave() } }
    }

    override suspend fun thumbnail(item: Item): Bitmap? {
        val f = item.file ?: Files.temp(this, item.name).also { Files.copyToFile(this, item.uri, it); item.file = it }
        if (Pages.isImage(item.name) || contentResolver.getType(item.uri)?.startsWith("image/") == true) {
            return android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
        }
        return withContext(DocSession.engineDispatcher) {
            try {
                val d = Pdf.open(f.absolutePath, null)
                try {
                    val page = d.loadPage(0)
                    val bb = page.bounds
                    val pix = page.toPixmap(Matrix.Scale(96f / maxOf(1f, bb.x1 - bb.x0)), ColorSpace.DeviceRGB, false, true)
                    val bmp = Bitmap.createBitmap(pix.width, pix.height, Bitmap.Config.ARGB_8888)
                    val px = IntArray(pix.width * pix.height)
                    val s = pix.samples
                    for (i in px.indices) px[i] = (0xFF shl 24) or ((s[i * 3].toInt() and 0xFF) shl 16) or ((s[i * 3 + 1].toInt() and 0xFF) shl 8) or (s[i * 3 + 2].toInt() and 0xFF)
                    bmp.setPixels(px, 0, pix.width, 0, 0, pix.width, pix.height)
                    bmp
                } finally { d.destroy() }
            } catch (e: Throwable) { null }
        }
    }

    /** Asks for the password of any protected PDF before combining. */
    private suspend fun prepareAndSave() {
        val files = try { localFiles() } catch (e: Throwable) { Dialogs.message(this, "Could not read a file", Ui.describe(e)); return }
        for ((k, item) in items.withIndex()) {
            if (Pages.isImage(item.name) || contentResolver.getType(item.uri)?.startsWith("image/") == true) continue
            var pw = passwords[item.uri]
            while (true) {
                val err = withContext(DocSession.engineDispatcher) {
                    try { Pdf.open(files[k].absolutePath, pw).destroy(); null } catch (e: PdfError) { e }
                } ?: break
                if (err.code != Codes.PASSWORD_REQUIRED && err.code != Codes.PASSWORD_WRONG) {
                    Dialogs.message(this, "Cannot use ${item.name}", err.message ?: ""); return
                }
                pw = Dialogs.password(this, if (err.code == Codes.PASSWORD_WRONG) "Wrong password" else "Password required", "\"${item.name}\" is protected.") ?: return
            }
            pw?.let { passwords[item.uri] = it }
        }
        saveLauncher.launch("Combined.pdf")
    }

    private suspend fun combine(target: Uri) {
        b.go.isEnabled = false
        b.hint.text = "Combining…"
        try {
            val files = localFiles()
            val out = Files.temp(this, "combined.pdf")
            val inputs = items.mapIndexed { k, it ->
                val path = if (Pages.isImage(it.name) || contentResolver.getType(it.uri)?.startsWith("image/") == true) {
                    // Pictures are recognised by extension inside the engine.
                    val ext = when (contentResolver.getType(it.uri)) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg" }
                    val f = java.io.File(files[k].parentFile, files[k].nameWithoutExtension + "_img." + ext)
                    files[k].copyTo(f, overwrite = true); f.absolutePath
                } else files[k].absolutePath
                Pages.Input(path, passwords[it.uri])
            }
            val pages = withContext(DocSession.engineDispatcher) { Pages.merge(inputs, out) }
            withContext(Dispatchers.IO) { Files.writeToUri(this@MergeActivity, out, target) }
            Ui.toast(this, "Combined ${items.size} files into $pages pages.")
            openResult(target)
        } catch (e: Throwable) {
            Dialogs.message(this, "Could not combine", Ui.describe(e))
        } finally {
            b.go.isEnabled = items.isNotEmpty()
            b.hint.text = hintText
        }
    }
}
