package npdf.app

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import npdf.app.Dialogs.selectedIndex

/** Name for comments, theme, recent list, and what N PDF is built from. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = MaterialToolbar(this).apply {
            title = "Settings and about"
            setNavigationIcon(R.drawable.ic_back)
            navigationContentDescription = "Back"
            setNavigationOnClickListener { finish() }
        }
        root.addView(bar)
        val (nameTil, nameEt) = Dialogs.field(this, "Your name (shown on comments)", prefs.author)
        nameEt.setOnFocusChangeListener { _, has -> if (!has) prefs.author = nameEt.text?.toString() ?: "" }
        val modes = listOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
        val theme = Dialogs.radios(this, listOf("Follow the phone", "Light", "Dark"), modes.indexOf(prefs.nightMode).coerceAtLeast(0))
        theme.setOnCheckedChangeListener { g, _ ->
            val m = modes[g.selectedIndex().coerceAtLeast(0)]
            prefs.nightMode = m
            AppCompatDelegate.setDefaultNightMode(m)
        }
        val clear = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Clear the recent files list"
            setOnClickListener { prefs.clearRecents(); Ui.toast(this@SettingsActivity, "Recent list cleared") }
        }
        val version = BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")"
        val about = Dialogs.label(this, """
            N PDF $version

            Works entirely on this phone. The app has no internet permission, so Android itself blocks every network connection it might try; it has no account, no advertising and no tracking, and Android's cloud backup is switched off for it. Files leave the phone only when you share or print them yourself.

            Built with: MuPDF (PDF engine, GNU AGPL v3), Tesseract OCR and its English, Hindi and Gujarati language data (Apache 2.0), AndroidX and Material Components (Apache 2.0), Kotlin (Apache 2.0).

            N PDF is free software under the GNU Affero General Public License v3. Its source code is available from the project's repository: github.com/nkpatel5369-web/Nayanpdf

            Signatures placed with N PDF are pictures of a signature. They are not certificate-based digital signatures and do not protect a document against later changes.
        """.trimIndent())
        val content = Dialogs.column(this, nameTil, Dialogs.label(this, "Theme", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall),
            theme, clear, about)
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Ui.fitSystemBars(root)
    }

    override fun onPause() {
        super.onPause()
        currentFocus?.clearFocus()
    }
}
