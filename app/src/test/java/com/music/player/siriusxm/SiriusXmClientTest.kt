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
    private var xtraCurrent = "intro"

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
                    "/playback/play/v1/tuneSource" -> xtraTune(xtraCurrent)
                    "/playback/telemetry/v2/actions" -> json("""{"failedActions":[]}""")
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
                        path.startsWith("/cdn/") && path.endsWith("/master.m3u8") -> MockResponse().setBody(
                            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=281600\n256k/media.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=105600\n96k/media.m3u8",
                        )
                        path.startsWith("/cdn/") && path.endsWith("/media.m3u8") -> {
                            val track = path.removePrefix("/cdn/").substringBefore('/')
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

    private fun xtraTune(trackId: String) = json(
        """{"streams":[{"urls":[{"url":"${server.url("/cdn/$trackId/master.m3u8")}","isPrimary":true}],
            "mtc":{"next":"enabled"},
            "metadata":{"xtra":{"items":[{"id":"$trackId","name":"$trackId","artistName":"Artist","duration":20000}]}}}]}""",
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
    fun `xtra channels play as one stream that grows a track at a time`() = runTest {
        var now = 1_000_000L
        val client = client(clock = { now })
        client.maxBitrate = 128_000
        val first = client.xtraPlaylist("channel-xtra", "x1")
        assertFalse(first.contains("#EXT-X-ENDLIST"))
        // The variant within the cap, with keys served by the app and the original IVs kept.
        assertTrue(first.contains(server.url("/cdn/intro/96k/intro_1.aac").toString()))
        assertTrue(first.contains("URI=\"sxmkey://12345678-1234-1234-1234-123456789abc\",IV=0x00000000000000000000000000000005"))
        assertEquals("intro", client.cutAt("channel-xtra", "x1", now + 5_000)?.title)

        // Reloading straight away doesn't tune again.
        val tuned = tunes()
        client.xtraPlaylist("channel-xtra", "x1")
        assertEquals(tuned, tunes())

        // SiriusXM hasn't moved on yet: the same track again adds nothing.
        now += 10_000
        val same = client.xtraPlaylist("channel-xtra", "x1")
        assertEquals(tuned + 1, tunes())
        assertEquals(2, same.lines().count { it.endsWith(".aac") })

        // The next track is appended after a discontinuity, and the song changes with it.
        xtraCurrent = "song-2"
        now += 10_000
        val grown = client.xtraPlaylist("channel-xtra", "x1").lines()
        assertEquals(4, grown.count { it.endsWith(".aac") })
        val discontinuity = grown.indexOf("#EXT-X-DISCONTINUITY")
        assertTrue(discontinuity > grown.indexOfFirst { it.endsWith("intro_2.aac") })
        assertTrue(discontinuity < grown.indexOfFirst { it.endsWith("song-2_1.aac") })
        assertEquals("song-2", client.cutAt("channel-xtra", "x1", 1_000_000L + 25_000)?.title)

        // What's heard is reported to SiriusXM.
        client.onXtraHeard("channel-xtra", "x1", 1_000_000L + 25_000)
        val telemetry = requests.last { it.path == "/playback/telemetry/v2/actions" }.body.readUtf8()
        assertTrue(telemetry.contains("\"start\"") && telemetry.contains("\"id\":\"song-2\""))
    }

    @Test
    fun `an xtra channel nobody has loaded for a while starts afresh`() = runTest {
        var now = 1_000_000L
        val client = client(clock = { now })
        client.xtraPlaylist("channel-xtra", "x1")
        xtraCurrent = "song-2"
        now += 10 * 60_000L
        val fresh = client.xtraPlaylist("channel-xtra", "x1")
        assertFalse(fresh.contains("intro_1.aac"))
        assertTrue(fresh.contains("song-2_1.aac"))
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
}
