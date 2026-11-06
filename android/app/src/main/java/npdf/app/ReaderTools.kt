package npdf.app

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import npdf.app.Dialogs.selectedIndex
import npdf.engine.Annotations
import npdf.engine.Forms
import npdf.engine.ImageEdit
import npdf.engine.PageText
import npdf.engine.Pdf
import npdf.engine.Redaction
import npdf.engine.TextEdit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The touch tools of the Comment, Edit, Fill & Sign and Redact modes. */
class ReaderTools(private val a: ReaderActivity) {

    private val b get() = a.b
    private val s get() = a.session
    private val density = a.resources.displayMetrics.density
    private var selected: MaterialButton? = null
    private val author get() = a.prefs.author.ifBlank { null }

    // ------------------------------------------------------------- tool rows

    fun clear() {
        a.markSelected(selected, false)
        selected = null
        a.toolId = null
    }

    fun buildRow(mode: Int) {
        val row = b.toolRow
        row.removeAllViews()
        when (mode) {
            R.id.mode_comment -> {
                tool("Highlight", R.drawable.ic_highlight) { MarkupTool(PDFAnnotation.TYPE_HIGHLIGHT) }
                tool("Underline", R.drawable.ic_underline) { MarkupTool(PDFAnnotation.TYPE_UNDERLINE) }
                tool("Strike", R.drawable.ic_strike) { MarkupTool(PDFAnnotation.TYPE_STRIKE_OUT) }
                tool("Note", R.drawable.ic_note) { TapTool { p, x, y -> addNote(p, x, y) } }
                tool("Text box", R.drawable.ic_text) { RectTool(defaultW = 180f, defaultH = 40f) { p, r -> addTextBox(p, r) } }
                tool("Draw", R.drawable.ic_draw) { InkTool() }
                tool("Rectangle", R.drawable.ic_rect) { RectTool { p, r -> addShape(p, PDFAnnotation.TYPE_SQUARE, r) } }
                tool("Ellipse", R.drawable.ic_circle) { RectTool { p, r -> addShape(p, PDFAnnotation.TYPE_CIRCLE, r) } }
                tool("Arrow", R.drawable.ic_arrow) { LineTool() }
                action("Colour", R.drawable.ic_settings) { chooseColour() }
            }
            R.id.mode_edit -> {
                tool("Edit text", R.drawable.ic_edit_text) { TapTool { p, x, y -> editTextAt(p, x, y) } }
                tool("Add text", R.drawable.ic_text) { RectTool(defaultW = 220f, defaultH = 30f) { p, r -> addTextAt(p, r) } }
                action("Add image", R.drawable.ic_image) { pickImageThenPlace() }
                tool("Edit image", R.drawable.ic_image) { TapTool { p, x, y -> editImageAt(p, x, y) } }
                action("Replace", R.drawable.ic_search) { findReplace() }
                action("Pages", R.drawable.ic_pages) { a.startActivity(android.content.Intent(a, OrganizeActivity::class.java)) }
            }
            R.id.mode_sign -> {
                action("Signature", R.drawable.ic_sign) { signature() }
                tool("Add text", R.drawable.ic_text) { TapTool { p, x, y -> addFillText(p, x, y, null) } }
                tool("Date", R.drawable.ic_date) { TapTool { p, x, y -> addFillText(p, x, y, SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(Date())) } }
                action("Fields", R.drawable.ic_form) { listFields() }
            }
            R.id.mode_redact -> {
                tool("Mark area", R.drawable.ic_rect) { RectTool { p, r -> markArea(p, r) } }
                tool("Mark text", R.drawable.ic_redact) { MarkupTool(-1) }
                action("Find & mark", R.drawable.ic_search) { findAndMark() }
                action("Apply", R.drawable.ic_check) { applyRedactions() }
            }
            else -> { b.toolScroll.visibility = View.GONE; return }
        }
        b.toolScroll.visibility = View.VISIBLE
    }

