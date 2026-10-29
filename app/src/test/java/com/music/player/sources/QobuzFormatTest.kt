package com.music.player.sources

import com.music.player.quality.StreamQuality
import com.music.player.sources.qobuz.QobuzFormat
import com.music.player.sources.qobuz.QobuzSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QobuzFormatTest {
    @Test
    fun `original requests the highest hi-res format`() {
        assertEquals(QobuzFormat.FLAC_HIRES_192, QobuzFormat.choose(StreamQuality.Original).tier)
    }

    @Test
    fun `320 kbps cap uses MP3 320`() {
        val choice = QobuzFormat.choose(StreamQuality.Capped(320))
        assertEquals(QobuzFormat.MP3_320, choice.tier)
        assertTrue(choice.withinCap)
    }

    @Test
    fun `caps below 320 still get MP3 320 but are flagged as over the cap`() {
        val choice = QobuzFormat.choose(StreamQuality.Capped(64))
        assertEquals(5, choice.tier.formatId)
        assertFalse(choice.withinCap)
    }

    @Test
    fun `request signature matches Qobuz's scheme`() {
        // md5("trackgetFileUrlformat_id5intentstreamtrack_id123451700000000secret")
        assertEquals(
            QobuzSource.md5("trackgetFileUrlformat_id5intentstreamtrack_id123451700000000secret"),
            QobuzSource.signature("12345", 5, "1700000000", "secret"),
        )
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", QobuzSource.md5("password"))
    }
}
