package npdf.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import java.util.concurrent.Executors

class NpdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs(this).nightMode)
        // Load the PDF engine's native library now, off the main thread, so the first
        // document opens without that delay.
        Executors.newSingleThreadExecutor().execute {
            try {
                com.artifex.mupdf.fitz.Context.init()
            } catch (e: Throwable) {
                // Reported when a document is opened.
            }
        }
        // Working files of earlier runs that ended without cleaning up.
        Executors.newSingleThreadExecutor().execute { cleanCache() }
    }

    private fun cleanCache() {
        val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
        for (dir in listOf("work", "share", "camera", "out")) {
            File(cacheDir, dir).listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
        }
    }
}
