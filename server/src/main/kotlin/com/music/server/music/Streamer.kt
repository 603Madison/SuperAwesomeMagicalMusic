package com.music.server.music

import com.music.player.CoreLog
import com.music.player.quality.StreamQuality
import com.music.player.sources.SourceStream
import com.music.server.ApiError
import com.music.server.util.Random
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A format the server can transcode to. */
enum class OutputFormat(val codec: String, val contentType: String, val extension: String, private val encoder: List<String>, private val muxer: String) {
    OPUS("opus", "audio/ogg", "opus", listOf("-c:a", "libopus", "-vbr", "on"), "ogg"),
    AAC("aac", "audio/aac", "aac", listOf("-c:a", "aac"), "adts"),
    MP3("mp3", "audio/mpeg", "mp3", listOf("-c:a", "libmp3lame"), "mp3"),
    FLAC("flac", "audio/flac", "flac", listOf("-c:a", "flac"), "flac"),
    ;

    fun args(kbps: Int?): List<String> = encoder + (if (this != FLAC && kbps != null) listOf("-b:a", "${kbps}k") else emptyList()) + listOf("-f", muxer)
}

/** How a stream reaches the player: as it is, or transcoded to [format] at [kbps]. */
data class StreamPlan(val format: OutputFormat?, val kbps: Int?) {
    val transcoded: Boolean get() = format != null
}

/**
 * Sends songs to players. A stream the player can play, within the bitrate cap, goes through
 * untouched, with seeking passed on to the service. Anything else (over the cap, a codec the
 * browser can't play, or a seek into a stream that can't seek) is transcoded with ffmpeg.
 *
 * ffmpeg reads the source through [relay], an address on the server itself, so the service's
 * address and credentials never appear on its command line.
 */
