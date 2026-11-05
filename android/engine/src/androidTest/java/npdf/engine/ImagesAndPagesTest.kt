package npdf.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ImagesAndPagesTest {

    private fun near(a: Rect, b: Rect, tol: Float = 0.6f) =
        abs(a.x0 - b.x0) < tol && abs(a.y0 - b.y0) < tol && abs(a.x1 - b.x1) < tol && abs(a.y1 - b.y1) < tol

    private fun imageObjects(doc: PDFDocument): List<PDFObject> {
        val out = mutableListOf<PDFObject>()
        for (i in 1 until doc.countObjects()) {
            val o = doc.newIndirect(i, 0).resolve()
            if (o.isStream && o.get("Subtype").let { it.isName && it.asName() == "Image" }) out.add(o)
        }
        return out
    }

    private fun rgbAt(pix: Pixmap, x: Int, y: Int): Triple<Int, Int, Int> {
        val o = (y - pix.y) * pix.stride + (x - pix.x) * pix.numberOfComponents
        val s = pix.samples
        return Triple(s[o].toInt() and 0xFF, s[o + 1].toInt() and 0xFF, s[o + 2].toInt() and 0xFF)
    }

    @Test
    fun insertMoveDeleteImage() {
        val d = Fixtures.open("a.pdf")
        val placed = ImageEdit.insertImage(d, 1, Rect(100f, 442f, 300f, 542f), Fixtures.bytes("photo.jpg"))
        // 4:3 photo fitted into a 2:1 box: full height, centred.
        assertTrue("placed $placed", abs((placed.x1 - placed.x0) / (placed.y1 - placed.y0) - 4f / 3f) < 0.02f)
        val list = ImageEdit.list(d, 1)
        assertEquals(1, list.size)
        assertTrue("listed ${list[0].rect} vs $placed", near(list[0].rect, placed))
        val target = Rect(50f, 717f, 150f, 792f)
        ImageEdit.edit(d, 1, list[0].rect, "transform", newRect = target)
        val moved = ImageEdit.list(d, 1)[0].rect
        assertTrue("moved $moved", near(moved, target))
        val r = ImageEdit.edit(d, 1, moved, "delete")
        assertEquals(true, r.imageDataRemoved)
        val saved = Fixtures.roundTrip(d)
        assertTrue(ImageEdit.list(saved, 1).isEmpty())
        assertTrue("no image data left", imageObjects(saved).isEmpty())
        assertTrue(Fixtures.text(saved, 1).contains("A page 1"))
    }

    @Test
    fun replaceImageKeepsFrameAndProportions() {
        val d = Fixtures.open("a.pdf")
        ImageEdit.insertImage(d, 1, Rect(100f, 442f, 300f, 542f), Fixtures.bytes("photo.jpg"))
        val old = ImageEdit.list(d, 1)[0].rect
        val r = ImageEdit.edit(d, 1, old, "replace", replacement = Fixtures.bytes("logo.png"))
        assertEquals(true, r.imageDataRemoved)
        val saved = Fixtures.roundTrip(d)
        val imgs = ImageEdit.list(saved, 1)
        assertEquals(1, imgs.size)
        val nb = imgs[0].rect
        assertTrue("inside the old frame", nb.x0 >= old.x0 - 0.6f && nb.x1 <= old.x1 + 0.6f && nb.y0 >= old.y0 - 0.6f && nb.y1 <= old.y1 + 0.6f)
        assertTrue("2:1 logo", abs((nb.x1 - nb.x0) / (nb.y1 - nb.y0) - 2f) < 0.05f)
        val dims = imageObjects(saved).map { it.get("Width").asInteger() to it.get("Height").asInteger() }
        assertFalse("old photo data gone: $dims", dims.contains(1200 to 900))
    }

    /** A phone photo stored sideways with EXIF orientation 6 must appear upright. */
    @Test
    fun exifOrientationIsHonoured() {
        val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, 100f, 50f, Paint().apply { color = Color.RED })
        }
        val f = Fixtures.out("exif.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val d = PDFDocument()
        Pages.addImagePage(d, f.readBytes(), Pages.PageSize.FIT, 0f)
        val page = d.loadPage(0)
        val b = page.bounds
        assertTrue("portrait after rotation: $b", (b.y1 - b.y0) > (b.x1 - b.x0))
        val pix = page.toPixmap(Matrix.Identity(), ColorSpace.DeviceRGB, false, true)
        val w = b.x1 - b.x0; val h = b.y1 - b.y0
        val tr = rgbAt(pix, (w * 0.75f).toInt(), (h * 0.25f).toInt())
        val tl = rgbAt(pix, (w * 0.25f).toInt(), (h * 0.25f).toInt())
        val br = rgbAt(pix, (w * 0.75f).toInt(), (h * 0.75f).toInt())
        assertTrue("top-right red: $tr", tr.first > 200 && tr.second < 90 && tr.third < 90)
        assertTrue("top-left white: $tl", tl.first > 200 && tl.second > 200 && tl.third > 200)
        assertTrue("bottom-right white: $br", br.second > 200)
    }

    @Test
    fun rotateDeleteReorderInsertExtract() {
        val d = Fixtures.open("twenty.pdf")
        Pages.rotate(d, listOf(1), 90)
        assertEquals(90, d.findPage(0).get("Rotate").asInteger())
        Pages.rotate(d, listOf(1), 270)
        assertEquals(0, d.findPage(0).get("Rotate").asInteger())
        Pages.delete(d, listOf(2, 3))
        assertEquals(18, d.countPages())
        assertTrue(Fixtures.text(d, 2).contains("Report page 4"))
        Pages.reorder(d, (18 downTo 1).toList())
        assertTrue(Fixtures.text(d, 1).contains("Report page 20"))
        Pages.insertBlank(d, 0)
        assertEquals(19, d.countPages())
        assertTrue(Fixtures.text(d, 1).isBlank())
        val a = Fixtures.open("a.pdf")
        Pages.insertFrom(d, 1, a)
        assertEquals(21, d.countPages())
        assertTrue(Fixtures.text(d, 2).contains("A page 1"))
        val out = Fixtures.out("extract.pdf")
        Pages.extract(d, listOf(2, 3), out)
        val e = Pdf.open(out.absolutePath)
        assertEquals(2, e.countPages())
        assertTrue(Fixtures.text(e, 2).contains("A page 2"))
        try {
            Pages.delete(d, (1..d.countPages()).toList())
            throw AssertionError("deleting every page must be refused")
        } catch (e2: PdfError) {
            assertEquals(Codes.INVALID, e2.code)
        }
    }

    @Test
    fun mergeSplitAndImagesToPdf() {
        val out = Fixtures.out("merged.pdf")
        val n = Pages.merge(listOf(
            Pages.Input(Fixtures.file("a.pdf").absolutePath),
            Pages.Input(Fixtures.file("b.pdf").absolutePath),
            Pages.Input(Fixtures.file("logo.png").absolutePath),
            Pages.Input(Fixtures.file("encrypted.pdf").absolutePath, password = "owner456"),
        ), out)
        assertEquals(2 + 3 + 1 + 2, n)
        val m = Pdf.open(out.absolutePath)
        assertEquals(8, m.countPages())
        assertTrue(Fixtures.text(m, 3).contains("B page 1"))
        assertTrue(Fixtures.text(m, 7).contains("A page 1"))

        val tw = Fixtures.open("twenty.pdf")
        val dir = Fixtures.out("split").apply { mkdirs() }
        val files = Pages.split(tw, dir, "report", ranges = "1-5,6-20")
        assertEquals(listOf(5, 15), files.map { Pdf.open(it.absolutePath).countPages() })
        val every = Pages.split(tw, dir, "report", everyN = 7)
        assertEquals(listOf(7, 7, 6), every.map { Pdf.open(it.absolutePath).countPages() })
        assertTrue("never overwrites: ${every.map { it.name }}", every[0].name == "report_1 (1).pdf")

        val pdf = Fixtures.out("images.pdf")
        Pages.imagesToPdf(listOf(Fixtures.bytes("photo.jpg"), Fixtures.bytes("logo.png")), pdf, Pages.PageSize.A4)
        val im = Pdf.open(pdf.absolutePath)
        assertEquals(2, im.countPages())
        val b = im.loadPage(0).bounds
        assertTrue("landscape A4 for a landscape photo: $b", abs(b.x1 - 841.89f) < 1f && abs(b.y1 - 595.28f) < 1f)
    }
}
