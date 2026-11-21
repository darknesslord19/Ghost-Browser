package com.darknesslord19.ghostbrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class SiteData(val name: String, val sites: List<JSONObject>, val blockHosts: List<String>)

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    companion object {
        const val ASSET = "https://appassets.androidplatform.net/assets/"
        val MEDIA_RE = Regex("\\.(m3u8|mp4|webm|m4v|mov)(\\?|#|\$)", RegexOption.IGNORE_CASE)
        val DIRECT_RE = Regex("^https?://\\S+\\.(mp4|m3u8|webm|mkv|mov|m4v)(\\?\\S*)?(#.*)?\$", RegexOption.IGNORE_CASE)
    }

    private lateinit var store: Store
    private lateinit var history: History
    private lateinit var adblock: AdBlock
    private lateinit var remote: RemoteServer
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var ua: String

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newCachedThreadPool()
    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private var seq = 0
    private var isTv = false

    private lateinit var bar: LinearLayout
    private lateinit var btnBack: TextView
    private lateinit var btnFwd: TextView
    private lateinit var btnReload: TextView
    private lateinit var btnMedia: TextView
    private lateinit var btnTabs: TextView
    private lateinit var btnMenu: TextView
    private lateinit var omni: EditText
    private lateinit var progress: ProgressBar
    private lateinit var holder: FrameLayout
    private lateinit var cursor: CursorView

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var immersive = false

    private val sourceSites = LinkedHashMap<String, SiteData>()
    private val sourceState = HashMap<String, JSONObject>()
    private var defaultSites: List<JSONObject> = emptyList()
    @Volatile private var lastState = "{}"
    @Volatile private var sitesJson = "[]"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ================= yaşam döngüsü =================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val um = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        isTv = um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        store = Store(this, isTv)
        history = History(this)
        adblock = AdBlock(this)
        adblock.enabled = store.bool("adblock")
        adblock.setCustom(store.arr("customBlockHosts"))
        defaultSites = try {
            Sites.parse(assets.open("sites.default.json").bufferedReader().readText()).sites
        } catch (_: Exception) {
            emptyList()
        }
        loadSitesCache()
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        ua = WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(" Version/4.0", "")

        buildUi()

        remote = RemoteServer(main)
        remote.pageProvider = { assets.open("remote.html").readBytes() }
        remote.stateProvider = { lastState }
        remote.sitesProvider = { sitesJson }
        remote.onCommand = { onRemote(it) }

        sitesJson = computeSitesJson()
        val start = intent?.data?.toString()
        newTab(start ?: store.str("homepage"))
        startRemote()
        refreshSites()
        exec.execute { adblock.refreshFilter(store.str("filterListUrl"), false) }
        main.postDelayed(tick, 1000)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val u = intent?.data?.toString()
        if (u != null) newTab(u)
    }

    override fun onResume() {
        super.onResume()
        cur?.webView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        cur?.webView?.onPause()
    }

    override fun onDestroy() {
        remote.stop()
        main.removeCallbacksAndMessages(null)
        for (t in tabs) try { t.webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ================= arayüz =================
    private fun tb(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 19f
        t.setTextColor(Color.parseColor("#CFCFE2"))
        t.gravity = Gravity.CENTER
        t.isFocusable = true
        t.isClickable = true
        val bg = StateListDrawable()
        val f = GradientDrawable()
        f.setColor(Color.parseColor("#3B3B55"))
        f.cornerRadius = dp(10).toFloat()
        val pr = GradientDrawable()
        pr.setColor(Color.parseColor("#2A2A3D"))
        pr.cornerRadius = dp(10).toFloat()
        bg.addState(intArrayOf(android.R.attr.state_focused), f)
        bg.addState(intArrayOf(android.R.attr.state_pressed), pr)
        t.background = bg
        t.setOnClickListener { onClick() }
        t.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        return t
    }

    private fun buildUi() {
        window.statusBarColor = Color.parseColor("#14141D")
        window.navigationBarColor = Color.parseColor("#0B0B10")
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0B0B10"))

        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#14141D"))
        bar.setPadding(dp(4), 0, dp(4), 0)

        btnBack = tb("◀") { cur?.webView?.let { if (it.canGoBack()) it.goBack() } }
        btnFwd = tb("▶") { cur?.webView?.let { if (it.canGoForward()) it.goForward() } }
        btnReload = tb("⟳") {
            val t = cur
            if (t != null) { if (t.loading) t.webView.stopLoading() else t.webView.reload() }
        }
        val btnHome = tb("⌂") { cur?.let { loadIn(it, store.str("homepage")) } }
        omni = EditText(this)
        omni.setSingleLine()
        omni.hint = "Film ara ya da adres yaz…"
        omni.setTextColor(Color.WHITE)
        omni.setHintTextColor(Color.parseColor("#80809A"))
        omni.textSize = 14f
        omni.imeOptions = EditorInfo.IME_ACTION_GO
        omni.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val ob = GradientDrawable()
        ob.setColor(Color.parseColor("#0B0B10"))
        ob.cornerRadius = dp(20).toFloat()
        omni.background = ob
        omni.setPadding(dp(14), 0, dp(14), 0)
        omni.setOnEditorActionListener { v, _, _ ->
            val text = v.text.toString()
            omni.clearFocus()
            cur?.webView?.requestFocus()
            val t = cur
            if (t != null) loadIn(t, text) else newTab(text)
            true
        }
        omni.setOnFocusChangeListener { _, f -> if (f) omni.post { omni.selectAll() } else updateUi() }
        btnMedia = tb("🎬") { showMedia() }
        btnMedia.visibility = View.GONE
        btnTabs = tb("1") { showTabs() }
        btnTabs.textSize = 14f
        btnMenu = tb("⋮") { showMenu() }

        bar.addView(btnBack)
        bar.addView(btnFwd)
        bar.addView(btnReload)
        bar.addView(btnHome)
        val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
        lp.setMargins(dp(4), 0, dp(4), 0)
        bar.addView(omni, lp)
        bar.addView(btnMedia)
        bar.addView(btnTabs)
        bar.addView(btnMenu)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
        progress.visibility = View.GONE

        holder = FrameLayout(this)
        cursor = CursorView(this)
        val content = FrameLayout(this)
        content.addView(holder, FrameLayout.LayoutParams(-1, -1))
        content.addView(cursor, FrameLayout.LayoutParams(-1, -1))

        root.addView(bar, LinearLayout.LayoutParams(-1, dp(if (isTv) 60 else 52)))
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun updateUi() {
        val t = cur ?: return
        btnBack.alpha = if (t.webView.canGoBack()) 1f else 0.3f
        btnFwd.alpha = if (t.webView.canGoForward()) 1f else 0.3f
        btnReload.text = if (t.loading) "✕" else "⟳"
        btnTabs.text = tabs.size.toString()
        if (!omni.hasFocus()) omni.setText(display(t.url))
        if (t.media.isNotEmpty()) {
            btnMedia.visibility = View.VISIBLE
            btnMedia.text = "🎬" + t.media.size
        } else btnMedia.visibility = View.GONE
    }

    // ================= URL yardımcıları =================
    private fun mapGhost(s: String): String {
        val rest = s.removePrefix("ghost://")
        val name = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if (name != "newtab" && name != "settings" && name != "player") return ASSET + "newtab.html"
        val tail = rest.substring(name.length).removePrefix("/")
        return "$ASSET$name.html$tail"
    }

    fun playerUrl(src: String, title: String? = null, ref: String? = null): String =
        ASSET + "player.html?src=" + Uri.encode(src) +
            (if (!title.isNullOrEmpty()) "&title=" + Uri.encode(title) else "") +
            (if (!ref.isNullOrEmpty()) "&ref=" + Uri.encode(ref) else "")

    private fun resolve(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return ASSET + "newtab.html"
        if (s.startsWith("ghost://")) return mapGhost(s)
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(s)) return if (DIRECT_RE.matches(s)) playerUrl(s) else s
        if (s.startsWith("about:")) return s
        if (!s.contains(' ') && Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?([/?#].*)?\$", RegexOption.IGNORE_CASE).matches(s)) return "http://$s"
        if (!s.contains(' ') && Regex("^[^\\s/]+\\.[a-z]{2,}(:\\d+)?([/?#].*)?\$", RegexOption.IGNORE_CASE).matches(s)) {
            val u = "https://$s"
            return if (DIRECT_RE.matches(u)) playerUrl(u) else u
        }
        return store.str("searchEngine").replace("%s", Uri.encode(s))
    }

    private fun display(u: String): String = when {
        u.isEmpty() || u.startsWith(ASSET + "newtab") -> ""
        u.startsWith(ASSET + "player") -> "🎬 Ghost Oynatıcı"
        u.startsWith(ASSET + "settings") -> "ghost://settings"
        else -> u
    }

    private fun loadIn(tab: Tab, input: String) {
        val u = resolve(input)
        adblock.allowOnce(u)
        tab.url = u
        tab.webView.loadUrl(u)
    }

    // ================= sekmeler =================
    private fun newTab(input: String?, activate: Boolean = true): Tab {
        val wv = WebView(this)
        val tab = Tab(++seq, wv)
        configure(tab)
        tabs.add(tab)
        if (activate) switchTo(tab)
        if (input != null) loadIn(tab, input)
        updateUi()
        return tab
    }

    private fun switchTo(tab: Tab) {
        val old = cur
        if (old != null && old !== tab) {
            old.webView.onPause()
            holder.removeView(old.webView)
        }
        cur = tab
        if (tab.webView.parent == null) holder.addView(tab.webView, FrameLayout.LayoutParams(-1, -1))
        tab.webView.onResume()
        cursor.cx = tab.cx
        cursor.cy = tab.cy
        progress.visibility = if (tab.loading) View.VISIBLE else View.GONE
        updateUi()
        main.post { pushRemote(null) }
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        holder.removeView(tab.webView)
        try { tab.webView.destroy() } catch (_: Exception) {}
        if (tabs.isEmpty()) {
            cur = null
            newTab(store.str("homepage"))
        } else if (cur === tab) {
            cur = null
            switchTo(tabs[minOf(idx, tabs.size - 1)])
        } else updateUi()
    }

    private fun showTabs() {
        val names = tabs.map { (if (it === cur) "● " else "   ") + (it.title.ifEmpty { display(it.url) }.ifEmpty { "Yeni sekme" }) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Sekmeler (${tabs.size})")
            .setItems(names) { _, i -> switchTo(tabs[i]) }
            .setPositiveButton("＋ Yeni sekme") { _, _ -> newTab("ghost://newtab") }
            .setNeutralButton("Bu sekmeyi kapat") { _, _ -> cur?.let { closeTab(it) } }
            .show()
    }

    private fun showMedia() {
        val t = cur ?: return
        val items = t.media.map {
            val u = Uri.parse(it.url)
            it.kind + " · " + (u.host ?: "") + (u.path ?: "").takeLast(36)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🎬 Ghost Oynatıcı'da aç")
            .setItems(items) { _, i -> val m = t.media[i]; newTab(playerUrl(m.url, t.title, m.ref)) }
            .show()
    }

    private fun showMenu() {
        val m = PopupMenu(this, btnMenu)
        m.menu.add(0, 1, 0, "Yeni sekme")
        m.menu.add(0, 2, 1, "Sekmeler")
        m.menu.add(0, 3, 2, "Geçmiş")
        m.menu.add(0, 4, 3, "Ayarlar")
        m.menu.add(0, 5, 4, "📱 Telefon kumandası (QR)")
        m.menu.add(0, 6, 5, (if (store.bool("tvMode")) "✓ " else "") + "TV modu")
        m.menu.add(0, 7, 6, (if (store.bool("adblock")) "✓ " else "") + "Reklam engelleyici")
        m.menu.add(0, 8, 7, "Tam ekran")
        m.menu.add(0, 9, 8, "Çıkış")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> newTab("ghost://newtab")
                2 -> showTabs()
                3 -> openInternal("settings#history")
                4 -> openInternal("settings")
                5 -> openInternal("settings#remote")
                6 -> { store.patch(JSONObject().put("tvMode", !store.bool("tvMode"))); toast(if (store.bool("tvMode")) "TV modu açık" else "TV modu kapalı") }
                7 -> { store.patch(JSONObject().put("adblock", !store.bool("adblock"))); adblock.enabled = store.bool("adblock") }
                8 -> toggleImmersive()
                9 -> finish()
            }
            true
        }
        m.show()
    }

    private fun openInternal(name: String) {
        val base = ASSET + name.substringBefore('#') + ".html"
        val ex = tabs.firstOrNull { it.url.startsWith(base) }
        if (ex != null) {
            switchTo(ex)
            loadIn(ex, "ghost://$name")
        } else newTab("ghost://$name")
    }

    private fun toggleImmersive() {
        immersive = !immersive
        bar.visibility = if (immersive) View.GONE else View.VISIBLE
        window.decorView.systemUiVisibility = if (immersive)
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        else 0
    }

    // ================= WebView =================
    private fun configure(tab: Tab) {
        val wv = tab.webView
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.userAgentString = ua
        wv.setBackgroundColor(Color.parseColor("#0B0B10"))
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, "GhostNative", setOf("https://appassets.androidplatform.net")) { _, message, _, _, reply ->
                onBridge(tab, reply, message.data ?: "")
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val r = DownloadManager.Request(Uri.parse(url))
                r.setMimeType(mimetype)
                r.addRequestHeader("User-Agent", userAgent)
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
                toast("İndiriliyor…")
            } catch (e: Exception) {
                toast("İndirilemedi")
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                assetLoader.shouldInterceptRequest(request.url)?.let { return it }
                if (tab.isPlayer) return proxyRequest(tab, request)
                val url = request.url.toString()
                if (!request.isForMainFrame && MEDIA_RE.containsMatchIn(url)) sniff(tab, url)
                if (adblock.shouldBlock(url, request.isForMainFrame, if (request.isForMainFrame) null else tab.pageHost)) {
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:")) return false
                if (u.startsWith("ghost://")) {
                    loadIn(tab, u)
                    return true
                }
                val sch = request.url.scheme ?: ""
                if (request.hasGesture() && (sch == "tel" || sch == "mailto" || sch == "sms")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                tab.url = url
                tab.pageHost = (try { Uri.parse(url).host } catch (_: Exception) { null }) ?: ""
                tab.loading = true
                tab.media.clear()
                tab.playerRef = if (url.startsWith(ASSET + "player")) (Uri.parse(url).getQueryParameter("ref") ?: "") else ""
                if (tab === cur) progress.visibility = View.VISIBLE
                updateUi()
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.loading = false
                tab.title = view.title ?: ""
                if (tab === cur) progress.visibility = View.GONE
                if (!url.startsWith(ASSET) && url.startsWith("http")) {
                    history.add(url, view.title)
                    if (store.bool("adblock") && store.bool("cosmetic") && adblock.cosmeticCss.isNotEmpty()) {
                        view.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=" + JSONObject.quote(adblock.cosmeticCss) + ";(document.head||document.documentElement).appendChild(s)})()", null)
                    }
                }
                updateUi()
                pushRemote(null)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (url.startsWith("http") || url.startsWith(ASSET)) tab.url = url
                if (tab === cur) updateUi()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (!request.isForMainFrame) return
                val html = "<meta name=viewport content='width=device-width,initial-scale=1'><body style='margin:0;display:grid;place-items:center;min-height:100vh;background:#0b0b10;color:#e8e8f0;font:16px system-ui;text-align:center'><div style='padding:24px'><div style='font-size:54px'>👻</div><h2>Sayfa açılamadı</h2><p style='opacity:.7;word-break:break-all'>" +
                    request.url.toString().replace("<", "").replace(">", "") + "</p><p style='color:#ff7a90'>" + error.description + "</p></div>"
                view.loadDataWithBaseURL(request.url.toString(), html, "text/html", "utf-8", request.url.toString())
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tab === cur) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title = title ?: ""
                history.setTitle(view.url ?: "", title)
                updateUi()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCb = callback
                (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(-1, -1))
                view.setBackgroundColor(Color.BLACK)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }

            override fun onHideCustomView() {
                hideCustom()
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val now = System.currentTimeMillis()
                val block = store.bool("popupBlock")
                if (block && (!isUserGesture || now - tab.lastPopup < 1200)) {
                    adblock.popups.incrementAndGet()
                    return false
                }
                tab.lastPopup = now
                val nt = newTab(null, true)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = nt.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabs.firstOrNull { it.webView === window }?.let { closeTab(it) }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("Tamam") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("Tamam") { _, _ -> result.confirm() }
                    .setNegativeButton("İptal") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
    }

    private fun hideCustom() {
        val v = customView ?: return
        (window.decorView as ViewGroup).removeView(v)
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = 0
        immersive = false
        bar.visibility = View.VISIBLE
    }

    private fun sniff(tab: Tab, url: String) {
        main.post {
            if (tab.media.any { it.url == url }) return@post
            tab.media.add(0, MediaItem(url, tab.url, if (url.contains("m3u8", true)) "HLS" else "Video"))
            while (tab.media.size > 15) tab.media.removeAt(tab.media.size - 1)
            if (tab === cur) updateUi()
        }
    }

    /** Oynatıcı sayfasının istekleri: Referer/Origin ekle + CORS izni ver. */
    private fun proxyRequest(tab: Tab, req: WebResourceRequest): WebResourceResponse? {
        val cors = HashMap<String, String>()
        cors["Access-Control-Allow-Origin"] = "*"
        cors["Access-Control-Allow-Headers"] = "*"
        cors["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS"
        cors["Access-Control-Expose-Headers"] = "*"
        if (req.method == "OPTIONS") return WebResourceResponse("text/plain", "utf-8", 204, "No Content", cors, ByteArrayInputStream(ByteArray(0)))
        if (req.method != "GET" && req.method != "HEAD") return null
        return try {
            val c = URL(req.url.toString()).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            for ((k, v) in req.requestHeaders) {
                val kl = k.lowercase()
                if (kl == "origin" || kl == "referer" || kl == "host" || kl == "accept-encoding") continue
                c.setRequestProperty(k, v)
            }
            val ref = tab.playerRef
            if (ref.isNotEmpty()) {
                c.setRequestProperty("Referer", ref)
                try {
                    val ru = Uri.parse(ref)
                    c.setRequestProperty("Origin", ru.scheme + "://" + ru.authority)
                } catch (_: Exception) {}
            }
            val code = c.responseCode
            val stream = (if (code >= 400) c.errorStream else c.inputStream) ?: ByteArrayInputStream(ByteArray(0))
            val ct = c.contentType ?: "application/octet-stream"
            val mime = ct.substringBefore(';').trim()
            val enc = Regex("charset=([^;]+)").find(ct)?.groupValues?.get(1)
            val h = HashMap<String, String>(cors)
            for ((k, v) in c.headerFields) {
                if (k == null || v == null || v.isEmpty()) continue
                val kl = k.lowercase()
                if (kl == "content-range" || kl == "accept-ranges" || kl == "etag" || kl == "last-modified") h[k] = v[v.size - 1]
            }
            val reason = c.responseMessage
            WebResourceResponse(mime, enc, code, if (reason.isNullOrEmpty()) "OK" else reason, h, stream)
        } catch (e: Exception) {
            null
        }
    }

    // ================= köprü (iç sayfalar <-> Kotlin) =================
    private fun onBridge(tab: Tab, proxy: JavaScriptReplyProxy, msg: String) {
        val m = try { JSONObject(msg) } catch (_: Exception) { return }
        val id = m.optInt("id")
        val name = m.optString("name")
        val args = m.optJSONArray("args") ?: JSONArray()

        fun reply(ok: Boolean, result: Any?, err: String? = null) {
            val o = JSONObject()
            o.put("id", id)
            o.put("ok", ok)
            if (ok) o.put("result", result ?: JSONObject.NULL) else o.put("error", err ?: "hata")
            main.post { try { proxy.postMessage(o.toString()) } catch (_: Exception) {} }
        }

        try {
            when (name) {
                "settings" -> reply(true, store.settings)
                "set" -> {
                    val oldPort = store.int("remotePort")
                    val oldEn = store.bool("remoteEnabled")
                    val oldFl = store.str("filterListUrl")
                    store.patch(args.optJSONObject(0) ?: JSONObject())
                    adblock.enabled = store.bool("adblock")
                    adblock.setCustom(store.arr("customBlockHosts"))
                    if (oldPort != store.int("remotePort") || oldEn != store.bool("remoteEnabled")) startRemote()
                    if (oldFl != store.str("filterListUrl")) exec.execute { adblock.refreshFilter(store.str("filterListUrl"), true) }
                    reply(true, store.settings)
                }
                "newtab" -> {
                    val o = JSONObject()
                    o.put("sites", mergedSites())
                    o.put("recent", history.recent(12))
                    o.put("top", history.top(8))
                    o.put("tv", store.bool("tvMode"))
                    o.put("sources", sourcesStatus())
                    o.put("searchEngine", store.str("searchEngine"))
                    reply(true, o)
                }
                "sites" -> {
                    val o = JSONObject()
                    o.put("sites", mergedSites())
                    o.put("sources", sourcesStatus())
                    o.put("local", store.arr("localSites"))
                    reply(true, o)
                }
                "sites-refresh" -> refreshSites { reply(true, JSONObject().put("sites", mergedSites()).put("sources", sourcesStatus())) }
                "source-add" -> {
                    val url = args.optString(0).trim()
                    if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                        reply(false, null, "Geçerli bir https bağlantısı gir")
                    } else {
                        val cur0 = store.arr("sitesSources")
                        var has = false
                        for (i in 0 until cur0.length()) if (cur0.optString(i) == url) has = true
                        if (!has) { cur0.put(url); store.settings.put("sitesSources", cur0); store.save() }
                        refreshSites { reply(true, JSONObject().put("sources", sourcesStatus()).put("sites", mergedSites())) }
                    }
                }
                "source-remove" -> {
                    val url = args.optString(0)
                    val n = JSONArray()
                    val cur0 = store.arr("sitesSources")
                    for (i in 0 until cur0.length()) if (cur0.optString(i) != url) n.put(cur0.optString(i))
                    store.settings.put("sitesSources", n)
                    store.save()
                    refreshSites { reply(true, JSONObject().put("sources", sourcesStatus()).put("sites", mergedSites())) }
                }
                "local-add" -> {
                    val c = Sites.clean(args.optJSONObject(0))
                    if (c == null) reply(false, null, "Geçersiz adres") else {
                        val n = JSONArray()
                        val old = store.arr("localSites")
                        for (i in 0 until old.length()) if (old.getJSONObject(i).optString("url") != c.optString("url")) n.put(old.getJSONObject(i))
                        n.put(c)
                        store.settings.put("localSites", n)
                        store.save()
                        sitesChanged()
                        reply(true, JSONObject().put("local", n))
                    }
                }
                "local-remove" -> {
                    val url = args.optString(0)
                    val n = JSONArray()
                    val old = store.arr("localSites")
                    for (i in 0 until old.length()) if (old.getJSONObject(i).optString("url") != url) n.put(old.getJSONObject(i))
                    store.settings.put("localSites", n)
                    store.save()
                    sitesChanged()
                    reply(true, JSONObject().put("local", n))
                }
                "history" -> reply(true, history.recent(minOf(args.optInt(1, 100), 500), args.optString(0)))
                "history-clear" -> { history.clear(); reply(true, true) }
                "history-remove" -> { history.remove(args.optString(0)); reply(true, true) }
                "open" -> {
                    val url = args.optString(0)
                    val nt = args.optJSONObject(1)?.optBoolean("newTab") == true
                    if (nt) newTab(url) else loadIn(tab, url)
                    reply(true, true)
                }
                "adblock" -> reply(true, adblock.summary())
                "adblock-refresh" -> exec.execute {
                    adblock.refreshFilter(store.str("filterListUrl"), true)
                    reply(true, adblock.summary())
                }
                "remote" -> reply(true, remoteInfo())
                "remote-regen" -> {
                    store.newToken()
                    startRemote()
                    main.postDelayed({ reply(true, remoteInfo()) }, 400)
                }
                "copy" -> {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ghost", args.optString(0)))
                    reply(true, true)
                }
                else -> reply(false, null, "bilinmeyen: $name")
            }
        } catch (e: Exception) {
            reply(false, null, e.message)
        }
    }

    // ================= siteler =================
    private fun loadSitesCache() {
        try {
            val o = JSONObject(getSharedPreferences("gb", MODE_PRIVATE).getString("sitesCache", "{}") ?: "{}")
            val it = o.keys()
            while (it.hasNext()) {
                val u = it.next()
                val e = o.getJSONObject(u)
                val sites = ArrayList<JSONObject>()
                val a = e.optJSONArray("sites") ?: JSONArray()
                for (i in 0 until a.length()) sites.add(a.getJSONObject(i))
                val bh = ArrayList<String>()
                val b = e.optJSONArray("blockHosts") ?: JSONArray()
                for (i in 0 until b.length()) bh.add(b.optString(i))
                sourceSites[u] = SiteData(e.optString("name"), sites, bh)
            }
        } catch (_: Exception) {
        }
        adblock.setPushed(sourceSites.values.flatMap { it.blockHosts })
    }

    private fun saveSitesCache() {
        val o = JSONObject()
        for ((u, d) in sourceSites) {
            val e = JSONObject()
            e.put("name", d.name)
            e.put("sites", JSONArray(d.sites))
            e.put("blockHosts", JSONArray(d.blockHosts))
            o.put(u, e)
        }
        getSharedPreferences("gb", MODE_PRIVATE).edit().putString("sitesCache", o.toString()).apply()
    }

    private fun refreshSites(done: (() -> Unit)? = null) {
        val srcs = store.arr("sitesSources")
        val list = ArrayList<String>()
        for (i in 0 until srcs.length()) list.add(srcs.getString(i))
        exec.execute {
            val results = LinkedHashMap<String, Any>()
            for (u in list) {
                try { results[u] = Sites.fetch(u) } catch (e: Exception) { results[u] = e.message ?: "hata" }
            }
            main.post {
                for ((u, r) in results) {
                    val st = JSONObject()
                    st.put("at", System.currentTimeMillis())
                    if (r is Fetched) {
                        sourceSites[u] = SiteData(r.parsed.name, r.parsed.sites, r.parsed.blockHosts)
                        st.put("ok", true)
                        st.put("count", r.parsed.sites.size)
                        st.put("name", r.parsed.name)
                        st.put("resolved", r.resolved)
                    } else {
                        val c = sourceSites[u]
                        st.put("ok", false)
                        st.put("error", r.toString())
                        st.put("count", c?.sites?.size ?: 0)
                        st.put("cached", c != null)
                    }
                    sourceState[u] = st
                }
                sourceSites.keys.retainAll(list.toSet())
                saveSitesCache()
                adblock.setPushed(sourceSites.values.flatMap { it.blockHosts })
                sitesChanged()
                done?.invoke()
            }
        }
    }

    private fun sitesChanged() {
        sitesJson = computeSitesJson()
        exec.execute { remote.pushSites(sitesJson) }
    }

    private fun mergedSites(): JSONArray {
        val out = JSONArray()
        val seen = HashSet<String>()
        fun add(s: JSONObject, src: String) {
            val u = s.optString("url")
            if (seen.add(u)) {
                val c = JSONObject(s.toString())
                c.put("src", src)
                out.put(c)
            }
        }
        val srcs = store.arr("sitesSources")
        for (i in 0 until srcs.length()) sourceSites[srcs.optString(i)]?.sites?.forEach { add(it, "github") }
        val loc = store.arr("localSites")
        for (i in 0 until loc.length()) add(loc.getJSONObject(i), "local")
        if (out.length() == 0) defaultSites.forEach { add(it, "builtin") }
        return out
    }

    private fun computeSitesJson(): String {
        val a = JSONArray()
        val m = mergedSites()
        for (i in 0 until m.length()) {
            val s = m.getJSONObject(i)
            a.put(JSONObject().put("name", s.optString("name")).put("url", s.optString("url")).put("category", s.optString("category")))
        }
        return a.toString()
    }

    private fun sourcesStatus(): JSONArray {
        val out = JSONArray()
        val srcs = store.arr("sitesSources")
        for (i in 0 until srcs.length()) {
            val u = srcs.optString(i)
            val st = sourceState[u]
            val o = if (st != null) JSONObject(st.toString()) else JSONObject().put("pending", true)
            o.put("url", u)
            out.put(o)
        }
        return out
    }

    // ================= kumanda =================
    private fun startRemote() {
        remote.stop()
        if (!store.bool("remoteEnabled")) return
        val port = store.int("remotePort")
        val tok = store.str("remoteToken")
        exec.execute { remote.start(port, tok) }
    }

    private fun remoteInfo(): JSONObject {
        val o = JSONObject()
        o.put("enabled", store.bool("remoteEnabled"))
        o.put("port", remote.port)
        o.put("token", store.str("remoteToken"))
        val urls = JSONArray()
        if (remote.port > 0) {
            var list = RemoteServer.lanAddresses()
            if (list.isEmpty()) list = listOf(Pair("loopback", "127.0.0.1"))
            for ((n, ip) in list) urls.put(JSONObject().put("name", n).put("ip", ip).put("url", "http://$ip:${remote.port}/r?t=${store.str("remoteToken")}"))
        }
        o.put("urls", urls)
        return o
    }

    private val tick = object : Runnable {
        override fun run() {
            if (remote.hasClients()) {
                val t = cur
                if (t != null) {
                    t.webView.evaluateJavascript(MediaScript.build("state", null)) { r -> pushRemote(parseMedia(r)) }
                } else pushRemote(null)
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun parseMedia(r: String?): JSONObject? {
        if (r == null || r == "null" || r.isEmpty()) return null
        return try { JSONObject(r) } catch (_: Exception) { null }
    }

    private fun pushRemote(media: JSONObject?) {
        val o = JSONObject()
        val ta = JSONArray()
        for (t in tabs) ta.put(JSONObject().put("id", t.id).put("title", t.title.ifEmpty { "Sekme" }).put("active", t === cur))
        val t = cur
        o.put("tabs", ta)
        o.put("title", t?.title ?: "")
        o.put("url", display(t?.url ?: ""))
        o.put("loading", t?.loading == true)
        o.put("canBack", t?.webView?.canGoBack() == true)
        o.put("canForward", t?.webView?.canGoForward() == true)
        o.put("mediaCount", t?.media?.size ?: 0)
        o.put("media", media ?: JSONObject.NULL)
        o.put("tv", store.bool("tvMode"))
        o.put("blocked", adblock.blocked.get())
        o.put("isPlayer", t?.isPlayer == true)
        lastState = o.toString()
        if (remote.hasClients()) remote.pushState(lastState)
    }

    private fun onRemote(c: JSONObject) {
        val t = cur
        val density = resources.displayMetrics.density
        val speed = store.dbl("cursorSpeed").toFloat()
        when (c.optString("type")) {
            "open" -> {
                val v = c.optString("value")
                if (v.isNotBlank()) {
                    if (c.optBoolean("newTab") || t == null) newTab(v) else loadIn(t, v)
                }
            }
            "tab" -> when (c.optString("action")) {
                "new" -> newTab("ghost://newtab")
                "close" -> {
                    val target = tabs.firstOrNull { it.id == c.optInt("id") } ?: t
                    if (target != null) closeTab(target)
                }
                "activate" -> tabs.firstOrNull { it.id == c.optInt("id") }?.let { switchTo(it) }
            }
            "nav" -> if (t != null) when (c.optString("action")) {
                "back" -> if (t.webView.canGoBack()) t.webView.goBack()
                "forward" -> if (t.webView.canGoForward()) t.webView.goForward()
                "reload" -> t.webView.reload()
                "home" -> loadIn(t, store.str("homepage"))
            }
            "key" -> if (t != null) sendKey(t, c.optString("key"))
            "move" -> if (t != null) moveCursor(t, c.optDouble("dx", 0.0).toFloat() * density * speed, c.optDouble("dy", 0.0).toFloat() * density * speed)
            "click" -> if (t != null) tap(t)
            "scroll" -> t?.webView?.evaluateJavascript("window.scrollBy(0," + (c.optDouble("dy", 0.0) * 1.2).toInt() + ")", null)
            "text" -> if (t != null) {
                val js = "(function(t){var e=document.activeElement;if(!e)return;if(e.isContentEditable){document.execCommand('insertText',false,t);return}if('value' in e){try{var s=e.selectionStart,n=e.selectionEnd;e.setRangeText(t,s,n,'end')}catch(_){e.value+=t}e.dispatchEvent(new Event('input',{bubbles:true}))}})(" + JSONObject.quote(c.optString("value").take(1000)) + ")"
                t.webView.evaluateJavascript(js, null)
            }
            "media" -> if (t != null) {
                val arg = if (c.isNull("arg")) null else c.optDouble("arg")
                t.webView.evaluateJavascript(MediaScript.build(c.optString("cmd"), arg)) { r -> pushRemote(parseMedia(r)) }
            }
            "player" -> if (t != null && t.media.isNotEmpty()) {
                val m = t.media[minOf(c.optInt("index"), t.media.size - 1)]
                newTab(playerUrl(m.url, t.title, m.ref))
            }
            "fullscreen" -> toggleImmersive()
            "zoom" -> if (t != null) {
                val z = t.webView.settings.textZoom
                t.webView.settings.textZoom = if (c.optBoolean("reset")) 100 else (z + (c.optDouble("delta") * 20).toInt()).coerceIn(50, 250)
            }
        }
    }

    // ================= imleç / klavye =================
    private fun moveCursor(t: Tab, dx: Float, dy: Float) {
        val w = holder.width.toFloat()
        val h = holder.height.toFloat()
        if (w <= 0f || h <= 0f) return
        t.cx = (t.cx + dx).coerceIn(2f, w - 3f)
        t.cy = (t.cy + dy).coerceIn(2f, h - 3f)
        cursor.cx = t.cx
        cursor.cy = t.cy
        cursor.show()
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, t.cx, t.cy, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        t.webView.dispatchGenericMotionEvent(ev)
        ev.recycle()
        if (dy > 0 && t.cy >= h - 8) t.webView.scrollBy(0, (dy * 2).toInt())
        else if (dy < 0 && t.cy <= 8) t.webView.scrollBy(0, (dy * 2).toInt())
    }

    private fun tap(t: Tab) {
        val x = t.cx
        val y = t.cy
        val now = SystemClock.uptimeMillis()
        t.webView.requestFocus()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        t.webView.dispatchTouchEvent(down)
        down.recycle()
        main.postDelayed({
            val t2 = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(now, t2, MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            t.webView.dispatchTouchEvent(up)
            up.recycle()
        }, 50)
        cursor.show()
    }

    private fun sendKey(t: Tab, name: String) {
        val code = when (name) {
            "Up" -> KeyEvent.KEYCODE_DPAD_UP
            "Down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "Left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "Right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "Enter" -> KeyEvent.KEYCODE_ENTER
            "Backspace" -> KeyEvent.KEYCODE_DEL
            "Esc", "Escape" -> { if (customView != null) { hideCustom(); return }; KeyEvent.KEYCODE_ESCAPE }
            "Tab" -> KeyEvent.KEYCODE_TAB
            "Space" -> KeyEvent.KEYCODE_SPACE
            "PageDown" -> KeyEvent.KEYCODE_PAGE_DOWN
            "PageUp" -> KeyEvent.KEYCODE_PAGE_UP
            else -> return
        }
        t.webView.requestFocus()
        val now = SystemClock.uptimeMillis()
        t.webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        t.webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val t = cur
        val tv = store.bool("tvMode")
        if (t != null && tv && customView == null) {
            val k = e.keyCode
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_BUTTON_Y) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                    if (bar.hasFocus()) t.webView.requestFocus() else btnBack.requestFocus()
                }
                return true
            }
            if (bar.hasFocus()) {
                if (k == KeyEvent.KEYCODE_DPAD_DOWN && !omni.hasFocus()) {
                    if (e.action == KeyEvent.ACTION_DOWN) t.webView.requestFocus()
                    return true
                }
                return super.dispatchKeyEvent(e)
            }
            if (!t.isInternal) {
                val isDir = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                val isOk = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (isDir || isOk) {
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        if (isOk) {
                            if (e.repeatCount == 0) tap(t)
                        } else {
                            if (k == KeyEvent.KEYCODE_DPAD_UP && t.cy <= 10f) {
                                btnBack.requestFocus()
                                return true
                            }
                            val accel = minOf(1f + e.repeatCount * 0.5f, 8f)
                            val step = 9f * resources.displayMetrics.density * accel * store.dbl("cursorSpeed").toFloat()
                            when (k) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(t, -step, 0f)
                                KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor(t, step, 0f)
                                KeyEvent.KEYCODE_DPAD_UP -> moveCursor(t, 0f, -step)
                                else -> moveCursor(t, 0f, step)
                            }
                        }
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            hideCustom()
            return
        }
        if (bar.hasFocus() && store.bool("tvMode")) {
            cur?.webView?.requestFocus()
            return
        }
        val t = cur
        if (t != null && t.webView.canGoBack()) t.webView.goBack()
        else if (tabs.size > 1 && t != null) closeTab(t)
        else super.onBackPressed()
    }
}