class Streamer(
    private val http: OkHttpClient,
    private val ffmpeg: String,
    maxTranscodes: Int,
    /** The server's own address for ffmpeg to read from, e.g. http://127.0.0.1:8080. */
    private val selfUrl: () -> String,
) {
    private val transcodes = Semaphore(maxTranscodes)
    private val relays = ConcurrentHashMap<String, SourceStream>()

    /** Picks how [stream] should reach a player that can play [codecs] (null: anything common). */
    fun plan(stream: SourceStream, quality: StreamQuality, codecs: Set<String>?, start: Double): StreamPlan {
        val codec = codecOf(stream)
        val cap = (quality as? StreamQuality.Capped)?.kbps
        val overCap = !stream.withinCap || (cap != null && (stream.kbps ?: 0) > cap + CAP_SLACK_KBPS)
        // An unknown codec is converted rather than risked, unless the player didn't say what it plays.
        val playable = codecs == null || (codec != null && codec in codecs)
        // Asked to start part-way (players that can't seek by themselves, like speakers): converted from there.
        val mustSeek = start > 0
        if (!overCap && playable && !mustSeek) return StreamPlan(null, null)

        val lossless = codec in LOSSLESS || (stream.kbps ?: 0) >= LOSSLESS_KBPS
        val can = { c: String -> codecs == null || c in codecs }
        return when {
            cap == null && lossless && can("flac") -> StreamPlan(OutputFormat.FLAC, null)
            can("opus") -> StreamPlan(OutputFormat.OPUS, (cap ?: OPUS_ORIGINAL_KBPS).coerceIn(32, 256))
            can("aac") -> StreamPlan(OutputFormat.AAC, (cap ?: AAC_ORIGINAL_KBPS).coerceIn(32, 320))
            else -> StreamPlan(OutputFormat.MP3, (cap ?: MP3_ORIGINAL_KBPS).coerceIn(32, 320))
        }
    }

    /**
     * Sends [stream] to [call] as [plan] says. [refresh] gets a new address for the song when the
     * kept one has stopped working (streaming services' addresses expire).
     */
    suspend fun respond(call: ApplicationCall, stream: SourceStream, plan: StreamPlan, start: Double, refresh: suspend () -> SourceStream) {
        if (plan.format == null) passThrough(call, stream, refresh) else transcode(call, stream, plan.format, plan.kbps, start)
    }

    /** Serves a relayed stream to ffmpeg (see the class notes). */
    suspend fun relay(call: ApplicationCall, id: String) {
        val stream = relays[id] ?: throw ApiError.notFound()
        passThrough(call, stream, refresh = null)
    }

    /**
     * Fetches the start of [stream] the way a player would and says what happened: how the
     * service answered, how long it took and how fast the audio came (Settings' stream test).
     */
    suspend fun probe(stream: SourceStream, maxBytes: Long = PROBE_BYTES, maxMs: Long = PROBE_MS): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        val host = stream.url.toHttpUrlOrNull()?.host ?: "?"
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        val quick = http.newBuilder().readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS).callTimeout(PROBE_TIMEOUT_S * 2, TimeUnit.SECONDS).build()
        try {
            // Asked for the way a browser asks for a song (from the start, open-ended), then read
            // for a while: a fast start that slows to a trickle shows here.
            open(stream, if (stream.supportsRangeRequests) "bytes=0-" else null, quick).use { response ->
                val answered = ms()
                lines += "$host answered HTTP ${response.code} in $answered ms" +
                    listOfNotNull(
                        response.header(HttpHeaders.ContentType),
                        response.header(HttpHeaders.ContentRange) ?: response.header(HttpHeaders.ContentLength)?.let { "$it bytes" },
                    ).joinToString(", ", prefix = " (", postfix = ")").takeIf { it != " ()" }.orEmpty()
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    body?.string()?.trim()?.take(300)?.takeIf { it.isNotEmpty() }?.let { lines += "It said: $it" }
                    return@use
                }
                val source = body.source()
                val sink = okio.Buffer()
                var read = 0L
                while (read < maxBytes && ms() - answered < maxMs) {
                    val n = source.read(sink, 16_384)
                    if (n < 0) break
                    read += n
                    sink.clear()
                }
                val took = (ms() - answered).coerceAtLeast(1)
                lines += "Read ${read / 1024} KB of audio in $took ms (${read * 1000 / 1024 / took} KB/s; this song needs about ${(stream.kbps ?: 128) / 8} KB/s)"
            }
        } catch (e: Exception) {
            lines += "Failed after ${ms()} ms: ${(e as? ApiError)?.message ?: e.toString()}"
        }
        lines
    }

    private suspend fun passThrough(call: ApplicationCall, stream: SourceStream, refresh: (suspend () -> SourceStream)?) {
        val asked = call.request.header(HttpHeaders.Range)
        // YouTube's servers trickle out audio asked for without a range. Browsers always send one
        // (and YouTube plays in the web player); the phone's player doesn't for a song's start,
        // so the whole song is asked for as a range, and sent on as a plain answer.
        val range = asked ?: "bytes=0-".takeIf { wantsRange(stream) }
        var current = stream
        val started = System.nanoTime()
        var response = open(current, range)
        if (response.code in EXPIRED && refresh != null) {
            CoreLog.w(TAG, "${hostOf(current)} answered HTTP ${response.code} for ${current.description}; asking for a new address")
            response.close()
            current = refresh()
            response = open(current, range)
        }
        val upstream = response
        val waited = (System.nanoTime() - started) / 1_000_000
        if (upstream.code !in 200..299 && upstream.code != 416) {
            CoreLog.w(TAG, "${hostOf(current)} answered HTTP ${upstream.code} for ${current.description} after $waited ms")
        } else if (waited > SLOW_MS) {
            CoreLog.w(TAG, "${hostOf(current)} took $waited ms to start sending ${current.description}")
        }
        var handedOff = false
        try {
            when {
                upstream.code == 416 -> {
                    upstream.header(HttpHeaders.ContentRange)?.let { call.response.header(HttpHeaders.ContentRange, it) }
                    call.respond(HttpStatusCode.RequestedRangeNotSatisfiable, "")
                    return
                }
                upstream.code != 200 && upstream.code != 206 -> throw ApiError.upstream("The source answered HTTP ${upstream.code}")
            }
            val body = upstream.body ?: throw ApiError.upstream("The source sent nothing")
            val contentType = upstream.header(HttpHeaders.ContentType)
                ?.takeIf { it.startsWith("audio/") || it.startsWith("video/") || it == "application/ogg" }
                ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?: contentTypeFor(current.fileExtension)
            // A range the player didn't ask for (see above): the whole song, as a plain answer.
            val whole = asked == null && upstream.code == 206
            call.response.header(HttpHeaders.AcceptRanges, if (current.supportsRangeRequests) "bytes" else "none")
            if (!whole) upstream.header(HttpHeaders.ContentRange)?.let { call.response.header(HttpHeaders.ContentRange, it) }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
            // The body may be written after this returns, so the writer closes the source when done.
            handedOff = true
            call.respondOutputStream(
                contentType = contentType,
                status = if (whole) HttpStatusCode.OK else HttpStatusCode.fromValue(upstream.code),
                contentLength = body.contentLength().takeIf { it >= 0 },
            ) {
                try {
                    body.byteStream().use { input -> input.copyTo(this, BUFFER) }
                } catch (e: IOException) {
                    // The player stopped listening (skipped, seeked, closed the tab): nothing to report.
                } finally {
                    upstream.close()
                }
            }
        } finally {
            if (!handedOff) upstream.close()
        }
    }

    private suspend fun transcode(call: ApplicationCall, stream: SourceStream, format: OutputFormat, kbps: Int?, start: Double) {
        withTimeoutOrNull(QUEUE_WAIT_MS) { transcodes.acquire() } ?: throw ApiError(
            HttpStatusCode.ServiceUnavailable, "The server is busy converting other songs; try again in a moment",
        )
        val id = Random.token()
        relays[id] = stream
        val errors = StringBuilder()
        var process: Process? = null
        val finished = AtomicBoolean(false)
        // Runs once, when the song has been sent (or couldn't be): stops ffmpeg, frees the slot.
        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            process?.let { running ->
                running.destroy()
                val exited = runCatching { running.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
                if (!exited) running.destroyForcibly()
                // 255 is ffmpeg stopped by us, when the player stopped listening.
                if (exited && running.exitValue() !in setOf(0, 143, 255) && errors.isNotBlank()) {
                    CoreLog.w(TAG, "ffmpeg: ${synchronized(errors) { errors.toString().trim() }}")
                }
            }
            relays.remove(id)
            transcodes.release()
        }
        var handedOff = false
        try {
            val command = buildList {
                addAll(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin"))
                val seek = if (start > 0) listOf("-ss", "%.3f".format(java.util.Locale.ROOT, start)) else emptyList()
                if (stream.supportsRangeRequests) {
                    // Jumps straight to the start point with a byte range.
                    addAll(seek)
                    addAll(listOf("-i", "${selfUrl()}/internal/relay/$id"))
                } else {
                    // A source that can't jump ahead is decoded through to the start point (given
                    // after the input); before it, ffmpeg tries byte ranges and loses the stream.
                    addAll(listOf("-seekable", "0", "-i", "${selfUrl()}/internal/relay/$id"))
                    addAll(seek)
                }
                addAll(listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "-1", "-ac", "2"))
                addAll(format.args(kbps))
                add("pipe:1")
            }
            val started = withContext(Dispatchers.IO) { ProcessBuilder(command).start() }
            process = started
            Thread({
                runCatching {
                    started.errorStream.bufferedReader().forEachLine { line -> synchronized(errors) { if (errors.length < 2000) errors.appendLine(line) } }
                }
            }, "ffmpeg-errors").apply { isDaemon = true }.start()
            call.response.header(HttpHeaders.AcceptRanges, "none")
            call.response.header("X-SAMM-Transcoded", "${format.codec}${kbps?.let { " $it kbps" } ?: ""}")
            handedOff = true
            call.respondOutputStream(contentType = ContentType.parse(format.contentType), status = HttpStatusCode.OK) {
                try {
                    started.inputStream.use { it.copyTo(this, BUFFER) }
                } catch (e: IOException) {
                    // The player stopped listening.
                } finally {
                    withContext(Dispatchers.IO) { finish() }
                }
            }
        } finally {
            if (!handedOff) finish()
        }
    }

    private suspend fun open(stream: SourceStream, range: String?, client: OkHttpClient = http): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(stream.url).apply {
            stream.headers.forEach { (name, value) -> header(name, value) }
            if (range != null && stream.supportsRangeRequests) header(HttpHeaders.Range, range)
        }.build()
        try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            CoreLog.w(TAG, "Couldn't reach ${hostOf(stream)} for ${stream.description}: ${e.message ?: e.javaClass.simpleName}")
            throw ApiError.upstream("Couldn't reach the source: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun hostOf(stream: SourceStream): String = stream.url.toHttpUrlOrNull()?.host ?: "The source"

    private fun wantsRange(stream: SourceStream): Boolean =
        stream.supportsRangeRequests && stream.url.toHttpUrlOrNull()?.host?.endsWith(".googlevideo.com") == true

    companion object {
        private const val TAG = "Streamer"
        private const val BUFFER = 64 * 1024
        private const val QUEUE_WAIT_MS = 15_000L
        private const val SLOW_MS = 8_000L
        private const val PROBE_BYTES = 1024 * 1024L
        private const val PROBE_MS = 10_000L
        private const val PROBE_TIMEOUT_S = 20L
        private const val CAP_SLACK_KBPS = 8
        private const val LOSSLESS_KBPS = 1_000
        private const val OPUS_ORIGINAL_KBPS = 192
        private const val AAC_ORIGINAL_KBPS = 256
        private const val MP3_ORIGINAL_KBPS = 320
        private val EXPIRED = setOf(401, 403, 404, 410)
        private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "ape", "wv")

        /** The codecs the server knows about, as the players name them. */
        val KNOWN_CODECS = setOf("flac", "alac", "mp3", "aac", "opus", "vorbis", "wav")

        fun codecOf(stream: SourceStream): String? = stream.codec?.lowercase()?.let { codec ->
            when {
                codec.startsWith("pcm") -> "wav"
                codec == "mp4a" || codec == "he-aac" -> "aac"
                else -> codec
            }
        } ?: when (stream.fileExtension.lowercase()) {
            "flac" -> "flac"
            "mp3" -> "mp3"
            "m4a", "mp4", "aac" -> "aac"
            "webm", "opus" -> "opus"
            "ogg", "oga" -> "vorbis"
            "wav" -> "wav"
            else -> null
        }

        fun contentTypeFor(extension: String): ContentType = when (extension.lowercase()) {
            "flac" -> ContentType("audio", "flac")
            "mp3" -> ContentType("audio", "mpeg")
            "m4a", "mp4" -> ContentType("audio", "mp4")
            "aac" -> ContentType("audio", "aac")
            "webm" -> ContentType("audio", "webm")
            "ogg", "oga", "opus" -> ContentType("audio", "ogg")
            "wav" -> ContentType("audio", "wav")
            else -> ContentType.Application.OctetStream
        }
    }
}
