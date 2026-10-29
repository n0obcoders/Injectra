package com.injectra.browser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A visited page kept in history. */
data class HistoryEntry(val url: String, val title: String, val time: Long)

/** A saved bookmark. */
data class Bookmark(val url: String, val title: String)

/**
 * Lightweight persistence backed by SharedPreferences + JSON.
 * Small lists only (history capped), so no SQLite needed and reads/writes stay
 * off the UI-critical path for typical sizes.
 */
class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("injectra", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HISTORY = "history"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val HISTORY_CAP = 1000

        const val KEY_HOMEPAGE = "homepage"
        const val KEY_SEARCH = "search_engine"
        const val KEY_JS = "javascript"
        const val KEY_DESKTOP_DEFAULT = "desktop_default"
        const val KEY_BLOCK_IMAGES = "block_images"
        const val KEY_SHIELDS = "shields"
        const val KEY_HTTPS_ONLY = "https_only"
        const val KEY_BLOCK_3P_COOKIES = "block_3p_cookies"
        const val KEY_BLOCK_POPUPS = "block_popups"

        // The browser opens the developer's portfolio by default; changeable in Settings.
        const val DEFAULT_HOMEPAGE = "https://n0obcoders.github.io/portfoli0/"
        const val PORTFOLIO = "https://n0obcoders.github.io/portfoli0/"
        val DEV_PROJECTS = linkedMapOf(
            "Portfolio" to "https://n0obcoders.github.io/portfoli0/",
            "Packetra" to "https://github.com/n0obcoders/packetra",
            "n3-editor" to "https://github.com/n0obcoders/n3-editor",
            "Sessiongram" to "https://github.com/n0obcoders/sessiongram"
        )
        // Search engine templates keyed by display name.
        val SEARCH_ENGINES = linkedMapOf(
            "Google" to "https://www.google.com/search?q=",
            "Bing" to "https://www.bing.com/search?q=",
            "DuckDuckGo" to "https://duckduckgo.com/?q=",
            "Brave" to "https://search.brave.com/search?q=",
            "Startpage" to "https://www.startpage.com/sp/search?query="
        )
    }

    // ---- Settings ----------------------------------------------------------

    var homepage: String
        get() = prefs.getString(KEY_HOMEPAGE, DEFAULT_HOMEPAGE) ?: DEFAULT_HOMEPAGE
        set(v) = prefs.edit().putString(KEY_HOMEPAGE, v).apply()

    var searchEngine: String
        get() = prefs.getString(KEY_SEARCH, "Google") ?: "Google"
        set(v) = prefs.edit().putString(KEY_SEARCH, v).apply()

    fun searchTemplate(): String =
        SEARCH_ENGINES[searchEngine] ?: SEARCH_ENGINES["Google"]!!

    var javaScriptEnabled: Boolean
        get() = prefs.getBoolean(KEY_JS, true)
        set(v) = prefs.edit().putBoolean(KEY_JS, v).apply()

    var desktopByDefault: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_DEFAULT, false)
        set(v) = prefs.edit().putBoolean(KEY_DESKTOP_DEFAULT, v).apply()

    var blockImages: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_IMAGES, false)
        set(v) = prefs.edit().putBoolean(KEY_BLOCK_IMAGES, v).apply()

    var shieldsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIELDS, true)
        set(v) = prefs.edit().putBoolean(KEY_SHIELDS, v).apply()

    var httpsOnly: Boolean
        get() = prefs.getBoolean(KEY_HTTPS_ONLY, true)
        set(v) = prefs.edit().putBoolean(KEY_HTTPS_ONLY, v).apply()

    var blockThirdPartyCookies: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_3P_COOKIES, false)
        set(v) = prefs.edit().putBoolean(KEY_BLOCK_3P_COOKIES, v).apply()

    var blockPopups: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_POPUPS, true)
        set(v) = prefs.edit().putBoolean(KEY_BLOCK_POPUPS, v).apply()

    // ---- History -----------------------------------------------------------

    fun addHistory(url: String, title: String) {
        if (url.isBlank() || url.startsWith("data:") || url.startsWith("about:")) return
        val list = history().toMutableList()
        // Drop an immediately-preceding duplicate of the same url.
        if (list.isNotEmpty() && list.first().url == url) {
            list[0] = HistoryEntry(url, title, System.currentTimeMillis())
        } else {
            list.add(0, HistoryEntry(url, title, System.currentTimeMillis()))
        }
        while (list.size > HISTORY_CAP) list.removeAt(list.size - 1)
        saveHistory(list)
    }

    fun history(): List<HistoryEntry> {
        val arr = readArray(KEY_HISTORY)
        val out = ArrayList<HistoryEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(HistoryEntry(o.optString("url"), o.optString("title"), o.optLong("time")))
        }
        return out
    }

    fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    private fun saveHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        for (h in list) {
            arr.put(JSONObject().put("url", h.url).put("title", h.title).put("time", h.time))
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    // ---- Bookmarks ---------------------------------------------------------

    fun bookmarks(): List<Bookmark> {
        val arr = readArray(KEY_BOOKMARKS)
        val out = ArrayList<Bookmark>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Bookmark(o.optString("url"), o.optString("title")))
        }
        return out
    }

    fun isBookmarked(url: String): Boolean = bookmarks().any { it.url == url }

    fun toggleBookmark(url: String, title: String): Boolean {
        if (url.isBlank()) return false
        val list = bookmarks().toMutableList()
        val existing = list.indexOfFirst { it.url == url }
        val nowBookmarked: Boolean
        if (existing >= 0) {
            list.removeAt(existing); nowBookmarked = false
        } else {
            list.add(0, Bookmark(url, title.ifBlank { url })); nowBookmarked = true
        }
        saveBookmarks(list)
        return nowBookmarked
    }

    fun removeBookmark(url: String) {
        val list = bookmarks().toMutableList()
        list.removeAll { it.url == url }
        saveBookmarks(list)
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().put("url", b.url).put("title", b.title))
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    // ---- helpers -----------------------------------------------------------

    private fun readArray(key: String): JSONArray =
        try { JSONArray(prefs.getString(key, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
}
