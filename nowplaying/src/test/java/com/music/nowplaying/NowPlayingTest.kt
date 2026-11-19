package com.music.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class NowPlayingTest {
    private val rate = 11_025

    private fun chord(seconds: Double, level: Float = 0.2f) = FloatArray((rate * seconds).toInt()) { i ->
        val t = i.toDouble() / rate
        (level * (sin(2 * PI * 440 * t) + 0.6 * sin(2 * PI * 554.4 * t) + 0.5 * sin(2 * PI * 659.3 * t)) / 2.1).toFloat()
    }

    private fun noise(seconds: Double, level: Float = 0.2f, random: Random = Random(1)) =
        FloatArray((rate * seconds).toInt()) { (random.nextFloat() * 2 - 1) * level }

    @Test
    fun `music is worth recognising, and quiet and noise are not`() {
        assertTrue(Sound.worthRecognising(chord(3.0), rate))
        // Music in a noisy room still is.
        val busy = chord(3.0).zip(noise(3.0, 0.03f)) { a, b -> a + b }.toFloatArray()
        assertTrue(Sound.worthRecognising(busy, rate))
        assertFalse("a quiet room", Sound.worthRecognising(chord(3.0, 0.0005f), rate))
        assertFalse("hiss", Sound.worthRecognising(noise(3.0), rate))
        assertFalse("nothing", Sound.worthRecognising(FloatArray(0), rate))
    }

    @Test
    fun `it waits until a song recognised should be over, and longer while nothing is`() {
        val pacing = Pacing(everyMs = 60_000, maxMs = 300_000)
        val now = 1_000_000L
        val song = Recognition("Song", "Artist", positionMs = 30_000, durationMs = 200_000, at = now)
        assertEquals(180_000L, pacing.recognised(song, now))
        // Near its end: the usual minute.
        assertEquals(60_000L, pacing.recognised(song.copy(positionMs = 195_000), now))
        // Not known how long: the usual minute.
        assertEquals(60_000L, pacing.recognised(song.copy(durationMs = null), now))
        assertEquals(60_000L, pacing.missed())
        assertEquals(120_000L, pacing.missed())
        assertEquals(240_000L, pacing.missed())
        assertEquals(300_000L, pacing.missed())
        assertEquals(60_000L, pacing.quiet())
    }

    @Test
    fun `the same song is the same whatever its case`() {
        assertTrue(Recognition("Hello", "Adele").sameSong(Recognition("hello", "ADELE")))
        assertFalse(Recognition("Hello", "Adele").sameSong(Recognition("Hello", "Lionel Richie")))
    }
}
