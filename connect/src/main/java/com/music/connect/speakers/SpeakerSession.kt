package com.music.connect.speakers

import android.os.SystemClock
import android.util.Log
import com.music.connect.net.ApiTrack
import com.music.connect.net.ConnectClient
import com.music.connect.net.ConnectCommand
import com.music.connect.net.ConnectDevice
import com.music.connect.net.PlayState
import com.music.connect.net.ServerApi
import com.music.connect.playback.MediaItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.roundToInt

/** What a speaker is playing from this app, for the notification. */
data class SpeakerNow(val speaker: String, val track: ApiTrack, val playing: Boolean)

/** A song (or a station) for a speaker: its address on the phone, and what to show while it plays. */
data class SpeakerMedia(
    val url: String,
    /** The kind of audio at [url] (audio/mpeg, audio/flac…). */
    val mime: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** The cover, at an address the speaker can fetch. */
    val artUrl: String? = null,
    val durationMs: Long? = null,
    /** A station: no end, no seeking. */
    val live: Boolean = false,
)

/** The kinds of audio a speaker plays, so the server sends it one of them (converting the rest). */
data class SpeakerFormats(
    /** What it plays as it is, as the server names them (flac, mp3, aac…). */
    val codecs: List<String>,
    /** Lossless audio above this many kbps is more than it plays (Sonos stops at 24-bit/48 kHz). */
    val maxLosslessKbps: Int? = null,
)

/** How a session talks to the speaker (a MusicCast, a Sonos group, or a stand-in in tests). */
interface SpeakerControl {
    val info: SpeakerInfo
    val formats: SpeakerFormats get() = SpeakerFormats(SpeakerSession.CODECS)
    suspend fun status(): ZoneStatus
    suspend fun setPower(on: Boolean)
    /** In the speaker's own steps, up to [ZoneStatus.maxVolume]. */
    suspend fun setVolume(level: Int)
    /** Puts [media] on in place of whatever it was playing; [play] starts it. */
    suspend fun load(media: SpeakerMedia)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    /** Jumps to [positionMs] in what it's playing; false if it can't (the song then starts again from there). */
    suspend fun seek(positionMs: Long): Boolean = false
    suspend fun transport(): Transport

    /**
     * Takes a new name for the same speaker (a Sonos joining or leaving a group); false if it
     * needs starting afresh instead.
     */
    fun update(info: SpeakerInfo): Boolean = false
}

/**
 * One speaker, as one of the person's devices: the queue handed to it plays here, a song at a
 * time (each fetched from the server through the phone), and it does what the person's other
 * devices ask. The speaker plays on its own; this keeps an eye on it to move to the next song,
 * and notices when someone takes it over (its own app, the TV, another input).
 */
