package npdf.engine

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Pixmap
import java.io.File
import kotlin.math.hypot

/**
 * Size reduction (same presets as the Windows engine). Pictures shown at more
 * than the preset's threshold resolution are resampled to its target resolution
 * and stored as JPEG at its quality; then unused objects are dropped and every
 * stream is compressed. The result is kept only when it is smaller.
 */
object Compress {

    class Preset(val label: String, val thresholdDpi: Int?, val targetDpi: Int?, val quality: Int?)

    val PRESETS = linkedMapOf(
        "maximum" to Preset("Maximum Quality (lossless)", null, null, null),
        "high" to Preset("High Quality", 300, 220, 88),
        "balanced" to Preset("Balanced Compression", 200, 150, 75),
        "strong" to Preset("High Compression", 130, 96, 55),
    )

    class Result(val before: Long, val after: Long, val imagesResampled: Int, val kept: Boolean, val preset: String)

    /** Compresses [src] (already open as [doc]) into [dest]. When nothing is gained, [dest] is not written. */
    fun compress(doc: PDFDocument, src: File, dest: File, preset: String = "balanced", progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val p = PRESETS[preset] ?: throw PdfError(Codes.INVALID, "Unknown compression setting.")
        var resampled = 0
        if (p.thresholdDpi != null && p.targetDpi != null && p.quality != null) {
            resampled = resampleImages(doc, p.thresholdDpi, p.targetDpi, p.quality, progress)
        }
        val tmp = File(dest.parentFile, ".${dest.name}.${System.nanoTime()}.tmp")
        try {
            Pdf.save(doc, tmp, "garbage=deduplicate,compress,compress-fonts,compress-images")
            val before = src.length()
            val after = tmp.length()
            if (after >= before) {
                return Result(before, after, resampled, kept = false, preset = preset)
            }
            Pages.verify(tmp, doc.countPages())
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true) }
            return Result(before, after, resampled, kept = true, preset = preset)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Highest resolution (dots per inch) at which each image object is drawn anywhere in the document. */
    private fun imageResolutions(doc: PDFDocument): Map<Int, Pair<PDFObject, Float>> {
        val out = HashMap<Int, Pair<PDFObject, Float>>()
        for (i in 0 until doc.countPages()) {
            val page = doc.loadPage(i) as PDFPage
            val (_, found) = ContentStream.images(Pdf.pageContent(page), page.getObject().getInheritable("Resources"))
            for (pl in found) {
                val obj = pl.obj ?: continue
                if (!obj.isIndirect) continue
                val w = obj.get("Width").takeIf { it.isNumber }?.asFloat() ?: continue
                val h = obj.get("Height").takeIf { it.isNumber }?.asFloat() ?: continue
                // Size of the placement in points along the image's own axes.
                val sw = hypot(pl.ctm.a, pl.ctm.b)
                val sh = hypot(pl.ctm.c, pl.ctm.d)
                if (sw < 1f || sh < 1f) continue
                val dpi = maxOf(w / (sw / 72f), h / (sh / 72f))
                val id = obj.asIndirect()
                val prev = out[id]
                if (prev == null || dpi > prev.second) out[id] = obj to dpi
            }
        }
        return out
    }

    private fun resampleImages(doc: PDFDocument, threshold: Int, target: Int, quality: Int, progress: (Int, Int) -> Unit): Int {
        val images = imageResolutions(doc)
        var done = 0
        var k = 0
        for ((_, entry) in images) {
            progress(k++, images.size)
            val (obj, dpi) = entry
            if (dpi <= threshold) continue
            if (obj.get("ImageMask").let { it.isBoolean && it.asBoolean() }) continue
            val bpc = obj.get("BitsPerComponent").takeIf { it.isNumber }?.asInteger() ?: 8
            if (bpc == 1) continue // black-and-white scans are already small (CCITT/JBIG2)
            val filter = obj.get("Filter").let { if (it.isArray && it.size() > 0) it.get(it.size() - 1) else it }
            if (filter.isName && filter.asName() in setOf("JBIG2Decode", "CCITTFaxDecode")) continue
            try {
                if (resampleOne(doc, obj, target / dpi, quality)) done++
            } catch (e: RuntimeException) {
                // An image MuPDF cannot decode is left exactly as it was.
            }
        }
        return done
    }

    private fun resampleOne(doc: PDFDocument, obj: PDFObject, factor: Float, quality: Int): Boolean {
        val image = doc.loadImage(obj)
        val w = image.width; val h = image.height
        val nw = maxOf(1, (w * factor).toInt()); val nh = maxOf(1, (h * factor).toInt())
        if (nw >= w || nh >= h) return false
        val gray = image.numberOfComponents == 1
        val cs = if (gray) ColorSpace.DeviceGray else ColorSpace.DeviceRGB
        val pix = Pixmap(cs, 0, 0, nw, nh, false)
        try {
            pix.clear(255)
            val dev = DrawDevice(pix)
            try {
                // The image unit square, flipped so the first row is at the top.
                dev.fillImage(image, Matrix(nw.toFloat(), 0f, 0f, nh.toFloat(), 0f, 0f), 1f, 0)
                dev.close()
            } finally {
                dev.destroy()
            }
            val jpeg = pix.asJPEG(quality, false)
            val oldLen = obj.readRawStream().size
            if (jpeg.length >= oldLen * 0.9) return false
            obj.writeRawStream(jpeg)
            obj.put("Filter", doc.newName("DCTDecode"))
            obj.delete("DecodeParms")
            obj.delete("Decode")
            obj.delete("Intent")
            obj.put("Width", nw)
            obj.put("Height", nh)
            obj.put("BitsPerComponent", 8)
            obj.put("ColorSpace", doc.newName(if (gray) "DeviceGray" else "DeviceRGB"))
            return true
        } finally {
            pix.destroy()
        }
    }
}
