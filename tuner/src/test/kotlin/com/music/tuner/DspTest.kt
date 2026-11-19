package com.music.tuner

import com.music.tuner.audio.AdtsSplitter
import com.music.tuner.audio.Broadcast
import com.music.tuner.dsp.FmQuality
import com.music.tuner.dsp.FmStereoDecoder
import com.music.tuner.dsp.SignalMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** FM multiplex signals made to order, as rtl_fm would demodulate them. */
object Mpx {
    const val RATE = 171_000
    /** rtl_fm's output at full (75 kHz) deviation. */
    const val FULL = 14_000.0

    /**
     * [seconds] of MPX with [left] and [right] (functions of time, -1 to 1), a stereo pilot
     * (unless [pilot] is false) and white noise of [noise] (in rtl_fm's units).
     */
    fun make(seconds: Double, left: (Double) -> Double, right: (Double) -> Double, noise: Double = 0.0, pilot: Boolean = true, seed: Long = 1): ShortArray {
        val random = Random(seed)
        val wp = 2 * PI * 19_000
        return ShortArray((seconds * RATE).toInt()) { i ->
            val t = i.toDouble() / RATE
            val l = left(t)
            val r = right(t)
            val mpx = if (pilot) 0.45 * (l + r) + 0.45 * (l - r) * sin(2 * wp * t) + 0.1 * sin(wp * t) else 0.9 * (l + r) / 2
            (FULL * mpx + noise * random.nextGaussian()).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    fun tone(hz: Double, amplitude: Double = 0.5): (Double) -> Double = { t -> amplitude * sin(2 * PI * hz * t) }
    val silent: (Double) -> Double = { 0.0 }

    fun bytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[2 * i] = s.toInt().toByte()
            out[2 * i + 1] = (s.toInt() shr 8).toByte()
        }
        return out
    }
}

class DspTest {
    /** Decodes [mpx] and returns (left RMS, right RMS) over its second half. */
    private fun decode(mpx: ShortArray, stereo: Float = 1f, deemphasisUs: Int = 75): Pair<Double, Double> {
        val decoder = FmStereoDecoder(Mpx.RATE.toDouble(), 3, deemphasisUs * 1e-6)
        decoder.stereo = stereo
        val out = ShortArray(2 * mpx.size / 3 + 4)
        var frames = 0
        val chunk = 8_192
        val scratch = ShortArray(2 * chunk / 3 + 4)
        var at = 0
        while (at < mpx.size) {
            val n = minOf(chunk, mpx.size - at)
            val made = decoder.process(mpx.copyOfRange(at, at + n), n, scratch)
            System.arraycopy(scratch, 0, out, frames * 2, made * 2)
            frames += made
            at += n
        }
        assertEquals(mpx.size / 3, frames)
        var l = 0.0
        var r = 0.0
        val from = frames / 2
        for (f in from until frames) {
            l += out[2 * f].toDouble() * out[2 * f]
            r += out[2 * f + 1].toDouble() * out[2 * f + 1]
        }
        return sqrt(l / (frames - from)) to sqrt(r / (frames - from))
    }

    private fun db(a: Double, b: Double) = 20 * log10(a / b)

    @Test
    fun `in stereo, a tone on the left stays on the left`() {
        val (left, right) = decode(Mpx.make(1.5, Mpx.tone(1_000.0), Mpx.silent))
        assertTrue("left $left, right $right", db(left, right) > 25)
        // About 0.5 of full scale at 1 kHz, less a little de-emphasis.
        assertTrue("level $left", left > 6_000 && left < 14_000)
        val (left2, right2) = decode(Mpx.make(1.5, Mpx.silent, Mpx.tone(1_000.0)))
        assertTrue("left $left2, right $right2", db(right2, left2) > 25)
    }

    @Test
    fun `mono when stereo isn't wanted, and when there's no pilot`() {
        val (left, right) = decode(Mpx.make(1.5, Mpx.tone(1_000.0), Mpx.silent), stereo = 0f)
        assertEquals(0.0, db(left, right), 0.5)
        val (l2, r2) = decode(Mpx.make(1.5, Mpx.tone(1_000.0), Mpx.tone(1_000.0), pilot = false))
        assertEquals(0.0, db(l2, r2), 0.5)
        assertTrue(l2 > 3_000)
    }

