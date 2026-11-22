package com.darknesslord19.ghostbrowser

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI

/** Crawler'ın ana etkinlikten ihtiyaç duyduğu şeyler. */
interface CrawlHost {
    fun hiddenTab(): Tab
    fun release(t: Tab)
    fun awake(on: Boolean)
    fun saveText(name: String, text: String): String
    fun setting(k: String): String
    fun flag(k: String): Boolean
}

/**
 * Herhangi bir film sitesi için genel tarayıcı:
 * kategori bulma -> kategori sayfalarını gezip film listeleme -> film sayfasından video adresi yakalama.
 * Tek gizli WebView ile sırayla, yavaş ve kaldığı yerden devam edecek şekilde çalışır.
 */
class Crawler(ctx: Context, private val host: CrawlHost, private val main: Handler, private val density: Float) {
    private val file = File(ctx.filesDir, "crawl.json")
    private var sites = JSONObject()
    private val saver = Runnable { save() }

    @Volatile var running = false
        private set
    var status = ""
        private set
    private var gen = 0
    private var tab: Tab? = null
    private val jobs = ArrayDeque<Job>()
    private var totalJobs = 0
    private var jobMode = false
    @Volatile var phase = ""
        private set
    @Volatile var curCat = ""
        private set
    @Volatile var prog = 0
        private set
    @Volatile var progTotal = 0
        private set

    private class Job(val kind: String, val site: String, val cat: String, val n: Int)

    init {
        try {
            sites = JSONObject(file.readText()).optJSONObject("sites") ?: JSONObject()
        } catch (_: Exception) {
        }
    }

    // ---------------- kayıt ----------------
    @Synchronized
    private fun save() {
        try {
            file.writeText(JSONObject().put("sites", sites).toString())
        } catch (_: Exception) {
        }
    }

    private fun schedule() {
        main.removeCallbacks(saver)
        main.postDelayed(saver, 1000)
    }

    private fun cats(site: JSONObject): JSONArray = site.optJSONArray("categories") ?: JSONArray().also { site.put("categories", it) }

