package npdf.engine

import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Rect

/**
 * A minimal PDF content-stream reader: enough to find where images are drawn,
 * with their transformation, and to splice the stream around those places.
 */
object ContentStream {

    /** One operator with its operands; [start] and [end] are byte offsets of the whole instruction. */
    class Op(val name: String, val operands: List<Any>, val start: Int, val end: Int)

    class Name(val value: String) { override fun toString() = "/$value" }

    private fun isWhite(b: Int) = b == 0 || b == 9 || b == 10 || b == 12 || b == 13 || b == 32
    private fun isDelim(b: Int) = b == '('.code || b == ')'.code || b == '<'.code || b == '>'.code ||
        b == '['.code || b == ']'.code || b == '{'.code || b == '}'.code || b == '/'.code || b == '%'.code

    /** Parses [data] into instructions. Arrays and dictionaries are kept only as placeholders. */
    fun parse(data: ByteArray): List<Op> {
        val ops = mutableListOf<Op>()
        val operands = mutableListOf<Any>()
        var opStart = -1
        var i = 0
        val n = data.size
        fun at(k: Int) = if (k < n) data[k].toInt() and 0xFF else -1
        var depth = 0
        while (i < n) {
            val c = at(i)
            if (isWhite(c)) { i++; continue }
            if (c == '%'.code) { while (i < n && at(i) != 10 && at(i) != 13) i++; continue }
            if (opStart < 0) opStart = i
            when {
                c == '('.code -> {
                    var level = 0
                    val sb = java.io.ByteArrayOutputStream()
                    i++
                    while (i < n) {
                        val d = at(i)
                        if (d == '\\'.code) { if (i + 1 < n) sb.write(at(i + 1)); i += 2; continue }
                        if (d == '('.code) level++
                        if (d == ')'.code) { if (level == 0) break; level-- }
                        sb.write(d); i++
                    }
                    i++
                    if (depth == 0) operands.add(sb.toByteArray())
                }
                c == '<'.code && at(i + 1) == '<'.code -> { depth++; i += 2 }
                c == '>'.code && at(i + 1) == '>'.code -> { depth--; i += 2; if (depth == 0) operands.add("<<dict>>") }
                c == '<'.code -> {
                    val s = i
                    while (i < n && at(i) != '>'.code) i++
                    i++
                    if (depth == 0) operands.add(String(data, s, i - s, Charsets.ISO_8859_1))
                }
                c == '['.code -> { depth++; i++ }
                c == ']'.code -> { depth--; i++; if (depth == 0) operands.add("[array]") }
                c == '/'.code -> {
                    val s = ++i
                    while (i < n && !isWhite(at(i)) && !isDelim(at(i))) i++
                    if (depth == 0) operands.add(Name(String(data, s, i - s, Charsets.ISO_8859_1)))
                }
                else -> {
                    val s = i
                    while (i < n && !isWhite(at(i)) && !isDelim(at(i))) i++
                    if (i == s) { i++; continue }
                    val tok = String(data, s, i - s, Charsets.ISO_8859_1)
                    val num = tok.toFloatOrNull()
                    if (num != null) {
                        if (depth == 0) operands.add(num)
                    } else if (depth > 0) {
                        // true/false/null inside arrays and dictionaries
                    } else if (tok == "true" || tok == "false" || tok == "null") {
                        operands.add(tok)
                    } else if (tok == "BI") {
                        // Inline image: BI <dict> ID <binary> EI
                        val idPos = find(data, i, "ID")
                        var j = if (idPos < 0) n else idPos + 2
                        if (j < n && isWhite(at(j))) j++
                        var end = n
                        var k = j
                        while (k + 1 < n) {
                            if (at(k) == 'E'.code && at(k + 1) == 'I'.code && (k == 0 || isWhite(at(k - 1))) &&
                                (k + 2 >= n || isWhite(at(k + 2)) || isDelim(at(k + 2)))) { end = k + 2; break }
                            k++
                        }
                        ops.add(Op("INLINE_IMAGE", emptyList(), opStart, end))
                        operands.clear(); opStart = -1
                        i = end
                    } else {
                        ops.add(Op(tok, operands.toList(), opStart, i))
                        operands.clear(); opStart = -1
                    }
                }
            }
        }
        return ops
    }

