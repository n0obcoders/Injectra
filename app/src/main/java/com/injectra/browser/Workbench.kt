package com.injectra.browser

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The "Burpsuite" workbench: a manual request Repeater, an Encoder/Decoder and a
 * text Comparer. These act only on traffic you drive from this browser — they are
 * standard web-testing tools for sites you control, not a system-wide proxy.
 *
 * A real intercepting proxy (Burp) sits below every app and MITMs all HTTPS with
 * its own CA; a sandboxed WebView app can only see and replay its own requests.
 */
object Repeater {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)   // show redirects rather than chasing them, like Burp
        .build()

    data class Result(
        val ok: Boolean,
        val statusLine: String,
        val headers: String,
        val body: String,
        val millis: Long
    )

    /**
     * Sends one request and returns the raw response. [headersText] is one
     * "Name: value" per line. [body] is sent for methods that take one.
     */
    fun send(method: String, url: String, headersText: String, body: String): Result {
        val started = System.currentTimeMillis()
        return try {
            val b = Request.Builder().url(url.trim())
            var contentType = "text/plain; charset=utf-8"
            for (line in headersText.lines()) {
                val i = line.indexOf(':'); if (i <= 0) continue
                val name = line.substring(0, i).trim()
                val value = line.substring(i + 1).trim()
                if (name.isEmpty()) continue
                if (name.equals("Content-Type", true)) contentType = value
                if (name.equals("Host", true) || name.equals("Content-Length", true)) continue
                try { b.header(name, value) } catch (_: Exception) {}
            }
            val m = method.trim().uppercase().ifEmpty { "GET" }
            val hasBody = m in setOf("POST", "PUT", "PATCH", "DELETE")
            val reqBody = if (hasBody) body.toByteArray(Charsets.UTF_8)
                .toRequestBody(contentType.toMediaTypeOrNull()) else null
            b.method(m, reqBody)

            client.newCall(b.build()).execute().use { resp ->
                val sb = StringBuilder()
                for (name in resp.headers.names())
                    for (v in resp.headers(name)) sb.append(name).append(": ").append(v).append('\n')
                val bodyStr = resp.body?.string() ?: ""
                Result(true,
                    "HTTP ${resp.code} ${resp.message}".trim(),
                    sb.toString().trimEnd(),
                    bodyStr,
                    System.currentTimeMillis() - started)
            }
        } catch (e: Exception) {
            Result(false, "Request failed", "", e.message ?: e.toString(),
                System.currentTimeMillis() - started)
        }
    }
}

/** Encoder/Decoder, like Burp's Decoder tab. */
object Codec {
    fun base64Encode(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun base64Decode(s: String): String =
        try { String(Base64.decode(s.trim(), Base64.DEFAULT), Charsets.UTF_8) }
        catch (e: Exception) { "[decode error: ${e.message}]" }

    fun urlEncode(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8") } catch (e: Exception) { "[error: ${e.message}]" }

    fun urlDecode(s: String): String =
        try { java.net.URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { "[error: ${e.message}]" }

    fun htmlEncode(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c)
        }
    }

    fun htmlDecode(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")

    fun hexEncode(s: String): String =
        s.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02x".format(it) }

    fun hexDecode(s: String): String = try {
        val clean = s.replace(Regex("[^0-9a-fA-F]"), "")
        String(ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, Charsets.UTF_8)
    } catch (e: Exception) { "[decode error: ${e.message}]" }

    // ---- v1.6 additions ----------------------------------------------------

    fun hash(algo: String, s: String): String = try {
        val md = java.security.MessageDigest.getInstance(algo)
        md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { "[error: ${e.message}]" }

    /** Decodes a JWT's header and payload (does NOT verify the signature). */
    fun jwtDecode(s: String): String {
        val parts = s.trim().split(".")
        if (parts.size < 2) return "[not a JWT: expected header.payload.signature]"
        fun seg(b: String) = try {
            String(Base64.decode(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        } catch (e: Exception) { "[decode error: ${e.message}]" }
        val header = prettyJson(seg(parts[0]))
        val payload = prettyJson(seg(parts[1]))
        val sig = if (parts.size > 2) parts[2] else "(none)"
        return "HEADER:\n$header\n\nPAYLOAD:\n$payload\n\nSIGNATURE (not verified):\n$sig"
    }

    fun prettyJson(s: String): String = try {
        val t = s.trim()
        if (t.startsWith("[")) org.json.JSONArray(t).toString(2)
        else org.json.JSONObject(t).toString(2)
    } catch (e: Exception) { "[invalid JSON: ${e.message}]" }

    fun minifyJson(s: String): String = try {
        val t = s.trim()
        if (t.startsWith("[")) org.json.JSONArray(t).toString()
        else org.json.JSONObject(t).toString()
    } catch (e: Exception) { "[invalid JSON: ${e.message}]" }

    fun unicodeEscape(s: String): String = buildString {
        for (c in s) if (c.code in 32..126) append(c) else append("\\u%04x".format(c.code))
    }

    fun unicodeUnescape(s: String): String = try {
        Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
    } catch (e: Exception) { "[error: ${e.message}]" }

    /** Converts between a unix timestamp (s or ms) and a human-readable UTC time. */
    fun timestamp(s: String): String {
        val n = s.trim().toLongOrNull() ?: return "[enter a unix timestamp in seconds or milliseconds]"
        val ms = if (n < 100_000_000_000L) n * 1000 else n
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }
}

/** Line-by-line comparer, like Burp's Comparer. */
object TextDiff {
    fun diff(a: String, b: String): String {
        val la = a.lines(); val lb = b.lines()
        val sb = StringBuilder()
        val max = maxOf(la.size, lb.size)
        var same = 0
        for (i in 0 until max) {
            val x = la.getOrNull(i); val y = lb.getOrNull(i)
            when {
                x == y -> { same++ }
                x == null -> sb.append("+ ${i + 1}: ${y}\n")
                y == null -> sb.append("- ${i + 1}: ${x}\n")
                else -> { sb.append("- ${i + 1}: ${x}\n"); sb.append("+ ${i + 1}: ${y}\n") }
            }
        }
        val header = "A: ${la.size} lines   B: ${lb.size} lines   $same identical\n" +
            "──────────────────────────────\n"
        return header + (if (sb.isEmpty()) "(no differences)" else sb.toString().trimEnd())
    }
}
