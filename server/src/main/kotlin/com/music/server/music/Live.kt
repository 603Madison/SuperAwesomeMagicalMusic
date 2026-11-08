package com.music.server.music

import com.music.player.sources.siriusxm.SiriusXmClient
import com.music.player.sources.siriusxm.SiriusXmParser
import com.music.server.ApiError
import com.music.server.util.SecretBox
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Live radio through the server: HLS playlists are fetched and rewritten so every playlist,
 * segment and key is fetched through the server too (SiriusXM's segments can't be fetched from a
 * browser, and its keys need the signed-in session), and Icecast radio streams are passed on with
 * their song titles taken out and kept for the player to show.
 *
 * Rewritten addresses are signed, so the proxy only fetches what a playlist named.
 */
class LiveProxy(http: OkHttpClient, private val box: SecretBox, private val clock: () -> Long = System::currentTimeMillis) {
    // Live streams never end, so no read timeout of the usual kind; a stalled one is noticed by the player.
    private val http = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
    private val titles = ConcurrentHashMap<String, Pair<String, Long>>()

    /** The server's address for an upstream playlist or segment of [scope] (a source id). */
    fun proxied(scope: String, kind: Char, url: String): String =
        "/api/live/hls/${scope.encodeURLParameter()}/$kind?u=${url.encodeURLParameter()}&s=${box.sign("hls|$scope|$kind|$url")}"

    fun verify(scope: String, kind: Char, url: String, signature: String) = box.verifySignature("hls|$scope|$kind|$url", signature)