    @Test
    fun `de-emphasis turns treble down as the transmitter turned it up (75 and 50 µs)`() {
        for (us in listOf(75, 50)) {
            val (low, _) = decode(Mpx.make(1.0, Mpx.tone(1_000.0, 0.3), Mpx.tone(1_000.0, 0.3)), deemphasisUs = us)
            val (high, _) = decode(Mpx.make(1.0, Mpx.tone(10_000.0, 0.3), Mpx.tone(10_000.0, 0.3)), deemphasisUs = us)
            val tau = us * 1e-6
            fun analog(hz: Double) = 1 / sqrt(1 + (2 * PI * hz * tau).let { it * it })
            val expected = 20 * log10(analog(10_000.0) / analog(1_000.0))
            assertEquals("$us µs", expected, db(high, low), 1.5)
        }
    }

    @Test
    fun `the signal meter hears noise in the gap above the audio, and the score follows it`() {
        fun measure(noise: Double, pilot: Boolean = true): SignalMeter {
            val meter = SignalMeter(Mpx.RATE.toDouble())
            val mpx = Mpx.make(2.0, Mpx.tone(1_000.0), Mpx.tone(440.0), noise = noise, pilot = pilot)
            meter.feed(mpx, mpx.size)
            assertTrue(meter.measurements >= 4)
            return meter
        }
        val clean = measure(5.0)
        val fair = measure(400.0)
        val static = measure(4_000.0)
        assertTrue(clean.pilot)
        assertTrue("${clean.snrDb} > ${fair.snrDb} > ${static.snrDb}", clean.snrDb!! > fair.snrDb!! && fair.snrDb!! > static.snrDb!!)
        assertTrue(FmQuality.score(clean.snrDb, 0)!! > 0.95)
        assertEquals(1f, clean.stereoWanted())
        assertTrue(FmQuality.score(fair.snrDb, null)!! in 0.3..0.8)
        assertTrue(FmQuality.score(static.snrDb, null)!! < 0.3)
        assertEquals(0f, static.stereoWanted())
        // RDS errors pull the score down.
        assertTrue(FmQuality.score(clean.snrDb, 30)!! < FmQuality.score(clean.snrDb, 0)!!)
        // A mono station: no pilot, measured against its programme.
        val mono = measure(5.0, pilot = false)
        assertTrue(!mono.pilot)
        assertTrue(FmQuality.score(mono.snrDb, null)!! > 0.9)
        assertEquals(0f, mono.stereoWanted())
    }
}

class AudioTest {
    @Test
    fun `ADTS frames are found however the bytes arrive`() {
        val frames = (1..20).map { AdtsSplitter.frame(100 + it * 7, it.toByte()) }
        val stream = byteArrayOf(1, 2, 3) + frames.reduce { a, b -> a + b }
        val got = mutableListOf<ByteArray>()
        val splitter = AdtsSplitter { got += it }
        var at = 0
        val random = Random(3)
        while (at < stream.size) {
            val n = minOf(1 + random.nextInt(90), stream.size - at)
            splitter.feed(stream.copyOfRange(at, at + n))
            at += n
        }
        // The last frame waits for the next one's header to be sure of it; all before are out.
        assertTrue(got.size >= 19)
        got.forEachIndexed { i, frame -> assertTrue(frame.contentEquals(frames[i])) }
    }

    @Test
    fun `a broadcast gives newcomers the last moments, and a slow listener doesn't hold others up`() {
        val broadcast = Broadcast(burstFrames = 3, listenerCapacity = 5)
        repeat(5) { broadcast.publish(byteArrayOf(it.toByte())) }
        val late = broadcast.subscribe()
        assertEquals(listOf(2, 3, 4), (0 until 3).map { late.frames.tryReceive().getOrThrow()[0].toInt() })
        repeat(20) { broadcast.publish(byteArrayOf(it.toByte())) }
        assertTrue(late.dropped > 0)
        broadcast.unsubscribe(late)
        assertEquals(0, broadcast.listenerCount)
    }
}
