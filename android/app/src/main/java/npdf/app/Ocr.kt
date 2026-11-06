package npdf.app

import android.content.Context
import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import npdf.engine.Codes
import npdf.engine.PdfError
import npdf.engine.TextLayer
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Text recognition with Tesseract on the phone (language data is inside the
 * app). Recognised words become an invisible, searchable text layer; the page
 * picture is not changed. Recognised text is never guaranteed to be accurate.
 */
class Ocr(private val ctx: Context) {

    companion object {
        val LANGUAGES = linkedMapOf("eng" to "English", "hin" to "Hindi", "guj" to "Gujarati")
        const val DPI = 300f
        const val MAX_SIDE = 3500
    }

    class PageResult(val words: List<TextLayer.Word>, val meanConfidence: Int)

    /** Copies the language data from the app package once (Tesseract reads it from files). */
    private fun dataPath(langs: List<String>): String {
        val base = File(ctx.filesDir, "ocr")
        val td = File(base, "tessdata").apply { mkdirs() }
        for (l in langs) {
            val f = File(td, "$l.traineddata")
            val size = ctx.assets.openFd("tessdata/$l.traineddata").use { it.length }
            if (!f.exists() || f.length() != size) {
                val tmp = File(td, "$l.part")
                ctx.assets.open("tessdata/$l.traineddata").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(f)
            }
        }
        return base.absolutePath
    }

    /** The page drawn at OCR resolution (300 dpi, longest side capped). */
    fun pageBitmap(session: DocSession, page: Int): Pair<Bitmap, Float> {
        val size = session.sizes[page]
        var scale = DPI / 72f
        val longest = max(size.w, size.h) * scale
        if (longest > MAX_SIDE) scale *= MAX_SIDE / longest
        val w = max(1, (size.w * scale).roundToInt()); val h = max(1, (size.h * scale).roundToInt())
        val bmp = session.renderTile(page, scale, 0, 0, w, h, Cookie())
            ?: throw PdfError(Codes.DAMAGED, "Page ${page + 1} could not be drawn.")
        return bmp to scale
    }

    /** Recognises one page. Called on a background thread. */
    fun recognize(session: DocSession, page: Int, langs: List<String>): PageResult {
        val (bmp, scale) = pageBitmap(session, page)
        val api = TessBaseAPI()
        try {
            if (!api.init(dataPath(langs), langs.joinToString("+"))) throw PdfError(Codes.NOT_SUPPORTED, "The OCR language data could not be loaded.")
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            // A bitmap carries no resolution. Left to guess, Tesseract over-estimates it and
            // its layout analysis then drops whole table columns, so state the real one.
            api.setVariable("user_defined_dpi", (scale * 72f).roundToInt().toString())
            api.setImage(bmp)
            api.getUTF8Text()
            val words = mutableListOf<TextLayer.Word>()
            val it = api.resultIterator ?: return PageResult(emptyList(), 0)
            try {
                it.begin()
                do {
                    val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD) ?: continue
                    if (text.isBlank()) continue
                    val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    val conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    val box = Rect(r.left / scale, r.top / scale, r.right / scale, r.bottom / scale)
                    val size = session.sizes[page]
                    val clipped = Rect(max(0f, box.x0), max(0f, box.y0), min(size.w, box.x1), min(size.h, box.y1))
                    if (clipped.x1 > clipped.x0 && clipped.y1 > clipped.y0) words.add(TextLayer.Word(text, clipped, conf))
                } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            } finally {
                it.delete()
            }
            return PageResult(words, api.meanConfidence())
        } finally {
            api.recycle()
            bmp.recycle()
        }
    }
}
