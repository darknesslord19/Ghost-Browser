package com.darknesslord19.ghostbrowser

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.URI

/** Crawler'ın ana etkinlikten ihtiyaç duyduğu şeyler. */
interface CrawlHost {
    fun hiddenTab(): Tab
    fun release(t: Tab)
    fun awake(on: Boolean)
    fun saveText(name: String, text: String): String
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
        gen++
        status = msg
        jobs.clear()
        tab?.let { host.release(it) }
        tab = null
        host.awake(false)
        schedule()
    }

    fun stop() {
        if (running) finish("Durduruldu")
    }

    private fun ensureTab(): Tab = tab ?: host.hiddenTab().also { tab = it }

    private fun load(url: String, g: Int, done: () -> Unit) {
        val t = ensureTab()
        t.media.clear()
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
    fun list(h: String, urls: JSONArray, maxPages: Int): String {
        if (running) return "Bir tarama sürüyor"
        jobs.clear()
        for (i in 0 until urls.length()) jobs.add(Job("list", h, urls.getString(i), maxPages.coerceIn(1, 5000)))
        if (jobs.isEmpty()) return "Kategori seç"
        startJobs()
        return ""
    }

    fun resolve(h: String, urls: JSONArray, limit: Int): String {
        if (running) return "Bir tarama sürüyor"
        jobs.clear()
        for (i in 0 until urls.length()) jobs.add(Job("resolve", h, urls.getString(i), limit.coerceIn(1, 100000)))
        if (jobs.isEmpty()) return "Kategori seç"
        startJobs()
        return ""
    }

    private fun startJobs() {
        totalJobs = jobs.size
        begin("Başlıyor…")
        nextJob(gen)
    }

    private fun nextJob(g: Int) {
        if (g != gen) return
        val j = jobs.removeFirstOrNull()
        if (j == null) {
            finish("Tamamlandı")
            return
        }
        val after = { step(600, g) { nextJob(g) } }
        if (j.kind == "list") runList(j, g, after) else runResolve(j, g, after)
    }

    private fun prefix(): String = "(${totalJobs - jobs.size}/$totalJobs) "

    private fun runList(j: Job, g: Int, done: () -> Unit) {
        val cat = findCat(j.site, j.cat) ?: return done()
        val films = cat.optJSONArray("films") ?: JSONArray().also { cat.put("films", it) }
        val seen = HashSet<String>()
        for (i in 0 until films.length()) seen.add(films.getJSONObject(i).optString("url"))
        val visited = HashSet<String>()
        var url = if (cat.optBoolean("done") || cat.optString("next").isEmpty()) cat.optString("url") else cat.optString("next")
        if (cat.optBoolean("done")) cat.put("pages", 0)
        var pages = 0

        fun page() {
            status = prefix() + cat.optString("name") + " · sayfa " + (cat.optInt("pages") + 1) + " · " + films.length() + " film"
            visited.add(url)
            load(url, g) {
                evalJs(LIST_JS, g) { r ->
                    var added = 0
                    var next = ""
                    try {
                        val o = JSONObject(r ?: "")
                        next = o.optString("next")
                        val fa = o.optJSONArray("films") ?: JSONArray()
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
                    pages++
                    cat.put("pages", cat.optInt("pages") + 1)
                    val more = next.isNotEmpty() && !visited.contains(next) && added > 0
                    cat.put("next", if (more) next else "")
                    cat.put("done", !more)
                    schedule()
                    if (more && pages < j.n) {
                        url = next
                        step(700, g) { page() }
                    } else done()
                }
            }
        }
        page()
    }

    private fun pickMedia(t: Tab): MediaItem? {
        val ok = t.media.filter { !(it.url.contains("aud", true) && it.url.contains("sublist", true)) }
        return ok.firstOrNull { it.kind == "HLS ★" } ?: ok.firstOrNull() ?: t.media.firstOrNull()
    }

    private fun runResolve(j: Job, g: Int, done: () -> Unit) {
        val cat = findCat(j.site, j.cat) ?: return done()
        val films = cat.optJSONArray("films") ?: return done()
        val todo = ArrayList<JSONObject>()
        for (i in 0 until films.length()) {
            val f = films.getJSONObject(i)
            if (f.optString("video").isEmpty() && f.optInt("tried") != 1) todo.add(f)
            if (todo.size >= j.n) break
        }
        var idx = 0

        fun nextFilm() {
            if (idx >= todo.size) return done()
            val f = todo[idx++]
            status = prefix() + "Video çözülüyor " + idx + "/" + todo.size + " · " + f.optString("title").take(40)
            load(f.optString("url"), g) {
                val t = tab
                if (t == null) {
                    done()
                } else {
                    var waited = 0

                    fun poll() {
                        if (t.media.isNotEmpty()) {
                            step(1500, g) {
                                val m = pickMedia(t)
                                if (m != null) {
                                    f.put("video", m.url).put("videoRef", m.ref).put("videoTime", System.currentTimeMillis())
                                } else f.put("tried", 1)
                                schedule()
                                step(700, g) { nextFilm() }
                            }
                            return
                        }
                        waited += 600
                        if (waited == 1800 || waited == 7200) evalJs(CLICK_JS, g) { tap(it) }
                        if (waited >= 16000) {
                            f.put("tried", 1)
                            schedule()
                            step(600, g) { nextFilm() }
                            return
                        }
                        step(600, g) { poll() }
                    }
                    poll()
                }
            }
        }
        nextFilm()
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
                if (!f.has("tmdb") && f.optInt("tmdbTried") != 1) todo.add(f)
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
                var info = 0
                for (k in 0 until fl.length()) {
                    val fo = fl.getJSONObject(k)
                    if (fo.optString("video").isNotEmpty()) res++
                    if (fo.has("tmdb")) info++
                }
                ca.put(JSONObject().put("name", c.optString("name")).put("group", c.optString("group")).put("url", c.optString("url"))
                    .put("films", fl.length()).put("resolved", res).put("info", info).put("done", c.optBoolean("done")).put("pages", c.optInt("pages")))
            }
            out.put(JSONObject().put("host", h).put("url", s.optString("url")).put("title", s.optString("title")).put("time", s.optLong("time")).put("cats", ca))
        }
        return out
    }

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

    fun saveFile(h: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        return host.saveText("ghost-" + (if (h.isEmpty()) "tum-siteler" else h) + "-" + stamp + ".json", export(h))
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

        /** Kategori sayfasındaki film kartlarını ve sonraki sayfa bağlantısını bulur. */
        val LIST_JS = """(()=>{try{
const H=location.href.split('#')[0],O=location.origin;
const abs=h=>{try{const u=new URL(h,H);u.hash='';return u.href}catch(e){return ''}};
const shape=u=>{const p=new URL(u);const s=p.pathname.split('/').filter(Boolean);const last=s.pop()||'';return s.map(x=>/\d/.test(x)?'#':x).join('/')+'|'+s.length+'|'+(last.length>10?'L':'S')};
const map=new Map();
const side='[class*=sidebar],[class*=widget],aside';
for(const a of document.querySelectorAll('a[href]')){
 if(a.closest('header,nav,footer,[class*=menu],[class*=pagination],[class*=breadcrumb]'))continue;
 const u=abs(a.getAttribute('href')||'');if(!u||u.indexOf(O)!==0||u===H)continue;
 const img=a.querySelector('img');
 let e=map.get(u);if(!e){e={u:u,t:'',p:'',side:!!a.closest(side)};map.set(u,e)}
 if(img){const src=img.getAttribute('data-src')||img.getAttribute('data-lazy-src')||img.getAttribute('data-original')||img.currentSrc||img.getAttribute('src')||'';if(src&&!e.p)e.p=abs(src);const c=a.title||img.alt||'';if(c.length>e.t.length)e.t=c}
 const tx=(a.textContent||'').replace(/\s+/g,' ').trim();
 if(tx.length>e.t.length&&tx.length<140)e.t=tx;
 if(a.title&&a.title.length>e.t.length)e.t=a.title;
}
let items=[...map.values()].filter(e=>e.p);
if(items.length<3)items=[...map.values()].filter(e=>e.t.length>3);
const main=items.filter(e=>!e.side);if(main.length>=3)items=main;
const cnt={};items.forEach(e=>{e.k=shape(e.u);cnt[e.k]=(cnt[e.k]||0)+1});
const top=Math.max(0,...Object.values(cnt));
items=items.filter(e=>cnt[e.k]>=Math.max(3,top*0.3)||items.length<4);
const films=items.slice(0,200).map(e=>({t:e.t.replace(/\s+izle\s*${'$'}/i,'').trim(),u:e.u,p:e.p}));
let next='';
const rn=document.querySelector('link[rel=next],a[rel=next]');if(rn)next=abs(rn.getAttribute('href'));
if(!next){const c=document.querySelector('.next a,a.next,a.nextpostslink,li.next a,[class*=pagination] a[class*=next],a[aria-label*=ext],a[title*=onraki]');if(c)next=abs(c.getAttribute('href'))}
if(!next){for(const a of document.querySelectorAll('a[href]')){const t=(a.textContent||'').trim();if(t.length<16&&/^(sonraki|ileri|next|›|»|→|>)/i.test(t)){const u=abs(a.getAttribute('href'));if(u&&u!==H&&u.indexOf(O)===0){next=u;break}}}}
if(!next){
 const sp=new URL(H);let cur=1;
 const q=sp.searchParams.get('page')||sp.searchParams.get('paged')||sp.searchParams.get('p');
 if(q&&/^\d+${'$'}/.test(q))cur=parseInt(q);
 else{const seg=sp.pathname.split('/').filter(Boolean);const l=seg[seg.length-1]||'';if(/^\d+${'$'}/.test(l))cur=parseInt(l)}
 for(const a of document.querySelectorAll('a[href]')){if((a.textContent||'').trim()===String(cur+1)){const u=abs(a.getAttribute('href'));if(u&&u!==H&&u.indexOf(O)===0){next=u;break}}}
}
if(next===H)next='';
return JSON.stringify({films:films,next:next});
}catch(e){return JSON.stringify({films:[],next:''})}})()"""

        /** Oynat düğmesine basar, oynatıcı alanının merkez koordinatını döndürür. */
        val CLICK_JS = """(()=>{try{
const sel=['.vjs-big-play-button','.jw-icon-display','.plyr__control--overlaid','[class*=play-btn]','[class*=playBtn]','[class*=btn-play]','.play-button','#play','button[aria-label*=lay]'];
for(const s of sel){const e=document.querySelector(s);if(e&&e.offsetParent!==null){try{e.click()}catch(x){}break}}
document.querySelectorAll('video').forEach(v=>{try{v.muted=true;v.play()}catch(x){}});
window.scrollTo(0,0);
let best=null,ba=0;
for(const e of document.querySelectorAll('iframe,video,[class*=player],[id*=player]')){const r=e.getBoundingClientRect();const a=r.width*r.height;if(a>ba&&r.width>150&&r.height>100&&r.top<innerHeight&&r.left<innerWidth){ba=a;best=r}}
if(!best)return JSON.stringify(null);
return JSON.stringify({x:Math.min(best.left+best.width/2,innerWidth-5),y:Math.min(best.top+best.height/2,innerHeight-5)});
}catch(e){return JSON.stringify(null)}})()"""
    }
}
