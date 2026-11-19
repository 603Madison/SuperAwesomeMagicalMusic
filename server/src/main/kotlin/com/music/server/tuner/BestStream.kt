package com.music.server.tuner

import com.music.player.CoreLog
import com.music.server.music.LiveProxy
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Which copy of a station plays: the local tuner's or the internet stream's. */
enum class Via { TUNER, INTERNET }

/**
 * Chooses between the tuner and the internet for a station both have: the tuner while it's
 * clear, the internet while it's static, with patience both ways so a passing bus or a moment
 * of multipath doesn't flip it back and forth. A tuner that stops (or is busy) means the
 * internet straight away.
 */
class Chooser(
    private val clearAbove: Double = 0.6,
    private val staticBelow: Double = 0.35,
    /** How long the tuner must be static before going to the internet. */
    private val toInternetMs: Long = 6_000,
    /** How long the tuner must be clear before going back to it. */
    private val toTunerMs: Long = 20_000,
) {
    var via: Via = Via.TUNER
        private set
    private var since: Long? = null

    /** The first choice: the tuner unless it's known to be static (or there's no tuner). */
    fun start(score: Double?, tunerAvailable: Boolean, internetAvailable: Boolean): Via {
        via = when {
            !internetAvailable -> Via.TUNER
            !tunerAvailable -> Via.INTERNET
            score != null && score < staticBelow -> Via.INTERNET
            else -> Via.TUNER
        }
        since = null
        return via
    }

    fun update(now: Long, score: Double?, tunerAlive: Boolean, internetAvailable: Boolean): Via {
        when (via) {
            Via.TUNER -> when {
                !internetAvailable -> since = null
                !tunerAlive -> switch(Via.INTERNET)
                score != null && score < staticBelow -> {
                    val from = since ?: now.also { since = it }
                    if (now - from >= toInternetMs) switch(Via.INTERNET)
                }
                else -> since = null
            }
            Via.INTERNET -> when {
                !internetAvailable && tunerAlive -> switch(Via.TUNER)
                tunerAlive && score != null && score >= clearAbove -> {
                    val from = since ?: now.also { since = it }
                    if (now - from >= toTunerMs) switch(Via.TUNER)
                }
                else -> since = null
            }
        }
        return via
    }

    private fun switch(to: Via) {
        via = to
        since = null
    }
}

/** Raw audio (16-bit stereo at [Blend.RATE]) waiting to be played: a bounded ring that drops the oldest. */
class PcmBuffer(private val capacity: Int = Blend.bytesOf(4_000)) {
    private val data = ByteArray(capacity)
    private var start = 0
    private var length = 0

    val size: Int @Synchronized get() = length

    @Synchronized
    fun write(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        var off = offset
        var n = count
        if (n > capacity) {
            off += n - capacity
            n = capacity
        }
        // Whole frames go, so left and right stay where they belong.
        val overflow = length + n - capacity
        if (overflow > 0) drop(minOf(length, (overflow + 3) and 3.inv()))
        var end = (start + length) % capacity
        var left = n
        while (left > 0) {
            val chunk = minOf(left, capacity - end)
            System.arraycopy(bytes, off, data, end, chunk)
            off += chunk
            left -= chunk
            end = (end + chunk) % capacity
        }
        length += n
    }

    /** Up to [count] bytes into [into]; how many there were. */
    @Synchronized
    fun read(into: ByteArray, offset: Int = 0, count: Int = into.size): Int {
        val n = minOf(count, length) and 3.inv()
        var done = 0
        while (done < n) {
            val chunk = minOf(n - done, capacity - start)
            System.arraycopy(data, start, into, offset + done, chunk)
            done += chunk
            start = (start + chunk) % capacity
        }
        length -= n
        return n
    }

    /** Throws away all but the newest [keep] bytes (catching up on a backlog). */
    @Synchronized
    fun keepNewest(keep: Int) {
        if (length > keep) drop((length - keep) and 3.inv())
    }

    private fun drop(n: Int) {
        start = (start + n) % capacity
        length -= n
    }
}

/** One copy of a station, decoded to raw audio as it arrives. */
interface PcmInput : AutoCloseable {
    val buffer: PcmBuffer

    /** Still connected (or connecting): false once it ended or failed. */
    val alive: Boolean
}

/** Where the blended audio goes: an encoder. */
interface PcmSink : AutoCloseable {
    fun write(pcm: ByteArray)
}

/** A stream opened for decoding: its bytes, and (for Icecast stations) how often song titles interrupt them. */
class Opened(val stream: InputStream, val icyInterval: Int? = null, val close: () -> Unit = { stream.close() })

/**
 * A stream decoded by ffmpeg into a [PcmBuffer]: [open] runs on its own thread (it may wait on
 * the network), the bytes are fed to ffmpeg, and its output fills the buffer.
 */
