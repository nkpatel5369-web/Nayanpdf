package npdf.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import npdf.app.Dialogs.selectedIndex
import npdf.engine.Export
import java.io.File

/** Home: Files (scan, open, recent files), Tools and Settings, under Material 3's navigation bar. */
class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_SCAN = "npdf.action.SCAN"
        const val ACTION_OPEN = "npdf.action.OPEN"
        const val ACTION_PHOTOS = "npdf.action.PHOTOS"
    }

    private val prefs by lazy { Prefs(this) }
    private lateinit var tabs: Kit.TabBar
    private lateinit var pages: FrameLayout
    private lateinit var files: LargeTitlePage
    private lateinit var fab: ExtendedFloatingActionButton
    private var recentBox: LinearLayout? = null
    private var recentHeader: View? = null
    private var emptyView: View? = null
    private var filter = ""

    /** What to do with the document picked next: a reader mode and/or action. */
    private var pendingMode = 0
    private var pendingAction: String? = null
    private var pendingFormat: String? = null

    private val openLauncher = registerForActivityResult(OpenPdf()) { uri ->
        if (uri != null) openReader(uri, pendingMode, pendingAction, pendingFormat)
        pendingMode = 0; pendingAction = null; pendingFormat = null
    }

    private val photosLauncher = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) startActivity(Intent(this, ScanReviewActivity::class.java)
            .putParcelableArrayListExtra(ScanReviewActivity.EXTRA_IMPORT, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** OPEN_DOCUMENT that asks for persistable read and write access. */
    class OpenPdf : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Kit.color(this@MainActivity, R.color.bg)) }
        pages = FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        tabs = Kit.TabBar(this)
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        files = filesPage()
        val tools = toolsPage()
        val settings = settingsPage()
        for (p in listOf(files.root, tools.root, settings.root)) { p.visibility = View.GONE; pages.addView(p) }
        tabs.add(R.id.tab_files, "Files", R.drawable.ic_folder)
        tabs.add(R.id.tab_tools, "Tools", R.drawable.ic_tools)
        tabs.add(R.id.tab_settings, "Settings", R.drawable.ic_settings)
        tabs.onSelect = { id ->
            files.root.visibility = if (id == R.id.tab_files) View.VISIBLE else View.GONE
            tools.root.visibility = if (id == R.id.tab_tools) View.VISIBLE else View.GONE
            settings.root.visibility = if (id == R.id.tab_settings) View.VISIBLE else View.GONE
        }
        tabs.select(savedInstanceState?.getInt("tab", R.id.tab_files) ?: R.id.tab_files)
        if (savedInstanceState == null) handleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    /** Quick actions from the app icon (long press): Scan, Open, Photos to PDF. */
    private fun handleShortcut(i: Intent?) {
        when (i?.action) {
            ACTION_SCAN -> startActivity(Intent(this, ScanActivity::class.java))
            ACTION_OPEN -> pick(0, null)
            ACTION_PHOTOS -> pickPhotos()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tabs.selected)
    }

    override fun onResume() {
        super.onResume()
        showRecents()
    }

    private fun pick(mode: Int, action: String?, format: String? = null) {
        pendingMode = mode; pendingAction = action; pendingFormat = format
        openLauncher.launch(arrayOf("application/pdf"))
    }

    private fun pickPhotos() = photosLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    private fun openReader(uri: Uri, mode: Int = 0, action: String? = null, format: String? = null) {
        Files.persist(this, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val i = Intent(this, ReaderActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (mode != 0) i.putExtra(ReaderActivity.EXTRA_MODE, mode)
        action?.let { i.putExtra(ReaderActivity.EXTRA_ACTION, it) }
        format?.let { i.putExtra(ReaderActivity.EXTRA_FORMAT, it) }
        startActivity(i)
    }

    // ------------------------------------------------------------------ Files

    private fun filesPage(): LargeTitlePage {
        val p = LargeTitlePage(this, "N PDF")
        val ctx = this
        val (search, _) = Kit.searchField(this, "Search recent files") { q -> filter = q.trim(); showRecents() }
        p.add(search, 16)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Kit.dpi(ctx, 16), Kit.dpi(ctx, 16), Kit.dpi(ctx, 16), 0) }
        fun lp(first: Boolean) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (!first) marginStart = Kit.dpi(ctx, 8) }
        row.addView(Kit.tonalButton(this, "Open PDF", R.drawable.ic_open) { pick(0, null) }.apply { id = R.id.openBtn }, lp(true))
        row.addView(Kit.tonalButton(this, "Photos to PDF", R.drawable.ic_gallery) { pickPhotos() }.apply { id = R.id.photosBtn }, lp(false))
        p.content.addView(row)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Kit.dpi(ctx, 16), Kit.dpi(ctx, 24), Kit.dpi(ctx, 16), Kit.dpi(ctx, 8))
        }
        header.addView(Kit.text(this, "Recent files", 16f, 500), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sort = Kit.segmented(this, listOf("Date", "Name"), if (prefs.recentSort == "name") 1 else 0)
        sort.setOnCheckedChangeListener { g, _ -> prefs.recentSort = if (g.selectedIndex() == 1) "name" else "date"; showRecents() }
        header.addView(sort, LinearLayout.LayoutParams(Kit.dpi(ctx, 168), ViewGroup.LayoutParams.WRAP_CONTENT))
        p.content.addView(header)
        recentHeader = header
        val box = LinearLayout(this).apply { id = R.id.recentList; orientation = LinearLayout.VERTICAL }
        p.content.addView(box)
        recentBox = box
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Kit.dpi(ctx, 32), Kit.dpi(ctx, 48), Kit.dpi(ctx, 32), Kit.dpi(ctx, 24))
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_doc); imageTintList = android.content.res.ColorStateList.valueOf(Kit.color(ctx, R.color.label2)) },
                LinearLayout.LayoutParams(Kit.dpi(ctx, 48), Kit.dpi(ctx, 48)))
            addView(Kit.text(ctx, "No recent files", 22f, 400).apply { gravity = Gravity.CENTER; setPadding(0, Kit.dpi(ctx, 16), 0, Kit.dpi(ctx, 8)) })
            addView(Kit.text(ctx, "Scan a document or open a PDF. Everything stays on this phone.", 14f, 400, R.color.label2).apply { gravity = Gravity.CENTER })
        }
        p.content.addView(empty)
        emptyView = empty
        // Room for the Scan button over the end of the list.
        p.content.setPadding(0, 0, 0, Kit.dpi(ctx, 96))

        fab = ExtendedFloatingActionButton(this).apply {
            id = R.id.scanBtn
            text = "Scan"
            setIconResource(R.drawable.ic_scan)
            contentDescription = "Scan"
            setOnClickListener { startActivity(Intent(this@MainActivity, ScanActivity::class.java)) }
        }
        p.root.addView(fab, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(Kit.dpi(ctx, 16), Kit.dpi(ctx, 16), Kit.dpi(ctx, 16), Kit.dpi(ctx, 16))
        })
        // The button shrinks to its icon while the list scrolls down, as in Google's apps.
        p.scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, y, _, oldY ->
            if (y > oldY + 4 && fab.isExtended) fab.shrink() else if (y < oldY - 4 && !fab.isExtended) fab.extend()
        })
        return p
    }

    private fun showRecents() {
        val box = recentBox ?: return
        box.removeAllViews()
        var items = prefs.recents()
        if (prefs.recentSort == "name") items = items.sortedBy { it.name.lowercase() }
        val all = items.size
        if (filter.isNotEmpty()) items = items.filter { it.name.contains(filter, ignoreCase = true) }
        emptyView?.visibility = if (all == 0) View.VISIBLE else View.GONE
        recentHeader?.visibility = if (all == 0) View.GONE else View.VISIBLE
        box.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val ctx = this
        for (r in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Kit.cellBackground(ctx)
                minimumHeight = Kit.dpi(ctx, 72)
                setPadding(Kit.dpi(ctx, 16), Kit.dpi(ctx, 8), Kit.dpi(ctx, 4), Kit.dpi(ctx, 8))
                contentDescription = r.name
                isClickable = true
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Kit.rounded(ctx, Kit.color(ctx, R.color.viewer_bg), 4f, Kit.color(ctx, R.color.separator), 1f)
                clipToOutline = true
                r.thumb?.let { t -> if (File(t).exists()) setImageBitmap(BitmapFactory.decodeFile(t)) }
                if (drawable == null) { setImageResource(R.drawable.ic_doc); scaleType = ImageView.ScaleType.CENTER_INSIDE; imageTintList = android.content.res.ColorStateList.valueOf(Kit.color(ctx, R.color.label2)) }
            }
            row.addView(thumb, LinearLayout.LayoutParams(Kit.dpi(ctx, 40), Kit.dpi(ctx, 52)).apply { marginEnd = Kit.dpi(ctx, 16) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Kit.text(this, r.name, 16f, 400).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            texts.addView(Kit.text(this, "Page ${r.page} · " + DateUtils.getRelativeTimeSpanString(r.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS), 14f, 400, R.color.label2))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Kit.iconButton(this, R.drawable.ic_more, "More options for ${r.name}") { recentMenu(r) })
            row.setOnClickListener { openRecent(r) }
            row.setOnLongClickListener { Kit.tick(it); recentMenu(r); true }
            box.addView(row)
        }
    }

    private fun openRecent(r: Prefs.Recent) {
        if (canRead(r.uri)) openReader(r.uri) else {
            Ui.toast(this, "\"${r.name}\" is no longer available. Open it again from its folder.")
            prefs.forget(r.uri); showRecents()
        }
    }

    /** A recent file's actions, as on Adobe Scan's file card. */
    private fun recentMenu(r: Prefs.Recent) = lifecycleScope.launch {
        val ctx = this@MainActivity
        if (!canRead(r.uri)) { openRecent(r); return@launch }
        val scan = withContext(Dispatchers.IO) { ScanSession.forUri(ctx, r.uri) }
        val own = withContext(Dispatchers.IO) { Files.isOwnMedia(ctx, r.uri) }
        val actions = mutableListOf<Pair<String, suspend () -> Unit>>()
        actions += "Open" to { openReader(r.uri) }
        if (scan != null) actions += "Modify scan" to {
            startActivity(Intent(ctx, ScanReviewActivity::class.java).putExtra(ScanReviewActivity.EXTRA_SESSION, scan.dir.absolutePath))
        }
        actions += "Share" to {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${r.name}"))
        }
        actions += "Rename" to { rename(r) }
        actions += "Save as pictures (JPEG)" to { openReader(r.uri, action = ReaderActivity.ACTION_PICTURES) }
        actions += "Print" to { openReader(r.uri, action = ReaderActivity.ACTION_PRINT) }
        actions += "Export to Word, Excel, PowerPoint…" to {
            val fmts = Export.Format.values()
            Dialogs.choose(ctx, "Export ${r.name} to", fmts.map { it.label })?.let { openReader(r.uri, action = ReaderActivity.ACTION_EXPORT, format = fmts[it].name) }
        }
        actions += "Compress" to { openReader(r.uri, action = ReaderActivity.ACTION_COMPRESS) }
        actions += "Set password" to { openReader(r.uri, action = ReaderActivity.ACTION_PROTECT) }
        actions += "Remove password" to { openReader(r.uri, action = ReaderActivity.ACTION_UNPROTECT) }
        actions += "Combine with other files" to {
            startActivity(Intent(ctx, MergeActivity::class.java).putParcelableArrayListExtra(FileListActivity.EXTRA_URIS, arrayListOf(r.uri)))
        }
        actions += "Remove from Recent" to { prefs.forget(r.uri); showRecents() }
        if (own) actions += "Delete file" to {
            if (Dialogs.confirm(ctx, "Delete ${r.name}?", "The file is deleted from this phone. This cannot be undone.", "Delete")) {
                val ok = withContext(Dispatchers.IO) { runCatching { contentResolver.delete(r.uri, null, null) > 0 }.getOrDefault(false) }
                if (ok) { prefs.forget(r.uri); scan?.discard(); showRecents(); Ui.toast(ctx, "Deleted ${r.name}") }
                else Dialogs.message(ctx, "Could not delete", "Android did not allow N PDF to delete this file.")
            }
        }
        val i = Dialogs.choose(ctx, r.name, actions.map { it.first }) ?: return@launch
        actions[i].second()
    }

    private suspend fun rename(r: Prefs.Recent) {
        val stem = Files.stem(r.name)
        val v = Dialogs.text(this, "Rename", "File name", stem, ok = "Rename")?.trim() ?: return
        if (v.isEmpty() || v == stem) return
        val newName = npdf.scan.TextInfo.safeName(v).ifBlank { stem } + ".pdf"
        val nu = withContext(Dispatchers.IO) { Files.rename(this@MainActivity, r.uri, newName) }
        if (nu == null) Dialogs.message(this, "Could not rename", "The folder this file is in does not let other apps rename files. Rename it in the Files app instead.")
        else { prefs.renamed(r.uri, nu, newName); showRecents(); Ui.toast(this, "Renamed to $newName") }
    }

    private fun canRead(uri: Uri): Boolean = try {
        contentResolver.openFileDescriptor(uri, "r")?.close(); true
    } catch (e: Exception) { false }

    // ------------------------------------------------------------------ Tools

    private fun toolsPage(): LargeTitlePage {
        val p = LargeTitlePage(this, "Tools")
        p.section("Create").apply {
            row("Scan a document", R.drawable.ic_scan) { startActivity(Intent(this@MainActivity, ScanActivity::class.java)) }
            row("Photos to PDF", R.drawable.ic_gallery) { pickPhotos() }
            row("Combine files", R.drawable.ic_merge) { startActivity(Intent(this@MainActivity, MergeActivity::class.java)) }
        }
        p.section("Edit and sign").apply {
            row("Edit text and images", R.drawable.ic_edit_text) { pick(R.id.mode_edit, null) }
            row("Comment", R.drawable.ic_comment) { pick(R.id.mode_comment, null) }
            row("Fill & Sign", R.drawable.ic_sign) { pick(R.id.mode_sign, null) }
            row("Redact", R.drawable.ic_redact) { pick(R.id.mode_redact, null) }
        }
        p.section("Pages").apply {
            row("Organize pages", R.drawable.ic_pages) { pick(0, ReaderActivity.ACTION_ORGANIZE) }
            row("Split", R.drawable.ic_split) { pick(0, ReaderActivity.ACTION_SPLIT) }
            row("Save pages as pictures", R.drawable.ic_jpeg) { pick(0, ReaderActivity.ACTION_PICTURES) }
        }
        p.section("Convert", "Converted on this phone. Text and tables are rebuilt from the PDF, so check the layout of complex pages.").apply {
            row("Export to Word", R.drawable.ic_fmt_doc) { pick(0, ReaderActivity.ACTION_EXPORT, Export.Format.WORD.name) }
            row("Export to Excel", R.drawable.ic_fmt_sheet) { pick(0, ReaderActivity.ACTION_EXPORT, Export.Format.EXCEL.name) }
            row("Export to PowerPoint", R.drawable.ic_fmt_slides) { pick(0, ReaderActivity.ACTION_EXPORT, Export.Format.POWERPOINT.name) }
            row("Export to Rich Text", R.drawable.ic_doc) { pick(0, ReaderActivity.ACTION_EXPORT, Export.Format.RTF.name) }
        }
        p.section("Protect and optimise").apply {
            row("Protect with password", R.drawable.ic_lock) { pick(0, ReaderActivity.ACTION_PROTECT) }
            row("Remove password", R.drawable.ic_unlock) { pick(0, ReaderActivity.ACTION_UNPROTECT) }
            row("Compress", R.drawable.ic_compress) { pick(0, ReaderActivity.ACTION_COMPRESS) }
            row("Make text searchable (OCR)", R.drawable.ic_ocr) { pick(0, ReaderActivity.ACTION_OCR) }
            row("Print", R.drawable.ic_print) { pick(0, ReaderActivity.ACTION_PRINT) }
        }
        return p
    }

    // ------------------------------------------------------------------ Settings

    private fun settingsPage(): LargeTitlePage {
        val p = LargeTitlePage(this, "Settings")
        val ctx = this
        p.section("You", "Shown as the author of your comments.").apply {
            row("Name", R.drawable.ic_person, value = prefs.author.ifEmpty { "Not set" }) { r ->
                lifecycleScope.launch {
                    val v = Dialogs.text(ctx, "Your name", "Name", prefs.author, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) ?: return@launch
                    prefs.author = v; r.setValue(prefs.author.ifEmpty { "Not set" })
                }
            }
        }
        p.section("Display").apply {
            val modes = listOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
            val seg = Kit.segmented(ctx, listOf("System", "Light", "Dark"), modes.indexOf(prefs.nightMode).coerceAtLeast(0))
            seg.setOnCheckedChangeListener { g, _ ->
                val m = modes[g.selectedIndex().coerceAtLeast(0)]
                if (m != prefs.nightMode) { prefs.nightMode = m; AppCompatDelegate.setDefaultNightMode(m) }
            }
            custom(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Kit.dpi(ctx, 16), Kit.dpi(ctx, 12), Kit.dpi(ctx, 16), Kit.dpi(ctx, 12))
                addView(Kit.text(ctx, "Theme", 16f))
                addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Kit.dpi(ctx, 12) })
            })
            toggle("Dark pages when reading", prefs.darkPages, R.drawable.ic_moon, subtitle = "Shows pages as light text on black") { prefs.darkPages = it }
            toggle("Keep the screen on while reading", prefs.keepScreenOn, R.drawable.ic_sun) { prefs.keepScreenOn = it }
        }
        p.section("Scanner").apply {
            toggle("Auto-capture", prefs.autoCapture, R.drawable.ic_scan, subtitle = "Takes the picture when the page holds steady") { prefs.autoCapture = it }
            toggle("Review each page after capture", prefs.adjustAfterCapture, R.drawable.ic_crop, subtitle = "Crop, filters and page order right after each picture") { prefs.adjustAfterCapture = it }
            val filters = npdf.scan.Filter.values()
            row("Default filter", R.drawable.ic_filter, value = filters.firstOrNull { it.name == prefs.defaultFilter }?.label ?: "Auto colour") { r ->
                lifecycleScope.launch {
                    val i = Dialogs.choose(ctx, "Default filter", filters.map { it.label }, filters.indexOfFirst { it.name == prefs.defaultFilter }) ?: return@launch
                    prefs.defaultFilter = filters[i].name; r.setValue(filters[i].label)
                }
            }
            row("Default file name", R.drawable.ic_rename, value = "${prefs.scanName} + date and time") { r ->
                lifecycleScope.launch {
                    val v = Dialogs.text(ctx, "Default file name", "Name before the date", prefs.scanName) ?: return@launch
                    prefs.scanName = npdf.scan.TextInfo.safeName(v).ifBlank { "Scan" }; r.setValue("${prefs.scanName} + date and time")
                }
            }
            toggle("Recognise text when saving", prefs.ocrOnSave, R.drawable.ic_ocr, subtitle = "Makes scans searchable and copyable") { prefs.ocrOnSave = it }
            row("Text languages", R.drawable.ic_translate, value = prefs.ocrLanguages.joinToString(", ") { Ocr.LANGUAGES[it] ?: it }) { r ->
                lifecycleScope.launch {
                    val checks = Ocr.LANGUAGES.map { (k, v) -> k to Dialogs.check(ctx, v, k in prefs.ocrLanguages) }
                    if (!Dialogs.custom(ctx, "Text languages", Dialogs.column(ctx, *checks.map { it.second }.toTypedArray()), "Done")) return@launch
                    val sel = checks.filter { it.second.isChecked }.map { it.first }.ifEmpty { listOf("eng") }
                    prefs.ocrLanguages = sel; r.setValue(sel.joinToString(", ") { Ocr.LANGUAGES[it] ?: it })
                }
            }
            row("Save scans to", R.drawable.ic_folder, value = ScanStore.folderLabel(ctx)) { r ->
                lifecycleScope.launch {
                    val opts = listOf("Documents/N PDF (default)", "Choose a folder…")
                    when (Dialogs.choose(ctx, "Save scans to", opts, if (prefs.scanFolder == null) 0 else 1)) {
                        0 -> { prefs.scanFolder = null; r.setValue(ScanStore.folderLabel(ctx)) }
                        1 -> { folderRow = r; folderLauncher.launch(null) }
                    }
                }
            }
            toggle("Save original photos", prefs.saveOriginals, R.drawable.ic_gallery, subtitle = "Also keeps the camera's photos in Pictures/N PDF/Originals") { prefs.saveOriginals = it }
            toggle("Keep scans editable after saving", prefs.keepEditable, R.drawable.ic_edit_scan, subtitle = "Lets you modify a saved scan later (Modify scan)") { prefs.keepEditable = it }
            val copies = row("Editable copies of saved scans", R.drawable.ic_storage, value = "Checking…") { r ->
                lifecycleScope.launch {
                    val (n, bytes) = withContext(Dispatchers.IO) { ScanSession.editableCopies(ctx) }
                    if (n == 0) { Ui.toast(ctx, "No editable copies are kept"); return@launch }
                    if (Dialogs.confirm(ctx, "Remove editable copies?", "Frees ${Formatter.formatShortFileSize(ctx, bytes)}. Your saved PDFs are not touched, but those $n scan(s) can no longer be modified.", "Remove")) {
                        withContext(Dispatchers.IO) { ScanSession.removeEditableCopies(ctx) }
                        r.setValue("None kept")
                    }
                }
            }
            lifecycleScope.launch {
                val (n, bytes) = withContext(Dispatchers.IO) { ScanSession.editableCopies(ctx) }
                copies.setValue(if (n == 0) "None kept" else "$n scan(s), ${Formatter.formatShortFileSize(ctx, bytes)}")
            }
        }
        p.section("Privacy", "N PDF has no internet permission: Android blocks every connection it could try. There is no account, no advertising and no tracking, and cloud backup is off.").apply {
            row("Internet access", R.drawable.ic_privacy, value = "None")
            row("Clear the recent files list", R.drawable.ic_history) {
                lifecycleScope.launch {
                    if (Dialogs.confirm(ctx, "Clear recent files?", "Only the list is cleared; your files are not touched.", "Clear")) { prefs.clearRecents(); showRecents(); Ui.toast(ctx, "Recent list cleared") }
                }
            }
        }
        val version = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        p.section("About", "Signatures placed with N PDF are pictures of a signature. They are not certificate-based digital signatures and do not protect a document against later changes.").apply {
            row("Version", R.drawable.ic_info, value = version)
            row("Licences", R.drawable.ic_license) { Dialogs.info(ctx, "Licences", LICENCES) }
        }
        return p
    }

    private var folderRow: Kit.Row? = null
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.scanFolder = uri.toString()
            folderRow?.setValue(ScanStore.folderLabel(this))
        }
    }

    private val LICENCES = """
        N PDF is free software under the GNU Affero General Public License v3. Its source code is available from github.com/nkpatel5369-web/Nayanpdf

        MuPDF (PDF engine) - GNU AGPL v3, Artifex Software.
        Tesseract OCR and its English, Hindi and Gujarati language data - Apache License 2.0.
        Leptonica, libjpeg, libpng (used by Tesseract) - BSD-style, IJG and libpng licences.
        ZXing core (QR codes) - Apache License 2.0.
        AndroidX, CameraX and Material Components - Apache License 2.0.
        Material Symbols icons - Apache License 2.0, Google.
        Kotlin and kotlinx.coroutines - Apache License 2.0.
    """.trimIndent()
}
