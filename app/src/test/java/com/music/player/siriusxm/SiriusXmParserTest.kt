package com.music.player.siriusxm

import com.music.player.net.AppJson
import com.music.player.sources.siriusxm.SiriusXmParser
import com.music.player.sources.siriusxm.SxmChannel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SiriusXmParserTest {
    private val lineup = AppJson.parseToJsonElement(
        """
        {"container":{"sets":[{"items":[
          {"entity":{"id":"octane-id","type":"channel-linear","texts":{"title":{"default":"Octane"}},
             "images":{"tile":{"aspect_1x1":{"preferredImage":{"url":"img/octane.png"}}}}},
           "decorations":{"channelNumber":"37","genre":"Rock"}},
          {"entity":{"id":"hits1-id","type":"channel-linear","texts":{"title":"SiriusXM Hits 1"}},
           "decorations":{"channelNumber":2,"contentTags":["Pop"],"unentitled":true}},
          {"entity":{"id":"octane-id","type":"channel-linear","texts":{"title":{"default":"Octane"}}}}
        ]}]}}
        """,
    ).jsonObject

    @Test
    fun `lineup becomes a sorted, de-duplicated channel list`() {
        val channels = SiriusXmParser.parseChannels(lineup)
        assertEquals(listOf("SiriusXM Hits 1", "Octane"), channels.map { it.name })
        val octane = channels[1]
        assertEquals(37, octane.number)
        assertEquals("Rock", octane.category)
        assertFalse(octane.locked)
        assertTrue(octane.logoUrl!!.startsWith(SiriusXmParser.IMAGE_BASE))
        assertEquals("Pop", channels[0].category)
        assertTrue(channels[0].locked)
    }

    @Test
    fun `channel tracks round-trip their id and type`() {
        val channel = SiriusXmParser.parseChannels(lineup).first { it.id == "octane-id" }
        val track = channel.toTrack()
        assertTrue(track.isSiriusXmChannel)
        assertEquals("channel-linear" to "octane-id", SxmChannel.idOf(track))
        assertEquals("SiriusXM · Ch 37", track.artist)
    }

    @Test
    fun `image keys become resize requests on the image server`() {
        val url = SiriusXmParser.imageUrl("img/octane.png", 300)
        val json = AppJson.parseToJsonElement(String(Base64.getDecoder().decode(url.removePrefix(SiriusXmParser.IMAGE_BASE)))).jsonObject
        assertEquals("img/octane.png", json["key"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/a.png", SiriusXmParser.imageUrl("https://example.com/a.png", 300))
    }

    @Test
    fun `the cut being heard is picked by wall-clock time`() {
        val items = AppJson.parseToJsonElement(
            """
            [{"name":"Song A","artistName":"Artist A","timestamp":"2026-10-29T20:00:00.000+00:00Z","duration":180000},
             {"name":"","artistName":"","timestamp":"2026-10-29T20:03:00Z","duration":60000,"isInterstitial":true},
             {"name":"Song B","artistName":"Artist B","albumName":"Album B","timestamp":"2026-10-29T20:04:00Z","duration":240000}]
            """,
        ) as JsonArray
        val cuts = SiriusXmParser.parseCuts(items)
        val start = SiriusXmParser.parseTimeMs(kotlinx.serialization.json.JsonPrimitive("2026-10-29T20:00:00Z"))!!
        assertEquals("Song A", SiriusXmParser.currentCut(cuts, start + 60_000)!!.title)
        assertFalse(SiriusXmParser.currentCut(cuts, start + 200_000)!!.isSong)
        val songB = SiriusXmParser.currentCut(cuts, start + 300_000)!!
        assertEquals("Song B", songB.title)
        assertEquals("Album B", songB.toTrack().album)
        assertNull(SiriusXmParser.currentCut(emptyList(), start))
    }

    @Test
    fun `playlist key URIs are pointed at the app's key scheme`() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://example.com/key/12345678-1234-1234-1234-123456789abc",IV=0x1
            #EXTINF:10.0,
            segment1.aac
        """.trimIndent()
        val rewritten = SiriusXmParser.rewriteKeys(playlist)
        assertTrue(rewritten.contains("URI=\"sxmkey://12345678-1234-1234-1234-123456789abc\""))
        assertTrue(rewritten.contains("IV=0x1"))
        assertTrue(rewritten.contains("segment1.aac"))
    }

    @Test
    fun `key lines without a GUID use the tune response's key id`() {
        val playlist = """
            #EXT-X-KEY:METHOD=NONE
            #EXT-X-KEY:METHOD=AES-128,URI="https://example.com/key",IV=0x2
        """.trimIndent()
        val rewritten = SiriusXmParser.rewriteKeys(playlist, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").lines()
        assertEquals("#EXT-X-KEY:METHOD=NONE", rewritten[0])
        assertTrue(rewritten[1].contains("URI=\"sxmkey://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\""))
        // Without a fallback the line is left alone.
        assertTrue(SiriusXmParser.rewriteKeys(playlist).contains("https://example.com/key"))
    }

    @Test
    fun `xtra track lists become cuts starting now`() {
        val items = AppJson.parseToJsonElement(
            """[{"id":"t1","name":"Intro","artistName":"Octane","duration":30000},{"id":"t2","name":"Song","artistName":"Band"}]""",
        ) as JsonArray
        val cuts = SiriusXmParser.parseXtraItems(items, nowMs = 1_000)
        assertEquals(listOf("t1", "t2"), cuts.map { it.id })
        assertEquals(1_000, cuts[0].startMs)
        assertEquals(30_000, cuts[0].durationMs)
        // Tracks play back to back.
        assertEquals(31_000, cuts[1].startMs)
    }

    @Test
    fun `xtra playlists keep growing instead of ending after the first track`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            intro1.aac
            #EXT-X-ENDLIST
        """.trimIndent()
        val growing = SiriusXmParser.keepGrowing(playlist).lines()
        assertEquals("#EXT-X-START:TIME-OFFSET=0,PRECISE=YES", growing[1])
        assertFalse(growing.any { it.startsWith("#EXT-X-ENDLIST") || it.startsWith("#EXT-X-PLAYLIST-TYPE") })
        assertTrue(growing.contains("intro1.aac"))
        // Master playlists are left alone.
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\nlow.m3u8"
        assertEquals(master, SiriusXmParser.keepGrowing(master))
    }

    @Test
    fun `timestamps parse in every form SiriusXM uses`() {
        val expected = 1_793_304_000_000L
        assertEquals(expected, SiriusXmParser.parseTimeMs(kotlinx.serialization.json.JsonPrimitive("2026-10-29T20:00:00.000+00:00Z")))
        assertEquals(expected, SiriusXmParser.parseTimeMs(kotlinx.serialization.json.JsonPrimitive("2026-10-29T20:00:00Z")))
        assertEquals(expected, SiriusXmParser.parseTimeMs(kotlinx.serialization.json.JsonPrimitive(expected)))
        assertEquals(expected, SiriusXmParser.parseTimeMs(kotlinx.serialization.json.JsonPrimitive(expected / 1000)))
        assertEquals("2026-10-29T20:00:00.000Z", SiriusXmParser.isoUtc(expected))
    }
}
