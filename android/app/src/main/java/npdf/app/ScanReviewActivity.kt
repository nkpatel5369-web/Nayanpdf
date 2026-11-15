package npdf.app

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import npdf.app.Dialogs.selectedIndex
import npdf.scan.Cleanup
import npdf.scan.Filter
import npdf.scan.Pt
import npdf.scan.TextInfo
import npdf.scan.TextLine
import java.io.File
import java.time.LocalDateTime

/** Review and edit the pages of a scan, then save it on the phone. */
class ScanReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION = "session"
        const val EXTRA_CROP = "crop"
        const val EXTRA_RETURN = "return"
        const val EXTRA_IMPORT = "import"
    }

    private val prefs by lazy { Prefs(this) }
    private lateinit var session: ScanSession
    private lateinit var pager: RecyclerView
    private lateinit var adapter: PageAdapter
    private lateinit var indicator: TextView
    private lateinit var title: TextView
    private lateinit var panel: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var strip: RecyclerView
    private lateinit var stripAdapter: StripAdapter
    private val tools = HashMap<String, MaterialButton>()
    private var current = 0
    private val cache = object : LruCache<String, Bitmap>(4) {}
    private var overlayBack: (() -> Unit)? = null
    private var lines: List<TextLine>? = null
    private var retakeAt = -1

    /** For tests: pages in the scan being reviewed. */
    val pageCount: Int get() = if (::session.isInitialized) session.pages.size else 0
    /** For tests: where the scan being reviewed is kept. */
    val sessionDir: File? get() = if (::session.isInitialized) session.dir else null

    private val addLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val before = session.pages.map { it.id }.toSet()
        ScanSession.load(session.dir)?.let { session = it }
        if (retakeAt >= 0) {
            val added = session.pages.filter { it.id !in before }
            if (added.isNotEmpty()) {
                session.pages.removeAll(added)
                session.pages.removeAt(retakeAt.coerceAtMost(session.pages.size - 1))
                session.pages.addAll(retakeAt.coerceAtMost(session.pages.size), added)
                session.save()
            }
            retakeAt = -1
        }
        refresh()
    }

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.scanFolder = uri.toString()
            pendingSave?.invoke()
        }
        pendingSave = null
    }
    private var pendingSave: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { overlayBack?.invoke() ?: close() }
        })
        val dir = intent.getStringExtra(EXTRA_SESSION)
        val imports = importedUris()
        if (dir != null) {
            session = ScanSession.load(File(dir)) ?: run { finish(); return }
            refresh()
            intent.getStringExtra(EXTRA_CROP)?.let { id -> pager.post { session.pages.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { current = it; crop() } } }
        } else if (imports.isNotEmpty()) {
            session = ScanSession.create(this)
            importPhotos(imports)
        } else { finish(); return }
    }

    @Suppress("DEPRECATION")
    private fun importedUris(): List<Uri> {
        intent.getParcelableArrayListExtra<Uri>(EXTRA_IMPORT)?.let { return it }
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi() {
        val ctx = this
        // The bar colour shows under the status and navigation bars; the pages sit on the viewer surface.
        val root = FrameLayout(this).apply { setBackgroundColor(Kit.color(ctx, R.color.bar)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Kit.color(ctx, R.color.viewer_bg)) }
        root.addView(column)
        // Top app bar: close, the scan's name (tap to rename) and Save PDF.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Kit.color(ctx, R.color.bar)); minimumHeight = Kit.dpi(ctx, 64)
            setPadding(Kit.dpi(ctx, 4), 0, Kit.dpi(ctx, 12), 0)
        }
        bar.addView(Kit.iconButton(this, R.drawable.ic_close, "Close", Kit.color(ctx, R.color.label)) { close() })
        title = Kit.text(this, "Scan", 20f, 400).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            isClickable = true; contentDescription = "Rename"
            setPadding(Kit.dpi(ctx, 4), 0, 0, 0)
            setOnClickListener { rename() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Kit.iconButton(this, R.drawable.ic_rename, "Rename") { rename() })
        bar.addView(Kit.textButton(this, "Save PDF", bold = true) { save() })
        column.addView(bar)

        pager = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
            PagerSnapHelper().attachToRecyclerView(this)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_IDLE) {
                        val p = (layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()
                        if (p >= 0 && p != current) { current = p; onPageChanged() }
                    }
                }
            })
        }
        adapter = PageAdapter()
        pager.adapter = adapter
        column.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        indicator = Kit.text(this, "", 12f, 500, R.color.label2).apply { gravity = Gravity.CENTER; setPadding(0, Kit.dpi(ctx, 4), 0, Kit.dpi(ctx, 2)) }
        column.addView(indicator)
        // Page thumbnails: tap to go to a page, hold and drag to move it, + to scan more.
        strip = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
            setPadding(Kit.dpi(ctx, 12), Kit.dpi(ctx, 6), Kit.dpi(ctx, 12), Kit.dpi(ctx, 8))
            clipToPadding = false
            contentDescription = "Pages"
        }
        stripAdapter = StripAdapter()
        strip.adapter = stripAdapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.START or ItemTouchHelper.END, 0) {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (vh.bindingAdapterPosition >= session.pages.size) 0 else super.getMovementFlags(rv, vh)
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
                val from = a.bindingAdapterPosition; val to = b.bindingAdapterPosition
                if (to >= session.pages.size || from >= session.pages.size) return false
                val moving = session.pages[from]
                session.pages.add(to, session.pages.removeAt(from))
                stripAdapter.notifyItemMoved(from, to)
                if (current == from) current = to
                session.pages.indexOf(moving)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh); session.save(); adapter.notifyDataSetChanged(); stripAdapter.notifyDataSetChanged()
                pager.scrollToPosition(current); onPageChanged()
            }
        }).attachToRecyclerView(strip)
        column.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Kit.dpi(ctx, 92)))
        panel = FrameLayout(this).apply { setBackgroundColor(Kit.color(ctx, R.color.bar)) }
        column.addView(panel)
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(Kit.color(ctx, R.color.bar)); tag = Kit.ANCHOR }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Kit.dpi(ctx, 8), Kit.dpi(ctx, 4), Kit.dpi(ctx, 8), Kit.dpi(ctx, 4)) }
        fun tool(label: String, icon: Int, run: () -> Unit) = row.addView(Ui.toolButton(this, label, icon) { run() }.also { tools[label] = it })
        tool("Keep scanning", R.drawable.ic_keep_scanning) { addPages() }
        tool("Retake", R.drawable.ic_retake) { retake() }
        tool("Filters", R.drawable.ic_filter) { filters() }
        tool("Crop", R.drawable.ic_crop) { crop() }
        tool("Rotate", R.drawable.ic_rotate) { rotate() }
        tool("Adjust", R.drawable.ic_adjust) { adjust() }
        tool("Clean up", R.drawable.ic_eraser) { paint(true) }
        tool("Markup", R.drawable.ic_draw) { paint(false) }
        tool("Resize", R.drawable.ic_resize) { resize() }
        tool("Arrange", R.drawable.ic_reorder) { reorder() }
        tool("Delete", R.drawable.ic_delete) { deletePage() }
        scroller.addView(row)
        column.addView(scroller)
        overlay = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Kit.color(ctx, R.color.bg)); isClickable = true }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        Ui.fitSystemBars(root)
    }

    /** The page strip: a thumbnail per page (the current one outlined) and a + tile that scans more. */
    private inner class StripAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = session.pages.size + 1
        override fun getItemViewType(position: Int) = if (position < session.pages.size) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val box = FrameLayout(ctx).apply { setPadding(Kit.dpi(ctx, 4), 0, Kit.dpi(ctx, 4), 0) }
            if (viewType == 1) {
                box.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    background = Kit.rounded(ctx, Kit.color(ctx, R.color.tint_soft), 12f)
                    addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_add); imageTintList = android.content.res.ColorStateList.valueOf(Kit.color(ctx, R.color.on_tint_soft)) },
                        LinearLayout.LayoutParams(Kit.dpi(ctx, 24), Kit.dpi(ctx, 24)))
                    contentDescription = "Add pages"; isClickable = true
                    setOnClickListener { addPages() }
                }, FrameLayout.LayoutParams(Kit.dpi(ctx, 56), Kit.dpi(ctx, 74)))
            } else {
                box.addView(ImageView(ctx).apply { tag = "img"; scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true },
                    FrameLayout.LayoutParams(Kit.dpi(ctx, 56), Kit.dpi(ctx, 74)))
                box.addView(Kit.text(ctx, "", 11f, 500).apply {
                    tag = "num"; gravity = Gravity.CENTER; minWidth = Kit.dpi(ctx, 20)
                    setPadding(Kit.dpi(ctx, 5), Kit.dpi(ctx, 1), Kit.dpi(ctx, 5), Kit.dpi(ctx, 1))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Kit.dpi(ctx, 4) })
            }
            return object : RecyclerView.ViewHolder(box) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            if (position >= session.pages.size) return
            val ctx = h.itemView.context
            val p = session.pages[position]
            val on = position == current
            val img = h.itemView.findViewWithTag<ImageView>("img")
            img.background = Kit.rounded(ctx, Kit.color(ctx, R.color.bg_cell), 8f, Kit.color(ctx, if (on) R.color.tint else R.color.separator), if (on) 3f else 1f)
            val pad = Kit.dpi(ctx, if (on) 3 else 1); img.setPadding(pad, pad, pad, pad)
            h.itemView.findViewWithTag<TextView>("num").apply {
                text = (position + 1).toString()
                setTextColor(Kit.color(ctx, if (on) R.color.on_tint else R.color.label))
                background = Kit.rounded(ctx, Kit.color(ctx, if (on) R.color.tint else R.color.bg_elevated), 8f)
            }
            h.itemView.contentDescription = "Page ${position + 1}" + if (on) ", shown" else ""
            h.itemView.setOnClickListener { current = h.bindingAdapterPosition.coerceAtLeast(0); pager.scrollToPosition(current); onPageChanged() }
            img.setImageDrawable(null)
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.Default) { runCatching { ScanProcessor.thumb(session, p) }.getOrNull() }
                if (h.bindingAdapterPosition == position) bmp?.let { img.setImageBitmap(it) }
            }
        }
    }

    private fun page() = session.pages.getOrNull(current)

    private fun refresh() {
        if (session.pages.isEmpty()) { session.discard(); finish(); return }
        current = current.coerceIn(0, session.pages.size - 1)
        title.text = session.name ?: defaultName()
        adapter.notifyDataSetChanged()
        stripAdapter.notifyDataSetChanged()
        pager.scrollToPosition(current)
        onPageChanged()
        // Filters open under the page from the start, as the first thing most scans need.
        if (panel.childCount == 0 && overlay.visibility != View.VISIBLE) filters()
    }

    private fun onPageChanged() {
        indicator.text = "Page ${current + 1} of ${session.pages.size}"
        stripAdapter.notifyDataSetChanged()
        strip.scrollToPosition(current)
        if (panel.childCount > 0 && panel.tag == "filters") filters(force = true)
        if (panel.childCount > 0 && panel.tag == "adjust") adjust()
    }

    private fun defaultName() = TextInfo.defaultName(if (session.kind == ScanKind.DOCUMENT) prefs.scanName else session.kind.baseName, LocalDateTime.now())

    /** Re-renders [p] after a change and shows it. */
    private fun changed(p: ScanPage) {
        session.save()
        val i = session.pages.indexOf(p)
        if (i >= 0) { adapter.notifyItemChanged(i); stripAdapter.notifyItemChanged(i) }
    }

    // ------------------------------------------------------------------ pages list

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.H>() {
        inner class H(val box: FrameLayout, val img: ImageView, val spin: ProgressBar) : RecyclerView.ViewHolder(box) { var job: Job? = null }

        override fun getItemCount() = session.pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val ctx = parent.context
            // Full width of the pager (a horizontal list measures MATCH_PARENT children at its own width).
            val box = FrameLayout(ctx).apply { layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(Kit.dpi(ctx, 18), Kit.dpi(ctx, 18), Kit.dpi(ctx, 18), Kit.dpi(ctx, 18)); elevation = Kit.dp(ctx, 4f) }
            box.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val spin = ProgressBar(ctx)
            box.addView(spin, FrameLayout.LayoutParams(Kit.dpi(ctx, 36), Kit.dpi(ctx, 36), Gravity.CENTER))
            return H(box, img, spin)
        }

        override fun onBindViewHolder(h: H, position: Int) {
            val p = session.pages[position]
            h.img.contentDescription = "Page ${position + 1}"
            val key = p.id + p.key()
            cache.get(key)?.let { h.img.setImageBitmap(it); h.spin.visibility = View.GONE; return }
            h.img.setImageDrawable(null); h.spin.visibility = View.VISIBLE
            h.job?.cancel()
            h.job = lifecycleScope.launch {
                val bmp = withContext(Dispatchers.Default) { runCatching { ScanProcessor.display(session, p) }.getOrNull() }
                if (bmp != null) { cache.put(key, bmp); h.img.setImageBitmap(bmp) }
                h.spin.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------------ tools

    private fun addPages() {
        session.save()
        addLauncher.launch(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_SESSION, session.dir.absolutePath)
            .putExtra(ScanActivity.EXTRA_APPEND, true).putExtra(ScanActivity.EXTRA_KIND, session.kind.name))
    }

    private fun retake() {
        val p = page() ?: return
        retakeAt = session.pages.indexOf(p)
        addLauncher.launch(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_SESSION, session.dir.absolutePath)
            .putExtra(ScanActivity.EXTRA_APPEND, true).putExtra(ScanActivity.EXTRA_KIND, p.kind.name))
    }

    private fun rotate() {
        val p = page() ?: return
        p.rot = (p.rot + 1) % 4
        // Turn the drawings with the page: (x, y) -> (1 - y, x) for a quarter turn clockwise.
        fun r(pt: Pt) = Pt(1f - pt.y, pt.x)
        val turned = p.edits.map { e ->
            when (e) {
                is Edit.Erase -> Edit.Erase(e.pts.map(::r), e.radius, e.color)
                is Edit.Ink -> Edit.Ink(e.pts.map(::r), e.color, e.width)
                is Edit.Shape -> Edit.Shape(e.type, r(e.a), r(e.b), e.color, e.width, e.fill)
                else -> e
            }
        }
        p.edits.clear(); p.edits.addAll(turned)
        changed(p)
    }

    private fun showPanel(tag: String, v: View) {
        panel.removeAllViews()
        panel.tag = tag
        panel.addView(v)
        tools.forEach { (k, b) -> Ui.markSelected(b, (k == "Filters" && tag == "filters") || (k == "Adjust" && tag == "adjust")) }
    }

    private fun closePanel() { panel.removeAllViews(); panel.tag = null; tools.values.forEach { Ui.markSelected(it, false) } }

    private fun filters(force: Boolean = false) {
        val p = page() ?: return
        if (!force && panel.tag == "filters" && panel.childCount > 0) { closePanel(); return }
        val ctx = this
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Kit.dpi(ctx, 12), Kit.dpi(ctx, 8), Kit.dpi(ctx, 12), Kit.dpi(ctx, 4)) }
        for (f in Filter.values()) {
            val on = f == p.filter
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(Kit.dpi(ctx, 4), 0, Kit.dpi(ctx, 4), 0)
                isClickable = true; contentDescription = f.label + if (on) ", chosen" else ""
                background = Kit.cellBackground(ctx, FloatArray(8) { Kit.dp(ctx, 12f) })
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Kit.rounded(ctx, Kit.color(ctx, R.color.bg_cell), 12f, Kit.color(ctx, if (on) R.color.tint else R.color.separator), if (on) 3f else 1f)
                clipToOutline = true
                val pad = Kit.dpi(ctx, if (on) 3 else 1); setPadding(pad, pad, pad, pad)
            }
            item.addView(img, LinearLayout.LayoutParams(Kit.dpi(ctx, 64), Kit.dpi(ctx, 84)))
            item.addView(Kit.text(this, f.label, 12f, 500, if (on) R.color.tint else R.color.label2).apply { setPadding(0, Kit.dpi(ctx, 4), 0, 0) })
            item.setOnClickListener { Kit.tick(it); p.filter = f; changed(p); filters(force = true) }
            strip.addView(item)
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.Default) { runCatching { ScanProcessor.preview(session, p, f) }.getOrNull() }
                bmp?.let { img.setImageBitmap(it) }
            }
        }
        if (session.pages.size > 1) strip.addView(Kit.tonalButton(this, "Apply to all pages", R.drawable.ic_done_all) {
            session.pages.forEach { it.filter = p.filter }
            session.save(); adapter.notifyDataSetChanged(); stripAdapter.notifyDataSetChanged()
            Kit.banner(this, "${p.filter.label} applied to all ${session.pages.size} pages")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Kit.dpi(ctx, 8) })
        showPanel("filters", HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
    }

    private fun slider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit): View {
        val ctx = this
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Kit.dpi(ctx, 16), 0, Kit.dpi(ctx, 8), 0) }
        row.addView(Kit.text(this, label, 14f, 500), LinearLayout.LayoutParams(Kit.dpi(ctx, 96), ViewGroup.LayoutParams.WRAP_CONTENT))
        val sl = com.google.android.material.slider.Slider(this).apply {
            valueFrom = min; valueTo = max
            this.value = value.coerceIn(min, max)
            contentDescription = label
            setLabelFormatter { v -> "${(((v - min) / (max - min)) * 100).toInt()} %" }
            addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: com.google.android.material.slider.Slider) {}
                override fun onStopTrackingTouch(s: com.google.android.material.slider.Slider) = onChange(s.value)
            })
        }
        row.addView(sl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun adjust() {
        val p = page() ?: return
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Kit.dpi(context, 6), 0, 0) }
        col.addView(slider("Brightness", p.brightness, -80f, 80f) { p.brightness = it; changed(p) })
        col.addView(slider("Contrast", p.contrast, 0.5f, 2f) { p.contrast = it; changed(p) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, 0, Kit.dpi(context, 12), Kit.dpi(context, 4)) }
        buttons.addView(Kit.textButton(this, "Reset") { p.brightness = 0f; p.contrast = 1f; changed(p); adjust() })
        if (session.pages.size > 1) buttons.addView(Kit.textButton(this, "Apply to all pages") {
            session.pages.forEach { it.brightness = p.brightness; it.contrast = p.contrast }
            session.save(); adapter.notifyDataSetChanged(); stripAdapter.notifyDataSetChanged()
            Kit.banner(this, "Brightness and contrast applied to all pages")
        })
        col.addView(buttons)
        showPanel("adjust", col)
    }

    /** Page sizes for a scan: the scan keeps its proportions, centred on the chosen page. */
    private val sizes = npdf.engine.Pages.PageSize.values().toList()
    private fun sizeKey(z: npdf.engine.Pages.PageSize) = if (z == npdf.engine.Pages.PageSize.FIT) "AUTO" else z.name

    private fun resize() = lifecycleScope.launch {
        val p = page() ?: return@launch
        val ctx = this@ScanReviewActivity
        val cur = sizes.indexOfFirst { sizeKey(it) == p.size }.coerceAtLeast(0)
        val i = Dialogs.choose(ctx, "Page size", sizes.map { it.label }, cur) ?: return@launch
        val key = sizeKey(sizes[i])
        val label = sizes[i].label.substringBefore(" (")
        if (session.pages.size > 1 && Dialogs.confirm(ctx, "Use $label for every page?", "Or only for page ${current + 1}.", "All pages", "This page")) {
            session.pages.forEach { it.size = key }
        } else p.size = key
        session.save()
        Kit.banner(ctx, if (key == "AUTO") "Pages fit the scan" else "Page size: $label. The scan is centred on the page, not stretched.")
    }

    private fun deletePage() = lifecycleScope.launch {
        val p = page() ?: return@launch
        if (!Dialogs.confirm(this@ScanReviewActivity, "Delete this page?", "Page ${current + 1} is removed from the scan.", "Delete")) return@launch
        session.pages.remove(p)
        session.save()
        refresh()
    }

    private fun rename() = lifecycleScope.launch {
        val v = Dialogs.text(this@ScanReviewActivity, "Name", "Name", session.name ?: defaultName(), type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) ?: return@launch
        session.name = TextInfo.safeName(v).ifEmpty { null }
        session.save()
        title.text = session.name ?: defaultName()
    }

    // ------------------------------------------------------------------ overlays (crop, paint, reorder)

    private fun openOverlay(content: View, bottom: View?, onBack: () -> Unit) {
        overlay.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (bottom != null) col.addView(bottom)
        overlay.addView(col)
        overlay.visibility = View.VISIBLE
        overlayBack = onBack
    }

    private fun closeOverlay() {
        overlay.visibility = View.GONE
        overlay.removeAllViews()
        overlayBack = null
        if (intent.getBooleanExtra(EXTRA_RETURN, false)) finish()
    }

    private fun barButtons(vararg items: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Kit.color(context, R.color.bar)); tag = Kit.ANCHOR
        setPadding(Kit.dpi(context, 8), Kit.dpi(context, 4), Kit.dpi(context, 8), Kit.dpi(context, 4))
        items.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun crop() = lifecycleScope.launch {
        val p = page() ?: return@launch
        val partIndex = if (p.parts.size > 1) Dialogs.choose(this@ScanReviewActivity, "Crop which side?", listOf("Front", "Back")) ?: return@launch else 0
        val part = p.parts[partIndex]
        val photo = withContext(Dispatchers.Default) { ScanProcessor.photo(session, part) }
        val cv = CropView(this@ScanReviewActivity, photo, part.w, part.h, part.quad)
        cv.setBackgroundColor(Color.BLACK)
        cv.curved = part.quad?.bulge?.any { it != 0f } == true
        val straighten = Ui.toolButton(this@ScanReviewActivity, "Straighten", R.drawable.ic_book) { b -> cv.curved = !cv.curved; Ui.markSelected(b, cv.curved) }
        Ui.markSelected(straighten, cv.curved)
        val bottom = barButtons(
            Kit.textButton(this@ScanReviewActivity, "Cancel") { closeOverlay() },
            Ui.toolButton(this@ScanReviewActivity, "Auto-detect", R.drawable.ic_scan) {
                lifecycleScope.launch {
                    val q = withContext(Dispatchers.Default) {
                        val full = ScanProcessor.photo(session, part, ScanProcessor.PHOTO_MAX)
                        ScanProcessor.findOutline(full, p.kind)?.scaled(part.w.toFloat() / full.width)
                    }
                    if (q == null) Kit.banner(this@ScanReviewActivity, "No document outline found. Drag the corners.") else { cv.set(q); cv.curved = q.bulge.any { it != 0f } }
                }
            },
            Ui.toolButton(this@ScanReviewActivity, "No crop", R.drawable.ic_resize) { cv.set(null) },
            straighten,
            Kit.textButton(this@ScanReviewActivity, "Done", bold = true) {
                if (!cv.valid()) { Kit.banner(this@ScanReviewActivity, "The outline is too small or crossed. Adjust the corners."); return@textButton }
                val full = npdf.scan.Quad.full(part.w, part.h)
                part.quad = if (cv.quad.maxCornerDistance(full) < 1f && cv.quad.bulge.all { it == 0f }) null else cv.quad
                changed(p)
                closeOverlay()
            },
        )
        openOverlay(cv, bottom) { closeOverlay() }
    }

    private fun paint(cleanup: Boolean) = lifecycleScope.launch {
        val p = page() ?: return@launch
        val bmp = withContext(Dispatchers.Default) { ScanProcessor.display(session, p) }
        val pv = PaintView(this@ScanReviewActivity, bmp).apply { setBackgroundColor(Kit.color(context, R.color.viewer_bg)) }
        val ctx = this@ScanReviewActivity
        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Kit.color(ctx, R.color.bar)) }
        if (cleanup) {
            pv.tool = PaintView.Tool.ERASE
            val swatch = View(ctx).apply { background = Kit.rounded(ctx, Color.WHITE, 12f, Kit.color(ctx, R.color.separator), 1f); contentDescription = "Eraser colour" }
            val mode = Kit.segmented(ctx, listOf("Surroundings", "Colour"), 0)
            mode.setOnCheckedChangeListener { g, _ ->
                pv.eraseColor = if (g.selectedIndex() == 1) (pv.eraseColor ?: Color.WHITE) else null
                swatch.visibility = if (g.selectedIndex() == 1) View.VISIBLE else View.GONE
            }
            swatch.visibility = View.GONE
            pv.onPicked = { c -> pv.eraseColor = c; swatch.background = Kit.rounded(ctx, c, 12f, Kit.color(ctx, R.color.separator), 1f); pv.tool = PaintView.Tool.ERASE; Kit.banner(ctx, "Colour picked") }
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Kit.dpi(ctx, 16), Kit.dpi(ctx, 8), Kit.dpi(ctx, 16), 0) }
            top.addView(mode, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(swatch, LinearLayout.LayoutParams(Kit.dpi(ctx, 24), Kit.dpi(ctx, 24)).apply { marginStart = Kit.dpi(ctx, 10) })
            top.addView(Ui.toolButton(this@ScanReviewActivity, "Pick", R.drawable.ic_eyedropper) { pv.tool = PaintView.Tool.PICK; Kit.banner(ctx, "Tap the colour to use") })
            controls.addView(top)
            controls.addView(slider("Brush", 0.5f, 0f, 1f) { pv.size = 0.006f + it * 0.05f })
            pv.size = 0.006f + 0.5f * 0.05f
        } else {
            pv.tool = PaintView.Tool.INK
            val kinds = listOf("Pen" to PaintView.Tool.INK, "Highlighter" to PaintView.Tool.HIGHLIGHT, "Box" to PaintView.Tool.RECT,
                "Oval" to PaintView.Tool.ELLIPSE, "Line" to PaintView.Tool.LINE, "Arrow" to PaintView.Tool.ARROW)
            val chips = com.google.android.material.chip.ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true; isSingleLine = true }
            for ((label, t) in kinds) chips.addView(com.google.android.material.chip.Chip(ctx, null, com.google.android.material.R.attr.chipStyle).apply {
                text = label; isCheckable = true; isChecked = t == PaintView.Tool.INK; contentDescription = label
                setOnClickListener { pv.tool = t }
            })
            controls.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; setPadding(Kit.dpi(ctx, 12), Kit.dpi(ctx, 4), Kit.dpi(ctx, 12), 0); addView(chips) })
            val colours = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Kit.dpi(ctx, 16), Kit.dpi(ctx, 4), Kit.dpi(ctx, 16), 0) }
            val choices = listOf(Color.BLACK, 0xFFD93025.toInt(), 0xFF1A73E8.toInt(), 0xFF1E8E3E.toInt(), 0xFFF9AB00.toInt(), Color.WHITE)
            val names = listOf("Black", "Red", "Blue", "Green", "Yellow", "White")
            pv.color = choices[1]
            val swatches = ArrayList<View>()
            fun mark(sel: Int) = swatches.forEachIndexed { k, v ->
                v.background = Kit.rounded(ctx, choices[k], 16f, Kit.color(ctx, if (k == sel) R.color.tint else R.color.separator), if (k == sel) 3f else 1f)
            }
            choices.forEachIndexed { k, c ->
                colours.addView(View(ctx).apply {
                    contentDescription = names[k]; isClickable = true
                    setOnClickListener { Kit.tick(it); pv.color = c; mark(k) }
                }.also { swatches.add(it) }, LinearLayout.LayoutParams(Kit.dpi(ctx, 32), Kit.dpi(ctx, 32)).apply { marginEnd = Kit.dpi(ctx, 12) })
            }
            mark(1)
            val fill = com.google.android.material.chip.Chip(ctx, null, com.google.android.material.R.attr.chipStyle).apply {
                text = "Fill shapes"; isCheckable = true; setChipIconResource(R.drawable.ic_fill); isChipIconVisible = true
                setOnCheckedChangeListener { _, on -> pv.filled = on }
            }
            colours.addView(fill)
            controls.addView(colours)
            controls.addView(slider("Width", 0.3f, 0f, 1f) { pv.size = 0.004f + it * 0.04f })
            controls.addView(slider("Opacity", 1f, 0.1f, 1f) { pv.opacity = it })
            pv.size = 0.004f + 0.3f * 0.04f
        }
        val bottom = barButtons(
            Kit.textButton(ctx, "Cancel") { closeOverlay() },
            Ui.toolButton(this@ScanReviewActivity, "Undo", R.drawable.ic_undo) { pv.undo() },
            Ui.toolButton(this@ScanReviewActivity, "Redo", R.drawable.ic_redo) { pv.redo() },
            if (cleanup) Ui.toolButton(this@ScanReviewActivity, "Fingers", R.drawable.ic_wand) {
                lifecycleScope.launch {
                    val found = withContext(Dispatchers.Default) { Cleanup.fingerMask(ScanProcessor.toRaster(bmp)) != null }
                    if (found) { p.edits.add(Edit.Fingers); changed(p); Kit.banner(ctx, "Fingers removed"); closeOverlay() }
                    else Kit.banner(ctx, "No fingers found at the edges of this page.")
                }
            } else View(ctx),
            Kit.textButton(ctx, "Done", bold = true) {
                if (pv.edits.isNotEmpty()) { p.edits.addAll(pv.edits); changed(p) }
                closeOverlay()
            },
        )
        controls.addView(bottom)
        openOverlay(pv, controls) { closeOverlay() }
    }

    private fun reorder(preselect: String? = null) {
        val ctx = this
        val selected = HashSet<String>().apply { preselect?.let { add(it) } }
        val grid = RecyclerView(this).apply { layoutManager = GridLayoutManager(ctx, 3); setPadding(Kit.dpi(ctx, 8), Kit.dpi(ctx, 8), Kit.dpi(ctx, 8), Kit.dpi(ctx, 8)); clipToPadding = false }
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = session.pages.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val box = FrameLayout(ctx).apply { setPadding(Kit.dpi(ctx, 6), Kit.dpi(ctx, 6), Kit.dpi(ctx, 6), Kit.dpi(ctx, 6)) }
                val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; id = View.generateViewId(); tag = "img" }
                box.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Kit.dpi(ctx, 150)))
                box.addView(Kit.text(ctx, "", 13f, 600).apply { tag = "num"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = Kit.rounded(ctx, 0x99000000.toInt(), 10f); setPadding(Kit.dpi(ctx, 8), Kit.dpi(ctx, 2), Kit.dpi(ctx, 8), Kit.dpi(ctx, 2)) },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Kit.dpi(ctx, 6) })
                box.addView(ImageView(ctx).apply { tag = "check"; setImageResource(R.drawable.ic_select); imageTintList = android.content.res.ColorStateList.valueOf(Kit.color(ctx, R.color.tint)); background = Kit.rounded(ctx, Color.WHITE, 12f) },
                    FrameLayout.LayoutParams(Kit.dpi(ctx, 24), Kit.dpi(ctx, 24), Gravity.TOP or Gravity.END).apply { setMargins(0, Kit.dpi(ctx, 4), Kit.dpi(ctx, 4), 0) })
                return object : RecyclerView.ViewHolder(box) {}
            }
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val p = session.pages[position]
                val img = h.itemView.findViewWithTag<ImageView>("img")
                h.itemView.findViewWithTag<TextView>("num").text = (position + 1).toString()
                h.itemView.findViewWithTag<View>("check").visibility = if (p.id in selected) View.VISIBLE else View.GONE
                img.background = if (p.id in selected) Kit.rounded(ctx, Kit.color(ctx, R.color.tint_soft), 8f, Kit.color(ctx, R.color.tint), 2.5f) else null
                h.itemView.contentDescription = "Page ${position + 1}"
                h.itemView.setOnClickListener { if (!selected.remove(p.id)) selected.add(p.id); notifyItemChanged(h.bindingAdapterPosition) }
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.Default) { runCatching { ScanProcessor.thumb(session, p) }.getOrNull() }
                    if (h.bindingAdapterPosition == position) bmp?.let { img.setImageBitmap(it) }
                }
            }
        }
        grid.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0) {
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
                val from = a.bindingAdapterPosition; val to = b.bindingAdapterPosition
                session.pages.add(to, session.pages.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) { super.clearView(rv, vh); adapter.notifyDataSetChanged() }
        }).attachToRecyclerView(grid)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Kit.color(ctx, R.color.bar)); minimumHeight = Kit.dpi(ctx, 64); setPadding(Kit.dpi(ctx, 4), 0, Kit.dpi(ctx, 12), 0)
        }
        head.addView(Kit.iconButton(this, R.drawable.ic_back, "Back", Kit.color(ctx, R.color.label)) { session.save(); closeOverlay(); refresh() })
        head.addView(Kit.text(this, "Arrange pages", 20f, 400).apply { setPadding(Kit.dpi(ctx, 4), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Kit.textButton(this, "Select all") {
            if (selected.size == session.pages.size) selected.clear() else session.pages.forEach { selected.add(it.id) }
            adapter.notifyDataSetChanged()
        })
        head.addView(Kit.textButton(this, "Done", bold = true) { session.save(); closeOverlay(); refresh() })
        content.addView(head)
        content.addView(Kit.text(this, "Hold and drag a page to move it. Tap pages to select them, then rotate, filter, adjust or delete them together.", 14f, 400, R.color.label2).apply { setPadding(Kit.dpi(ctx, 16), Kit.dpi(ctx, 8), Kit.dpi(ctx, 16), 0) })
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        fun sel() = session.pages.filter { it.id in selected }
        val bottom = barButtons(
            Ui.toolButton(this, "Rotate", R.drawable.ic_rotate) {
                if (sel().isEmpty()) Kit.banner(ctx, "Select pages first.") else { sel().forEach { it.rot = (it.rot + 1) % 4 }; session.save(); adapter.notifyDataSetChanged() }
            },
            Ui.toolButton(this, "Filter", R.drawable.ic_filter) {
                lifecycleScope.launch {
                    if (sel().isEmpty()) { Kit.banner(ctx, "Select pages first."); return@launch }
                    val f = Dialogs.choose(ctx, "Filter for ${sel().size} page(s)", Filter.values().map { it.label }, Filter.values().indexOf(sel().first().filter)) ?: return@launch
                    sel().forEach { it.filter = Filter.values()[f] }; session.save(); adapter.notifyDataSetChanged()
                }
            },
            Ui.toolButton(this, "Adjust", R.drawable.ic_adjust) {
                lifecycleScope.launch {
                    val s = sel()
                    if (s.isEmpty()) { Kit.banner(ctx, "Select pages first."); return@launch }
                    var b = s.first().brightness; var c = s.first().contrast
                    val view = Dialogs.column(ctx, slider("Brightness", b, -80f, 80f) { b = it }, slider("Contrast", c, 0.5f, 2f) { c = it })
                    if (!Dialogs.custom(ctx, "Adjust ${s.size} page(s)", view, "Apply")) return@launch
                    s.forEach { it.brightness = b; it.contrast = c }; session.save(); adapter.notifyDataSetChanged()
                }
            },
            Ui.toolButton(this, "Delete", R.drawable.ic_delete) {
                lifecycleScope.launch {
                    val s = sel()
                    if (s.isEmpty()) { Kit.banner(ctx, "Select pages first."); return@launch }
                    if (s.size == session.pages.size) { Kit.banner(ctx, "A scan needs at least one page."); return@launch }
                    if (!Dialogs.confirm(ctx, "Delete ${s.size} page(s)?", "They are removed from the scan.", "Delete")) return@launch
                    session.pages.removeAll(s); selected.clear(); session.save(); adapter.notifyDataSetChanged()
                }
            },
        )
        openOverlay(content, bottom) { session.save(); closeOverlay(); refresh() }
    }

    // ------------------------------------------------------------------ import, save, leave

    private fun importPhotos(uris: List<Uri>) = lifecycleScope.launch {
        val hud = progress("Importing photos…")
        try {
            val filter = runCatching { Filter.valueOf(prefs.defaultFilter) }.getOrDefault(Filter.AUTO)
            uris.forEachIndexed { i, u ->
                hud.second.text = "Importing photo ${i + 1} of ${uris.size}…"
                val part = withContext(Dispatchers.Default) {
                    val bmp = ScanProcessor.decode({ contentResolver.openInputStream(u) ?: throw java.io.FileNotFoundException("The photo could not be opened.") })
                    ScanProcessor.addPhoto(session, bmp, ScanKind.DOCUMENT).also { bmp.recycle() }
                }
                session.pages.add(ScanProcessor.newPage(ScanKind.DOCUMENT, part, filter))
            }
            session.save()
            refresh()
        } catch (e: Throwable) {
            Dialogs.message(this@ScanReviewActivity, "Could not import", Ui.describe(e))
            if (session.pages.isEmpty()) { session.discard(); finish() } else refresh()
        } finally {
            hud.first.dismiss()
        }
    }

    /** A Material progress dialog (circular indicator and a status line) that blocks the screen while work runs. */
    private fun progress(text: String): Pair<Dialog, TextView> {
        val ctx = this
        val label = Kit.text(this, text, 16f, 400).apply { setPadding(Kit.dpi(ctx, 16), 0, 0, 0) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Kit.dpi(ctx, 24), Kit.dpi(ctx, 24), Kit.dpi(ctx, 24), Kit.dpi(ctx, 24))
            addView(com.google.android.material.progressindicator.CircularProgressIndicator(ctx).apply { isIndeterminate = true })
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val d = com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setView(row).setCancelable(false).create()
        d.show()
        return d to label
    }

    private fun save() = lifecycleScope.launch {
        val ctx = this@ScanReviewActivity
        val (nameBox, nameEt) = Dialogs.field(ctx, "File name", session.name ?: defaultName())
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        val format = Kit.segmented(ctx, listOf("PDF", "JPEG"), 0)
        val ocr = Dialogs.check(ctx, "Recognise text (searchable PDF)", prefs.ocrOnSave)
        val protect = Dialogs.check(ctx, "Protect with a password", false)
        val (pw1Box, pw1) = Dialogs.field(ctx, "Password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val (pw2Box, pw2) = Dialogs.field(ctx, "Repeat password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pw1Box.visibility = View.GONE; pw2Box.visibility = View.GONE
        protect.setOnCheckedChangeListener { b, on -> Kit.tick(b); pw1Box.visibility = if (on) View.VISIBLE else View.GONE; pw2Box.visibility = pw1Box.visibility }
        val quality = Kit.segmented(ctx, listOf("Best", "Balanced", "Small"), 0)
        // Page size for every page (A3, A4, A5 and the rest); the scan keeps its proportions on the page.
        val sizeKeys = session.pages.map { it.size }.distinct()
        val sizeLabels = (if (sizeKeys.size > 1) listOf("As set for each page") else emptyList()) + sizes.map { it.label }
        var sizeChoice = if (sizeKeys.size > 1) 0 else sizes.indexOfFirst { sizeKey(it) == sizeKeys.first() }.coerceAtLeast(0)
        val sizeBox = com.google.android.material.textfield.TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle).apply { hint = "Page size" }
        val sizeField = com.google.android.material.textfield.MaterialAutoCompleteTextView(sizeBox.context).apply {
            inputType = InputType.TYPE_NULL
            setAdapter(android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, sizeLabels))
            setText(sizeLabels[sizeChoice], false)
            setOnItemClickListener { _, _, pos, _ -> sizeChoice = pos }
            contentDescription = "Page size"
        }
        sizeBox.addView(sizeField)
        // A scan saved before and kept editable: replace that PDF, or save a new one.
        val savedBefore = session.savedUri?.let { Uri.parse(it) }?.takeIf { u -> runCatching { contentResolver.openFileDescriptor(u, "r")?.close(); true }.getOrDefault(false) }
        val replace = Kit.segmented(ctx, listOf("Replace saved PDF", "Save as new"), 0)
        val pdfOnly = listOf<View>(ocr, protect, quality, sizeBox, replace)
        format.setOnCheckedChangeListener { g, _ -> val pdf = g.selectedIndex() == 0; pdfOnly.forEach { it.isEnabled = pdf; it.alpha = if (pdf) 1f else 0.4f } }
        val where = Dialogs.label(ctx, "${session.pages.size} page(s), saved on this phone in ${ScanStore.folderLabel(ctx)}.")
        val parts = mutableListOf(nameBox, chipScroll, Dialogs.label(ctx, "Format"), format)
        if (savedBefore != null) parts += listOf(Dialogs.label(ctx, "This scan was saved before"), replace)
        parts += listOf(sizeBox, ocr, protect, pw1Box, pw2Box, Dialogs.label(ctx, "Picture quality"), quality, where)
        val view = Dialogs.column(ctx, *parts.toTypedArray())
        // Name suggestions from the first page's text (on the phone).
        val suggest = lifecycleScope.launch {
            val ls = lines ?: withContext(Dispatchers.Default) { runCatching { ScanStore.firstPageLines(ctx, session, prefs.ocrLanguages) }.getOrNull() }.also { lines = it }
            val names = TextInfo.nameSuggestions(ls ?: emptyList(), defaultName()).filter { it != nameEt.text.toString() }
            for (n in names.take(3)) chips.addView(Kit.text(ctx, n, 13f, 500, R.color.tint).apply {
                background = Kit.rounded(ctx, Kit.color(ctx, R.color.tint_soft), 14f)
                setPadding(Kit.dpi(ctx, 12), Kit.dpi(ctx, 6), Kit.dpi(ctx, 12), Kit.dpi(ctx, 6))
                isClickable = true; contentDescription = "Use the name $n"
                setOnClickListener { nameEt.setText(n); nameEt.setSelection(n.length) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Kit.dpi(ctx, 8) })
        }
        if (!Dialogs.custom(ctx, "Save scan", view, "Save")) { suggest.cancel(); return@launch }
        suggest.cancel()
        val name = TextInfo.safeName(nameEt.text.toString()).ifEmpty { defaultName() }
        session.name = name
        if (sizeKeys.size == 1 || sizeChoice > 0) {
            val z = sizes[if (sizeKeys.size > 1) sizeChoice - 1 else sizeChoice]
            session.pages.forEach { it.size = sizeKey(z) }
        }
        session.save(); title.text = name
        val asPdf = format.selectedIndex() == 0
        val target = if (asPdf && savedBefore != null && replace.selectedIndex() == 0) savedBefore else null
        val password = if (asPdf && protect.isChecked) pw1.text.toString() else null
        if (password != null) {
            if (password.isEmpty()) { Dialogs.message(ctx, "Password needed", "Enter a password, or turn off password protection."); return@launch }
            if (password != pw2.text.toString()) { Dialogs.message(ctx, "Passwords differ", "The two passwords are not the same."); return@launch }
        }
        val run: () -> Unit = { doSave(name, asPdf, ocr.isChecked && asPdf, quality.selectedIndex().coerceAtLeast(0), password, target) }
        if (prefs.scanFolder == null && android.os.Build.VERSION.SDK_INT < 29) {
            Dialogs.message(ctx, "Choose a folder for scans", "On this version of Android, N PDF saves scans in a folder you choose once (for example Documents). Your choice is remembered.")
            pendingSave = run
            folderLauncher.launch(null)
        } else run()
    }

    private fun doSave(name: String, asPdf: Boolean, ocr: Boolean, quality: Int, password: String?, target: Uri? = null): Unit { lifecycleScope.launch {
        val ctx = this@ScanReviewActivity
        val hud = progress("Saving…")
        try {
            val uris = withContext(Dispatchers.Default) {
                if (asPdf) listOf(ScanStore.savePdf(ctx, session, name, ScanStore.Options(ocr, prefs.ocrLanguages, quality, password), target) { msg, _ -> runOnUiThread { hud.second.text = msg } })
                else ScanStore.saveJpegs(ctx, session, name) { msg, _ -> runOnUiThread { hud.second.text = msg } }
            }
            hud.first.dismiss()
            val card = session.kind == ScanKind.BUSINESSCARD
            val firstThumb = withContext(Dispatchers.Default) { runCatching { ScanProcessor.thumb(session, session.pages.first()) }.getOrNull() }
            if (asPdf) {
                val thumbFile = File(cacheDir, "thumbs").apply { mkdirs() }.resolve("${System.nanoTime()}.jpg")
                firstThumb?.let { b -> thumbFile.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                prefs.remember(uris[0], "$name.pdf", 1, thumbFile.takeIf { it.exists() }?.absolutePath)
            }
            val contact = if (card) withContext(Dispatchers.Default) { runCatching { TextInfo.parseCard(lines ?: ScanStore.firstPageLines(ctx, session, prefs.ocrLanguages)) }.getOrNull() } else null
            // Kept on the phone so the scan can be modified later (Modify scan), unless turned off in Settings.
            if (asPdf && prefs.keepEditable) { session.savedUri = uris[0].toString(); session.tidy(); session.save() }
            else if (session.savedUri == null) session.discard()
            Kit.banner(ctx, if (asPdf) (if (target != null) "Replaced $name.pdf" else "Saved $name.pdf") else "Saved ${uris.size} picture(s)", R.drawable.ic_check)
            afterSave(uris, asPdf, name, contact)
        } catch (e: Throwable) {
            hud.first.dismiss()
            if (e is ScanStore.NeedFolder) { pendingSave = { doSave(name, asPdf, ocr, quality, password, target) }; folderLauncher.launch(null); return@launch }
            Dialogs.message(ctx, "Could not save", Ui.describe(e))
        }
    } }

    private suspend fun afterSave(uris: List<Uri>, pdf: Boolean, name: String, contact: npdf.scan.Contact?) {
        val items = mutableListOf<String>()
        if (pdf) items.add("Open in N PDF")
        if (pdf) items.add("Edit text")
        items.add("Share")
        if (contact != null && !contact.isEmpty) items.add("Save contact")
        items.add("Done")
        while (true) {
            when (items.getOrNull(Dialogs.choose(this, "Saved: $name", items) ?: items.lastIndex)) {
                "Open in N PDF" -> { startActivity(Intent(this, ReaderActivity::class.java).setData(uris[0]).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); finish(); return }
                "Edit text" -> {
                    startActivity(Intent(this, ReaderActivity::class.java).setData(uris[0]).putExtra(ReaderActivity.EXTRA_MODE, R.id.mode_edit)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); finish(); return
                }
                "Share" -> {
                    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(if (pdf) "application/pdf" else "image/jpeg").putExtra(Intent.EXTRA_STREAM, uris[0])
                    else Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    startActivity(Intent.createChooser(i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share $name"))
                    finish(); return
                }
                "Save contact" -> runCatching { startActivity(ScanStore.contactIntent(contact!!)) }.onFailure { Kit.banner(this, "No contacts app is available.") }
                else -> { finish(); return }
            }
        }
    }

    private fun close() = lifecycleScope.launch {
        if (session.savedUri != null) { session.save(); finish(); return@launch }
        when (Dialogs.ask3(this@ScanReviewActivity, "Close this scan?", "Keep it to finish later, or discard its ${session.pages.size} page(s).", "Keep for later", "Discard", "Cancel")) {
            0 -> { session.save(); finish() }
            1 -> { session.discard(); finish() }
        }
    }

    @Suppress("unused")
    private fun unused(e: EditText, b: MaterialButton) = Unit
}
