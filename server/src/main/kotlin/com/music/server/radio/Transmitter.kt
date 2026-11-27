package com.music.server.radio

import com.music.player.CoreLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos

/** Raw audio as the station mixes it: 16-bit little-endian stereo at 44.1 kHz. */
object Pcm {
    const val RATE = 44_100
    const val BYTES_PER_FRAME = 4

    fun msOf(frames: Long): Long = frames * 1000 / RATE
    fun framesOf(ms: Long): Long = ms * RATE / 1000
    fun bytesOf(ms: Long): Int = (framesOf(ms) * BYTES_PER_FRAME).toInt()

    private fun sample(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xff) or (bytes[at + 1].toInt() shl 8)

    private fun put(bytes: ByteArray, at: Int, value: Int) {
        val v = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        bytes[at] = v.toByte()
        bytes[at + 1] = (v shr 8).toByte()
    }

    /**
     * How much of [audio] is left once silence at its end is cut (in bytes, whole frames), with
     * [keepMs] after the last sound so endings aren't clipped.
     */
    fun withoutTrailingSilence(audio: ByteArray, length: Int = audio.size, threshold: Int = 160, keepMs: Long = 150): Int {
        var at = (length / BYTES_PER_FRAME) * BYTES_PER_FRAME - 2
        while (at >= 0 && abs(sample(audio, at).toShort().toInt()) < threshold) at -= 2
        if (at < 0) return 0
        val end = (at / BYTES_PER_FRAME + 1) * BYTES_PER_FRAME + bytesOf(keepMs)
        return end.coerceAtMost((length / BYTES_PER_FRAME) * BYTES_PER_FRAME)
    }

    /** [audio] fading out to nothing (a gentle curve: slow at first, then away), as a new array. */
    fun fadeOut(audio: ByteArray): ByteArray {
        val frames = audio.size / BYTES_PER_FRAME
        val faded = ByteArray(frames * BYTES_PER_FRAME)
        for (f in 0 until frames) {
            val t = if (frames <= 1) 1.0 else f.toDouble() / (frames - 1)
            val gain = cos(t * Math.PI / 2)
            for (c in 0 until 2) {
                val at = f * BYTES_PER_FRAME + c * 2
                put(faded, at, (sample(audio, at).toShort().toInt() * gain).toInt())
            }
        }
        return faded
    }
}

/** A song decoding to [Pcm]: read until -1. */
interface PcmSource : Closeable {
    fun read(into: ByteArray, offset: Int, length: Int): Int

    /** Where in the song its audio starts (it may not have been able to start where asked). */
    val startMs: Long get() = 0
}

/** [source] with [head] (already read from it) put back in front. */
private class Primed(private val source: PcmSource, private val head: ByteArray, private var headLength: Int) : PcmSource {
    private var at = 0
    override val startMs: Long get() = source.startMs

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (at < headLength) {
            val n = minOf(length, headLength - at)
            System.arraycopy(head, at, into, offset, n)
            at += n
            return n
        }
        return source.read(into, offset, length)
    }

    override fun close() = source.close()
}

/** What a [Transmitter] plays: where the songs come from, how they decode, and what's said. */
interface Programme {
    /**
     * The next song (null: nothing to play), given what's on, opening and lined up already
     * ([scheduled], in the order they play).
     */
    suspend fun nextSong(scheduled: List<Candidate>): Candidate?

    /**
     * Starts decoding [song] from [fromMs] into its audio; throws if it can't be played. Unless
     * [exact], it may start from the beginning instead where seeking would be slow.
     */
    suspend fun open(song: Candidate, fromMs: Long, exact: Boolean = false): PcmSource

    /** An encoder taking [Pcm] in and giving ADTS AAC out. */
    fun encoder(): Process

    /** [now] went on the air (became audible at [atMs]) after [before]: kept, and what the DJ says over it. */
    fun aired(now: Transmitter.OnAir, before: Transmitter.OnAir?, coming: List<Candidate>, atMs: Long, skipped: Boolean = false): DjLine?
}

