package com.music.connect.speakers

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hands songs to speakers on the home network. Speakers play plain-HTTP addresses on the local
 * network best (not the server's HTTPS address, and they can't sign in), so the phone serves
 * each song at an address only the speaker is given, fetching it from the server as it goes.
 */
class StreamRelay(http: OkHttpClient) {
    private val http = http.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val routes = ConcurrentHashMap<String, Long>()
    private val targets = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()
    private val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "speaker-relay").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        if (server != null) return
        val socket = ServerSocket(0)
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { serve(client) }.onFailure { Log.d(TAG, "Relay request ended: ${it.message}") } }
            }
        }, "speaker-relay-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
        routes.clear()
        targets.clear()
    }

    /** Serves [url] (the server's, signed in by the HTTP client) at a path of its own; returns the path. */
    fun publish(url: String): String {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        targets[token] = url
        routes[token] = System.currentTimeMillis()
        // Keep the recent ones: a speaker may come back for a song (or its art) a while later.
        if (routes.size > MAX_ROUTES) routes.entries.sortedBy { it.value }.take(routes.size - MAX_ROUTES).forEach { (old, _) ->
            routes.remove(old)
            targets.remove(old)
        }
        return "/s/$token"
    }

    /** The full address for [path] as the speaker at [host] reaches this phone. */
    fun addressFor(host: String, path: String): String? {
        val local = localAddressFor(host) ?: return null
        return "http://${MusicCastSpeaker.hostForUrl(local)}:$port$path"
    }

    private fun serve(client: Socket) = client.use { socket ->
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        val headers = lines.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }.toMap()
        val out = socket.getOutputStream()
        val token = path.removePrefix("/s/").substringBefore('?')
        val target = targets[token]
        if ((method != "GET" && method != "HEAD") || !path.startsWith("/s/") || target == null) {
            respond(out, 404, "Not Found", emptyMap())
            return
        }
        val request = Request.Builder().url(target).apply { headers["range"]?.let { header("Range", it) } }.build()
        http.newCall(request).execute().use { upstream ->
            val body = upstream.body
            val reply = linkedMapOf<String, String>()
            reply["Content-Type"] = upstream.header("Content-Type") ?: "audio/mpeg"
            upstream.header("Content-Length")?.let { reply["Content-Length"] = it }
            upstream.header("Content-Range")?.let { reply["Content-Range"] = it }
            val ranges = upstream.header("Accept-Ranges") == "bytes" && upstream.header("Content-Length") != null
            reply["Accept-Ranges"] = if (ranges) "bytes" else "none"
            reply["transferMode.dlna.org"] = "Streaming"
            reply["contentFeatures.dlna.org"] = "DLNA.ORG_OP=${if (ranges) "01" else "00"};DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            respond(out, upstream.code, upstream.message.ifBlank { "OK" }, reply)
            if (method == "GET" && upstream.isSuccessful && body != null) copy(body.byteStream(), out)
        }
    }

    private fun respond(out: OutputStream, code: Int, message: String, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            // No length means the song ends when the connection does, which every speaker understands.
            append("Connection: close\r\n")
            if (code == 404) append("Content-Length: 0\r\n")
            append("\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = from.read(buffer)
            if (read < 0) break
            to.write(buffer, 0, read)
        }
        to.flush()
    }

    /** The request line and headers, up to the blank line (null if the speaker hung up). */
    private fun readHead(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (bytes.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            bytes.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == end.size) return bytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
        }
        return null
    }

    companion object {
        private const val TAG = "StreamRelay"
        private const val MAX_ROUTES = 200
        private const val MAX_HEAD = 16 * 1024

        /** This phone's address on the network that reaches [host] (no packet is sent). */
        fun localAddressFor(host: String): String? = runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(host), 9)
                socket.localAddress?.takeUnless { it.isAnyLocalAddress }?.hostAddress?.substringBefore('%')
            }
        }.getOrNull()
    }
}