class FfmpegDecoder(
    private val name: String,
    private val ffmpeg: String,
    private val open: () -> Opened,
    private val onTitle: (String) -> Unit = {},
) : PcmInput {
    override val buffer = PcmBuffer()

    @Volatile
    private var ended = false

    @Volatile
    private var opened: Opened? = null
    private var process: Process? = null

    override val alive: Boolean get() = !ended

    fun start(): FfmpegDecoder {
        Thread({ run() }, "decode-$name").apply { isDaemon = true; start() }
        return this
    }

    private fun run() {
        try {
            val source = open()
            opened = source
            if (ended) return source.close()
            // A live stream: start decoding after a glance at it, rather than ffmpeg's usual five seconds.
            val p = ProcessBuilder(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-probesize", "65536", "-analyzeduration", "500000", "-fflags", "nobuffer",
                "-i", "pipe:0", "-vn", "-f", "s16le", "-ar", Blend.RATE.toString(), "-ac", "2", "pipe:1",
            )
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process = p
            Thread({
                try {
                    p.outputStream.use { out ->
                        if (source.icyInterval == null) source.stream.copyTo(out, 16 * 1024)
                        else LiveProxy.copyWithoutMetadata(source.stream, out, source.icyInterval, onTitle)
                    }
                } catch (e: IOException) {
                    // The stream or ffmpeg stopped.
                } finally {
                    runCatching { source.close() }
                }
            }, "decode-$name-feed").apply { isDaemon = true; start() }
            val bytes = ByteArray(Blend.bytesOf(Blend.BLOCK_MS))
            p.inputStream.use { input ->
                while (!ended) {
                    val read = input.read(bytes)
                    if (read < 0) break
                    buffer.write(bytes, 0, read)
                }
            }
            if (!ended) CoreLog.i(TAG, "$name: the stream ended")
        } catch (e: Exception) {
            if (!ended) CoreLog.i(TAG, "$name: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            ended = true
            close()
        }
    }

    override fun close() {
        ended = true
        opened?.let { runCatching { it.close() } }
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    private companion object {
        const val TAG = "BestStream"
    }
}

/** ffmpeg encoding raw audio to AAC (ADTS), its output going to [onBytes]. */
class FfmpegEncoder(ffmpeg: String, kbps: Int, private val onBytes: (ByteArray) -> Unit) : PcmSink {
    private val process = ProcessBuilder(
        ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "s16le", "-ar", Blend.RATE.toString(), "-ac", "2", "-i", "pipe:0",
        "-c:a", "aac", "-b:a", "${kbps}k", "-f", "adts", "-flush_packets", "1", "pipe:1",
    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val input: OutputStream = process.outputStream

    init {
        Thread({
            val bytes = ByteArray(8 * 1024)
            try {
                process.inputStream.use { out ->
                    while (true) {
                        val read = out.read(bytes)
                        if (read < 0) break
                        if (read > 0) onBytes(bytes.copyOf(read))
                    }
                }
            } catch (e: IOException) {
                // Stopped.
            }
        }, "encode").apply { isDaemon = true; start() }
    }

    override fun write(pcm: ByteArray) {
        input.write(pcm)
        input.flush()
    }

    override fun close() {
        runCatching { input.close() }
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

/** A listener of a [Blend]: the encoded audio, as it comes. */
class BlendListener {
    val chunks = Channel<ByteArray>(512)
}

/**
 * A station that's both on the tuner and on the internet, played as one stream: the copy that
 * sounds best (see [Chooser]) goes out, and when that changes it crossfades to the other over
 * [fadeMs]. The internet copy is only fetched while it's needed (or about to be); the tuner's
 * stays connected while the internet plays, so the server knows when it clears up.
 *
 * The output is paced by the clock ([step] every [BLOCK_MS]): a copy that stalls means a moment
 * of silence, not a stalled stream, while the other takes over.
 */
class Blend(
    val name: String,
    private val openTuner: (() -> PcmInput)?,
    private val openInternet: (() -> PcmInput)?,
    /** The tuner's latest signal score for the station (null when unknown). */
    private val tunerScore: () -> Double?,
    private val sink: PcmSink,
    private val chooser: Chooser = Chooser(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val fadeMs: Long = 2_000,
) : AutoCloseable {
    private var tuner: PcmInput? = null
    private var internet: PcmInput? = null
    private var tunerRetryAt = 0L
    private var internetRetryAt = 0L
    private var internetFailures = 0
    private var waitingSince: Long? = null
    private var fadeTo: Via? = null
    private var fadeDone = 0
    private var lastAudioAt = 0L

    @Volatile
    var via: Via = Via.TUNER
        private set

    @Volatile
    private var running = false
    private val listeners = CopyOnWriteArrayList<BlendListener>()
    private val recent = ArrayDeque<ByteArray>()

    /** When the last listener left (null while someone listens). */
    @Volatile
    var idleSince: Long? = clock()
        private set

    /** Opens the copy to start with. */
    fun begin(): Via {
        val score = tunerScore()
        via = chooser.start(score, openTuner != null, openInternet != null)
        if (via == Via.TUNER) tuner = openTuner?.let { runCatching(it).getOrNull() }
        if (via == Via.INTERNET || tuner == null) {
            internet = openInternet?.let { runCatching(it).getOrNull() }
            if (internet != null) {
                via = Via.INTERNET
                chooser.start(0.0, tunerAvailable = false, internetAvailable = true)
            }
        }
        // On the internet, the tuner is connected too (by [step]), to know when it's clear again.
        lastAudioAt = clock()
        return via
    }

    /** Waits until the copy chosen has a moment of audio ready (or [timeoutMs] passes). */
    fun prebuffer(timeoutMs: Long = 8_000): Boolean {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < until) {
            val input = current()
            if (input != null && input.buffer.size >= bytesOf(PREBUFFER_MS)) return true
            if (input == null || !input.alive) {
                // The first choice couldn't start: the other, if there is one.
                if (via == Via.TUNER && openInternet != null) {
                    internet = internet ?: runCatching(openInternet).getOrNull()
                    via = Via.INTERNET
                    chooser.start(0.0, tunerAvailable = false, internetAvailable = internet != null)
                } else if (via == Via.INTERNET && tuner?.alive == true) {
                    via = Via.TUNER
                    chooser.start(null, tunerAvailable = true, internetAvailable = false)
                } else if (input == null || !input.alive) {
                    return false
                }
            }
            Thread.sleep(50)
        }
        return current()?.buffer?.size?.let { it > 0 } == true
    }

    private fun current(): PcmInput? = if (via == Via.TUNER) tuner else internet

    private fun input(v: Via): PcmInput? = if (v == Via.TUNER) tuner else internet

    /** Makes the next [BLOCK_MS] of audio and sends it to the encoder. */
    fun step(): ByteArray {
        val now = clock()
        if (tuner?.alive == false) {
            tuner?.close()
            tuner = null
            tunerRetryAt = now + TUNER_RETRY_MS
        }
        if (internet?.alive == false) {
            internet?.close()
            internet = null
            internetFailed(now)
        }
        // A tuner that stopped (or was busy) is tried again now and then.
        if (tuner == null && openTuner != null && now >= tunerRetryAt) {
            tunerRetryAt = now + TUNER_RETRY_MS
            tuner = runCatching(openTuner).getOrNull()
        }
        // An internet stream that failed is tried again later, waiting longer each time.
        val internetAvailable = openInternet != null && (internet != null || now >= internetRetryAt)
        val wanted = chooser.update(now, tunerScore(), tuner?.alive == true, internetAvailable)

        if (fadeTo == null && wanted != via) {
            val target = input(wanted) ?: run {
                if (wanted == Via.INTERNET && internetAvailable) internet = openInternet?.let { runCatching(it).getOrNull() }
                input(wanted)
            }
            val since = waitingSince ?: now.also { waitingSince = it }
            when {
                target != null && target.buffer.size >= bytesOf(PREBUFFER_MS) -> {
                    fadeTo = wanted
                    fadeDone = 0
                    waitingSince = null
                    CoreLog.i(TAG, "$name: ${if (wanted == Via.TUNER) "the tuner is clear again" else "the tuner is static"}, switching to the ${wanted.name.lowercase()}")
                }
                now - since > SWITCH_TIMEOUT_MS -> {
                    // It never came: stay put.
                    waitingSince = null
                    if (wanted == Via.INTERNET) {
                        internet?.close()
                        internet = null
                        internetFailed(now)
                    }
                }
            }
        } else if (fadeTo == null) {
            waitingSince = null
        }

        val block = ByteArray(bytesOf(BLOCK_MS))
        current()?.let { readInto(it, block) }
        val target = fadeTo?.let(::input)
        if (fadeTo != null) {
            val incoming = ByteArray(block.size)
            target?.let { readInto(it, incoming) }
            val total = (fadeMs / BLOCK_MS).toInt().coerceAtLeast(1)
            mix(block, incoming, fadeDone, total)
            fadeDone++
            if (fadeDone >= total || target == null) {
                val from = via
                via = fadeTo!!
                fadeTo = null
                if (via == Via.INTERNET) internetFailures = 0
                // The internet copy is only fetched while it's needed.
                if (from == Via.INTERNET) {
                    internet?.close()
                    internet = null
                }
            }
        }
        // The copies not playing are kept fresh (the newest moment ready), not piling up.
        for (v in Via.entries) {
            if (v == via || v == fadeTo) continue
            input(v)?.buffer?.keepNewest(bytesOf(PREBUFFER_MS * 2))
        }
        // A backlog (a burst at the start, a clock running fast) is caught up on.
        current()?.buffer?.let { if (it.size > bytesOf(MAX_BACKLOG_MS)) it.keepNewest(bytesOf(PREBUFFER_MS)) }
        sink.write(block)
        return block
    }

    private fun internetFailed(now: Long) {
        internetFailures++
        internetRetryAt = now + minOf(INTERNET_RETRY_MAX_MS, INTERNET_RETRY_MS shl minOf(internetFailures - 1, 4))
    }

    private fun readInto(input: PcmInput, block: ByteArray) {
        val got = input.buffer.read(block)
        if (got > 0) lastAudioAt = clock()
        // Not enough: silence for the rest (a gap, not a stall).
        if (got < block.size) java.util.Arrays.fill(block, got, block.size, 0)
    }

    /** Runs [step] every [BLOCK_MS] on its own thread until [close]. */
    fun run() {
        running = true
        Thread({
            var next = System.nanoTime()
            try {
                while (running) {
                    step()
                    next += BLOCK_MS * 1_000_000
                    val wait = next - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    // Far behind (the machine was busy): start the clock again rather than rush.
                    else if (wait < -500_000_000) next = System.nanoTime()
                    // Neither copy has sent anything for a while: the station's gone.
                    if (clock() - lastAudioAt > SILENT_LIMIT_MS) {
                        CoreLog.w(TAG, "$name: nothing from the tuner or the internet for ${SILENT_LIMIT_MS / 1000} s; stopping")
                        break
                    }
                }
            } catch (e: InterruptedException) {
                // Stopped.
            } catch (e: IOException) {
                CoreLog.w(TAG, "$name: the encoder stopped: ${e.message}")
            } catch (e: Exception) {
                CoreLog.e(TAG, "$name stopped", e)
            } finally {
                CoreLog.i(TAG, "$name: off the air")
                running = false
                listeners.forEach { it.chunks.close() }
            }
        }, "blend-$name").apply { isDaemon = true; start() }
    }

    val isRunning: Boolean get() = running

    // ---- Listeners ----

    fun publish(bytes: ByteArray) {
        synchronized(recent) {
            recent.addLast(bytes)
            while (recent.size > BURST_CHUNKS) recent.removeFirst()
        }
        for (listener in listeners) listener.chunks.trySend(bytes)
    }

    fun subscribe(): BlendListener = synchronized(recent) {
        val listener = BlendListener()
        recent.forEach { listener.chunks.trySend(it) }
        listeners += listener
        idleSince = null
        listener
    }

    fun unsubscribe(listener: BlendListener) {
        listeners -= listener
        listener.chunks.close()
        if (listeners.isEmpty()) idleSince = clock()
    }

    override fun close() {
        running = false
        tuner?.close()
        internet?.close()
        runCatching { sink.close() }
        listeners.forEach { it.chunks.close() }
    }

    companion object {
        private const val TAG = "BestStream"
        const val RATE = 48_000
        const val BLOCK_MS = 20L
        const val PREBUFFER_MS = 500L
        const val MAX_BACKLOG_MS = 2_500L
        const val SWITCH_TIMEOUT_MS = 15_000L
        const val TUNER_RETRY_MS = 60_000L
        const val INTERNET_RETRY_MS = 5_000L
        const val INTERNET_RETRY_MAX_MS = 60_000L
        const val SILENT_LIMIT_MS = 30_000L
        const val BURST_CHUNKS = 24

        fun bytesOf(ms: Long): Int = (RATE * ms / 1000).toInt() * 4

        /** Crossfades [incoming] into [outgoing] (in place), for step [index] of [total]. */
        fun mix(outgoing: ByteArray, incoming: ByteArray, index: Int, total: Int) {
            val samples = outgoing.size / 2
            val frames = samples / 2
            for (f in 0 until frames) {
                // Equal-power, so the level holds through the middle.
                val t = ((index + f.toDouble() / frames) / total).coerceIn(0.0, 1.0)
                val gainIn = kotlin.math.sin(t * Math.PI / 2)
                val gainOut = kotlin.math.cos(t * Math.PI / 2)
                for (c in 0..1) {
                    val i = (2 * f + c) * 2
                    val a = ((outgoing[i].toInt() and 0xFF) or (outgoing[i + 1].toInt() shl 8)).toShort()
                    val b = ((incoming[i].toInt() and 0xFF) or (incoming[i + 1].toInt() shl 8)).toShort()
                    val v = (a * gainOut + b * gainIn).toInt().coerceIn(-32768, 32767)
                    outgoing[i] = v.toByte()
                    outgoing[i + 1] = (v shr 8).toByte()
                }
            }
        }
    }
}
