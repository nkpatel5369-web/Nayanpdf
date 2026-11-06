package npdf.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Access to the user's files through Android's document providers. */
object Files {

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path ?: "document.pdf").name
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it }
            }
        } catch (e: Exception) {
            // Some providers do not answer queries; fall back to the last path segment.
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "document.pdf"
    }

    fun size(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (e: Exception) {
            // unknown
        }
        return -1
    }

    fun workDir(ctx: Context, name: String): File = File(ctx.cacheDir, name).apply { mkdirs() }

    /** A new empty file in the app's private working area. */
    fun temp(ctx: Context, name: String): File {
        val dir = workDir(ctx, "work")
        return File(dir, "${System.nanoTime()}_${safe(name)}")
    }

    fun safe(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifBlank { "document.pdf" }

    fun copyToFile(ctx: Context, uri: Uri, dest: File) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("The file could not be read.")
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
    }

    fun readBytes(ctx: Context, uri: Uri): ByteArray {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("The file could not be read.")
        return input.use { it.readBytes() }
    }

    /** Replaces the content behind [uri] with [src]. */
    fun writeToUri(ctx: Context, src: File, uri: Uri) {
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("The file could not be written.")
        out.use { o -> src.inputStream().use { i -> i.copyTo(o, 256 * 1024) } }
    }

    /** Keeps access to a picked file after restarts (for the recent list), where the provider allows it. */
    fun persist(ctx: Context, uri: Uri, flags: Int) {
        val f = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, f)
        } catch (e: SecurityException) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e2: SecurityException) {
                // Not persistable (e.g. an e-mail attachment): it simply won't reopen from Recent.
            }
        }
    }

    fun canWrite(ctx: Context, uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path ?: return false).canWrite()
        return try {
            ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } ||
                ctx.checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** A content URI other apps can read, for sharing or printing a file of ours. */
    fun shareUri(ctx: Context, file: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)

    fun shareCopy(ctx: Context, src: File, name: String): File {
        val dir = workDir(ctx, "share")
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, safe(name))
        src.copyTo(f, overwrite = true)
        return f
    }

    fun stem(name: String) = name.substringBeforeLast('.', name)

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
