package com.injectra.browser

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * Applies the developer-tool rules to each request, in the spirit of ModHeader
 * (header add/modify/remove) and Requestly (block / redirect / response rewrite /
 * query-param & URL edits / status override / map-local / delay).
 *
 * How it works: when a rule matches, Injectra re-issues the request itself with
 * OkHttp so it can change the outgoing headers, the URL and the response, then
 * hands the result back to the WebView. Requests that no rule touches are left to
 * the WebView untouched. Because Android's WebView never exposes a request body,
 * rewriting only applies to GET requests (navigations and GET subresources);
 * this matches what a WebView-based tool can do.
 */
class RuleEngine(private val dev: DevStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Rewrites the URL per QUERY / URLREPLACE rules; returns the url unchanged if none apply. */
    fun rewriteUrl(url: String, rules: List<RequestRule>): String {
        var out = url
        for (r in rules) {
            when (r.action) {
                RequestRule.ACTION_URLREPLACE ->
                    if (r.target.isNotEmpty()) out = out.replace(r.target, r.replaceWith)
                RequestRule.ACTION_QUERY -> out = applyQuery(out, r)
            }
        }
        return out
    }

    private fun applyQuery(url: String, r: RequestRule): String {
        return try {
            val uri = Uri.parse(url)
            val params = LinkedHashMap<String, String>()
            for (k in uri.queryParameterNames) params[k] = uri.getQueryParameter(k) ?: ""
            // set: "k=v&k2=v2"
            if (r.target.isNotBlank()) for (pair in r.target.split("&")) {
                val i = pair.indexOf('='); if (i < 0) continue
                params[pair.substring(0, i).trim()] = pair.substring(i + 1)
            }
            // remove: "k,k2"
            if (r.replaceWith.isNotBlank()) for (k in r.replaceWith.split(",")) params.remove(k.trim())
            val b = uri.buildUpon().clearQuery()
            for ((k, v) in params) b.appendQueryParameter(k, v)
            b.build().toString()
        } catch (_: Exception) { url }
    }

    /** Returns a response to use, or null to let the WebView handle the request. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!dev.devToolsEnabled) return null
        val url = request.url?.toString() ?: return null
        if (!url.startsWith("http")) return null
        val method = request.method ?: "GET"
        val reqHeaders = request.requestHeaders ?: emptyMap()

        // Scope gate: outside the configured target scope, never modify traffic.
        if (!dev.inScope(url)) {
            NetLog.add(NetEntry(method, url, 0, "out-of-scope", reqHeaders))
            return null
        }

        val reqRules = dev.requestRules().filter { it.enabled && UrlMatch.matches(it.urlPattern, url) }

        // Block wins outright.
        reqRules.firstOrNull { it.action == RequestRule.ACTION_BLOCK }?.let {
            NetLog.add(NetEntry(method, url, 0, "blocked", reqHeaders))
            return emptyResponse()
        }

        // Map-local: serve a canned body, no network at all (works for any method).
        reqRules.firstOrNull { it.action == RequestRule.ACTION_MAPLOCAL }?.let { r ->
            val ctype = r.replaceWith.ifBlank { "text/html" }
            val mime = ctype.substringBefore(";").trim()
            NetLog.add(NetEntry(method, url, 200, "map-local", reqHeaders))
            return WebResourceResponse(mime, "utf-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream(r.target.toByteArray(Charsets.UTF_8)))
        }

        // Delay this request (cap to keep it sane).
        reqRules.firstOrNull { it.action == RequestRule.ACTION_DELAY }?.let { r ->
            val ms = r.target.trim().toLongOrNull()?.coerceIn(0, 60_000) ?: 0
            if (ms > 0) try { Thread.sleep(ms) } catch (_: InterruptedException) {}
        }

        val headerReq = dev.headerRules().filter { it.enabled && !it.onResponse && UrlMatch.matches(it.urlPattern, url) }
        val headerResp = dev.headerRules().filter { it.enabled && it.onResponse && UrlMatch.matches(it.urlPattern, url) }
        val replaceRules = reqRules.filter { it.action == RequestRule.ACTION_REPLACE }
        val statusRule = reqRules.firstOrNull { it.action == RequestRule.ACTION_STATUS }
        // URL edits (query / urlreplace) on subresources; main-frame handled in MainActivity.
        val urlRules = if (!request.isForMainFrame)
            reqRules.filter { it.action == RequestRule.ACTION_QUERY || it.action == RequestRule.ACTION_URLREPLACE } else emptyList()
        // Main-frame redirects update the address bar in MainActivity; here we redirect subresources.
        val subRedirect = if (!request.isForMainFrame)
            reqRules.firstOrNull { it.action == RequestRule.ACTION_REDIRECT && it.target.isNotBlank() } else null

        val newUrl = rewriteUrl(url, urlRules)
        val needsRewrite = headerReq.isNotEmpty() || headerResp.isNotEmpty() ||
            replaceRules.isNotEmpty() || subRedirect != null || statusRule != null || newUrl != url
        if (!needsRewrite) {
            // Nothing to change; still note it for the inspector, cheaply.
            NetLog.add(NetEntry(method, url, 0, "", reqHeaders))
            return null
        }
        // Only GET can be safely replayed (no body available from WebView).
        if (!method.equals("GET", true)) {
            NetLog.add(NetEntry(method, url, 0, "skipped (non-GET)", reqHeaders))
            return null
        }

        val fetchUrl = subRedirect?.target ?: newUrl
        val note = when {
            subRedirect != null -> "redirected"
            newUrl != url -> "url-edited"
            statusRule != null -> "status"
            replaceRules.isNotEmpty() -> "modified"
            else -> "headers"
        }
        return try {
            runFetch(fetchUrl, url, method, reqHeaders, headerReq, headerResp, replaceRules,
                statusRule?.target?.trim()?.toIntOrNull(), note)
        } catch (e: Exception) {
            NetLog.add(NetEntry(method, url, 0, "error: ${e.message?.take(40)}", reqHeaders))
            null // fall back to the WebView's own request
        }
    }

    private fun runFetch(
        fetchUrl: String, originalUrl: String, method: String,
        original: Map<String, String>,
        headerReq: List<HeaderRule>, headerResp: List<HeaderRule>, replace: List<RequestRule>,
        statusOverride: Int?, noteIn: String
    ): WebResourceResponse {
        val builder = Request.Builder().url(fetchUrl)

        // Start from the headers the WebView would have sent.
        val outHeaders = LinkedHashMap<String, String>()
        for ((k, v) in original) outHeaders[k] = v
        // Forward cookies for the target.
        CookieManager.getInstance().getCookie(fetchUrl)?.let { if (it.isNotBlank()) outHeaders["Cookie"] = it }
        // Apply ModHeader-style request rules.
        for (r in headerReq) {
            if (r.header.isBlank()) continue
            val key = outHeaders.keys.firstOrNull { it.equals(r.header, true) } ?: r.header
            if (r.remove) outHeaders.remove(key) else outHeaders[r.header] = r.value
        }
        outHeaders.remove("Accept-Encoding") // let OkHttp manage compression/decompression
        for ((k, v) in outHeaders) { try { builder.header(k, v) } catch (_: Exception) {} }

        client.newCall(builder.get().build()).execute().use { resp ->
            // Save any cookies the server set.
            for (sc in resp.headers("Set-Cookie")) CookieManager.getInstance().setCookie(originalUrl, sc)
            CookieManager.getInstance().flush()

            val ctype = resp.header("Content-Type") ?: "text/html"
            val mime = ctype.substringBefore(";").trim().ifEmpty { "text/html" }
            val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                .find(ctype)?.groupValues?.get(1)?.trim() ?: "utf-8"

            val respHeaders = LinkedHashMap<String, String>()
            for (name in resp.headers.names()) {
                if (name.equals("Content-Encoding", true) || name.equals("Content-Length", true) ||
                    name.equals("Transfer-Encoding", true)) continue
                respHeaders[name] = resp.headers(name).joinToString(", ")
            }
            for (r in headerResp) {
                if (r.header.isBlank()) continue
                if (r.remove) respHeaders.keys.filter { it.equals(r.header, true) }.forEach { respHeaders.remove(it) }
                else respHeaders[r.header] = r.value
            }

            var bytes = resp.body?.bytes() ?: ByteArray(0)
            val textLike = mime.startsWith("text/") || mime.contains("json") ||
                mime.contains("javascript") || mime.contains("xml") || mime.contains("css") || mime.contains("html")
            if (replace.isNotEmpty() && textLike) {
                var body = String(bytes, charset(charset))
                for (r in replace) if (r.target.isNotEmpty()) body = body.replace(r.target, r.replaceWith)
                bytes = body.toByteArray(charset(charset))
            }

            val code = statusOverride ?: resp.code
            NetLog.add(NetEntry(method, originalUrl, code, noteIn, original))
            val reason = if (statusOverride != null) "Overridden" else resp.message.ifBlank { "OK" }
            return WebResourceResponse(mime, charset, code, reason, respHeaders, ByteArrayInputStream(bytes))
        }
    }

    private fun charset(name: String): java.nio.charset.Charset =
        try { java.nio.charset.Charset.forName(name) } catch (_: Exception) { Charsets.UTF_8 }

    private fun emptyResponse() =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
