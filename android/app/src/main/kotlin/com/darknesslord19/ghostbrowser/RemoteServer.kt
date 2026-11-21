package com.darknesslord19.ghostbrowser

import android.os.Handler
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Yerel ağ kumanda sunucusu (telefon sayfası + SSE durum + komut POST). */
class RemoteServer(private val ui: Handler) {
    @Volatile var port = 0
    @Volatile var token = ""
    @Volatile private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val sender = Executors.newSingleThreadExecutor()

    var pageProvider: () -> ByteArray = { ByteArray(0) }
    var sitesProvider: () -> String = { "[]" }
    var stateProvider: () -> String = { "{}" }
    var onCommand: (JSONObject) -> Unit = {}

    fun hasClients() = clients.isNotEmpty()

    fun start(wantedPort: Int, tok: String) {
        stop()
        token = tok
        var p = wantedPort
        var found: ServerSocket? = null
        var tries = 0
        while (found == null) {
            try {
                found = ServerSocket(p)
            } catch (e: BindException) {
                if (tries++ >= 10) return
                p++
            }
        }
        val srv: ServerSocket = found ?: return
        server = srv
        port = p
        Thread {
            while (!srv.isClosed) {
                try {
                    val c = srv.accept()
                    Thread { handle(c) }.apply { isDaemon = true }.start()
                } catch (_: IOException) {
                    break
                }
            }
        }.apply { isDaemon = true }.start()
        Thread {
            while (!srv.isClosed) {
                try {
                    Thread.sleep(15000)
                } catch (_: InterruptedException) {
                }
                send(": ping\n\n")
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        for (c in clients) try { c.close() } catch (_: Exception) {}
        clients.clear()
        port = 0
    }

    private fun send(text: String) {
        if (clients.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        sender.execute {
            for (c in clients) {
                try {
                    synchronized(c) {
                        c.write(bytes)
                        c.flush()
                    }
                } catch (_: Exception) {
                    clients.remove(c)
                    try { c.close() } catch (_: Exception) {}
                }
            }
        }
    }

    fun pushState(json: String) = send("event: state\ndata: $json\n\n")
    fun pushSites(json: String) = send("event: sites\ndata: $json\n\n")

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun isPrivate(a: InetAddress) = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
        (a.address.isNotEmpty() && (a.address[0].toInt() and 0xfe) == 0xfc)

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 415 -> "Unsupported Media Type"; else -> "OK" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun handle(sock: Socket) {
        var keep = false
        try {
            sock.soTimeout = 20000
            if (!isPrivate(sock.inetAddress)) {
                respond(sock.getOutputStream(), 403, "text/plain", "yalnizca yerel ag".toByteArray())
                return
            }
            val ins = sock.getInputStream()
            val out = sock.getOutputStream()
            val reqLine = readLine(ins) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(ins) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val path = target.substringBefore('?')
            val query = HashMap<String, String>()
            if (target.contains('?')) {
                for (kv in target.substringAfter('?').split('&')) {
                    val k = kv.substringBefore('=')
                    val v = if (kv.contains('=')) kv.substringAfter('=') else ""
                    query[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
                }
            }
            val given = query["t"] ?: headers["x-token"] ?: ""
            val authed = MessageDigest.isEqual(given.toByteArray(), token.toByteArray())

            if (path == "/" || path == "/r") {
                if (!authed) {
                    respond(out, 401, "text/html; charset=utf-8", "<meta name=viewport content=\"width=device-width\"><body style=\"font:18px sans-serif;background:#111;color:#eee;padding:24px\">Gecersiz anahtar. QR kodu yeniden tara.</body>".toByteArray())
                } else respond(out, 200, "text/html; charset=utf-8", pageProvider())
                return
            }
            if (path == "/favicon.ico") { respond(out, 204, "text/plain", ByteArray(0)); return }
            if (!authed) { respond(out, 401, "text/plain", "unauthorized".toByteArray()); return }

            if (path == "/events") {
                sock.soTimeout = 0
                val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: keep-alive\r\nX-Accel-Buffering: no\r\n\r\nretry: 1500\n\n"
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.flush()
                clients.add(out)
                keep = true
                try {
                    synchronized(out) {
                        out.write("event: state\ndata: ${stateProvider()}\n\n".toByteArray(Charsets.UTF_8))
                        out.write("event: sites\ndata: ${sitesProvider()}\n\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                } catch (_: Exception) {
                    clients.remove(out)
                    keep = false
                }
                // soketi açık tut: istemci kapanana kadar oku
                if (keep) {
                    try { while (ins.read() >= 0) { /* bekle */ } } catch (_: Exception) {}
                    clients.remove(out)
                    keep = false
                }
                return
            }
            if (path == "/cmd" && method == "POST") {
                if (headers["content-type"]?.contains("application/json") != true) {
                    respond(out, 415, "text/plain", "json bekleniyor".toByteArray())
                    return
                }
                val len = (headers["content-length"] ?: "0").toIntOrNull() ?: 0
                if (len <= 0 || len > 20000) { respond(out, 400, "text/plain", "bad".toByteArray()); return }
                val buf = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = ins.read(buf, read, len - read)
                    if (n < 0) break
                    read += n
                }
                try {
                    val cmd = JSONObject(String(buf, 0, read, Charsets.UTF_8))
                    ui.post { try { onCommand(cmd) } catch (_: Exception) {} }
                    respond(out, 200, "application/json", "{\"ok\":true}".toByteArray())
                } catch (e: Exception) {
                    respond(out, 400, "application/json", "{\"ok\":false,\"error\":\"gecersiz komut\"}".toByteArray())
                }
                return
            }
            respond(out, 404, "text/plain", "yok".toByteArray())
        } catch (_: Exception) {
        } finally {
            if (!keep) try { sock.close() } catch (_: Exception) {}
        }
    }

    companion object {
        fun lanAddresses(): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) out.add(Pair(ni.name, a.hostAddress ?: ""))
                }
            } catch (_: Exception) {
            }
            fun rank(ip: String) = if (ip.startsWith("192.168.")) 0 else if (ip.startsWith("10.")) 1 else if (ip.startsWith("172.")) 2 else 3
            return out.sortedBy { rank(it.second) }
        }
    }
}
