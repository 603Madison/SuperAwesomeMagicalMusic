package com.music.connect.speakers

import android.os.SystemClock
import android.util.Log
import com.music.connect.net.ApiTrack
import com.music.connect.net.ConnectClient
import com.music.connect.net.ConnectCommand
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

/** How a session talks to the speaker (the real one, or a stand-in in tests). */
interface SpeakerControl {
    val info: SpeakerInfo
    suspend fun status(): ZoneStatus
    suspend fun setPower(on: Boolean)
    suspend fun setVolume(level: Int)
    suspend fun setUri(url: String, metadata: String)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun transport(): Transport
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
            "seek" -> command.positionMs?.takeIf { queue.getOrNull(index)?.isLive == false }?.let { playAt(index, it.coerceAtLeast(0)) }
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
            playAt(index.coerceAtLeast(0), resumeAt ?: position())
        }
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
        val (source, mime) = streamOf(track, startMs)
        val path = relay.publish(source)
        val url = relay.addressFor(speaker.info.host, path) ?: throw SpeakerException("This phone isn't on ${speaker.info.name}'s network")
        val art = track.artworkUrl?.let { MediaItems.artworkUri(server(), it, 600)?.toString() }?.let { relay.addressFor(speaker.info.host, relay.publish(it)) }
        // A stop first, so a speaker busy with the last song takes the new one.
        runCatching { speaker.stop() }
        speaker.setUri(url, MusicCastSpeaker.didl(url, mime, track.title, track.artist, track.album, art, track.durationMs?.minus(startMs)))
        speaker.play()
        currentUrl = url
        report(force = true)
    }

    /** The server address of [track] for the speaker, and the kind of audio it is. */
    private suspend fun streamOf(track: ApiTrack, startMs: Long): Pair<String, String> {
        MediaItems.livePath(track)?.let { (path, hls) ->
            if (hls) throw SpeakerException("Live SiriusXM channels can't play on speakers yet")
            return server() + path to if (track.isStationTrack) "audio/mp4" else "audio/mpeg"
        }
        val resolved = api.resolve(track, kbps(), thorough = true, codecs = CODECS)
        val start = if (startMs > 0) "&start=" + String.format(Locale.ROOT, "%.1f", startMs / 1000.0) else ""
        val extension = resolved.extension.lowercase()
        // Starting part-way, the server converts: lossless to FLAC, the rest to MP3.
        val mime = when {
            startMs > 0 && extension in LOSSLESS -> "audio/flac"
            extension == "flac" -> "audio/flac"
            extension == "wav" -> "audio/wav"
            else -> "audio/mpeg"
        }
        return api.absolute(resolved.streamUrl) + start to mime
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
