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
import kotlin.math.sin

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

    /**
     * [outgoing] fading out under [incoming] fading in (equal power, so the level holds), as
     * long as the shorter of the two.
     */
    fun crossfade(outgoing: ByteArray, incoming: ByteArray): ByteArray {
        val frames = minOf(outgoing.size, incoming.size) / BYTES_PER_FRAME
        val mixed = ByteArray(frames * BYTES_PER_FRAME)
        for (f in 0 until frames) {
            val t = if (frames <= 1) 1.0 else f.toDouble() / (frames - 1)
            val out = cos(t * Math.PI / 2)
            val inn = sin(t * Math.PI / 2)
            for (c in 0 until 2) {
                val at = f * BYTES_PER_FRAME + c * 2
                val a = sample(outgoing, at).toShort().toInt()
                val b = sample(incoming, at).toShort().toInt()
                put(mixed, at, (a * out + b * inn).toInt())
            }
        }
        return mixed
    }
}

/** A song decoding to [Pcm]: read until -1. */
interface PcmSource : Closeable {
    fun read(into: ByteArray, offset: Int, length: Int): Int
}

/** What a [Transmitter] plays: where the songs come from, how they decode, and what's said. */
interface Programme {
    /**
     * The next song (null: nothing to play), given what's on, opening and lined up already
     * ([scheduled], in the order they play).
     */
    suspend fun nextSong(scheduled: List<Candidate>): Candidate?

    /** Starts decoding [song] from [fromMs] into its audio; throws if it can't be played. */
    suspend fun open(song: Candidate, fromMs: Long): PcmSource

    /** An encoder taking [Pcm] in and giving ADTS AAC out. */
    fun encoder(): Process

    /** [now] went on the air (became audible at [atMs]) after [before]: kept, and what the DJ says over it. */
    fun aired(now: Transmitter.OnAir, before: Transmitter.OnAir?, coming: List<Candidate>, atMs: Long): DjLine?
}