class SpeakerSession(
    private val speaker: SpeakerControl,
    private val hub: ConnectClient,
    private val api: ServerApi,
    private val relay: StreamRelay,
    private val server: () -> String,
    private val kbps: () -> Int,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** How long after being given a song the speaker may still be getting going. */
    private val settleMs: Long = SETTLE_MS,
) {
    private val lock = Mutex()
    private var job: Job? = null

    private var queue: List<ApiTrack> = emptyList()
    private var index = -1
    /** Where in the song the current address starts (seeking starts the song again from there). */
    private var offsetMs = 0L
    /** The address the speaker was given, if it's playing one of ours. */
    private var currentUrl: String? = null
    /** The speaker can seek in it by itself (the original file, which answers byte ranges). */
    private var seekable = false
    /** It's meant to be playing (as opposed to paused, finished or taken over). */
    private var wanted = false
    private var startedAt = 0L
    /** Pause didn't hold on this model, so it was stopped: playing again starts from here. */
    private var resumeAt: Long? = null
    private var positionMs = 0L
    private var positionAt = 0L
    private var playing = false
    private var volume = 0.5
    private var maxVolume = 100
    private var failures = 0
    private var unreachable = 0

    private val _problem = MutableStateFlow<String?>(null)
    /** The last thing that went wrong with it (shown in Settings, for finding out why). */
    val problem: StateFlow<String?> = _problem

    /** Told when something it was asked to do failed (the app shows it). */
    var onProblem: (String) -> Unit = {}

    private val _now = MutableStateFlow<SpeakerNow?>(null)
    /** What it's playing from this app (null when nothing, or someone else took it). */
    val now: StateFlow<SpeakerNow?> = _now

    /** It stopped answering for a while (switched off at the wall, or the phone left home). */
    val gone: Boolean get() = unreachable >= GONE_AFTER

    val id: String get() = speaker.info.deviceId
    val info: SpeakerInfo get() = speaker.info

    /** The person's devices, as the server last listed them to this speaker. */
    val devices: List<ConnectDevice> get() = hub.devices.value

    /**
     * The same speaker under a new name (a Sonos group gained or lost a room): renamed among the
     * devices, carrying on with what it plays. False if it has to be started afresh.
     */
    fun update(info: SpeakerInfo): Boolean {
        if (!speaker.update(info)) return false
        hub.rename(info.name, info.detail)
        _now.value = _now.value?.copy(speaker = info.name)
        return true
    }

    fun start() {
        if (job != null) return
        hub.start()
        job = scope.launch {
            launch { hub.commands.collect { command -> lock.withLock { runCatching { obey(command) }.onFailure { complain(command.action, it) } } } }
            while (isActive) {
                lock.withLock { runCatching { check() }.onFailure { unreachable++ } }
                delay(if (wanted) BUSY_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        hub.stop()
        _now.value = null
    }

    /** Pauses it (from the notification). */
    suspend fun pause() = lock.withLock { runCatching { pauseNow() } }

    // ---- what the other devices ask ----

    private suspend fun obey(command: ConnectCommand) {
        when (command.action) {
            "take" -> command.state?.takeIf { it.queue.isNotEmpty() }?.let { state ->
                queue = state.queue
                playAt(state.index.coerceIn(0, queue.size - 1), if (state.live) 0 else state.positionMs.coerceAtLeast(0))
            }
            "play" -> resume()
            "pause" -> pauseNow()
            "next" -> if (index + 1 < queue.size) playAt(index + 1, 0)
            "previous" -> when {
                position() > RESTART_WITHIN_MS || index == 0 -> playAt(index, 0)
                index > 0 -> playAt(index - 1, 0)
            }
            "seek" -> command.positionMs?.takeIf { queue.getOrNull(index)?.isLive == false }?.let { seekTo(it.coerceAtLeast(0)) }
            "volume" -> command.volume?.let { level ->
                speaker.setVolume((level.coerceIn(0.0, 1.0) * maxVolume).roundToInt())
                volume = level.coerceIn(0.0, 1.0)
                report(force = true)
            }
        }
    }

    private suspend fun resume() {
        if (queue.isEmpty()) return
        val transport = runCatching { speaker.transport() }.getOrNull()
        if (resumeAt == null && transport?.state == "PAUSED_PLAYBACK" && isOurs(transport.uri)) {
            speaker.play()
            wanted = true
            playing = true
            startedAt = clock()
            positionAt = clock()
            report(force = true)
        } else {
            val at = index.coerceAtLeast(0)
            playAt(at, if (queue.getOrNull(at)?.isLive == true) 0 else resumeAt ?: position())
        }
    }

    /**
     * Jumps to [ms] in the song: the speaker does it itself when it can (in the original file),
     * otherwise the song starts again from there.
     */
    private suspend fun seekTo(ms: Long) {
        if (seekable && currentUrl != null && resumeAt == null && runCatching { speaker.seek(ms) }.getOrDefault(false)) {
            positionMs = ms
            positionAt = clock()
            startedAt = clock()
            report(force = true)
            return
        }
        playAt(index, ms)
    }

    private suspend fun pauseNow() {
        if (!wanted) return
        val at = position()
        wanted = false
        playing = false
        runCatching { speaker.pause() }
        // Not every model pauses a stream: if it's still going, it's stopped, and starts again from here.
        delay(PAUSE_CHECK_MS)
        val transport = runCatching { speaker.transport() }.getOrNull()
        if (transport?.state != "PAUSED_PLAYBACK") {
            runCatching { speaker.stop() }
            resumeAt = at
        }
        positionMs = at
        positionAt = clock()
        report(force = true)
    }

    /** Plays song [at] of the queue, from [startMs] into it. */
    private suspend fun playAt(at: Int, startMs: Long) {
        val track = queue.getOrNull(at) ?: return
        index = at
        offsetMs = startMs
        resumeAt = null
        wanted = true
        startedAt = clock()
        positionMs = startMs
        positionAt = clock()
        playing = true
        report(force = true)

        val status = runCatching { speaker.status() }.getOrNull()
        if (status != null && !status.on) {
            speaker.setPower(true)
            delay(POWER_ON_MS)
        }
        val stream = streamOf(track, startMs)
        val path = relay.publish(stream.url)
        val url = relay.addressFor(speaker.info.host, path) ?: throw SpeakerException("This phone isn't on ${speaker.info.name}'s network")
        val art = track.artworkUrl?.let { MediaItems.artworkUri(server(), it, 600)?.toString() }?.let { relay.addressFor(speaker.info.host, relay.publish(it)) }
        speaker.load(SpeakerMedia(url, stream.mime, track.title, track.artist, track.album, art, track.durationMs?.minus(startMs), live = track.isLive))
        speaker.play()
        currentUrl = url
        seekable = stream.seekable
        report(force = true)
    }

    /** Where a song comes from on the server, the kind of audio it is, and whether the speaker can seek in it. */
    private data class Stream(val url: String, val mime: String, val seekable: Boolean = false)

    /** The server address of [track] for the speaker, in a kind of audio it plays. */
    private suspend fun streamOf(track: ApiTrack, startMs: Long): Stream {
        MediaItems.livePath(track)?.let { (path, hls) ->
            if (hls) throw SpeakerException("Live SiriusXM channels can't play on speakers yet")
            return Stream(server() + path, if (track.isStationTrack) "audio/mp4" else "audio/mpeg")
        }
        val formats = speaker.formats
        // Converted, it's FLAC or MP3: not every speaker gets on with a raw AAC stream.
        val plain = formats.codecs - "aac"
        var resolved = api.resolve(track, kbps(), thorough = true, codecs = if (startMs > 0) plain else formats.codecs)
        val tooRich = formats.maxLosslessKbps?.let { max -> !resolved.transcoded && resolved.extension.lowercase() in LOSSLESS && (resolved.kbps ?: 0) > max } == true
        if (tooRich) {
            // Hi-res is more than it plays: MP3 instead (FLAC converted keeps the hi-res).
            resolved = api.resolve(track, kbps(), thorough = true, codecs = listOf("mp3"))
        } else if (resolved.transcoded && resolved.extension.lowercase() == "aac") {
            resolved = api.resolve(track, kbps(), thorough = true, codecs = plain)
        }
        val start = if (startMs > 0) "&start=" + String.format(Locale.ROOT, "%.1f", startMs / 1000.0) else ""
        val extension = resolved.extension.lowercase()
        // Starting part-way, the server converts: lossless to FLAC, the rest to MP3.
        val mime = when {
            startMs > 0 -> if (extension in LOSSLESS && !tooRich) "audio/flac" else "audio/mpeg"
            else -> mimeOf(extension)
        }
        return Stream(api.absolute(resolved.streamUrl) + start, mime, seekable = startMs == 0L && resolved.seekable && !resolved.transcoded)
    }

    // ---- keeping an eye on it ----

    private suspend fun check() {
        val status = speaker.status()
        unreachable = 0
        maxVolume = status.maxVolume
        volume = status.volume.toDouble() / status.maxVolume
        val ours = currentUrl
        if (ours == null) {
            report()
            return
        }
        val transport = speaker.transport()
        val settling = clock() - startedAt < settleMs
        val stillOurs = isOurs(transport.uri) && status.input in OUR_INPUTS
        when {
            !stillOurs && !settling -> {
                // Someone played something else on it (its own app, AirPlay, the TV): it's theirs now.
                if (wanted || playing) Log.i(TAG, "${speaker.info.name} was taken over (${status.input})")
                wanted = false
                playing = false
                currentUrl = null
            }
            transport.state == "PLAYING" -> {
                playing = true
                failures = 0
                positionMs = offsetMs + transport.positionMs
                positionAt = clock()
            }
            transport.state == "PAUSED_PLAYBACK" -> playing = false
            (transport.state == "STOPPED" || transport.state == "NO_MEDIA_PRESENT") && wanted && !settling -> songEnded()
        }
        report()
    }

    /** The speaker stopped by itself: the song finished (or couldn't be played). */
    private suspend fun songEnded() {
        val duration = queue.getOrNull(index)?.durationMs ?: 0
        val nearEnd = duration <= 0 || position() >= duration - END_SLACK_MS
        if (!nearEnd && ++failures >= MAX_FAILURES) {
            // Several songs in a row wouldn't play: stop rather than skip through everything.
            wanted = false
            playing = false
            report(force = true)
            return
        }
        if (index + 1 < queue.size) {
            playAt(index + 1, 0)
        } else {
            wanted = false
            playing = false
            positionMs = 0
            report(force = true)
        }
    }

    /** Whether the speaker's address is the one it was given (by its private path; hosts get rewritten). */
    private fun isOurs(uri: String): Boolean {
        val path = currentUrl?.substringAfter("/s/", "")?.takeIf { it.isNotEmpty() } ?: return false
        return uri.contains("/s/$path")
    }

    private fun position(): Long = positionMs + if (playing) clock() - positionAt else 0

    private var reported: String? = null
    private var reportedAt = 0L

    private fun report(force: Boolean = false) {
        val track = queue.getOrNull(index)?.takeIf { currentUrl != null || wanted }
        val at = position()
        _now.value = track?.let { SpeakerNow(speaker.info.name, it, playing || wanted) }
        val key = "${track?.key}|$index|$playing|${queue.size}|${(volume * 100).roundToInt()}"
        if (!force && key == reported && clock() - reportedAt < REPORT_EVERY_MS) return
        reported = key
        reportedAt = clock()
        hub.report(
            PlayState(
                track = track,
                queue = queue,
                index = index,
                positionMs = at,
                durationMs = track?.durationMs ?: 0,
                playing = playing,
                volume = volume,
                live = track?.isLive == true,
            ),
        )
    }

    private fun complain(action: String, error: Throwable) {
        Log.w(TAG, "${speaker.info.name}: $action failed: ${error.message}")
        val message = "${speaker.info.name}: couldn't ${if (action == "take") "play" else action} (${error.message ?: error.javaClass.simpleName})"
        _problem.value = message
        onProblem(message)
        wanted = false
        playing = false
        report(force = true)
    }

    companion object {
        private const val TAG = "SpeakerSession"
        /** What speakers play reliably; anything else the server converts. */
        val CODECS = listOf("flac", "mp3", "wav")
        private val LOSSLESS = setOf("flac", "wav", "alac", "aiff")

        /** The kind of audio a file with [extension] is, as speakers are told it. */
        fun mimeOf(extension: String): String = when (extension.lowercase()) {
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a", "mp4", "alac" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            else -> "audio/mpeg"
        }
        /** Where MusicCast says it's playing an address it was given (UPnP is "server"). */
        private val OUR_INPUTS = setOf("server", "")
        private const val BUSY_POLL_MS = 1_500L
        private const val IDLE_POLL_MS = 10_000L
        private const val SETTLE_MS = 8_000L
        private const val PAUSE_CHECK_MS = 1_200L
        private const val POWER_ON_MS = 1_500L
        private const val RESTART_WITHIN_MS = 3_000L
        private const val END_SLACK_MS = 15_000L
        private const val MAX_FAILURES = 3
        private const val GONE_AFTER = 6
        private const val REPORT_EVERY_MS = 15_000L
    }
}
