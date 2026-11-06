package npdf.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.artifex.mupdf.fitz.Cookie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Small first-page pictures for the recent list, kept in the app's private storage. */
object Thumbs {
    const val WIDTH = 160

    suspend fun make(ctx: Context, s: DocSession, uri: Uri): String? = withContext(Dispatchers.Default) {
        if (s.sizes.isEmpty()) return@withContext null
        val size = s.sizes[0]
        val scale = WIDTH / max(1f, size.w)
        val w = WIDTH; val h = max(1, (size.h * scale).roundToInt()).coerceAtMost(WIDTH * 3)
        val bmp = s.renderTile(0, scale, 0, 0, w, h, Cookie()) ?: return@withContext null
        val dir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
        val f = File(dir, Integer.toHexString(uri.toString().hashCode()) + ".png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        bmp.recycle()
        f.absolutePath
    }
}
