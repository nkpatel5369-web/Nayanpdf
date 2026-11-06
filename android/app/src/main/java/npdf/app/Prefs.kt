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
