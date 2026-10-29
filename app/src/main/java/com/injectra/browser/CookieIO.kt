package com.injectra.browser

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One cookie, normalized across the JSON and Netscape formats. */
data class ParsedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val expiresEpochSec: Long? = null
)

object CookieIO {

    // ---- Parsing -----------------------------------------------------------

    /** JSON: an array of cookie objects, or { "cookies": [ ... ] }. */
    fun parseJson(text: String): List<ParsedCookie> {
        val out = mutableListOf<ParsedCookie>()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return out
        val arr: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                if (obj.has("cookies")) obj.getJSONArray("cookies") else JSONArray().put(obj)
            }
            else -> return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = firstNonEmpty(o, "name", "Name")
            val domain = firstNonEmpty(o, "domain", "Domain", "host")
            if (name.isEmpty() || domain.isEmpty()) continue
            val value = firstNonEmpty(o, "value", "Value")
            val path = firstNonEmpty(o, "path", "Path").ifEmpty { "/" }
            val secure = o.optBoolean("secure", o.optBoolean("Secure", false))
            val httpOnly = o.optBoolean("httpOnly", o.optBoolean("httponly", o.optBoolean("HttpOnly", false)))
            var exp: Long? = null
            for (k in listOf("expirationDate", "expiry", "expires")) {
                if (o.has(k) && !o.isNull(k)) {
                    val d = o.optDouble(k, 0.0)
                    if (d > 0) exp = d.toLong()
                    break
                }
            }
            out.add(ParsedCookie(name, value, domain, path, secure, httpOnly, exp))
        }
        return out
    }

    private fun firstNonEmpty(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    /**
     * Netscape cookies.txt:
     * domain \t includeSubdomains \t path \t secure \t expiry \t name \t value
     */
    fun parseNetscape(text: String): List<ParsedCookie> {
        val out = mutableListOf<ParsedCookie>()
        for (rawLine in text.split("\n")) {
            var line = rawLine.trimEnd('\r')
            if (line.isBlank()) continue
            var httpOnly = false
            if (line.startsWith("#HttpOnly_")) {
                httpOnly = true
                line = line.substring("#HttpOnly_".length)
            } else if (line.startsWith("#")) {
                continue
            }
            val parts = line.split("\t")
            if (parts.size < 7) continue
            val domain = parts[0]
            val path = parts[2].ifEmpty { "/" }
            val secure = parts[3].equals("TRUE", ignoreCase = true)
            val expiry = parts[4].toLongOrNull()?.takeIf { it > 0 }
            val name = parts[5]
            val value = parts[6]
            if (name.isEmpty() || domain.isEmpty()) continue
            out.add(ParsedCookie(name, value, domain, path, secure, httpOnly, expiry))
        }
        return out
    }

    // ---- Serialization -----------------------------------------------------

    fun toJson(cookies: List<ParsedCookie>): String {
        val arr = JSONArray()
        for (c in cookies) {
            val o = JSONObject()
            o.put("domain", c.domain)
            o.put("name", c.name)
            o.put("value", c.value)
            o.put("path", c.path)
            o.put("secure", c.secure)
            o.put("httpOnly", c.httpOnly)
            if (c.expiresEpochSec != null) o.put("expirationDate", c.expiresEpochSec)
            arr.put(o)
        }
        return arr.toString(2)
    }

    fun toNetscape(cookies: List<ParsedCookie>): String {
        val sb = StringBuilder()
        sb.append("# Netscape HTTP Cookie File\n")
        sb.append("# Exported by Injectra\n\n")
        for (c in cookies) {
            val prefix = if (c.httpOnly) "#HttpOnly_" else ""
            val includeSub = if (c.domain.startsWith(".")) "TRUE" else "FALSE"
            val secure = if (c.secure) "TRUE" else "FALSE"
            val expiry = c.expiresEpochSec ?: 0L
            sb.append(prefix + c.domain).append('\t')
                .append(includeSub).append('\t')
                .append(c.path).append('\t')
                .append(secure).append('\t')
                .append(expiry).append('\t')
                .append(c.name).append('\t')
                .append(c.value).append('\n')
        }
        return sb.toString()
    }

    // ---- Helpers for WebView CookieManager ---------------------------------

    /** The URL a cookie is scoped to, used when calling CookieManager.setCookie. */
    fun urlFor(c: ParsedCookie): String {
        val host = c.domain.removePrefix(".")
        val scheme = if (c.secure) "https" else "http"
        return "$scheme://$host${if (c.path.startsWith("/")) c.path else "/" + c.path}"
    }

    /** A Set-Cookie style string for CookieManager.setCookie. */
    fun setCookieString(c: ParsedCookie): String {
        val sb = StringBuilder()
        sb.append(c.name).append('=').append(c.value)
        sb.append("; Path=").append(c.path)
        sb.append("; Domain=").append(c.domain)
        if (c.expiresEpochSec != null) sb.append("; Expires=").append(gmtDate(c.expiresEpochSec))
        if (c.secure) sb.append("; Secure")
        if (c.httpOnly) sb.append("; HttpOnly")
        return sb.toString()
    }

    private fun gmtDate(epochSec: Long): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date(epochSec * 1000))
    }
}
