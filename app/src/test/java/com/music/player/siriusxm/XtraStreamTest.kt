package com.music.player.siriusxm

import com.music.player.sources.siriusxm.SxmCut
import com.music.player.sources.siriusxm.XtraStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtraStreamTest {
    private fun playlist(track: String, segments: Int, ended: Boolean = true, key: String? = null) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-TARGETDURATION:10")
        appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        key?.let { appendLine(it) }
        repeat(segments) { appendLine("#EXTINF:10.0,"); appendLine("${track}_$it.aac?token=abc") }
        if (ended) appendLine("#EXT-X-ENDLIST")
    }

    private fun cut(id: String) = SxmCut(id, id, "Artist", null, 0, 0, null, interstitial = false)

    @Test
    fun `segments are stamped on one clock and the playlist starts at the newest track`() {
        val stream = XtraStream(startTimeMs = 0)
        stream.append("a", "https://cdn/a/media.m3u8", playlist("a", 3), cut("a"), nowMs = 0)
        stream.append("b", "https://cdn/b/media.m3u8", playlist("b", 2), cut("b"), nowMs = 0)
        val lines = stream.render().lines()
        assertTrue(lines.contains("#EXT-X-START:TIME-OFFSET=30.000,PRECISE=YES"))
        assertTrue(lines.contains("#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:00:30.000Z"))
        assertTrue(lines.contains("https://cdn/b/b_0.aac?token=abc"))
        assertEquals("a", stream.cutAt(29_000)?.id)
        assertEquals("b", stream.cutAt(30_000)?.id)
        assertEquals(20_000L, stream.cutAt(30_000)?.durationMs)
        assertNull(stream.cutAt(60_000))
    }

    @Test
    fun `a growing track only adds new segments`() {
        val stream = XtraStream(startTimeMs = 0)
        assertEquals(2, stream.append("a", "https://cdn/a/media.m3u8", playlist("a", 2, ended = false), null, 0))
        assertEquals(1, stream.append("a", "https://cdn/a/media.m3u8", playlist("a", 3), null, 0))
        assertEquals(true, stream.lastTrack?.complete)
        assertEquals(30_000L, stream.lastTrack?.durationMs)
    }

    @Test
    fun `old segments are dropped with their discontinuities counted`() {
        val stream = XtraStream(startTimeMs = 0, maxSegments = 2)
        stream.append("a", "https://cdn/a/m.m3u8", playlist("a", 2), null, 0)
        stream.append("b", "https://cdn/b/m.m3u8", playlist("b", 2), null, 0)
        stream.append("c", "https://cdn/c/m.m3u8", playlist("c", 1), null, 0)
        val lines = stream.render().lines()
        assertTrue(lines.contains("#EXT-X-MEDIA-SEQUENCE:3"))
        // b_0 started a track (a discontinuity) and was dropped.
        assertTrue(lines.contains("#EXT-X-DISCONTINUITY-SEQUENCE:1"))
        assertEquals(listOf("https://cdn/b/b_1.aac?token=abc", "https://cdn/c/c_0.aac?token=abc"), lines.filter { it.startsWith("https://") })
    }

    @Test
    fun `keys without an IV keep the one HLS would have derived`() {
        val key = "#EXT-X-KEY:METHOD=AES-128,URI=\"sxmkey://k\""
        assertEquals("$key,IV=0x0000000000000000000000000000002a", XtraStream.withIv(key, 42))
        assertEquals("$key,IV=0x01", XtraStream.withIv("$key,IV=0x01", 42))
        assertEquals("#EXT-X-KEY:METHOD=NONE", XtraStream.withIv("#EXT-X-KEY:METHOD=NONE", 42))
    }
}
