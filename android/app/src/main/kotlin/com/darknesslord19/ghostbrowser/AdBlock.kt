package com.darknesslord19.ghostbrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac")

fun registrable(host: String): String {
    val p = host.split(".")
    if (p.size <= 2) return host
    val last = p[p.size - 1]
    val sl = p[p.size - 2]
    return if (last.length == 2 && sl in SECOND_LEVEL) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
}

class AdBlock(private val ctx: Context) {
    @Volatile var enabled = true
    val blocked = AtomicInteger(0)
    val popups = AtomicInteger(0)
    private val builtin = HashSet<String>()
    @Volatile private var custom: Set<String> = emptySet()
    @Volatile private var remote: Set<String> = emptySet()
    @Volatile private var pushed: Set<String> = emptySet()
    private val byHost = ConcurrentHashMap<String, Int>()
    private val allowed = ConcurrentHashMap<String, Long>()
    private val pattern = Regex(
        "(^|[/_.-])(popunder|popads|clickunder|adserver|adsterra|exoclick|juicyads|trafficjunky|adsbygoogle|prebid)([/_.-]|\$)",
        RegexOption.IGNORE_CASE
    )
    var cosmeticCss = ""
    private val cacheFile = File(ctx.filesDir, "filter-cache.txt")

    init {
        try {
            ctx.assets.open("builtin-hosts.txt").bufferedReader().useLines { ls -> ls.forEach { if (it.isNotBlank()) builtin.add(it.trim()) } }
            cosmeticCss = ctx.assets.open("cosmetic.css").bufferedReader().readText()
        } catch (_: Exception) {
        }
        try {
            if (cacheFile.exists()) loadRemoteText(cacheFile.readText())
        } catch (_: Exception) {
        }
    }

    fun setCustom(list: JSONArray) {
        val s = HashSet<String>()
        for (i in 0 until list.length()) {
            val h = list.optString(i).trim().lowercase().removePrefix("||").substringBefore("^").substringBefore("/")
            if (h.isNotEmpty()) s.add(h)
        }
        custom = s
    }

    fun setPushed(list: List<String>) {
        pushed = list.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    fun loadRemoteText(text: String): Int {
        val set = HashSet<String>()
        val a = Regex("^\\|\\|([a-z0-9.-]+)\\^?\$", RegexOption.IGNORE_CASE)
        val b = Regex("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1)\\s+([a-z0-9.-]+)", RegexOption.IGNORE_CASE)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '#' || line[0] == '[' || line.startsWith("@@")) continue
            val m = a.find(line) ?: b.find(line)
            if (m != null) {
                val h = m.groupValues[1].lowercase()
                if (h != "localhost") set.add(h)
            }
        }
        remote = set
        return set.size
    }

    /** Arka plan iş parçacığında çağır. */
    fun refreshFilter(url: String, force: Boolean) {
        if (url.isEmpty()) {
            remote = emptySet()
            return
        }
        val stale = !cacheFile.exists() || System.currentTimeMillis() - cacheFile.lastModified() > 3L * 86400000
        if (!force && !stale) return
        try {
            val text = httpGet(url, 20000)
            if (loadRemoteText(text) > 100) cacheFile.writeText(text)
        } catch (_: Exception) {
        }
    }

    fun hit(host: String): String? {
        val parts = host.split(".")
        for (i in 0..(parts.size - 2)) {
            val h = parts.subList(i, parts.size).joinToString(".")
            if (builtin.contains(h) || custom.contains(h) || pushed.contains(h) || remote.contains(h)) return h
        }
        return null
    }

    fun isBlockedHost(host: String?): Boolean = enabled && host != null && hit(host.lowercase()) != null

    fun allowOnce(url: String) {
        allowed[url] = System.currentTimeMillis() + 15000
    }

    fun shouldBlock(url: String, isMain: Boolean, firstPartyHost: String?): Boolean {
        if (!enabled) return false
        val exp = allowed[url]
        if (exp != null) {
            if (exp > System.currentTimeMillis()) return false
            allowed.remove(url)
        }
        val host = (try { Uri.parse(url).host } catch (_: Exception) { null })?.lowercase() ?: return false
        if (host == "localhost" || Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+\$").matches(host)) return false
        if (host.endsWith("androidplatform.net")) return false
        if (!firstPartyHost.isNullOrEmpty() && registrable(firstPartyHost) == registrable(host)) return false
        var h = hit(host)
        if (h == null && !isMain && pattern.containsMatchIn(url)) h = "pattern"
        if (h == null) return false
        blocked.incrementAndGet()
        byHost.merge(if (h == "pattern") host else h, 1) { a, b -> a + b }
        return true
    }

    fun summary(): JSONObject {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("blocked", blocked.get())
        o.put("popups", popups.get())
        val top = JSONArray()
        byHost.entries.sortedByDescending { it.value }.take(8).forEach { top.put(JSONArray().put(it.key).put(it.value)) }
        o.put("top", top)
        o.put("rules", builtin.size + custom.size + remote.size + pushed.size)
        return o
    }
}
