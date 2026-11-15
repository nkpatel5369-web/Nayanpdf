package npdf.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import npdf.scan.Cleanup
import npdf.scan.Compose
import npdf.scan.DocDetector
import npdf.scan.Filter
import npdf.scan.Filters
import npdf.scan.Pt
import npdf.scan.Quad
import npdf.scan.Raster
import npdf.scan.Warp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** What is being scanned: decides outline detection, layout and the default name. */
enum class ScanKind(val label: String, val baseName: String) {
    WHITEBOARD("Whiteboard", "Whiteboard"),
    BOOK("Book", "Book"),
    DOCUMENT("Document", "Scan"),
    IDCARD("ID card", "ID card"),
    BUSINESSCARD("Business card", "Business card"),
}

/** Changes drawn on a corrected page, in page fractions (0..1) so they survive re-rendering. */
sealed class Edit {
    class Erase(val pts: List<Pt>, val radius: Float, val color: Int?) : Edit()
    class Ink(val pts: List<Pt>, val color: Int, val width: Float) : Edit()
    class Shape(val type: Int, val a: Pt, val b: Pt, val color: Int, val width: Float) : Edit()
    object Fingers : Edit()

    companion object {
        const val RECT = 0; const val ELLIPSE = 1; const val LINE = 2; const val ARROW = 3
    }
}

/** One photo and the outline of the document in it ([quad] null: the whole photo). */
class Part(var file: String, var quad: Quad?, var w: Int, var h: Int)

/** A page of the scan: its photo(s) and every setting needed to redraw it. */
class ScanPage(
    val id: String,
    val kind: ScanKind,
    val parts: MutableList<Part>,
    var half: Int = 0, // book spreads: 1 left page, 2 right page
    var rot: Int = 0,
    var filter: Filter = Filter.AUTO,
    var brightness: Float = 0f,
    var contrast: Float = 1f,
    var size: String = "AUTO", // AUTO, A4, LETTER, LEGAL
    val edits: MutableList<Edit> = mutableListOf(),
) {
    /** A key that changes whenever the rendered page would change. */
    fun key(): String = buildString {
        for (p in parts) append(p.file).append(p.quad?.corners?.joinToString { "%.1f,%.1f".format(it.x, it.y) }).append(p.quad?.bulge?.joinToString()).append('|')
        append(half).append(rot).append(filter).append(brightness).append(contrast).append(edits.size).append(edits.hashCode())
    }
}

/**
 * A scan in progress, kept in the app's private storage so it survives the
 * app being closed. It is removed once saved or discarded.
 */
class ScanSession(val dir: File) {
    val pages = mutableListOf<ScanPage>()
    var name: String? = null
    var kind: ScanKind = ScanKind.DOCUMENT

    fun newPhoto(): File = File(dir, "photo_${UUID.randomUUID()}.jpg")
    fun file(name: String) = File(dir, name)
    fun outFile(p: ScanPage) = File(dir, "out_${p.id}.jpg")
    fun thumbFile(p: ScanPage) = File(dir, "thumb_${p.id}.jpg")

