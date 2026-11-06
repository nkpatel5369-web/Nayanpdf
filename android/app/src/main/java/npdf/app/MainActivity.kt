package npdf.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.GridLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import npdf.app.databinding.ActivityMainBinding
import npdf.app.databinding.ItemRowBinding
import java.io.File

/** Home: open a PDF, start a tool, or reopen a recent file. */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { Prefs(this) }

    /** What to do with the document picked next: a reader mode and/or action. */
    private var pendingMode = 0
    private var pendingAction: String? = null

    private val openLauncher = registerForActivityResult(OpenPdf()) { uri ->
        if (uri != null) openReader(uri, pendingMode, pendingAction)
        pendingMode = 0; pendingAction = null
    }

    /** OPEN_DOCUMENT that asks for persistable read and write access. */
    class OpenPdf : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.fitSystemBars(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setLogo(R.drawable.ic_logo)
        b.openBtn.setOnClickListener { pick(0, null) }
        b.clearRecent.setOnClickListener { prefs.clearRecents(); showRecents() }
        buildTools()
    }

    override fun onResume() {
        super.onResume()
        showRecents()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) { startActivity(Intent(this, SettingsActivity::class.java)); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun pick(mode: Int, action: String?) {
        pendingMode = mode; pendingAction = action
        openLauncher.launch(arrayOf("application/pdf"))
    }

    private fun openReader(uri: Uri, mode: Int = 0, action: String? = null) {
        Files.persist(this, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val i = Intent(this, ReaderActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (mode != 0) i.putExtra(ReaderActivity.EXTRA_MODE, mode)
        action?.let { i.putExtra(ReaderActivity.EXTRA_ACTION, it) }
        startActivity(i)
    }

    private fun buildTools() {
        val tools = listOf(
            Triple("Edit text", R.drawable.ic_edit_text) { pick(R.id.mode_edit, null) },
            Triple("Comment", R.drawable.ic_comment) { pick(R.id.mode_comment, null) },
            Triple("Fill & Sign", R.drawable.ic_sign) { pick(R.id.mode_sign, null) },
            Triple("Photos to PDF", R.drawable.ic_camera) { startActivity(Intent(this, ImagesActivity::class.java)) },
            Triple("Combine files", R.drawable.ic_merge) { startActivity(Intent(this, MergeActivity::class.java)) },
            Triple("Organize pages", R.drawable.ic_pages) { pick(0, ReaderActivity.ACTION_ORGANIZE) },
            Triple("Split", R.drawable.ic_split) { pick(0, ReaderActivity.ACTION_SPLIT) },
            Triple("Compress", R.drawable.ic_compress) { pick(0, ReaderActivity.ACTION_COMPRESS) },
            Triple("Protect", R.drawable.ic_lock) { pick(0, ReaderActivity.ACTION_PROTECT) },
            Triple("Remove password", R.drawable.ic_unlock) { pick(0, ReaderActivity.ACTION_UNPROTECT) },
            Triple("Make searchable", R.drawable.ic_ocr) { pick(0, ReaderActivity.ACTION_OCR) },
            Triple("Redact", R.drawable.ic_redact) { pick(R.id.mode_redact, null) },
        )
        val grid = b.toolGrid
        for ((label, icon, run) in tools) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                setIconResource(icon)
                iconGravity = MaterialButton.ICON_GRAVITY_TOP
                isAllCaps = false
                textSize = 12f
                minHeight = Dialogs.dp(this@MainActivity, 84)
                setOnClickListener { run() }
            }
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                setMargins(Dialogs.dp(this@MainActivity, 3), Dialogs.dp(this@MainActivity, 3), Dialogs.dp(this@MainActivity, 3), Dialogs.dp(this@MainActivity, 3))
            }
            grid.addView(btn, lp)
        }
    }

    private fun showRecents() {
        val list = b.recentList
        list.removeAllViews()
        val items = prefs.recents()
        b.recentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        b.clearRecent.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        for (r in items) {
            val row = ItemRowBinding.inflate(layoutInflater, list, false)
            row.title.text = r.name
            row.subtitle.text = "Page ${r.page} · " + DateUtils.getRelativeTimeSpanString(r.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            r.thumb?.let { t -> if (File(t).exists()) row.thumb.setImageBitmap(BitmapFactory.decodeFile(t)) }
            row.root.setOnClickListener {
                if (canRead(r.uri)) openReader(r.uri) else {
                    Ui.toast(this, "\"${r.name}\" is no longer available. Open it again from its folder.")
                    prefs.forget(r.uri); showRecents()
                }
            }
            row.remove.setOnClickListener { prefs.forget(r.uri); showRecents() }
            list.addView(row.root)
        }
    }

    private fun canRead(uri: Uri): Boolean = try {
        contentResolver.openFileDescriptor(uri, "r")?.close(); true
    } catch (e: Exception) { false }
}
