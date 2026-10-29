package com.music.player.quality

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamQualityTest {
    @Test
    fun `stored values round trip`() {
        assertEquals(StreamQuality.Original, StreamQuality.fromStored(StreamQuality.Original.toStored()))
        StreamQuality.STEPS.forEach { kbps ->
            assertEquals(StreamQuality.Capped(kbps), StreamQuality.fromStored(StreamQuality.Capped(kbps).toStored()))
        }
    }

    @Test
    fun `odd stored values round down to a slider step`() {
        assertEquals(StreamQuality.Capped(192), StreamQuality.fromStored(200))
        assertEquals(StreamQuality.Capped(320), StreamQuality.fromStored(999))
        assertEquals(StreamQuality.Capped(64), StreamQuality.fromStored(10))
    }

    @Test
    fun `steps span 64 to 320 kbps`() {
        assertEquals(64, StreamQuality.STEPS.first())
        assertEquals(320, StreamQuality.STEPS.last())
    }
}
