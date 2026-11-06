package npdf.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import npdf.engine.Codes
import npdf.engine.PageText
import npdf.engine.Pdf
import npdf.engine.PdfError
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * One open document. Every call into the PDF library for this document runs on
 * the single engine thread; pages are drawn from display lists on two render
 * threads, which MuPDF supports. Each edit is a journal operation, so it can be
 * undone, and a failed edit is rolled back completely.
 */
class DocSession private constructor(
    private val app: Context,
    var uri: Uri?,
    var name: String,
    private var path: String,
    private var pfd: ParcelFileDescriptor?,
    var password: String?,
    var doc: PDFDocument,
) {
    class PageSize(val w: Float, val h: Float)

    @Volatile var sizes: List<PageSize> = emptyList(); private set
    @Volatile var version = 0; private set
    @Volatile var dirty = false; private set
    private val pageVersions = HashMap<Int, Int>()
    private val lists = HashMap<Int, DisplayList>()
    private val texts = object : LinkedHashMap<Int, PageText>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageText>?) = size > 24
    }
    private val main = Handler(Looper.getMainLooper())
    val listeners = mutableListOf<(Set<Int>?) -> Unit>()
    var restoredJournal = false; private set

    companion object {
        private fun named(prefix: String) = object : ThreadFactory {
            private val n = AtomicInteger()
            override fun newThread(r: Runnable) = Thread(r, "$prefix-${n.incrementAndGet()}")
        }
        val engine = Executors.newSingleThreadExecutor(named("npdf-engine"))
        val engineDispatcher = engine.asCoroutineDispatcher()
        val renderPool = Executors.newFixedThreadPool(2, named("npdf-render"))

        /** The document open in the reader, shared with the page organiser. */
        @Volatile var current: DocSession? = null

        /** Opens [uri]. The file is read in place when the provider allows it, otherwise copied first. */
        suspend fun open(ctx: Context, uri: Uri, password: String?): DocSession = withContext(engineDispatcher) {
            val app = ctx.applicationContext
            val name = Files.displayName(app, uri)
            val (path, pfd) = source(app, uri, name)
            val doc = try {
                Pdf.open(path, password)
            } catch (e: Throwable) {
                pfd?.close()
                throw e
            }
            DocSession(app, uri, name, path, pfd, password, doc).also { it.start() }
        }

        /** Opens a file of our own (a result written to the working area). */
        suspend fun openFile(ctx: Context, file: File, uri: Uri?, name: String, password: String?): DocSession = withContext(engineDispatcher) {
            val doc = Pdf.open(file.absolutePath, password)
            DocSession(ctx.applicationContext, uri, name, file.absolutePath, null, password, doc).also { it.start() }
        }

        private fun source(app: Context, uri: Uri, name: String): Pair<String, ParcelFileDescriptor?> {
            if (uri.scheme == ContentResolver.SCHEME_FILE) return (uri.path ?: throw PdfError(Codes.INVALID, "Invalid file.")) to null
            try {
                val pfd = app.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    // Readable in place only if the descriptor is a real, seekable file.
                    Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET)
                    val p = "/proc/self/fd/${pfd.fd}"
                    if (File(p).canRead()) return p to pfd
                    pfd.close()
                }
            } catch (e: Exception) {
                // Pipes and some providers cannot be read in place.
            }
            val f = Files.temp(app, name)
            Files.copyToFile(app, uri, f)
            return f.absolutePath to null
        }
    }

    private fun start() {
        doc.enableJournal()
        computeSizes()
        restoreJournal()
    }

    // -------------------------------------------------------------- page sizes

    /** Page sizes from the page dictionaries (fast even for thousands of pages: no page is parsed). */
    private fun computeSizes() {
        val n = doc.countPages()
        val out = ArrayList<PageSize>(n)
        for (i in 0 until n) {
            out.add(try { sizeFromDict(i) } catch (e: RuntimeException) { null } ?: run {
                val b = doc.loadPage(i).bounds
                PageSize(b.x1 - b.x0, b.y1 - b.y0)
            })
        }
        sizes = out
    }

    private fun sizeFromDict(i: Int): PageSize? {
        val obj = doc.findPage(i)
        fun box(name: String): FloatArray? {
            val a = obj.getInheritable(name)
            if (!a.isArray || a.size() != 4) return null
            val v = FloatArray(4) { a.get(it).asFloat() }
            return floatArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
        }
        val media = box("MediaBox") ?: return null
        val crop = box("CropBox")?.let { c ->
            floatArrayOf(maxOf(c[0], media[0]), maxOf(c[1], media[1]), minOf(c[2], media[2]), minOf(c[3], media[3]))
        }?.takeIf { it[2] > it[0] && it[3] > it[1] } ?: media
        var w = crop[2] - crop[0]
        var h = crop[3] - crop[1]
        val unit = obj.getInheritable("UserUnit").takeIf { it.isNumber }?.asFloat() ?: 1f
        if (abs(unit - 1f) > 0.001f) return null
        val rot = ((obj.getInheritable("Rotate").takeIf { it.isNumber }?.asInteger() ?: 0) % 360 + 360) % 360
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        if (w <= 0f || h <= 0f) return null
        return PageSize(w, h)
    }

    // --------------------------------------------------------------- access

    /** Runs [block] with the document on the engine thread. */
    suspend fun <T> read(block: (PDFDocument) -> T): T = withContext(engineDispatcher) { block(doc) }

    /**
     * Runs an edit as one undoable operation. If [block] throws, every change it
     * made is rolled back. [pages] are the 0-based pages to redraw; null means the
     * page list itself may have changed.
     */
    suspend fun <T> edit(label: String, pages: Set<Int>?, block: (PDFDocument) -> T): T = withContext(engineDispatcher) {
        doc.beginOperation(label)
        val result = try {
            block(doc)
        } catch (e: Throwable) {
            try { doc.abandonOperation() } catch (e2: Throwable) { /* nothing was recorded */ }
            invalidate(null)
            throw e
        }
        doc.endOperation()
        dirty = true
        invalidate(pages)
        result
    }

    suspend fun canUndo() = read { it.canUndo() }
    suspend fun canRedo() = read { it.canRedo() }

    suspend fun undo() = withContext(engineDispatcher) {
        if (doc.canUndo()) { doc.undo(); dirty = true; invalidate(null) }
    }

    suspend fun redo() = withContext(engineDispatcher) {
        if (doc.canRedo()) { doc.redo(); dirty = true; invalidate(null) }
    }

    /** Engine thread only. */
    private fun invalidate(pages: Set<Int>?) {
        if (pages == null) {
            lists.clear(); texts.clear()
            synchronized(pageVersions) { pageVersions.clear() }
            computeSizes()
        } else {
            for (p in pages) {
                lists.remove(p); texts.remove(p)
                synchronized(pageVersions) { pageVersions[p] = (pageVersions[p] ?: 0) + 1 }
            }
        }
        version++
        val copy = pages?.toSet()
        main.post { listeners.toList().forEach { it(copy) } }
    }

    fun pageVersion(page: Int): Int = synchronized(pageVersions) { pageVersions[page] ?: 0 }

    /** Engine thread only: the cached text of a page (characters, lines, selection and search). */
    fun pageText(page: Int): PageText = texts.getOrPut(page) { PageText.of(doc.loadPage(page) as PDFPage) }

    private fun displayList(page: Int): DisplayList = lists.getOrPut(page) { doc.loadPage(page).toDisplayList(true) }

    /**
     * Draws a w x h tile whose top-left corner is at device position (x, y) of
     * page [page] drawn at [scale] pixels per point. Called on a render thread.
     */
    fun renderTile(page: Int, scale: Float, x: Int, y: Int, w: Int, h: Int, cookie: Cookie): Bitmap? {
        val dl = engine.submit<DisplayList?> { if (page < sizes.size) displayList(page) else null }.get() ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val dev = AndroidDrawDevice(bmp, x, y, 0, 0, w, h, true)
        try {
            dl.run(dev, Matrix.Scale(scale), cookie)
            dev.close()
        } finally {
            dev.destroy()
        }
        return bmp
    }

    // ----------------------------------------------------------------- saving

    /** Writes the current state to [dest] (full rewrite, encryption kept). */
    suspend fun writeTo(dest: File, options: String = "garbage=compact,compress") = read { Pdf.save(it, dest, options) }

    /** A file with the current content, for sharing, printing or exporting. */
    suspend fun snapshot(): File {
        val f = Files.temp(app, name)
        writeTo(f)
        return f
    }

    /**
     * Saves to [target] (the original or a new file). The document is then
     * re-opened from the saved copy, so it never reads a file being rewritten.
     */
    suspend fun saveTo(target: Uri, newName: String? = null) {
        val tmp = Files.temp(app, newName ?: name)
        writeTo(tmp)
        withContext(engineDispatcher) {
            val keepPassword = password
            doc.destroy()
            pfd?.close(); pfd = null
            try {
                Files.writeToUri(app, tmp, target)
            } finally {
                doc = Pdf.open(tmp.absolutePath, keepPassword)
                doc.enableJournal()
                path = tmp.absolutePath
            }
            uri = target
            newName?.let { name = it }
            dirty = false
            dropJournal()
            invalidate(null)
        }
    }

    // ---------------------------------------------------------------- journal

    private fun journalFile(): File? {
        val u = uri ?: return null
        val key = Integer.toHexString((u.toString() + "|" + name).hashCode())
        return File(Files.workDir(app, "journals"), "$key.journal")
    }

    /**
     * Keeps unsaved edits if Android stops the app in the background. Not done
     * for password-protected files, whose journal could hold decrypted content.
     */
    suspend fun persistJournal() = withContext(engineDispatcher) {
        val f = journalFile() ?: return@withContext
        if (dirty && !doc.needsPassword() && doc.isUnencryptedPDF) {
            try { doc.saveJournal(f.absolutePath) } catch (e: RuntimeException) { f.delete() }
        }
    }

    private fun restoreJournal() {
        val f = journalFile() ?: return
        if (!f.exists() || !doc.isUnencryptedPDF) return
        try {
            doc.loadJournal(f.absolutePath)
            if (doc.canUndo()) {
                restoredJournal = true
                dirty = true
                computeSizes()
            }
        } catch (e: RuntimeException) {
            // The file changed since: the old edits do not apply.
            f.delete()
        }
    }

    fun dropJournal() { journalFile()?.delete() }

    fun close() {
        engine.execute {
            try {
                texts.clear(); lists.clear()
                doc.destroy()
            } catch (e: Throwable) {
                // already closed
            }
            try { pfd?.close() } catch (e: Exception) { /* ignore */ }
        }
        if (current === this) current = null
    }
}
