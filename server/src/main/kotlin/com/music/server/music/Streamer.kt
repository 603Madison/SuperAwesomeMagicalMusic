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
        val mustSeek = start > 0 && !stream.supportsRangeRequests
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

    private suspend fun passThrough(call: ApplicationCall, stream: SourceStream, refresh: (suspend () -> SourceStream)?) {
        val range = call.request.header(HttpHeaders.Range)
        var current = stream
        var response = open(current, range)
        if (response.code in EXPIRED && refresh != null) {
            response.close()
            current = refresh()
            response = open(current, range)
        }
        val upstream = response
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
            call.response.header(HttpHeaders.AcceptRanges, if (current.supportsRangeRequests) "bytes" else "none")
            upstream.header(HttpHeaders.ContentRange)?.let { call.response.header(HttpHeaders.ContentRange, it) }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
            // The body may be written after this returns, so the writer closes the source when done.
            handedOff = true
            call.respondOutputStream(
                contentType = contentType,
                status = HttpStatusCode.fromValue(upstream.code),
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

    private suspend fun open(stream: SourceStream, range: String?): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(stream.url).apply {
            stream.headers.forEach { (name, value) -> header(name, value) }
            if (range != null && stream.supportsRangeRequests) header(HttpHeaders.Range, range)
        }.build()
        try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError.upstream("Couldn't reach the source: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "Streamer"
        private const val BUFFER = 64 * 1024
        private const val QUEUE_WAIT_MS = 15_000L
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