    fun save() {
        val o = JSONObject()
        o.put("name", name ?: JSONObject.NULL)
        o.put("kind", kind.name)
        o.put("pages", JSONArray().apply { pages.forEach { put(pageJson(it)) } })
        val tmp = File(dir, "session.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "session.json"))
    }

    /** Deletes files no page refers to any more. */
    fun tidy() {
        val used = HashSet<String>()
        for (p in pages) { p.parts.forEach { used.add(it.file) }; used.add(outFile(p).name); used.add(thumbFile(p).name) }
        used.add("session.json")
        dir.listFiles()?.forEach { if (it.name !in used) it.delete() }
    }

    fun discard() { dir.deleteRecursively() }

    companion object {
        fun root(ctx: Context) = File(ctx.filesDir, "scans").apply { mkdirs() }

        fun create(ctx: Context): ScanSession = ScanSession(File(root(ctx), UUID.randomUUID().toString()).apply { mkdirs() })

        /** The most recent unfinished scan, if any. */
        fun latest(ctx: Context): ScanSession? = root(ctx).listFiles()?.filter { File(it, "session.json").exists() }
            ?.maxByOrNull { File(it, "session.json").lastModified() }?.let { load(it) }

        fun load(dir: File): ScanSession? = try {
            val o = JSONObject(File(dir, "session.json").readText())
            ScanSession(dir).apply {
                name = if (o.isNull("name")) null else o.optString("name")
                kind = runCatching { ScanKind.valueOf(o.optString("kind")) }.getOrDefault(ScanKind.DOCUMENT)
                val arr = o.getJSONArray("pages")
                for (i in 0 until arr.length()) pages.add(pageFrom(arr.getJSONObject(i)))
            }
        } catch (e: Exception) { null }

        private fun ptJson(p: Pt) = JSONArray().put(p.x.toDouble()).put(p.y.toDouble())
        private fun ptFrom(a: JSONArray) = Pt(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())

        private fun pageJson(p: ScanPage) = JSONObject().apply {
            put("id", p.id); put("kind", p.kind.name); put("half", p.half); put("rot", p.rot)
            put("filter", p.filter.name); put("brightness", p.brightness.toDouble()); put("contrast", p.contrast.toDouble()); put("size", p.size)
            put("parts", JSONArray().apply {
                p.parts.forEach { pt ->
                    put(JSONObject().apply {
                        put("file", pt.file); put("w", pt.w); put("h", pt.h)
                        pt.quad?.let { q -> put("quad", JSONArray().apply { q.corners.forEach { put(ptJson(it)) } }); put("bulge", JSONArray().apply { q.bulge.forEach { put(it.toDouble()) } }) }
                    })
                }
            })
            put("edits", JSONArray().apply {
                p.edits.forEach { e ->
                    put(when (e) {
                        is Edit.Erase -> JSONObject().put("t", "erase").put("pts", JSONArray().apply { e.pts.forEach { put(ptJson(it)) } }).put("r", e.radius.toDouble()).put("c", e.color ?: JSONObject.NULL)
                        is Edit.Ink -> JSONObject().put("t", "ink").put("pts", JSONArray().apply { e.pts.forEach { put(ptJson(it)) } }).put("c", e.color).put("w", e.width.toDouble())
                        is Edit.Shape -> JSONObject().put("t", "shape").put("s", e.type).put("a", ptJson(e.a)).put("b", ptJson(e.b)).put("c", e.color).put("w", e.width.toDouble())
                        Edit.Fingers -> JSONObject().put("t", "fingers")
                    })
                }
            })
        }

        private fun pageFrom(o: JSONObject): ScanPage {
            val parts = mutableListOf<Part>()
            val pa = o.getJSONArray("parts")
            for (i in 0 until pa.length()) {
                val j = pa.getJSONObject(i)
                val quad = j.optJSONArray("quad")?.let { q ->
                    val b = j.optJSONArray("bulge")
                    Quad(ptFrom(q.getJSONArray(0)), ptFrom(q.getJSONArray(1)), ptFrom(q.getJSONArray(2)), ptFrom(q.getJSONArray(3)),
                        FloatArray(4) { k -> b?.optDouble(k, 0.0)?.toFloat() ?: 0f })
                }
                parts.add(Part(j.getString("file"), quad, j.getInt("w"), j.getInt("h")))
            }
            val edits = mutableListOf<Edit>()
            val ea = o.optJSONArray("edits") ?: JSONArray()
            for (i in 0 until ea.length()) {
                val e = ea.getJSONObject(i)
                fun pts(): List<Pt> { val a = e.getJSONArray("pts"); return List(a.length()) { ptFrom(a.getJSONArray(it)) } }
                edits.add(when (e.getString("t")) {
                    "erase" -> Edit.Erase(pts(), e.getDouble("r").toFloat(), if (e.isNull("c")) null else e.getInt("c"))
                    "ink" -> Edit.Ink(pts(), e.getInt("c"), e.getDouble("w").toFloat())
                    "shape" -> Edit.Shape(e.getInt("s"), ptFrom(e.getJSONArray("a")), ptFrom(e.getJSONArray("b")), e.getInt("c"), e.getDouble("w").toFloat())
                    else -> Edit.Fingers
                })
            }
            return ScanPage(o.getString("id"), runCatching { ScanKind.valueOf(o.getString("kind")) }.getOrDefault(ScanKind.DOCUMENT), parts,
                o.optInt("half"), o.optInt("rot"), runCatching { Filter.valueOf(o.getString("filter")) }.getOrDefault(Filter.AUTO),
                o.optDouble("brightness", 0.0).toFloat(), o.optDouble("contrast", 1.0).toFloat(), o.optString("size", "AUTO"), edits)
        }
    }
}

/** Turns photos into corrected pages. All work runs off the main thread. */
object ScanProcessor {