/**
 * One station on the air: songs decoded one after another, each fading out before the next (and
 * silence at the ends trimmed) into one stream, encoded once and sent to every listener, paced to
 * real time. A listener tuning in gets the last couple of seconds at once (so it starts straight
 * away), then the station as it goes: every one of the person's devices hears the same broadcast.
 */
class Transmitter(
    val userId: String,
    val kind: StationKind,
    @Volatile var name: String,
    private val programme: Programme,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    /**
     * A song on the air: it started (on the air) at [startMs], counting any part of it skipped
     * by tuning in; it was actually heard from [airedAt] until [endedAt] (once it's over).
     */
    data class OnAir(val song: Candidate, val startMs: Long, val durationMs: Long, val airedAt: Long = startMs, val endedAt: Long? = null)

    @Volatile var current: OnAir? = null
        private set

    private val recentSongs = ArrayDeque<OnAir>()
    val recent: List<OnAir> get() = synchronized(recentSongs) { recentSongs.toList().asReversed() }

    @Volatile var lined: List<Candidate> = emptyList()
        private set

    @Volatile var dj: DjLine? = null
        private set

    /** The person's time zone, for the DJ's time checks. */
    @Volatile var zone: ZoneId = ZoneId.systemDefault()

    @Volatile var startedAt: Long = 0
        private set

    // ---- Listeners ----

    class Listener {
        val frames = Channel<ByteArray>(LISTENER_FRAMES)
    }

    /** Where each listener's stream began (the on-air time of its first frame), by the id it tuned in with. */
    private val listenerStarts = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 64
    }

    /** The on-air time of the next frame out of the encoder. */
    @Volatile private var nextFrameAt = 0L

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val ring = ArrayDeque<Pair<Long, ByteArray>>()
    private val ringLock = Any()

    /** When the last listener left (null while someone listens). */
    @Volatile var idleSince: Long? = null
        private set

    val listenerCount: Int get() = listeners.size

    /**
     * Tunes in: the last moments (to start at once), and the station from here on. With [id],
     * where the stream begins is kept, so [heardAt] can say what that listener hears.
     */
    fun subscribe(id: String? = null): Pair<List<ByteArray>, Listener> = synchronized(ringLock) {
        val from = clock() - BURST_MS
        val kept = ring.filter { it.first >= from }
        val burst = kept.map { it.second }
        if (id != null) synchronized(listenerStarts) { listenerStarts[id] = kept.firstOrNull()?.first ?: nextFrameAt }
        val listener = Listener()
        listeners += listener
        idleSince = null
        burst to listener
    }

    /**
     * The on-air time a listener (by the id it tuned in with) hears, [positionMs] into its stream
     * (its player's position), or null if it isn't known or doesn't make sense.
     */
    fun heardAt(id: String, positionMs: Long): Long? {
        val start = synchronized(listenerStarts) { listenerStarts[id] } ?: return null
        val heard = start + positionMs
        // Ahead of what's been sent, or more than a little while behind: not this stream's position.
        return heard.takeIf { positionMs >= 0 && it <= clock() + LEAD_MS + 2_000 && it >= clock() - MAX_BEHIND_MS }
    }

    /** What was on the air at [at]: the song then, and the ones after it (oldest first). */
    fun songsFrom(at: Long): List<OnAir> {
        val all = synchronized(recentSongs) { recentSongs.toList() + listOfNotNull(current) }
        val index = all.indexOfLast { it.airedAt <= at }.coerceAtLeast(0)
        return all.drop(index)
    }

    /** The DJ's line over [songKey], if there was one. */
    fun djFor(songKey: String): DjLine? = synchronized(djLines) { djLines.lastOrNull { it.songKey == songKey } }

    private val djLines = ArrayDeque<DjLine>()

    fun unsubscribe(listener: Listener) {
        listeners -= listener
        listener.frames.close()
        if (listeners.isEmpty()) idleSince = clock()
    }

    // ---- On the air ----

    @Volatile private var running = false
    @Volatile private var skipRequested = false

    /** The song before the one starting was skipped (so the DJ doesn't announce it). */
    @Volatile private var endedBySkip = false
    @Volatile var failed: String? = null
        private set
    private var playoutThread: Thread? = null

    val isRunning: Boolean get() = running

    /** Moves on to the next song now (a quick fade, as a DJ would). */
    fun skip() {
        skipRequested = true
    }

    /** Takes [key] off what's lined up (a thumbs down). */
    fun drop(key: String) {
        lined = lined.filter { it.key != key }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        startedAt = clock()
        nextFrameAt = startedAt
        idleSince = clock()
        playoutThread = thread(name = "radio-${kind.id}", isDaemon = true) {
            try {
                playout()
            } catch (e: Exception) {
                failed = e.message ?: e.javaClass.simpleName
                CoreLog.w(TAG, "$name went off the air", e)
            } finally {
                running = false
                synchronized(ringLock) { listeners.forEach { it.frames.close() } }
            }
        }
    }

    fun stop() {
        running = false
        playoutThread?.interrupt()
    }

    /**
     * A song being played: its decoder, and how far into it the station is. It began at
     * [fromMs]; its decoder (replaced if it's picked up again) started at [baseMs].
     */
    private class Playing(val song: Candidate, var source: PcmSource, val durationMs: Long, val fromMs: Long, var decodedBytes: Long = 0) {
        var baseMs: Long = fromMs
        var resumes = 0
        val positionMs: Long get() = baseMs + decodedBytes / (Pcm.RATE * Pcm.BYTES_PER_FRAME / 1000)

        /** It stopped well before its end (the service dropped it, say), so it's worth picking up again. */
        fun endedEarly(): Boolean = durationMs > 0 && positionMs < durationMs - RESUME_SLACK_MS && resumes < MAX_RESUMES
    }

    /**
     * A song opening (resolving it and starting its decoder, up to its first audio) on its own
     * thread. One that takes too long is given up on: its decoder is closed, which also ends a
     * read waiting on it.
     */
    private inner class Opening(val song: Candidate, val fromMs: Long, val exact: Boolean = false) {
        @Volatile private var opened: PcmSource? = null
        @Volatile private var abandoned = false
        @Volatile var source: PcmSource? = null
        @Volatile var error: Exception? = null
        private val began = System.nanoTime()
        private val worker = thread(isDaemon = true, name = "radio-${kind.id}-open") {
            try {
                val decoder = runBlocking { withTimeoutOrNull(OPEN_TIMEOUT_MS) { programme.open(song, fromMs, exact) } }
                    ?: throw IOException("It took too long to start")
                opened = decoder
                if (abandoned) {
                    decoder.close()
                    return@thread
                }
                // Its first audio: a song that won't play is known now (not mid-air).
                val head = ByteArray(CHUNK_BYTES)
                val n = decoder.read(head, 0, head.size)
                if (n <= 0) {
                    decoder.close()
                    throw IOException("It sent nothing to play")
                }
                source = Primed(decoder, head, n)
            } catch (e: Exception) {
                error = e
            }
        }
        val done: Boolean get() = !worker.isAlive

        /** Waits for it, keeping the air filled (quietly) meanwhile. Null if it can't play. */
        fun await(pipe: OutputStream): PcmSource? {
            while (!done && running) {
                if ((System.nanoTime() - began) / 1_000_000 > OPEN_TIMEOUT_MS + FIRST_AUDIO_MS) {
                    error = IOException("It took too long to start")
                    abandon()
                    break
                }
                if (aheadMs() < LEAD_MS / 2) send(pipe, SILENCE, 0, SILENCE.size) else worker.join(50)
            }
            if (!running) {
                abandon()
                return null
            }
            error?.let {
                CoreLog.w(TAG, "$name couldn't play ${song.track.artist} – ${song.track.title}: ${it.message}")
                abandon()
                return null
            }
            return source
        }

        /** Gives up on it: whatever it opened is closed (now, or as soon as it's opened). */
        private fun abandon() {
            abandoned = true
            thread(isDaemon = true) {
                runCatching { opened?.close() }
                runCatching { source?.close() }
            }
        }

        fun cancel() = abandon()
    }

    private var t0 = 0L
    private var writtenFrames = 0L

    private fun onAirAt(frames: Long) = t0 + Pcm.msOf(frames)

    private fun playout() {
        val encoder = programme.encoder()
        val reader = thread(name = "radio-${kind.id}-out", isDaemon = true) { distribute(encoder.inputStream) }
        val pipe = BufferedOutputStream(encoder.outputStream, 64 * 1024)
        t0 = clock()
        nextFrameAt = t0
        writtenFrames = 0
        var playing: Playing? = null
        var opening: Opening? = null
        try {
            // Tuning in to a station that wasn't on: it was, and here's the middle of a song.
            playing = begin(next(joinMidSong = true) ?: return, pipe, before = null) ?: return
            val window = ByteArray(WINDOW_BYTES)
            var held = 0
            val chunk = ByteArray(CHUNK_BYTES)
            while (running) {
                val song = playing!!
                // The next song opens a little before this one ends, so it's ready to cut to.
                if (opening == null && song.durationMs > 0 && song.positionMs >= song.durationMs - PREOPEN_MS) opening = next(joinMidSong = false)
                val skipped = skipRequested
                val read = if (skipped) -1 else readFully(song.source, chunk)
                if (read <= 0 && !skipped && song.endedEarly()) {
                    // It stopped short of its end: what's held goes out, and it's picked up where it stopped.
                    song.resumes++
                    CoreLog.w(TAG, "$name lost ${song.song.track.artist} – ${song.song.track.title} at ${song.positionMs / 1000}s of ${song.durationMs / 1000}s; picking it up again")
                    runCatching { song.source.close() }
                    send(pipe, window, 0, held)
                    held = 0
                    val again = Opening(song.song, song.positionMs, exact = true).await(pipe)
                    if (again != null) {
                        song.source = again
                        song.baseMs = again.startMs
                        song.decodedBytes = 0
                        continue
                    }
                }
                if (read <= 0) {
                    // The end (or a skip): its last moments (silence cut) fade out, then the next song.
                    skipRequested = false
                    endedBySkip = skipped
                    song.source.close()
                    playing = transition(song, window.copyOf(held), pipe, skipped, opening ?: next(joinMidSong = false)) ?: return
                    opening = null
                    held = 0
                    continue
                }
                song.decodedBytes += read
                // Holds the newest WINDOW of the song back (to find where its sound ends), sending the rest.
                if (held + read > window.size) {
                    val out = held + read - window.size
                    val fromWindow = minOf(out, held)
                    if (fromWindow > 0) send(pipe, window, 0, fromWindow)
                    if (out > fromWindow) send(pipe, chunk, 0, out - fromWindow)
                    System.arraycopy(window, fromWindow, window, 0, held - fromWindow)
                    held -= fromWindow
                    val keepFromChunk = read - (out - fromWindow)
                    System.arraycopy(chunk, out - fromWindow, window, held, keepFromChunk)
                    held += keepFromChunk
                } else {
                    System.arraycopy(chunk, 0, window, held, read)
                    held += read
                }
            }
        } finally {
            runCatching { playing?.source?.close() }
            opening?.cancel()
            runCatching { pipe.close() }
            encoder.destroy()
            if (!encoder.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) encoder.destroyForcibly()
            reader.join(2_000)
        }
    }

    /**
     * From [ending] (its unsent [tail]) to [opening]: the tail with its silence cut, its last
     * seconds fading out, a breath of quiet, then the next song from its start (a skip fades out
     * quickly). The songs never overlap: one ends before the next begins, as on the radio. A
     * song that won't play is passed over for the next.
     */
    private fun transition(ending: Playing, tail: ByteArray, pipe: OutputStream, skipped: Boolean, opening: Opening?): Playing? {
        val usable = if (skipped) minOf(tail.size, Pcm.bytesOf(SKIP_FADE_MS)) else Pcm.withoutTrailingSilence(tail)
        val fade = if (skipped) usable else minOf(usable, Pcm.bytesOf(FADE_OUT_MS))
        send(pipe, tail, 0, usable - fade)
        val faded = Pcm.fadeOut(tail.copyOfRange(usable - fade, usable))
        send(pipe, faded, 0, faded.size)
        val gap = ByteArray(Pcm.bytesOf(GAP_MS))
        send(pipe, gap, 0, gap.size)
        var next = opening
        var attempts = 0
        while (next != null && running && attempts++ < OPEN_ATTEMPTS) {
            val source = next.await(pipe)
            if (source == null) {
                next = next(joinMidSong = false)
                continue
            }
            return begin(Playing(next.song, source, next.song.track.durationMs ?: 0L, source.startMs), pipe, ending)
        }
        return null
    }

    /** Lines up and starts opening the next song (null: nothing to play). */
    private fun next(joinMidSong: Boolean): Opening? {
        if (!running) return null
        if (lined.isEmpty()) refillNow()
        val song = lined.firstOrNull() ?: return null
        lined = lined.drop(1)
        openingSong = song
        refillLater()
        val length = song.track.durationMs ?: 0L
        val from = if (joinMidSong && length > 60_000) (length * (0.15 + random.nextDouble() * 0.4)).toLong() else 0L
        return Opening(song, from)
    }

    /** Starts [playing] (for the first song: waits for it to open, from [opening]). */
    private fun begin(opening: Opening, pipe: OutputStream, before: Playing?): Playing? {
        val source = opening.await(pipe) ?: return null
        return begin(Playing(opening.song, source, opening.song.track.durationMs ?: 0L, source.startMs), pipe, before)
    }

    /** [playing] goes on the air now: what's on, what was, and what the DJ says. */
    private fun begin(playing: Playing, pipe: OutputStream, before: Playing?): Playing {
        val at = onAirAt(writtenFrames)
        val onAir = OnAir(playing.song, at - playing.fromMs, playing.durationMs, airedAt = at)
        val previous = current?.copy(endedAt = at)
        if (openingSong?.key == playing.song.key) openingSong = null
        // What was on moves to what's been played, as what's on changes (so between them, it's always one or the other).
        synchronized(recentSongs) {
            previous?.let { recentSongs.addLast(it); while (recentSongs.size > RECENT) recentSongs.removeFirst() }
            current = onAir
        }
        val skipped = before != null && endedBySkip
        endedBySkip = false
        val line = runCatching { programme.aired(onAir, if (before != null) previous else null, lined, at, skipped) }.getOrNull()
        if (line != null) {
            dj = line
            synchronized(djLines) {
                djLines.addLast(line)
                while (djLines.size > RECENT) djLines.removeFirst()
            }
        }
        return playing
    }

    @Volatile private var refilling = false

    /** The song opening to play next (no longer lined up, not yet on). */
    @Volatile private var openingSong: Candidate? = null

    /** Keeps [LINED_UP] songs lined up (for "coming up" and the DJ), off the audio's thread. */
    private fun refillLater() {
        if (refilling || lined.size >= LINED_UP) return
        refilling = true
        thread(isDaemon = true, name = "radio-${kind.id}-lineup") {
            try {
                refillNow()
            } finally {
                refilling = false
            }
        }
    }

    @Synchronized
    private fun refillNow() {
        runCatching {
            runBlocking {
                while (lined.size < LINED_UP && running) {
                    val scheduled = listOfNotNull(current?.song, openingSong) + lined
                    val next = programme.nextSong(scheduled) ?: break
                    lined = lined + next
                }
            }
        }.onFailure { CoreLog.w(TAG, "$name couldn't line up songs: ${it.message}") }
    }

    private fun aheadMs(): Long = Pcm.msOf(writtenFrames) - (clock() - t0)

    /** Sends audio to the encoder at the pace of the clock (a little ahead, never further). */
    private fun send(pipe: OutputStream, audio: ByteArray, offset: Int, length: Int) {
        var at = offset
        val end = offset + (length / Pcm.BYTES_PER_FRAME) * Pcm.BYTES_PER_FRAME
        while (at < end && running) {
            val ahead = aheadMs()
            if (ahead > LEAD_MS) {
                try {
                    Thread.sleep((ahead - LEAD_MS).coerceAtMost(200))
                } catch (e: InterruptedException) {
                    return
                }
                continue
            }
            val n = minOf(end - at, CHUNK_BYTES)
            pipe.write(audio, at, n)
            pipe.flush()
            writtenFrames += n / Pcm.BYTES_PER_FRAME
            at += n
        }
    }

    private fun readFully(source: PcmSource, into: ByteArray): Int {
        var got = 0
        while (got < into.size) {
            val n = source.read(into, got, into.size - got)
            if (n < 0) break
            got += n
        }
        return if (got == 0) -1 else (got / Pcm.BYTES_PER_FRAME) * Pcm.BYTES_PER_FRAME
    }

    /** Splits the encoder's ADTS stream into frames and sends each to every listener. */
    private fun distribute(input: InputStream) {
        val data = DataInputStream(input.buffered(16 * 1024))
        val header = ByteArray(7)
        var frames = 0L
        try {
            while (true) {
                data.readFully(header, 0, 2)
                // Resynchronises on the ADTS sync word if ever needed.
                while (!((header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xf0) == 0xf0)) {
                    header[0] = header[1]
                    header[1] = data.readByte()
                }
                data.readFully(header, 2, 5)
                val length = ((header[3].toInt() and 0x03) shl 11) or ((header[4].toInt() and 0xff) shl 3) or ((header[5].toInt() and 0xe0) ushr 5)
                if (length < 7) continue
                val frame = ByteArray(length)
                System.arraycopy(header, 0, frame, 0, 7)
                data.readFully(frame, 7, length - 7)
                val onAir = t0 + frames * AAC_FRAME_SAMPLES * 1000 / Pcm.RATE
                frames++
                nextFrameAt = t0 + frames * AAC_FRAME_SAMPLES * 1000 / Pcm.RATE
                synchronized(ringLock) {
                    ring.addLast(onAir to frame)
                    val keepFrom = clock() - BURST_MS
                    while (ring.isNotEmpty() && ring.first().first < keepFrom) ring.removeFirst()
                    for (listener in listeners) {
                        // A listener this far behind has stopped listening: let it go.
                        if (listener.frames.trySend(frame).isFailure) unsubscribe(listener)
                    }
                }
            }
        } catch (e: EOFException) {
            // The encoder stopped.
        } catch (e: IOException) {
            // The encoder stopped.
        }
    }

    companion object {
        private const val TAG = "Radio"

        /** How far ahead of the clock the station encodes (listeners' cushion). */
        const val LEAD_MS = 3_000L

        /** What a listener gets at once on tuning in. */
        const val BURST_MS = 2_000L

        /** How much of each song is held back to find where its sound ends. */
        private const val WINDOW_MS = 8_000L
        private val WINDOW_BYTES = Pcm.bytesOf(WINDOW_MS)
        /** How long a song takes to fade out at its end, and on a skip; then a moment's quiet before the next. */
        private const val FADE_OUT_MS = 3_500L
        private const val SKIP_FADE_MS = 1_500L
        private const val GAP_MS = 600L
        private val CHUNK_BYTES = Pcm.bytesOf(100)
        private val SILENCE = ByteArray(Pcm.bytesOf(100))
        private const val OPEN_ATTEMPTS = 6
        private const val PREOPEN_MS = 20_000L
        private const val OPEN_TIMEOUT_MS = 15_000L

        /** How long after opening a song its first audio may take. */
        private const val FIRST_AUDIO_MS = 10_000L

        /** A song that stops more than this before its end is picked up again (this many times at most). */
        private const val RESUME_SLACK_MS = 15_000L
        private const val MAX_RESUMES = 2

        /** How far behind the station a listener's stream can be and still be followed. */
        private const val MAX_BEHIND_MS = 15 * 60_000L
        private const val LINED_UP = 3
        private const val RECENT = 12
        private const val LISTENER_FRAMES = 3_000
        private const val AAC_FRAME_SAMPLES = 1024L
    }
}