    private fun findCat(h: String, url: String): JSONObject? {
        val site = sites.optJSONObject(h) ?: return null
        val a = cats(site)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("url") == url) return a.getJSONObject(i)
        return null
    }

    private fun hostOf(u: String): String = try { (URI(u).host ?: "").removePrefix("www.") } catch (_: Exception) { "" }

    // ---------------- yardımcılar ----------------
    private fun step(ms: Long, g: Int, fn: () -> Unit) {
        main.postDelayed({ if (g == gen && running) fn() }, ms)
    }

    private fun begin(msg: String) {
        running = true
        gen++
        status = msg
        host.awake(true)
    }

    private fun finish(msg: String) {
        running = false
        jobMode = false
        phase = ""
        curCat = ""
        curTitle = ""
        curUrl = ""
        prog = 0
        progTotal = 0
        gen++
        status = msg
        jobs.clear()
        tab?.let {
            if (keepNext) {
                keepNext = false
                keptTab?.let { k -> host.release(k) }
                keptTab = it
                main.removeCallbacks(keptRel)
                main.postDelayed(keptRel, 15 * 60 * 1000L)
            } else host.release(it)
        }
        tab = null
        host.awake(false)
        schedule()
    }

    @Volatile private var keepNext = false
    private var keptTab: Tab? = null
    private val keptRel = Runnable { keptTab?.let { host.release(it) }; keptTab = null }

    fun stop() {
        if (running) finish("Durduruldu")
    }

    private fun ensureTab(): Tab = tab ?: host.hiddenTab().also { tab = it }

    private fun load(url: String, g: Int, done: () -> Unit) {
        val t = ensureTab()
        t.media.clear()
        t.lockUrl = ""
        var finished = false
        val fin = {
            if (!finished) {
                finished = true
                t.onFinished = null
                step(1500, g) { done() }
            }
        }
        t.onFinished = { fin() }
        step(25000, g) { fin() }
        t.webView.loadUrl(url)
    }

    private fun evalJs(js: String, g: Int, cb: (String?) -> Unit) {
        val t = tab ?: return
        t.webView.evaluateJavascript(js) { r ->
            if (g == gen) {
                val s = try { JSONTokener(r ?: "null").nextValue() as? String } catch (_: Exception) { null }
                cb(s)
            }
        }
    }

    private fun tap(r: String?) {
        val t = tab ?: return
        try {
            val o = JSONObject(r ?: return)
            val x = (o.optDouble("x") * density).toFloat()
            val y = (o.optDouble("y") * density).toFloat()
            val now = SystemClock.uptimeMillis()
            val d = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            t.webView.dispatchTouchEvent(d)
            d.recycle()
            val u = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
            t.webView.dispatchTouchEvent(u)
            u.recycle()
        } catch (_: Exception) {
        }
    }

    // ---------------- 1) kategori bul ----------------
    fun discover(raw: String): String {
        if (running) return "Bir tarama sürüyor"
        var u = raw.trim()
        if (u.isEmpty()) return "Adres boş"
        if (!u.startsWith("http")) u = "https://$u"
        begin("Kategoriler aranıyor…")
        phase = "discover"
        val g = gen
        load(u, g) {
            evalJs(CATS_JS, g) { r ->
                try {
                    val o = JSONObject(r ?: "")
                    val list = o.getJSONArray("list")
                    val real = tab?.url?.takeIf { it.startsWith("http") } ?: u
                    var h = hostOf(real)
                    if (h.isEmpty()) h = hostOf(u)
                    val site = sites.optJSONObject(h) ?: JSONObject().put("host", h).put("url", real).also { sites.put(h, it) }
                    site.put("url", real)
                    site.put("title", o.optString("title"))
                    site.put("time", System.currentTimeMillis())
                    val ca = cats(site)
                    val have = HashSet<String>()
                    for (i in 0 until ca.length()) have.add(ca.getJSONObject(i).optString("url"))
                    var added = 0
                    for (i in 0 until list.length()) {
                        val c = list.getJSONObject(i)
                        val cu = c.optString("u")
                        if (cu.isEmpty() || !have.add(cu)) continue
                        ca.put(JSONObject().put("name", c.optString("n")).put("group", c.optString("g")).put("url", cu).put("films", JSONArray()).put("pages", 0).put("done", false).put("next", ""))
                        added++
                    }
                    finish(if (ca.length() == 0) "Kategori bulunamadı. Kategori sayfasının adresini elle ekleyebilirsin." else "${ca.length()} kategori bulundu ($added yeni)")
                } catch (_: Exception) {
                    finish("Sayfa okunamadı. Adresi kontrol et.")
                }
            }
        }
        return ""
    }

    fun addCat(h: String, name: String, url: String): String {
        if (!url.startsWith("http")) return "Adres http ile başlamalı"
        val site = sites.optJSONObject(h) ?: JSONObject().put("host", h).put("url", "https://$h/").also { sites.put(h, it) }
        if (findCat(h, url) != null) return "Bu kategori zaten var"
        cats(site).put(JSONObject().put("name", name.ifEmpty { url.trimEnd('/').substringAfterLast('/') }).put("group", "").put("url", url).put("films", JSONArray()).put("pages", 0).put("done", false).put("next", ""))
        schedule()
        return ""
    }

    // ---------------- 2) film listele / 3) video çöz ----------------
    private fun queue(kind: String, h: String, urls: JSONArray, n: Int): String {
        if (running && !jobMode) return "Kategori arama sürüyor, bitince tekrar dene"
        if (urls.length() == 0) return "Kategori seç"
        val fresh = !running
        if (fresh) jobs.clear()
        for (i in 0 until urls.length()) {
            val u = urls.getString(i)
            if (jobs.any { it.kind == kind && it.site == h && it.cat == u } || (kind == phase && u == curCat)) continue
            jobs.add(Job(kind, h, u, n))
            if (!fresh) totalJobs++
        }
        if (fresh) startJobs()
        return ""
    }

    fun list(h: String, urls: JSONArray, maxPages: Int): String = queue("list", h, urls, maxPages.coerceIn(1, 5000))

    /** Çözülemeyen (denenmiş, videosu yok) filmleri yeniden denenebilir hale getirir. films boşsa kategorideki hepsi. */
    fun retry(h: String, catUrl: String, films: JSONArray): Int {
        val cat = findCat(h, catUrl) ?: return 0
        val fl = cat.optJSONArray("films") ?: return 0
        val want = HashSet<String>()
        for (i in 0 until films.length()) want.add(films.getString(i))
        var n = 0
        for (k in 0 until fl.length()) {
            val f = fl.getJSONObject(k)
            if (f.optString("video").isEmpty() && f.optInt("tried") >= 1 && (want.isEmpty() || want.contains(f.optString("url")))) {
                f.put("tried", 0).put("retry", 1)
                n++
            }
        }
        schedule()
        return n
    }

    fun fresh(h: String, catUrl: String, url: String): Boolean {
        keepNext = true
        val cat = findCat(h, catUrl) ?: return false
        val fl = cat.optJSONArray("films") ?: return false
        for (k in 0 until fl.length()) {
            val f = fl.getJSONObject(k)
            if (f.optString("url") == url) {
                f.put("video", "").put("videoRef", "").put("tried", 0).put("retry", 1)
                schedule()
                return true
            }
        }
        return false
    }

    fun resolve(h: String, urls: JSONArray, limit: Int): String = queue("resolve", h, urls, limit.coerceIn(1, 100000))

    private fun startJobs() {
        jobMode = true
        totalJobs = jobs.size
        begin("Başlıyor…")
        nextJob(gen)
    }

    private fun nextJob(g: Int) {
        if (g != gen) return
        val j = jobs.removeFirstOrNull()
        if (j == null) {
            finish("Tamamlandı")
            if (host.flag("githubAuto")) githubSync("")
            return
        }
        val after = { step(600, g) { nextJob(g) } }
        if (j.kind == "list") runList(j, g, after) else runResolve(j, g, after)
    }

    /** Taranan sayfa, kategorinin kendi yolunda mı? (tıklamayla başka kategoriye gidilmesini engeller) */
    private fun samePath(catUrl: String, pageUrl: String): Boolean = try {
        val a = URI(catUrl).path.trimEnd('/')
        val b = URI(pageUrl).path.trimEnd('/')
        b.startsWith(a)
    } catch (_: Exception) { true }

    @Volatile var curTitle = ""
        private set
    @Volatile var curUrl = ""
        private set
    private val recentRes = java.util.ArrayDeque<JSONObject>()

    private fun recent(): JSONArray {
        val a = JSONArray()
        synchronized(recentRes) { for (o in recentRes) a.put(o) }
        return a
    }

    private fun noteRes(u: String, v: String, t: Int) {
        synchronized(recentRes) {
            recentRes.addLast(JSONObject().put("u", u).put("v", v).put("t", t))
            while (recentRes.size > 60) recentRes.removeFirst()
        }
    }

    private fun prefix(): String = "(${totalJobs - jobs.size}/$totalJobs) "

    private fun runList(j: Job, g: Int, done: () -> Unit) {
        ListRun(j, g, done).start()
    }

    /** Bir kategorinin sayfalarını gezer: kaydır -> film kartlarını çıkar -> sonraki sayfa (adres ya da tıklama). */
    private inner class ListRun(val j: Job, val g: Int, val done: () -> Unit) {
        var cat: JSONObject? = null
        var films = JSONArray()
        val seen = HashSet<String>()
        val visited = HashSet<String>()
        var url = ""
        var pages = 0
        var clicks = 0
        var initial = 0
        var wasDone = false
        var probeStage = 0
        var triedStart = false
        var startUrl = ""
        var startAdded = 0
        var rewound = false
        var probePage = 2
        var probeBase = ""

        fun start() {
            val cc = findCat(j.site, j.cat)
            if (cc == null) {
                done()
                return
            }
            cat = cc
            films = cc.optJSONArray("films") ?: JSONArray().also { cc.put("films", it) }
            initial = films.length()
            wasDone = cc.optBoolean("done")
            for (i in 0 until films.length()) seen.add(films.getJSONObject(i).optString("url"))
            url = if (cc.optBoolean("done") || cc.optString("next").isEmpty()) cc.optString("url") else cc.optString("next")
            if (cc.optBoolean("done")) cc.put("pages", 0)
            phase = "list"
            curCat = j.cat
            prog = 0
            progTotal = cc.optInt("totalPages")
            if (cc.optString("tpl").isNotEmpty()) {
                // sitenin ajax sayfalama adresi biliniyor: sayfayı bir kez aç, sonra doğrudan istek at
                status = label()
                load(cc.optString("url"), g) { fetchLoop(if (cc.optBoolean("done")) 2 else cc.optInt("apage", 1) + 1) }
            } else page()
        }

        fun addFilms(fa: JSONArray): Int {
            var added = 0
            for (i in 0 until fa.length()) {
                val f = fa.getJSONObject(i)
                val u = f.optString("u")
                if (u.isNotEmpty() && seen.add(u)) {
                    films.put(JSONObject().put("title", f.optString("t")).put("url", u).put("poster", f.optString("p")).put("video", "").put("videoRef", ""))
                    added++
                }
            }
            return added
        }

        fun fetchLoop(p: Int) {
            val cc = cat ?: return done()
            val total = cc.optInt("totalPages")
            if (total in 1 until p) return finish(true, "")
            status = prefix() + cc.optString("name") + " · sayfa " + p + (if (total > 0) "/$total" else "") + " · " + films.length() + " film"
            prog = p
            fetchPage(cc.optString("tpl"), p, g) { arr ->
                if (arr == null) {
                    log("fetch p$p başarısız")
                    return@fetchPage finish(false, "")
                }
                if (arr.length() == 0 && p == 2 && !cc.has("apage")) {
                    cc.remove("tpl")
                    log("şablon film vermedi, tıklamaya dönüldü")
                    return@fetchPage extract()
                }
                val added = addFilms(arr)
                pages++
                cc.put("pages", cc.optInt("pages") + 1)
                cc.put("apage", p)
                log("ajax p$p films=" + films.length() + " added=$added")
                schedule()
                if (added == 0) return@fetchPage finish(true, "")
                if (pages >= j.n) return@fetchPage finish(false, "")
                step(120, g) { fetchLoop(p + 1) }
            }
        }

        fun label(): String {
            val cc = cat ?: return ""
            val tp = cc.optInt("totalPages")
            return prefix() + cc.optString("name") + " · sayfa " + (cc.optInt("pages") + 1) + (if (tp > 0) "/$tp" else "") + " · " + films.length() + " film"
        }

        fun page() {
            status = label()
            prog = pages + 1
            visited.add(url)
            load(url, g) { extract() }
        }

        fun log(s: String) {
            val cc = cat ?: return
            val old = cc.optString("log")
            cc.put("log", (old + "\n" + s).takeLast(3000))
        }

        /** Sayfayı sonuna kadar kaydırır (sonsuz kaydırma / geç yüklenen kartlar), kart sayısı artmayı bırakınca film kartlarını çıkarır. */
        fun extract() {
            val maxRounds = if (j.n >= 200) 3000 else j.n * 25
            var stale = 0
            var best = -1
            var rounds = 0
            var grew = false

            var snap: String? = null
            fun tick() {
                evalJs(SCROLL_JS, g) { r ->
                    val cnt = r?.toIntOrNull() ?: 0
                    rounds++
                    if (cnt > best && cnt in 4..80) evalJs(LIST_JS, g) { sx -> if (sx != null) snap = sx }
                    if (cnt > best) {
                        if (best >= 0) grew = true
                        best = cnt
                        stale = 0
                    } else stale++
                    val cc = cat
                    status = prefix() + (cc?.optString("name") ?: "") + " · kaydırılıyor · " + cnt + " kart"
                    val enough = stale >= (if (best <= 3) 8 else if (grew) 4 else 2)
                    if (enough || rounds >= maxRounds) {
                        log("scroll rounds=$rounds cards=$cnt best=$best v=" + BuildConfig.VERSION_NAME)
                        evalJs(LIST_JS, g) { x -> process(if (cnt == 0 && snap != null) snap else x) }
                    } else step(700, g) { tick() }
                }
            }
            status = label()
            tick()
        }

        fun finish(complete: Boolean, next: String) {
            val cc = cat ?: return done()
            cc.put("next", if (complete) "" else next)
            cc.put("done", complete)
            if (wasDone) {
                cc.put("lastNew", films.length() - initial)
                cc.put("lastCheck", System.currentTimeMillis())
            }
            schedule()
            done()
        }

        fun process(r: String?) {
            val cc = cat ?: return done()
            var added = 0
            var next = ""
            var nc = false
            var last = 0
            var offSite = false
            try {
                val o = JSONObject(r ?: "")
                val hr = o.optString("href")
                if (hr.isNotEmpty() && !samePath(cc.optString("url"), hr)) offSite = true
                next = o.optString("next")
                if (next.isNotEmpty() && !plausibleNext(url, next)) {
                    log("geçersiz sonraki bağlantı yok sayıldı: " + next.takeLast(50))
                    next = ""
                }
                nc = o.optBoolean("nc")
                last = o.optInt("last")
                val fa = if (offSite) JSONArray() else (o.optJSONArray("films") ?: JSONArray())
                for (i in 0 until fa.length()) {
                    val f = fa.getJSONObject(i)
                    val u = f.optString("u")
                    if (u.isNotEmpty() && seen.add(u)) {
                        films.put(JSONObject().put("title", f.optString("t")).put("url", u).put("poster", f.optString("p")).put("video", "").put("videoRef", ""))
                        added++
                    }
                }
            } catch (_: Exception) {
            }
            if (offSite) {
                log("başka sayfaya gidildi, durduruldu: " + (r ?: "").take(0))
                return finish(true, "")
            }
            if (probeStage == 1) {
                pages++
                cc.put("pages", cc.optInt("pages") + 1)
                log("probe p$probePage added=$added total=" + films.length())
                schedule()
                if (added > 0) {
                    if (pages >= j.n) return finish(false, "")
                    probePage++
                    url = probeBase + probePage
                    step(400, g) { page() }
                } else {
                    probeStage = 2
                    url = probeBase + "1"
                    step(300, g) { page() }
                }
                return
            }
            if (probeStage == 2) {
                log("probe p1 added=$added total=" + films.length())
                return finish(true, "")
            }
            if (!triedStart && pages == 0 && added > 0 && added <= 40 && next.isEmpty() && !url.contains("page=")) {
                triedStart = true
                startUrl = url
                startAdded = added
                log("az film var ($added), ?page=1 deneniyor")
                url = url.substringBefore('#') + (if (url.contains('?')) "&" else "?") + "page=1"
                step(500, g) { page() }
                return
            }
            if (triedStart && startUrl.isNotEmpty() && pages == 0) {
                val back = startUrl
                startUrl = ""
                if (added < maxOf(3, startAdded / 2)) {
                    log("?page=1 daha iyi değil, ilk adrese dönülüyor")
                    rewound = true
                    url = back
                    step(500, g) { page() }
                    return
                }
            }
            pages++
            log("p$pages url=" + url.takeLast(60) + " films=" + films.length() + " added=$added next=" + next.takeLast(60) + " last=$last nc=$nc")
            cc.put("pages", cc.optInt("pages") + 1)
            if (last > cc.optInt("totalPages")) cc.put("totalPages", last)
            schedule()
            status = label()
            if (next.isNotEmpty() && !visited.contains(next) && added > 0) {
                if (pages >= j.n) return finish(false, next)
                url = next
                step(700, g) { page() }
            } else if (next.isEmpty() && nc && (added > 0 || rewound || pages == 1) && clicks < 800) {
                if (pages >= j.n) return finish(false, "")
                clicks++
                evalJs(CLICKNEXT_JS, g) { ok ->
                    if (ok == "1") {
                        step(1300, g) {
                            evalJs(TPL_JS, g) { t ->
                                if (!t.isNullOrEmpty()) {
                                    cc.put("tpl", t)
                                    log("ajax şablonu: $t")
                                    fetchLoop(2)
                                } else extract()
                            }
                        }
                    } else finish(true, "")
                }
            } else if (probeStage == 0 && pages == 1 && next.isEmpty() && !nc && added >= 5) {
                probeStage = 1
                probePage = 2
                val pb = url.substringBefore('#').replace(Regex("[?&]page=\\d+"), "")
                probeBase = pb + (if (pb.contains('?')) "&" else "?") + "page="
                log("sayfalama bulunamadı, ?page= deneniyor")
                url = probeBase + "2"
                step(500, g) { page() }
            } else finish(true, "")
        }
    }

    /** Sayfanın içinde sitenin kendi sayfalama isteğini çağırır; sonucu bekleyip döndürür. */
    private fun fetchPage(tpl: String, n: Int, g: Int, cb: (JSONArray?) -> Unit) {
        evalJs(fetchJs(tpl, n), g) {
            var tries = 0
            fun poll() {
                evalJs("(()=>{const r=window.__fres;return r?r:''})()", g) { r ->
                    if (!r.isNullOrEmpty()) {
                        try {
                            val o = JSONObject(r)
                            cb(if (o.optBoolean("ok")) o.optJSONArray("films") ?: JSONArray() else null)
                        } catch (_: Exception) {
                            cb(null)
                        }
                    } else if (++tries > 40) cb(null) else step(400, g) { poll() }
                }
            }
            step(400, g) { poll() }
        }
    }

    private fun pickMedia(t: Tab): MediaItem? {
        val ok = t.media.filter { !(it.url.contains("aud", true) && it.url.contains("sublist", true)) }
        return ok.firstOrNull { it.kind == "HLS ★" } ?: ok.firstOrNull() ?: t.media.firstOrNull()
    }

    /** Sonraki sayfa adayı aynı listenin sayfası mı? (aynı yol, alt yol ya da rakamları farklı aynı yol) */
    private fun plausibleNext(cur: String, nxt: String): Boolean = try {
        val pa = URI(cur.substringBefore('#')).path.trimEnd('/')
        val pb = URI(nxt.substringBefore('#')).path.trimEnd('/')
        val norm = { s: String -> s.replace(Regex("\\d+"), "#") }
        pa == pb || pb.startsWith("$pa/") || norm(pa) == norm(pb)
    } catch (_: Exception) {
        false
    }

    private fun isSeries(u: String): Boolean = Regex("^https?://[^/]+/(series|dizi|diziler|show|tv-series)/", RegexOption.IGNORE_CASE).containsMatchIn(u)

    private fun runResolve(j: Job, g: Int, done: () -> Unit) {
        val cat = findCat(j.site, j.cat) ?: return done()
        val films = cat.optJSONArray("films") ?: return done()
        val todo = ArrayList<JSONObject>()
        for (pass in 0..1) {
            for (i in 0 until films.length()) {
                val f = films.getJSONObject(i)
                if (todo.size >= j.n) break
                if (todo.contains(f)) continue
                val rt = f.has("retry")
                if ((pass == 0) != rt) continue
                if (f.optString("video").isEmpty() && f.optInt("tried") < 2 && !(f.has("eps") && f.optInt("eps") > 0)) todo.add(f)
            }
        }
        for (f in todo) f.remove("retry")
        var idx = 0
        phase = "resolve"
        curCat = j.cat
        prog = 0
        progTotal = todo.size

        fun nextFilm() {
            if (idx >= todo.size) return done()
            val f = todo[idx++]
            prog = idx
            curTitle = f.optString("title")
            curUrl = f.optString("url")
            status = prefix() + "Video çözülüyor " + idx + "/" + todo.size
            if (isSeries(f.optString("url")) && !f.has("eps")) {
                status = prefix() + "Bölümler aranıyor " + idx + "/" + todo.size
                load(f.optString("url"), g) {
                    step(1200, g) {
                        val base = f.optString("url").trimEnd('/').substringAfterLast('/').substringBefore('?').replace(Regex("-c\\d+${'$'}"), "")
                        evalJs(epsJs(base), g) { r ->
                            var n = 0
                            try {
                                val ea = JSONArray(r ?: "[]")
                                val have = HashSet<String>()
                                for (i in 0 until films.length()) have.add(films.getJSONObject(i).optString("url"))
                                for (i in 0 until ea.length()) {
                                    val e = ea.getJSONObject(i)
                                    if (!have.add(e.optString("u"))) continue
                                    val nm = f.optString("title") + " S" + String.format("%02d", e.optInt("s")) + "E" + String.format("%02d", e.optInt("e"))
                                    val ef = JSONObject().put("title", nm).put("url", e.optString("u")).put("poster", f.optString("poster")).put("video", "").put("videoRef", "")
                                        .put("series", f.optString("title")).put("season", e.optInt("s")).put("episode", e.optInt("e"))
                                    films.put(ef)
                                    todo.add(ef)
                                    n++
                                }
                            } catch (_: Exception) {
                            }
                            f.put("eps", n)
                            if (n == 0) f.put("tried", 1)
                            progTotal = todo.size
                            schedule()
                            step(80, g) { nextFilm() }
                        }
                    }
                }
                return
            }
            load(f.optString("url"), g) {
                val t = tab
                if (t == null) {
                    done()
                } else {
                    var waited = 0

                    fun fail() {
                        if (!f.optBoolean("okTried")) {
                            f.put("okTried", true)
                            evalJs(OK_FRAME_JS, g) { ou ->
                                val okUrl = (ou ?: "").trim().trim('"')
                                if (okUrl.startsWith("http")) {
                                    load(okUrl, g) {
                                        step(800, g) {
                                            evalJs(OK_EXTRACT_JS, g) { vr ->
                                                val v = (vr ?: "").trim().trim('"')
                                                if (v.startsWith("http")) {
                                                    f.put("video", v).put("videoRef", "https://ok.ru/").put("videoTime", System.currentTimeMillis()).put("tried", 0)
                                                    noteRes(f.optString("url"), v, 1)
                                                    schedule()
                                                    step(80, g) { nextFilm() }
                                                } else fail()
                                            }
                                        }
                                    }
                                } else fail()
                            }
                            return
                        }
                        evalJs(DIAG_JS, g) { d -> cat.put("log", (cat.optString("log") + "\nçözülemedi: " + f.optString("url").takeLast(40) + " " + (d ?: "")).takeLast(3000)) }
                        if (f.optInt("tried") == 0) {
                            f.put("tried", 1)
                            todo.add(f)
                            progTotal = todo.size
                        } else {
                            f.put("tried", 2)
                            noteRes(f.optString("url"), "", 2)
                        }
                        schedule()
                        step(80, g) { nextFilm() }
                    }

                    var chal = 0
                    var adBusy = false
                    lateinit var pollRef: () -> Unit
                    fun cont() {
                        if (waited >= 1500 && ensureTab().lockUrl.isEmpty()) ensureTab().lockUrl = ensureTab().url.substringBefore("#")
                        if (waited == 1500 || waited == 4500 || waited == 9000 || waited == 14000 || waited == 20000 || waited == 27000 || waited == 34000 || waited == 41000) evalJs(CLICK_JS, g) { tap(it) }
                        if (waited == 9000 || waited == 18000) evalJs("(/reklam|advert|skip ad/i.test((document.body?document.body.innerText:'').slice(0,600))?'1':'0')", g) { a -> if ((a ?: "").contains("1")) adBusy = true }
                        if (waited >= (if (adBusy) 45000 else 20000)) return fail()
                        step(300, g) { pollRef() }
                    }

                    fun poll() {
                        if (t.media.isNotEmpty()) {
                            step(800, g) {
                                val m = pickMedia(t)
                                if (m != null) {
                                    f.put("video", m.url).put("videoRef", m.ref).put("videoTime", System.currentTimeMillis()).put("tried", 0)
                                    noteRes(f.optString("url"), m.url, 1)
                                    schedule()
                                    step(80, g) { nextFilm() }
                                } else fail()
                            }
                            return
                        }
                        if (waited % 900 == 600 && chal < 70) {
                            evalJs("document.title", g) { ti ->
                                if (Regex("(?i)just a moment|bir dakika|attention required|checking your browser").containsMatchIn(ti ?: "")) {
                                    chal++
                                    waited = 0
                                    status = prefix() + "Doğrulama bekleniyor " + idx + "/" + todo.size
                                    step(500, g) { poll() }
                                } else {
                                    waited += 300
                                    status = prefix() + "Video çözülüyor " + idx + "/" + todo.size
                                    cont()
                                }
                            }
                            return
                        }
                        waited += 300
                        cont()
                    }

                    pollRef = { poll() }
                    poll()
                }
            }
        }
        nextFilm()
    }



    // ---------------- uzaktan tanı (GitHub üzerinden) ----------------
    @Volatile private var diagOn = false
    private var diagLast = ""

    fun diagStart() {
        if (!host.flag("diag") || diagOn) return
        if (host.setting("githubToken").trim().isEmpty()) return
        diagOn = true
        Thread {
            while (host.flag("diag")) {
                try { diagTick(host.setting("githubToken").trim()) } catch (_: Exception) {}
                try { Thread.sleep(8000) } catch (_: Exception) {}
            }
            diagOn = false
        }.start()
    }

    private fun diagGet(path: String, token: String): JSONObject? {
        val c = ghConn("https://api.github.com/repos/darknesslord19/Ghost-Browser/contents/$path?ref=diag", "GET", token)
        if (c.responseCode != 200) return null
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        val txt = String(android.util.Base64.decode(o.optString("content").replace("\n", ""), android.util.Base64.DEFAULT), Charsets.UTF_8)
        return JSONObject(txt)
    }

    private fun diagTick(token: String) {
        val cmd = diagGet("cmd.json", token) ?: return
        val id = cmd.optString("id")
        if (id.isEmpty() || id == diagLast) return
        diagLast = id
        val prev = try { diagGet("out.json", token) } catch (_: Exception) { null }
        if (prev != null && prev.optString("id") == id) return
        val out = JSONObject().put("id", id).put("op", cmd.optString("op")).put("at", System.currentTimeMillis())
        try {
            when (cmd.optString("op")) {
                "fetch" -> diagFetch(cmd, out)
                "probe" -> diagProbe(cmd, out)
                else -> out.put("error", "bilinmeyen op")
            }
        } catch (e: Exception) {
            out.put("error", e.toString())
        }
        ghPut("darknesslord19/Ghost-Browser", "out.json", out.toString(2), "diag", token)
    }

    @Volatile private var diagOut = ""
    fun diagRun(url: String, wait: Int, js: String, pre: String = ""): String {
        diagOut = ""
        Thread {
            val o = JSONObject()
            try { diagProbe(JSONObject().put("url", url).put("wait", wait).put("js", js).put("pre", pre).put("noclick", pre.isNotEmpty()), o) } catch (e: Exception) { o.put("error", e.toString()) }
            diagOut = o.toString()
        }.start()
        return "başladı"
    }
    fun diagResult(): String = diagOut
    fun diagFetchRun(cmd: String): String {
        diagOut = ""
        Thread {
            val o = JSONObject()
            try { diagFetch(JSONObject(cmd), o) } catch (e: Exception) { o.put("error", e.toString()) }
            diagOut = o.toString()
        }.start()
        return "başladı"
    }

    private fun diagFetch(cmd: JSONObject, out: JSONObject) {
        val u = cmd.optString("url")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", cmd.optString("ua", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36"))
        cmd.optJSONObject("headers")?.let { hh -> for (k in hh.keys()) c.setRequestProperty(k, hh.optString(k)) }
        val ref = cmd.optString("ref")
        if (ref.isNotEmpty()) {
            c.setRequestProperty("Referer", ref)
            try { val ru = java.net.URI(ref); c.setRequestProperty("Origin", ru.scheme + "://" + ru.authority) } catch (_: Exception) {}
        }
        try { val ck = android.webkit.CookieManager.getInstance().getCookie(u); if (!ck.isNullOrEmpty() && cmd.optBoolean("cookies", true)) c.setRequestProperty("Cookie", ck) } catch (_: Exception) {}
        val code = c.responseCode
        val st = (if (code >= 400) c.errorStream else c.inputStream)
        val bytes = st?.readBytes() ?: ByteArray(0)
        val hs = JSONObject()
        for ((k, v) in c.headerFields) if (k != null) hs.put(k, v.joinToString(", ").take(200))
        out.put("code", code).put("len", bytes.size).put("headers", hs).put("body", String(bytes, Charsets.UTF_8).take(cmd.optInt("max", 1500)))
    }

    private fun diagProbe(cmd: JSONObject, out: JSONObject) {
        val latch = java.util.concurrent.CountDownLatch(1)
        val waitMs = cmd.optInt("wait", 20) * 1000
        main.post {
            if (running) {
                out.put("error", "tarama sürüyor")
                latch.countDown()
                return@post
            }
            begin("Tanı…")
            phase = "diag"
            val g = gen
            load(cmd.optString("url"), g) {
                var waited = 0
                lateinit var tk: () -> Unit
                tk = {
                    waited += 500
                    if (waited % 3000 == 0 && !cmd.optBoolean("noclick")) evalJs(CLICK_JS, g) { tap(it) }
                    if (waited == 500 && cmd.optString("pre").isNotEmpty()) evalJs(cmd.optString("pre"), g) { }
                    if (waited >= waitMs) {
                        val js = cmd.optString("js").ifEmpty { "'-'" }
                        evalJs(DIAG2_JS, g) { d ->
                            out.put("diag", d ?: "")
                            val t = tab
                            val arr = JSONArray()
                            t?.media?.forEach { arr.put(JSONObject().put("url", it.url).put("ref", it.ref).put("kind", it.kind).put("body", it.body)) }
                            out.put("media", arr)
                            evalJs("(()=>{try{return String(eval(" + JSONObject.quote(js) + "))}catch(e){return 'ERR '+e}})()", g) { r ->
                                out.put("js", r ?: "")
                                finish("Tanı bitti")
                                latch.countDown()
                            }
                        }
                    } else step(500, g) { tk() }
                }
                tk()
            }
        }
        if (!latch.await((waitMs + 60000).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)) out.put("error", "zaman aşımı")
    }

    // ---------------- GitHub aktarımı ----------------
    @Volatile var ghStatus = ""
        private set
    @Volatile private var ghBusy = false

    private fun gitBlobSha(b: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update(("blob " + b.size + "\u0000").toByteArray())
        md.update(b)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ghConn(url: String, method: String, token: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("Authorization", "token $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "GhostBrowser")
        return c
    }

    /** Dosyayı repoya yazar (varsa günceller). Dönüş: null = tamam, "=" = değişmedi, aksi halde hata metni. */
    private fun ghPut(repo: String, path: String, text: String, branch: String, token: String): String? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val enc = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val api = "https://api.github.com/repos/$repo/contents/$enc"
        var sha = ""
        val g = ghConn(api + if (branch.isNotEmpty()) "?ref=" + java.net.URLEncoder.encode(branch, "UTF-8") else "", "GET", token)
        val gc = g.responseCode
        if (gc == 200) {
            sha = JSONObject(g.inputStream.bufferedReader().readText()).optString("sha")
            if (sha == gitBlobSha(bytes)) return "="
        } else if (gc == 401) return "Token geçersiz (401)"
        else if (gc == 403) return "Yetki yok / limit (403)"
        else if (gc != 404) return "GitHub hatası ($gc)"
        val body = JSONObject().put("message", "Ghost Tarayıcı: $path").put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        if (sha.isNotEmpty()) body.put("sha", sha)
        if (branch.isNotEmpty()) body.put("branch", branch)
        val p = ghConn(api, "PUT", token)
        p.doOutput = true
        p.outputStream.use { it.write(body.toString().toByteArray()) }
        val pc = p.responseCode
        if (pc == 200 || pc == 201) return null
        val err = try { p.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
        val msg = try { JSONObject(err).optString("message") } catch (_: Exception) { err.take(120) }
        return "Yazılamadı ($pc): " + msg.take(120)
    }

    /** JSON + M3U8 + M3U dosyalarını GitHub deposuna yükler. h boşsa tüm siteler. */
    fun githubSync(h: String): String {
        val repo = host.setting("githubRepo").trim().removePrefix("https://github.com/").trim('/')
        val token = host.setting("githubToken").trim()
        val branch = host.setting("githubBranch").trim()
        val dir = host.setting("githubPath").trim().trim('/').ifEmpty { "ghost-tarayici" }
        if (!Regex("[\\w.-]+/[\\w.-]+").matches(repo)) return "GitHub repo adı 'kullanici/depo' biçiminde olmalı"
        if (token.isEmpty()) return "GitHub token (ghp_…) gir"
        if (ghBusy) return "GitHub yüklemesi zaten sürüyor"
        val files = ArrayList<Pair<String, String>>()
        val keys = if (h.isEmpty()) sites.keys().asSequence().toList() else listOf(h)
        for (k in keys) {
            if (sites.optJSONObject(k) == null) continue
            val base = fileBase(k)
            files.add("$dir/$base.json" to export(k))
            try {
                val m = exportM3u(k, JSONArray()).first
                files.add("$dir/$base.m3u8" to m)
                files.add("$dir/$base.m3u" to m)
            } catch (_: Exception) {
            }
        }
        if (files.isEmpty()) return "Yüklenecek kayıt yok"
        ghBusy = true
        ghStatus = "GitHub: hazırlanıyor…"
        Thread {
            var ok = 0
            var same = 0
            var err = ""
            try {
                for ((i, f) in files.withIndex()) {
                    ghStatus = "GitHub: " + (i + 1) + "/" + files.size + " · " + f.first.substringAfterLast('/')
                    val r = ghPut(repo, f.first, f.second, branch, token)
                    if (r == null) ok++ else if (r == "=") same++ else { err = r; break }
                }
            } catch (e: Exception) {
                err = "Bağlantı hatası: " + (e.message ?: "").take(80)
            }
            ghStatus = if (err.isEmpty()) "GitHub: $ok yüklendi" + (if (same > 0) ", $same değişmedi" else "") + " ✓" else "GitHub: $err"
            ghBusy = false
        }.start()
        return "GitHub yüklemesi başladı (" + files.size + " dosya)"
    }

    // ---------------- TMDB (isteğe bağlı) ----------------
    @Volatile var enriching = false
        private set
    @Volatile var enrichLeft = 0
        private set
    @Volatile private var enrichStop = false

    private val W = "(?<![\\p{L}\\p{N}])"
    private val E = "(?![\\p{L}\\p{N}])"
    private val yearRe = Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)")
    private val tagRe = Regex("(?i)" + W + "(full\\s*hd|fhd|uhd|hd|4k|1080p|720p|480p|izle|türkçe|turkce|dublajlı|dublaj|altyazılı|altyazi|filmi|film|sansürsüz|tek\\s*parça|bluray|webrip|web-dl|hdrip|dual|yerli|yabancı)" + E)

    /** "Robin Hood'un Ölümü 2026 Filmi 1080p Full HD izle" -> ("Robin Hood'un Ölümü", "2026") */
    fun cleanTitle(raw: String): Pair<String, String> {
        var s = raw
        val year = yearRe.findAll(s).lastOrNull()?.value ?: ""
        s = s.replace(Regex("[\\(\\[][^\\)\\]]*[\\)\\]]"), " ")
        val noYear = yearRe.replace(s, " ")
        var t = tagRe.replace(noYear, " ").replace(Regex("[|:–—]+"), " ").replace(Regex("\\s+-\\s+"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) t = if (year.isNotEmpty()) year else raw.trim()
        return t to year
    }

    private fun tmdbGet(url: String, key: String): String {
        val bearer = key.length > 40
        val u = if (bearer) url else url + (if (url.contains("?")) "&" else "?") + "api_key=" + java.net.URLEncoder.encode(key, "UTF-8")
        val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 9000
        c.readTimeout = 9000
        c.setRequestProperty("Accept", "application/json")
        if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
        try {
            if (c.responseCode == 401) throw RuntimeException("TMDB anahtarı geçersiz")
            if (c.responseCode !in 200..299) throw RuntimeException("TMDB HTTP " + c.responseCode)
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun lookup(title: String, year: String, key: String): JSONObject? {
        val (q, y) = cleanTitle(title).let { it.first to (if (year.isNotEmpty()) year else it.second) }
        val res = JSONObject(tmdbGet("https://api.themoviedb.org/3/search/multi?include_adult=false&language=tr-TR&query=" + java.net.URLEncoder.encode(q, "UTF-8"), key)).optJSONArray("results") ?: return null
        var best: JSONObject? = null
        for (i in 0 until res.length()) {
            val r = res.getJSONObject(i)
            val mt = r.optString("media_type")
            if (mt != "movie" && mt != "tv") continue
            if (r.optString("poster_path").isEmpty() || r.optString("poster_path") == "null") continue
            val d = r.optString("release_date").ifEmpty { r.optString("first_air_date") }
            val ry = d.take(4)
            if (y.isNotEmpty() && ry.isNotEmpty() && Math.abs((ry.toIntOrNull() ?: 0) - y.toInt()) <= 1) { best = r; break }
            if (best == null) best = r
        }
        val r = best ?: return null
        val d = r.optString("release_date").ifEmpty { r.optString("first_air_date") }
        val img = "https://image.tmdb.org/t/p/"
        return JSONObject().put("id", r.optInt("id")).put("type", r.optString("media_type"))
            .put("title", r.optString("title").ifEmpty { r.optString("name") })
            .put("orig", r.optString("original_title").ifEmpty { r.optString("original_name") })
            .put("year", d.take(4))
            .put("poster", img + "w342" + r.optString("poster_path"))
            .put("backdrop", if (r.optString("backdrop_path").isNotEmpty() && r.optString("backdrop_path") != "null") img + "w780" + r.optString("backdrop_path") else "")
            .put("rating", Math.round(r.optDouble("vote_average", 0.0) * 10) / 10.0)
            .put("overview", r.optString("overview"))
    }

    /** Seçili kategorilerdeki (boşsa hepsindeki) bilgisi olmayan filmleri TMDB'den tamamlar. */
    fun enrich(h: String, urls: JSONArray, key: String): String {
        if (key.isBlank()) return "Önce TMDB anahtarını kaydet"
        if (enriching) return "TMDB işlemi sürüyor"
        val site = sites.optJSONObject(h) ?: return "Site yok"
        val todo = ArrayList<JSONObject>()
        val want = HashSet<String>()
        for (i in 0 until urls.length()) want.add(urls.getString(i))
        val ca = cats(site)
        for (i in 0 until ca.length()) {
            val cat = ca.getJSONObject(i)
            if (want.isNotEmpty() && !want.contains(cat.optString("url"))) continue
            val fl = cat.optJSONArray("films") ?: continue
            for (k in 0 until fl.length()) {
                val f = fl.getJSONObject(k)
                if (!f.has("tmdb") && !f.has("series") && f.optInt("tmdbTried") != 1) todo.add(f)
            }
        }
        if (todo.isEmpty()) return "Bilgi çekilecek film yok"
        val list = todo.take(500)
        enriching = true
        enrichStop = false
        enrichLeft = list.size
        Thread {
            var failMsg = ""
            try {
                for (f in list) {
                    if (enrichStop) break
                    var info: JSONObject? = null
                    var ok = true
                    try {
                        info = lookup(f.optString("title"), "", key)
                    } catch (e: Exception) {
                        ok = false
                        failMsg = e.message ?: "hata"
                        if (failMsg.contains("anahtar")) break
                    }
                    main.post {
                        if (info != null) f.put("tmdb", info) else if (ok) f.put("tmdbTried", 1)
                        schedule()
                    }
                    enrichLeft--
                    try { Thread.sleep(260) } catch (_: InterruptedException) {}
                }
            } finally {
                enriching = false
                enrichLeft = 0
                if (failMsg.isNotEmpty()) main.post { status = "TMDB: $failMsg" }
            }
        }.start()
        return ""
    }

    fun stopEnrich() {
        enrichStop = true
    }

    // ---------------- sorgular ----------------
    fun stateJson(): JSONObject = JSONObject().put("running", running).put("status", status).put("left", jobs.size).put("total", totalJobs)
        .put("enriching", enriching).put("enrichLeft", enrichLeft)
        .put("phase", phase).put("cat", curCat).put("prog", prog).put("progTotal", progTotal).put("gh", ghStatus).put("cur", curTitle).put("curUrl", curUrl).put("recent", recent()).put("queue", jobs.size)

    fun summary(): JSONArray {
        val out = JSONArray()
        val keys = sites.keys().asSequence().toList().sortedByDescending { sites.getJSONObject(it).optLong("time") }
        for (h in keys) {
            val s = sites.getJSONObject(h)
            val ca = JSONArray()
            val src = cats(s)
            for (i in 0 until src.length()) {
                val c = src.getJSONObject(i)
                val fl = c.optJSONArray("films") ?: JSONArray()
                var res = 0
                var failed = 0
                var info = 0
                for (k in 0 until fl.length()) {
                    val fo = fl.getJSONObject(k)
                    if (fo.optString("video").isNotEmpty()) res++
                    else if (fo.optInt("tried") >= 2) failed++
                    if (fo.has("tmdb")) info++
                }
                ca.put(JSONObject().put("name", c.optString("name")).put("group", c.optString("group")).put("url", c.optString("url"))
                    .put("films", fl.length()).put("resolved", res).put("lastNew", c.optInt("lastNew")).put("lastCheck", c.optLong("lastCheck")).put("failed", failed).put("info", info).put("done", c.optBoolean("done")).put("pages", c.optInt("pages")).put("total", c.optInt("totalPages")))
            }
            out.put(JSONObject().put("host", h).put("url", s.optString("url")).put("title", s.optString("title")).put("time", s.optLong("time")).put("cats", ca))
        }
        return out
    }

    fun log(h: String, url: String): String = findCat(h, url)?.optString("log") ?: ""

    fun films(h: String, url: String): JSONArray = findCat(h, url)?.optJSONArray("films") ?: JSONArray()

    fun remove(h: String) {
        sites.remove(h)
        schedule()
    }

    // ---------------- dışa / içe aktar ----------------
    fun export(h: String): String {
        val arr = JSONArray()
        val keys = if (h.isEmpty()) sites.keys().asSequence().toList() else listOf(h)
        for (k in keys) {
            val s = sites.optJSONObject(k) ?: continue
            val cs = JSONArray()
            val src = cats(s)
            for (i in 0 until src.length()) {
                val c = src.getJSONObject(i)
                val fl = JSONArray()
                val fs = c.optJSONArray("films") ?: JSONArray()
                for (n in 0 until fs.length()) {
                    val f = fs.getJSONObject(n)
                    fl.put(JSONObject().put("title", f.optString("title")).put("url", f.optString("url")).put("poster", f.optString("poster"))
                        .put("video", f.optString("video")).put("videoRef", f.optString("videoRef")).also { o -> f.optJSONObject("tmdb")?.let { o.put("tmdb", it) } })
                }
                cs.put(JSONObject().put("name", c.optString("name")).put("group", c.optString("group")).put("url", c.optString("url")).put("films", fl))
            }
            arr.put(JSONObject().put("host", k).put("url", s.optString("url")).put("title", s.optString("title")).put("categories", cs))
        }
        return JSONObject().put("format", "ghost-crawl-1").put("sites", arr).toString(2)
    }

    /** Çözülmüş videoları M3U8 oynatma listesi olarak döndürür (seçili kategoriler, boşsa hepsi). */
    fun exportM3u(h: String, urls: JSONArray): Pair<String, Int> {
        val site = sites.optJSONObject(h) ?: throw IllegalArgumentException("Site yok")
        val want = HashSet<String>()
        for (i in 0 until urls.length()) want.add(urls.getString(i))
        val sb = StringBuilder("#EXTM3U\n")
        var n = 0
        val ca = cats(site)
        for (i in 0 until ca.length()) {
            val cat = ca.getJSONObject(i)
            if (want.isNotEmpty() && !want.contains(cat.optString("url"))) continue
            val grp = ((if (cat.optString("group").isNotEmpty()) cat.optString("group") + " > " else "") + cat.optString("name")).replace("\"", "'")
            val fl = cat.optJSONArray("films") ?: continue
            for (k in 0 until fl.length()) {
                val f = fl.getJSONObject(k)
                val v = f.optString("video")
                if (v.isEmpty()) continue
                val tm = f.optJSONObject("tmdb")
                val title = (tm?.optString("title")?.ifEmpty { null } ?: cleanTitle(f.optString("title")).first).replace(Regex("[\\r\\n]+"), " ")
                val logo = (tm?.optString("poster")?.ifEmpty { null } ?: f.optString("poster")).replace("\"", "")
                val g2 = if (f.has("series")) grp + " > " + f.optString("series").replace("\"", "'") else grp
                val title2 = if (f.has("series")) f.optString("title") else title
                sb.append("#EXTINF:-1 tvg-name=\"").append(title2.replace("\"", "'")).append("\" tvg-logo=\"").append(logo).append("\" group-title=\"").append(g2).append("\",").append(title2).append('\n')
                val ref = f.optString("videoRef")
                if (ref.isNotEmpty()) sb.append("#EXTVLCOPT:http-referrer=").append(ref).append('\n')
                sb.append(v).append('\n')
                n++
            }
        }
        if (n == 0) throw IllegalArgumentException("Çözülmüş video yok. Önce \"Videoları çöz\" çalıştır.")
        return sb.toString() to n
    }

    private fun fileBase(h: String): String = (if (h.isEmpty()) "tum-siteler" else h).replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun saveM3u(h: String, urls: JSONArray, ext: String): String {
        val (text, n) = exportM3u(h, urls)
        val e = if (ext == "m3u") "m3u" else "m3u8"
        return host.saveText(fileBase(h) + "." + e, text) + " ($n video)"
    }

    fun saveFile(h: String): String {
        return host.saveText(fileBase(h) + ".json", export(h))
    }

    /** Dışa aktarılmış JSON'u mevcut kayıtlara ekler. Eklenen film sayısını döndürür. */
    fun import(text: String): Int {
        val root: Any = try { JSONTokener(text.trim()).nextValue() } catch (_: Exception) { throw IllegalArgumentException("Geçerli JSON değil") }
        val list = JSONArray()
        when (root) {
            is JSONObject -> if (root.has("sites")) {
                val a = root.getJSONArray("sites")
                for (i in 0 until a.length()) list.put(a.get(i))
            } else list.put(root)
            is JSONArray -> for (i in 0 until root.length()) list.put(root.get(i))
            else -> throw IllegalArgumentException("Geçerli JSON değil")
        }
        var added = 0
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val url = s.optString("url")
            val h = s.optString("host").ifEmpty { hostOf(url) }
            if (h.isEmpty()) continue
            val site = sites.optJSONObject(h) ?: JSONObject().put("host", h).put("url", url).also { sites.put(h, it) }
            site.put("time", System.currentTimeMillis())
            if (site.optString("title").isEmpty()) site.put("title", s.optString("title"))
            val ca = cats(site)
            val incoming = s.optJSONArray("categories") ?: continue
            for (k in 0 until incoming.length()) {
                val c = incoming.optJSONObject(k) ?: continue
                val cu = c.optString("url")
                if (cu.isEmpty()) continue
                val cat = findCat(h, cu) ?: JSONObject().put("name", c.optString("name")).put("group", c.optString("group")).put("url", cu)
                    .put("films", JSONArray()).put("pages", 0).put("done", true).put("next", "").also { ca.put(it) }
                val fl = cat.optJSONArray("films") ?: JSONArray().also { cat.put("films", it) }
                val idx = HashMap<String, JSONObject>()
                for (n in 0 until fl.length()) idx[fl.getJSONObject(n).optString("url")] = fl.getJSONObject(n)
                val inc = c.optJSONArray("films") ?: continue
                for (n in 0 until inc.length()) {
                    val f = inc.optJSONObject(n) ?: continue
                    val fu = f.optString("url")
                    if (fu.isEmpty()) continue
                    val ex = idx[fu]
                    if (ex == null) {
                        fl.put(JSONObject().put("title", f.optString("title")).put("url", fu).put("poster", f.optString("poster")).put("video", f.optString("video")).put("videoRef", f.optString("videoRef")).also { o -> f.optJSONObject("tmdb")?.let { o.put("tmdb", it) } })
                        added++
                    } else if (ex.optString("video").isEmpty() && f.optString("video").isNotEmpty()) {
                        ex.put("video", f.optString("video")).put("videoRef", f.optString("videoRef"))
                    }
                }
            }
        }
        schedule()
        return added
    }

    companion object {
        /** Kategori adaylarını bulur (menü, kenar çubuğu, /kategori/ /tur/ benzeri bağlantılar). */
        val CATS_JS = """(()=>{try{
const O=location.origin,H=location.href.split('#')[0];
const bad=/login|giris|register|kayit|uye-|iletisim|contact|dmca|hakkimizda|gizlilik|privacy|sss|faq|rss|feed|wp-admin|wp-login|javascript:|mailto:|tel:|telegram|whatsapp|facebook|twitter|instagram|reklam|sitemap|arama|search|[?]s=|cart|sepet|vip|profil|logout|cikis/i;
const kw=/kategori|category|\/tur\/|\/turu\/|genre|\/filmler|\/diziler|-filmleri|-filmler|animasyon|aksiyon|komedi|korku|dram|belgesel|bilim-kurgu|macera|gerilim|romantik|savas|suc|fantastik/i;
const abs=h=>{try{const u=new URL(h,H);u.hash='';return u.href}catch(e){return ''}};
const roots='header,nav,aside,[class*=menu],[class*=nav],[id*=menu],[id*=nav],[class*=categor],[class*=kategori],[class*=genre],[id*=categor],[id*=kategori],[class*=sidebar],[class*=widget]';
const out=new Map();
for(const a of document.querySelectorAll('a[href]')){
 const raw=a.getAttribute('href')||'';if(!raw||raw[0]==='#')continue;
 const u=abs(raw);if(!u||u.indexOf(O)!==0)continue;
 const path=new URL(u).pathname;if(path==='/'||path==='')continue;
 if(bad.test(u))continue;
 const n=(a.textContent||'').replace(/\s+/g,' ').trim();
 if(n.length<2||n.length>32||/^\d+${'$'}/.test(n))continue;
 if(a.querySelector('img')&&!a.closest('nav,[class*=menu]'))continue;
 const menu=!!a.closest(roots),k=kw.test(u);
 if(!menu&&!k)continue;
 const depth=path.split('/').filter(Boolean).length;
 if(!menu&&depth>3)continue;
 const key=u.replace(/\/+${'$'}/,'');
 if(out.has(key))continue;
 const li=a.closest('li'),pli=li&&li.parentElement?li.parentElement.closest('li'):null;
 let g='';
 if(pli){const pa=pli.querySelector(':scope>a');if(pa&&pa!==a)g=(pa.textContent||'').replace(/\s+/g,' ').trim().slice(0,32)}
 out.set(key,{n:n,u:u,g:g,s:(menu?1:0)+(k?2:0)});
}
const arr=[...out.values()].sort((a,b)=>b.s-a.s).slice(0,150);
return JSON.stringify({title:document.title,list:arr});
}catch(e){return JSON.stringify({title:'',list:[]})}})()"""

        const val SCROLL_JS = "(()=>{const h=Math.max(document.body.scrollHeight,document.documentElement.scrollHeight);window.scrollTo(0,Math.max(0,h-1500));window.scrollTo(0,h);return String(document.querySelectorAll('a img').length)})()"

        private const val NEXT_FINDER = """
const abs=h=>{try{const u=new URL(h,location.href);u.hash='';return u.href}catch(e){return ''}};
const H=location.href.split('#')[0],O=location.origin;
const SELS=['link[rel=next]','a[rel=next]','.next a','a.next','a.nextpostslink','li.next a','a.page-numbers.next','[class*=pagination] a[class*=next]','[class*=pager] a[class*=next]','a[aria-label*=ext]','a[title*=onraki]','a[aria-label*=onraki]','.pagination .active + * a','.pagination .current + * a','li.active + li > a','li.current + li > a','span.current + a','.page-numbers.current + a','.pagination .active + a','[class*=pagination] .active + li a'];
const okUrl=u=>u&&u!==H&&u.indexOf(O)===0;
const findNext=()=>{
 for(const s of SELS){let e;try{e=document.querySelector(s)}catch(x){continue}if(e){const h=e.getAttribute('href');const u=h?abs(h):'';if(okUrl(u))return{u:u,el:e}}}
 for(const a of document.querySelectorAll('a[href]')){const t=(a.textContent||'').trim();if(t.length<16&&/^(sonraki|ileri|next|›|»|→|>)/i.test(t)){const u=abs(a.getAttribute('href'));if(okUrl(u))return{u:u,el:a}}}
 for(const a of document.querySelectorAll('[class*=pagination] a[href],[class*=pager] a[href]')){const k=((a.className||'')+' '+(a.getAttribute('aria-label')||'')+' '+(a.title||'')+' '+((a.querySelector('i,svg,span')||{}).className||'')).toString();if(/next|right|forward|ileri|sonraki/i.test(k)){const u=abs(a.getAttribute('href'));if(okUrl(u))return{u:u,el:a}}}
 return null};
const SLIDER='[class*=slick],[class*=swiper],[class*=carousel],[class*=slider],[class*=owl-]';
const ctlSels=['button[aria-label*=onraki]','button[aria-label*=Next]','button.ldm','.next','a.next','button.next','[class*=next]:not(a[href])','[class*=load-more]','[class*=loadmore]','[class*=more-btn]','button[class*=more]','a[class*=more]','[class*=pagination] [class*=next]'];
const visible=e=>e&&e.offsetParent!==null&&!e.disabled&&e.getAttribute('aria-disabled')!=='true'&&!/disabled/.test(e.className||'')&&!e.closest(SLIDER);
const ctlIn=root=>{
 const gp=(window.__gpage||1)+1;
 for(const s of ctlSels){let l;try{l=root.querySelectorAll(s)}catch(x){continue}for(const e of l)if(visible(e))return e}
 for(const e of root.querySelectorAll('[class*=pagination] *,[class*=pager] *,[class*=paging] *,.page-numbers')){if(e.children.length===0&&(e.textContent||'').trim()===String(gp)&&visible(e))return e}
 for(const e of root.querySelectorAll('button,a,span,div,li')){if(e.children.length>2)continue;const t=(e.textContent||'').trim();if(t.length<22&&/^(daha fazla|devam|devamı|load more|more|sonraki|ileri|next|›|»|→|>)/i.test(t)&&visible(e))return e}
 return null};
const findCtl=()=>{
 let el=window.__gridCont||null;
 for(let i=0;i<5&&el;i++){el=el.parentElement;if(!el)break;const r=ctlIn(el);if(r)return r}
 return ctlIn(document)};
"""

        /** Kategori sayfasındaki film kartlarını, sonraki sayfa bağlantısını ve toplam sayfa sayısını bulur. */
        val LIST_JS = """(()=>{try{
""" + NEXT_FINDER + """
const shape=u=>{const p=new URL(u);const s=p.pathname.split('/').filter(Boolean);s.pop();return s.map(x=>/\d/.test(x)?'#':x).join('/')+'|'+s.length};
const map=new Map();
const side='[class*=sidebar],[class*=widget],aside';
for(const a of document.querySelectorAll('a[href]')){
 if(a.closest('header,nav,footer,[class*=menu],[class*=pagination],[class*=pager],[class*=breadcrumb]'))continue;
 const u=abs(a.getAttribute('href')||'');if(!u||u.indexOf(O)!==0||u===H)continue;
 const img=a.querySelector('img');
 let e=map.get(u);if(!e){e={u:u,t:'',p:'',side:!!a.closest(side),a:a};map.set(u,e)}
 if(img){const src=img.getAttribute('data-src')||img.getAttribute('data-lazy-src')||img.getAttribute('data-original')||img.currentSrc||img.getAttribute('src')||'';if(src&&!e.p)e.p=abs(src);const c=a.title||img.alt||'';if(c.length>e.t.length)e.t=c}
 const ttl=(a.getAttribute('title')||'').replace(/\s+/g,' ').trim();
 if(ttl){e.t=ttl;e.pin=1}
 else if(!e.pin){const cl=a.cloneNode(true);cl.querySelectorAll('style,script,noscript,svg').forEach(x=>x.remove());const tx=(cl.textContent||'').replace(/\s+/g,' ').trim();
 if(tx.length>e.t.length&&tx.length<140&&tx.indexOf('{')<0)e.t=tx}
}
let items=[...map.values()].filter(e=>e.p);
if(items.length<3)items=[...map.values()].filter(e=>e.t.length>3);
const main=items.filter(e=>!e.side);if(main.length>=3)items=main;
const cnt={};items.forEach(e=>{e.k=shape(e.u);cnt[e.k]=(cnt[e.k]||0)+1});
const top=Math.max(0,...Object.values(cnt));
items=items.filter(e=>cnt[e.k]>=Math.max(2,top*0.2)||items.length<4);
const groups=new Map();
for(const e of items){let el=e.a,cont=null;for(let i=0;i<6&&el&&el.parentElement;i++){el=el.parentElement;const sib=el.parentElement;if(!sib)break;let n=0;for(const ch of sib.children){if(ch.tagName===el.tagName&&(ch.classList[0]||'')===(el.classList[0]||''))n++}if(n>=6){cont=sib;break}}
 if(cont){if(!groups.has(cont))groups.set(cont,[]);groups.get(cont).push(e)}}
let bestG=null;for(const [k,l] of groups){if(!bestG||l.length>bestG.l.length)bestG={k:k,l:l}}
window.__gridCont=null;
if(bestG&&bestG.l.length>=Math.max(6,items.length*0.4)){items=bestG.l;window.__gridCont=bestG.k}
const films=items.slice(0,600).map(e=>({t:e.t.replace(/\s+izle\s*${'$'}/i,'').trim(),u:e.u,p:e.p}));
// toplam sayfa + mevcut sayfa
const pgSel='[class*=pagination] a,[class*=pagination] button,[class*=pager] a,[class*=paging] a,a.page-numbers,a[class*=page-link],nav[aria-label*=agin] a';
let last=0;const nums=[];
document.querySelectorAll('span,div,li').forEach(e=>{if(e.children.length>3)return;const t=(e.textContent||'').trim();if(t.length>11)return;const m=t.match(/^(\d{1,3})\s*\/\s*(\d{1,4})${'$'}/);if(m){const v=parseInt(m[2]);if(v>1&&v<5000&&v>=parseInt(m[1]))last=Math.max(last,v)}});
const dp=document.querySelector('[data-pages]');if(dp){const v=parseInt(dp.getAttribute('data-pages'));if(v>0)last=v}
document.querySelectorAll(pgSel).forEach(a=>{const t=(a.textContent||'').trim();if(/^\d{1,4}${'$'}/.test(t)){const n=parseInt(t);last=Math.max(last,n);nums.push({n:n,h:a.getAttribute('href')||''})}});
const sp=new URL(H);let cur=1;
const q=sp.searchParams.get('page')||sp.searchParams.get('paged')||sp.searchParams.get('p');
if(q&&/^\d+${'$'}/.test(q))cur=parseInt(q);
else{const seg=sp.pathname.split('/').filter(Boolean);const l=seg[seg.length-1]||'';if(/^\d+${'$'}/.test(l))cur=parseInt(l);else{const pi=seg.indexOf('page');if(pi>=0&&/^\d+${'$'}/.test(seg[pi+1]||''))cur=parseInt(seg[pi+1])}}
let next='';const fn=findNext();if(fn)next=fn.u;
if(!next){for(const a of document.querySelectorAll('a[href]')){if((a.textContent||'').trim()===String(cur+1)&&a.closest('[class*=pagination],[class*=pager],[class*=paging],nav,ul')){const u=abs(a.getAttribute('href'));if(okUrl(u)){next=u;break}}}}
if(!next&&last>cur){
 for(const x of nums){if(!x.h||x.h[0]==='#')continue;try{const u=new URL(x.h,H);const segs=u.pathname.split('/');const i=segs.lastIndexOf(String(x.n));
  if(i>=0){segs[i]=String(cur+1);u.pathname=segs.join('/');next=abs(u.href);break}
  for(const k of ['page','paged','p']){if(u.searchParams.get(k)===String(x.n)){u.searchParams.set(k,String(cur+1));next=abs(u.href);break}}
  if(next)break}catch(e){}}
}
if(next===H)next='';
let nc=false;if(!next){nc=!!findCtl()}
return JSON.stringify({films:films,next:next,last:last,cur:cur,nc:nc,href:H});
}catch(e){return JSON.stringify({films:[],next:'',last:0,nc:false})}})()"""

        /** Adresi olmayan (ajax / sonsuz kaydırma) sonraki-sayfa denetimine tıklar. */
        val CLICKNEXT_JS = """(()=>{try{
""" + NEXT_FINDER + """
const e=findCtl();if(!e)return '0';
window.__gpage=(window.__gpage||1)+1;
try{e.scrollIntoView({block:'center'})}catch(x){}
e.click();return '1';
}catch(e){return '0'}})()"""

        /** Dizi sayfasındaki tüm bölüm adreslerini (…-3x5 gibi) bulur; sayfa kaynağında geçenleri alır. */
        fun epsJs(base: String): String = """(()=>{try{
const base=""" + org.json.JSONObject.quote(base) + """;
const slug=base.toLowerCase().replace(/\.(html?|php|asp)${'$'}/,'').replace(/-(dizi-izle|izle|dizi)${'$'}/,'');
const h=document.documentElement.outerHTML.split('\\/').join('/');
const seen={};const out=[];
const add=(p,s,e)=>{const k=s+'x'+e;if(seen[k]||!(s>=0)||!(e>=0))return;seen[k]=1;let u;try{u=new URL(p,location.href).href}catch(x){return}if(u.indexOf(location.origin)!==0)return;out.push({u:u,s:s,e:e})};
const re=/\/[a-z0-9_\-\/.%]*?\d{1,2}x\d{1,3}(?:-c\d+)?(?=["'\\?\s<#&])/gi;let m;
while((m=re.exec(h))){const p=m[0];const seg=p.split('/').pop().toLowerCase();
 const mm=seg.match(/^(.*?)-(\d{1,2})x(\d{1,3})(?:-c\d+)?${'$'}/);if(!mm)continue;
 if(mm[1]!==base.toLowerCase())continue;add(p,parseInt(mm[2]),parseInt(mm[3]))}
const re2=/\/[a-z0-9_\-\/.%]*?(\d{1,2})-sezon[\/-](\d{1,3})-bolum[a-z0-9_\-.]*/gi;
while((m=re2.exec(h))){const p=m[0].split('#')[0].split('?')[0];if(slug&&p.toLowerCase().indexOf('/'+slug+'/')<0)continue;add(p,parseInt(m[1]),parseInt(m[2]))}
out.sort((a,b)=>a.s-b.s||a.e-b.e);
return JSON.stringify(out)}catch(e){return '[]'}})()"""

        /** Tıklamadan sonra sitenin kendi sayfalama isteğini (ör. /load/page/2/...) bulup {n} kalıbına çevirir. */
        val TPL_JS = """(()=>{try{
const n=window.__gpage||2;const gr=window.__gr||[];
const re=new RegExp('(?<![0-9])'+n+'(?![0-9])');
for(let i=gr.length-1;i>=0;i--){let u;try{u=new URL(gr[i],location.href)}catch(e){continue}
 if(u.origin!==location.origin)continue;
 const s=u.pathname+u.search;
 if(!/page|load|paged|offset|[?&]p=/i.test(s))continue;
 if(re.test(s))return s.replace(re,'{n}');
}
return '';
}catch(e){return ''}})()"""

        /** Sitenin sayfalama adresini doğrudan çağırıp film kartlarını sonuca yazar (window.__fres). */
        fun fetchJs(tpl: String, n: Int): String = """(()=>{window.__fres=null;try{
const u=""" + org.json.JSONObject.quote(tpl) + """.replace('{n}','""" + n + """');
fetch(u,{headers:{'X-Requested-With':'fetch'},credentials:'same-origin'}).then(r=>r.text()).then(t=>{
 let h=t;try{const j=JSON.parse(t);if(typeof j==='string')h=j;else{for(const k of Object.keys(j)){if(typeof j[k]==='string'&&j[k].indexOf('<a')>=0){h=j[k];break}}}}catch(e){}
 const d=new DOMParser().parseFromString(h,'text/html');
 const out=[];const seen=new Set();
 d.querySelectorAll('a[href]').forEach(a=>{const img=a.querySelector('img');if(!img)return;
  let u2='';try{u2=new URL(a.getAttribute('href'),location.href).href}catch(e){return}
  if(u2.indexOf(location.origin)!==0||seen.has(u2))return;seen.add(u2);
  let p=img.getAttribute('data-src')||img.getAttribute('data-lazy-src')||img.getAttribute('data-original')||'';
  if(!p){const s=img.getAttribute('src')||'';if(s.indexOf('data:')!==0)p=s}
  if(!p){const ss=img.getAttribute('srcset')||'';const f=ss.split(',')[0].trim().split(' ')[0];if(f&&f.indexOf('data:')!==0)p=f}
  const cl=a.cloneNode(true);cl.querySelectorAll('style,script,noscript,svg').forEach(x=>x.remove());let tt=(a.getAttribute('title')||img.getAttribute('alt')||cl.textContent||'').replace(/\s+/g,' ').trim();if(tt.indexOf('{')>=0)tt='';
  let pu='';try{pu=p?new URL(p,location.href).href:''}catch(e){}
  out.push({t:tt,u:u2,p:pu})});
 window.__fres=JSON.stringify({films:out,ok:true})}).catch(e=>{window.__fres=JSON.stringify({films:[],ok:false})});
}catch(e){window.__fres=JSON.stringify({films:[],ok:false})}
return '1'})()"""

        /** Oynat düğmesine basar, oynatıcı alanının merkez koordinatını döndürür. */
        val DIAG2_JS = """(()=>{try{return JSON.stringify({u:location.href,t:document.title,i:[...document.querySelectorAll('iframe')].map(f=>(f.src||'').slice(0,200)).slice(0,5),gr:(window.__gr||[]).slice(-40).map(x=>String(x).slice(0,160)),txt:(document.body?document.body.innerText.replace(/\s+/g,' ').slice(0,300):''),v:document.querySelectorAll('video').length})}catch(e){return ''}})()"""

        val OK_FRAME_JS = """(()=>{try{const f=[...document.querySelectorAll('iframe')].map(x=>x.src||x.getAttribute('data-src')||'').find(s=>/ok\.ru\/video/.test(s));return f||''}catch(e){return ''}})()"""
        val OK_EXTRACT_JS = """(()=>{try{const e=document.querySelector('[data-module=OKVideo]');const o=JSON.parse(e.getAttribute('data-options'));let m=o.flashvars.metadata;if(typeof m==='string')m=JSON.parse(m);if(m.hlsManifestUrl)return m.hlsManifestUrl;const v=(m.videos||[]).filter(x=>x.url);return v.length?v[v.length-1].url:''}catch(e){return ''}})()"""
        val DIAG_JS = """(()=>{try{const t=document.body?document.body.innerText.replace(/\s+/g,' ').slice(0,90):'';return JSON.stringify({i:[...document.querySelectorAll('iframe')].map(f=>(f.src||'').slice(0,70)).slice(0,3),pre:(document.querySelector('[data-dp-preroll]')||{getAttribute(){return ''}}).getAttribute('data-dp-preroll'),v:document.querySelectorAll('video').length,t:t})}catch(e){return ''}})()"""

        val CLICK_JS = """(()=>{try{
 try{const sk=[...document.querySelectorAll('button,a,div,span')].find(e=>e.children.length<2&&e.offsetParent!==null&&/^(reklamı geç|skip ad|reklamı atla)$/i.test((e.textContent||e.getAttribute('aria-label')||'').trim()));if(sk)sk.click()}catch(x){}
 try{if(!window.__nl){window.__nl=1;const n=()=>{};history.pushState=n;history.replaceState=n;document.addEventListener('click',e=>{const a=e.target&&e.target.closest&&e.target.closest('a[href]');if(a&&a.href.split('#')[0]!==location.href.split('#')[0]){e.preventDefault();e.stopImmediatePropagation()}},true)}}catch(x){}
try{window.__lc=(window.__lc||0)+1;const L=[...document.querySelectorAll('a,button,li,div,span')].filter(e=>e.children.length<3&&e.offsetParent!==null);const pk=re=>L.find(e=>{const t=(e.textContent||'').trim();return t.length<24&&re.test(t)});const lt=pk(window.__lc%2?/^(türkçe\s*)?dublaj$/i:/^(türkçe\s*)?altyaz[ıi](lı)?$/i);if(lt)lt.click()}catch(x){}
try{const ev=()=>new CustomEvent('dp:preroll',{detail:'done'});const pe=document.querySelector('[data-dp-preroll]');if(pe&&pe.parentElement)pe.parentElement.dispatchEvent(ev());window.dispatchEvent(ev())}catch(x){}
const sel=['.play-that-video','.play-icon','.vjs-big-play-button','.jw-icon-display','.plyr__control--overlaid','[class*=play-btn]','[class*=playBtn]','[class*=btn-play]','.play-button','#play','button[aria-label*=lay]','[data-preroll-slot]','#cstk','[data-player] button'];
for(const s of sel){const e=document.querySelector(s);if(e&&e.offsetParent!==null){try{e.click()}catch(x){}break}}
document.querySelectorAll('video').forEach(v=>{try{v.muted=true;v.play()}catch(x){}});
window.scrollTo(0,0);
let best=null,ba=0;
for(const q of ['[data-preroll-slot],iframe,video','[class*=player],[id*=player]']){for(const e of document.querySelectorAll(q)){const r=e.getBoundingClientRect();const a=r.width*r.height;if(a>ba&&r.width>150&&r.height>100&&r.top<innerHeight&&r.left<innerWidth){ba=a;best=r}}if(best)break}
if(!best)return JSON.stringify(null);
return JSON.stringify({x:Math.min(best.left+best.width/2,innerWidth-5),y:Math.min(best.top+best.height/2,innerHeight-5)});
}catch(e){return JSON.stringify(null)}})()"""
    }
}