    /** Longest side kept from a photo: about 290 dpi for an A4 page that fills the frame. */
    const val PHOTO_MAX = 3200

    fun toRaster(b: Bitmap): Raster {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Raster(b.width, b.height, px)
    }

    fun toBitmap(r: Raster): Bitmap = Bitmap.createBitmap(r.px, r.width, r.height, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)

    /** Decodes a picture upright (EXIF orientation applied), at most [maxSide] on its longer side. */
    fun decode(open: () -> InputStream, maxSide: Int = PHOTO_MAX): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw npdf.engine.PdfError(npdf.engine.Codes.INVALID, "The picture could not be read.")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = open().use { BitmapFactory.decodeStream(it, null, opts) } ?: throw npdf.engine.PdfError(npdf.engine.Codes.INVALID, "The picture could not be read.")
        val orientation = runCatching { open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val s = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (s < 1f) m.postScale(s, s)
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return bmp
    }

    /** Stores a photo for the scan (JPEG, upright, capped) and returns its part, with the document outline found in it. */
    fun addPhoto(session: ScanSession, bmp: Bitmap, kind: ScanKind, detect: Boolean = true): Part {
        val f = session.newPhoto()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val quad = if (detect && kind != ScanKind.WHITEBOARD) findOutline(bmp, kind) else null
        return Part(f.name, quad, bmp.width, bmp.height)
    }

    /** The document outline in a photo, refined at high resolution, or null when none is found. */
    fun findOutline(bmp: Bitmap, kind: ScanKind): Quad? {
        val small = Bitmap.createScaledBitmap(bmp, (bmp.width * minOf(1f, 1400f / max(bmp.width, bmp.height))).roundToInt().coerceAtLeast(1),
            (bmp.height * minOf(1f, 1400f / max(bmp.width, bmp.height))).roundToInt().coerceAtLeast(1), true)
        val g = toRaster(small).gray()
        val book = kind == ScanKind.BOOK
        val r = DocDetector.detect(g, minArea = if (kind == ScanKind.BUSINESSCARD || kind == ScanKind.IDCARD) 0.05f else 0.10f, curvedEdges = book) ?: return null
        val q = DocDetector.refine(g, r.quad, curved = book)
        return q.scaled(bmp.width.toFloat() / small.width)
    }

    /** Draws the corrected page: outline, layout, rotation, filter, then the edits. */
    fun render(session: ScanSession, page: ScanPage, maxSide: Int = Warp.MAX_SIDE, photoMax: Int = PHOTO_MAX): Bitmap {
        fun corrected(part: Part): Raster {
            val src = toRaster(decode({ session.file(part.file).inputStream() }, photoMax))
            val q = part.quad?.let { scaleQuad(it, part, src) }
            return if (q == null) src else Warp.warp(src, q, maxSide)
        }
        var r: Raster
        if (page.kind == ScanKind.IDCARD) {
            val front = Filters.apply(corrected(page.parts[0]), page.filter, page.brightness, page.contrast)
            val back = page.parts.getOrNull(1)?.let { Filters.apply(corrected(it), page.filter, page.brightness, page.contrast) }
            r = Compose.idCardPage(front, back).rotated(page.rot)
        } else {
            r = corrected(page.parts[0])
            if (page.half != 0) r = Compose.splitSpread(r)[page.half - 1]
            r = r.rotated(page.rot)
            r = Filters.apply(r, page.filter, page.brightness, page.contrast)
        }
        // Clean-up edits work on the pixels; markup is drawn on top.
        for (e in page.edits) when (e) {
            is Edit.Erase -> {
                val mask = BooleanArray(r.px.size)
                Cleanup.brush(mask, r.width, r.height, e.pts.map { Pt(it.x * r.width, it.y * r.height) }, e.radius * r.width)
                if (e.color == null) Cleanup.fillFromSurroundings(r, mask) else Cleanup.fillColor(r, mask, e.color)
            }
            Edit.Fingers -> Cleanup.removeFingers(r)
            else -> {}
        }
        val bmp = toBitmap(r)
        val marks = page.edits.filter { it is Edit.Ink || it is Edit.Shape }
        if (marks.isNotEmpty()) drawMarks(bmp, marks)
        return bmp
    }

