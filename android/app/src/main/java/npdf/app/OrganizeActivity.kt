package npdf.app

import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.artifex.mupdf.fitz.Cookie
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import npdf.app.databinding.ActivityOrganizeBinding
import npdf.app.databinding.ItemPageBinding
import npdf.engine.Pages
import npdf.engine.Pdf
import kotlin.math.max
import kotlin.math.roundToInt

/** Thumbnails of the open document's pages: select, reorder, rotate, delete, extract, insert. */
class OrganizeActivity : AppCompatActivity() {

    private lateinit var b: ActivityOrganizeBinding
    private lateinit var s: DocSession
    private val selected = sortedSetOf<Int>()
    private var order = mutableListOf<Int>()
    private val thumbs = LruCache<Int, Bitmap>(80)
    private var thumbVersion = -1
    private val adapter = PagesAdapter()
    private var pendingExtract: List<Int> = emptyList()

    private val extractLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) lifecycleScope.launch { extractTo(uri) }
    }
    private val insertLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { insertFrom(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = DocSession.current
        if (session == null) { finish(); return }
        s = session
        b = ActivityOrganizeBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.fitSystemBars(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        val span = max(2, (resources.displayMetrics.widthPixels / resources.displayMetrics.density / 130).toInt())
        b.grid.layoutManager = GridLayoutManager(this, span)
        b.grid.adapter = adapter
        dragHelper.attachToRecyclerView(b.grid)
        buildActions()
        s.listeners.add(listener)
        reload()
    }

    override fun onDestroy() {
        if (::s.isInitialized) s.listeners.remove(listener)
        super.onDestroy()
    }

    private val listener: (Set<Int>?) -> Unit = { reload() }

    private fun reload() {
        order = (0 until s.sizes.size).toMutableList()
        selected.retainAll(order.toSet())
        if (thumbVersion != s.version) { thumbs.evictAll(); thumbVersion = s.version }
        adapter.notifyDataSetChanged()
        b.toolbar.subtitle = "${order.size} pages" + if (selected.isNotEmpty()) " · ${selected.size} selected" else ""
    }

    private fun buildActions() {
        fun add(label: String, icon: Int, run: suspend () -> Unit) {
            val btn = Ui.toolButton(this, label, icon) { lifecycleScope.launch { run() } }
            b.actions.addView(btn)
        }
        add("Rotate left", R.drawable.ic_rotate) { rotate(-90) }
        add("Rotate right", R.drawable.ic_rotate) { rotate(90) }
        add("Delete", R.drawable.ic_delete) { delete() }
        add("Extract", R.drawable.ic_extract) { extract() }
        add("Blank page", R.drawable.ic_insert_page) { insertBlank() }
        add("Insert file", R.drawable.ic_add) { insertLauncher.launch(arrayOf("application/pdf")) }
        add("Select all", R.drawable.ic_check) {
            if (selected.size == order.size) selected.clear() else selected.addAll(order)
            reload()
        }
    }

    private fun need(): List<Int>? {
        if (selected.isEmpty()) { Ui.toast(this, "Select pages first."); return null }
        return selected.toList()
    }

    private suspend fun <T> run(label: String, pages: Set<Int>?, block: (com.artifex.mupdf.fitz.PDFDocument) -> T): T? {
        b.hint.text = "$label…"
        return try {
            s.edit(label, pages, block)
        } catch (e: Throwable) {
            Dialogs.message(this, "Could not complete", Ui.describe(e))
            null
        } finally {
            b.hint.text = "Tap pages to select them. Long-press and drag to move a page."
        }
    }

    private suspend fun rotate(deg: Int) {
        val sel = need() ?: return
        run("Rotate", sel.toSet()) { Pages.rotate(it, sel.map { p -> p + 1 }, deg) }
    }

    private suspend fun delete() {
        val sel = need() ?: return
        if (sel.size >= order.size) { Dialogs.message(this, "Cannot delete every page", "A PDF needs at least one page."); return }
        if (!Dialogs.confirm(this, "Delete ${sel.size} page(s)?", "You can undo this in the document until you close it.", "Delete")) return
        run("Delete pages", null) { Pages.delete(it, sel.map { p -> p + 1 }) }
        selected.clear(); reload()
    }

    private fun extract() {
        val sel = need() ?: return
        pendingExtract = sel
        extractLauncher.launch(Files.stem(s.name) + "_pages.pdf")
    }

    private suspend fun extractTo(uri: android.net.Uri) {
        val pages = pendingExtract.map { it + 1 }
        val tmp = Files.temp(this, "extract.pdf")
        try {
            s.read { Pages.extract(it, pages, tmp) }
            withContext(kotlinx.coroutines.Dispatchers.IO) { Files.writeToUri(this@OrganizeActivity, tmp, uri) }
            Ui.toast(this, "${pages.size} page(s) saved as ${Files.displayName(this, uri)}")
        } catch (e: Throwable) {
            Dialogs.message(this, "Could not extract", Ui.describe(e))
        }
    }

    private suspend fun insertBlank() {
        val after = selected.lastOrNull()?.plus(1) ?: order.size
        run("Insert page", null) { Pages.insertBlank(it, after) }
    }

    private suspend fun insertFrom(uri: android.net.Uri) {
        val after = selected.lastOrNull()?.plus(1) ?: order.size
        val tmp = Files.temp(this, "insert.pdf")
        try {
            withContext(kotlinx.coroutines.Dispatchers.IO) { Files.copyToFile(this@OrganizeActivity, uri, tmp) }
        } catch (e: Throwable) {
            Dialogs.message(this, "Could not read the file", Ui.describe(e)); return
        }
        var pw: String? = null
        while (true) {
            try {
                s.edit("Insert pages", null) { d ->
                    val src = Pdf.open(tmp.absolutePath, pw)
                    try { Pages.insertFrom(d, after, src) } finally { src.destroy() }
                }
                return
            } catch (e: npdf.engine.PdfError) {
                if (e.code == npdf.engine.Codes.PASSWORD_REQUIRED || e.code == npdf.engine.Codes.PASSWORD_WRONG) {
                    pw = Dialogs.password(this, if (e.code == npdf.engine.Codes.PASSWORD_WRONG) "Wrong password" else "Password required",
                        "The file to insert is protected.") ?: return
                } else { Dialogs.message(this, "Could not insert", Ui.describe(e)); return }
            } catch (e: Throwable) {
                Dialogs.message(this, "Could not insert", Ui.describe(e)); return
            }
        }
    }

    // -------------------------------------------------------------- dragging

    private var dragFrom = -1
    private var dragTo = -1

    private val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition; val to = target.bindingAdapterPosition
            if (dragFrom < 0) dragFrom = from
            dragTo = to
            val item = order.removeAt(from)
            order.add(to, item)
            adapter.notifyItemMoved(from, to)
            return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            if (dragFrom >= 0 && dragTo >= 0 && dragFrom != dragTo) {
                val newOrder = order.map { it + 1 }
                lifecycleScope.launch { run("Move page", null) { Pages.reorder(it, newOrder) }; selected.clear(); reload() }
            }
            dragFrom = -1; dragTo = -1
        }
    })

    // -------------------------------------------------------------- adapter

    private inner class Holder(val v: ItemPageBinding) : RecyclerView.ViewHolder(v.root)

    private inner class PagesAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = order.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(h: Holder, position: Int) {
            val page = order[position]
            h.v.number.text = "${page + 1}"
            h.v.selected.visibility = if (page in selected) View.VISIBLE else View.GONE
            h.v.root.contentDescription = "Page ${page + 1}" + if (page in selected) ", selected" else ""
            h.v.root.setOnClickListener {
                if (!selected.remove(page)) selected.add(page)
                notifyItemChanged(h.bindingAdapterPosition)
                b.toolbar.subtitle = "${order.size} pages" + if (selected.isNotEmpty()) " · ${selected.size} selected" else ""
            }
            val bmp = thumbs.get(page)
            h.v.thumb.setImageBitmap(bmp)
            if (bmp == null) render(page, h)
        }
    }

    private fun render(page: Int, h: Holder) {
        val size = s.sizes.getOrNull(page) ?: return
        val w = 240
        val scale = w / max(1f, size.w)
        val hh = max(1, (size.h * scale).roundToInt()).coerceAtMost(w * 3)
        val ver = s.version
        DocSession.renderPool.execute {
            val bmp = try { s.renderTile(page, scale, 0, 0, w, hh, Cookie()) } catch (e: Throwable) { null }
            runOnUiThread {
                if (bmp != null && ver == s.version) {
                    thumbs.put(page, bmp)
                    val pos = order.indexOf(page)
                    if (pos >= 0) adapter.notifyItemChanged(pos)
                }
            }
        }
    }
}
