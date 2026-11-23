package com.darknesslord19.ghostbrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uygulamanın Claude tarafından uzaktan sürülebilmesi için Cloude Browser aracı sunucusuna bağlanır (uzun yoklama). */
interface RelayApi {
    fun link(): String
    fun <T> ui(block: () -> T): T
    fun tabs(): List<Tab>
    fun current(): Tab?
    fun open(url: String, newTab: Boolean): Tab
    fun select(i: Int)
    fun close(i: Int)
    fun back(): Boolean
}

class Relay(private val api: RelayApi) {
    @Volatile var status = "Kapalı"
        private set
    @Volatile private var started = false
    private var seq = 0

    fun start() {
        if (started) return
        started = true
        Thread { loop() }.also { it.isDaemon = true }.start()
    }

    private fun parse(link: String): Pair<String, String>? {
        val i = link.indexOf("/mcp/")
        if (i < 0) return null
        val base = link.substring(0, i).trimEnd('/')
        val token = link.substring(i + 5).trim('/').substringBefore('/').substringBefore('?')
        return if (base.startsWith("http") && token.isNotEmpty()) Pair(base, token) else null
    }

    private fun nap(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun loop() {
        while (true) {
            val p = parse(api.link().trim())
            if (p == null) {
                status = "Kapalı"
                nap(3000)
                continue
            }
            try {
                val c = URL(p.first + "/device/" + p.second + "/poll").openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 45000
                c.useCaches = false
                c.setRequestProperty("Connection", "close")
                val code = c.responseCode
                if (code == 200) {
                    status = "Bağlı, komut çalışıyor"
                    val body = c.inputStream.bufferedReader().readText()
                    val res = run(JSONObject(body))
                    post(p.first + "/device/" + p.second + "/result", res)
                    status = "Bağlı"
                } else if (code == 204) {
                    status = "Bağlı"
                } else {
                    status = "Sunucu hatası: $code"
                    nap(3000)
                }
                c.disconnect()
            } catch (e: Throwable) {
                status = "Bağlantı yok (" + e.javaClass.simpleName + ")"
                nap(1500)
            }
        }
    }

    private fun post(endpoint: String, res: JSONObject) {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(res.toString().toByteArray(Charsets.UTF_8)) }
        c.responseCode
        c.disconnect()
    }

    private fun tabOrThrow(): Tab = api.ui { api.current() } ?: throw IllegalStateException("Açık sekme yok")

    private fun run(cmd: JSONObject): JSONObject {
        val out = JSONObject().put("id", cmd.getString("id"))
        try {
            val args = cmd.optJSONObject("args") ?: JSONObject()
            val data: String = when (cmd.getString("action")) {
                "navigate" -> navigate(args.getString("url"), false)
                "info" -> api.ui { api.current()?.let { (it.webView.title ?: "") + " | " + it.webView.url } ?: "" }
                "text" -> evalJs(JS_TEXT, args)
                "links" -> evalJs(JS_LINKS, args)
                "report" -> evalJs(JS_REPORT, args)
                "click" -> evalJs(JS_CLICK, args)
                "type" -> evalJs(JS_TYPE, args)
                "scroll" -> evalJs("window.scrollBy(0, Number(A.dy)||0); return {scrollY: window.scrollY};", args)
                "eval" -> evalJs(args.getString("script"), JSONObject())
                "back" -> {
                    val moved = api.ui { api.back() }
                    nap(1500)
                    if (moved) "Geri gidildi" else "Geri gidilecek sayfa yok"
                }
                "screenshot" -> screenshot()
                "tabs" -> api.ui {
                    val cur = api.current()
                    JSONArray(api.tabs().filter { !it.hidden }.mapIndexed { i, t ->
                        JSONObject().put("index", i).put("title", t.webView.title ?: t.title).put("url", t.webView.url ?: t.url).put("active", t === cur)
                    }).toString()
                }
                "new_tab" -> {
                    val u = args.optString("url", "")
                    if (u.isEmpty()) {
                        api.ui { api.open("", true) }
                        "Yeni sekme açıldı"
                    } else navigate(u, true)
                }
                "switch_tab" -> api.ui {
                    val i = args.getInt("index")
                    val l = api.tabs().filter { !it.hidden }
                    if (i !in l.indices) throw IllegalArgumentException("Böyle bir sekme yok")
                    api.select(api.tabs().indexOf(l[i]))
                    "Sekme $i: " + (l[i].webView.title ?: "") + " | " + l[i].webView.url
                }
                "close_tab" -> api.ui {
                    val l = api.tabs().filter { !it.hidden }
                    val i = if (args.has("index")) args.getInt("index") else l.indexOf(api.current())
                    if (i !in l.indices) throw IllegalArgumentException("Böyle bir sekme yok")
                    api.close(api.tabs().indexOf(l[i]))
                    "Sekme kapatıldı"
                }
                else -> throw IllegalArgumentException("Bilinmeyen komut")
            }
            out.put("ok", true).put("data", data)
        } catch (e: Exception) {
            out.put("ok", false).put("error", e.message ?: e.toString())
        }
        return out
    }

