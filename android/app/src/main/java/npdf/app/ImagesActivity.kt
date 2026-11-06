package npdf.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import npdf.app.Dialogs.selectedIndex
import npdf.engine.Pages
import npdf.engine.TextLayer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Photos or pictures to PDF, one per page: from the gallery, the phone's camera
 * app or another app's Share. Optionally made searchable with OCR.
 */
class ImagesActivity : FileListActivity() {
    override val screenTitle = "Photos to PDF"
    override val hintText = "Add photos or take new ones with the camera, put them in order, then create the PDF."
    override val goLabel = "Create PDF"

    private lateinit var size: android.widget.RadioGroup
    private lateinit var reduce: android.widget.CheckBox
    private lateinit var ocr: android.widget.CheckBox
    private var cameraFile: File? = null

    private val addLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty()) addUris(uris) }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null && f.length() > 0) addUris(listOf(Files.shareUri(this, f)))
    }
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) lifecycleScope.launch { create(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b.add.setOnClickListener { addLauncher.launch("image/*") }
        b.camera.visibility = android.view.View.VISIBLE
        b.camera.setOnClickListener { takePhoto() }
        b.go.setOnClickListener { saveLauncher.launch("Photos.pdf") }
        size = Dialogs.radios(this, listOf("A4", "Letter", "Fit to picture"), 0).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        reduce = Dialogs.check(this, "Reduce photo size (keeps them sharp for reading and printing)", true)
        ocr = Dialogs.check(this, "Make the text searchable (OCR, English)", false)
        b.options.addView(size); b.options.addView(reduce); b.options.addView(ocr)
        incoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming(intent)
    }

    /** Pictures shared from another app. */
    private fun incoming(i: Intent?) {
        i ?: return
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) addUris(uris)
    }

    private fun takePhoto() {
        val f = File(Files.workDir(this, "camera"), "photo_${System.currentTimeMillis()}.jpg")
        cameraFile = f
        try {
            cameraLauncher.launch(Files.shareUri(this, f))
        } catch (e: android.content.ActivityNotFoundException) {
            Ui.toast(this, "No camera app is available on this phone.")
        }
    }

    override suspend fun thumbnail(item: Item): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val sample = max(1, max(opts.outWidth, opts.outHeight) / 160)
        val bmp = contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        return bmp?.let { upright(it, orientation(item.uri)) }
    }

    private fun orientation(uri: Uri): Int = try {
        contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }

    private fun upright(src: Bitmap, o: Int): Bitmap {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** A photo made upright and at most 3000 pixels on its longer side, as JPEG. */
    private fun reduced(uri: Uri): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw java.io.IOException("A picture could not be read.")
        var up = upright(bmp, orientation(uri))
        val longest = max(up.width, up.height)
        if (longest > MAX_SIDE) {
            val f = MAX_SIDE.toFloat() / longest
            up = Bitmap.createScaledBitmap(up, (up.width * f).toInt(), (up.height * f).toInt(), true)
        }
        return ByteArrayOutputStream().also { up.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private suspend fun create(target: Uri) {
        b.go.isEnabled = false
        b.hint.text = "Creating the PDF…"
        try {
            val pageSize = listOf(Pages.PageSize.A4, Pages.PageSize.LETTER, Pages.PageSize.FIT)[size.selectedIndex().coerceAtLeast(0)]
            val bytes = withContext(Dispatchers.IO) {
                items.map { if (reduce.isChecked) reduced(it.uri) else Files.readBytes(this@ImagesActivity, it.uri) }
            }
            var out = Files.temp(this, "photos.pdf")
            withContext(DocSession.engineDispatcher) {
                Pages.imagesToPdf(bytes, out, pageSize, if (pageSize == Pages.PageSize.FIT) 0f else 18f)
            }
            if (ocr.isChecked) out = recognise(out)
            withContext(Dispatchers.IO) { Files.writeToUri(this@ImagesActivity, out, target) }
            Ui.toast(this, "PDF created with ${items.size} page(s).")
            openResult(target)
        } catch (e: Throwable) {
            Dialogs.message(this, "Could not create the PDF", Ui.describe(e))
        } finally {
            b.go.isEnabled = items.isNotEmpty()
            b.hint.text = hintText
        }
    }

    /** OCR on the new PDF; returns the file with the text layer. */
    private suspend fun recognise(pdf: File): File {
        val s = DocSession.openFile(this, pdf, null, "photos.pdf", null)
        try {
            val engine = Ocr(this)
            val results = withContext(Dispatchers.Default) {
                (0 until s.sizes.size).map { p ->
                    runOnUiThread { b.hint.text = "Recognising text on page ${p + 1} of ${s.sizes.size}…" }
                    p to engine.recognize(s, p, listOf("eng"))
                }
            }
            s.edit("OCR", null) { d ->
                val font = TextLayer.newFont(d)
                for ((p, r) in results) TextLayer.addWords(d, p + 1, r.words, font)
            }
            val out = Files.temp(this, "photos_ocr.pdf")
            s.writeTo(out)
            return out
        } finally {
            s.close()
        }
    }

    companion object {
        const val MAX_SIDE = 3000
    }
}