    private fun find(data: ByteArray, from: Int, s: String): Int {
        val b = s.toByteArray(Charsets.ISO_8859_1)
        var i = from
        while (i <= data.size - b.size) {
            var ok = true
            for (k in b.indices) if (data[i + k] != b[k]) { ok = false; break }
            if (ok && (i == 0 || isWhite(data[i - 1].toInt() and 0xFF)) &&
                (i + b.size >= data.size || isWhite(data[i + b.size].toInt() and 0xFF))) return i
            i++
        }
        return -1
    }

    /** A place where an image is drawn: [ctm] maps the unit square to PDF user space. */
    class ImagePlacement(
        val opIndex: Int, val name: String, val ctm: Matrix, val obj: PDFObject?, val topLevel: Boolean,
        val start: Int, val end: Int,
    ) {
        /** Bounding box in PDF user space. */
        val bbox: Rect
            get() {
                val xs = floatArrayOf(ctm.e, ctm.a + ctm.e, ctm.c + ctm.e, ctm.a + ctm.c + ctm.e)
                val ys = floatArrayOf(ctm.f, ctm.b + ctm.f, ctm.d + ctm.f, ctm.b + ctm.d + ctm.f)
                return Rect(xs.min(), ys.min(), xs.max(), ys.max())
            }
    }

    private fun mat(o: List<Any>): Matrix? {
        if (o.size != 6 || o.any { it !is Float }) return null
        val v = o.map { it as Float }
        return Matrix(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    /**
     * Image placements in [data] drawn with [resources]. Form XObjects are
     * followed (their images are reported with topLevel = false) up to a
     * small depth.
     */
    fun images(data: ByteArray, resources: PDFObject, base: Matrix = Matrix.Identity(), depth: Int = 0): Pair<List<Op>, List<ImagePlacement>> {
        val ops = parse(data)
        val found = mutableListOf<ImagePlacement>()
        val stack = ArrayDeque<Matrix>()
        var ctm = Matrix(base)
        val xobjects = if (resources.isDictionary) resources.get("XObject") else PDFObject.Null
        ops.forEachIndexed { idx, op ->
            when (op.name) {
                "q" -> stack.addLast(Matrix(ctm))
                "Q" -> if (stack.isNotEmpty()) ctm = stack.removeLast()
                "cm" -> mat(op.operands)?.let { ctm = Matrix(it, ctm) }
                "Do" -> {
                    val nm = (op.operands.firstOrNull() as? Name)?.value ?: return@forEachIndexed
                    val xo = if (xobjects.isDictionary) xobjects.get(nm) else PDFObject.Null
                    if (!xo.isStream) return@forEachIndexed
                    when (xo.get("Subtype").takeIf { it.isName }?.asName()) {
                        "Image" -> found.add(ImagePlacement(idx, nm, Matrix(ctm), xo, depth == 0, op.start, op.end))
                        "Form" -> if (depth < 6) {
                            val fm = xo.get("Matrix")
                            val m = if (fm.isArray && fm.size() == 6) Matrix(
                                fm.get(0).asFloat(), fm.get(1).asFloat(), fm.get(2).asFloat(),
                                fm.get(3).asFloat(), fm.get(4).asFloat(), fm.get(5).asFloat(),
                            ) else Matrix.Identity()
                            val res = xo.get("Resources").takeIf { it.isDictionary } ?: resources
                            val inner = images(xo.readStream(), res, Matrix(m, ctm), depth + 1).second
                            inner.forEach { found.add(ImagePlacement(idx, it.name, it.ctm, it.obj, false, op.start, op.end)) }
                        }
                    }
                }
                "INLINE_IMAGE" -> found.add(ImagePlacement(idx, "inline", Matrix(ctm), null, depth == 0, op.start, op.end))
            }
        }
        return ops to found
    }
}
