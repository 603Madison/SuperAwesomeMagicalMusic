package com.music.player.sources

import com.music.player.quality.StreamQuality
import com.music.player.settings.TranscodeCodec
import com.music.player.sources.jellyfin.JellyfinPlan
import com.music.player.sources.jellyfin.JellyfinStreamPlanner
import com.music.player.sources.jellyfin.JfMediaSource
import com.music.player.sources.jellyfin.JfMediaStream
import org.junit.Assert.assertEquals
import org.junit.Test

class JellyfinStreamPlannerTest {
    private fun source(codec: String, bitrate: Long) =
        JfMediaSource(id = "ms", container = codec, bitrate = bitrate, mediaStreams = listOf(JfMediaStream("Audio", codec, bitrate)))

    @Test
    fun `original plays the file as is`() {
        assertEquals(JellyfinPlan.Direct("flac"), JellyfinStreamPlanner.plan(source("flac", 900_000), StreamQuality.Original, TranscodeCodec.MP3))
    }

    @Test
    fun `original transcodes codecs Android can't decode to lossless FLAC`() {
        assertEquals(
            JellyfinPlan.Transcode("flac", "flac", null),
            JellyfinStreamPlanner.plan(source("alac", 900_000), StreamQuality.Original, TranscodeCodec.MP3),
        )
    }

    @Test
    fun `files above the cap are transcoded to exactly the cap`() {
        assertEquals(
            JellyfinPlan.Transcode("mp3", "mp3", 96),
            JellyfinStreamPlanner.plan(source("flac", 900_000), StreamQuality.Capped(96), TranscodeCodec.MP3),
        )
        assertEquals(
            JellyfinPlan.Transcode("aac", "aac", 64),
            JellyfinStreamPlanner.plan(source("mp3", 320_000), StreamQuality.Capped(64), TranscodeCodec.AAC),
        )
    }

    @Test
    fun `files already under the cap are not re-encoded`() {
        assertEquals(JellyfinPlan.Direct("mp3"), JellyfinStreamPlanner.plan(source("mp3", 128_000), StreamQuality.Capped(192), TranscodeCodec.MP3))
        // Container overhead can push a 320 kbps MP3 slightly over 320.
        assertEquals(JellyfinPlan.Direct("mp3"), JellyfinStreamPlanner.plan(source("mp3", 327_000), StreamQuality.Capped(320), TranscodeCodec.MP3))
    }

    @Test
    fun `unknown bitrate is transcoded when capped`() {
        val noBitrate = JfMediaSource(id = "ms", mediaStreams = listOf(JfMediaStream("Audio", "mp3", null)))
        assertEquals(
            JellyfinPlan.Transcode("mp3", "mp3", 128),
            JellyfinStreamPlanner.plan(noBitrate, StreamQuality.Capped(128), TranscodeCodec.MP3),
        )
    }
}
