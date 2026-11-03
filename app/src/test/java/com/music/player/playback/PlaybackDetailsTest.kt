package com.music.player.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackDetailsTest {
    @Test
    fun namesAacProfiles() {
        assertEquals("AAC-LC", PlaybackDetails.codecName("mp4a.40.2", MimeTypes.AUDIO_AAC))
        assertEquals("HE-AAC", PlaybackDetails.codecName("mp4a.40.5", MimeTypes.AUDIO_AAC))
        assertEquals("HE-AAC v2", PlaybackDetails.codecName("mp4a.40.29", MimeTypes.AUDIO_AAC))
        assertEquals("AAC", PlaybackDetails.codecName(null, MimeTypes.AUDIO_AAC))
        assertEquals("Opus", PlaybackDetails.codecName("opus", MimeTypes.AUDIO_OPUS))
    }

    @Test
    fun heAacReportsTheFullSampleRate() {
        val details = PlaybackDetails()
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setCodecs("mp4a.40.5")
            .setSampleRate(22_050)
            .setChannelCount(2)
            .build()
        details.onFormat("song", format)
        val result = details.state.value.getValue("song")
        assertEquals("HE-AAC", result.codec)
        assertEquals(44_100, result.sampleRateHz)
        assertEquals(2, result.channels)
        assertNull(result.declaredKbps)
    }

    @Test
    fun measuresBitrateFromSegments() {
        val details = PlaybackDetails()
        // 10 s segments of 320 kbps audio: 400,000 bytes each.
        repeat(3) { details.onSegment("song", 400_000, 10_000) }
        assertEquals(320, details.state.value.getValue("song").measuredKbps)
        // Ignores empty loads.
        details.onSegment("song", 0, 10_000)
        assertEquals(320, details.state.value.getValue("song").measuredKbps)
    }

    @Test
    fun `aac decoded to twice its declared rate is he-aac with implicit sbr`() {
        val details = PlaybackDetails()
        // A Pandora station track: an MP4 declaring AAC-LC at 22.05 kHz.
        val lc = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setCodecs("mp4a.40.2")
            .setSampleRate(22_050)
            .setChannelCount(2)
            .build()
        details.onFormat("track-1", lc)
        assertEquals("AAC-LC", details.state.value.getValue("track-1").codec)
        details.onOutput("track-1", 44_100)
        val first = details.state.value.getValue("track-1")
        assertEquals("HE-AAC", first.codec)
        assertEquals(44_100, first.sampleRateHz)
        assertEquals(true, first.implicitSbr)

        // The next track of the same kind is known for what it is straight away.
        details.onFormat("track-2", lc)
        assertEquals("HE-AAC", details.state.value.getValue("track-2").codec)
        assertEquals(44_100, details.state.value.getValue("track-2").sampleRateHz)

        // Real AAC-LC played at its own rate stays AAC-LC.
        val plain = PlaybackDetails()
        plain.onFormat("song", lc.buildUpon().setSampleRate(44_100).build())
        plain.onOutput("song", 44_100)
        assertEquals("AAC-LC", plain.state.value.getValue("song").codec)
    }
}
