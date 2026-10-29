package com.injectra.browser

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A PASSIVE web security scanner. It only reads what a page already returns —
 * it never sends crafted/attack traffic, never fuzzes, and never probes for
 * vulnerabilities actively. Active exploitation and high-volume testing are
 * intentionally not implemented (see the capability matrix).
 */
data class Finding(
    val severity: String,   // "High" | "Medium" | "Low" | "Info"
    val title: String,
    val url: String,
    val evidence: String,
    val remediation: String
)

object Scanner {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val sevRank = mapOf("High" to 0, "Medium" to 1, "Low" to 2, "Info" to 3)

    /** Fetches [url] once (GET) and runs passive checks on the response. */
    fun scan(url: String): List<Finding> {
        val out = mutableListOf<Finding>()
        if (!url.startsWith("http")) return out
        val isHttps = url.startsWith("https://")
        if (!isHttps) out.add(Finding("Medium", "Site served over HTTP",
            url, "The page was loaded over an unencrypted http:// connection.",
            "Serve the site over HTTPS and redirect http to https."))
        try {
            client.newCall(Request.Builder().url(url).header("Accept", "*/*").build()).execute().use { r ->
                fun h(name: String) = r.header(name)
                // Security headers
                if (isHttps && h("Strict-Transport-Security") == null)
                    out.add(Finding("Medium", "Missing HSTS header", url,
                        "No Strict-Transport-Security response header.",
                        "Add Strict-Transport-Security with a long max-age."))
                if (h("Content-Security-Policy") == null)
                    out.add(Finding("Medium", "Missing Content-Security-Policy", url,
                        "No Content-Security-Policy header — limited XSS mitigation.",
                        "Define a restrictive CSP for the site."))
                if (h("X-Content-Type-Options")?.contains("nosniff", true) != true)
                    out.add(Finding("Low", "Missing X-Content-Type-Options: nosniff", url,
                        "Responses may be MIME-sniffed by the browser.",
                        "Send X-Content-Type-Options: nosniff."))
                if (h("X-Frame-Options") == null && h("Content-Security-Policy")?.contains("frame-ancestors", true) != true)
                    out.add(Finding("Low", "No clickjacking protection", url,
                        "Neither X-Frame-Options nor CSP frame-ancestors is set.",
                        "Send X-Frame-Options: DENY or a CSP frame-ancestors directive."))
                if (h("Referrer-Policy") == null)
                    out.add(Finding("Info", "No Referrer-Policy", url,
                        "Default referrer behaviour may leak URLs to third parties.",
                        "Set a Referrer-Policy such as strict-origin-when-cross-origin."))
                h("Server")?.let { if (it.any { c -> c.isDigit() }) out.add(Finding("Info",
                    "Server version disclosed", url, "Server header: $it",
                    "Suppress version details in the Server header.")) }
                // Cookie flags
                for (sc in r.headers("Set-Cookie")) {
                    val name = sc.substringBefore('=').trim()
                    val low = sc.lowercase()
                    if (isHttps && !low.contains("secure"))
                        out.add(Finding("Medium", "Cookie without Secure flag", url,
                            "Set-Cookie \"$name\" has no Secure attribute.",
                            "Add the Secure attribute so the cookie is only sent over HTTPS."))
                    if (!low.contains("httponly"))
                        out.add(Finding("Low", "Cookie without HttpOnly flag", url,
                            "Set-Cookie \"$name\" has no HttpOnly attribute.",
                            "Add HttpOnly so JavaScript cannot read the cookie."))
                    if (!low.contains("samesite"))
                        out.add(Finding("Info", "Cookie without SameSite", url,
                            "Set-Cookie \"$name\" has no SameSite attribute.",
                            "Set SameSite=Lax or Strict to reduce CSRF exposure."))
                }
                // Mixed content hint (passive: look at the HTML)
                if (isHttps) {
                    val body = r.peekBody(512 * 1024).string()
                    val mixed = Regex("""(?:src|href)\s*=\s*["']http://[^"']+""", RegexOption.IGNORE_CASE)
                        .findAll(body).map { it.value }.take(3).toList()
                    if (mixed.isNotEmpty()) out.add(Finding("Medium", "Mixed content", url,
                        "HTTPS page references http:// resources, e.g. ${mixed.first()}",
                        "Load all sub-resources over HTTPS."))
                }
            }
        } catch (e: Exception) {
            out.add(Finding("Info", "Scan could not complete", url,
                "Error: ${e.message}", "Check connectivity and that the host is in scope."))
        }
        if (out.isEmpty()) out.add(Finding("Info", "No passive issues found", url,
            "The passive checks did not flag anything.", "Keep headers and cookie flags in place."))
        return out.sortedBy { sevRank[it.severity] ?: 9 }
    }
}

