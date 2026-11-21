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
import android.graphics.drawable.ColorDrawable
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
import android.widget.PopupWindow
import android.widget.ScrollView
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
        val IMG_RE = Regex("\\.(jpe?g|png|webp|gif|svg|ts|m4s|vtt|srt|css|js)(\\?|#|\$)", RegexOption.IGNORE_CASE)
        val MEDIA_RE = Regex("\\.(m3u8|mpd|mp4|webm|m4v|mov|mkv)(\\?|#|\$)|m3u8|videoplayback", RegexOption.IGNORE_CASE)
        val SNIFF_JS = """(()=>{if(window.__gs)return;window.__gs=1;const seen=new Set();
const bad=u=>/\.(jpe?g|png|webp|gif|svg|ts|m4s|vtt|srt|css|js)(\?|#|${'$'})/i.test(u);
const ok=u=>!bad(u)&&(/\.(m3u8|mpd|mp4|webm|m4v|mov|mkv)(\?|#|${'$'})/i.test(u)||/m3u8|videoplayback/i.test(u));
const send=(u,f,mst)=>{try{if(!u||typeof u!=='string')return;u=new URL(u,location.href).href;if(!/^https?:/.test(u)||seen.has(u)||(!f&&!ok(u)))return;seen.add(u);GhostSniff.postMessage(JSON.stringify({u:u,r:location.href,m:mst?1:0}))}catch(e){}};
const head=t=>{t=t.trim();return t.indexOf('#EXTM3U')==0?(t.indexOf('#EXT-X-STREAM-INF')>0?2:1):0};
const chk=(u,r)=>{try{const rd=r.clone().body.getReader();rd.read().then(a=>{try{rd.cancel()}catch(e){}if(a.value){const k=head(new TextDecoder().decode(a.value.subarray(0,4096)));if(k)send(u,1,k==2)}}).catch(()=>{})}catch(e){}};
const of=window.fetch;if(of)window.fetch=function(a){const u=typeof a==='string'?a:a&&a.url;try{send(u)}catch(e){}const p=of.apply(this,arguments);try{p.then(r=>{if(r&&r.ok&&r.body)chk(r.url||u,r)}).catch(()=>{})}catch(e){}return p};
const xo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){try{const U=String(u);send(U);this.addEventListener('load',()=>{try{let t='';if(this.responseType===''||this.responseType==='text')t=(this.responseText||'').slice(0,4096);else if(this.responseType==='arraybuffer'&&this.response)t=new TextDecoder().decode(new Uint8Array(this.response,0,Math.min(4096,this.response.byteLength)));const k=head(t);if(k)send(U,1,k==2)}catch(e){}})}catch(e){}return xo.apply(this,arguments)};
const scan=()=>{try{document.querySelectorAll('video,source').forEach(e=>{send(e.currentSrc);send(e.src);send(e.getAttribute('src'))})}catch(e){}};
setInterval(scan,1200);document.addEventListener('DOMContentLoaded',scan);
})();"""
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
    private lateinit var pill: LinearLayout
    private lateinit var btnBack: IconView
    private lateinit var btnFwd: IconView
    private lateinit var btnHome: IconView
    private lateinit var btnReload: IconView
    private lateinit var siteIcon: IconView
    private lateinit var btnMedia: TextView
    private lateinit var btnTabs: TabCountView
    private lateinit var btnMenu: IconView
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

    // ================= arayüz (telefon + TV uyumlu) =================
    private val cBar = Color.parseColor("#14141D")
    private val cPill = Color.parseColor("#25253A")
    private val cAcc = Color.parseColor("#8B5CF6")
    private val cDim = Color.parseColor("#9A9AB2")

    /** TV'de ya da TV modu açıkken arayüz büyür. */
    private fun big() = isTv || store.bool("tvMode")

    private fun focusBg(radiusDp: Int = 12): StateListDrawable {
        val bg = StateListDrawable()
        val f = GradientDrawable()
        f.setColor(Color.parseColor("#2E2E48"))
        f.cornerRadius = dp(radiusDp).toFloat()
        f.setStroke(dp(2), cAcc)
        val pr = GradientDrawable()
        pr.setColor(Color.parseColor("#2A2A3D"))
        pr.cornerRadius = dp(radiusDp).toFloat()
        bg.addState(intArrayOf(android.R.attr.state_focused), f)
        bg.addState(intArrayOf(android.R.attr.state_pressed), pr)
        return bg
    }

    private fun pillBg(focused: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(cPill)
        g.cornerRadius = dp(24).toFloat()
        if (focused) g.setStroke(dp(2), cAcc)
        return g
    }

    private fun ib(kind: String, onClick: () -> Unit): IconView {
        val v = IconView(this, kind)
        v.isFocusable = true
        v.isClickable = true
        v.background = focusBg()
        v.setPadding(dp(8), dp(8), dp(8), dp(8))
        v.setOnClickListener { onClick() }
        return v
    }

    private fun buildUi() {
        window.statusBarColor = cBar
        window.navigationBarColor = Color.parseColor("#0B0B10")
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0B0B10"))

        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(cBar)
        bar.setPadding(dp(8), dp(6), dp(4), dp(6))

        btnBack = ib("back") { cur?.webView?.let { if (it.canGoBack()) it.goBack() } }
        btnFwd = ib("forward") { cur?.webView?.let { if (it.canGoForward()) it.goForward() } }
        btnHome = ib("home") { cur?.let { loadIn(it, store.str("homepage")) } }

        pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = pillBg(false)
        pill.setPadding(dp(14), 0, dp(4), 0)
        siteIcon = IconView(this, "search")
        siteIcon.tint = cDim
        omni = EditText(this)
        omni.setSingleLine()
        omni.hint = "Ara veya adres yaz"
        omni.setTextColor(Color.WHITE)
        omni.setHintTextColor(Color.parseColor("#80809A"))
        omni.setBackgroundColor(Color.TRANSPARENT)
        omni.setPadding(dp(10), 0, dp(4), 0)
        omni.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        omni.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        omni.setOnEditorActionListener { v, _, _ ->
            val text = v.text.toString()
            omni.clearFocus()
            cur?.webView?.requestFocus()
            val t = cur
            if (t != null) loadIn(t, text) else newTab(text)
            true
        }
        omni.setOnFocusChangeListener { _, f ->
            pill.background = pillBg(f)
            if (f) {
                omni.setText(display(cur?.url ?: ""))
                omni.post { omni.selectAll() }
            } else updateUi()
        }
        btnReload = ib("reload") {
            val t = cur
            if (t != null) { if (t.loading) t.webView.stopLoading() else t.webView.reload() }
        }
        pill.addView(siteIcon)
        pill.addView(omni)
        pill.addView(btnReload)

        btnMedia = TextView(this)
        btnMedia.text = "▶"
        btnMedia.setTextColor(Color.WHITE)
        btnMedia.gravity = Gravity.CENTER
        btnMedia.setTypeface(btnMedia.typeface, android.graphics.Typeface.BOLD)
        val mb = GradientDrawable()
        mb.setColor(cAcc)
        mb.cornerRadius = dp(16).toFloat()
        btnMedia.background = mb
        btnMedia.setPadding(dp(10), 0, dp(10), 0)
        btnMedia.isFocusable = true
        btnMedia.isClickable = true
        btnMedia.setOnClickListener { showMedia() }
        btnMedia.visibility = View.GONE

        btnTabs = TabCountView(this)
        btnTabs.isFocusable = true
        btnTabs.isClickable = true
        btnTabs.background = focusBg()
        btnTabs.setOnClickListener { showTabs() }
        btnMenu = ib("dots") { showMenu() }

        bar.addView(btnBack)
        bar.addView(btnFwd)
        bar.addView(btnHome)
        bar.addView(pill)
        bar.addView(btnMedia)
        bar.addView(btnTabs)
        bar.addView(btnMenu)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(cAcc)
        progress.visibility = View.GONE

        holder = FrameLayout(this)
        cursor = CursorView(this)
        val content = FrameLayout(this)
        content.addView(holder, FrameLayout.LayoutParams(-1, -1))
        content.addView(cursor, FrameLayout.LayoutParams(-1, -1))

        root.addView(bar, LinearLayout.LayoutParams(-1, dp(60)))
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        applyMode()
    }

    /** Ekran türüne göre ölçüler: telefon (sade), tablet (geri/ileri görünür), TV (büyük + odak). */
    private fun applyMode() {
        val big = big()
        val wide = big || resources.configuration.smallestScreenWidthDp >= 600
        val k = if (big) 1.3f else 1f
        fun sz(v: Int) = (dp(v) * k).toInt()
        bar.layoutParams.height = sz(60)
        for (v in listOf(btnBack, btnFwd, btnHome)) {
            v.visibility = if (wide) View.VISIBLE else View.GONE
            v.layoutParams = LinearLayout.LayoutParams(sz(42), sz(42))
        }
        val pl = LinearLayout.LayoutParams(0, sz(46), 1f)
        pl.setMargins(dp(6), 0, dp(6), 0)
        pill.layoutParams = pl
        siteIcon.layoutParams = LinearLayout.LayoutParams(sz(18), sz(18))
        omni.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        omni.textSize = if (big) 18f else 15f
        btnReload.layoutParams = LinearLayout.LayoutParams(sz(38), sz(38))
        btnMedia.layoutParams = LinearLayout.LayoutParams(-2, sz(32)).also { it.setMargins(0, 0, dp(4), 0) }
        btnMedia.textSize = if (big) 15f else 13f
        btnTabs.layoutParams = LinearLayout.LayoutParams(sz(44), sz(44))
        btnMenu.layoutParams = LinearLayout.LayoutParams(sz(42), sz(42))
        bar.requestLayout()
        for (t in tabs) t.webView.settings.textZoom = if (big) 125 else 100
    }

    private fun focusBar() {
        val v: View = if (btnBack.visibility == View.VISIBLE) btnBack else omni
        v.requestFocus()
    }

    private fun pretty(u: String): String = when {
        u.isEmpty() || u.startsWith(ASSET + "newtab") -> ""
        u.startsWith(ASSET + "player") -> "Ghost Oynatıcı"
        u.startsWith(ASSET + "settings") -> "Ayarlar"
        u.startsWith("http") -> ((try { Uri.parse(u).host } catch (_: Exception) { null }) ?: u).removePrefix("www.")
        else -> u
    }

    private fun updateUi() {
        val t = cur ?: return
        btnBack.alpha = if (t.webView.canGoBack()) 1f else 0.3f
        btnFwd.alpha = if (t.webView.canGoForward()) 1f else 0.3f
        btnReload.kind = if (t.loading) "close" else "reload"
        btnReload.invalidate()
        btnTabs.count = tabs.size
        if (!omni.hasFocus()) omni.setText(pretty(t.url))
        val u = t.url
        siteIcon.kind = when {
            u.startsWith(ASSET) || u.isEmpty() -> "search"
            u.startsWith("https://") -> "lock"
            else -> "info"
        }
        siteIcon.tint = if (siteIcon.kind == "info") Color.parseColor("#FBBF24") else cDim
        siteIcon.invalidate()
        if (t.media.isNotEmpty()) {
            btnMedia.visibility = View.VISIBLE
            btnMedia.text = "▶ " + t.media.size
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

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun cardPopup(w: Int, content: LinearLayout): PopupWindow {
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#1E1E2B"))
        bg.cornerRadius = dp(20).toFloat()
        bg.setStroke(dp(1), Color.parseColor("#34344D"))
        content.background = bg
        content.setPadding(dp(8), dp(10), dp(8), dp(10))
        val dm = resources.displayMetrics
        val scroll = ScrollView(this)
        scroll.addView(content)
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val h = minOf(content.measuredHeight, (dm.heightPixels * 0.8).toInt())
        val popup = PopupWindow(scroll, w, h, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = dp(16).toFloat()
        popup.isOutsideTouchable = true
        return popup
    }

    private fun showTabs() {
        val big = big()
        val k = if (big) 1.25f else 1f
        fun sz(v: Int) = (dp(v) * k).toInt()
        val ref = arrayOfNulls<PopupWindow>(1)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = "Sekmeler (${tabs.size})"
        title.setTextColor(Color.WHITE)
        title.textSize = if (big) 20f else 17f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.setPadding(dp(12), dp(2), dp(12), dp(8))
        card.addView(title)
        for (t in ArrayList(tabs)) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.isFocusable = true
            r.isClickable = true
            r.background = focusBg()
            r.setPadding(dp(12), 0, dp(4), 0)
            val dot = View(this)
            val dg = GradientDrawable()
            dg.shape = GradientDrawable.OVAL
            dg.setColor(if (t === cur) cAcc else Color.parseColor("#3A3A55"))
            dot.background = dg
            r.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
            val tv = TextView(this)
            tv.text = t.title.ifEmpty { pretty(t.url) }.ifEmpty { "Yeni sekme" }
            tv.setSingleLine()
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.setTextColor(Color.parseColor("#ECECF4"))
            tv.textSize = if (big) 18f else 15f
            tv.setPadding(dp(14), 0, dp(8), 0)
            r.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            val x = ib("close") { ref[0]?.dismiss(); closeTab(t) }
            r.addView(x, LinearLayout.LayoutParams(sz(40), sz(40)))
            r.setOnClickListener { ref[0]?.dismiss(); switchTo(t) }
            card.addView(r, LinearLayout.LayoutParams(-1, sz(50)))
        }
        val d = View(this)
        d.setBackgroundColor(Color.parseColor("#2C2C42"))
        val dl = LinearLayout.LayoutParams(-1, dp(1))
        dl.setMargins(dp(10), dp(6), dp(10), dp(6))
        card.addView(d, dl)
        val add = LinearLayout(this)
        add.orientation = LinearLayout.HORIZONTAL
        add.gravity = Gravity.CENTER_VERTICAL
        add.isFocusable = true
        add.isClickable = true
        add.background = focusBg()
        add.setPadding(dp(12), 0, dp(12), 0)
        add.addView(IconView(this, "plus"), LinearLayout.LayoutParams(sz(22), sz(22)))
        val at = TextView(this)
        at.text = "Yeni sekme"
        at.setTextColor(Color.parseColor("#ECECF4"))
        at.textSize = if (big) 18f else 15f
        at.setPadding(dp(14), 0, 0, 0)
        add.addView(at)
        add.setOnClickListener { ref[0]?.dismiss(); newTab("ghost://newtab") }
        card.addView(add, LinearLayout.LayoutParams(-1, sz(50)))
        val w = minOf(sz(340), resources.displayMetrics.widthPixels - dp(32))
        val popup = cardPopup(w, card)
        ref[0] = popup
        popup.showAtLocation(window.decorView, Gravity.CENTER, 0, 0)
        card.getChildAt(1)?.requestFocus()
    }

    private var askPopup: PopupWindow? = null

    /** Film sitesinde video yakalanınca: Ghost Oynatıcı'da açılsın mı? */
    private fun askPlayer(tab: Tab) {
        if (tab !== cur || tab.isPlayer || tab.isInternal || tab.media.isEmpty()) return
        if (store.settings.has("askPlayer") && !store.bool("askPlayer")) return
        if (tab.asked == tab.url || askPopup?.isShowing == true) return
        tab.asked = tab.url
        val big = big()
        val k = if (big) 1.25f else 1f
        fun sz(v: Int) = (dp(v) * k).toInt()
        val ref = arrayOfNulls<PopupWindow>(1)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val msg = TextView(this)
        msg.text = "▶ Video bulundu"
        msg.setTextColor(Color.WHITE)
        msg.textSize = if (big) 19f else 16f
        msg.setTypeface(msg.typeface, android.graphics.Typeface.BOLD)
        msg.setPadding(dp(10), 0, dp(10), dp(2))
        card.addView(msg)
        val sub = TextView(this)
        sub.text = "Ghost Oynatıcı'da reklamsız açılsın mı?"
        sub.setTextColor(cDim)
        sub.textSize = if (big) 15f else 13f
        sub.setPadding(dp(10), 0, dp(10), dp(10))
        card.addView(sub)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        fun btn(label: String, fill: Boolean, action: () -> Unit) {
            val b = TextView(this)
            b.text = label
            b.gravity = Gravity.CENTER
            b.setTextColor(Color.WHITE)
            b.textSize = if (big) 16f else 14f
            b.isFocusable = true
            b.isClickable = true
            val g = GradientDrawable()
            g.setColor(if (fill) cAcc else Color.parseColor("#2A2A3D"))
            g.cornerRadius = dp(14).toFloat()
            b.background = g
            b.setOnFocusChangeListener { _, f -> g.setStroke(if (f) dp(2) else 0, Color.WHITE) }
            b.setOnClickListener { ref[0]?.dismiss(); action() }
            val lp = LinearLayout.LayoutParams(0, sz(44), 1f)
            lp.setMargins(dp(4), 0, dp(4), 0)
            row.addView(b, lp)
        }
        btn("Hayır", false) { }
        btn("Oynatıcıda aç", true) {
            val m = tab.media[0]
            newTab(playerUrl(m.url, tab.title, m.ref))
        }
        card.addView(row)
        val w = minOf(sz(360), resources.displayMetrics.widthPixels - dp(24))
        val popup = cardPopup(w, card)
        popup.isFocusable = big
        ref[0] = popup
        askPopup = popup
        popup.showAtLocation(window.decorView, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(28))
        if (big) row.getChildAt(1)?.requestFocus()
        main.postDelayed({ if (popup.isShowing) popup.dismiss() }, 15000)
    }

    private fun showMedia() {
        val t = cur ?: return
        val items = t.media.map {
            val u = Uri.parse(it.url)
            it.kind + " · " + (u.host ?: "") + (u.path ?: "").takeLast(36)
        }.toTypedArray()
        dialog()
            .setTitle("Ghost Oynatıcı'da aç")
            .setItems(items) { _, i -> val m = t.media[i]; newTab(playerUrl(m.url, t.title, m.ref)) }
            .show()
    }

    private fun showMenu() {
        val big = big()
        val k = if (big) 1.25f else 1f
        fun sz(v: Int) = (dp(v) * k).toInt()
        val ref = arrayOfNulls<PopupWindow>(1)
        val t = cur

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#1E1E2B"))
        bg.cornerRadius = dp(20).toFloat()
        bg.setStroke(dp(1), Color.parseColor("#34344D"))
        card.background = bg
        card.setPadding(dp(6), dp(8), dp(6), dp(8))

        fun divider() {
            val d = View(this)
            d.setBackgroundColor(Color.parseColor("#2C2C42"))
            val lp = LinearLayout.LayoutParams(-1, dp(1))
            lp.setMargins(dp(10), dp(6), dp(10), dp(6))
            card.addView(d, lp)
        }

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        fun topBtn(kind: String, enabled: Boolean, action: () -> Unit) {
            val v = ib(kind) { ref[0]?.dismiss(); action() }
            v.alpha = if (enabled) 1f else 0.35f
            v.isEnabled = enabled
            top.addView(v, LinearLayout.LayoutParams(0, sz(48), 1f))
        }
        topBtn("back", t?.webView?.canGoBack() == true) { t?.webView?.goBack() }
        topBtn("forward", t?.webView?.canGoForward() == true) { t?.webView?.goForward() }
        topBtn(if (t?.loading == true) "close" else "reload", t != null) {
            if (t != null) { if (t.loading) t.webView.stopLoading() else t.webView.reload() }
        }
        topBtn("home", t != null) { if (t != null) loadIn(t, store.str("homepage")) }
        card.addView(top)
        divider()

        fun row(kind: String, label: String, toggle: Boolean? = null, action: () -> Unit) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.isFocusable = true
            r.isClickable = true
            r.background = focusBg()
            r.setPadding(dp(12), 0, dp(12), 0)
            r.addView(IconView(this, kind), LinearLayout.LayoutParams(sz(22), sz(22)))
            val tv = TextView(this)
            tv.text = label
            tv.setTextColor(Color.parseColor("#ECECF4"))
            tv.textSize = if (big) 18f else 15f
            tv.setPadding(dp(16), 0, 0, 0)
            r.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            if (toggle != null) r.addView(ToggleView(this, toggle), LinearLayout.LayoutParams(sz(38), sz(22)))
            r.setOnClickListener { ref[0]?.dismiss(); action() }
            card.addView(r, LinearLayout.LayoutParams(-1, sz(50)))
        }
        row("plus", "Yeni sekme") { newTab("ghost://newtab") }
        row("tabs", "Sekmeler") { showTabs() }
        row("clock", "Geçmiş") { openInternal("settings#history") }
        row("phone", "Telefon kumandası (QR)") { openInternal("settings#remote") }
        divider()
        row("shield", "Reklam engelleyici", store.bool("adblock")) {
            store.patch(JSONObject().put("adblock", !store.bool("adblock")))
            adblock.enabled = store.bool("adblock")
            toast(if (adblock.enabled) "Reklam engelleyici açık" else "Reklam engelleyici kapalı")
        }
        row("tv", "TV modu", store.bool("tvMode")) {
            store.patch(JSONObject().put("tvMode", !store.bool("tvMode")))
            applyMode()
            toast(if (store.bool("tvMode")) "TV modu açık" else "TV modu kapalı")
        }
        row("play", "Videoda oynatıcı sor", store.bool("askPlayer")) {
            store.patch(JSONObject().put("askPlayer", !store.bool("askPlayer")))
            toast(if (store.bool("askPlayer")) "Video bulununca sorulacak" else "Sorma kapalı")
        }
        row("fullscreen", "Tam ekran") { toggleImmersive() }
        divider()
        row("gear", "Ayarlar") { openInternal("settings") }
        row("power", "Çıkış") { finish() }

        val dm = resources.displayMetrics
        val w = minOf(sz(300), dm.widthPixels - dp(16))
        val scroll = ScrollView(this)
        scroll.addView(card)
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val h = minOf(card.measuredHeight, (dm.heightPixels * 0.85).toInt())
        val popup = PopupWindow(scroll, w, h, true)
        ref[0] = popup
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = dp(16).toFloat()
        popup.isOutsideTouchable = true
        popup.showAsDropDown(btnMenu, 0, -dp(4), Gravity.END)
        card.getChildAt(2)?.requestFocus()
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
        s.textZoom = if (big()) 125 else 100
        wv.setBackgroundColor(Color.parseColor("#0B0B10"))
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, "GhostNative", setOf("https://appassets.androidplatform.net")) { _, message, _, _, reply ->
                onBridge(tab, reply, message.data ?: "")
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addWebMessageListener(wv, "GhostSniff", setOf("*")) { _, message, _, _, _ ->
                    try {
                        val o = JSONObject(message.data ?: "")
                        if (!tab.isPlayer && !tab.isInternal) sniff(tab, o.optString("u"), o.optString("r"), o.optInt("m") == 1)
                    } catch (_: Exception) {}
                }
                WebViewCompat.addDocumentStartJavaScript(wv, SNIFF_JS, setOf("*"))
            } catch (_: Exception) {}
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
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog_Alert).setMessage(message)
                    .setPositiveButton("Tamam") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog_Alert).setMessage(message)
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

    private fun sniff(tab: Tab, url: String, ref: String = "", master: Boolean = false) {
        if (url.isEmpty()) return
        main.post {
            if (tab.media.any { it.url == url }) return@post
            tab.media.add(0, MediaItem(url, ref.ifEmpty { tab.url }, if (master) "HLS ★" else if (url.contains("m3u8", true) || ref.isNotEmpty() && !MEDIA_RE.containsMatchIn(url)) "HLS" else if (url.contains(".mpd", true)) "DASH" else "Video"))
            tab.media.sortBy { if (it.kind == "HLS ★") 0 else 1 }
            while (tab.media.size > 15) tab.media.removeAt(tab.media.size - 1)
            if (tab === cur) { updateUi(); askPlayer(tab) }
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
                    applyMode()
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
                    if (bar.hasFocus()) t.webView.requestFocus() else focusBar()
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
                                focusBar()
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