    /**
     * [playlist] (fetched from [base]) with its addresses pointing back through the server; SiriusXM's
     * `sxmkey://` keys go to [keyPath] for the source.
     */
    fun rewrite(playlist: String, base: String, scope: String, keyPath: ((String) -> String)? = null): String {
        val baseUrl = base.toHttpUrlOrNull()
        fun absolute(uri: String): String? = when {
            uri.startsWith("http://") || uri.startsWith("https://") -> uri
            else -> baseUrl?.resolve(uri)?.toString()
        }
        val master = playlist.contains("#EXT-X-STREAM-INF")
        return playlist.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> line
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> null
                line.startsWith("#") && line.contains("URI=\"") -> URI_ATTRIBUTE.replace(line) { match ->
                    val uri = match.groupValues[1]
                    val replaced = when {
                        uri.startsWith("${SiriusXmParser.KEY_SCHEME}://") && keyPath != null -> keyPath(uri.substringAfter("://"))
                        line.startsWith("#EXT-X-MEDIA") -> absolute(uri)?.let { proxied(scope, 'p', it) }
                        else -> absolute(uri)?.let { proxied(scope, 's', it) }
                    } ?: uri
                    "URI=\"$replaced\""
                }
                line.startsWith("#") -> line
                else -> absolute(line)?.let { proxied(scope, if (master || line.substringBefore('?').endsWith(".m3u8")) 'p' else 's', it) } ?: line
            }
        }.joinToString("\n")
    }

    /** Fetches an upstream playlist, returning its text and final address (after redirects). */
    suspend fun fetchPlaylist(url: String, headers: Map<String, String> = emptyMap()): Pair<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ApiError.upstream("The stream answered HTTP ${response.code}")
                val body = response.body?.byteStream()?.use { it.readNBytes(MAX_PLAYLIST_BYTES) } ?: ByteArray(0)
                String(body) to response.request.url.toString()
            }
        } catch (e: IOException) {
            throw ApiError.upstream("Couldn't reach the stream: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun respondPlaylist(call: ApplicationCall, text: String) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(text, ContentType("application", "vnd.apple.mpegurl"))
    }

    /** Passes a segment (or any file) through, with byte ranges. */
    suspend fun respondFile(call: ApplicationCall, url: String, headers: Map<String, String> = emptyMap()) {
        val range = call.request.header(HttpHeaders.Range)
        val response = open(url, headers + listOfNotNull(range?.let { HttpHeaders.Range to it }))
        var handedOff = false
        try {
            if (response.code != 200 && response.code != 206) throw ApiError.upstream("The stream answered HTTP ${response.code}")
            val body = response.body ?: throw ApiError.upstream("The stream sent nothing")
            response.header(HttpHeaders.ContentRange)?.let { call.response.header(HttpHeaders.ContentRange, it) }
            response.header(HttpHeaders.AcceptRanges)?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
            val type = response.header(HttpHeaders.ContentType)?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?.takeIf { it.contentType in setOf("audio", "video", "application") && !it.match(ContentType.Text.Html) }
                ?: ContentType.Application.OctetStream
            handedOff = true
            call.respondOutputStream(type, HttpStatusCode.fromValue(response.code), body.contentLength().takeIf { it >= 0 }) {
                try {
                    body.byteStream().use { it.copyTo(this, BUFFER) }
                } catch (e: IOException) {
                    // The player moved on.
                } finally {
                    response.close()
                }
            }
        } finally {
            if (!handedOff) response.close()
        }
    }

    /**
     * Passes an Icecast/Shoutcast stream on without its in-band song titles (players in browsers
     * can't read them), keeping the latest title for [titleKey].
     */
    suspend fun respondRadio(call: ApplicationCall, url: String, titleKey: String) {
        val response = open(url, mapOf("Icy-MetaData" to "1"))
        var handedOff = false
        try {
            if (!response.isSuccessful) throw ApiError.upstream("The station answered HTTP ${response.code}")
            val body = response.body ?: throw ApiError.upstream("The station sent nothing")
            val metaInterval = response.header("icy-metaint")?.toIntOrNull()?.takeIf { it in 1..1_000_000 }
            val type = when (val raw = response.header(HttpHeaders.ContentType)?.substringBefore(';')?.trim()?.lowercase()) {
                "audio/aacp", "audio/x-aac" -> ContentType("audio", "aac")
                null, "", "text/html" -> ContentType("audio", "mpeg")
                else -> runCatching { ContentType.parse(raw) }.getOrDefault(ContentType("audio", "mpeg"))
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            handedOff = true
            call.respondOutputStream(type, HttpStatusCode.OK) {
                try {
                    body.byteStream().use { input ->
                        if (metaInterval == null) input.copyTo(this, BUFFER) else copyWithoutMetadata(input, this, metaInterval) { title ->
                            titles[titleKey] = title to clock()
                        }
                    }
                } catch (e: IOException) {
                    // The listener tuned away.
                } finally {
                    response.close()
                }
            }
        } finally {
            if (!handedOff) response.close()
        }
    }

    /** The song title a radio station sent most recently (in the last hour), if any. */
    fun radioTitle(titleKey: String): String? = titles[titleKey]?.takeIf { clock() - it.second < 3_600_000 }?.first

    private suspend fun open(url: String, headers: Map<String, String>): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError.upstream("Couldn't reach the stream: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            throw ApiError.upstream("That stream's address isn't usable")
        }
    }

    companion object {
        private const val BUFFER = 32 * 1024
        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
        private val URI_ATTRIBUTE = Regex("URI=\"([^\"]*)\"")
        private val STREAM_TITLE = Regex("StreamTitle='(.*?)';", RegexOption.DOT_MATCHES_ALL)

        /** Copies an ICY stream: [interval] bytes of audio, then a length byte and that many 16-byte blocks of metadata. */
        fun copyWithoutMetadata(input: InputStream, output: java.io.OutputStream, interval: Int, onTitle: (String) -> Unit) {
            val buffer = ByteArray(interval.coerceAtMost(BUFFER))
            while (true) {
                var left = interval
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(left, buffer.size))
                    if (read < 0) return
                    output.write(buffer, 0, read)
                    left -= read
                }
                output.flush()
                val length = input.read()
                if (length < 0) return
                if (length > 0) {
                    val meta = input.readNBytes(length * 16)
                    if (meta.size < length * 16) return
                    val text = String(meta, Charsets.UTF_8).trimEnd('\u0000')
                    STREAM_TITLE.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(onTitle)
                }
            }
        }

        /** Headers SiriusXM's web player sends for playlists and segments. */
        val SXM_HEADERS = mapOf("User-Agent" to SiriusXmClient.USER_AGENT)
    }
}
