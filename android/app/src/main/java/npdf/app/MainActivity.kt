package npdf.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import npdf.app.Dialogs.selectedIndex
import npdf.engine.Export
import java.io.File

/** Home: Files (scan, open, recent), Tools and Settings, in a tab bar. */
class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_SCAN = "npdf.action.SCAN"
        const val ACTION_OPEN = "npdf.action.OPEN"
        const val ACTION_PHOTOS = "npdf.action.PHOTOS"
    }

    private val prefs by lazy { Prefs(this) }
    private lateinit var tabs: Ios.TabBar
    private lateinit var pages: FrameLayout
    private lateinit var files: LargeTitlePage
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ios.color(this@MainActivity, R.color.bg)) }
        pages = FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        tabs = Ios.TabBar(this)
        root.addView(tabs)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(tabs) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(b.left, 0, b.right, b.bottom); insets
        }
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

    private fun tile(id: Int, label: String, icon: Int, prominent: Boolean, onClick: () -> Unit): View {
        val ctx = this
        return LinearLayout(ctx).apply {
            this.id = id
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = Ios.dpi(ctx, 96)
            setPadding(Ios.dpi(ctx, 6), Ios.dpi(ctx, 12), Ios.dpi(ctx, 6), Ios.dpi(ctx, 10))
            background = android.graphics.drawable.StateListDrawable().apply {
                val base = Ios.color(ctx, if (prominent) R.color.tint else R.color.bg_cell)
                addState(intArrayOf(android.R.attr.state_pressed), Ios.rounded(ctx, if (prominent) (base and 0x00FFFFFF) or 0xCC000000.toInt() else Ios.color(ctx, R.color.bg_cell_pressed), 16f))
                addState(intArrayOf(), Ios.rounded(ctx, base, 16f))
            }
            isClickable = true; isFocusable = true
            contentDescription = label
            val fg = Ios.color(ctx, if (prominent) R.color.on_tint else R.color.tint)
            addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(fg) }, LinearLayout.LayoutParams(Ios.dpi(ctx, 30), Ios.dpi(ctx, 30)))
            addView(Ios.text(ctx, label, 14f, 600).apply {
                setTextColor(if (prominent) fg else Ios.color(ctx, R.color.label)); gravity = Gravity.CENTER; setPadding(0, Ios.dpi(ctx, 8), 0, 0); maxLines = 2
            })
            setOnClickListener { Ios.tick(it); onClick() }
        }
    }

    private fun filesPage(): LargeTitlePage {
        val p = LargeTitlePage(this, "N PDF")
        val ctx = this
        val (search, _) = Ios.searchField(this, "Search recent files") { q -> filter = q.trim(); showRecents() }
        p.add(search, 16)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ios.dpi(ctx, 16), Ios.dpi(ctx, 16), Ios.dpi(ctx, 16), 0) }
        fun lp(first: Boolean) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (!first) marginStart = Ios.dpi(ctx, 10) }
        row.addView(tile(R.id.scanBtn, "Scan", R.drawable.ic_scan, true) { startActivity(Intent(this, ScanActivity::class.java)) }, lp(true))
        row.addView(tile(R.id.openBtn, "Open PDF", R.drawable.ic_open, false) { pick(0, null) }, lp(false))
        row.addView(tile(R.id.photosBtn, "Photos to PDF", R.drawable.ic_gallery, false) { pickPhotos() }, lp(false))
        p.content.addView(row)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ios.dpi(ctx, 20), Ios.dpi(ctx, 24), Ios.dpi(ctx, 16), Ios.dpi(ctx, 8))
        }
        header.addView(Ios.text(this, "Recent", 22f, 700), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sort = Ios.segmented(this, listOf("Date", "Name"), if (prefs.recentSort == "name") 1 else 0)
        sort.setOnCheckedChangeListener { g, _ -> prefs.recentSort = if (g.selectedIndex() == 1) "name" else "date"; showRecents() }
        header.addView(sort, LinearLayout.LayoutParams(Ios.dpi(ctx, 140), ViewGroup.LayoutParams.WRAP_CONTENT))
        p.content.addView(header)
        recentHeader = header
        val box = LinearLayout(this).apply {
            id = R.id.recentList
            orientation = LinearLayout.VERTICAL
            background = Ios.rounded(ctx, Ios.color(ctx, R.color.bg_cell), 12f)
            clipToOutline = true
        }
        p.content.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ios.dpi(ctx, 16), 0, Ios.dpi(ctx, 16), 0)
        })
        recentBox = box
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ios.dpi(ctx, 32), Ios.dpi(ctx, 36), Ios.dpi(ctx, 32), Ios.dpi(ctx, 24))
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_doc); imageTintList = android.content.res.ColorStateList.valueOf(Ios.color(ctx, R.color.label3)) },
                LinearLayout.LayoutParams(Ios.dpi(ctx, 52), Ios.dpi(ctx, 52)))
            addView(Ios.text(ctx, "No recent files", 20f, 600).apply { gravity = Gravity.CENTER; setPadding(0, Ios.dpi(ctx, 12), 0, Ios.dpi(ctx, 4)) })
            addView(Ios.text(ctx, "Scan a document or open a PDF. Everything stays on this phone.", 15f, 400, R.color.label2).apply { gravity = Gravity.CENTER })
        }
        p.content.addView(empty)
        emptyView = empty
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
        items.forEachIndexed { i, r ->
            if (i > 0) box.addView(Ios.hairline(this, insetStartDp = 76))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ios.cellBackground(ctx)
                setPadding(Ios.dpi(ctx, 14), Ios.dpi(ctx, 10), Ios.dpi(ctx, 12), Ios.dpi(ctx, 10))
                contentDescription = r.name
                isClickable = true
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ios.rounded(ctx, Ios.color(ctx, R.color.viewer_bg), 5f, Ios.color(ctx, R.color.separator), 0.5f)
                clipToOutline = true
                r.thumb?.let { t -> if (File(t).exists()) setImageBitmap(BitmapFactory.decodeFile(t)) }
                if (drawable == null) { setImageResource(R.drawable.ic_doc); scaleType = ImageView.ScaleType.CENTER_INSIDE; imageTintList = android.content.res.ColorStateList.valueOf(Ios.color(ctx, R.color.label3)) }
            }
            row.addView(thumb, LinearLayout.LayoutParams(Ios.dpi(ctx, 46), Ios.dpi(ctx, 58)).apply { marginEnd = Ios.dpi(ctx, 14) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ios.text(this, r.name, 16f, 500).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            texts.addView(Ios.text(this, "Page ${r.page} · " + DateUtils.getRelativeTimeSpanString(r.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS), 13f, 400, R.color.label2).apply { setPadding(0, Ios.dpi(ctx, 2), 0, 0) })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_chevron_right); imageTintList = android.content.res.ColorStateList.valueOf(Ios.color(ctx, R.color.label3)) },
                LinearLayout.LayoutParams(Ios.dpi(ctx, 18), Ios.dpi(ctx, 18)))
            row.setOnClickListener { openRecent(r) }
            row.setOnLongClickListener { Ios.tick(it); recentMenu(r); true }
            box.addView(row)
        }
    }

    private fun openRecent(r: Prefs.Recent) {
        if (canRead(r.uri)) openReader(r.uri) else {
            Ui.toast(this, "\"${r.name}\" is no longer available. Open it again from its folder.")
            prefs.forget(r.uri); showRecents()
        }
    }

    private fun recentMenu(r: Prefs.Recent) = lifecycleScope.launch {
        when (Dialogs.choose(this@MainActivity, r.name, listOf("Open", "Share", "Remove from Recent"))) {
            0 -> openRecent(r)
            1 -> if (canRead(r.uri)) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${r.name}"))
            2 -> { prefs.forget(r.uri); showRecents() }
        }
    }

    private fun canRead(uri: Uri): Boolean = try {
        contentResolver.openFileDescriptor(uri, "r")?.close(); true
    } catch (e: Exception) { false }

    // ------------------------------------------------------------------ Tools

    private fun toolsPage(): LargeTitlePage {
        val p = LargeTitlePage(this, "Tools")
        p.section("Create").apply {
            row("Scan a document", R.drawable.ic_scan, R.color.tint) { startActivity(Intent(this@MainActivity, ScanActivity::class.java)) }
            row("Photos to PDF", R.drawable.ic_gallery, R.color.orange) { pickPhotos() }
            row("Combine files", R.drawable.ic_merge, R.color.indigo) { startActivity(Intent(this@MainActivity, MergeActivity::class.java)) }
        }
        p.section("Edit and sign").apply {
            row("Edit text and images", R.drawable.ic_edit_text, R.color.blue) { pick(R.id.mode_edit, null) }
            row("Comment", R.drawable.ic_comment, R.color.yellow) { pick(R.id.mode_comment, null) }
            row("Fill & Sign", R.drawable.ic_sign, R.color.purple) { pick(R.id.mode_sign, null) }
            row("Redact", R.drawable.ic_redact, R.color.gray) { pick(R.id.mode_redact, null) }
        }
        p.section("Pages").apply {
            row("Organize pages", R.drawable.ic_pages, R.color.teal) { pick(0, ReaderActivity.ACTION_ORGANIZE) }
            row("Split", R.drawable.ic_split, R.color.teal) { pick(0, ReaderActivity.ACTION_SPLIT) }
        }
        p.section("Convert", "Converted on this phone. Text and tables are rebuilt from the PDF, so check the layout of complex pages.").apply {
            row("Export to Word", R.drawable.ic_fmt_doc, R.color.blue) { pick(0, ReaderActivity.ACTION_EXPORT, Export.Format.WORD.name) }
            row("Export to Excel", R.drawable.ic_fmt_sheet, R.color.green) { pick(0, ReaderActivity.ACTION_EXPORT, Export.Format.EXCEL.name) }
            row("Export to PowerPoint", R.drawable.ic_fmt_slides, R.color.orange) { pick(0, ReaderActivity.ACTION_EXPORT, Export.Format.POWERPOINT.name) }
            row("Export to Rich Text", R.drawable.ic_doc, R.color.gray) { pick(0, ReaderActivity.ACTION_EXPORT, Export.Format.RTF.name) }
        }
        p.section("Protect and optimise").apply {
            row("Protect with password", R.drawable.ic_lock, R.color.red) { pick(0, ReaderActivity.ACTION_PROTECT) }
            row("Remove password", R.drawable.ic_unlock, R.color.gray) { pick(0, ReaderActivity.ACTION_UNPROTECT) }
            row("Compress", R.drawable.ic_compress, R.color.green) { pick(0, ReaderActivity.ACTION_COMPRESS) }
            row("Make text searchable (OCR)", R.drawable.ic_ocr, R.color.indigo) { pick(0, ReaderActivity.ACTION_OCR) }
        }
        return p
    }

    // ------------------------------------------------------------------ Settings

    private fun settingsPage(): LargeTitlePage {
        val p = LargeTitlePage(this, "Settings")
        val ctx = this
        p.section("You", "Shown as the author of your comments.").apply {
            row("Name", R.drawable.ic_contact, R.color.blue, value = prefs.author.ifEmpty { "Not set" }) { r ->
                lifecycleScope.launch {
                    val v = Dialogs.text(ctx, "Your name", "Name", prefs.author, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) ?: return@launch
                    prefs.author = v; r.setValue(prefs.author.ifEmpty { "Not set" })
                }
            }
        }
        p.section("Appearance").apply {
            val modes = listOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
            val seg = Ios.segmented(ctx, listOf("Automatic", "Light", "Dark"), modes.indexOf(prefs.nightMode).coerceAtLeast(0))
            seg.setOnCheckedChangeListener { g, _ ->
                val m = modes[g.selectedIndex().coerceAtLeast(0)]
                if (m != prefs.nightMode) { prefs.nightMode = m; AppCompatDelegate.setDefaultNightMode(m) }
            }
            custom(FrameLayout(ctx).apply { setPadding(Ios.dpi(ctx, 16), Ios.dpi(ctx, 10), Ios.dpi(ctx, 16), Ios.dpi(ctx, 10)); addView(seg) })
            toggle("Dark pages when reading", prefs.darkPages, R.drawable.ic_moon, R.color.indigo) { prefs.darkPages = it }
            toggle("Keep the screen on while reading", prefs.keepScreenOn, R.drawable.ic_sun, R.color.orange) { prefs.keepScreenOn = it }
        }
        p.section("Scanner", "Scans are saved on this phone in Documents/N PDF unless you choose another folder.").apply {
            toggle("Auto-capture", prefs.autoCapture, R.drawable.ic_scan, R.color.tint) { prefs.autoCapture = it }
            toggle("Adjust borders after each capture", prefs.adjustAfterCapture, R.drawable.ic_crop, R.color.teal) { prefs.adjustAfterCapture = it }
            val filters = npdf.scan.Filter.values()
            row("Default filter", R.drawable.ic_filter, R.color.purple, value = filters.firstOrNull { it.name == prefs.defaultFilter }?.label ?: "Auto colour") { r ->
                lifecycleScope.launch {
                    val i = Dialogs.choose(ctx, "Default filter", filters.map { it.label }, filters.indexOfFirst { it.name == prefs.defaultFilter }) ?: return@launch
                    prefs.defaultFilter = filters[i].name; r.setValue(filters[i].label)
                }
            }
            toggle("Recognise text when saving", prefs.ocrOnSave, R.drawable.ic_ocr, R.color.indigo, subtitle = "Makes scans searchable and copyable") { prefs.ocrOnSave = it }
            row("Text languages", R.drawable.ic_text, R.color.blue, value = prefs.ocrLanguages.joinToString(", ") { Ocr.LANGUAGES[it] ?: it }) { r ->
                lifecycleScope.launch {
                    val checks = Ocr.LANGUAGES.map { (k, v) -> k to Dialogs.check(ctx, v, k in prefs.ocrLanguages) }
                    if (!Dialogs.custom(ctx, "Text languages", Dialogs.column(ctx, *checks.map { it.second }.toTypedArray()), "Done")) return@launch
                    val sel = checks.filter { it.second.isChecked }.map { it.first }.ifEmpty { listOf("eng") }
                    prefs.ocrLanguages = sel; r.setValue(sel.joinToString(", ") { Ocr.LANGUAGES[it] ?: it })
                }
            }
            row("Save scans to", R.drawable.ic_folder, R.color.blue, value = ScanStore.folderLabel(ctx)) { r ->
                lifecycleScope.launch {
                    val opts = listOf("Documents/N PDF (default)", "Choose a folder…")
                    when (Dialogs.choose(ctx, "Save scans to", opts, if (prefs.scanFolder == null) 0 else -1)) {
                        0 -> { prefs.scanFolder = null; r.setValue(ScanStore.folderLabel(ctx)) }
                        1 -> { folderRow = r; folderLauncher.launch(null) }
                    }
                }
            }
        }
        p.section("Privacy", "N PDF has no internet permission: Android blocks every connection it could try. There is no account, no advertising and no tracking, and cloud backup is off.").apply {
            row("Internet access", R.drawable.ic_lock, R.color.green, value = "None", chevron = false)
            row("Clear the recent files list", destructive = true) {
                lifecycleScope.launch {
                    if (Dialogs.confirm(ctx, "Clear recent files?", "Only the list is cleared; your files are not touched.", "Clear")) { prefs.clearRecents(); showRecents(); Ui.toast(ctx, "Recent list cleared") }
                }
            }
        }
        val version = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        p.section("About", "Signatures placed with N PDF are pictures of a signature. They are not certificate-based digital signatures and do not protect a document against later changes.").apply {
            row("Version", R.drawable.ic_info, R.color.gray, value = version, chevron = false)
            row("Licences", R.drawable.ic_doc, R.color.gray) { Dialogs.info(ctx, "Licences", LICENCES) }
        }
        return p
    }

    private var folderRow: Ios.Row? = null
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
        Kotlin and kotlinx.coroutines - Apache License 2.0.
        Inter typeface - SIL Open Font License 1.1, The Inter Project Authors.
    """.trimIndent()

    @Suppress("unused")
    private fun roundedThumb(): GradientDrawable = GradientDrawable()

    @Suppress("unused")
    private val sdk = Build.VERSION.SDK_INT
}