/**
 * Token sequencer: basic statistical summaries for a set of tokens (e.g. session
 * ids) you supply from your own authorized testing. It reports length and
 * character spread and a rough Shannon-entropy estimate. It does NOT prove
 * cryptographic strength — treat it as a smell test only.
 */
object Sequencer {
    fun analyse(tokens: List<String>): String {
        val toks = tokens.map { it.trim() }.filter { it.isNotEmpty() }
        if (toks.size < 2) return "Provide at least 2 tokens (one per line)."
        val lens = toks.map { it.length }
        val unique = toks.toSet().size
        val charCounts = HashMap<Char, Int>()
        var total = 0
        for (t in toks) for (c in t) { charCounts[c] = (charCounts[c] ?: 0) + 1; total++ }
        var entropyPerChar = 0.0
        for ((_, n) in charCounts) { val p = n.toDouble() / total; entropyPerChar -= p * (Math.log(p) / Math.log(2.0)) }
        val avgLen = lens.average()
        val sb = StringBuilder()
        sb.append("Samples: ${toks.size}  (unique: $unique)\n")
        if (unique < toks.size) sb.append("⚠ ${toks.size - unique} duplicate token(s) — a red flag for randomness.\n")
        sb.append("Length: min ${lens.min()}  max ${lens.max()}  avg ${"%.1f".format(avgLen)}\n")
        sb.append("Distinct characters: ${charCounts.size}\n")
        sb.append("Entropy estimate: ${"%.2f".format(entropyPerChar)} bits/char")
        if (avgLen > 0) sb.append("  (~${"%.0f".format(entropyPerChar * avgLen)} bits/token)")
        sb.append("\n")
        if (toks.size < 100) sb.append("\nNote: ${toks.size} samples is far too few to judge randomness; collect thousands.\n")
        sb.append("\nThis is a heuristic, not a proof of cryptographic strength.")
        return sb.toString()
    }
}

/** HAR (HTTP Archive) export of the captured history, and copy-as-cURL. */
object HarExport {
    fun toHar(entries: List<NetEntry>): String {
        val log = JSONObject()
        log.put("version", "1.2")
        log.put("creator", JSONObject().put("name", "Injectra").put("version", "1.6"))
        val arr = JSONArray()
        for (e in entries) {
            val headers = JSONArray()
            for ((k, v) in e.reqHeaders) headers.put(JSONObject().put("name", k).put("value", v))
            val req = JSONObject()
                .put("method", e.method).put("url", e.url)
                .put("httpVersion", "HTTP/1.1").put("headers", headers)
                .put("queryString", JSONArray()).put("headersSize", -1).put("bodySize", -1)
            val resp = JSONObject()
                .put("status", e.status).put("statusText", e.note)
                .put("httpVersion", "HTTP/1.1").put("headers", JSONArray())
                .put("content", JSONObject().put("size", 0).put("mimeType", ""))
                .put("redirectURL", "").put("headersSize", -1).put("bodySize", -1)
            arr.put(JSONObject()
                .put("startedDateTime", iso(e.time))
                .put("time", 0)
                .put("request", req).put("response", resp)
                .put("cache", JSONObject())
                .put("timings", JSONObject().put("send", 0).put("wait", 0).put("receive", 0)))
        }
        log.put("entries", arr)
        return JSONObject().put("log", log).toString(2)
    }

    fun curl(e: NetEntry): String {
        val sb = StringBuilder("curl -X ${e.method} '${e.url}'")
        for ((k, v) in e.reqHeaders) sb.append(" \\\n  -H '$k: ${v.replace("'", "'\\''")}'")
        return sb.toString()
    }

    private fun iso(ms: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }
}
