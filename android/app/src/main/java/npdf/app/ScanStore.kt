package npdf.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import npdf.engine.Codes
import npdf.engine.Pages
import npdf.engine.Pdf
import npdf.engine.PdfError
import npdf.engine.Security
import npdf.engine.TextLayer
import npdf.scan.Contact
import npdf.scan.TextInfo
import npdf.scan.TextLine
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Saves scans on the phone: in Documents/N PDF (pictures in Pictures/N PDF)
 * through Android's media store, or in a folder the user chose. Nothing is
 * uploaded anywhere.
 */
object ScanStore {

    /** Android 8 and 9 need a folder chosen once (writing to shared Documents needs a permission N PDF does not ask for). */
    class NeedFolder : Exception("Choose a folder for your scans.")

    class Options(
        val ocr: Boolean, val languages: List<String>, val quality: Int = 0, // 0 best, 1 balanced, 2 smallest
        val password: String? = null,
    )

    private val QUALITY = listOf(3200 to 90, 2400 to 82, 1700 to 70)

    fun folderLabel(ctx: Context): String {
        val tree = Prefs(ctx).scanFolder ?: return if (Build.VERSION.SDK_INT >= 29) "Documents/N PDF" else "Choose a folder"
        return runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(tree))?.name }.getOrNull() ?: "Chosen folder"
    }

    /** Creates an empty file for [displayName] (without extension) and returns where to write it. */
    fun create(ctx: Context, displayName: String, mime: String, ext: String, picture: Boolean = false): Uri {
        val name = TextInfo.safeName(displayName).ifEmpty { "Scan" }
        Prefs(ctx).scanFolder?.let { tree ->
            val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree))
            if (dir != null && dir.canWrite()) {
                return dir.createFile(mime, name)?.uri ?: throw PdfError(Codes.IO, "The file could not be created in the chosen folder.")
            }
        }
        if (Build.VERSION.SDK_INT < 29) throw NeedFolder()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (picture) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOCUMENTS) + "/N PDF")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (picture) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return ctx.contentResolver.insert(collection, values) ?: throw PdfError(Codes.IO, "The file could not be created in Documents.")
    }

    /** Makes a media-store file visible to other apps once it is complete. */
    fun publish(ctx: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY) {
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    private fun discard(ctx: Context, uri: Uri) { runCatching { ctx.contentResolver.delete(uri, null, null) } }

    private fun pageSize(s: String) = when (s) {
        "A4" -> Pages.PageSize.A4; "LETTER" -> Pages.PageSize.LETTER; "LEGAL" -> Pages.PageSize.LEGAL; else -> Pages.PageSize.FIT
    }

    private fun scaled(b: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
    }

    /**
     * Writes the scan as one PDF, recognising text when asked, and returns the
     * saved file. [progress] receives a message and the fraction done.
     */
    fun savePdf(ctx: Context, session: ScanSession, name: String, opt: Options, progress: (String, Float) -> Unit): Uri {
        val (maxSide, quality) = QUALITY[opt.quality.coerceIn(0, 2)]
        val tmp = File(ctx.cacheDir, "scan_${System.nanoTime()}.pdf")
        val doc = PDFDocument()
        try {
            val font = if (opt.ocr) TextLayer.newFont(doc) else null
            session.pages.forEachIndexed { i, page ->
                progress("Preparing page ${i + 1} of ${session.pages.size}", i.toFloat() / session.pages.size)
                val full = ScanProcessor.cached(session, page)
                val bmp = scaled(full, maxSide)
                val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                val box: Rect = Pages.addImagePage(doc, jpeg, pageSize(page.size), 0f)
                if (opt.ocr) {
                    progress("Recognising text on page ${i + 1} of ${session.pages.size}", (i + 0.5f) / session.pages.size)
                    // Recognition at about 300 dpi: the page's longer side is 842 pt (A4) or more.
                    val dpi = (bmp.height / ((box.y1 - box.y0) / 72f)).roundToInt()
                    val r = Ocr(ctx).recognizeBitmap(bmp, dpi, opt.languages)
                    val sx = (box.x1 - box.x0) / bmp.width; val sy = (box.y1 - box.y0) / bmp.height
                    val words = r.words.map { w ->
                        TextLayer.Word(w.text, Rect(box.x0 + w.box.left * sx, box.y0 + w.box.top * sy, box.x0 + w.box.right * sx, box.y0 + w.box.bottom * sy), w.confidence)
                    }
                    TextLayer.addWords(doc, i + 1, words, font)
                }
                if (bmp !== full) bmp.recycle()
                full.recycle()
            }
            progress("Saving", 0.97f)
            if (!opt.password.isNullOrEmpty()) Security.protect(doc, tmp, opt.password, null)
            else Pdf.save(doc, tmp, "garbage=compact,compress")
        } finally {
            doc.destroy()
        }
        Pages.verify(tmp, session.pages.size, opt.password)
        val uri = create(ctx, name, "application/pdf", "pdf")
        try {
            Files.writeToUri(ctx, tmp, uri)
            publish(ctx, uri)
        } catch (e: Exception) {
            discard(ctx, uri); throw e
        } finally {
            tmp.delete()
        }
        return uri
    }

    /** Saves every page as a JPEG picture; returns the saved files. */
    fun saveJpegs(ctx: Context, session: ScanSession, name: String, progress: (String, Float) -> Unit): List<Uri> {
        val out = ArrayList<Uri>()
        session.pages.forEachIndexed { i, page ->
            progress("Saving picture ${i + 1} of ${session.pages.size}", i.toFloat() / session.pages.size)
            val bmp = ScanProcessor.cached(session, page)
            val uri = create(ctx, if (session.pages.size == 1) name else "$name ${i + 1}", "image/jpeg", "jpg", picture = true)
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: throw PdfError(Codes.IO, "The picture could not be written.")
                publish(ctx, uri)
            } catch (e: Exception) {
                discard(ctx, uri); throw e
            }
            out.add(uri)
        }
        return out
    }

    /** Recognised lines of the first page (for name suggestions and business cards). */
    fun firstPageLines(ctx: Context, session: ScanSession, langs: List<String>): List<TextLine> {
        val page = session.pages.firstOrNull() ?: return emptyList()
        val bmp = scaled(ScanProcessor.cached(session, page), 2200)
        val r = Ocr(ctx).recognizeBitmap(bmp, (bmp.height / 11.7f).roundToInt(), langs)
        return r.lines.map { TextLine(it.text, it.box.height().toFloat(), it.box.top.toFloat()) }
    }

    /** Opens the phone's Contacts app with the card's details filled in (no permission needed: the user saves). */
    fun contactIntent(c: Contact): Intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        c.name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
        c.company?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        c.title?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
        c.phones.getOrNull(0)?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        c.phones.getOrNull(1)?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
        c.phones.getOrNull(2)?.let { putExtra(ContactsContract.Intents.Insert.TERTIARY_PHONE, it) }
        c.emails.getOrNull(0)?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        c.emails.getOrNull(1)?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_EMAIL, it) }
        c.address?.let { putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
        c.website?.let { putExtra(ContactsContract.Intents.Insert.NOTES, it) }
    }
}