    private fun navigate(raw: String, newTab: Boolean): String {
        val u = when {
            raw.matches(Regex("^(https?|ghost)://.*", RegexOption.IGNORE_CASE)) -> raw
            !raw.contains("://") -> "https://$raw"
            else -> throw IllegalArgumentException("Yalnızca http, https ve ghost adresleri açılır")
        }
        val latch = CountDownLatch(1)
        val tab = api.ui {
            val t = api.open(u, newTab)
            t.onFinished = { latch.countDown() }
            t
        }
        latch.await(25, TimeUnit.SECONDS)
        api.ui { tab.onFinished = null }
        nap(600)
        return api.ui { (tab.webView.title ?: "") + " | " + tab.webView.url }
    }

    /** Sayfada fonksiyon gövdesi çalıştırır. Sonuç bir Promise ise beklenir (ghost.invoke gibi). */
    private fun evalJs(body: String, args: JSONObject): String {
        val key = "__rl" + (++seq)
        val script = "(function(){try{window.$key=undefined;var A=$args;var r=(function(){$body\n})();" +
            "Promise.resolve(r).then(function(v){window.$key=JSON.stringify(v===undefined?null:v)},function(e){window.$key=JSON.stringify({error:String(e&&e.message||e)})});" +
            "}catch(e){window.$key=JSON.stringify({error:String(e)})}return 'ok'})()"
        val tab = tabOrThrow()
        val first = CountDownLatch(1)
        api.ui { tab.webView.evaluateJavascript(script) { first.countDown() }; true }
        first.await(10, TimeUnit.SECONDS)
        val end = System.currentTimeMillis() + 25000
        while (System.currentTimeMillis() < end) {
            val l = CountDownLatch(1)
            var raw: String? = null
            api.ui { tab.webView.evaluateJavascript("(function(){var v=window.$key;return v===undefined?'__wait':v})()") { v -> raw = v; l.countDown() }; true }
            l.await(10, TimeUnit.SECONDS)
            val v = raw
            if (v != null && v != "null") {
                val s = try { JSONTokener(v).nextValue().toString() } catch (_: Exception) { v }
                if (s != "__wait") {
                    api.ui { tab.webView.evaluateJavascript("delete window.$key", null); true }
                    return s
                }
            }
            nap(250)
        }
        throw RuntimeException("Sayfa betiği zaman aşımına uğradı")
    }

    private fun screenshot(): String = api.ui {
        val wv = (api.current() ?: throw IllegalStateException("Açık sekme yok")).webView
        val w = wv.width
        val h = wv.height
        if (w <= 0 || h <= 0) throw RuntimeException("Görünüm boş")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        wv.draw(Canvas(bmp))
        val scaled = if (w > 900) Bitmap.createScaledBitmap(bmp, 900, h * 900 / w, true) else bmp
        val bo = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 65, bo)
        Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val JS_TEXT = """
            var m = A.max || 20000;
            var t = document.body ? document.body.innerText : '';
            return t.length > m ? t.slice(0, m) + '\n...[kesildi, toplam ' + t.length + ' karakter]' : t;
        """

        private const val JS_LINKS = """
            var o = [];
            var max = A.max || 200;
            document.querySelectorAll('a[href]').forEach(function (a) {
                if (o.length < max) o.push({ text: (a.innerText || a.title || '').trim().slice(0, 80), href: a.href });
            });
            return o;
        """

        private const val JS_CLICK = """
            var el = null;
            if (A.selector) {
                el = document.querySelector(A.selector);
            } else if (A.text) {
                var t = String(A.text).toLowerCase();
                var c = document.querySelectorAll('a,button,[role=button],input[type=submit],input[type=button],summary');
                for (var i = 0; i < c.length; i++) {
                    var s = ((c[i].innerText || c[i].value || c[i].title || '') + '').toLowerCase();
                    if (s.indexOf(t) > -1) { el = c[i]; break; }
                }
            }
            if (!el) return { error: 'Öğe bulunamadı' };
            el.scrollIntoView({ block: 'center' });
            el.click();
            return { clicked: (el.tagName + ' ' + (el.innerText || el.value || '')).slice(0, 80) };
        """

        private const val JS_TYPE = """
            var el = document.querySelector(A.selector);
            if (!el) return { error: 'Öğe bulunamadı' };
            el.focus();
            var isInput = el.tagName === 'INPUT' || el.tagName === 'TEXTAREA';
            if (isInput) {
                var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                var d = Object.getOwnPropertyDescriptor(proto, 'value');
                if (d && d.set) { d.set.call(el, A.text); } else { el.value = A.text; }
            } else {
                el.textContent = A.text;
            }
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            if (A.submit && el.form) {
                if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit();
            }
            return { typed: true };
        """

        private const val JS_REPORT = """
            var h = location.hostname;
            function host(u) { try { return new URL(u, location.href).hostname; } catch (e) { return ''; } }
            var iframes = [].map.call(document.querySelectorAll('iframe'), function (f) { return f.src; }).slice(0, 20);
            var scripts = {};
            [].forEach.call(document.scripts, function (s) { if (s.src) scripts[host(s.src)] = 1; });
            return {
                url: location.href,
                title: document.title,
                https: location.protocol === 'https:',
                hasPasswordField: !!document.querySelector('input[type=password]'),
                scriptHosts: Object.keys(scripts),
                iframes: iframes,
                textLength: document.body ? document.body.innerText.length : 0
            };
        """
    }
}
