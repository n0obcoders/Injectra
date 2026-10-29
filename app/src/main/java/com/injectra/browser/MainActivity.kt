package com.injectra.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

open class MainActivity : AppCompatActivity() {

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        private const val PRIVATE_HOME = "about:blank"

        // Injected at document start in desktop mode so touch-detecting sites
        // (Netflix, Prime Video, etc.) serve their desktop layout and player.
        const val DESKTOP_SPOOF_JS = """
            (function(){try{
              Object.defineProperty(navigator,'maxTouchPoints',{get:function(){return 0;},configurable:true});
              Object.defineProperty(navigator,'platform',{get:function(){return 'Win32';},configurable:true});
              Object.defineProperty(navigator,'userAgentData',{get:function(){return undefined;},configurable:true});
              try{delete window.ontouchstart;}catch(e){}
              Object.defineProperty(window,'ontouchstart',{get:function(){return undefined;},configurable:true});
              var mm=window.matchMedia;
              window.matchMedia=function(q){var r=mm(q);if(/pointer:\s*coarse|hover:\s*none|any-pointer:\s*coarse/.test(q)){try{Object.defineProperty(r,'matches',{get:function(){return false;}});}catch(e){}}return r;};
            }catch(e){}})();
        """
    }

    /** True in PrivateActivity, which runs in its own process with its own cookie jar. */
    protected open val isPrivate: Boolean = false

    private class Tab(val webView: WebView, val chip: View, val titleView: TextView) {
        var title: String = "New Tab"
        var desktop: Boolean = false
        var spoofScript: androidx.webkit.ScriptHandler? = null
        val blocked = AtomicInteger(0)
    }

    private fun applyInsertRules(view: WebView, url: String) {
        for (r in dev.insertRulesFor(url)) {
            if (r.target.isBlank()) continue
            val js = if (r.isCss) {
                val css = r.target.replace("\\", "\\\\").replace("`", "\\`")
                "(function(){var s=document.createElement('style');s.textContent=`$css`;document.head.appendChild(s);})();"
            } else r.target
            try { view.evaluateJavascript(js, null) } catch (_: Exception) {}
        }
    }

    private val tabs = mutableListOf<Tab>()
    private var currentIndex = -1

    private lateinit var store: Store
    private lateinit var dev: DevStore
    private lateinit var ruleEngine: RuleEngine
    private lateinit var webContainer: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var urlBar: AutoCompleteTextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    // Hosts the user chose to keep on plain http after an https upgrade failed.
    private val httpAllowed = mutableSetOf<String>()
    // Hosts whose current load is an automatic http -> https upgrade.
    private val upgradedHosts = mutableSetOf<String>()

    // Fullscreen video
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // <input type="file"> uploads
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
            fileCallback = null
        }

    // Cookie import/export via the Storage Access Framework.
    private var pendingImportFormat: String = "json"
    private var pendingExportText: String = ""
    private val openCookieFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importCookiesFrom(uri, pendingImportFormat)
        }
    private val createJsonFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) writeTextTo(uri, pendingExportText)
        }
    private val createTxtFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeTextTo(uri, pendingExportText)
        }

    private val homeUrl: String get() = if (isPrivate) PRIVATE_HOME else store.homepage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isPrivate) {
            // Like Chrome Incognito: keep private pages out of screenshots and the recents preview.
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(R.layout.activity_main)
        store = Store(this)
        dev = DevStore(this)
        ruleEngine = RuleEngine(dev)

        webContainer = findViewById(R.id.webContainer)
        tabBar = findViewById(R.id.tabBar)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)

        if (isPrivate) applyPrivateLook()

        CookieManager.getInstance().setAcceptCookie(true)

        findViewById<ImageButton>(R.id.btnReload).setOnClickListener { current()?.webView?.reload() }
        findViewById<ImageButton>(R.id.btnNewTab).setOnClickListener { createTab(homeUrl) }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { current()?.webView?.let { loadInto(it, homeUrl) } }

        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnBack.setOnClickListener { current()?.webView?.let { if (it.canGoBack()) it.goBack() } }
        btnForward.setOnClickListener { current()?.webView?.let { if (it.canGoForward()) it.goForward() } }
        // Long-press back/forward to jump straight to an earlier (or later) page.
        btnBack.setOnLongClickListener { showNavHistory(); true }
        btnForward.setOnLongClickListener { showNavHistory(); true }

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadUserInput(urlBar.text.toString()); true } else false
        }
        setupSuggestions()

        setupFindBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = current()?.webView
                when {
                    customView != null -> exitFullscreen()
                    findBar.visibility == View.VISIBLE -> closeFind()
                    wv != null && wv.canGoBack() -> wv.goBack()
                    tabs.size > 1 -> closeTab(currentIndex)
                    else -> closeWindow()
                }
            }
        })

        createTab(incomingUrl(intent) ?: homeUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUrl(intent)?.let { createTab(it) }
    }

    /** A link handed to us by another app. Only http(s) is accepted. */
    private fun incomingUrl(intent: Intent?): String? {
        val data = intent?.data ?: return null
        return if (data.scheme == "http" || data.scheme == "https") data.toString() else null
    }

    override fun onDestroy() {
        if (isPrivate && isFinishing) erasePrivateData()
        for (t in tabs) t.webView.destroy()
        tabs.clear()
        super.onDestroy()
        // The private process holds nothing worth keeping; ending it guarantees
        // the next private window starts from a wiped data directory.
        if (isPrivate && isFinishing) android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun closeWindow() {
        if (isPrivate) {
            erasePrivateData()
            finishAndRemoveTask()
        } else finish()
    }

    private fun erasePrivateData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        for (t in tabs) { t.webView.clearCache(true); t.webView.clearHistory(); t.webView.clearFormData() }
    }

    private fun applyPrivateLook() {
        val dark = getColor(R.color.private_dark)
        findViewById<View>(R.id.root).setBackgroundColor(getColor(R.color.private_bar))
        findViewById<View>(R.id.tabStrip).setBackgroundColor(dark)
        findViewById<View>(R.id.findBar).setBackgroundColor(dark)
        window.statusBarColor = dark
        window.navigationBarColor = dark
        urlBar.hint = getString(R.string.private_hint)
        findViewById<ImageButton>(R.id.btnHome).setImageResource(R.drawable.ic_private)
    }

    // ---- Tabs --------------------------------------------------------------

    private fun current(): Tab? = tabs.getOrNull(currentIndex)

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(url: String?): Tab {
        val webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        hardenSettings(webView.settings)

        val chip = layoutInflater.inflate(R.layout.tab_item, tabBar, false)
        val titleView = chip.findViewById<TextView>(R.id.tabTitle)
        val tab = Tab(webView, chip, titleView)
        tab.desktop = store.desktopByDefault
        applyUserAgent(tab)

        // Private windows always refuse third-party cookies, like Chrome Incognito.
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView, !(isPrivate || store.blockThirdPartyCookies)
        )

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleNavigation(view, request.url, request.isForMainFrame, request.hasGesture())

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Shields (ad/tracker blocking) applies to subresources first.
                if (!request.isForMainFrame && store.shieldsEnabled) {
                    val host = request.url.host
                    if (host != null && Shields.isBlocked(host)) {
                        tab.blocked.incrementAndGet()
                        return Shields.emptyResponse()
                    }
                }
                // Then the developer rules (ModHeader / Requestly).
                return ruleEngine.intercept(request)
            }

            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                tab.blocked.set(0)
                if (view === current()?.webView) {
                    if (pageUrl != null && !urlBar.hasFocus()) setUrlBarText(displayUrl(pageUrl))
                    updateNavButtons()
                }
            }

            override fun onPageFinished(view: WebView, pageUrl: String?) {
                pageUrl?.let { u -> Uri.parse(u).host?.let { upgradedHosts.remove(it) } }
                if (pageUrl != null && !isPrivate && !pageUrl.startsWith("about:")) {
                    store.addHistory(pageUrl, tab.title)
                }
                if (!isPrivate) CookieManager.getInstance().flush()
                if (tab.desktop) view.evaluateJavascript(DESKTOP_SPOOF_JS, null)
                if (pageUrl != null) applyInsertRules(view, pageUrl)
                if (view === current()?.webView) updateNavButtons()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // HTTPS upgrade failed for this site: offer to continue over plain http.
                val u = request.url
                val host = u.host ?: return
                if (request.isForMainFrame && u.scheme == "https" && host in upgradedHosts) {
                    upgradedHosts.remove(host)
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Site doesn't support HTTPS")
                        .setMessage("$host couldn't be loaded securely. Continue with an unencrypted connection?")
                        .setPositiveButton("Continue") { _, _ ->
                            httpAllowed.add(host)
                            view.loadUrl(u.buildUpon().scheme("http").build().toString())
                        }
                        .setNegativeButton("Go back", null)
                        .show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== current()?.webView) return
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title = title?.takeIf { it.isNotBlank() && it != "about:blank" } ?: "New Tab"
                tab.titleView.text = (if (isPrivate) "🕶 " else "") + tab.title
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                // Popup blocker: only windows opened by a tap get through.
                if (store.blockPopups && !isUserGesture) {
                    toast("Pop-up blocked")
                    return false
                }
                val newTab = createTab(null)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)
            override fun onHideCustomView() = exitFullscreen()

            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { pickFiles.launch(params.createIntent()); true }
                catch (_: Exception) { fileCallback = null; toast("No file picker available"); false }
            }

            // Sites never get camera, microphone or location silently.
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)
        }

        webView.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            startDownload(downloadUrl, userAgent, contentDisposition, mimeType)
        }
        webView.setOnLongClickListener { showLinkMenu(webView) }

        tab.chip.setOnClickListener { switchTo(tabs.indexOf(tab)) }
        tab.chip.findViewById<TextView>(R.id.tabClose).setOnClickListener { closeTab(tabs.indexOf(tab)) }
        if (isPrivate) tab.titleView.text = "🕶 New Tab"

        tabs.add(tab)
        tabBar.addView(tab.chip)
        webContainer.addView(webView)
        switchTo(tabs.size - 1)

        if (url != null) navigate(webView, url)
        return tab
    }

    /** WebView settings tightened against the common WebView attack surface. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun hardenSettings(s: WebSettings) {
        s.javaScriptEnabled = store.javaScriptEnabled
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = !store.blockPopups
        s.mediaPlaybackRequiresUserGesture = true
        // Pages can never read the device's files or other apps' content providers.
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        run {
            s.allowFileAccessFromFileURLs = false
            s.allowUniversalAccessFromFileURLs = false
            s.saveFormData = !isPrivate
        }
        s.mixedContentMode =
            if (store.httpsOnly) WebSettings.MIXED_CONTENT_NEVER_ALLOW
            else WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.safeBrowsingEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.blockNetworkImage = store.blockImages
        s.loadsImagesAutomatically = !store.blockImages
    }

    /**
     * Decides what happens when a page navigates. Returns true when the WebView
     * should NOT load the url itself.
     */
    private fun handleNavigation(view: WebView, u: Uri, mainFrame: Boolean, gesture: Boolean): Boolean {
        // Requestly-style main-frame rules: send the address bar to the new target too.
        if (mainFrame && dev.devToolsEnabled) {
            val urlStr = u.toString()
            val matching = dev.requestRules().filter { it.enabled && UrlMatch.matches(it.urlPattern, urlStr) }
            // Redirect wins outright.
            matching.firstOrNull {
                it.action == RequestRule.ACTION_REDIRECT && it.target.isNotBlank() && it.target != urlStr
            }?.let { view.loadUrl(it.target); return true }
            // Query-param / URL-string edits rewrite the address and reload once.
            val urlEdits = matching.filter { it.action == RequestRule.ACTION_QUERY || it.action == RequestRule.ACTION_URLREPLACE }
            if (urlEdits.isNotEmpty()) {
                val rewritten = ruleEngine.rewriteUrl(urlStr, urlEdits)
                if (rewritten != urlStr) { view.loadUrl(rewritten); return true }
            }
        }
        when (u.scheme?.lowercase()) {
            "https" -> return false
            "http" -> {
                val host = u.host ?: return false
                if (mainFrame && store.httpsOnly && host !in httpAllowed) {
                    upgradedHosts.add(host)
                    view.loadUrl(u.buildUpon().scheme("https").build().toString())
                    return true
                }
                return false
            }
            "about" -> return u.toString() != "about:blank"
            // Never let a page navigate to local files, content providers, script or data urls.
            "file", "content", "javascript", "data", "blob" -> return true
            "intent" -> { openIntentUri(view, u, gesture); return true }
            else -> { openExternal(Intent(Intent.ACTION_VIEW, u), gesture); return true }
        }
    }

    /** intent:// links, with the target restricted the same way Chrome does. */
    private fun openIntentUri(view: WebView, u: Uri, gesture: Boolean) {
        val intent = try { Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) } catch (_: Exception) { return }
        val fallback = intent.getStringExtra("browser_fallback_url")
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val canHandle = intent.resolveActivity(packageManager) != null
        if (canHandle) openExternal(intent, gesture)
        else if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
            view.loadUrl(fallback)
        }
    }

    /** Hands a link to another app, but only after the user agrees. */
    private fun openExternal(intent: Intent, gesture: Boolean) {
        if (!gesture) return // pages can't bounce you into other apps without a tap
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        if (intent.resolveActivity(packageManager) == null) { toast("No app can open this link"); return }
        AlertDialog.Builder(this)
            .setMessage("Open this link in another app?")
            .setPositiveButton("Open") { _, _ ->
                try { startActivity(intent) } catch (_: Exception) { toast("Couldn't open link") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Loads a url the user (or another app) asked for, upgrading http to https when enabled. */
    private fun navigate(webView: WebView, url: String) {
        val u = Uri.parse(url)
        val host = u.host
        if (u.scheme == "http" && store.httpsOnly && host != null && host !in httpAllowed) {
            upgradedHosts.add(host)
            webView.loadUrl(u.buildUpon().scheme("https").build().toString())
        } else loadInto(webView, url)
    }

    private fun loadInto(webView: WebView, url: String) {
        if (url == PRIVATE_HOME && isPrivate) {
            webView.loadDataWithBaseURL(null, privateLandingHtml(), "text/html", "utf-8", PRIVATE_HOME)
        } else webView.loadUrl(url)
    }

    private fun privateLandingHtml(): String = """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{background:#1b1028;color:#e8e1f0;font-family:sans-serif;padding:28px;line-height:1.5}
        h1{font-size:22px;color:#c9a8ff}li{margin:6px 0}</style></head><body>
        <h1>🕶 You've gone private</h1>
        <p>This window is completely separate from your normal browsing.</p>
        <ul>
        <li>It has its <b>own cookie jar</b>. Normal-window logins and cookies can't be seen here, and nothing from here leaks back.</li>
        <li>Cookies, site data, cache and tabs are <b>erased when you close it</b>.</li>
        <li>Pages aren't saved to history, and screenshots are blocked.</li>
        <li>Third-party cookies are always blocked here.</li>
        </ul>
        <p>Type a search or address above to start.</p></body></html>
    """.trimIndent()

    private fun displayUrl(url: String): String = if (url == PRIVATE_HOME) "" else url

    private fun switchTo(index: Int) {
        if (index < 0 || index >= tabs.size) return
        currentIndex = index
        for (i in tabs.indices) {
            val visible = i == index
            tabs[i].webView.visibility = if (visible) View.VISIBLE else View.GONE
            tabs[i].chip.setBackgroundResource(if (visible) R.color.tab_active else R.color.tab_inactive)
            tabs[i].titleView.setTextColor(getColor(if (visible) R.color.text else R.color.text_dim))
        }
        setUrlBarText(displayUrl(tabs[index].webView.url ?: ""))
        updateNavButtons()
        if (findBar.visibility == View.VISIBLE) closeFind()
    }

    private fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs.removeAt(index)
        webContainer.removeView(tab.webView)
        tabBar.removeView(tab.chip)
        tab.webView.destroy()
        if (tabs.isEmpty()) createTab(homeUrl)
        else switchTo(index.coerceAtMost(tabs.size - 1))
    }

    // ---- Navigation --------------------------------------------------------

    private fun loadUserInput(text: String) {
        val wv = current()?.webView ?: return
        navigate(wv, toUrl(text))
        urlBar.clearFocus()
        hideKeyboard(urlBar)
    }

    private fun toUrl(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return homeUrl
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return t
        // Typed file:, javascript:, content: etc. are treated as search text, never executed.
        val looksLikeHost = t.contains(".") && !t.contains(" ") && !t.contains(":") || lower.startsWith("localhost")
        return if (looksLikeHost) "https://$t" else store.searchTemplate() + Uri.encode(t)
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Sets the address bar text WITHOUT triggering the suggestion dropdown. */
    private fun setUrlBarText(text: String) {
        urlBar.setText(text, false)
    }

    /** Dims the toolbar back/forward arrows when there's nowhere to go. */
    private fun updateNavButtons() {
        val wv = current()?.webView
        val back = wv?.canGoBack() == true
        val fwd = wv?.canGoForward() == true
        btnBack.isEnabled = back; btnBack.alpha = if (back) 1f else 0.35f
        btnForward.isEnabled = fwd; btnForward.alpha = if (fwd) 1f else 0.35f
    }

    // ---- Address-bar suggestions (omnibox) ---------------------------------

    /** Wires the url bar's autocomplete to history + bookmarks + popular sites. */
    private fun setupSuggestions() {
        val adapter = SuggestAdapter(this) { suggestionSource() }
        urlBar.setAdapter(adapter)
        urlBar.threshold = 1
        urlBar.setOnItemClickListener { _, _, position, _ ->
            val picked = adapter.getItem(position)
            loadUserInput(picked.url)
        }
    }

    /** Builds the suggestion pool fresh each time the user types (private windows stay empty). */
    private fun suggestionSource(): List<Suggestion> {
        if (isPrivate) return SuggestAdapter.TOP_SITES
        val out = ArrayList<Suggestion>()
        for (h in store.history()) out.add(Suggestion(h.title.ifBlank { h.url }, h.url, Suggestion.Kind.HISTORY))
        for (b in store.bookmarks()) out.add(Suggestion(b.title.ifBlank { b.url }, b.url, Suggestion.Kind.BOOKMARK))
        out.addAll(SuggestAdapter.TOP_SITES)
        return out
    }

    // ---- Back/forward history ----------------------------------------------

    /** Shows this tab's full back/forward list so the user can jump to any earlier page. */
    private fun showNavHistory() {
        val wv = current()?.webView ?: return
        val list = wv.copyBackForwardList()
        if (list.size == 0) { toast("Nothing to go back to yet"); return }
        val items = ArrayList<Pair<String, Int>>() // label -> index
        for (i in list.size - 1 downTo 0) {
            val it = list.getItemAtIndex(i) ?: continue
            val marker = when {
                i == list.currentIndex -> "• "
                i < list.currentIndex -> "← "
                else -> "→ "
            }
            val title = it.title?.takeIf { t -> t.isNotBlank() } ?: it.url
            items.add("$marker$title" to i)
        }
        AlertDialog.Builder(this)
            .setTitle("Pages in this tab")
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                val target = items[which].second
                val steps = target - list.currentIndex
                if (steps != 0 && wv.canGoBackOrForward(steps)) wv.goBackOrForward(steps)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---- Desktop mode ------------------------------------------------------

    private fun applyUserAgent(tab: Tab) {
        val s = tab.webView.settings
        s.userAgentString = if (tab.desktop) DESKTOP_UA else WebSettings.getDefaultUserAgent(this)
        // A real desktop site needs more than the UA string: present a wide
        // viewport and, at document start, make the page look non-touch so sites
        // like Netflix and Prime Video serve their desktop player.
        s.useWideViewPort = tab.desktop
        s.loadWithOverviewMode = tab.desktop
        tab.webView.setInitialScale(if (tab.desktop) 1 else 0)
        installDesktopSpoof(tab)
    }

    private fun installDesktopSpoof(tab: Tab) {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return
        tab.spoofScript?.let { try { it.remove() } catch (_: Exception) {} }
        tab.spoofScript = null
        if (tab.desktop) {
            tab.spoofScript = try {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(tab.webView, DESKTOP_SPOOF_JS, setOf("*"))
            } catch (_: Exception) { null }
        }
    }

    private fun toggleDesktopCurrent() {
        val tab = current() ?: return
        tab.desktop = !tab.desktop
        applyUserAgent(tab)
        tab.webView.reload()
        toast(if (tab.desktop) "Desktop site on" else "Desktop site off")
    }

    // ---- Fullscreen video --------------------------------------------------

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view
        customViewCallback = callback
        (window.decorView as FrameLayout).addView(
            view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        (window.decorView as FrameLayout).removeView(v)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // ---- Long-press link menu ----------------------------------------------

    private fun showLinkMenu(webView: WebView): Boolean {
        val hit = webView.hitTestResult
        val link = hit.extra ?: return false
        val isLink = hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
            hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val isImage = hit.type == WebView.HitTestResult.IMAGE_TYPE ||
            hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        if (!isLink && !isImage) return false
        if (!(link.startsWith("http://") || link.startsWith("https://"))) return false

        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += "Open in new tab" to { createTab(link); Unit }
        if (!isPrivate) options += "Open in private window" to { openPrivateWindow(link) }
        options += "Copy link address" to { copyText(link, "Link copied") }
        options += "Share link" to { shareText(link) }
        if (isImage) options += "Download image" to { startDownload(link, webView.settings.userAgentString, null, null) }

        AlertDialog.Builder(this)
            .setTitle(link.take(80))
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
        return true
    }

    // ---- Private window ----------------------------------------------------

    private fun openPrivateWindow(url: String? = null) {
        val i = Intent(this, PrivateActivity::class.java)
        if (url != null) i.data = Uri.parse(url)
        startActivity(i)
    }

    // ---- Bottom-sheet menu -------------------------------------------------

    private fun showMenu() {
        val sheet = BottomSheetDialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(if (isPrivate) R.color.private_bar else R.color.toolbar))
            setPadding(0, dp(8), 0, dp(8))
        }
        val scroll = ScrollView(this).apply { addView(col) }

        val tab = current()
        val wv = tab?.webView
        val url = wv?.url?.takeIf { it.startsWith("http") }
        val bookmarked = url != null && store.isBookmarked(url)

        fun item(icon: String, label: String, action: () -> Unit) {
            col.addView(menuRow(icon, label) { sheet.dismiss(); action() })
        }

        // Quick action row: back, forward, bookmark, reload, home.
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        fun quickIcon(res: Int, desc: String, enabled: Boolean = true, tint: Int = R.color.text, action: () -> Unit) {
            quick.addView(ImageButton(this).apply {
                setImageResource(res)
                contentDescription = desc
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(tint))
                background = rippleBg()
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.35f
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
                setOnClickListener { sheet.dismiss(); action() }
            })
        }
        quickIcon(R.drawable.ic_back, "Back", wv?.canGoBack() == true) { wv?.goBack() }
        quickIcon(R.drawable.ic_forward, "Forward", wv?.canGoForward() == true) { wv?.goForward() }
        if (!isPrivate) {
            quickIcon(
                if (bookmarked) R.drawable.ic_star else R.drawable.ic_star_border, "Bookmark",
                enabled = url != null, tint = if (bookmarked) R.color.accent else R.color.text
            ) { toggleBookmarkCurrent() }
        }
        quickIcon(R.drawable.ic_refresh, "Reload") { wv?.reload() }
        quickIcon(R.drawable.ic_home, "Home") { wv?.let { loadInto(it, homeUrl) } }
        col.addView(quick)
        col.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.tab_active))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        val blocked = tab?.blocked?.get() ?: 0
        item("🛡", if (store.shieldsEnabled) "Shields on · $blocked blocked here" else "Shields off (tap to turn on)") { toggleShields() }
        item("➕", if (isPrivate) "New private tab" else "New tab") { createTab(homeUrl) }
        if (isPrivate) {
            item("🔥", "Erase & close private window") { closeWindow() }
        } else {
            item("🕶", "New private window") { openPrivateWindow() }
            item("🕒", "History") { showHistory() }
            item("📑", "Bookmarks") { showBookmarks() }
        }
        item("🍪", "Cookie Manager") { showCookieManager() }
        item("🔍", "Find in page") { openFind() }
        item("🖥", if (tab?.desktop == true) "Desktop site  ✓" else "Desktop site") { toggleDesktopCurrent() }
        item("🧰", "Developer tools") { showDevTools() }
        item("📥", "Downloads") { openDownloads() }
        item("🔗", "Share page") { url?.let { shareText(it) } ?: toast("Open a page first") }
        item("📋", "Copy link") { url?.let { copyText(it, "Link copied") } ?: toast("Open a page first") }
        if (!isPrivate) item("⚙️", "Settings") { showSettings() }
        item("ℹ️", "About") { showAbout() }

        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun menuRow(icon: String, label: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            isClickable = true
            background = rippleBg()
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = icon; textSize = 18f
            layoutParams = LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(getColor(R.color.text)) })
        return row
    }

    private fun toggleShields() {
        if (isPrivate) {
            // Settings are owned by the normal window's process; just flip for this session view.
            toast(if (store.shieldsEnabled) "Shields are on" else "Turn Shields on in Settings (normal window)")
            return
        }
        store.shieldsEnabled = !store.shieldsEnabled
        current()?.webView?.reload()
        toast(if (store.shieldsEnabled) "Shields on: ads & trackers blocked" else "Shields off")
    }

    // ---- History -----------------------------------------------------------

    private fun showHistory() {
        val all = store.history()
        if (all.isEmpty()) { toast("No history yet"); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), 0) }
        val search = EditText(this).apply { hint = "Search history"; setSingleLine(true) }
        val list = ListView(this)
        root.addView(search); root.addView(list)

        var shown = all
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        fun render(items: List<HistoryEntry>) {
            shown = items
            val labels = items.map { "${it.title.ifBlank { it.url }}\n${it.url}  ·  ${df.format(Date(it.time))}" }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        }
        render(all)
        search.addTextChangedListener(simpleWatcher {
            val q = it.lowercase()
            render(if (q.isBlank()) all else all.filter { e -> e.url.lowercase().contains(q) || e.title.lowercase().contains(q) })
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("History")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear all") { _, _ -> confirm("Clear all history?") { store.clearHistory(); toast("History cleared") } }
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { e -> current()?.webView?.loadUrl(e.url) ?: createTab(e.url) }
            dialog.dismiss()
        }
        dialog.show()
    }

    // ---- Bookmarks ---------------------------------------------------------

    private fun showBookmarks() {
        val all = store.bookmarks()
        if (all.isEmpty()) { toast("No bookmarks yet"); return }
        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1,
                all.map { "${it.title.ifBlank { it.url }}\n${it.url}" })
        }
        val dialog = AlertDialog.Builder(this).setTitle("Bookmarks").setView(list)
            .setPositiveButton("Close", null).create()
        list.setOnItemClickListener { _, _, pos, _ ->
            all.getOrNull(pos)?.let { b -> current()?.webView?.loadUrl(b.url) ?: createTab(b.url) }
            dialog.dismiss()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            all.getOrNull(pos)?.let { b ->
                confirm("Remove bookmark?\n${b.url}") { store.removeBookmark(b.url); dialog.dismiss(); toast("Removed") }
            }
            true
        }
        dialog.show()
    }

    private fun toggleBookmarkCurrent() {
        val tab = current() ?: return
        val url = tab.webView.url?.takeIf { it.startsWith("http") } ?: run { toast("Open a page first"); return }
        toast(if (store.toggleBookmark(url, tab.title)) "Bookmarked" else "Bookmark removed")
    }

    // ---- Cookie Manager ----------------------------------------------------

    private fun showCookieManager() {
        val actions = listOf(
            "View cookies for this site",
            "Inject for a site (no visit needed)",
            "Inject from file (JSON)",
            "Inject from file (Netscape)",
            "Paste & inject (current site)",
            "Export as text (copy)",
            "Export to file (JSON)",
            "Export to file (Netscape)",
            "Clear cookies for this site",
            if (isPrivate) "Clear ALL private cookies" else "Clear ALL cookies"
        )
        AlertDialog.Builder(this)
            .setTitle(if (isPrivate) "🍪 Cookie Manager (private jar)" else "🍪 Cookie Manager")
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> viewCookies()
                    1 -> injectForSite()
                    2 -> { pendingImportFormat = "json"; openCookieFile.launch(arrayOf("application/json", "text/plain", "*/*")) }
                    3 -> { pendingImportFormat = "netscape"; openCookieFile.launch(arrayOf("text/plain", "*/*")) }
                    4 -> pasteAndInject()
                    5 -> exportCookiesAsText()
                    6 -> exportCookies("json")
                    7 -> exportCookies("netscape")
                    8 -> clearSiteCookies()
                    9 -> confirm("Clear all cookies in this ${if (isPrivate) "private window" else "browser"}?") {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush(); toast("Cookies cleared")
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Injects cookies for a site you name, without having to open it first. You
     * give the target URL/domain, then paste cookies (JSON, Netscape, or plain
     * name=value pairs). Cookies that don't carry their own domain are scoped to
     * the target you typed. Afterwards it offers to open the site.
     */
    private fun injectForSite() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val urlField = EditText(this).apply {
            hint = "Target site, e.g. example.com or https://example.com"
            setSingleLine(true)
            current()?.webView?.url?.takeIf { it.startsWith("http") }?.let { setText(it) }
        }
        val cookieField = EditText(this).apply {
            hint = "Paste cookies: JSON array, Netscape cookies.txt, or name=value; name2=value2"
            gravity = Gravity.TOP; minLines = 5; maxLines = 12
        }
        box.addView(urlField); box.addView(cookieField)
        AlertDialog.Builder(this)
            .setTitle("Inject for a site")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Inject") { _, _ ->
                val target = toUrl(urlField.text.toString().trim())
                val host = Uri.parse(target).host
                if (host.isNullOrBlank()) { toast("Enter a valid site"); return@setPositiveButton }
                val text = cookieField.text.toString().trim()
                if (text.isEmpty()) { toast("Nothing to inject"); return@setPositiveButton }
                val fmt = when {
                    text.startsWith("[") || text.startsWith("{") -> "json"
                    text.contains("\t") -> "netscape"
                    else -> "pairs"
                }
                val n = injectCookieText(text, fmt, fallbackDomain = host, secure = target.startsWith("https"))
                if (n > 0) confirm("Injected $n cookie(s) for $host. Open the site now?") {
                    current()?.webView?.let { navigate(it, target) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun currentHttpUrl(): String? =
        current()?.webView?.url?.takeIf { it.startsWith("http") } ?: run { toast("Open a website first"); null }

    private fun viewCookies() {
        val url = currentHttpUrl() ?: return
        val raw = CookieManager.getInstance().getCookie(url)
        val host = Uri.parse(url).host ?: url
        val body = if (raw.isNullOrBlank()) "No cookies for $host" else raw.split(";").joinToString("\n") { it.trim() }
        val tv = TextView(this).apply { text = body; setTextIsSelectable(true); setPadding(dp(20), dp(12), dp(20), dp(12)); textSize = 13f }
        AlertDialog.Builder(this).setTitle(host).setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null).show()
    }

    private fun pasteAndInject() {
        val input = EditText(this).apply {
            hint = "Paste cookies (JSON array or Netscape cookies.txt)"
            gravity = Gravity.TOP; minLines = 6; maxLines = 12; setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Paste & inject")
            .setView(input)
            .setPositiveButton("Inject") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) { toast("Nothing to inject"); return@setPositiveButton }
                injectCookieText(text, if (text.startsWith("[") || text.startsWith("{")) "json" else "netscape")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearSiteCookies() {
        val url = currentHttpUrl() ?: return
        val host = Uri.parse(url).host ?: return
        val raw = CookieManager.getInstance().getCookie(url)
        if (raw.isNullOrBlank()) { toast("No cookies for this site"); return }
        val names = raw.split(";").mapNotNull { it.substringBefore("=").trim().takeIf { n -> n.isNotEmpty() } }.distinct()
        confirm("Clear ${names.size} cookie(s) for $host?") {
            val cm = CookieManager.getInstance()
            val expired = "Thu, 01 Jan 1970 00:00:00 GMT"
            // The earlier bug: it only expired cookies with Domain=host / .host. A
            // cookie set WITHOUT a Domain attribute (host-only, the common case) is a
            // different cookie that those never matched, so it survived. Here we expire
            // every domain form (host-only, host, .host, and the registrable parent)
            // across the usual paths, which covers how the cookie was actually set.
            val domains = buildList {
                add(null)                 // host-only: no Domain attribute
                add(host); add(".$host")
                parentDomain(host)?.let { add(it); add(".$it") }
            }
            val paths = listOf("/", pathOf(url))
            for (name in names) for (dom in domains) for (p in paths.distinct()) {
                val sb = StringBuilder("$name=; Path=$p; Expires=$expired; Max-Age=0")
                if (dom != null) sb.append("; Domain=$dom")
                if (url.startsWith("https")) sb.append("; Secure")
                try { cm.setCookie(url, sb.toString()) } catch (_: Exception) {}
            }
            cm.flush()
            // Verify and tell the user the truth.
            val remaining = CookieManager.getInstance().getCookie(url)
                ?.split(";")?.count { it.substringBefore("=").trim().isNotEmpty() } ?: 0
            current()?.webView?.reload()
            if (remaining == 0) toast("Cleared all cookies for $host")
            else toast("Cleared ${names.size - remaining} of ${names.size}; $remaining couldn't be removed")
        }
    }

    /** The registrable parent of a host, e.g. www.example.com -> example.com (best-effort). */
    private fun parentDomain(host: String): String? {
        val parts = host.split(".")
        return if (parts.size > 2) parts.drop(1).joinToString(".") else null
    }

    private fun pathOf(url: String): String =
        (Uri.parse(url).path?.substringBeforeLast("/")?.ifBlank { "/" } ?: "/")

    private fun importCookiesFrom(uri: Uri, format: String) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) { toast("Could not read file: ${e.message}"); return }
        injectCookieText(text, format)
    }

    /**
     * Injects cookies and returns how many were set. [fallbackDomain] scopes any
     * cookie whose own data has no domain (e.g. plain name=value pairs); when it's
     * null those are scoped to the current page. Does not reload when a caller
     * drives navigation itself.
     */
    private fun injectCookieText(
        text: String, format: String,
        fallbackDomain: String? = null, secure: Boolean = false, reload: Boolean = fallbackDomain == null
    ): Int {
        val fallback = fallbackDomain
            ?: current()?.webView?.url?.takeIf { it.startsWith("http") }?.let { Uri.parse(it).host }
        val cookies = try {
            when (format) {
                "netscape" -> CookieIO.parseNetscape(text)
                "pairs" -> parsePairs(text, fallback, secure)
                else -> CookieIO.parseJson(text)
            }
        } catch (e: Exception) { toast("Could not parse: ${e.message}"); return 0 }
        // Give domain-less cookies the fallback domain so they still land somewhere.
        val resolved = cookies.map {
            if (it.domain.isBlank() && fallback != null) it.copy(domain = fallback, secure = it.secure || secure) else it
        }.filter { it.domain.isNotBlank() }
        if (resolved.isEmpty()) { toast("No cookies found (or no target domain for them)"); return 0 }
        val cm = CookieManager.getInstance()
        var ok = 0
        for (c in resolved) {
            try { cm.setCookie(CookieIO.urlFor(c), CookieIO.setCookieString(c)); ok++ } catch (_: Exception) { }
        }
        cm.flush()
        if (reload) current()?.webView?.reload()
        toast("Injected $ok of ${resolved.size} cookie(s)" + if (isPrivate) " into private window" else "")
        return ok
    }

    /** Parses plain "name=value; name2=value2" (or newline-separated) cookie pairs. */
    private fun parsePairs(text: String, domain: String?, secure: Boolean): List<ParsedCookie> {
        if (domain.isNullOrBlank()) return emptyList()
        return text.split("\n", ";").mapNotNull { part ->
            val eq = part.indexOf('='); if (eq <= 0) return@mapNotNull null
            val name = part.substring(0, eq).trim(); val value = part.substring(eq + 1).trim()
            if (name.isEmpty()) null else ParsedCookie(name, value, domain, "/", secure, false, null)
        }
    }

    /**
     * The current site's cookies as ParsedCookie. Android's CookieManager only
     * exposes name=value for the current URL, so domain/path/secure are inferred
     * from the page and there's no expiry or httpOnly metadata.
     */
    private fun currentSiteCookies(): List<ParsedCookie> {
        val pageUrl = currentHttpUrl() ?: return emptyList()
        val raw = CookieManager.getInstance().getCookie(pageUrl)
        if (raw.isNullOrBlank()) return emptyList()
        val host = Uri.parse(pageUrl).host ?: ""
        val secure = pageUrl.startsWith("https")
        return raw.split(";").mapNotNull { part ->
            val eq = part.indexOf('='); if (eq <= 0) return@mapNotNull null
            val name = part.substring(0, eq).trim(); val value = part.substring(eq + 1).trim()
            if (name.isEmpty()) null else ParsedCookie(name, value, host, "/", secure, false, null)
        }
    }

    private fun exportCookies(format: String) {
        if (currentHttpUrl() == null) return
        val cookies = currentSiteCookies()
        if (cookies.isEmpty()) { toast("No cookies for this site"); return }
        if (format == "netscape") { pendingExportText = CookieIO.toNetscape(cookies); createTxtFile.launch("cookies.txt") }
        else { pendingExportText = CookieIO.toJson(cookies); createJsonFile.launch("cookies.json") }
    }

    /** Shows the exported cookies as selectable text with a one-tap Copy, no file needed. */
    private fun exportCookiesAsText() {
        if (currentHttpUrl() == null) return
        val cookies = currentSiteCookies()
        if (cookies.isEmpty()) { toast("No cookies for this site"); return }
        val formats = listOf("JSON", "Netscape (cookies.txt)")
        AlertDialog.Builder(this)
            .setTitle("Export as text")
            .setItems(formats.toTypedArray()) { _, which ->
                val text = if (which == 0) CookieIO.toJson(cookies) else CookieIO.toNetscape(cookies)
                val tv = TextView(this).apply {
                    setText(text); setTextIsSelectable(true); textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                }
                AlertDialog.Builder(this)
                    .setTitle("${cookies.size} cookie(s) · ${formats[which]}")
                    .setView(ScrollView(this).apply { addView(tv) })
                    .setPositiveButton("Copy") { _, _ -> copyText(text, "Cookies copied to clipboard") }
                    .setNegativeButton("Close", null)
                    .show()
            }
            .show()
    }

    private fun writeTextTo(uri: Uri, text: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            toast("Cookies exported")
        } catch (e: Exception) { toast("Could not write file: ${e.message}") }
    }

    // ---- Find in page ------------------------------------------------------

    private fun setupFindBar() {
        findViewById<ImageButton>(R.id.findPrev).setOnClickListener { current()?.webView?.findNext(false) }
        findViewById<ImageButton>(R.id.findNext).setOnClickListener { current()?.webView?.findNext(true) }
        findViewById<ImageButton>(R.id.findClose).setOnClickListener { closeFind() }
        findInput.addTextChangedListener(simpleWatcher { q -> current()?.webView?.findAllAsync(q) })
        findInput.setOnEditorActionListener { _, _, _ -> current()?.webView?.findNext(true); true }
    }

    private fun openFind() {
        val wv = current()?.webView ?: return
        wv.setFindListener { active, count, _ -> findCount.text = if (count == 0) "0/0" else "${active + 1}/$count" }
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        findBar.visibility = View.GONE
        findInput.setText("")
        current()?.webView?.clearMatches()
        hideKeyboard(findInput)
    }

    // ---- Share / copy / downloads -----------------------------------------

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        startActivity(Intent.createChooser(send, "Share link"))
    }

    private fun copyText(text: String, msg: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", text))
        toast(msg)
    }

    private fun openDownloads() {
        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) { toast("No downloads app found") }
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) { toast("This download type isn't supported"); return }
        try {
            // guessFileName never returns path separators, so the file stays inside Downloads.
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimeType)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("cookie", it) }
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast("Downloading $fileName")
        } catch (e: Exception) { toast("Download failed: ${e.message}") }
    }

    // ---- Settings ----------------------------------------------------------

    private fun showSettings() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0) }

        col.addView(label("Homepage"))
        val homepage = EditText(this).apply { setText(store.homepage); setSingleLine(true) }
        col.addView(homepage)

        col.addView(label("Search engine"))
        val engines = Store.SEARCH_ENGINES.keys.toList()
        val engineSpinner = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, engines)
            setSelection(engines.indexOf(store.searchEngine).coerceAtLeast(0))
        }
        col.addView(engineSpinner)

        col.addView(label("Privacy & security"))
        val shields = switchRow("Shields: block ads & trackers", store.shieldsEnabled)
        val https = switchRow("Upgrade connections to HTTPS", store.httpsOnly)
        val popups = switchRow("Block pop-ups", store.blockPopups)
        val thirdParty = switchRow("Block third-party cookies", store.blockThirdPartyCookies)
        col.addView(label("Content"))
        val js = switchRow("Enable JavaScript", store.javaScriptEnabled)
        val desktop = switchRow("Open pages in desktop mode", store.desktopByDefault)
        val images = switchRow("Block images (data saver)", store.blockImages)
        for (r in listOf(shields, https, popups, thirdParty)) col.addView(r.first, col.childCount - 1)
        for (r in listOf(js, desktop, images)) col.addView(r.first)

        AlertDialog.Builder(this)
            .setTitle("⚙️ Settings")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                store.homepage = toUrl(homepage.text.toString())
                store.searchEngine = engines.getOrElse(engineSpinner.selectedItemPosition) { "Google" }
                store.shieldsEnabled = shields.second.isChecked
                store.httpsOnly = https.second.isChecked
                store.blockPopups = popups.second.isChecked
                store.blockThirdPartyCookies = thirdParty.second.isChecked
                store.javaScriptEnabled = js.second.isChecked
                store.desktopByDefault = desktop.second.isChecked
                store.blockImages = images.second.isChecked
                for (t in tabs) {
                    hardenSettings(t.webView.settings)
                    applyUserAgent(t)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(t.webView, !store.blockThirdPartyCookies)
                }
                toast("Settings saved")
            }
            .setNeutralButton("Clear browsing data") { _, _ ->
                confirm("Clear history, cookies, site data and cache?") {
                    store.clearHistory()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    current()?.webView?.clearCache(true)
                    toast("Browsing data cleared")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchRow(text: String, checked: Boolean): Pair<View, android.widget.Switch> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            this.text = text; setTextColor(getColor(R.color.text)); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = android.widget.Switch(this).apply { isChecked = checked }
        row.addView(sw)
        return row to sw
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(getColor(R.color.text_dim)); textSize = 13f
        setPadding(0, dp(14), 0, dp(2))
    }

    // ---- Developer tools ---------------------------------------------------

    private fun showDevTools() {
        val sheet = BottomSheetDialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(if (isPrivate) R.color.private_bar else R.color.toolbar))
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(TextView(this).apply {
            text = "🧰  Developer tools"; textSize = 18f; setTextColor(getColor(R.color.text))
            setPadding(dp(20), dp(12), dp(20), dp(6))
        })
        val masterRow = switchRow("Rules enabled (master switch)", dev.devToolsEnabled)
        (masterRow.first as LinearLayout).setPadding(dp(20), dp(4), dp(20), dp(8))
        masterRow.second.setOnCheckedChangeListener { _, c -> dev.devToolsEnabled = c }
        col.addView(masterRow.first)
        col.addView(sep())

        fun row(icon: String, label: String, sub: String, action: () -> Unit) {
            col.addView(menuRow2(icon, label, sub) { sheet.dismiss(); action() })
        }
        row("🏷", "Header rules", "ModHeader-style: add, change or remove request/response headers") { showHeaderRules() }
        row("🔀", "Request rules", "Requestly-style: block, redirect, rewrite, query/URL edits, status, map-local, delay") { showRequestRules() }
        row("🧪", "Burpsuite", "Workbench: HTTP history, Repeater, Encoder/Decoder, Comparer") { showWorkbench() }
        row("📡", "Network inspector", "See every request, with blocked/modified/redirected tags") { showNetworkInspector() }
        row("🗄", "Storage editor", "StorageAce-style: view & edit localStorage / sessionStorage") { showStorageEditor() }
        row("📄", "View page source", "The rendered HTML of the current page") { viewSource() }
        row("🛠", "Quick toggles", "JavaScript, images, cookies on/off") { showQuickToggles() }

        sheet.setContentView(ScrollView(this).apply { addView(col) })
        sheet.show()
    }

    private fun menuRow2(icon: String, label: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12)); isClickable = true
            background = rippleBg(); setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = icon; textSize = 18f
            layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(getColor(R.color.text)) })
        texts.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(getColor(R.color.text_dim)) })
        row.addView(texts)
        return row
    }

    private fun sep() = View(this).apply {
        setBackgroundColor(getColor(R.color.tab_active))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    // ---- Header rules (ModHeader) ------------------------------------------

    private fun showHeaderRules() {
        val rules = dev.headerRules()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        val list = ListView(this)
        fun labels() = rules.map {
            val dir = if (it.onResponse) "response" else "request"
            val op = if (it.remove) "remove" else "set"
            "${if (it.enabled) "✓" else "✗"}  $op $dir: ${it.header.ifBlank { "(name?)" }}" +
                (if (!it.remove) " = ${it.value}" else "") + "\n   url: ${it.urlPattern}"
        }
        fun refresh() { list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels()) }
        refresh()
        root.addView(list)
        val dialog = AlertDialog.Builder(this)
            .setTitle("🏷 Header rules")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNeutralButton("Add rule", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ -> editHeaderRule(rules[pos]) { rules[pos] = it; dev.saveHeaderRules(rules); refresh() } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirm("Delete this header rule?") { rules.removeAt(pos); dev.saveHeaderRules(rules); refresh() }; true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editHeaderRule(HeaderRule()) { rules.add(0, it); dev.saveHeaderRules(rules); refresh() }
            }
        }
        dialog.show()
    }

    private fun editHeaderRule(rule: HeaderRule, onSave: (HeaderRule) -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val enabled = switchRow("Enabled", rule.enabled); col.addView(enabled.first)
        val onResp = switchRow("Apply to response (off = request)", rule.onResponse); col.addView(onResp.first)
        val remove = switchRow("Remove header (off = set)", rule.remove); col.addView(remove.first)
        col.addView(label("URL pattern (* wildcards, blank = all)"))
        val pat = EditText(this).apply { setText(rule.urlPattern); setSingleLine(true) }; col.addView(pat)
        col.addView(label("Header name"))
        val name = EditText(this).apply { setText(rule.header); setSingleLine(true); hint = "e.g. Authorization" }; col.addView(name)
        col.addView(label("Header value"))
        val value = EditText(this).apply { setText(rule.value); setSingleLine(true); hint = "e.g. Bearer abc123" }; col.addView(value)
        AlertDialog.Builder(this)
            .setTitle("Header rule")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                rule.enabled = enabled.second.isChecked
                rule.onResponse = onResp.second.isChecked
                rule.remove = remove.second.isChecked
                rule.urlPattern = pat.text.toString().ifBlank { "*" }
                rule.header = name.text.toString().trim()
                rule.value = value.text.toString()
                if (rule.header.isBlank()) { toast("Header name is required"); return@setPositiveButton }
                onSave(rule)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Request rules (Requestly) -----------------------------------------

    private fun showRequestRules() {
        val rules = dev.requestRules()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        val list = ListView(this)
        fun labels() = rules.map {
            "${if (it.enabled) "✓" else "✗"}  ${it.action.uppercase()}  ${it.urlPattern}" +
                when (it.action) {
                    RequestRule.ACTION_REDIRECT -> "\n   → ${it.target}"
                    RequestRule.ACTION_REPLACE -> "\n   \"${it.target}\" → \"${it.replaceWith}\""
                    RequestRule.ACTION_INSERT -> "\n   inject ${if (it.isCss) "CSS" else "JS"}"
                    RequestRule.ACTION_QUERY -> "\n   set ${it.target}  remove ${it.replaceWith}"
                    RequestRule.ACTION_URLREPLACE -> "\n   url \"${it.target}\" → \"${it.replaceWith}\""
                    RequestRule.ACTION_STATUS -> "\n   status → ${it.target}"
                    RequestRule.ACTION_MAPLOCAL -> "\n   serve ${it.target.take(30)}…"
                    RequestRule.ACTION_DELAY -> "\n   delay ${it.target} ms"
                    else -> ""
                }
        }
        fun refresh() { list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels()) }
        refresh(); root.addView(list)
        val dialog = AlertDialog.Builder(this)
            .setTitle("🔀 Request rules")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNeutralButton("Add rule", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ -> editRequestRule(rules[pos]) { rules[pos] = it; dev.saveRequestRules(rules); refresh() } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirm("Delete this request rule?") { rules.removeAt(pos); dev.saveRequestRules(rules); refresh() }; true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editRequestRule(RequestRule()) { rules.add(0, it); dev.saveRequestRules(rules); refresh() }
            }
        }
        dialog.show()
    }

    private fun editRequestRule(rule: RequestRule, onSave: (RequestRule) -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val enabled = switchRow("Enabled", rule.enabled); col.addView(enabled.first)
        col.addView(label("Action"))
        val actions = listOf(
            RequestRule.ACTION_BLOCK, RequestRule.ACTION_REDIRECT, RequestRule.ACTION_REPLACE,
            RequestRule.ACTION_INSERT, RequestRule.ACTION_QUERY, RequestRule.ACTION_URLREPLACE,
            RequestRule.ACTION_STATUS, RequestRule.ACTION_MAPLOCAL, RequestRule.ACTION_DELAY
        )
        val actionNames = listOf(
            "Block request", "Redirect to URL", "Replace text in response", "Inject JS/CSS",
            "Modify query params", "Replace text in URL", "Override status code",
            "Map local (custom response body)", "Delay request"
        )
        val spinner = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, actionNames)
            setSelection(actions.indexOf(rule.action).coerceAtLeast(0))
        }
        col.addView(spinner)
        col.addView(label("URL pattern (* wildcards)"))
        val pat = EditText(this).apply { setText(rule.urlPattern); setSingleLine(true); hint = "e.g. *ads*  or  example.com/api*" }
        col.addView(pat)
        val targetLabel = label("Target / find text / code"); col.addView(targetLabel)
        val target = EditText(this).apply { setText(rule.target); minLines = 1; maxLines = 8; hint = "redirect URL, text to find, or JS/CSS" }
        col.addView(target)
        val replLabel = label("Replace with"); col.addView(replLabel)
        val repl = EditText(this).apply { setText(rule.replaceWith); minLines = 1; maxLines = 6 }
        col.addView(repl)
        val css = switchRow("Inject as CSS (off = JS)", rule.isCss); col.addView(css.first)
        // Relabel the two text fields to match the chosen action.
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                when (actions[pos]) {
                    RequestRule.ACTION_REDIRECT -> { targetLabel.text = "Redirect to URL"; replLabel.text = "(unused)" }
                    RequestRule.ACTION_REPLACE -> { targetLabel.text = "Find text in response"; replLabel.text = "Replace with" }
                    RequestRule.ACTION_INSERT -> { targetLabel.text = "JS or CSS to inject"; replLabel.text = "(unused)" }
                    RequestRule.ACTION_QUERY -> { targetLabel.text = "Set params: k=v&k2=v2"; replLabel.text = "Remove params: k,k2" }
                    RequestRule.ACTION_URLREPLACE -> { targetLabel.text = "Find text in URL"; replLabel.text = "Replace with" }
                    RequestRule.ACTION_STATUS -> { targetLabel.text = "Status code e.g. 404"; replLabel.text = "(unused)" }
                    RequestRule.ACTION_MAPLOCAL -> { targetLabel.text = "Response body to serve"; replLabel.text = "Content-Type (e.g. application/json)" }
                    RequestRule.ACTION_DELAY -> { targetLabel.text = "Delay in milliseconds"; replLabel.text = "(unused)" }
                    else -> { targetLabel.text = "Target / find text / code"; replLabel.text = "Replace with" }
                }
                css.first.visibility = if (actions[pos] == RequestRule.ACTION_INSERT) View.VISIBLE else View.GONE
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Request rule")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                rule.enabled = enabled.second.isChecked
                rule.action = actions[spinner.selectedItemPosition]
                rule.urlPattern = pat.text.toString().trim()
                rule.target = target.text.toString()
                rule.replaceWith = repl.text.toString()
                rule.isCss = css.second.isChecked
                if (rule.urlPattern.isBlank()) { toast("URL pattern is required"); return@setPositiveButton }
                onSave(rule)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Network inspector -------------------------------------------------

    private fun showNetworkInspector() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        val search = EditText(this).apply { setSingleLine(true); hint = "filter: host, method, status or tag"; textSize = 13f }
        root.addView(search)
        val list = ListView(this)
        var filtered = NetLog.snapshot()
        fun render() {
            val q = search.text.toString().trim().lowercase()
            filtered = NetLog.snapshot().filter {
                q.isEmpty() || it.url.lowercase().contains(q) || it.method.lowercase().contains(q) ||
                    it.status.toString() == q || it.note.lowercase().contains(q)
            }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filtered.map {
                val tag = if (it.note.isBlank()) "" else "  [${it.note}]"
                "${it.method} ${if (it.status > 0) it.status else ""}$tag\n${it.url}"
            })
        }
        render(); root.addView(list)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { render() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle("📡 Network / HTTP history")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNeutralButton("Export HAR", null)
            .setNegativeButton("Clear") { _, _ -> NetLog.clear(); toast("Cleared") }
            .create()
        list.setOnItemClickListener { _, _, pos, _ -> filtered.getOrNull(pos)?.let { showNetEntry(it) } }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                copyText(HarExport.toHar(NetLog.snapshot()), "HAR copied to clipboard")
            }
        }
        dialog.show()
    }

    /** Detail of one captured request, with a jump into the Repeater. */
    private fun showNetEntry(e: NetEntry) {
        val headers = e.reqHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        val body = "${e.method} ${e.url}\n" +
            (if (e.status > 0) "Status: ${e.status}\n" else "") +
            (if (e.note.isNotBlank()) "Note: ${e.note}\n" else "") +
            "\nRequest headers:\n${headers.ifBlank { "(none captured)" }}" +
            "\n\nAs cURL:\n${HarExport.curl(e)}"
        val tv = TextView(this).apply {
            text = body; setTextIsSelectable(true); setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
        }
        AlertDialog.Builder(this)
            .setTitle("Request detail")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Send to Repeater") { _, _ -> showRepeater(e.method, e.url, headers, "") }
            .setNegativeButton("Copy cURL") { _, _ -> copyText(HarExport.curl(e), "cURL copied") }
            .show()
    }

    // ---- Burpsuite workbench ------------------------------------------------

    private fun showWorkbench() {
        val sheet = BottomSheetDialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(if (isPrivate) R.color.private_bar else R.color.toolbar))
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(TextView(this).apply {
            text = "🧪  Burpsuite workbench"; textSize = 18f; setTextColor(getColor(R.color.text))
            setPadding(dp(20), dp(12), dp(20), dp(2))
        })
        col.addView(TextView(this).apply {
            text = "Manual testing of traffic from this browser. Not a system-wide proxy."
            textSize = 12f; setTextColor(getColor(R.color.text_dim)); setPadding(dp(20), 0, dp(20), dp(6))
        })
        col.addView(sep())
        fun row(icon: String, label: String, sub: String, action: () -> Unit) {
            col.addView(menuRow2(icon, label, sub) { sheet.dismiss(); action() })
        }
        row("🕑", "HTTP history", "Every request this browser made — send to Repeater, export HAR / cURL") { showNetworkInspector() }
        row("🔁", "Repeater", "Edit a request's method / URL / headers / body and resend it") { showRepeater("GET", current()?.webView?.url ?: "https://", "", "") }
        row("🛡", "Passive scanner", "Read-only checks: security headers, cookie flags, mixed content") { showScanner() }
        row("🎯", "Target & scope", "Limit the tools to hosts you're authorized to test") { showScope() }
        row("🔡", "Encoder / Decoder", "Base64, URL, HTML, hex, JWT, hashes, JSON, unicode, timestamp") { showDecoder() }
        row("⚖️", "Comparer", "Diff two blocks of text (e.g. two responses)") { showComparer() }
        row("🎲", "Sequencer", "Rough randomness stats for tokens from your own testing") { showSequencer() }
        row("📋", "Capability matrix", "What's implemented, partial, or blocked on Android") { showCapabilityMatrix() }
        sheet.setContentView(ScrollView(this).apply { addView(col) })
        sheet.show()
    }

    private fun showScanner() {
        val wv = current()?.webView; val url = wv?.url
        if (url?.startsWith("http") != true) { toast("Open a website first"); return }
        if (!dev.inScope(url)) { toast("This host is out of scope — add it in Target & scope"); return }
        val out = TextView(this).apply {
            text = "Scanning $url …"; setTextIsSelectable(true); textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(getColor(R.color.text_dim))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("🛡 Passive scan")
            .setView(ScrollView(this).apply { addView(out) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy", null)
            .create()
        Thread {
            val findings = Scanner.scan(url)
            val text = findings.joinToString("\n\n") {
                "[${it.severity}] ${it.title}\n  ${it.evidence}\n  Fix: ${it.remediation}"
            }
            runOnUiThread {
                out.setTextColor(getColor(R.color.text)); out.text = text
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { copyText(text, "Scan copied") }
            }
        }.start()
        dialog.show()
    }

    private fun showScope() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        col.addView(label("In-scope hosts (one * pattern per line, blank = all)"))
        val inc = EditText(this).apply { setText(dev.scopeInclude); minLines = 3; maxLines = 6; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f; hint = "*.example.com\nlocalhost*" }
        col.addView(inc)
        col.addView(label("Excluded hosts (always out of scope)"))
        val exc = EditText(this).apply { setText(dev.scopeExclude); minLines = 2; maxLines = 5; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f; hint = "*.bank.com" }
        col.addView(exc)
        col.addView(TextView(this).apply {
            text = "Rules and the scanner act only on in-scope URLs."
            textSize = 12f; setTextColor(getColor(R.color.text_dim)); setPadding(0, dp(8), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("🎯 Target & scope")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                dev.scopeInclude = inc.text.toString(); dev.scopeExclude = exc.text.toString(); toast("Scope saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSequencer() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        col.addView(label("Paste tokens, one per line"))
        val input = EditText(this).apply { minLines = 5; maxLines = 12; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(input)
        val out = TextView(this).apply {
            setTextIsSelectable(true); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text)); setPadding(0, dp(10), 0, 0)
        }
        col.addView(out)
        val dialog = AlertDialog.Builder(this)
            .setTitle("🎲 Sequencer")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Analyse", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                out.text = Sequencer.analyse(input.text.toString().lines())
            }
        }
        dialog.show()
    }

    private fun showCapabilityMatrix() {
        val rows = listOf(
            "Overview / DevTools shell" to "✅ Implemented",
            "HTTP history" to "✅ Implemented (browser traffic)",
            "Request/response inspection" to "✅ Implemented",
            "Repeater" to "✅ Implemented",
            "Rules engine (ModHeader + Requestly)" to "✅ Implemented",
            "Redirect / block / mock / rewrite" to "✅ Implemented",
            "Query / URL / status / map-local / delay" to "✅ Implemented",
            "Encoder / Decoder (+JWT, hashes, JSON)" to "✅ Implemented",
            "Comparer" to "✅ Implemented",
            "Cookie manager" to "✅ Implemented",
            "Storage editor (local/session)" to "✅ Implemented",
            "Target & scope" to "✅ Implemented",
            "Passive security scanner" to "✅ Implemented",
            "Sequencer (token stats)" to "✅ Implemented",
            "HAR export / copy as cURL" to "✅ Implemented",
            "Network timing (DNS/TCP/TLS)" to "🟡 Partial — WebView exposes no timing",
            "DOM / CSS inspector" to "🟡 Partial — view-source + storage only",
            "WebSocket frame history" to "🟡 Partial — not captured by WebView",
            "Modify request body" to "⛔ Blocked — WebView hides request bodies",
            "System-wide intercepting proxy" to "⛔ Blocked — OS sandbox",
            "HTTPS MITM of other apps (CA/VPN)" to "⛔ Not built — would MITM other apps",
            "Active scanner / Intruder (flooding)" to "⛔ Not built — mass-attack tooling",
            "Collaborator callback service" to "⛔ Not built",
            "Plugin sandbox" to "⛔ Not built"
        )
        val matrixText = rows.joinToString("\n") { "${it.second}\n   ${it.first}" }
        val tv = TextView(this).apply {
            text = "What Injectra's Burp-style suite does and doesn't do:\n\n$matrixText"
            setTextIsSelectable(true); textSize = 12f; setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(getColor(R.color.text))
        }
        AlertDialog.Builder(this)
            .setTitle("📋 Capability matrix")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showRepeater(method: String, url: String, headers: String, body: String) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val methodSp = android.widget.Spinner(this).apply {
            val ms = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ms)
            setSelection(ms.indexOf(method.uppercase()).coerceAtLeast(0))
        }
        col.addView(label("Method")); col.addView(methodSp)
        col.addView(label("URL"))
        val urlField = EditText(this).apply { setText(url); setSingleLine(true) }; col.addView(urlField)
        col.addView(label("Headers (one per line, Name: value)"))
        val hdrField = EditText(this).apply { setText(headers); minLines = 3; maxLines = 8; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(hdrField)
        col.addView(label("Body (for POST/PUT/PATCH/DELETE)"))
        val bodyField = EditText(this).apply { setText(body); minLines = 2; maxLines = 8; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(bodyField)
        val respView = TextView(this).apply {
            setTextIsSelectable(true); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_dim)); setPadding(0, dp(10), 0, 0)
        }
        col.addView(respView)
        val dialog = AlertDialog.Builder(this)
            .setTitle("🔁 Repeater")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Send", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val m = methodSp.selectedItem as String
                val u = urlField.text.toString().trim()
                if (!u.startsWith("http")) { toast("Enter an http(s) URL"); return@setOnClickListener }
                respView.text = "Sending…"
                val h = hdrField.text.toString(); val b = bodyField.text.toString()
                Thread {
                    val r = Repeater.send(m, u, h, b)
                    runOnUiThread {
                        respView.setTextColor(getColor(if (r.ok) R.color.text else R.color.accent))
                        respView.text = "${r.statusLine}   (${r.millis} ms)\n\n${r.headers}\n\n${r.body}"
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun showDecoder() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        col.addView(label("Input"))
        val input = EditText(this).apply { minLines = 3; maxLines = 8; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(input)
        val schemes = listOf("Base64", "URL", "HTML", "Hex", "Unicode", "JSON", "JWT", "MD5", "SHA-1", "SHA-256", "Timestamp")
        col.addView(label("Scheme (Encode / Decode; JWT, hashes, JSON & timestamp use one button)"))
        val sp = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, schemes)
        }
        col.addView(sp)
        val out = TextView(this).apply {
            setTextIsSelectable(true); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text)); setPadding(0, dp(10), 0, 0)
        }
        col.addView(out)
        fun run(encode: Boolean) {
            val s = input.text.toString()
            out.text = when (sp.selectedItem as String) {
                "Base64" -> if (encode) Codec.base64Encode(s) else Codec.base64Decode(s)
                "URL" -> if (encode) Codec.urlEncode(s) else Codec.urlDecode(s)
                "HTML" -> if (encode) Codec.htmlEncode(s) else Codec.htmlDecode(s)
                "Hex" -> if (encode) Codec.hexEncode(s) else Codec.hexDecode(s)
                "Unicode" -> if (encode) Codec.unicodeEscape(s) else Codec.unicodeUnescape(s)
                "JSON" -> if (encode) Codec.minifyJson(s) else Codec.prettyJson(s)
                "JWT" -> Codec.jwtDecode(s)
                "MD5" -> Codec.hash("MD5", s)
                "SHA-1" -> Codec.hash("SHA-1", s)
                "SHA-256" -> Codec.hash("SHA-256", s)
                "Timestamp" -> Codec.timestamp(s)
                else -> s
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("🔡 Encoder / Decoder")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Encode", null)
            .setNegativeButton("Decode", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { run(true) }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { run(false) }
        }
        dialog.show()
    }

    private fun showComparer() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        col.addView(label("A"))
        val a = EditText(this).apply { minLines = 3; maxLines = 7; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(a)
        col.addView(label("B"))
        val b = EditText(this).apply { minLines = 3; maxLines = 7; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        col.addView(b)
        val out = TextView(this).apply {
            setTextIsSelectable(true); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text)); setPadding(0, dp(10), 0, 0)
        }
        col.addView(out)
        val dialog = AlertDialog.Builder(this)
            .setTitle("⚖️ Comparer")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Compare", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                out.text = TextDiff.diff(a.text.toString(), b.text.toString())
            }
        }
        dialog.show()
    }

    // ---- Storage editor (StorageAce) ---------------------------------------

    private fun showStorageEditor() {
        val wv = current()?.webView ?: return
        if (wv.url?.startsWith("http") != true) { toast("Open a website first"); return }
        val js = """(function(){function d(s){var o={};try{for(var i=0;i<s.length;i++){var k=s.key(i);o[k]=s.getItem(k);}}catch(e){}return o;}
            return JSON.stringify({local:d(localStorage),session:d(sessionStorage)});})();"""
        wv.evaluateJavascript(js) { raw ->
            val json = try { org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String) } catch (_: Exception) {
                try { org.json.JSONObject(raw.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")) } catch (_: Exception) { null }
            }
            if (json == null) { toast("Couldn't read storage (is JS enabled?)"); return@evaluateJavascript }
            renderStorage(wv, json)
        }
    }

    private fun renderStorage(wv: WebView, json: org.json.JSONObject) {
        val local = json.optJSONObject("local") ?: org.json.JSONObject()
        val session = json.optJSONObject("session") ?: org.json.JSONObject()
        data class Item(val area: String, val key: String, val value: String)
        val items = mutableListOf<Item>()
        for (k in local.keys()) items.add(Item("local", k, local.optString(k)))
        for (k in session.keys()) items.add(Item("session", k, session.optString(k)))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1,
                if (items.isEmpty()) listOf("(no storage items)") else items.map { "[${it.area}] ${it.key}\n   ${it.value.take(120)}" })
        }
        root.addView(list)
        val dialog = AlertDialog.Builder(this)
            .setTitle("🗄 Storage: ${Uri.parse(wv.url).host}")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNeutralButton("Add item", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { editStorageItem(wv, it.area, it.key, it.value) } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            items.getOrNull(pos)?.let { it0 ->
                confirm("Delete ${it0.area} \"${it0.key}\"?") {
                    wv.evaluateJavascript("${it0.area}Storage.removeItem(${jsStr(it0.key)});", null)
                    dialog.dismiss(); toast("Deleted"); showStorageEditor()
                }
            }; true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { editStorageItem(wv, "local", "", "") }
        }
        dialog.show()
    }

    private fun editStorageItem(wv: WebView, area: String, key: String, value: String) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        col.addView(label("Area"))
        val areas = listOf("local", "session")
        val spin = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, areas)
            setSelection(areas.indexOf(area).coerceAtLeast(0))
        }
        col.addView(spin)
        col.addView(label("Key"))
        val k = EditText(this).apply { setText(key); setSingleLine(true) }; col.addView(k)
        col.addView(label("Value"))
        val v = EditText(this).apply { setText(value); minLines = 1; maxLines = 8 }; col.addView(v)
        AlertDialog.Builder(this)
            .setTitle("Storage item")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                val kk = k.text.toString()
                if (kk.isBlank()) { toast("Key required"); return@setPositiveButton }
                wv.evaluateJavascript("${areas[spin.selectedItemPosition]}Storage.setItem(${jsStr(kk)},${jsStr(v.text.toString())});", null)
                toast("Saved"); showStorageEditor()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

    // ---- View source -------------------------------------------------------

    private fun viewSource() {
        val wv = current()?.webView ?: return
        if (wv.url?.startsWith("http") != true) { toast("Open a website first"); return }
        wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val html = try { org.json.JSONTokener(raw).nextValue() as? String ?: raw } catch (_: Exception) { raw }
            val tv = TextView(this).apply {
                text = html; setTextIsSelectable(true); setPadding(dp(16), dp(12), dp(16), dp(12))
                textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            }
            AlertDialog.Builder(this)
                .setTitle("📄 Source")
                .setView(ScrollView(this).apply { addView(tv) })
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy") { _, _ -> copyText(html, "Source copied") }
                .show()
        }
    }

    // ---- Quick toggles (Web Developer) -------------------------------------

    private fun showQuickToggles() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val js = switchRow("JavaScript", store.javaScriptEnabled)
        val img = switchRow("Load images", !store.blockImages)
        val cookies = switchRow("Accept cookies", CookieManager.getInstance().acceptCookie())
        col.addView(js.first); col.addView(img.first); col.addView(cookies.first)
        AlertDialog.Builder(this)
            .setTitle("🛠 Quick toggles")
            .setView(col)
            .setPositiveButton("Apply") { _, _ ->
                store.javaScriptEnabled = js.second.isChecked
                store.blockImages = !img.second.isChecked
                CookieManager.getInstance().setAcceptCookie(cookies.second.isChecked)
                for (t in tabs) {
                    t.webView.settings.javaScriptEnabled = store.javaScriptEnabled
                    t.webView.settings.blockNetworkImage = store.blockImages
                    t.webView.settings.loadsImagesAutomatically = !store.blockImages
                }
                current()?.webView?.reload()
                toast("Applied")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- About -------------------------------------------------------------

    private fun showAbout() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), 0) }
        col.addView(TextView(this).apply { text = "Injectra"; textSize = 26f; setTextColor(getColor(R.color.text)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(TextView(this).apply {
            text = "Developed by Nitin Shukla"; textSize = 15f; setTextColor(getColor(R.color.text)); setPadding(0, dp(8), 0, dp(2))
        })
        col.addView(TextView(this).apply {
            text = "A developer's browser with cookie injection, ModHeader & Requestly-style rules, a Burpsuite workbench (Repeater, Encoder, Comparer), storage editing and a network inspector."
            textSize = 13f; setTextColor(getColor(R.color.text_dim)); setPadding(0, 0, 0, dp(12))
        })
        col.addView(TextView(this).apply { text = "Projects"; textSize = 13f; setTextColor(getColor(R.color.text_dim)) })
        for ((name, link) in Store.DEV_PROJECTS) {
            col.addView(TextView(this).apply {
                text = "• $name"; textSize = 16f; setTextColor(getColor(R.color.accent)); setPadding(0, dp(10), 0, dp(10))
                isClickable = true; background = rippleBg()
                setOnClickListener { current()?.webView?.let { loadInto(it, link) } ?: createTab(link); dismissTopDialog() }
            })
        }
        aboutDialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Close", null)
            .create()
        aboutDialog?.show()
    }

    private var aboutDialog: AlertDialog? = null
    private fun dismissTopDialog() { aboutDialog?.dismiss() }

    // ---- small helpers -----------------------------------------------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rippleBg(): android.graphics.drawable.Drawable {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return getDrawable(outValue.resourceId) ?: android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
    }

    private fun simpleWatcher(onChange: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }
}