    private fun tool(label: String, icon: Int, make: () -> PdfView.Tool) {
        val btn = a.toolButton(label, icon) { btn ->
            if (selected === btn) {
                clear(); b.pdf.tool = null
            } else {
                a.markSelected(selected, false)
                selected = btn
                a.markSelected(btn, true)
                a.toolId = label
                b.pdf.tool = make()
                hint(label)
            }
        }
        b.toolRow.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun action(label: String, icon: Int, run: suspend () -> Unit) {
        val btn = a.toolButton(label, icon) { a.lifecycleScope.launch { run() } }
        b.toolRow.addView(btn)
    }

    private fun hint(label: String) {
        val msg = when (label) {
            "Highlight", "Underline", "Strike", "Mark text" -> "Drag across the text."
            "Note" -> "Tap where the note goes."
            "Text box", "Add text", "Rectangle", "Ellipse", "Mark area" -> "Drag to draw the box, or tap."
            "Draw" -> "Draw with one finger; use two fingers to scroll or zoom."
            "Arrow" -> "Drag from the start to the tip."
            "Edit text" -> "Tap the text to change."
            "Edit image" -> "Tap a picture."
            "Date" -> "Tap where the date goes."
            else -> return
        }
        Snackbar.make(b.pdf, msg, Snackbar.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ edit helper

    /** Runs an edit on [pages] (0-based) with the busy indicator; shows the engine's warnings. */
    private suspend fun <T> edit(label: String, pages: Set<Int>?, block: (com.artifex.mupdf.fitz.PDFDocument) -> T): T? {
        val ses = s ?: return null
        val r = a.busy("$label…") { ses.edit(label, pages, block) }
        a.refreshUndo()
        return r
    }

    private val color get() = a.prefs.annotColor

    // ------------------------------------------------------------ comments

    fun addMarkup(page: Int, type: Int, quads: List<Quad>) {
        if (quads.isEmpty()) return
        val c = if (type == PDFAnnotation.TYPE_HIGHLIGHT) a.prefs.highlightColor else color
        a.lifecycleScope.launch { edit(label(type), setOf(page)) { Annotations.addMarkup(it, page + 1, type, quads, c, author) } }
    }

    private fun label(type: Int) = when (type) {
        PDFAnnotation.TYPE_HIGHLIGHT -> "Highlight"
        PDFAnnotation.TYPE_UNDERLINE -> "Underline"
        else -> "Strike-through"
    }

    private fun addNote(page: Int, x: Float, y: Float) = a.lifecycleScope.launch {
        val t = Dialogs.text(a, "Note", "Your comment", multiline = true) ?: return@launch
        if (t.isBlank()) return@launch
        edit("Note", setOf(page)) { Annotations.addNote(it, page + 1, Point(x, y), t, 0xFFFFD400.toInt(), author) }
    }

    private fun addTextBox(page: Int, r: Rect) = a.lifecycleScope.launch {
        val t = Dialogs.text(a, "Text box", "Text", multiline = true) ?: return@launch
        if (t.isBlank()) return@launch
        edit("Text box", setOf(page)) { Annotations.addTextBox(it, page + 1, r, t, 12f, color, author) }
    }

    private fun addShape(page: Int, type: Int, r: Rect) = a.lifecycleScope.launch {
        edit(if (type == PDFAnnotation.TYPE_SQUARE) "Rectangle" else "Ellipse", setOf(page)) { Annotations.addShape(it, page + 1, type, r, color, 2f, null, author) }
    }

    private suspend fun chooseColour() {
        val names = listOf("Red", "Blue", "Green", "Black", "Orange", "Purple", "Yellow (highlight)", "Green (highlight)", "Blue (highlight)", "Pink (highlight)")
        val values = listOf(0xFFE53935, 0xFF1E5AD8, 0xFF2E7D32, 0xFF000000, 0xFFF57C00, 0xFF7B1FA2, 0xFFFFEB3B, 0xFF9CFF7A, 0xFF8AD4FF, 0xFFFF9ECF).map { it.toInt() }
        val i = Dialogs.choose(a, "Colour", names) ?: return
        if (i >= 6) a.prefs.highlightColor = values[i] else a.prefs.annotColor = values[i]
        Ui.toast(a, "${names[i]} selected")
    }

    /** Tap on an existing comment. */
    fun annotationMenu(page: Int, info: Annotations.Info) = a.lifecycleScope.launch {
        val canText = info.type in setOf(PDFAnnotation.TYPE_TEXT, PDFAnnotation.TYPE_FREE_TEXT, PDFAnnotation.TYPE_HIGHLIGHT,
            PDFAnnotation.TYPE_UNDERLINE, PDFAnnotation.TYPE_STRIKE_OUT, PDFAnnotation.TYPE_SQUARE, PDFAnnotation.TYPE_CIRCLE)
        val resizable = info.type in setOf(PDFAnnotation.TYPE_STAMP, PDFAnnotation.TYPE_FREE_TEXT, PDFAnnotation.TYPE_SQUARE, PDFAnnotation.TYPE_CIRCLE)
        val items = mutableListOf<String>()
        val acts = mutableListOf<suspend () -> Unit>()
        val title = info.typeName + (if (info.author.isNotBlank()) " by ${info.author}" else "")
        if (canText) { items.add(if (info.contents.isBlank()) "Add comment text" else "Edit text"); acts.add {
            val t = Dialogs.text(a, info.typeName, "Text", info.contents, multiline = true)
            if (t != null) edit("Edit comment", setOf(page)) { Annotations.setContents(it, page + 1, info.id, t) }
        } }
        if (info.type != PDFAnnotation.TYPE_STAMP && info.type != PDFAnnotation.TYPE_REDACT) { items.add("Colour"); acts.add {
            val names = listOf("Red", "Blue", "Green", "Black", "Orange", "Yellow")
            val values = listOf(0xFFE53935, 0xFF1E5AD8, 0xFF2E7D32, 0xFF000000, 0xFFF57C00, 0xFFFFEB3B).map { it.toInt() }
            val i = Dialogs.choose(a, "Colour", names)
            if (i != null) edit("Colour", setOf(page)) { Annotations.setColor(it, page + 1, info.id, values[i]) }
        } }
        items.add(if (resizable) "Move or resize" else "Move"); acts.add { b.pdf.tool = AdjustTool(page, info.rect, resizable) { r ->
            if (resizable) edit("Move", setOf(page)) { Annotations.setRect(it, page + 1, info.id, r) }
            else edit("Move", setOf(page)) { Annotations.move(it, page + 1, info.id, r.x0 - info.rect.x0, r.y0 - info.rect.y0) }
        }; Snackbar.make(b.pdf, "Drag to move; drag the corner dot to resize.", Snackbar.LENGTH_SHORT).show() }
        items.add("Delete"); acts.add { edit("Delete comment", setOf(page)) { Annotations.delete(it, page + 1, info.id) } }
        if (info.contents.isNotBlank() && !canText) { items.add(0, "Show text"); acts.add(0) { Dialogs.message(a, info.typeName, info.contents) } }
        val i = Dialogs.choose(a, title + if (info.contents.isNotBlank()) ": " + info.contents.take(60) else "", items) ?: return@launch
        acts[i]()
    }

    // ------------------------------------------------------------- editing

    /** Tap on text: edit that line (or its whole paragraph) in place. */
    private fun editTextAt(page: Int, x: Float, y: Float) = a.lifecycleScope.launch {
        val ses = s ?: return@launch
        val found = ses.read { d ->
            val t = PageText.of(d.loadPage(page) as PDFPage)
            val block = t.blocks.firstOrNull { bl -> bl.lines.any { Pdf.contains(Pdf.grow(it.bbox, 2f), x, y) } }
            val line = block?.lines?.firstOrNull { Pdf.contains(Pdf.grow(it.bbox, 2f), x, y) }
            if (line == null) null else Triple(line, block, line.spans.first().size)
        }
        if (found == null) { Ui.toast(a, "No editable text here. Scanned pages need OCR first; use Add text for new text."); return@launch }
        val (line, block, size) = found
        val ctx = a
        val (til, et) = Dialogs.field(ctx, "Text", line.text.trimEnd(), InputType.TYPE_CLASS_TEXT, 1)
        val paragraph = Dialogs.check(ctx, "Edit the whole paragraph (${block.lines.size} lines)", false)
        paragraph.visibility = if (block.lines.size > 1) View.VISIBLE else View.GONE
        paragraph.setOnCheckedChangeListener { _, on ->
            et.setText(if (on) block.lines.joinToString(" ") { it.text.trim() } else line.text.trimEnd())
            if (on) { et.isSingleLine = false; et.maxLines = 8 } else et.isSingleLine = true
        }
        val (sizeTil, sizeEt) = Dialogs.field(ctx, "Font size", String.format(Locale.ROOT, "%.1f", size), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val fit = Dialogs.radios(ctx, listOf("Keep size", "Shrink to fit", "Condense to fit"), 0)
        val view = Dialogs.column(ctx, til, paragraph, sizeTil, Dialogs.label(ctx, "If the new text is longer:"), fit)
        if (!Dialogs.custom(ctx, "Edit text", view, "Apply")) return@launch
        val text = et.text?.toString() ?: ""
        val fs = sizeEt.text?.toString()?.toFloatOrNull()?.takeIf { abs(it - size) > 0.05f && it in 2f..200f }
        val fitMode = listOf("none", "shrink", "condense")[fit.selectedIndex().coerceAtLeast(0)]
        val isPara = paragraph.isChecked
        val res = edit("Edit text", setOf(page)) { d ->
            if (isPara) TextEdit.editText(d, page + 1, block.lines.map { it.bbox }.reduce(Pdf::union), text, mode = "block", fontSize = fs)
            else TextEdit.editText(d, page + 1, line.bbox, text, fontSize = fs, fit = fitMode)
        } ?: return@launch
        val msg = (listOf("Edited in ${res.font}" + if (res.fontKind == "original") " (the original font)." else ".") + res.warnings).joinToString("\n")
        if (res.warnings.isEmpty()) Snackbar.make(b.pdf, msg, Snackbar.LENGTH_SHORT).show() else Dialogs.message(a, "Text edited", msg)
    }

    private fun addTextAt(page: Int, r: Rect) = a.lifecycleScope.launch {
        val ctx = a
        val (til, et) = Dialogs.field(ctx, "Text", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 6)
        val (sizeTil, sizeEt) = Dialogs.field(ctx, "Font size", "11", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val font = Dialogs.radios(ctx, listOf("Sans (Helvetica)", "Serif (Times)", "Monospace (Courier)"), 0)
        if (!Dialogs.custom(ctx, "Add text", Dialogs.column(ctx, til, sizeTil, font), "Add")) return@launch
        val text = et.text?.toString() ?: ""
        if (text.isBlank()) return@launch
        val size = sizeEt.text?.toString()?.toFloatOrNull()?.coerceIn(4f, 144f) ?: 11f
        val family = listOf("helvetica", "times", "courier")[font.selectedIndex().coerceAtLeast(0)]
        val box = Rect(r.x0, r.y0, max(r.x1, r.x0 + 40f), r.y1)
        val label = edit("Add text", setOf(page)) { TextEdit.insertText(it, page + 1, box, text, size, 0xFF000000.toInt(), family) }
        if (label != null) Snackbar.make(b.pdf, "Text added ($label).", Snackbar.LENGTH_SHORT).show()
    }

    private fun pickImageThenPlace() {
        a.pendingImageAction = { bytes ->
            clear()
            b.pdf.tool = RectTool(defaultW = 200f, defaultH = 150f) { p, r ->
                a.lifecycleScope.launch {
                    edit("Add image", setOf(p)) { ImageEdit.insertImage(it, p + 1, r, bytes) }
                    b.pdf.tool = null
                }
            }
            Snackbar.make(b.pdf, "Drag where the picture goes, or tap.", Snackbar.LENGTH_LONG).show()
        }
        a.imageLauncher.launch("image/*")
    }

    private fun editImageAt(page: Int, x: Float, y: Float) = a.lifecycleScope.launch {
        val ses = s ?: return@launch
        val pl = ses.read { ImageEdit.list(it, page + 1) }.lastOrNull { Pdf.contains(it.rect, x, y) }
        if (pl == null) { Ui.toast(a, "No editable picture here."); return@launch }
        val i = Dialogs.choose(a, "Picture", listOf("Move or resize", "Replace", "Delete")) ?: return@launch
        when (i) {
            0 -> {
                b.pdf.tool = AdjustTool(page, pl.rect, true) { r -> edit("Move picture", setOf(page)) { ImageEdit.edit(it, page + 1, pl.rect, "transform", newRect = r) } }
                Snackbar.make(b.pdf, "Drag to move; drag the corner dot to resize.", Snackbar.LENGTH_SHORT).show()
            }
            1 -> {
                a.pendingImageAction = { bytes -> a.lifecycleScope.launch { edit("Replace picture", setOf(page)) { ImageEdit.edit(it, page + 1, pl.rect, "replace", replacement = bytes) } } }
                a.imageLauncher.launch("image/*")
            }
            2 -> {
                val r = edit("Delete picture", setOf(page)) { ImageEdit.edit(it, page + 1, pl.rect, "delete") }
                if (r?.imageDataRemoved == false) Dialogs.message(a, "Picture deleted", "The picture is no longer shown, but its data is still in the file because another page uses it. Use Redact if it must be removed completely.")
            }
        }
    }

    private suspend fun findReplace() {
        val ctx = a
        val (f1, find) = Dialogs.field(ctx, "Find")
        val (f2, repl) = Dialogs.field(ctx, "Replace with")
        val case = Dialogs.check(ctx, "Match case", true)
        val word = Dialogs.check(ctx, "Whole words only", false)
        if (!Dialogs.custom(ctx, "Find and replace", Dialogs.column(ctx, f1, f2, case, word), "Replace all")) return
        val q = find.text?.toString() ?: ""
        if (q.isEmpty()) return
        val r = edit("Replace", null) { TextEdit.replaceAll(it, q, repl.text?.toString() ?: "", case.isChecked, word.isChecked) } ?: return
        val extra = (r.skipped + r.warnings).take(6)
        Dialogs.message(a, "Replaced ${r.replaced} of ${r.found}", if (extra.isEmpty()) "Done." else extra.joinToString("\n"))
    }

    // --------------------------------------------------------- fill & sign

    fun fillField(f: Forms.Field) = a.lifecycleScope.launch {
        if (f.readOnly) { Ui.toast(a, "This field is read-only."); return@launch }
        val p = f.page
        when (f.kind) {
            Forms.Kind.TEXT -> {
                val v = Dialogs.text(a, f.label.ifBlank { "Field" }, f.label.ifBlank { "Value" }, f.value, multiline = f.multiline) ?: return@launch
                edit("Fill field", setOf(p - 1)) { Forms.setText(it, p, f.id, v) }
            }
            Forms.Kind.CHECKBOX -> edit("Tick box", setOf(p - 1)) { Forms.setChecked(it, p, f.id, f.value.isEmpty() || f.value == "Off") }
            Forms.Kind.RADIO -> edit("Choose option", setOf(p - 1)) { Forms.setChecked(it, p, f.id, true) }
            Forms.Kind.COMBO, Forms.Kind.LIST -> {
                val i = Dialogs.choose(a, f.label.ifBlank { "Choose" }, f.options, f.options.indexOf(f.value)) ?: return@launch
                edit("Choose option", setOf(p - 1)) { Forms.setChoice(it, p, f.id, f.options[i]) }
            }
            Forms.Kind.SIGNATURE -> Dialogs.message(a, "Digital signature field", "This field is for a certificate-based digital signature, which N PDF on Android does not create. Use Signature to place a picture of your signature.")
            else -> Ui.toast(a, "This field cannot be filled here.")
        }
    }

    private suspend fun listFields() {
        val ses = s ?: return
        val fields = ses.read { Forms.list(it) }
        if (fields.isEmpty()) { Dialogs.message(a, "No form fields", "This PDF has no fillable fields. Use Add text to type on it."); return }
        val i = Dialogs.choose(a, "Form fields", fields.map { "Page ${it.page}: ${it.label.ifBlank { it.name }}" + if (it.value.isNotBlank() && it.value != "Off") " = ${it.value}" else "" }) ?: return
        val f = fields[i]
        b.pdf.reveal(f.page - 1, f.rect.x0, f.rect.y0)
        fillField(f)
    }

    private fun addFillText(page: Int, x: Float, y: Float, preset: String?) = a.lifecycleScope.launch {
        val t = preset ?: Dialogs.text(a, "Add text", "Text") ?: return@launch
        if (t.isBlank()) return@launch
        val size = 11f
        val w = max(30f, t.length * size * 0.55f + 6f)
        val r = Rect(x, y - size, min(x + w, (s?.sizes?.get(page)?.w ?: (x + w))), y + size * 0.6f)
        edit("Add text", setOf(page)) { Annotations.addTextBox(it, page + 1, r, t, size, 0xFF000000.toInt(), author, borderWidth = 0f) }
    }

    private suspend fun signature() {
        val png = SignatureDialog.show(a) ?: return
        val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size) ?: return
        val aspect = bmp.width.toFloat() / max(1, bmp.height)
        clear()
        b.pdf.tool = RectTool(defaultW = 150f, defaultH = 150f / aspect, keepAspect = aspect) { p, r ->
            a.lifecycleScope.launch {
                edit("Signature", setOf(p)) { Annotations.addSignatureImage(it, p + 1, r, png, author) }
                b.pdf.tool = null
                Snackbar.make(b.pdf, "Signature placed. Tap it to move or resize.", Snackbar.LENGTH_SHORT).show()
            }
        }
        Snackbar.make(b.pdf, "Tap where the signature goes, or drag its box.", Snackbar.LENGTH_LONG).show()
    }

    // --------------------------------------------------------------- redact

    private fun markArea(page: Int, r: Rect) = a.lifecycleScope.launch {
        edit("Mark for redaction", setOf(page)) { Redaction.mark(it, page + 1, rect = r) }
    }

    fun markRedactionText(page: Int, quads: List<Quad>, text: String) = a.lifecycleScope.launch {
        if (quads.isEmpty()) return@launch
        val ok = edit("Mark for redaction", setOf(page)) { Redaction.mark(it, page + 1, quads = quads) }
        if (ok != null && text.isNotBlank()) a.redactionSecrets.add(text.trim())
    }

    private suspend fun findAndMark() {
        val ses = s ?: return
        val ctx = a
        val (til, et) = Dialogs.field(ctx, "Words or phrases (one per line)", "", InputType.TYPE_CLASS_TEXT, 5)
        val checks = Redaction.PATTERNS.map { (k, v) -> k to Dialogs.check(ctx, v.second, false) }
        val view = Dialogs.column(ctx, til, Dialogs.label(ctx, "Patterns"), *checks.map { it.second }.toTypedArray())
        if (!Dialogs.custom(ctx, "Find and mark", android.widget.ScrollView(ctx).apply { addView(view) }, "Find")) return
        val terms = (et.text?.toString() ?: "").lines().filter { it.isNotBlank() }
        val pats = checks.filter { it.second.isChecked }.map { it.first }
        if (terms.isEmpty() && pats.isEmpty()) return
        val matches = a.busy("Searching…") { ses.read { Redaction.find(it, terms, pats) } } ?: return
        if (matches.isEmpty()) { Dialogs.message(a, "Nothing found", "No matching text was found."); return }
        val pages = matches.map { it.page }.distinct()
        if (!Dialogs.confirm(a, "Mark ${matches.size} matches?", "On ${pages.size} page(s). Review them on the page before applying.", "Mark")) return
        edit("Mark for redaction", pages.map { it - 1 }.toSet()) { d -> matches.forEach { m -> Redaction.mark(d, m.page, quads = m.quads) } }
        a.redactionSecrets.addAll(matches.map { it.text })
        b.pdf.goToPage(pages.first() - 1)
    }

    private suspend fun applyRedactions() {
        val ses = s ?: return
        val marks = ses.read { Redaction.marks(it) }
        if (marks.isEmpty()) { Dialogs.message(a, "No marks", "Mark areas or text first."); return }
        val n = marks.values.sumOf { it.size }
        if (!Dialogs.confirm(a, "Apply $n redaction(s)?", "The marked text, pictures and drawings are removed from the file permanently and replaced by black boxes. You can undo until you close the document; after saving it cannot be recovered.", "Apply")) return
        val report = edit("Apply redactions", null) { Redaction.apply(it, a.redactionSecrets.toList()) } ?: return
        a.redactionSecrets.clear()
        Dialogs.message(a, "Redaction applied", "${report.areas} area(s) on ${report.pages} page(s) removed and checked: the marked text is gone and every area is covered.\n\nSave as a new file to keep the original.")
    }

    // --------------------------------------------------------------- tools

    private fun dp(v: Float) = v * density
    private fun toPage(v: Float) = v / b.pdf.scale

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = 0xFFC8102E.toInt()
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3F51D9.toInt() }

    /** Selection handles after a long press: drag either end to extend the selection. */
    val selectionHandles = object : PdfView.Tool {
        private var moving: Point? = null
        private var fixed: Point? = null
        private fun ends(): Pair<Point, Point>? {
            val q = b.pdf.selection?.second ?: return null
            if (q.isEmpty()) return null
            return Point(q.first().ll_x, q.first().ll_y) to Point(q.last().lr_x, q.last().lr_y)
        }
        override fun onDown(page: Int, x: Float, y: Float): Boolean {
            if (page != a.selPage) return false
            val (s0, s1) = ends() ?: return false
            val tol = toPage(dp(28f))
            return when {
                hypot(x - s1.x, y - s1.y) < tol -> { fixed = a.selA; moving = Point(x, y); true }
                hypot(x - s0.x, y - s0.y) < tol -> { fixed = a.selB; moving = Point(x, y); true }
                else -> false
            }
        }
        override fun onMove(page: Int, x: Float, y: Float) {
            val f = fixed ?: return
            a.updateSelection(page, f, Point(x, y))
        }
        override fun onUp(page: Int, x: Float, y: Float) {
            val f = fixed ?: return
            a.updateSelection(page, f, Point(x, y)) {}
            fixed = null; moving = null
        }
        override fun draw(canvas: Canvas, view: PdfView) {
            val (s0, s1) = ends() ?: return
            val p = a.selPage
            for (pt in listOf(s0, s1)) canvas.drawCircle(view.pageToViewX(p, pt.x), view.pageToViewY(p, pt.y) + dp(8f), dp(8f), handlePaint)
        }
    }

    /** Drag across text to highlight, underline, strike it out ([type]) or mark it for redaction (type -1). */
    inner class MarkupTool(private val type: Int) : PdfView.Tool {
        private var start: Point? = null
        override fun onDown(page: Int, x: Float, y: Float): Boolean { start = Point(x, y); a.selPage = page; return true }
        override fun onMove(page: Int, x: Float, y: Float) { start?.let { a.updateSelection(page, it, Point(x, y)) } }
        override fun onUp(page: Int, x: Float, y: Float) {
            val st = start ?: return
            start = null
            a.updateSelection(page, st, Point(x, y)) { quads ->
                if (quads.isEmpty()) { b.pdf.selection = null; return@updateSelection }
                if (type == -1) {
                    a.lifecycleScope.launch {
                        val text = a.selectedText()
                        markRedactionText(page, quads.toList(), text)
                        b.pdf.selection = null
                    }
                } else {
                    addMarkup(page, type, quads.toList())
                    b.pdf.selection = null
                }
            }
        }
        override fun onCancel() { start = null; b.pdf.selection = null }
    }

    /** A tap on a page. */
    inner class TapTool(private val onTap: (Int, Float, Float) -> Unit) : PdfView.Tool {
        private var down: Point? = null
        override fun onDown(page: Int, x: Float, y: Float): Boolean { down = Point(x, y); return true }
        override fun onMove(page: Int, x: Float, y: Float) {}
        override fun onUp(page: Int, x: Float, y: Float) {
            val d = down ?: return
            down = null
            if (hypot(x - d.x, y - d.y) < toPage(dp(12f))) onTap(page, d.x, d.y)
        }
    }

    /** Drag out a rectangle; a tap gives a box of the default size at that point. */
    inner class RectTool(
        private val defaultW: Float = 120f, private val defaultH: Float = 80f, private val keepAspect: Float? = null,
        private val onRect: (Int, Rect) -> Unit,
    ) : PdfView.Tool {
        private var page = -1
        private var p0: Point? = null
        private var p1: Point? = null
        override fun onDown(page: Int, x: Float, y: Float): Boolean { this.page = page; p0 = Point(x, y); p1 = Point(x, y); return true }
        override fun onMove(page: Int, x: Float, y: Float) { p1 = Point(x, y) }
        override fun onUp(page: Int, x: Float, y: Float) {
            val s0 = p0 ?: return
            p0 = null; p1 = null
            val size = s?.sizes?.getOrNull(page) ?: return
            var r = Rect(min(s0.x, x), min(s0.y, y), max(s0.x, x), max(s0.y, y))
            if (r.x1 - r.x0 < 8f && r.y1 - r.y0 < 8f) {
                r = Rect(s0.x, s0.y, s0.x + defaultW, s0.y + defaultH)
            } else if (keepAspect != null) {
                r = Rect(r.x0, r.y0, r.x1, r.y0 + (r.x1 - r.x0) / keepAspect)
            }
            // Keep the box on the page.
            val dx = min(0f, size.w - r.x1); val dy = min(0f, size.h - r.y1)
            r = Rect(max(0f, r.x0 + dx), max(0f, r.y0 + dy), min(size.w, r.x1 + dx), min(size.h, r.y1 + dy))
            onRect(page, r)
        }
        override fun onCancel() { p0 = null; p1 = null }
        override fun draw(canvas: Canvas, view: PdfView) {
            val s0 = p0 ?: return; val s1 = p1 ?: return
            canvas.drawRect(view.pageRectToView(page, min(s0.x, s1.x), min(s0.y, s1.y), max(s0.x, s1.x), max(s0.y, s1.y)), previewPaint)
        }
    }

    /** Freehand drawing: each stroke becomes one drawing comment. */
    inner class InkTool : PdfView.Tool {
        private var page = -1
        private val pts = mutableListOf<Point>()
        override fun onDown(page: Int, x: Float, y: Float): Boolean { this.page = page; pts.clear(); pts.add(Point(x, y)); return true }
        override fun onMove(page: Int, x: Float, y: Float) { pts.add(Point(x, y)) }
        override fun onUp(page: Int, x: Float, y: Float) {
            pts.add(Point(x, y))
            val stroke = pts.toList()
            pts.clear()
            if (stroke.size < 2) return
            val p = page
            val c = color
            a.lifecycleScope.launch { edit("Drawing", setOf(p)) { Annotations.addInk(it, p + 1, listOf(stroke), c, 2f, author) } }
        }
        override fun onCancel() { pts.clear() }
        override fun draw(canvas: Canvas, view: PdfView) {
            if (pts.size < 2) return
            inkPaint.color = color
            inkPaint.strokeWidth = 2f * view.scale
            val path = Path()
            path.moveTo(view.pageToViewX(page, pts[0].x), view.pageToViewY(page, pts[0].y))
            for (i in 1 until pts.size) path.lineTo(view.pageToViewX(page, pts[i].x), view.pageToViewY(page, pts[i].y))
            canvas.drawPath(path, inkPaint)
        }
    }

    /** A straight arrow from where the finger goes down to where it lifts. */
    inner class LineTool : PdfView.Tool {
        private var page = -1
        private var p0: Point? = null
        private var p1: Point? = null
        override fun onDown(page: Int, x: Float, y: Float): Boolean { this.page = page; p0 = Point(x, y); p1 = Point(x, y); return true }
        override fun onMove(page: Int, x: Float, y: Float) { p1 = Point(x, y) }
        override fun onUp(page: Int, x: Float, y: Float) {
            val s0 = p0 ?: return
            p0 = null; p1 = null
            if (hypot(x - s0.x, y - s0.y) < 4f) return
            val p = page; val c = color
            a.lifecycleScope.launch { edit("Arrow", setOf(p)) { Annotations.addLine(it, p + 1, s0, Point(x, y), c, 2f, true, author) } }
        }
        override fun onCancel() { p0 = null; p1 = null }
        override fun draw(canvas: Canvas, view: PdfView) {
            val s0 = p0 ?: return; val s1 = p1 ?: return
            canvas.drawLine(view.pageToViewX(page, s0.x), view.pageToViewY(page, s0.y), view.pageToViewX(page, s1.x), view.pageToViewY(page, s1.y), previewPaint)
        }
    }

    /** Move a box by dragging inside it, or resize it from the bottom-right handle; lifting applies. */
    inner class AdjustTool(private val page: Int, private var rect: Rect, private val resizable: Boolean, private val apply: suspend (Rect) -> Unit) : PdfView.Tool {
        private var start: Point? = null
        private var orig = Rect(rect)
        private var resizing = false
        override fun onDown(page: Int, x: Float, y: Float): Boolean {
            if (page != this.page) { b.pdf.tool = null; return false }
            val tol = toPage(dp(24f))
            resizing = resizable && hypot(x - rect.x1, y - rect.y1) < tol
            if (!resizing && !Pdf.contains(Pdf.grow(rect, tol / 2), x, y)) { b.pdf.tool = null; return false }
            start = Point(x, y); orig = Rect(rect)
            return true
        }
        override fun onMove(page: Int, x: Float, y: Float) {
            val s0 = start ?: return
            val dx = x - s0.x; val dy = y - s0.y
            rect = if (resizing) {
                val w = max(10f, orig.x1 - orig.x0 + dx)
                val h = max(10f, (orig.y1 - orig.y0) * w / max(1f, orig.x1 - orig.x0))
                Rect(orig.x0, orig.y0, orig.x0 + w, orig.y0 + h)
            } else Rect(orig.x0 + dx, orig.y0 + dy, orig.x1 + dx, orig.y1 + dy)
        }
        override fun onUp(page: Int, x: Float, y: Float) {
            if (start == null) return
            start = null
            val r = Rect(rect)
            b.pdf.tool = null
            a.lifecycleScope.launch { apply(r) }
        }
        override fun onCancel() { start = null; rect = Rect(orig) }
        override fun draw(canvas: Canvas, view: PdfView) {
            canvas.drawRect(view.pageRectToView(page, rect.x0, rect.y0, rect.x1, rect.y1), previewPaint)
            if (resizable) canvas.drawCircle(view.pageToViewX(page, rect.x1), view.pageToViewY(page, rect.y1), dp(9f), handlePaint)
        }
    }
}
