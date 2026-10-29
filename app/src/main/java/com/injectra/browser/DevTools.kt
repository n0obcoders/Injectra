package com.injectra.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistence and models for the developer tools: header rules (like ModHeader)
 * and request/response rules (like Requestly), plus an in-memory network log
 * (like Requestly's network inspector).
 */

/** A request- or response-header modification, in the spirit of ModHeader. */
data class HeaderRule(
    val id: String = UUID.randomUUID().toString(),
    var enabled: Boolean = true,
    var name: String = "",
    var urlPattern: String = "*",   // * wildcards; empty/"*" = all urls
    var onResponse: Boolean = false, // false = request header, true = response header
    var remove: Boolean = false,     // true = remove the header, else set it
    var header: String = "",
    var value: String = ""
)

/** A Requestly-style rule that blocks, redirects, rewrites the body, or injects code. */
data class RequestRule(
    val id: String = UUID.randomUUID().toString(),
    var enabled: Boolean = true,
    var name: String = "",
    var urlPattern: String = "",
    var action: String = ACTION_BLOCK,   // see constants below
    var target: String = "",             // redirect url / find text / script body
    var replaceWith: String = "",        // replacement text for REPLACE
    var isCss: Boolean = false           // for INSERT: css vs js
) {
    companion object {
        const val ACTION_BLOCK = "block"
        const val ACTION_REDIRECT = "redirect"
        const val ACTION_REPLACE = "replace"      // find target -> replaceWith in response body
        const val ACTION_INSERT = "insert"        // inject js/css on matching pages
        const val ACTION_QUERY = "query"          // modify URL query params (set target "k=v&..", remove replaceWith "k,k2")
        const val ACTION_URLREPLACE = "urlreplace" // replace substring target -> replaceWith in the URL
        const val ACTION_STATUS = "status"        // override response status code (target = code)
        const val ACTION_MAPLOCAL = "maplocal"    // serve target as the whole response body (replaceWith = content-type)
        const val ACTION_DELAY = "delay"          // delay the request by target milliseconds
    }
}

/** One captured request, for the network inspector. */
data class NetEntry(
    val method: String,
    val url: String,
    var status: Int = 0,
    var note: String = "",   // "blocked", "redirected", "headers", "modified", ""
    val reqHeaders: Map<String, String> = emptyMap(),  // headers as the WebView would send them
    val time: Long = System.currentTimeMillis()
)

object NetLog {
    private const val CAP = 500
    private val entries = ArrayDeque<NetEntry>()

    @Synchronized fun add(e: NetEntry) {
        entries.addFirst(e)
        while (entries.size > CAP) entries.removeLast()
    }
    @Synchronized fun snapshot(): List<NetEntry> = entries.toList()
    @Synchronized fun clear() = entries.clear()
}

/** Matches urls against a pattern that may contain '*' wildcards; empty/"*" matches all. */
object UrlMatch {
    fun matches(pattern: String, url: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty() || p == "*") return true
        val u = url.lowercase()
        val pl = p.lowercase()
        if (!pl.contains("*")) return u.contains(pl)
        val regex = buildString {
            append("^.*")
            for (part in pl.split("*")) append(Regex.escape(part)).append(".*")
        }.let { it.dropLast(2) /* trailing .* already */ }
        return try { Regex(regex).containsMatchIn(u) } catch (_: Exception) { u.contains(pl.replace("*", "")) }
    }
}

class DevStore(context: Context) {
    private val prefs = context.getSharedPreferences("injectra", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HEADER_RULES = "header_rules"
        private const val KEY_REQUEST_RULES = "request_rules"
        const val KEY_DEVTOOLS = "devtools_enabled"
        private const val KEY_SCOPE_INCLUDE = "scope_include"
        private const val KEY_SCOPE_EXCLUDE = "scope_exclude"
    }

    var devToolsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEVTOOLS, true)
        set(v) = prefs.edit().putBoolean(KEY_DEVTOOLS, v).apply()

    // ---- Target & scope ----------------------------------------------------
    // One pattern per line. Empty include = everything is in scope.

    var scopeInclude: String
        get() = prefs.getString(KEY_SCOPE_INCLUDE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SCOPE_INCLUDE, v).apply()

    var scopeExclude: String
        get() = prefs.getString(KEY_SCOPE_EXCLUDE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SCOPE_EXCLUDE, v).apply()

    /** True if [url] is allowed by the scope (include/exclude lists of * patterns). */
    fun inScope(url: String): Boolean {
        val inc = scopeInclude.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val exc = scopeExclude.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (exc.any { UrlMatch.matches(it, url) }) return false
        if (inc.isEmpty()) return true
        return inc.any { UrlMatch.matches(it, url) }
    }

    // ---- Header rules ------------------------------------------------------

    fun headerRules(): MutableList<HeaderRule> {
        val out = mutableListOf<HeaderRule>()
        val arr = read(KEY_HEADER_RULES)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(HeaderRule(
                o.optString("id", UUID.randomUUID().toString()),
                o.optBoolean("enabled", true), o.optString("name"),
                o.optString("urlPattern", "*"), o.optBoolean("onResponse"),
                o.optBoolean("remove"), o.optString("header"), o.optString("value")
            ))
        }
        return out
    }

    fun saveHeaderRules(list: List<HeaderRule>) {
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject()
            .put("id", r.id).put("enabled", r.enabled).put("name", r.name)
            .put("urlPattern", r.urlPattern).put("onResponse", r.onResponse)
            .put("remove", r.remove).put("header", r.header).put("value", r.value))
        prefs.edit().putString(KEY_HEADER_RULES, arr.toString()).apply()
    }

    // ---- Request rules -----------------------------------------------------

    fun requestRules(): MutableList<RequestRule> {
        val out = mutableListOf<RequestRule>()
        val arr = read(KEY_REQUEST_RULES)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(RequestRule(
                o.optString("id", UUID.randomUUID().toString()),
                o.optBoolean("enabled", true), o.optString("name"),
                o.optString("urlPattern"), o.optString("action", RequestRule.ACTION_BLOCK),
                o.optString("target"), o.optString("replaceWith"), o.optBoolean("isCss")
            ))
        }
        return out
    }

    fun saveRequestRules(list: List<RequestRule>) {
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject()
            .put("id", r.id).put("enabled", r.enabled).put("name", r.name)
            .put("urlPattern", r.urlPattern).put("action", r.action)
            .put("target", r.target).put("replaceWith", r.replaceWith).put("isCss", r.isCss))
        prefs.edit().putString(KEY_REQUEST_RULES, arr.toString()).apply()
    }

    /** Enabled INSERT rules whose pattern matches [url]; used to inject js/css after load. */
    fun insertRulesFor(url: String): List<RequestRule> =
        requestRules().filter { it.enabled && it.action == RequestRule.ACTION_INSERT && UrlMatch.matches(it.urlPattern, url) }

    private fun read(key: String): JSONArray =
        try { JSONArray(prefs.getString(key, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
}
