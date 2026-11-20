package com.music.server.speakers

import com.music.server.connect.Command
import com.music.server.connect.ConnectHub
import com.music.server.connect.Outbound
import com.music.server.connect.PlayState
import com.music.server.music.ApiTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * One speaker (or group) as a device of each person who may use it. A speaker can't run our
 * player, so the server plays the queue on it: it hands the speaker one song at a time (a signed
 * address on this server), keeps an eye on it to move to the next song when one ends, and does
 * what the person's other devices ask. It notices when someone else takes the speaker over (its
 * own app, another input) and lets go.
 *
 * A shared speaker shows for everyone who may use it; the person who last moved music to it is
 * the one playing on it ([driver]), and only their devices see what it's playing.
 */
class SpeakerPlayer(
    target: SpeakerTarget,
    private val hub: ConnectHub,
    /** The address and description of a song or station for the speaker. */
    private val media: suspend (target: SpeakerTarget, userId: String, track: ApiTrack, startMs: Long) -> SpeakerMedia,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long after being given a song the speaker may still be getting going. */
    private val settleMs: Long = SETTLE_MS,
    private val busyPollMs: Long = BUSY_POLL_MS,
    private val idlePollMs: Long = IDLE_POLL_MS,
) {
    @Volatile var target: SpeakerTarget = target
        private set
    @Volatile private var renderer: Renderer = target.renderer()

    /** Each person's view of it in the hub. */
    private val devices = ConcurrentHashMap<String, ConnectHub.Device>()
    private val commands = Channel<Pair<String, Command>>(COMMAND_BUFFER)
    private val lock = Mutex()
    private val showing = Mutex()
    private var job: Job? = null
    /**
     * What to tell the hub, sent from a coroutine of its own: a device that's slow to take its
     * messages holds up only these (and only the latest is kept), never the speaker.
     */
    private val reports = Channel<Pair<String, PlayState>>(Channel.CONFLATED)
    private var reporter: Job? = null

    /** The person playing on it. */
    @Volatile var driver: String? = null
        private set
    private var queue: List<ApiTrack> = emptyList()
    private var index = -1
    private var repeat = "off"
    /** What the speaker was given, if it's playing (or paused on) one of ours. */
    private var current: SpeakerMedia? = null
    /** It's meant to be playing (as opposed to paused, finished or taken over). */
    private var wanted = false
    private var startedAt = 0L
    /** It was stopped rather than paused: playing again starts the song from here. */
    private var resumeFrom: Long? = null
    private var positionMs = 0L
    private var positionAt = 0L
    private var playing = false
    private var volume = 0.5
    private var failures = 0
    private var unreachable = 0

    /** The last thing that went wrong, shown under its name until something plays. */
    @Volatile var problem: String? = null
        private set

    val deviceId: String get() = DEVICE_PREFIX + target.id

    fun start() {
        if (job != null) return
        reporter = scope.launch {
            for ((user, state) in reports) {
                val device = devices[user] ?: continue
                try {
                    hub.report(device, state)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.debug("Couldn't report {}: {}", target.name, e.message)
                }
            }
        }
        job = scope.launch {
            var lastCheck = 0L
            while (isActive) {
                val wait = (if (wanted || playing) busyPollMs else idlePollMs) - (clock() - lastCheck)
                // Commands first, even when a look at the speaker is due (it can take a while).
                val next = if (wait > 0) withTimeoutOrNull(wait) { commands.receive() } else commands.tryReceive().getOrNull()
                lock.withLock {
                    if (next != null) {
                        val (user, command) = next
                        try {
                            obey(user, command)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            complain(command.action, e)
                        }
                    } else {
                        try {
                            check()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn("{}: keeping an eye on it failed", target.name, e)
                        }
                        lastCheck = clock()
                    }
                }
            }
        }
    }

    /** Takes it out of everyone's devices. */
    suspend fun close() {
        job?.cancel()
        job = null
        reporter?.cancel()
        reporter = null
        showing.withLock {
            devices.values.forEach { hub.leave(it) }
            devices.clear()
        }
    }

    /** A new name, address or way to reach it (a group changed, Music Assistant reconnected). */
    fun update(next: SpeakerTarget) {
        val before = target
        target = next
        if (next.rendererKey != before.rendererKey) renderer = next.renderer()
    }

    /** Shows it as a device of exactly [users]; whoever's no longer among them stops playing on it. */
    suspend fun show(users: Set<String>) = showing.withLock {
        val shown = target
        val detail = problem ?: shown.detail
        for (user in users) {
            val existing = devices[user]
            if (existing == null || existing.name != shown.name || existing.detail != detail) {
                devices[user] = hub.join(user, deviceId, shown.name, "speaker", detail, server = true) { text -> receive(user, text) }
            }
        }
        for ((user, device) in devices.entries.toList()) {
            if (user in users) continue
            devices.remove(user)
            hub.leave(device)
            if (driver == user) commands.trySend(user to Command(RELEASE))
        }
    }

    /** What the hub sends this person's view of it: only commands matter. */
    private fun receive(user: String, text: String) {
        // Device lists (most of what the hub sends) aren't read.
        if ("\"command\"" !in text) return
        val message = runCatching { ConnectHub.HubJson.decodeFromString(Outbound.serializer(), text) }.getOrNull() ?: return
        if (message.type == "command") message.command?.let { commands.trySend(user to it) }
    }

    // ---- what the person's devices ask ----

    private suspend fun obey(user: String, command: Command) {
        when (command.action) {
            "take" -> {
                val state = command.state ?: return
                val handed = state.queue.ifEmpty { listOfNotNull(state.track) }.take(ConnectHub.MAX_QUEUE)
                if (handed.isEmpty()) return
                val previous = driver
                if (previous != null && previous != user) devices[previous]?.let { scope.launch { hub.report(it, PlayState()) } }
                driver = user
                queue = handed
                repeat = state.repeat
                failures = 0
                val at = state.index.coerceIn(0, handed.size - 1)
                // Within its first moments, a song starts from the top (as it is, rather than converted from there).
                val from = state.positionMs.takeIf { it >= FROM_THE_TOP_MS } ?: 0
                playAt(at, if (state.live || handed[at].isLiveStream) 0 else from)
            }
            RELEASE -> if (driver == user) {
                if (current != null || wanted) runCatching { renderer.stop() }
                wanted = false
                playing = false
                current = null
                driver = null
            }
            else -> {
                // Someone it isn't playing for can only turn it up or down while it's free.
                if (user != driver) {
                    if (command.action == "volume" && driver == null) command.volume?.let { setVolume(it) }
                    return
                }
                when (command.action) {
                    "play" -> resume()
                    "pause" -> pauseNow()
                    "next" -> following(index, skipping = true)?.let { playAt(it, 0) }
                    "previous" -> when {
                        index < 0 -> Unit
                        position() > RESTART_WITHIN_MS || index == 0 -> playAt(index, 0)
                        else -> playAt(index - 1, 0)
                    }
                    "seek" -> command.positionMs?.let { seek(it.coerceAtLeast(0)) }
                    "volume" -> command.volume?.let { setVolume(it) }
                }
            }
        }
    }

    private suspend fun setVolume(level: Double) {
        val clamped = level.coerceIn(0.0, 1.0)
        renderer.setVolume(clamped)
        volume = clamped
        report(force = true)
    }

    private suspend fun resume() {
        val track = queue.getOrNull(index) ?: return
        val ours = current
        if (ours != null && !track.isLiveStream && resumeFrom == null) {
            val status = runCatching { renderer.status() }.getOrNull()
            if (status?.transport == Transport.PAUSED && ours.marker in status.playing) {
                renderer.play()
                wanted = true
                playing = true
                startedAt = clock()
                positionAt = clock()
                report(force = true)
                return
            }
        }
        playAt(index, if (track.isLiveStream) 0 else resumeFrom ?: position())
    }

    private suspend fun pauseNow() {
        if (!wanted && !playing) return
        val at = position()
        wanted = false
        playing = false
        val live = queue.getOrNull(index)?.isLiveStream == true
        // A station can't be paused, only stopped (and tuned in again live).
        val paused = !live && runCatching { renderer.pause() }.isSuccess
        if (!paused) {
            runCatching { renderer.stop() }
            resumeFrom = at
        }
        positionMs = at
        positionAt = clock()
        report(force = true)
    }

    private suspend fun seek(to: Long) {
        val track = queue.getOrNull(index) ?: return
        if (track.isLiveStream) return
        val ours = current
        if (ours != null && ours.seekable && ours.offsetMs == 0L && resumeFrom == null) {
            val moved = runCatching { renderer.seek(to) }.isSuccess
            if (moved) {
                positionMs = to
                positionAt = clock()
                startedAt = clock()
                report(force = true)
                return
            }
        }
        if (!wanted && !playing) {
            // Paused: it starts from there when played.
            resumeFrom = to
            positionMs = to
            report(force = true)
            return
        }
        // Converted streams can't be sought in: the song starts again from there.
        playAt(index, to)
    }

    /** Plays song [at] of the queue, from [startMs] into it. */
    private suspend fun playAt(at: Int, startMs: Long) {
        val track = queue.getOrNull(at) ?: return
        val user = driver ?: return
        index = at
        resumeFrom = null
        startedAt = clock()
        positionMs = startMs
        positionAt = clock()
        wanted = true
        playing = true
        report(force = true)
        val next = media(target, user, track, startMs)
        renderer.load(next)
        current = next
        startedAt = clock()
        if (problem != null) {
            problem = null
            showAgain()
        }
        report(force = true)
    }

    /** The song after [from], or null at the end of the queue. */
    private fun following(from: Int, skipping: Boolean = false): Int? = when {
        repeat == "one" && !skipping -> from
        from + 1 < queue.size -> from + 1
        repeat == "all" && queue.isNotEmpty() -> 0
        else -> null
    }

    // ---- keeping an eye on it ----

    private suspend fun check() {
        val status = try {
            renderer.status()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (++unreachable >= GONE_AFTER && (wanted || playing)) {
                log.info("{} stopped answering: {}", target.name, e.message)
                wanted = false
                playing = false
                report(force = true)
            }
            return
        }
        unreachable = 0
        status.volume?.let { volume = it }
        val ours = current
        if (ours == null) {
            report()
            return
        }
        val settling = clock() - startedAt < settleMs
        val stillOurs = ours.marker in status.playing
        when {
            !stillOurs && settling -> Unit
            // Something else at the end of the song: it finished (and the speaker moved on by
            // itself, as Music Assistant's "don't stop the music" does), so the queue goes on.
            !stillOurs -> if (wanted && nearEnd()) songEnded() else takenOver(status)
            status.transport == Transport.PLAYING -> {
                playing = true
                if (!ours.live) {
                    positionMs = ours.offsetMs + status.positionMs
                    positionAt = clock()
                }
            }
            status.transport == Transport.PAUSED -> {
                // Paused from its own app, or the speaker's buttons.
                if (!settling) wanted = false
                if (!ours.live) positionMs = ours.offsetMs + status.positionMs
                positionAt = clock()
                playing = false
            }
            status.transport == Transport.STOPPED && wanted && !settling -> songEnded()
            status.transport == Transport.STOPPED && !wanted -> playing = false
        }
        // A song that plays for a while has worked: earlier failures are forgotten.
        if (playing && position() > FAILURES_FORGOTTEN_MS) failures = 0
        report()
    }

    private fun takenOver(status: RendererStatus) {
        if (wanted || playing) log.info("{} was taken over ({})", target.name, status.playing.take(120))
        wanted = false
        playing = false
        current = null
    }

    private fun nearEnd(): Boolean {
        val track = queue.getOrNull(index) ?: return false
        val duration = track.durationMs ?: 0
        return !track.isLiveStream && duration > 0 && position() >= duration - END_SLACK_MS
    }

    /** The speaker stopped by itself: the song finished (or couldn't be played). */
    private suspend fun songEnded() {
        val track = queue.getOrNull(index)
        if (track == null || track.isLiveStream) {
            // A station that stopped: it isn't tuned in again and again.
            wanted = false
            playing = false
            current = null
            report(force = true)
            return
        }
        if (!nearEnd() && ++failures >= MAX_FAILURES) {
            // Several songs in a row wouldn't play: stop rather than skip through everything.
            log.info("{}: {} songs in a row didn't play; stopping", target.name, failures)
            stopWanting()
            return
        }
        var at = following(index)
        while (at != null) {
            try {
                playAt(at, 0)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // One that can't play here (a SiriusXM channel, say) is passed over.
                log.info("{}: skipping {}: {}", target.name, queue[at].title, e.message)
                if (++failures >= MAX_FAILURES) break
                at = following(at, skipping = true)
            }
        }
        stopWanting()
        positionMs = 0
    }

    private suspend fun stopWanting() {
        wanted = false
        playing = false
        positionAt = clock()
        report(force = true)
    }

    private fun position(): Long {
        val at = positionMs + if (playing) clock() - positionAt else 0
        val duration = queue.getOrNull(index)?.durationMs ?: 0
        return if (duration > 0) at.coerceAtMost(duration) else at
    }

    private var reported: String? = null
    private var reportedAt = 0L
    private var reportedPosition = 0L

    /** Tells the driver's devices what it's doing: when that changes, when it's somewhere other than expected, and now and then. */
    private suspend fun report(force: Boolean = false) {
        val device = driver?.let { devices[it] } ?: return
        val track = queue.getOrNull(index)?.takeIf { current != null || wanted }
        val key = "${track?.sourceId}|$index|$playing|${queue.size}|${(volume * 100).roundToInt()}"
        val at = position()
        val expected = reportedPosition + if (playing) clock() - reportedAt else 0
        val moved = kotlin.math.abs(at - expected) > DRIFT_MS
        if (!force && key == reported && !moved && clock() - reportedAt < REPORT_EVERY_MS) return
        reported = key
        reportedAt = clock()
        reportedPosition = at
        val live = track?.isLiveStream == true
        val state = PlayState(
            track = track,
            queue = queue,
            index = index,
            positionMs = if (live) 0 else position(),
            durationMs = track?.durationMs ?: 0,
            playing = playing,
            volume = volume,
            live = live,
            repeat = repeat,
        )
        reports.trySend(device.userId to state)
    }

    private suspend fun complain(action: String, error: Throwable) {
        log.warn("{}: {} failed: {}", target.name, action, error.message ?: error.javaClass.simpleName)
        val reason = (error as? SpeakerException)?.message ?: error.message ?: error.javaClass.simpleName
        problem = "Couldn't ${if (action == "take") "play" else action}: $reason".take(80)
        wanted = false
        playing = false
        report(force = true)
        showAgain()
    }

    /** Shows its name (and any problem) again, to the same people. */
    private fun showAgain() {
        scope.launch { show(devices.keys.toSet()) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SpeakerPlayer::class.java)
        /** Speakers' device ids have a colon, which apps' ids never do, so an app can't take a speaker's place. */
        const val DEVICE_PREFIX = "speaker:"
        private const val RELEASE = "release"
        private const val COMMAND_BUFFER = 64
        const val BUSY_POLL_MS = 2_000L
        const val IDLE_POLL_MS = 15_000L
        const val SETTLE_MS = 10_000L
        private const val RESTART_WITHIN_MS = 3_000L
        private const val FROM_THE_TOP_MS = 2_000L
        private const val END_SLACK_MS = 15_000L
        private const val FAILURES_FORGOTTEN_MS = 20_000L
        private const val MAX_FAILURES = 3
        private const val GONE_AFTER = 5
        private const val REPORT_EVERY_MS = 15_000L
        /** Further than this from where it should be (seeked from its own app, say): reported at once. */
        private const val DRIFT_MS = 3_000L
    }
}