    /** Scales an outline stored for the photo's full size to the decoded photo (which may be smaller). */
    private fun scaleQuad(q: Quad, part: Part, src: Raster): Quad {
        val s = src.width.toFloat() / part.w
        return if (kotlin.math.abs(s - 1f) < 1e-3) q else q.scaled(s)
    }

    fun drawMarks(bmp: Bitmap, marks: List<Edit>) {
        val c = Canvas(bmp)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (e in marks) when (e) {
            is Edit.Ink -> {
                p.color = e.color; p.strokeWidth = e.width * w
                val path = Path()
                e.pts.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x * w, pt.y * h) else path.lineTo(pt.x * w, pt.y * h) }
                c.drawPath(path, p)
            }
            is Edit.Shape -> {
                p.color = e.color; p.strokeWidth = e.width * w
                val a = Pt(e.a.x * w, e.a.y * h); val b = Pt(e.b.x * w, e.b.y * h)
                val rect = RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
                when (e.type) {
                    Edit.RECT -> c.drawRect(rect, p)
                    Edit.ELLIPSE -> c.drawOval(rect, p)
                    else -> {
                        c.drawLine(a.x, a.y, b.x, b.y, p)
                        if (e.type == Edit.ARROW) {
                            val ang = Math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                            val len = p.strokeWidth * 5
                            for (d in doubleArrayOf(2.6, -2.6)) {
                                c.drawLine(b.x, b.y, (b.x + len * Math.cos(ang + d)).toFloat(), (b.y + len * Math.sin(ang + d)).toFloat(), p)
                            }
                        }
                    }
                }
            }
            else -> {}
        }
    }

    /** The rendered page, cached on disk until a setting changes. */
    private val keys = HashMap<String, String>()

    fun cached(session: ScanSession, page: ScanPage): Bitmap {
        val f = session.outFile(page)
        val key = page.key()
        if (f.exists() && keys[f.absolutePath] == key) BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
        val bmp = render(session, page)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        keys[f.absolutePath] = key
        val t = session.thumbFile(page)
        val s = 360f / max(bmp.width, bmp.height)
        Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
            .let { th -> t.outputStream().use { th.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        return bmp
    }

    fun thumb(session: ScanSession, page: ScanPage): Bitmap {
        val t = session.thumbFile(page)
        if (t.exists() && keys[session.outFile(page).absolutePath] == page.key()) BitmapFactory.decodeFile(t.absolutePath)?.let { return it }
        cached(session, page)
        return BitmapFactory.decodeFile(t.absolutePath)
    }

    /** The rendered page for the screen: at most [maxSide] pixels on its longer side. */
    fun display(session: ScanSession, page: ScanPage, maxSide: Int = 1600): Bitmap {
        val f = session.outFile(page)
        if (!(f.exists() && keys[f.absolutePath] == page.key())) cached(session, page).recycle()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw npdf.engine.PdfError(npdf.engine.Codes.IO, "The page could not be shown.")
    }

    /** A small preview of [page] with another filter (for the filter strip). */
    fun preview(session: ScanSession, page: ScanPage, filter: Filter): Bitmap {
        val p = ScanPage(page.id, page.kind, page.parts, page.half, page.rot, filter, page.brightness, page.contrast, page.size)
        return render(session, p, maxSide = 320, photoMax = 900)
    }

    /** A page's photo for the crop editor, at most [maxSide] pixels. */
    fun photo(session: ScanSession, part: Part, maxSide: Int = 1600): Bitmap = decode({ session.file(part.file).inputStream() }, maxSide)

    fun newPage(kind: ScanKind, part: Part, filter: Filter, half: Int = 0) =
        ScanPage(UUID.randomUUID().toString(), kind, mutableListOf(part), half = half,
            filter = if (kind == ScanKind.WHITEBOARD && filter == Filter.AUTO) Filter.WHITEBOARD else filter)
}
