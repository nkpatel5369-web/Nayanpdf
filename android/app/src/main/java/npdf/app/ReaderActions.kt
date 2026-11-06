package npdf.app

import android.content.Intent
import android.net.Uri
import android.print.PrintManager
import android.text.InputType
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import npdf.app.Dialogs.selectedIndex
import npdf.engine.Annotations
import npdf.engine.Compress
import npdf.engine.DocInfo
import npdf.engine.Pages
import npdf.engine.Pdf
import npdf.engine.Security
import npdf.engine.TextLayer
import java.io.File

/** The reader's menu actions. Exports always write a new file; the open document is not changed by them. */
object ReaderActions {

    private suspend fun snapshot(a: ReaderActivity): File? {
        val s = a.session ?: return null
        return a.busy("Preparing…") { s.snapshot() }
    }

    suspend fun share(a: ReaderActivity) {
        val s = a.session ?: return
        val f = snapshot(a) ?: return
        val shared = Files.shareCopy(a, f, s.name)
        val uri = Files.shareUri(a, shared)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(send, "Share ${s.name}"))
    }

    suspend fun print(a: ReaderActivity) {
        val s = a.session ?: return
        val f = snapshot(a) ?: return
        val pm = a.getSystemService(android.content.Context.PRINT_SERVICE) as PrintManager
        pm.print(s.name, PrintAdapter(f, s.name, s.sizes.size), null)
    }

    suspend fun bookmarks(a: ReaderActivity) {
        val s = a.session ?: return
        val list = s.read { DocInfo.bookmarks(it) }.filter { it.page > 0 }
        if (list.isEmpty()) { Dialogs.message(a, "Bookmarks", "This PDF has no bookmarks."); return }
        val i = Dialogs.choose(a, "Bookmarks", list.map { "  ".repeat(it.depth) + it.title + "  ·  p. ${it.page}" }) ?: return
        a.b.pdf.goToPage(list[i].page - 1)
    }

    suspend fun comments(a: ReaderActivity) {
        val s = a.session ?: return
        val all = s.read { d -> (1..d.countPages()).flatMap { p -> Annotations.list(d, p).map { p to it } } }
        if (all.isEmpty()) { Dialogs.message(a, "Comments", "This PDF has no comments."); return }
        val i = Dialogs.choose(a, "${all.size} comments", all.map { (p, c) ->
            "p. $p · ${c.typeName}" + (if (c.author.isNotBlank()) " · ${c.author}" else "") + (if (c.contents.isNotBlank()) ": ${c.contents.take(80)}" else "")
        }) ?: return
        val (p, c) = all[i]
        a.b.pdf.reveal(p - 1, c.rect.x0, c.rect.y0)
    }

    /** OCR: recognises pages on a background thread, then adds the text layer as one undoable change. */
    suspend fun ocr(a: ReaderActivity) {
        val s = a.session ?: return
        val langChecks = Ocr.LANGUAGES.map { (k, v) -> k to Dialogs.check(a, v, k == "eng") }
        val which = Dialogs.radios(a, listOf("Pages without text", "All pages", "This page only"), 0)
        val view = Dialogs.column(a, Dialogs.label(a, "Languages in the document"), *langChecks.map { it.second }.toTypedArray(),
            Dialogs.label(a, "Pages"), which)
        if (!Dialogs.custom(a, "Make text searchable", view, "Start")) return
        val langs = langChecks.filter { it.second.isChecked }.map { it.first }.ifEmpty { listOf("eng") }
        val n = s.sizes.size
        val pages = when (which.selectedIndex()) {
            1 -> (0 until n).toList()
            2 -> listOf(a.b.pdf.currentPage)
            else -> s.read { d -> (0 until n).filter { !TextLayer.hasText(d, it + 1) } }
        }
        if (pages.isEmpty()) { Dialogs.message(a, "Nothing to do", "Every page already has text. Choose All pages to recognise them again."); return }
        val ocr = Ocr(a)
        val results = HashMap<Int, Ocr.PageResult>()
        val ok = a.busy("Recognising text…") {
            withContext(Dispatchers.Default) {
                pages.forEachIndexed { k, p ->
                    a.runOnUiThread { a.b.status.text = "Recognising page ${p + 1} (${k + 1} of ${pages.size})…" }
                    results[p] = ocr.recognize(s, p, langs)
                }
            }
            true
        } ?: return
        if (!ok) return
        val words = results.values.sumOf { it.words.size }
        if (words == 0) { Dialogs.message(a, "No text found", "No text was recognised on the selected pages."); return }
        a.busy("Adding the text layer…") {
            s.edit("Make text searchable", pages.toSet()) { d ->
                val font = TextLayer.newFont(d)
                for ((p, r) in results) TextLayer.addWords(d, p + 1, r.words, font)
            }
        }
        a.refreshUndo()
        val low = results.filter { it.value.meanConfidence in 1..69 }.keys.sorted().map { it + 1 }
        Dialogs.message(a, "Text recognised", "$words words on ${results.size} page(s) are now searchable and can be copied. The pages look exactly as before." +
            (if (low.isNotEmpty()) "\n\nLow confidence on page(s) ${low.joinToString(", ")}: check the text before relying on it." else "") +
            "\n\nRecognised text can contain mistakes.")
    }

    suspend fun compress(a: ReaderActivity) {
        val s = a.session ?: return
        val keys = Compress.PRESETS.keys.toList()
        val i = Dialogs.choose(a, "Compress", Compress.PRESETS.values.map { it.label }, 2) ?: return
        val src = snapshot(a) ?: return
        val out = Files.temp(a, "compressed.pdf")
        val r = a.busy("Compressing…") {
            s.read { _ -> }
            withContext(DocSession.engineDispatcher) {
                val d = Pdf.open(src.absolutePath, s.password)
                try { Compress.compress(d, src, out, keys[i]) } finally { d.destroy() }
            }
        } ?: return
        if (!r.kept) {
            Dialogs.message(a, "Already compact", "This setting would not make the file smaller (${Files.formatSize(r.before)}). Nothing was written.")
            return
        }
        val pct = 100 - (r.after * 100 / maxOf(1, r.before))
        if (!Dialogs.confirm(a, "Smaller by $pct%", "${Files.formatSize(r.before)} → ${Files.formatSize(r.after)}" +
                (if (r.imagesResampled > 0) ", ${r.imagesResampled} picture(s) resampled" else "") + ". Save the compressed copy?", "Save")) return
        a.pendingExport = out to "Compressed copy"
        a.exportLauncher.launch(Files.stem(s.name) + "_compressed.pdf")
    }

    suspend fun protect(a: ReaderActivity) {
        val s = a.session ?: return
        val pwType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val (t1, open1) = Dialogs.field(a, "Password to open", "", pwType)
        val (t2, open2) = Dialogs.field(a, "Repeat password", "", pwType)
        val restrict = Dialogs.check(a, "Also restrict printing, copying and editing", false)
        val (t3, owner) = Dialogs.field(a, "Permissions password (to lift the restrictions)", "", pwType)
        t3.visibility = android.view.View.GONE
        val print = Dialogs.check(a, "Allow printing", true).apply { visibility = android.view.View.GONE }
        val copy = Dialogs.check(a, "Allow copying text", false).apply { visibility = android.view.View.GONE }
        val edit = Dialogs.check(a, "Allow changes and comments", false).apply { visibility = android.view.View.GONE }
        restrict.setOnCheckedChangeListener { _, on ->
            val v = if (on) android.view.View.VISIBLE else android.view.View.GONE
            listOf(t3, print, copy, edit).forEach { it.visibility = v }
        }
        val view = android.widget.ScrollView(a).apply { addView(Dialogs.column(a, t1, t2, restrict, t3, print, copy, edit)) }
        if (!Dialogs.custom(a, "Protect with password", view, "Protect")) return
        val pw = open1.text?.toString() ?: ""
        if (pw != (open2.text?.toString() ?: "")) { Dialogs.message(a, "Passwords differ", "The two passwords are not the same."); return }
        val ownerPw = owner.text?.toString()?.takeIf { restrict.isChecked && it.isNotEmpty() }
        if (restrict.isChecked && ownerPw == null) { Dialogs.message(a, "Permissions password needed", "Restrictions need their own password; otherwise anyone who can open the file could lift them."); return }
        if (pw.isEmpty() && ownerPw == null) { Dialogs.message(a, "No password", "Enter a password."); return }
        val perms = if (restrict.isChecked) Security.Permissions(print = print.isChecked, copy = copy.isChecked, edit = edit.isChecked,
            annotate = edit.isChecked, forms = edit.isChecked, assemble = edit.isChecked) else Security.Permissions()
        val src = snapshot(a) ?: return
        val out = Files.temp(a, "protected.pdf")
        a.busy("Protecting…") {
            withContext(DocSession.engineDispatcher) {
                val d = Pdf.open(src.absolutePath, s.password)
                try { Security.protect(d, out, pw.ifEmpty { null }, ownerPw, perms) } finally { d.destroy() }
            }
            true
        } ?: return
        a.pendingExport = out to "Protected copy"
        a.exportLauncher.launch(Files.stem(s.name) + "_protected.pdf")
        Ui.toast(a, "Keep the password safe: it cannot be recovered.")
    }

    suspend fun unprotect(a: ReaderActivity) {
        val s = a.session ?: return
        val encrypted = s.read { Security.isEncrypted(it) }
        if (!encrypted) { Dialogs.message(a, "Not protected", "This PDF has no password."); return }
        var password = s.password
        if (!s.read { Security.hasFullRights(it) }) {
            password = Dialogs.password(a, "Permissions password", "Removing protection needs the permissions (owner) password. N PDF does not crack or bypass passwords.") ?: return
        }
        val src = snapshot(a) ?: return
        val out = Files.temp(a, "unprotected.pdf")
        a.busy("Removing protection…") {
            withContext(DocSession.engineDispatcher) {
                val d = Pdf.open(src.absolutePath, password)
                try { Security.removeProtection(d, out) } finally { d.destroy() }
            }
            true
        } ?: return
        a.pendingExport = out to "Unprotected copy"
        a.exportLauncher.launch(Files.stem(s.name) + "_unprotected.pdf")
    }

    suspend fun split(a: ReaderActivity) {
        val s = a.session ?: return
        val n = s.sizes.size
        val mode = Dialogs.radios(a, listOf("By page ranges", "Every N pages"), 0)
        val (t1, ranges) = Dialogs.field(a, "Ranges, e.g. 1-3, 4-$n", "")
        val (t2, every) = Dialogs.field(a, "Pages per file", "1", InputType.TYPE_CLASS_NUMBER)
        if (!Dialogs.custom(a, "Split ($n pages)", Dialogs.column(a, mode, t1, t2, Dialogs.label(a, "Then choose the folder for the new files.")), "Choose folder")) return
        val byRanges = mode.selectedIndex() == 0
        val r = ranges.text?.toString()?.trim().orEmpty()
        val e = every.text?.toString()?.toIntOrNull()
        if (byRanges && r.isEmpty()) { Dialogs.message(a, "Ranges needed", "Enter ranges such as 1-3, 4-$n."); return }
        if (!byRanges && (e == null || e < 1)) { Dialogs.message(a, "Number needed", "Enter how many pages each file gets."); return }
        a.pendingSplit = { tree -> a.lifecycleScope.launch { doSplit(a, tree, if (byRanges) r else null, if (byRanges) null else e) } }
        a.treeLauncher.launch(null)
    }

    private suspend fun doSplit(a: ReaderActivity, tree: Uri, ranges: String?, every: Int?) {
        val s = a.session ?: return
        val src = snapshot(a) ?: return
        val dir = Files.workDir(a, "out/split_${System.nanoTime()}")
        val files = a.busy("Splitting…") {
            withContext(DocSession.engineDispatcher) {
                val d = Pdf.open(src.absolutePath, s.password)
                try { Pages.split(d, dir, Files.safe(Files.stem(s.name)), ranges, every) } finally { d.destroy() }
            }
        } ?: return
        val folder = DocumentFile.fromTreeUri(a, tree) ?: return
        val written = a.busy("Saving ${files.size} files…") {
            withContext(Dispatchers.IO) {
                files.map { f ->
                    val doc = folder.createFile("application/pdf", f.nameWithoutExtension) ?: throw java.io.IOException("Cannot create ${f.name}")
                    Files.writeToUri(a, f, doc.uri)
                    doc.name ?: f.name
                }
            }
        } ?: return
        dir.deleteRecursively()
        Dialogs.message(a, "Split into ${written.size} files", written.joinToString("\n"))
    }

    suspend fun flatten(a: ReaderActivity) {
        val s = a.session ?: return
        if (!Dialogs.confirm(a, "Flatten?", "Comments and form fields become part of the page and can no longer be edited or filled. You can undo until you close the document.", "Flatten")) return
        a.busy("Flattening…") { s.edit("Flatten", null) { Annotations.flatten(it, widgets = true) } }
        a.refreshUndo()
    }

    suspend fun properties(a: ReaderActivity) {
        val s = a.session ?: return
        val meta = s.read { DocInfo.metadata(it) }
        val fmt = s.read { DocInfo.format(it) }
        val enc = s.read { Security.isEncrypted(it) }
        val size = s.uri?.let { Files.size(a, it) } ?: -1
        val fields = listOf("Title", "Author", "Subject", "Keywords").map { k -> k to Dialogs.field(a, k, meta[k] ?: "") }
        val info = Dialogs.label(a, "${s.sizes.size} pages · $fmt" + (if (size > 0) " · ${Files.formatSize(size)}" else "") +
            (if (enc) " · password protected" else "") + "\nCreated with: ${meta["Creator"].orEmpty().ifBlank { "unknown" }}" +
            "\nProducer: ${meta["Producer"].orEmpty().ifBlank { "unknown" }}")
        val view = android.widget.ScrollView(a).apply { addView(Dialogs.column(a, info, *fields.map { it.second.first }.toTypedArray())) }
        if (!Dialogs.custom(a, "Properties", view, "Save")) return
        val values = fields.associate { (k, f) -> k to (f.second.text?.toString() ?: "") }
        if (values.all { (k, v) -> v == (meta[k] ?: "") }) return
        a.busy("Saving properties…") { s.edit("Properties", emptySet()) { DocInfo.setMetadata(it, values) } }
        a.refreshUndo()
    }
}