/**
 * One station on the air: songs decoded one after another, cut together with crossfades (and
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
    /** A song on the air: it started (on the air) at [startMs], counting any part of it skipped by tuning in. */
    data class OnAir(val song: Candidate, val startMs: Long, val durationMs: Long)

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

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val ring = ArrayDeque<Pair<Long, ByteArray>>()
    private val ringLock = Any()

    /** When the last listener left (null while someone listens). */
    @Volatile var idleSince: Long? = null
        private set

    val listenerCount: Int get() = listeners.size

    /** Tunes in: the last moments (to start at once), and the station from here on. */
    fun subscribe(): Pair<List<ByteArray>, Listener> = synchronized(ringLock) {
        val from = clock() - BURST_MS
        val burst = ring.filter { it.first >= from }.map { it.second }
        val listener = Listener()
        listeners += listener
        idleSince = null
        burst to listener
    }

    fun unsubscribe(listener: Listener) {
        listeners -= listener
        listener.frames.close()
        if (listeners.isEmpty()) idleSince = clock()
    }

    // ---- On the air ----

    @Volatile private var running = false
    @Volatile private var skipRequested = false
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

    /** A song being played: its decoder, and how far into it the station is. */
    private class Playing(val song: Candidate, val source: PcmSource, val durationMs: Long, val fromMs: Long, var decodedBytes: Long = 0) {
        val positionMs: Long get() = fromMs + decodedBytes / (Pcm.RATE * Pcm.BYTES_PER_FRAME / 1000)
    }

    /** The next song opening (resolving and starting its decoder) on its own thread. */
    private inner class Opening(val song: Candidate, val fromMs: Long) {
        @Volatile var source: PcmSource? = null
        @Volatile var error: Exception? = null
        private val worker = thread(isDaemon = true, name = "radio-${kind.id}-open") {
            try {
                source = runBlocking { withTimeoutOrNull(OPEN_TIMEOUT_MS) { programme.open(song, fromMs) } }
                    ?: throw IOException("It took too long to start")
            } catch (e: Exception) {
                error = e
            }
        }
        val done: Boolean get() = !worker.isAlive

        /** Waits for it, keeping the air filled (quietly) meanwhile. Null if it can't play. */
        fun await(pipe: OutputStream): PcmSource? {
            while (!done && running) {
                if (aheadMs() < LEAD_MS / 2) send(pipe, SILENCE, 0, SILENCE.size) else worker.join(50)
            }
            if (!running) {
                source?.close()
                return null
            }
            error?.let { CoreLog.w(TAG, "$name couldn't play ${song.track.artist} – ${song.track.title}: ${it.message}") }
            return source
        }

        fun cancel() {
            thread(isDaemon = true) {
                worker.join(OPEN_TIMEOUT_MS + 1_000)
                source?.close()
            }
        }
    }

    private var t0 = 0L
    private var writtenFrames = 0L

    private fun onAirAt(frames: Long) = t0 + Pcm.msOf(frames)

    private fun playout() {
        val encoder = programme.encoder()
        val reader = thread(name = "radio-${kind.id}-out", isDaemon = true) { distribute(encoder.inputStream) }
        val pipe = BufferedOutputStream(encoder.outputStream, 64 * 1024)
        t0 = clock()
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
                if (read <= 0) {
                    // The end (or a skip): its last moments (silence cut) crossfade into the next song.
                    skipRequested = false
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
     * seconds crossfaded into the next song's start. A skip fades out quickly instead. A song
     * that won't play is passed over for the next.
     */
    private fun transition(ending: Playing, tail: ByteArray, pipe: OutputStream, skipped: Boolean, opening: Opening?): Playing? {
        val usable = if (skipped) minOf(tail.size, Pcm.bytesOf(SKIP_FADE_MS)) else Pcm.withoutTrailingSilence(tail)
        val fade = if (skipped) usable else minOf(usable, Pcm.bytesOf(CROSSFADE_MS))
        send(pipe, tail, 0, usable - fade)
        var outgoing = tail.copyOfRange(usable - fade, usable)
        var next = opening
        var attempts = 0
        while (next != null && running && attempts++ < OPEN_ATTEMPTS) {
            // Ready to cut to: crossfaded. Not yet: the song ends as it is, and the next follows.
            val crossfade = next.done && outgoing.isNotEmpty()
            if (!crossfade) {
                send(pipe, outgoing, 0, outgoing.size)
                outgoing = ByteArray(0)
            }
            val source = next.await(pipe)
            if (source == null) {
                next = next(joinMidSong = false)
                continue
            }
            val playing = Playing(next.song, source, next.song.track.durationMs ?: 0L, next.fromMs)
            if (!crossfade) return begin(playing, pipe, ending)
            val head = ByteArray(outgoing.size)
            val got = readFully(source, head)
            val begun = begin(playing, pipe, ending)
            if (got > 0) {
                playing.decodedBytes += got
                send(pipe, Pcm.crossfade(outgoing, head.copyOf(got)), 0, minOf(outgoing.size, got))
                if (got < outgoing.size) send(pipe, outgoing, got, outgoing.size - got)
            } else {
                send(pipe, outgoing, 0, outgoing.size)
            }
            return begun
        }
        send(pipe, outgoing, 0, outgoing.size)
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
        return begin(Playing(opening.song, source, opening.song.track.durationMs ?: 0L, opening.fromMs), pipe, before)
    }

    /** [playing] goes on the air now: what's on, what was, and what the DJ says. */
    private fun begin(playing: Playing, pipe: OutputStream, before: Playing?): Playing {
        val onAir = OnAir(playing.song, onAirAt(writtenFrames) - playing.fromMs, playing.durationMs)
        val previous = current
        current = onAir
        if (openingSong?.key == playing.song.key) openingSong = null
        previous?.let { synchronized(recentSongs) { recentSongs.addLast(it); while (recentSongs.size > RECENT) recentSongs.removeFirst() } }
        dj = runCatching { programme.aired(onAir, if (before != null) previous else null, lined, onAirAt(writtenFrames)) }.getOrNull() ?: dj
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
        private const val CROSSFADE_MS = 2_500L
        private const val SKIP_FADE_MS = 1_200L
        private val CHUNK_BYTES = Pcm.bytesOf(100)
        private val SILENCE = ByteArray(Pcm.bytesOf(100))
        private const val OPEN_ATTEMPTS = 6
        private const val PREOPEN_MS = 20_000L
        private const val OPEN_TIMEOUT_MS = 20_000L
        private const val LINED_UP = 3
        private const val RECENT = 12
        private const val LISTENER_FRAMES = 3_000
        private const val AAC_FRAME_SAMPLES = 1024L
    }
}
