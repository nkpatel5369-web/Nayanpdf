package npdf.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import npdf.app.databinding.ActivityReaderBinding
import npdf.engine.Annotations
import npdf.engine.Codes
import npdf.engine.DocInfo
import npdf.engine.Forms
import npdf.engine.PdfError
import java.io.File

class ReaderActivity : AppCompatActivity(), PdfView.Listener {

    lateinit var b: ActivityReaderBinding
    var session: DocSession? = null
    val prefs by lazy { Prefs(this) }
    var mode = R.id.mode_view
        private set
    var toolId: String? = null
    private var canUndo = false
    private var canRedo = false
    private var openStarted = 0L
    var lastOpenToFirstPageMs = -1L
    var readAloud: ReadAloud? = null
    val tools by lazy { ReaderTools(this) }

    /** Text the user has marked for redaction; checked as gone after applying. */
    val redactionSecrets = mutableListOf<String>()

    // Destinations chosen in the system file dialogs.
    var pendingExport: Pair<File, String>? = null
    var pendingImageAction: ((ByteArray) -> Unit)? = null
    var pendingSplit: ((Uri) -> Unit)? = null

    val saveAsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) lifecycleScope.launch { saveAs(uri) }
    }
    val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val p = pendingExport
        pendingExport = null
        if (uri != null && p != null) lifecycleScope.launch { exportTo(p.first, uri, p.second) }
    }
    val imageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val act = pendingImageAction
        pendingImageAction = null
        if (uri != null && act != null) {
            try { act(Files.readBytes(this, uri)) } catch (e: Exception) { Ui.toast(this, Ui.describe(e)) }
        }
    }
    val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val act = pendingSplit
        pendingSplit = null
        if (uri != null && act != null) act(uri)
    }

    companion object {
        const val EXTRA_MODE = "npdf.mode"
        const val EXTRA_ACTION = "npdf.action"
        const val ACTION_ORGANIZE = "organize"
        const val ACTION_SPLIT = "split"
        const val ACTION_COMPRESS = "compress"
        const val ACTION_PROTECT = "protect"
        const val ACTION_UNPROTECT = "unprotect"
        const val ACTION_OCR = "ocr"
        const val TAG = "NPDF"
    }

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.fitSystemBars(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        b.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        b.pdf.listener = this
        b.pageChip.setOnClickListener { lifecycleScope.launch { goToPageDialog() } }
        b.modes.setOnItemSelectedListener { switchMode(it.itemId); true }
        b.modes.setOnItemReselectedListener { }
        setupSearch()
        b.ttsPlay.setOnClickListener { toggleReadAloud() }
        b.ttsStop.setOnClickListener { readAloud?.stop() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { lifecycleScope.launch { leave() } }
        })
        val uri = docUri()
        if (uri == null) { finish(); return }
        open(uri, null)
    }

    private fun docUri(): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> intent.data
    }

    private fun open(uri: Uri, password: String?) {
        openStarted = SystemClock.elapsedRealtime()
        showBusy(true, "Opening…")
        lifecycleScope.launch {
            try {
                val s = DocSession.open(this@ReaderActivity, uri, password)
                attach(s)
            } catch (e: PdfError) {
                showBusy(false)
                if (e.code == Codes.PASSWORD_REQUIRED || e.code == Codes.PASSWORD_WRONG) {
                    val pw = Dialogs.password(this@ReaderActivity,
                        if (e.code == Codes.PASSWORD_WRONG) "Wrong password" else "Password required",
                        "\"${Files.displayName(this@ReaderActivity, uri)}\" is protected. Enter its password.")
                    if (pw == null) finish() else open(uri, pw)
                } else fail(e.message ?: "The file could not be opened.")
            } catch (e: Throwable) {
                showBusy(false)
                fail(Ui.describe(e))
            }
        }
    }

    private fun fail(msg: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Cannot open this file").setMessage(msg)
            .setPositiveButton("OK") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }

    private fun attach(s: DocSession) {
        session?.close()
        session = s
        DocSession.current = s
        val start = s.uri?.let { prefs.lastPage(it) } ?: 1
        b.pdf.firstPageAt = 0L
        b.pdf.firstRenderedPage = (start - 1).coerceIn(0, maxOf(0, s.sizes.size - 1))
        b.pdf.onFirstPage = {
            lastOpenToFirstPageMs = SystemClock.elapsedRealtime() - openStarted
            Log.i("NPDF-PERF", "open to first page: $lastOpenToFirstPageMs ms (${s.sizes.size} pages)")
            showBusy(false)
        }
        b.pdf.setSession(s, start - 1)
        s.listeners.add { refreshUndo(); updateTitle() }
        updateTitle()
        onPageChanged(start - 1)
        refreshUndo()
        if (s.restoredJournal) {
            Snackbar.make(b.pdf, "Your unsaved changes were restored.", Snackbar.LENGTH_LONG)
                .setAction("Discard") { lifecycleScope.launch { discardAll() } }.show()
        }
        rememberRecent()
        intent.getIntExtra(EXTRA_MODE, 0).takeIf { it != 0 }?.let { b.modes.selectedItemId = it }
        if (s.sizes.isEmpty()) { showBusy(false); fail("This PDF has no pages."); return }
        intent.getStringExtra(EXTRA_ACTION)?.let { act ->
            intent.removeExtra(EXTRA_ACTION)
            lifecycleScope.launch { runAction(act) }
        }
    }

    /** A tool started from the home screen, run once the document is open. */
    private suspend fun runAction(act: String) {
        when (act) {
            ACTION_ORGANIZE -> startActivity(Intent(this, OrganizeActivity::class.java))
            ACTION_SPLIT -> ReaderActions.split(this)
            ACTION_COMPRESS -> ReaderActions.compress(this)
            ACTION_PROTECT -> ReaderActions.protect(this)
            ACTION_UNPROTECT -> ReaderActions.unprotect(this)
            ACTION_OCR -> ReaderActions.ocr(this)
        }
    }

    fun updateTitle() {
        val s = session ?: return
        supportActionBar?.title = (if (s.dirty) "• " else "") + s.name
        supportActionBar?.subtitle = "${s.sizes.size} page" + if (s.sizes.size == 1) "" else "s"
    }

    private fun rememberRecent() {
        val s = session ?: return
        val uri = s.uri ?: return
        val page = b.pdf.currentPage + 1
        lifecycleScope.launch {
            val thumb = try { Thumbs.make(this@ReaderActivity, s, uri) } catch (e: Throwable) { null }
            prefs.remember(uri, s.name, page, thumb)
        }
    }

    override fun onStop() {
        super.onStop()
        val s = session ?: return
        s.uri?.let { prefs.remember(it, s.name, b.pdf.currentPage + 1, prefs.recents().firstOrNull { r -> r.uri == it }?.thumb) }
        lifecycleScope.launch { try { s.persistJournal() } catch (e: Throwable) { /* best effort */ } }
    }

    override fun onDestroy() {
        readAloud?.shutdown()
        if (isFinishing) session?.close()
        super.onDestroy()
    }

    /** Leaving with unsaved changes asks first. */
    private suspend fun leave() {
        val s = session
        if (s != null && s.dirty) {
            when (Dialogs.ask3(this, "Save changes?", "\"${s.name}\" has changes that are not saved.", "Save", "Cancel", "Discard")) {
                0 -> { if (!save()) return }
                2 -> s.dropJournal()
                else -> return
            }
        }
        finish()
    }

    private suspend fun discardAll() {
        val s = session ?: return
        while (s.canUndo()) s.undo()
        s.dropJournal()
        refreshUndo()
    }

    // ----------------------------------------------------------------- status

    fun showBusy(on: Boolean, msg: String? = null) {
        b.progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on && msg != null) { b.status.text = msg; b.status.visibility = View.VISIBLE } else b.status.visibility = View.GONE
    }

    /** Runs [block] with the busy indicator; failures are shown, never thrown. */
    suspend fun <T> busy(msg: String, block: suspend () -> T): T? {
        showBusy(true, msg)
        return try {
            block()
        } catch (e: Throwable) {
            Log.w(TAG, "$msg failed: ${e.javaClass.simpleName}")
            Dialogs.message(this, "Could not complete", Ui.describe(e))
            null
        } finally {
            showBusy(false)
        }
    }

    fun refreshUndo() {
        val s = session ?: return
        lifecycleScope.launch {
            canUndo = s.canUndo(); canRedo = s.canRedo()
            invalidateOptionsMenu()
            updateTitle()
        }
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_undo)?.isEnabled = canUndo
        menu.findItem(R.id.action_redo)?.isEnabled = canRedo
        menu.findItem(R.id.action_undo)?.icon?.alpha = if (canUndo) 255 else 90
        menu.findItem(R.id.action_redo)?.icon?.alpha = if (canRedo) 255 else 90
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val s = session ?: return super.onOptionsItemSelected(item)
        lifecycleScope.launch {
            when (item.itemId) {
                R.id.action_search -> openSearch()
                R.id.action_undo -> { s.undo(); refreshUndo() }
                R.id.action_redo -> { s.redo(); refreshUndo() }
                R.id.action_save -> save()
                R.id.action_save_as -> saveAsLauncher.launch(Files.stem(s.name) + ".pdf")
                R.id.action_share -> ReaderActions.share(this@ReaderActivity)
                R.id.action_print -> ReaderActions.print(this@ReaderActivity)
                R.id.action_read -> startReadAloud()
                R.id.action_goto -> goToPageDialog()
                R.id.action_bookmarks -> ReaderActions.bookmarks(this@ReaderActivity)
                R.id.action_comments -> ReaderActions.comments(this@ReaderActivity)
                R.id.action_organize -> startActivity(Intent(this@ReaderActivity, OrganizeActivity::class.java))
                R.id.action_ocr -> ReaderActions.ocr(this@ReaderActivity)
                R.id.action_compress -> ReaderActions.compress(this@ReaderActivity)
                R.id.action_protect -> ReaderActions.protect(this@ReaderActivity)
                R.id.action_unprotect -> ReaderActions.unprotect(this@ReaderActivity)
                R.id.action_split -> ReaderActions.split(this@ReaderActivity)
                R.id.action_flatten -> ReaderActions.flatten(this@ReaderActivity)
                R.id.action_properties -> ReaderActions.properties(this@ReaderActivity)
            }
        }
        return true
    }

    // ------------------------------------------------------------------ saving

    /** Saves over the original (asked once per file) or, when that is not possible, as a new file. */
    suspend fun save(): Boolean {
        val s = session ?: return false
        val uri = s.uri
        if (uri == null || !Files.canWrite(this, uri)) {
            Ui.toast(this, "This file cannot be changed where it is. Choose where to save a copy.")
            saveAsLauncher.launch(Files.stem(s.name) + ".pdf")
            return false
        }
        if (uri.toString() !in prefs.confirmedReplace) {
            when (Dialogs.ask3(this, "Replace the original?", "Saving replaces \"${s.name}\" with the edited version. Choose Save as copy to keep the original unchanged.", "Replace", "Cancel", "Save as copy")) {
                0 -> prefs.confirmedReplace = prefs.confirmedReplace + uri.toString()
                2 -> { saveAsLauncher.launch(Files.stem(s.name) + " (edited).pdf"); return false }
                else -> return false
            }
        }
        val ok = busy("Saving…") { s.saveTo(uri); true } ?: false
        if (ok) { Ui.toast(this, "Saved"); refreshUndo(); rememberRecent() }
        return ok
    }

    private suspend fun saveAs(target: Uri) {
        val s = session ?: return
        Files.persist(this, target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val name = Files.displayName(this, target)
        val ok = busy("Saving…") { s.saveTo(target, name); true } ?: false
        if (ok) { Ui.toast(this, "Saved as $name"); refreshUndo(); rememberRecent() }
    }

    /** Writes a finished file (compressed, protected...) to a place the user chose. */
    private suspend fun exportTo(file: File, uri: Uri, label: String) {
        val ok = busy("Saving…") { Files.writeToUri(this, file, uri); true } ?: false
        if (!ok) return
        Files.persist(this, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        Snackbar.make(b.pdf, "$label saved as ${Files.displayName(this, uri)}", Snackbar.LENGTH_LONG)
            .setAction("Open") {
                startActivity(Intent(this, ReaderActivity::class.java).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            }.show()
    }

    // -------------------------------------------------------------- navigation

    override fun onPageChanged(page: Int) {
        val n = session?.sizes?.size ?: 0
        b.pageChip.text = "${page + 1} / $n"
    }

    suspend fun goToPageDialog() {
        val n = session?.sizes?.size ?: return
        val v = Dialogs.text(this, "Go to page", "Page (1-$n)", "", type = android.text.InputType.TYPE_CLASS_NUMBER, ok = "Go") ?: return
        val p = v.trim().toIntOrNull()
        if (p == null || p < 1 || p > n) { Ui.toast(this, "Enter a page number from 1 to $n."); return }
        b.pdf.goToPage(p - 1)
    }

    // ------------------------------------------------------------------- modes

    fun switchMode(id: Int) {
        mode = id
        tools.clear()
        b.pdf.tool = null
        b.pdf.selection = null
        tools.buildRow(id)
    }

    // ----------------------------------------------------------- tap handling

    override fun onTap(page: Int, x: Float, y: Float): Boolean {
        val s = session ?: return false
        if (b.pdf.selection != null) { clearSelection(); return true }
        lifecycleScope.launch {
            // Form fields first (they can be filled in View and Fill & Sign modes).
            if (mode == R.id.mode_view || mode == R.id.mode_sign) {
                val field = s.read { Forms.at(it, page + 1, Point(x, y)) }
                if (field != null) { tools.fillField(field); return@launch }
            }
            val annot = s.read { Annotations.at(it, page + 1, Point(x, y)) }
            if (annot != null && annot.type != PDFAnnotation.TYPE_LINK) { tools.annotationMenu(page, annot); return@launch }
            val link = s.read { d -> (d.loadPage(page).links ?: emptyArray<Link>()).firstOrNull { l -> l.bounds.contains(x, y) }?.let { l ->
                if (l.isExternal) l.uri to -1 else l.uri to try { d.pageNumberFromLocation(d.resolveLink(l)) } catch (e: Exception) { -1 }
            } }
            if (link != null) followLink(link.first, link.second)
        }
        return true
    }

    private fun followLink(uri: String?, page: Int) {
        if (page >= 0) { b.pdf.goToPage(page); return }
        val target = uri ?: return
        Snackbar.make(b.pdf, "N PDF works offline and does not open web links: $target", Snackbar.LENGTH_LONG)
            .setAction("Copy") { copyText(target) }.show()
    }

    fun copyText(t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("N PDF", t))
        Ui.toast(this, "Copied")
    }

    // --------------------------------------------------------- text selection

    var selPage = -1
    var selA: Point? = null
    var selB: Point? = null
    private var selJob: Job? = null
    private var actionMode: ActionMode? = null

    override fun onLongPress(page: Int, x: Float, y: Float) {
        if (mode != R.id.mode_view && mode != R.id.mode_comment) return
        val s = session ?: return
        lifecycleScope.launch {
            val quads = s.read {
                val t = s.pageText(page)
                t.selectionRange(x, y, x, y, words = true)?.let { r -> t.quads(r) } ?: emptyArray()
            }
            if (quads.isEmpty()) return@launch
            selPage = page; selA = Point(x, y); selB = Point(x, y)
            b.pdf.selection = page to quads
            b.pdf.tool = tools.selectionHandles
            startSelectionActions()
        }
    }

    /** Recomputes the selection between [a] and [bp] (page points), at most one request at a time. */
    fun updateSelection(page: Int, a: Point, bp: Point, done: ((Array<Quad>) -> Unit)? = null) {
        val s = session ?: return
        if (selJob?.isActive == true && done == null) return
        selJob = lifecycleScope.launch {
            val q = s.read {
                val t = s.pageText(page)
                t.selectionRange(a.x, a.y, bp.x, bp.y, words = true)?.let { r -> t.quads(r) } ?: emptyArray()
            }
            selPage = page; selA = a; selB = bp
            b.pdf.selection = page to q
            done?.invoke(q)
        }
    }

    suspend fun selectedText(): String {
        val s = session ?: return ""
        val a = selA ?: return ""; val bp = selB ?: return ""
        val p = selPage
        return s.read {
            val t = s.pageText(p)
            t.selectionRange(a.x, a.y, bp.x, bp.y, words = true)?.let { r -> t.text(r) } ?: ""
        }
    }

    fun clearSelection() {
        b.pdf.selection = null
        selPage = -1; selA = null; selB = null
        if (b.pdf.tool === tools.selectionHandles) b.pdf.tool = null
        actionMode?.finish(); actionMode = null
    }

    private fun startSelectionActions() {
        if (actionMode != null) return
        actionMode = startSupportActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(m: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, "Copy"); menu.add(0, 2, 1, "Highlight"); menu.add(0, 3, 2, "Underline")
                menu.add(0, 4, 3, "Strike"); menu.add(0, 5, 4, "Redact")
                return true
            }
            override fun onPrepareActionMode(m: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(m: ActionMode, item: MenuItem): Boolean {
                val quads = b.pdf.selection?.second ?: return true
                val page = selPage
                lifecycleScope.launch {
                    when (item.itemId) {
                        1 -> copyText(selectedText())
                        2 -> tools.addMarkup(page, PDFAnnotation.TYPE_HIGHLIGHT, quads.toList())
                        3 -> tools.addMarkup(page, PDFAnnotation.TYPE_UNDERLINE, quads.toList())
                        4 -> tools.addMarkup(page, PDFAnnotation.TYPE_STRIKE_OUT, quads.toList())
                        5 -> tools.markRedactionText(page, quads.toList(), selectedText())
                    }
                    clearSelection()
                }
                return true
            }
            override fun onDestroyActionMode(m: ActionMode) {
                actionMode = null
                if (b.pdf.selection != null) { b.pdf.selection = null; if (b.pdf.tool === tools.selectionHandles) b.pdf.tool = null }
            }
        })
    }

    // ------------------------------------------------------------------ search

    private var hits: List<Pair<Int, Int>> = emptyList()
    private var hitIndex = -1
    private var searchJob: Job? = null

    private fun setupSearch() {
        b.searchField.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { runSearch(b.searchField.text.toString()); true } else false
        }
        b.searchNext.setOnClickListener { stepHit(1) }
        b.searchPrev.setOnClickListener { stepHit(-1) }
        b.searchClose.setOnClickListener { closeSearch() }
    }

    fun openSearch() {
        b.toolbar.visibility = View.GONE
        b.searchBar.visibility = View.VISIBLE
        b.searchField.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(b.searchField, 0)
    }

    private fun closeSearch() {
        searchJob?.cancel()
        b.searchBar.visibility = View.GONE
        b.toolbar.visibility = View.VISIBLE
        b.pdf.searchHits = emptyMap(); b.pdf.currentHit = null
        hits = emptyList(); hitIndex = -1
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.searchField.windowToken, 0)
    }

    /** Searches every page, starting from the current one, showing matches as they are found. */
    fun runSearch(q: String) {
        val s = session ?: return
        searchJob?.cancel()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.searchField.windowToken, 0)
        if (q.isBlank()) return
        val n = s.sizes.size
        val start = b.pdf.currentPage
        val found = sortedMapOf<Int, List<Array<Quad>>>()
        hits = emptyList(); hitIndex = -1
        b.searchCount.text = "…"
        searchJob = lifecycleScope.launch {
            for (k in 0 until n) {
                val p = (start + k) % n
                val res = s.read { d -> DocInfo.searchPage(d, p + 1, q).map { it.quads } }
                if (res.isNotEmpty()) {
                    found[p] = res
                    b.pdf.searchHits = found.toMap()
                    hits = found.flatMap { (pg, list) -> list.indices.map { pg to it } }
                    if (hitIndex < 0) { hitIndex = hits.indexOfFirst { it.first == p }; showHit() }
                    b.searchCount.text = "${hitIndex + 1}/${hits.size}"
                }
            }
            if (hits.isEmpty()) b.searchCount.text = "0/0"
        }
    }

    private fun stepHit(d: Int) {
        if (hits.isEmpty()) return
        hitIndex = (hitIndex + d + hits.size) % hits.size
        showHit()
    }

    private fun showHit() {
        val (p, k) = hits.getOrNull(hitIndex) ?: return
        b.pdf.currentHit = p to k
        val q = b.pdf.searchHits[p]?.getOrNull(k)?.firstOrNull() ?: return
        b.pdf.reveal(p, q.ul_x, q.ul_y)
        b.searchCount.text = "${hitIndex + 1}/${hits.size}"
    }

    // -------------------------------------------------------------- read aloud

    private fun startReadAloud() {
        val s = session ?: return
        val ra = readAloud ?: ReadAloud(this, lifecycleScope) { state, msg ->
            when (state) {
                ReadAloud.State.ERROR -> { b.ttsBar.visibility = View.GONE; Dialogs.message(this, "Read Out Loud", msg) }
                ReadAloud.State.STOPPED -> b.ttsBar.visibility = View.GONE
                ReadAloud.State.PAUSED -> { b.ttsText.text = msg; b.ttsPlay.setImageResource(R.drawable.ic_play) }
                else -> {
                    b.ttsBar.visibility = View.VISIBLE; b.ttsText.text = msg; b.ttsPlay.setImageResource(R.drawable.ic_pause)
                    readAloud?.currentPage?.let { if (it < s.sizes.size && it != b.pdf.currentPage) b.pdf.goToPage(it) }
                }
            }
        }.also { readAloud = it }
        ra.start(s, b.pdf.currentPage)
    }

    private fun toggleReadAloud() {
        val ra = readAloud ?: return
        if (b.ttsText.text.startsWith("Paused")) ra.resume() else ra.pause()
    }

    fun toolButton(label: String, icon: Int, onClick: (MaterialButton) -> Unit) = Ui.toolButton(this, label, icon, onClick)

    fun markSelected(btn: MaterialButton?, on: Boolean) = Ui.markSelected(btn, on)
}
