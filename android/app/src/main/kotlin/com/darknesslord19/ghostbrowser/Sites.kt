package com.darknesslord19.ghostbrowser

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Parsed(val name: String, val sites: List<JSONObject>, val blockHosts: List<String>)
data class Fetched(val parsed: Parsed, val resolved: String)

fun httpGet(url: String, timeoutMs: Int = 9000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = timeoutMs
    c.readTimeout = timeoutMs
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "GhostBrowser/1.0")
    c.setRequestProperty("Accept", "application/json,text/plain,*/*")
    try {
        if (c.responseCode !in 200..299) throw RuntimeException("HTTP " + c.responseCode)
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        c.disconnect()
    }
}

object Sites {
    /** GitHub linkini ham JSON adaylarına çevirir. */
    fun normalize(input: String): List<String> {
        val raw = input.trim()
        val u = try { Uri.parse(raw) } catch (_: Exception) { return emptyList() }
        val host = (u.host ?: return emptyList()).lowercase()
        val scheme = u.scheme ?: return emptyList()
        if (scheme != "http" && scheme != "https") return emptyList()
        if (host == "raw.githubusercontent.com" || host == "gist.githubusercontent.com") return listOf(raw)
        val p = u.pathSegments.filter { it.isNotEmpty() }
        if (host == "gist.github.com") {
            return if (p.size >= 2) listOf("https://gist.githubusercontent.com/${p[0]}/${p[1]}/raw") else emptyList()
        }
        if (host == "github.com" || host == "www.github.com") {
            if (p.size < 2) return emptyList()
            val owner = p[0]
            val repo = p[1].removeSuffix(".git")
            val base = "https://raw.githubusercontent.com/$owner/$repo"
            if (p.size >= 5 && (p[2] == "blob" || p[2] == "raw")) return listOf(base + "/" + p.drop(3).joinToString("/"))
            if (p.size >= 4 && p[2] == "tree") {
                val dir = p.drop(4).joinToString("/")
                return listOf("$base/${p[3]}/" + (if (dir.isNotEmpty()) "$dir/" else "") + "sites.json")
            }
            return listOf("$base/main/sites.json", "$base/master/sites.json")
        }
        return listOf(raw)
    }

    fun clean(o: JSONObject?, category: String? = null): JSONObject? {
        if (o == null) return null
        var url = (o.optString("url").ifEmpty { o.optString("link") }).trim()
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            if (Regex("^[\\w-]+(\\.[\\w-]+)+").containsMatchIn(url)) url = "https://$url" else return null
        }
        val host = try { Uri.parse(url).host ?: return null } catch (_: Exception) { return null }
        val out = JSONObject()
        out.put("name", (o.optString("name").ifEmpty { o.optString("title").ifEmpty { host.removePrefix("www.") } }).take(60))
        out.put("url", url)
        out.put("category", (o.optString("category").ifEmpty { category ?: "" }).take(30))
        val icon = o.optString("icon")
        out.put("icon", if (icon.startsWith("http")) icon else "")
        out.put("description", o.optString("description").take(140))
        return out
    }

    fun parse(text: String): Parsed {
        val t = text.trim()
        var name = ""
        val list = ArrayList<JSONObject>()
        val block = ArrayList<String>()
        if (t.startsWith("[")) {
            val a = JSONArray(t)
            for (i in 0 until a.length()) clean(a.optJSONObject(i))?.let { list.add(it) }
        } else {
            val o = JSONObject(t)
            name = o.optString("name").ifEmpty { o.optString("title") }
            o.optJSONArray("sites")?.let { a -> for (i in 0 until a.length()) clean(a.optJSONObject(i))?.let { list.add(it) } }
            o.optJSONArray("categories")?.let { cs ->
                for (i in 0 until cs.length()) {
                    val c = cs.optJSONObject(i) ?: continue
                    val ss = c.optJSONArray("sites") ?: continue
                    for (j in 0 until ss.length()) clean(ss.optJSONObject(j), c.optString("name"))?.let { list.add(it) }
                }
            }
            o.optJSONArray("blockHosts")?.let { a -> for (i in 0 until a.length()) block.add(a.optString(i)) }
        }
        return Parsed(name, list, block)
    }

    fun fetch(input: String): Fetched {
        val cands = normalize(input)
        if (cands.isEmpty()) throw RuntimeException("Geçersiz bağlantı")
        var last: Exception? = null
        for (u in cands) {
            try {
                val sep = if (u.contains("?")) "&" else "?"
                return Fetched(parse(httpGet(u + sep + "_=" + System.currentTimeMillis() / 60000)), u)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: RuntimeException("Alınamadı")
    }
}
