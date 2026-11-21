package com.darknesslord19.ghostbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

class Store(ctx: Context, isTv: Boolean) {
    private val prefs = ctx.getSharedPreferences("gb", Context.MODE_PRIVATE)
    val settings: JSONObject = JSONObject()

    init {
        val first = prefs.getString("settings", null) == null
        settings.put("searchEngine", "https://www.google.com/search?q=%s")
        settings.put("homepage", "ghost://newtab")
        settings.put("adblock", true)
        settings.put("popupBlock", true)
        settings.put("askPlayer", true)
        settings.put("cosmetic", true)
        settings.put("filterListUrl", "https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/master/Filters/filter.txt")
        settings.put("customBlockHosts", JSONArray())
        settings.put("sitesSources", JSONArray().put("https://github.com/darknesslord19/Ghost-Browser"))
        settings.put("localSites", JSONArray())
        settings.put("tvMode", isTv && first)
        settings.put("remoteEnabled", true)
        settings.put("remotePort", 8421)
        settings.put("remoteToken", "")
        settings.put("cursorSpeed", 1.0)
        try {
            val saved = prefs.getString("settings", null)
            if (saved != null) {
                val o = JSONObject(saved)
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    settings.put(k, o.get(k))
                }
            }
        } catch (_: Exception) {
        }
        if (settings.optString("remoteToken").isEmpty()) {
            val b = ByteArray(6)
            SecureRandom().nextBytes(b)
            settings.put("remoteToken", b.joinToString("") { "%02x".format(it) })
        }
        save()
    }

    fun save() {
        prefs.edit().putString("settings", settings.toString()).apply()
    }

    fun str(k: String): String = settings.optString(k, "")
    fun bool(k: String): Boolean = settings.optBoolean(k, false)
    fun int(k: String): Int = settings.optInt(k, 0)
    fun dbl(k: String): Double = settings.optDouble(k, 1.0)
    fun arr(k: String): JSONArray = settings.optJSONArray(k) ?: JSONArray()

    fun newToken() {
        val b = ByteArray(6)
        SecureRandom().nextBytes(b)
        settings.put("remoteToken", b.joinToString("") { "%02x".format(it) })
        save()
    }

    /** Sayfalardan gelen ayar yamasını beyaz liste ile uygular. */
    fun patch(p: JSONObject) {
        val it = p.keys()
        while (it.hasNext()) {
            val k = it.next()
            when (k) {
                "searchEngine", "homepage", "filterListUrl" -> settings.put(k, p.optString(k).take(500))
                "adblock", "popupBlock", "cosmetic", "tvMode", "remoteEnabled", "askPlayer" -> settings.put(k, p.optBoolean(k))
                "remotePort" -> settings.put(k, p.optDouble(k, 8421.0).toInt().coerceIn(1024, 65000))
                "cursorSpeed" -> settings.put(k, p.optDouble(k, 1.0).coerceIn(0.3, 4.0))
                "customBlockHosts" -> {
                    val a = JSONArray()
                    val src = p.optJSONArray(k) ?: JSONArray()
                    for (i in 0 until minOf(src.length(), 2000)) a.put(src.optString(i))
                    settings.put(k, a)
                }
            }
        }
        save()
    }
}

class History(ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")
    private val list = ArrayList<JSONObject>()
    private val map = HashMap<String, JSONObject>()
    private val handler = Handler(Looper.getMainLooper())
    private val saver = Runnable { save() }

    init {
        try {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list.add(o)
                map[o.getString("url")] = o
            }
        } catch (_: Exception) {
        }
    }

    private fun schedule() {
        handler.removeCallbacks(saver)
        handler.postDelayed(saver, 1500)
    }

    @Synchronized
    private fun save() {
        try {
            val a = JSONArray()
            for (o in list) a.put(o)
            file.writeText(a.toString())
        } catch (_: Exception) {
        }
    }

    fun add(url: String, title: String?) {
        if (!url.startsWith("http")) return
        var h = map[url]
        if (h == null) {
            h = JSONObject()
            h.put("url", url)
            h.put("title", title ?: url)
            h.put("time", System.currentTimeMillis())
            h.put("visits", 0)
            map[url] = h
            list.add(h)
        }
        h.put("visits", h.optInt("visits") + 1)
        h.put("time", System.currentTimeMillis())
        if (!title.isNullOrEmpty()) h.put("title", title)
        if (list.size > 5000) {
            list.sortByDescending { it.optLong("time") }
            while (list.size > 4000) {
                val x = list.removeAt(list.size - 1)
                map.remove(x.optString("url"))
            }
        }
        schedule()
    }

    fun setTitle(url: String, title: String?) {
        val h = map[url] ?: return
        if (!title.isNullOrEmpty()) {
            h.put("title", title)
            schedule()
        }
    }

    fun recent(limit: Int, q: String = ""): JSONArray {
        val ql = q.lowercase()
        val out = JSONArray()
        list.filter { ql.isEmpty() || it.optString("url").lowercase().contains(ql) || it.optString("title").lowercase().contains(ql) }
            .sortedByDescending { it.optLong("time") }
            .take(limit)
            .forEach { out.put(it) }
        return out
    }

    fun top(limit: Int): JSONArray {
        val out = JSONArray()
        list.sortedWith(compareByDescending<JSONObject> { it.optInt("visits") }.thenByDescending { it.optLong("time") })
            .take(limit).forEach { out.put(it) }
        return out
    }

    fun remove(url: String) {
        val h = map.remove(url) ?: return
        list.remove(h)
        schedule()
    }

    fun clear() {
        list.clear()
        map.clear()
        save()
    }
}
