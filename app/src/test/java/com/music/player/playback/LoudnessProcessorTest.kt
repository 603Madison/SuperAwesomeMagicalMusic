package com.music.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class LoudnessProcessorTest {
    private val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    private fun processor() = LoudnessProcessor().apply {
        configure(format)
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    /** [seconds] of a stereo sine at [amplitude], fed in 100 ms blocks; returns the RMS (dBFS) of the last second out. */
    private fun play(processor: LoudnessProcessor, amplitude: Double, seconds: Int): Double {
        val frames = 4_410
        var lastSecond = DoubleArray(0)
        var t = 0
        repeat(seconds * 10) { block ->
            val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
            repeat(frames) {
                val value = (amplitude * sin(2 * PI * 440 * t++ / 44_100) * 32767).toInt().toShort()
                input.putShort(value)
                input.putShort(value)
            }
            input.flip()
            processor.queueInput(input)
            val out = processor.output
            val samples = DoubleArray(out.remaining() / 2) { out.short / 32768.0 }
            if (block >= seconds * 10 - 10) lastSecond += samples
        }
        return 20 * log10(sqrt(lastSecond.sumOf { it * it } / lastSecond.size))
    }

    @Test
    fun `a loud song is brought down and a quiet one up, to the same level`() {
        val loud = play(processor(), amplitude = 0.9, seconds = 40)
        val quiet = play(processor(), amplitude = 0.1, seconds = 40)
        assertEquals(LoudnessProcessor.TARGET_DB, loud, 1.0)
        // Quiet music is lifted, but only by up to MAX_GAIN_DB.
        val quietIn = 20 * log10(0.1 / sqrt(2.0))
        assertEquals(quietIn + LoudnessProcessor.MAX_GAIN_DB, quiet, 1.0)
    }

    @Test
    fun `gain changes gently rather than jumping`() {
        val processor = processor()
        play(processor, amplitude = 0.9, seconds = 1)
        // After a second of loud music, at most ~2 dB has been taken off.
        assertTrue(processor.currentGainDb > -2.5)
    }

    @Test
    fun `a known gain applies at once`() {
        val processor = processor().apply { knownGainDb = -8f }
        play(processor, amplitude = 0.5, seconds = 1)
        assertEquals(-8.0 + LoudnessProcessor.KNOWN_GAIN_OFFSET_DB, processor.currentGainDb, 0.01)
    }

    @Test
    fun `off leaves audio untouched, and boosts never clip`() {
        val off = processor().apply { enabled = false }
        val out = play(off, amplitude = 0.5, seconds = 2)
        assertEquals(20 * log10(0.5 / sqrt(2.0)), out, 0.1)
        assertTrue(abs(LoudnessProcessor.limit(3.0)) <= 1.0)
        assertEquals(0.5, LoudnessProcessor.limit(0.5), 0.0)
    }
}
