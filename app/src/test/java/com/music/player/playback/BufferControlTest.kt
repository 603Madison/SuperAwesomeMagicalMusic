package com.music.player.playback

import com.music.player.playback.BufferControl.Companion.keepLoading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferControlTest {
    private val tenMinutes = 600_000L

    @Test
    fun `loads until the buffer is full, then waits until a quarter has played`() {
        assertTrue(keepLoading(isLoading = false, bufferedMs = 0, targetMs = tenMinutes, memoryFull = false))
        assertFalse(keepLoading(isLoading = true, bufferedMs = tenMinutes, targetMs = tenMinutes, memoryFull = false))
        // Between three quarters and full, loading carries on in whichever state it was.
        assertTrue(keepLoading(isLoading = true, bufferedMs = 500_000, targetMs = tenMinutes, memoryFull = false))
        assertFalse(keepLoading(isLoading = false, bufferedMs = 500_000, targetMs = tenMinutes, memoryFull = false))
        assertTrue(keepLoading(isLoading = false, bufferedMs = 449_000, targetMs = tenMinutes, memoryFull = false))
    }

    @Test
    fun `stops when the memory limit is reached`() {
        assertFalse(keepLoading(isLoading = true, bufferedMs = 60_000, targetMs = tenMinutes, memoryFull = true))
    }

    @Test
    fun `a new, shorter setting takes effect straight away`() {
        assertFalse(keepLoading(isLoading = true, bufferedMs = 120_000, targetMs = 60_000, memoryFull = false))
    }

    @Test
    fun `short buffers still refill early enough`() {
        // 30 s buffer: refills below 22.5 s.
        assertTrue(keepLoading(isLoading = false, bufferedMs = 22_000, targetMs = 30_000, memoryFull = false))
        assertEquals(false, keepLoading(isLoading = false, bufferedMs = 25_000, targetMs = 30_000, memoryFull = false))
    }

    @Test
    fun `codec names are readable`() {
        assertEquals("FLAC", AudioFormatInfo.codecName("audio/flac"))
        assertEquals("Opus", AudioFormatInfo.codecName("audio/opus"))
        assertEquals("AAC", AudioFormatInfo.codecName("audio/mp4a-latm"))
        assertEquals("MP3", AudioFormatInfo.codecName("audio/mpeg"))
        assertEquals("X-WEIRD", AudioFormatInfo.codecName("audio/x-weird"))
    }
}
