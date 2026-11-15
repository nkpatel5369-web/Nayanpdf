package npdf.app

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject

/** Settings and the recent-files list, kept in the app's private storage only. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("npdf", Context.MODE_PRIVATE)

    var author: String
        get() = sp.getString("author", "") ?: ""
        set(v) = sp.edit().putString("author", v.trim()).apply()

    var nightMode: Int
        get() = sp.getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(v) = sp.edit().putInt("night", v).apply()

    var confirmedReplace: Set<String>
        get() = sp.getStringSet("replaceOk", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("replaceOk", v).apply()

    var annotColor: Int
        get() = sp.getInt("annotColor", 0xFFE53935.toInt())
        set(v) = sp.edit().putInt("annotColor", v).apply()

    var highlightColor: Int
        get() = sp.getInt("hlColor", 0xFFFFEB3B.toInt())
        set(v) = sp.edit().putInt("hlColor", v).apply()

    // Scanner
    var autoCapture: Boolean
        get() = sp.getBoolean("scanAuto", true)
        set(v) = sp.edit().putBoolean("scanAuto", v).apply()

    /** Show the border adjustment after each automatic capture. */
    var adjustAfterCapture: Boolean
        get() = sp.getBoolean("scanAdjust", false)
        set(v) = sp.edit().putBoolean("scanAdjust", v).apply()

    var defaultFilter: String
        get() = sp.getString("scanFilter", "AUTO") ?: "AUTO"
        set(v) = sp.edit().putString("scanFilter", v).apply()

    /** Recognise text (OCR) when a scan is saved as PDF. */
    var ocrOnSave: Boolean
        get() = sp.getBoolean("scanOcr", true)
        set(v) = sp.edit().putBoolean("scanOcr", v).apply()

    var ocrLanguages: List<String>
        get() = (sp.getString("ocrLangs", "eng") ?: "eng").split(',').filter { it in Ocr.LANGUAGES }.ifEmpty { listOf("eng") }
        set(v) = sp.edit().putString("ocrLangs", v.joinToString(",")).apply()

    var flashMode: Int
        get() = sp.getInt("scanFlash", 2) // 0 off, 1 on, 2 auto
        set(v) = sp.edit().putInt("scanFlash", v).apply()

    /** A folder the user chose for scans (document tree), or null for Documents/N PDF. */
    var scanFolder: String?
        get() = sp.getString("scanFolder", null)
        set(v) = sp.edit().putString("scanFolder", v).apply()

    // Reading
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", false)
        set(v) = sp.edit().putBoolean("keepScreenOn", v).apply()

    var darkPages: Boolean
        get() = sp.getBoolean("darkPages", false)
        set(v) = sp.edit().putBoolean("darkPages", v).apply()

    var recentSort: String
        get() = sp.getString("recentSort", "date") ?: "date"
        set(v) = sp.edit().putString("recentSort", v).apply()

    class Recent(val uri: Uri, val name: String, val page: Int, val time: Long, val thumb: String?)

    fun recents(): List<Recent> {
        val arr = try { JSONArray(sp.getString("recent", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Recent(Uri.parse(o.optString("uri")), o.optString("name"), o.optInt("page", 1), o.optLong("time"),
                o.optString("thumb").ifEmpty { null })
        }
    }

    fun remember(uri: Uri, name: String, page: Int, thumb: String?) {
        val list = recents().filter { it.uri != uri }.toMutableList()
        list.add(0, Recent(uri, name, page, System.currentTimeMillis(), thumb))
        save(list.take(30))
    }

    fun lastPage(uri: Uri): Int = recents().firstOrNull { it.uri == uri }?.page ?: 1

    fun forget(uri: Uri) = save(recents().filter { it.uri != uri })

    fun clearRecents() = save(emptyList())

    private fun save(list: List<Recent>) {
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject().apply {
            put("uri", r.uri.toString()); put("name", r.name); put("page", r.page); put("time", r.time)
            r.thumb?.let { put("thumb", it) }
        })
        sp.edit().putString("recent", arr.toString()).apply()
    }
}
