package com.gv.scpreader

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A saved page reference (bookmark or history row). */
data class PageRef(val slug: String, val title: String, val time: Long)

/**
 * All persistent state: reader settings, bookmarks, history, read-tracking and
 * per-page scroll positions. Small enough that SharedPreferences + JSON is plenty.
 */
class Prefs internal constructor(private val sp: SharedPreferences) {
    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences("reader", Context.MODE_PRIVATE))

    // ---- Reader settings ----
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()
    var textZoom: Int
        get() = sp.getInt("textZoom", 105)
        set(v) = sp.edit().putInt("textZoom", v).apply()
    var lineHeight: Float
        get() = sp.getFloat("lineHeight", 1.6f)
        set(v) = sp.edit().putFloat("lineHeight", v).apply()
    var font: Int
        get() = sp.getInt("font", 0)
        set(v) = sp.edit().putInt("font", v).apply()
    var strictDark: Boolean
        get() = sp.getBoolean("strictDark", true)
        set(v) = sp.edit().putBoolean("strictDark", v).apply()
    var dimImages: Boolean
        get() = sp.getBoolean("dimImages", true)
        set(v) = sp.edit().putBoolean("dimImages", v).apply()
    var autoExpand: Boolean
        get() = sp.getBoolean("autoExpand", false)
        set(v) = sp.edit().putBoolean("autoExpand", v).apply()
    var volumeKeys: Boolean
        get() = sp.getBoolean("volumeKeys", true)
        set(v) = sp.edit().putBoolean("volumeKeys", v).apply()
    var tapPaging: Boolean
        get() = sp.getBoolean("tapPaging", true)
        set(v) = sp.edit().putBoolean("tapPaging", v).apply()
    /** Width of each page-turn tap area, as a percentage of the screen width. */
    var tapZone: Int
        get() = sp.getInt("tapZone", 25)
        set(v) = sp.edit().putInt("tapZone", v).apply()
    var nextCard: Boolean
        get() = sp.getBoolean("nextCard", true)
        set(v) = sp.edit().putBoolean("nextCard", v).apply()
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", false)
        set(v) = sp.edit().putBoolean("keepScreenOn", v).apply()
    var blockAds: Boolean
        get() = sp.getBoolean("blockAds", true)
        set(v) = sp.edit().putBoolean("blockAds", v).apply()
    var fullscreen: Boolean
        get() = sp.getBoolean("fullscreen", false)
        set(v) = sp.edit().putBoolean("fullscreen", v).apply()
    var hideRead: Boolean
        get() = sp.getBoolean("hideRead", false)
        set(v) = sp.edit().putBoolean("hideRead", v).apply()

    var lastSlug: String?
        get() = sp.getString("lastSlug", null)
        set(v) = sp.edit().putString("lastSlug", v).apply()

    // ---- Read tracking ----
    fun isRead(slug: String) = slug in readSet()
    fun readSet(): Set<String> = sp.getStringSet("read", emptySet())!!
    fun setRead(slug: String, read: Boolean) {
        val s = readSet().toMutableSet()
        if (read) s.add(slug) else s.remove(slug)
        sp.edit().putStringSet("read", s).apply()
    }

    // ---- Bookmarks ----
    fun bookmarks(): List<PageRef> = loadRefs("bookmarks")
    fun isBookmarked(slug: String) = bookmarks().any { it.slug == slug }
    /** Toggles and returns the new bookmarked state. */
    fun toggleBookmark(slug: String, title: String): Boolean {
        val list = bookmarks().toMutableList()
        val removed = list.removeAll { it.slug == slug }
        if (!removed) list.add(0, PageRef(slug, title, System.currentTimeMillis()))
        saveRefs("bookmarks", list)
        return !removed
    }

    // ---- History ----
    fun history(): List<PageRef> = loadRefs("history")
    fun addHistory(slug: String, title: String) {
        val list = history().filterNot { it.slug == slug }.toMutableList()
        list.add(0, PageRef(slug, title, System.currentTimeMillis()))
        saveRefs("history", list.take(300))
    }
    fun clearHistory() = sp.edit().remove("history").apply()

    // ---- Scroll positions (fraction 0..1 per page) ----
    fun scrollFor(slug: String): Float =
        JSONObject(sp.getString("scroll", "{}")!!).optDouble(slug, 0.0).toFloat()
    fun saveScroll(slug: String, fraction: Float) {
        val o = JSONObject(sp.getString("scroll", "{}")!!)
        if (fraction < 0.02f || fraction > 0.97f) o.remove(slug) else o.put(slug, fraction.toDouble())
        // Keep it bounded: drop arbitrary old keys once it grows large.
        while (o.length() > 400) o.remove(o.keys().next())
        sp.edit().putString("scroll", o.toString()).apply()
    }

    private fun loadRefs(key: String): List<PageRef> = parseRefs(sp.getString(key, "[]")!!)

    private fun saveRefs(key: String, refs: List<PageRef>) {
        sp.edit().putString(key, refsJson(refs)).apply()
    }

    // ---- Backup ----

    /** Counts from an import, for the confirmation message. */
    data class ImportResult(val bookmarks: Int, val read: Int, val history: Int)

    /** Every stored value (progress and settings), typed so it can be restored exactly. */
    fun exportJson(): String {
        val values = JSONObject()
        for ((k, v) in sp.all) {
            val (type, value) = when (v) {
                is Boolean -> "bool" to v
                is Int -> "int" to v
                is Long -> "long" to v
                is Float -> "float" to v.toDouble()
                is String -> "string" to v
                is Set<*> -> "set" to JSONArray(v.map { it.toString() })
                else -> continue
            }
            values.put(k, JSONObject().put("t", type).put("v", value))
        }
        return JSONObject()
            .put("app", BACKUP_APP)
            .put("version", 1)
            .put("exported", System.currentTimeMillis())
            .put("values", values)
            .toString(2)
    }

    /**
     * Restores a backup. Progress is merged so nothing already on this device is lost;
     * reader settings are taken from the backup.
     */
    fun importJson(text: String): ImportResult {
        val root = JSONObject(text)
        require(root.optString("app") == BACKUP_APP) { "Not an SCP Night Reader backup" }
        val values = root.getJSONObject("values")
        fun str(k: String) = values.optJSONObject(k)?.optString("v")

        val beforeBookmarks = bookmarks().map { it.slug }.toSet()
        val beforeRead = readSet()
        val beforeHistory = history().size

        val e = sp.edit()
        for (k in values.keys()) {
            if (k in MERGED_KEYS) continue
            val o = values.getJSONObject(k)
            when (o.getString("t")) {
                "bool" -> e.putBoolean(k, o.getBoolean("v"))
                "int" -> e.putInt(k, o.getInt("v"))
                "long" -> e.putLong(k, o.getLong("v"))
                "float" -> e.putFloat(k, o.getDouble("v").toFloat())
                "string" -> e.putString(k, o.getString("v"))
                "set" -> o.getJSONArray("v").let { a -> e.putStringSet(k, (0 until a.length()).map(a::getString).toSet()) }
            }
        }
        values.optJSONObject("read")?.optJSONArray("v")?.let { a ->
            e.putStringSet("read", beforeRead + (0 until a.length()).map(a::getString))
        }
        str("bookmarks")?.let { b ->
            val merged = (bookmarks() + parseRefs(b)).distinctBy { it.slug }.sortedByDescending { it.time }
            e.putString("bookmarks", refsJson(merged))
        }
        str("history")?.let { h ->
            val merged = (history() + parseRefs(h)).sortedByDescending { it.time }.distinctBy { it.slug }.take(300)
            e.putString("history", refsJson(merged))
        }
        str("scroll")?.let { sc ->
            val local = JSONObject(sp.getString("scroll", "{}")!!)
            val incoming = JSONObject(sc)
            for (k in incoming.keys()) if (!local.has(k)) local.put(k, incoming.get(k))
            e.putString("scroll", local.toString())
        }
        if (lastSlug == null) str("lastSlug")?.let { e.putString("lastSlug", it) }
        e.commit()

        return ImportResult(
            bookmarks = bookmarks().count { it.slug !in beforeBookmarks },
            read = (readSet() - beforeRead).size,
            history = history().size - beforeHistory,
        )
    }

    private fun parseRefs(json: String): List<PageRef> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PageRef(o.getString("s"), o.optString("t"), o.optLong("ts"))
        }
    }

    private fun refsJson(refs: List<PageRef>): String {
        val arr = JSONArray()
        refs.forEach { arr.put(JSONObject().put("s", it.slug).put("t", it.title).put("ts", it.time)) }
        return arr.toString()
    }

    private companion object {
        const val BACKUP_APP = "com.gv.scpreader"
        /** Progress values combined with what's on the device instead of overwritten. */
        val MERGED_KEYS = setOf("read", "bookmarks", "history", "scroll", "lastSlug")
    }
}
