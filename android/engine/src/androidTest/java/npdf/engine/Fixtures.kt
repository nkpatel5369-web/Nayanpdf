package npdf.engine

import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
import java.io.File

/** Synthetic test documents (see scripts/make_android_test_assets.py) and helpers. */
object Fixtures {
    private val instr get() = InstrumentationRegistry.getInstrumentation()

    /** A fresh writable copy of an asset in the app's cache directory. */
    fun file(name: String): File {
        val dir = File(instr.targetContext.cacheDir, "fixtures").apply { mkdirs() }
        val f = File(dir, "${System.nanoTime()}_$name")
        instr.context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    fun bytes(name: String): ByteArray = instr.context.assets.open(name).use { it.readBytes() }

    fun open(name: String, password: String? = null): PDFDocument = Pdf.open(file(name).absolutePath, password)

    fun out(name: String): File {
        val dir = File(instr.targetContext.cacheDir, "out").apply { mkdirs() }
        return File(dir, "${System.nanoTime()}_$name")
    }

    /** Saves and re-opens, so assertions see what a reader of the file would see. */
    fun roundTrip(doc: PDFDocument, password: String? = null): PDFDocument {
        val f = out("rt.pdf")
        Pdf.save(doc, f)
        return Pdf.open(f.absolutePath, password)
    }

    fun text(doc: PDFDocument, page: Int): String = DocInfo.pageText(doc, page)

    /** The box of the line whose text (trimmed) equals [line]. */
    fun line(doc: PDFDocument, page: Int, line: String): Rect {
        val t = PageText.of(doc.loadPage(page - 1) as PDFPage)
        return t.lines.firstOrNull { it.text.trim() == line }?.bbox
            ?: throw AssertionError("line '$line' not found; lines: ${t.lines.map { it.text }}")
    }

    /** First span whose trimmed text equals [s]. */
    fun span(doc: PDFDocument, page: Int, s: String): Span? =
        PageText.of(doc.loadPage(page - 1) as PDFPage).lines.flatMap { it.spans }.firstOrNull { it.text.trim() == s }

    fun spans(doc: PDFDocument, page: Int): Map<String, Span> =
        PageText.of(doc.loadPage(page - 1) as PDFPage).lines.flatMap { it.spans }.associateBy { it.text.trim() }
}
