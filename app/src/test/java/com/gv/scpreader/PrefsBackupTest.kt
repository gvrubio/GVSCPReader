package com.gv.scpreader

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory SharedPreferences, enough for Prefs. */
private class FakePrefs : SharedPreferences {
    val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(k: String, d: String?) = map[k] as String? ?: d
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(k: String, d: MutableSet<String>?) = (map[k] as Set<String>?)?.toMutableSet() ?: d
    override fun getInt(k: String, d: Int) = map[k] as Int? ?: d
    override fun getLong(k: String, d: Long) = map[k] as Long? ?: d
    override fun getFloat(k: String, d: Float) = map[k] as Float? ?: d
    override fun getBoolean(k: String, d: Boolean) = map[k] as Boolean? ?: d
    override fun contains(k: String) = k in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val pending = mutableMapOf<String, Any?>()
        val removed = mutableSetOf<String>()
        override fun putString(k: String, v: String?) = apply { pending[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { pending[k] = v?.toSet() }
        override fun putInt(k: String, v: Int) = apply { pending[k] = v }
        override fun putLong(k: String, v: Long) = apply { pending[k] = v }
        override fun putFloat(k: String, v: Float) = apply { pending[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
        override fun remove(k: String) = apply { removed += k }
        override fun clear() = apply { removed += map.keys }
        override fun commit(): Boolean { removed.forEach { map.remove(it) }; map.putAll(pending); return true }
        override fun apply() { commit() }
    }
}

class PrefsBackupTest {
    private fun phoneLike(): Prefs = Prefs(FakePrefs()).apply {
        theme = 3; textZoom = 140; lineHeight = 1.8f; fullscreen = true; tapZone = 30
        toggleBookmark("scp-173", "SCP-173 — The Sculpture")
        toggleBookmark("scp-7004", "SCP-7004 — Insane, Wailing, Feral.")
        addHistory("scp-049", "SCP-049"); addHistory("scp-7008", "SCP-7008")
        setRead("scp-173", true); setRead("scp-049", true)
        saveScroll("scp-7008", 0.4f)
        lastSlug = "scp-7004/offset/1"
    }

    @Test
    fun exportThenImportIntoFreshInstallRestoresEverything() {
        val old = phoneLike()
        val fresh = Prefs(FakePrefs())
        val r = fresh.importJson(old.exportJson())

        assertEquals(2, r.bookmarks); assertEquals(2, r.read); assertEquals(2, r.history)
        assertEquals(old.bookmarks(), fresh.bookmarks())
        assertEquals(old.history(), fresh.history())
        assertEquals(old.readSet(), fresh.readSet())
        assertEquals(0.4f, fresh.scrollFor("scp-7008"), 0.0001f)
        assertEquals("scp-7004/offset/1", fresh.lastSlug)
        // Settings keep their exact types (int vs float vs boolean).
        assertEquals(3, fresh.theme); assertEquals(140, fresh.textZoom)
        assertEquals(1.8f, fresh.lineHeight, 0.0001f); assertTrue(fresh.fullscreen); assertEquals(30, fresh.tapZone)
    }

    @Test
    fun importMergesInsteadOfOverwritingProgress() {
        val backup = phoneLike().exportJson()
        val device = Prefs(FakePrefs()).apply {
            toggleBookmark("scp-682", "SCP-682"); setRead("scp-682", true)
            addHistory("scp-682", "SCP-682"); lastSlug = "scp-682"
        }
        val r = device.importJson(backup)

        assertEquals(setOf("scp-682", "scp-173", "scp-7004"), device.bookmarks().map { it.slug }.toSet())
        assertEquals(setOf("scp-682", "scp-173", "scp-049"), device.readSet())
        assertEquals(3, device.history().size)
        assertEquals("scp-682", device.lastSlug) // the device's own last page wins
        assertEquals(2, r.bookmarks); assertEquals(2, r.read)
        // Importing the same file again adds nothing new.
        val again = device.importJson(backup)
        assertEquals(0, again.bookmarks); assertEquals(0, again.read); assertEquals(0, again.history)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherFiles() {
        Prefs(FakePrefs()).importJson("""{"app":"something.else","values":{}}""")
    }
}
