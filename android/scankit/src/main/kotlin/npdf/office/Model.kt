package npdf.office

/**
 * A document as Office export needs it: pages of paragraphs, tables and
 * pictures in reading order. Built from a PDF's text (its own or recognised by
 * OCR) and images, then written as Word, Excel, PowerPoint or RTF.
 */
data class Run(
    val text: String,
    val size: Float = 11f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val rgb: Int = 0x000000,
)

enum class Align { LEFT, CENTER, RIGHT }

sealed class Block

data class Para(val runs: List<Run>, val align: Align = Align.LEFT, val heading: Int = 0, val indentPt: Float = 0f) : Block() {
    val text: String get() = runs.joinToString("") { it.text }
}

/** A table; each cell is plain text. Numbers stay text here and are typed by the Excel writer. */
data class Table(val rows: List<List<String>>) : Block() {
    val columns: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

/** A picture (JPEG or PNG bytes) shown [widthPt] x [heightPt] points. */
class Picture(val bytes: ByteArray, val png: Boolean, val widthPt: Float, val heightPt: Float) : Block()

class Page(val widthPt: Float, val heightPt: Float, val blocks: List<Block>, val render: Picture? = null)

class Doc(val title: String, val pages: List<Page>)

/** Text positioned on a page, as extracted from a PDF (points, top-left origin). */
data class TSpan(val text: String, val x0: Float, val x1: Float, val size: Float, val bold: Boolean = false, val italic: Boolean = false, val rgb: Int = 0)

data class TLine(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val spans: List<TSpan>) {
    val text: String get() = spans.joinToString("") { it.text }
    val size: Float get() = spans.maxOfOrNull { it.size } ?: 11f
}

/** A picture placed on a page (points, top-left origin). */
class TImage(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val bytes: ByteArray, val png: Boolean)
