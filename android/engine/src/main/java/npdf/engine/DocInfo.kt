package npdf.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Quad

/** Document properties, bookmarks and search. */
object DocInfo {

    val FIELDS = linkedMapOf(
        "Title" to Document.META_INFO_TITLE, "Author" to Document.META_INFO_AUTHOR,
        "Subject" to Document.META_INFO_SUBJECT, "Keywords" to Document.META_INFO_KEYWORDS,
        "Creator" to Document.META_INFO_CREATOR, "Producer" to Document.META_INFO_PRODUCER,
    )

    fun metadata(doc: PDFDocument): Map<String, String> =
        FIELDS.mapValues { (_, key) -> (try { doc.getMetaData(key) } catch (e: RuntimeException) { null }).orEmpty() }

    fun setMetadata(doc: PDFDocument, values: Map<String, String>) {
        Pdf.require(doc, Document.PERMISSION_EDIT, "changing document properties")
        for ((k, v) in values) {
            val key = FIELDS[k] ?: continue
            doc.setMetaData(key, v)
        }
    }

    fun format(doc: PDFDocument): String = (try { doc.getMetaData(Document.META_FORMAT) } catch (e: RuntimeException) { null }).orEmpty()

    class Bookmark(val title: String, val page: Int, val depth: Int)

    /** Bookmarks flattened in reading order; [page] is 1-based, 0 when the target is not a page. */
    fun bookmarks(doc: PDFDocument): List<Bookmark> {
        val out = mutableListOf<Bookmark>()
        fun walk(items: Array<Outline>?, depth: Int) {
            for (o in items ?: return) {
                val page = try { doc.pageNumberFromLocation(doc.resolveLink(o)) + 1 } catch (e: RuntimeException) { 0 }
                out.add(Bookmark(o.title ?: "", page, depth))
                walk(o.down, depth + 1)
            }
        }
        walk(try { doc.loadOutline() } catch (e: RuntimeException) { null }, 0)
        return out
    }

    class Hit(val page: Int, val quads: Array<Quad>)

    /** Finds [query] on one page (1-based); each hit may span several lines (several quads). */
    fun searchPage(doc: PDFDocument, pageNumber: Int, query: String, matchCase: Boolean = false): List<Hit> {
        if (query.isBlank()) return emptyList()
        val text = PageText.of(doc.loadPage(pageNumber - 1) as com.artifex.mupdf.fitz.PDFPage)
        return text.search(query, matchCase).map { Hit(pageNumber, it) }
    }

    /** Plain text of a page, in reading order (for Read Out Loud and copying). */
    fun pageText(doc: PDFDocument, pageNumber: Int): String {
        val st = doc.loadPage(pageNumber - 1).toStructuredText()
        try {
            return st.asText()
        } finally {
            st.destroy()
        }
    }
}
