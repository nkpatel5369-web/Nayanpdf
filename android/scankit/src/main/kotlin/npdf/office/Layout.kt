package npdf.office

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns positioned text into paragraphs and tables. Lines on the same visual
 * row are grouped; runs of rows that share columns of white space become
 * tables; the rest become paragraphs, joined while the text keeps flowing.
 */
object Layout {

    /** Lines whose vertical centres are close enough to sit on one row, left to right. */
    fun rows(lines: List<TLine>): List<List<TLine>> {
        val sorted = lines.filter { it.text.isNotBlank() }.sortedWith(compareBy({ (it.y0 + it.y1) / 2 }, { it.x0 }))
        val rows = ArrayList<MutableList<TLine>>()
        for (l in sorted) {
            val cy = (l.y0 + l.y1) / 2
            val row = rows.lastOrNull()
            if (row != null) {
                val rcy = row.map { (it.y0 + it.y1) / 2 }.average().toFloat()
                val tol = 0.45f * min(l.size, row.minOf { it.size })
                if (abs(cy - rcy) <= tol) { row.add(l); continue }
            }
            rows.add(mutableListOf(l))
        }
        return rows.map { r -> r.sortedBy { it.x0 } }
    }

    /** Column separators (x positions) from the white space shared by the rows. */
    fun columnEdges(rows: List<List<TLine>>): List<Float> {
        val intervals = rows.filter { it.size >= 2 }.flatMap { r -> r.map { it.x0 to it.x1 } }.sortedBy { it.first }
        if (intervals.isEmpty()) return emptyList()
        val merged = ArrayList<Pair<Float, Float>>()
        for ((a, b) in intervals) {
            val last = merged.lastOrNull()
            if (last != null && a <= last.second + 2f) merged[merged.size - 1] = last.first to max(last.second, b) else merged.add(a to b)
        }
        return (0 until merged.size - 1).map { (merged[it].second + merged[it + 1].first) / 2 }
    }

    private fun cells(row: List<TLine>, edges: List<Float>): List<String> {
        val out = MutableList(edges.size + 1) { "" }
        for (l in row) {
            val c = (l.x0 + l.x1) / 2
            var k = edges.indexOfFirst { c < it }
            if (k < 0) k = edges.size
            out[k] = if (out[k].isEmpty()) l.text.trim() else out[k] + " " + l.text.trim()
        }
        return out
    }

    /** Every row of the page as cells, for spreadsheets. */
    fun sheet(lines: List<TLine>): List<List<String>> {
        val rs = rows(lines)
        val edges = columnEdges(rs)
        return rs.map { cells(it, edges) }
    }

    /** Reading-order blocks for word processing and RTF. */
    fun blocks(lines: List<TLine>, images: List<TImage>, pageW: Float, pageH: Float): List<Block> {
        val rs = rows(lines)
        val body = bodySize(lines)
        val out = ArrayList<Pair<Float, Block>>() // y, block
        var i = 0
        while (i < rs.size) {
            // A table: three or more consecutive multi-part rows sharing at least two columns.
            if (rs[i].size >= 2) {
                var j = i
                while (j < rs.size && (rs[j].size >= 2 || (j + 1 < rs.size && rs[j + 1].size >= 2 && j > i))) j++
                val group = rs.subList(i, j)
                val edges = columnEdges(group)
                if (group.count { it.size >= 2 } >= 3 && edges.isNotEmpty()) {
                    out.add(group.first().first().y0 to Table(group.map { cells(it, edges) }))
                    i = j; continue
                }
            }
            // A paragraph: rows that continue one another.
            val first = rs[i]
            val para = ArrayList<List<TLine>>(); para.add(first)
            var k = i + 1
            while (k < rs.size) {
                val prev = para.last(); val next = rs[k]
                if (next.size >= 2 || prev.size >= 2) break
                val p = prev.first(); val n = next.first()
                val sameSize = abs(p.size - n.size) <= max(0.6f, 0.08f * p.size)
                val gap = n.y0 - p.y1
                val closeBelow = gap < 0.9f * p.size && gap > -0.5f * p.size
                val sameLeft = abs(n.x0 - firstLeft(para)) <= 1.5f * p.size
                val prevFull = p.x1 >= rightEdge(lines) - 4f * p.size
                if (sameSize && closeBelow && sameLeft && prevFull) { para.add(next); k++ } else break
            }
            out.add(first.first().y0 to paragraph(para, body, pageW))
            i = k
        }
        val textArea = lines.isNotEmpty()
        for (im in images) {
            val area = (im.x1 - im.x0) * (im.y1 - im.y0)
            // A picture covering most of a page that has text is the scan behind recognised text: leave it out.
            if (textArea && area > 0.7f * pageW * pageH) continue
            out.add(im.y0 to Picture(im.bytes, im.png, im.x1 - im.x0, im.y1 - im.y0))
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    private fun firstLeft(para: List<List<TLine>>) = para.first().first().x0

    private fun rightEdge(lines: List<TLine>) = lines.maxOfOrNull { it.x1 } ?: 0f

    /** The most common text size, weighted by the number of characters. */
    fun bodySize(lines: List<TLine>): Float {
        val count = HashMap<Int, Int>()
        for (l in lines) for (s in l.spans) count.merge(Math.round(s.size * 2), s.text.length, Int::plus)
        return (count.maxByOrNull { it.value }?.key ?: 22) / 2f
    }

    private fun paragraph(rows: List<List<TLine>>, body: Float, pageW: Float): Para {
        val runs = ArrayList<Run>()
        for ((idx, row) in rows.withIndex()) {
            val line = row.first()
            for ((si, s) in line.spans.withIndex()) {
                var t = s.text
                if (si == line.spans.size - 1 && idx < rows.size - 1) {
                    // Join wrapped lines: drop a hyphen that splits a word, otherwise add a space.
                    val nextFirst = rows[idx + 1].first().text.trimStart().firstOrNull()
                    t = if (t.endsWith("-") && nextFirst != null && nextFirst.isLowerCase()) t.dropLast(1) else t.trimEnd() + " "
                }
                val last = runs.lastOrNull()
                if (last != null && last.size == s.size && last.bold == s.bold && last.italic == s.italic && last.rgb == s.rgb) {
                    runs[runs.size - 1] = last.copy(text = last.text + t)
                } else runs.add(Run(t, s.size, s.bold, s.italic, s.rgb))
            }
        }
        val size = rows.first().first().size
        val heading = when {
            size >= body * 1.6f -> 1
            size >= body * 1.25f -> 2
            else -> 0
        }
        val l = rows.first().first()
        val centre = (l.x0 + l.x1) / 2
        val align = when {
            rows.size == 1 && abs(centre - pageW / 2) < pageW * 0.04f && (l.x1 - l.x0) < pageW * 0.7f -> Align.CENTER
            rows.size == 1 && l.x0 > pageW * 0.5f -> Align.RIGHT
            else -> Align.LEFT
        }
        return Para(runs, align, heading)
    }
}
