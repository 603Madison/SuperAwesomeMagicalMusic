package com.music.player.siriusxm

import com.music.player.settings.SiriusXmConfig
import com.music.player.sources.siriusxm.SiriusXmAuthException
import com.music.player.sources.siriusxm.SiriusXmClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class SiriusXmClientTest {
    private lateinit var server: MockWebServer
    private var savedSession: String? = null
    private val requests = mutableListOf<RecordedRequest>()
    private var rejectPassword = false
    private var expireNextKeyRequest = false
    /** The queue SiriusXM hands out when the channel is tuned, and what peeking adds. */
    private var tuneQueue = listOf("intro", "song-1")
    private var peekQueue = listOf("song-1", "song-2")
    /** Tracks SiriusXM has only sent the first part of so far (no ENDLIST yet). */
    private val partial = mutableSetOf<String>()

    private var searchResponse = "{}"
    private var rejectFeedback = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path!!.substringBefore('?')
                return when (path) {
                    "/device/v1/devices" -> json("""{"deviceId":"dev-1","grant":"device-grant"}""")
                    "/session/v1/sessions/anonymous" -> json("""{"accessToken":"anon-token","accessTokenExpiresAt":"2099-01-01T00:00:00Z"}""")
                    "/identity/v1/identities/authenticate/password" ->
                        if (rejectPassword) MockResponse().setResponseCode(401) else json("""{"grant":"identity-grant"}""")
                    "/session/v1/sessions/authenticated" -> json("""{"accessToken":"access-token","accessTokenExpiresAt":"2099-01-01T00:00:00Z"}""")
                    "/playback/play/v1/tuneSource" -> xtraQueue(tuneQueue, sequenceToken = "seq-1")
                    "/playback/play/v1/peek" -> xtraQueue(peekQueue, sequenceToken = "seq-2")
                    "/playback/telemetry/v2/actions" -> json("""{"failedActions":[]}""")
                    "/playback/signal/v1/feedback" ->
                        if (rejectFeedback) MockResponse().setResponseCode(500) else MockResponse().setResponseCode(204)
                    "/search/v1/search" -> json(searchResponse)
                    "/relationship/v1/container/all-channels" ->
                        json("""{"container":{"sets":[{"items":[{"entity":{"id":"c1","texts":{"title":"Octane"}}}]}]}}""")
                    "/playback/key/v1/12345678-1234-1234-1234-123456789abc" ->
                        if (expireNextKeyRequest) {
                            expireNextKeyRequest = false
                            MockResponse().setResponseCode(401)
                        } else {
                            json("""{"key":"${Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })}"}""")
                        }
                    else -> when {
                        path.startsWith("/cdn/binary/") -> MockResponse().setBody("\u0000\u0000\u0000 ftypmp42 stts stsc stsz mlmlml")
                        path.startsWith("/cdn/") && path.endsWith("/master.m3u8") -> MockResponse().setBody(
                            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=281600\n256k/media.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=105600\n96k/media.m3u8",
                        )
                        path.startsWith("/cdn/") && path.endsWith("/media.m3u8") -> {
                            val track = path.removePrefix("/cdn/").substringBefore('/')
                            if (track in partial) return MockResponse().setBody(
                                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:5\n" +
                                    "#EXT-X-KEY:METHOD=AES-128,URI=\"https://key.example.com/12345678-1234-1234-1234-123456789abc\"\n" +
                                    "#EXTINF:10.0,\n${track}_1.aac\n",
                            )
                            MockResponse().setBody(
                                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:5\n" +
                                    "#EXT-X-KEY:METHOD=AES-128,URI=\"https://key.example.com/12345678-1234-1234-1234-123456789abc\"\n" +
                                    "#EXTINF:10.0,\n${track}_1.aac\n#EXTINF:10.0,\n${track}_2.aac\n#EXT-X-ENDLIST",
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** A tune or peek response: one stream per queued track, as SiriusXM sends them. */
    private fun xtraQueue(trackIds: List<String>, sequenceToken: String) = json(
        """{"id":"x1","type":"channel-xtra","sequenceToken":"$sequenceToken","streams":[""" +
            trackIds.joinToString(",") { trackId ->
                """{"id":"$trackId","urls":[{"url":"${server.url("/cdn/$trackId/master.m3u8")}","isPrimary":true,
                    "encryptionKeyId":"12345678-1234-1234-1234-123456789abc"}],
                    "mtc":{"next":"ENABLED"},
                    "metadata":{"xtra":{"sourceContextId":"x1#ctx","items":[{"id":"$trackId","name":"$trackId",
                    "artistName":"Artist","duration":20000}]}}}"""
            } + "]}",
    )

    private fun json(body: String) = MockResponse().setBody(body).addHeader("Content-Type", "application/json")

    private fun client(
        config: SiriusXmConfig = SiriusXmConfig("me@example.com", "secret"),
        clock: () -> Long = System::currentTimeMillis,
    ) = SiriusXmClient(
        http = OkHttpClient(),
        credentials = { config },
        loadSession = { savedSession },
        saveSession = { savedSession = it },
        baseUrl = server.url("/").toString(),
        clock = clock,
    )

    @Test
    fun `sign-in walks the device, anonymous, identity and authenticated steps`() = runTest {
        val channels = client().verify(SiriusXmConfig("me@example.com", "secret"))
        assertEquals(1, channels)
        assertEquals(
            listOf(
                "/device/v1/devices",
                "/session/v1/sessions/anonymous",
                "/identity/v1/identities/authenticate/password",
                "/session/v1/sessions/authenticated",
                "/relationship/v1/container/all-channels",
            ),
            requests.map { it.path!!.substringBefore('?') },
        )
        assertEquals(null, requests[0].getHeader("Authorization"))
        assertEquals("Bearer device-grant", requests[1].getHeader("Authorization"))
        assertEquals("Bearer anon-token", requests[2].getHeader("Authorization"))
        assertTrue(requests[2].body.readUtf8().contains("\"handle\":\"me@example.com\""))
        assertEquals("Bearer identity-grant", requests[3].getHeader("Authorization"))
        assertEquals("Bearer access-token", requests[4].getHeader("Authorization"))
        assertEquals("sxm", requests[4].getHeader("x-sxm-tenant"))
        assertTrue(savedSession!!.contains("access-token"))
    }

    @Test
    fun `a saved session is reused without signing in again`() = runTest {
        client().channels()
        requests.clear()
        client().channels(refresh = true)
        assertEquals(listOf("/relationship/v1/container/all-channels"), requests.map { it.path!!.substringBefore('?') })
    }

    @Test
    fun `an expired session is renewed once and the request retried`() = runTest {
        val client = client()
        client.channels()
        requests.clear()
        expireNextKeyRequest = true
        val key = client.key("12345678-1234-1234-1234-123456789abc")
        assertArrayEquals(ByteArray(16) { it.toByte() }, key)
        assertTrue(requests.any { it.path == "/identity/v1/identities/authenticate/password" })
        assertEquals("/playback/key/v1/12345678-1234-1234-1234-123456789abc", requests.last().path)
    }

    private fun tunes() = requests.count { it.path == "/playback/play/v1/tuneSource" }

    @Test
    fun `xtra channels queue every track SiriusXM hands out and peek for more`() = runTest {
        var now = 1_000_000L
        val client = client(clock = { now })
        client.maxBitrate = 128_000
        val first = client.xtraPlaylist("channel-xtra", "x1").lines()
        assertFalse(first.contains("#EXT-X-ENDLIST"))
        // Both queued tracks, in order, after a discontinuity, from the variant within the cap.
        assertTrue(first.contains(server.url("/cdn/intro/96k/intro_1.aac").toString()))
        val discontinuity = first.indexOf("#EXT-X-DISCONTINUITY")
        assertTrue(discontinuity > first.indexOfFirst { it.endsWith("intro_2.aac") })
        assertTrue(discontinuity < first.indexOfFirst { it.endsWith("song-1_1.aac") })
        assertTrue(first.any { it.contains("URI=\"sxmkey://12345678-1234-1234-1234-123456789abc\",IV=0x00000000000000000000000000000005") })
        assertEquals("intro", client.cutAt("channel-xtra", "x1", now + 5_000)?.title)
        assertEquals("song-1", client.cutAt("channel-xtra", "x1", now + 25_000)?.title)
        assertEquals(0, requests.count { it.path == "/playback/play/v1/peek" })

        // Under a minute queued: peek for more, as SiriusXM's player does, sending back its tokens.
        val grown = client.xtraPlaylist("channel-xtra", "x1").lines()
        val peek = requests.single { it.path == "/playback/play/v1/peek" }.body.readUtf8()
        assertTrue(peek.contains("\"sourceContextId\":\"x1#ctx\"") && peek.contains("\"sequenceToken\":\"seq-1\""))
        // song-1 was already queued; only song-2 is new.
        assertEquals(6, grown.count { it.endsWith(".aac") })
        assertEquals(1, grown.count { it.endsWith("song-1_1.aac") })
        assertEquals("song-2", client.cutAt("channel-xtra", "x1", now + 45_000)?.title)

        // A minute queued: no more requests for now.
        requests.clear()
        client.xtraPlaylist("channel-xtra", "x1")
        assertTrue(requests.none { it.path == "/playback/play/v1/peek" || it.path == "/playback/play/v1/tuneSource" })

        // What's heard is reported; finishing a track reports it played through before the next starts.
        client.onXtraHeard("channel-xtra", "x1", now + 5_000)
        client.onXtraHeard("channel-xtra", "x1", now + 25_000)
        val telemetry = requests.filter { it.path == "/playback/telemetry/v2/actions" }.map { it.body.readUtf8() }
        assertTrue(telemetry[0].contains("\"start\"") && telemetry[0].contains("\"id\":\"intro\""))
        assertTrue(telemetry[1].contains("\"progress\"") && telemetry[1].contains("\"id\":\"intro\"") && telemetry[1].contains("20000"))
        assertTrue(telemetry[2].contains("\"start\"") && telemetry[2].contains("\"id\":\"song-1\""))
    }

    @Test
    fun `a track SiriusXM has only sent part of is fetched again until it's all there`() = runTest {
        var now = 1_000_000L
        val client = client(clock = { now })
        partial += "song-2"
        client.xtraPlaylist("channel-xtra", "x1")
        val peeked = client.xtraPlaylist("channel-xtra", "x1") // peeks: song-2 joins, only its start so far
        assertTrue(peeked.contains("song-2_1.aac"))
        assertFalse(peeked.contains("song-2_2.aac"))
        fun fetches() = requests.count { it.path!!.startsWith("/cdn/song-2/") && it.path!!.endsWith("/media.m3u8") }
        val before = fetches()

        // Asked again within a few seconds: not fetched again yet.
        now += 2_000
        client.xtraPlaylist("channel-xtra", "x1")
        assertEquals(before, fetches())

        // The rest has arrived at SiriusXM: fetched, and the song plays through.
        partial.clear()
        now += 8_000
        val grown = client.xtraPlaylist("channel-xtra", "x1")
        assertEquals(before + 1, fetches())
        assertEquals(1, grown.lines().count { it.endsWith("song-2_1.aac") })
        assertTrue(grown.contains("song-2_2.aac"))
        assertTrue(client.diagnostics().contains("xtra grow song-2"))

        // All there now: not fetched again.
        now += 9_000
        client.xtraPlaylist("channel-xtra", "x1")
        assertEquals(before + 1, fetches())
    }

    @Test
    fun `what an xtra channel played before is listed, newest first`() = runTest {
        val now = 1_000_000L
        val client = client(clock = { now })
        client.maxBitrate = 128_000
        client.xtraPlaylist("channel-xtra", "x1")
        client.xtraPlaylist("channel-xtra", "x1") // peeks: song-2 joins
        // intro, song-1 and song-2 play from 0, 20 and 40 seconds in.
        assertEquals(listOf("song-1", "intro"), client.recentCuts("channel-xtra", "x1", now + 45_000).map { it.title })
        assertEquals(emptyList<String>(), client.recentCuts("channel-xtra", "x1", now + 5_000).map { it.title })
    }

    @Test
    fun `nothing new from peeking waits before asking again`() = runTest {
        var now = 1_000_000L
        val client = client(clock = { now })
        client.xtraPlaylist("channel-xtra", "x1")
        peekQueue = listOf("song-1")
        client.xtraPlaylist("channel-xtra", "x1")
        // Peek brought nothing, so the channel was tuned again; then it waits.
        assertEquals(2, requests.count { it.path == "/playback/play/v1/tuneSource" })
        client.xtraPlaylist("channel-xtra", "x1")
        assertEquals(1, requests.count { it.path == "/playback/play/v1/peek" })
        now += 11_000
        client.xtraPlaylist("channel-xtra", "x1")
        assertEquals(2, requests.count { it.path == "/playback/play/v1/peek" })
    }

    @Test
    fun `an xtra channel nobody has loaded for a while starts afresh`() = runTest {
        var now = 1_000_000L
        val client = client(clock = { now })
        client.xtraPlaylist("channel-xtra", "x1")
        tuneQueue = listOf("song-2")
        now += 10 * 60_000L
        val fresh = client.xtraPlaylist("channel-xtra", "x1")
        assertFalse(fresh.contains("intro_1.aac"))
        assertTrue(fresh.contains("song-2_1.aac"))
    }

    @Test
    fun `artist search sends the web player's request and finds stations first`() = runTest {
        val client = client()
        searchResponse = """{"container":{"sets":[{"items":[
            {"entity":{"id":"t1","type":"talent","texts":{"title":{"default":"Noah Kahan"}}}},
            {"entity":{"id":"st1","type":"artist-station","texts":{"title":{"default":"Noah Kahan Radio"}}}},
            {"entity":{"id":"t2","type":"talent","texts":{"title":{"default":"Gracie Abrams"}}}}]}]}}"""
        val results = client.searchArtists("Noah Kahan")
        val body = requests.single { it.path == "/search/v1/search" }.body.readUtf8()
        assertTrue(body.contains("\"searchString\":\"Noah Kahan\"") && body.contains("\"filterTypes\":[]"))
        assertEquals(listOf("Noah Kahan", "Gracie Abrams"), results.map { it.name })
        assertEquals("st1", results[0].stationId)
        assertEquals("st1", client.artistStation(results[0]).id)
    }

    @Test
    fun `wrong password is reported clearly`() = runTest {
        rejectPassword = true
        try {
            client().verify(SiriusXmConfig("me@example.com", "wrong"))
            fail("Expected SiriusXmAuthException")
        } catch (e: SiriusXmAuthException) {
            assertTrue(e.message!!.contains("rejected"))
        }
    }

    @Test
    fun `pandora stations hand out their tracks as audio files, and more on request`() = runTest {
        val client = client()
        val first = client.stationTracks("st1", more = false)
        val tune = requests.single { it.path == "/playback/play/v1/tuneSource" }.body.readUtf8()
        assertTrue(tune.contains("\"type\":\"artist-station\"") && tune.contains("\"mediaFormat\":\"MP4\""))
        assertEquals(listOf("intro", "song-1"), first.map { it.id })
        assertEquals(server.url("/cdn/song-1/master.m3u8").toString(), client.stationTrackUrl("song-1"))
        val track = first[1].toTrack("Noah Kahan Radio")
        assertTrue(track.isSiriusXmStationTrack && track.isLive)
        assertEquals("song-1", track.title)
        assertEquals("st1" to "song-1", com.music.player.sources.siriusxm.SxmStationTrack.idOf(track))

        // More: only tracks not handed out yet, asking with the station's sequence token.
        val more = client.stationTracks("st1", more = true)
        assertEquals(listOf("song-2"), more.map { it.id })
        val peek = requests.single { it.path == "/playback/play/v1/peek" }.body.readUtf8()
        assertTrue(peek.contains("\"sequenceToken\":\"seq-1\"") && peek.contains("\"mediaFormat\":\"MP4\""))
    }

    @Test
    fun `an audio file is never read as a playlist`() = runTest {
        var now = 1_000_000L
        val client = client(clock = { now })
        tuneQueue = listOf("binary")
        try {
            client.xtraPlaylist("channel-xtra", "x1")
            fail("Expected an error")
        } catch (e: IllegalStateException) {
            assertEquals("SiriusXM sent something other than a playlist", e.message)
        }
    }

    @Test
    fun `a thumb on a station track is sent as feedback and as telemetry, and remembered`() = runTest {
        val client = client()
        assertTrue(client.rateStationTrack("st1", "song-1", com.music.player.sources.siriusxm.SxmThumb.UP, elapsedMs = 42_000))
        assertEquals(com.music.player.sources.siriusxm.SxmThumb.UP, client.thumbs.value["song-1"])
        val feedback = requests.single { it.path == "/playback/signal/v1/feedback" }.body.readUtf8()
        assertTrue(feedback, feedback.contains("\"feedbackType\":\"THUMBS_UP\"") && feedback.contains("\"stationTrack\":\"song-1\""))
        assertTrue(feedback, feedback.contains("\"type\":\"artist-station\"") && feedback.contains("\"id\":\"st1\""))
        val telemetry = requests.last { it.path == "/playback/telemetry/v2/actions" }.body.readUtf8()
        assertTrue(telemetry, telemetry.contains("\"feedback\":{\"thumbsUp\"") && telemetry.contains("\"elapsedTimeMs\":42000"))

        // Taking it back.
        assertTrue(client.rateStationTrack("st1", "song-1", com.music.player.sources.siriusxm.SxmThumb.NONE, elapsedMs = 0))
        assertEquals(null, client.thumbs.value["song-1"])
        assertTrue(requests.last { it.path == "/playback/signal/v1/feedback" }.body.readUtf8().contains("REMOVE_THUMB"))
    }

    @Test
    fun `one way of sending a thumb failing is enough for it to count`() = runTest {
        val client = client()
        rejectFeedback = true
        assertTrue(client.rateStationTrack("st1", "song-1", com.music.player.sources.siriusxm.SxmThumb.DOWN, elapsedMs = 0))
        assertEquals(com.music.player.sources.siriusxm.SxmThumb.DOWN, client.thumbs.value["song-1"])
    }
}
