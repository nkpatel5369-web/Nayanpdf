package npdf.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import npdf.app.databinding.ActivityListBinding
import npdf.app.databinding.ItemRowBinding
import java.io.File

/** A list of files to turn into one PDF: add, reorder by dragging, remove, then create. */
abstract class FileListActivity : AppCompatActivity() {

    class Item(val uri: Uri, val name: String, var thumb: Bitmap? = null, var file: File? = null)

    lateinit var b: ActivityListBinding
    val items = mutableListOf<Item>()
    protected val adapter = Adapter()

    abstract val screenTitle: String
    abstract val hintText: String
    abstract val goLabel: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.fitSystemBars(b.root)
        b.toolbar.title = screenTitle
        b.toolbar.setNavigationOnClickListener { finish() }
        b.hint.text = hintText
        b.go.text = goLabel
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        drag.attachToRecyclerView(b.list)
        refresh()
    }

    fun refresh() {
        adapter.notifyDataSetChanged()
        b.go.isEnabled = items.isNotEmpty()
        b.toolbar.subtitle = if (items.isEmpty()) null else "${items.size} file(s)"
    }

    fun addUris(uris: List<Uri>) {
        for (u in uris) {
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { /* temporary access is enough */ }
            items.add(Item(u, Files.displayName(this, u)))
        }
        refresh()
        lifecycleScope.launch { loadThumbs() }
    }

    open suspend fun thumbnail(item: Item): Bitmap? = null

    private suspend fun loadThumbs() {
        for (it in items.toList()) if (it.thumb == null) {
            it.thumb = try { withContext(Dispatchers.IO) { thumbnail(it) } } catch (e: Throwable) { null }
            adapter.notifyItemChanged(items.indexOf(it))
        }
    }

    /** Copies every item into the working area (done once, just before creating the PDF). */
    suspend fun localFiles(): List<File> = withContext(Dispatchers.IO) {
        items.map { item ->
            item.file?.takeIf { it.exists() } ?: Files.temp(this@FileListActivity, item.name).also {
                Files.copyToFile(this@FileListActivity, item.uri, it); item.file = it
            }
        }
    }

    /** Opens a finished PDF in the reader. */
    fun openResult(uri: Uri) {
        startActivity(Intent(this, ReaderActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        finish()
    }

    private val drag = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition; val to = t.bindingAdapterPosition
            items.add(to, items.removeAt(from))
            adapter.notifyItemMoved(from, to)
            return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
    })

    inner class Holder(val v: ItemRowBinding) : RecyclerView.ViewHolder(v.root)

    inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(h: Holder, position: Int) {
            val it = items[position]
            h.v.title.text = it.name
            h.v.subtitle.text = "${position + 1} of ${items.size} · long-press and drag to move"
            h.v.thumb.setImageBitmap(it.thumb)
            h.v.remove.setOnClickListener { _ ->
                val p = h.bindingAdapterPosition
                if (p >= 0) { items.removeAt(p); refresh() }
            }
        }
    }
}
