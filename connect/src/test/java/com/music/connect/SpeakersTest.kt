package com.music.connect

import com.music.connect.net.ApiTrack
import com.music.connect.net.ConnectClient
import com.music.connect.net.ServerApi
import com.music.connect.speakers.MusicCastSpeaker
import com.music.connect.speakers.SpeakerControl
import com.music.connect.speakers.SpeakerInfo
import com.music.connect.speakers.SpeakerSession
import com.music.connect.speakers.Ssdp
import com.music.connect.speakers.StreamRelay
import com.music.connect.speakers.Transport
import com.music.connect.speakers.ZoneStatus
import com.music.player.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A speaker as it behaves: it plays an address it's given, fetching it itself, and says where it's at. */
private class FakeSpeaker(private val http: OkHttpClient) : SpeakerControl {
    override val info = SpeakerInfo("ABC123DEF456", "Living Room", "WX-030", "127.0.0.1", "http://127.0.0.1:1/AVTransport/ctrl")
    @Volatile var on = false
    @Volatile var volume = 20
    @Volatile var input = "net_radio"
    @Volatile var uri = ""
    @Volatile var state = "NO_MEDIA_PRESENT"
    @Volatile var positionMs = 0L
    val given = LinkedBlockingQueue<Pair<String, String>>()
    val fetched = LinkedBlockingQueue<ByteArray>()

    override suspend fun status() = ZoneStatus(on, volume, 60, false, input)
    override suspend fun setPower(on: Boolean) { this.on = on }
    override suspend fun setVolume(level: Int) { volume = level }
    override suspend fun setUri(url: String, metadata: String) {
        uri = url
        input = "server"
        state = "STOPPED"
        positionMs = 0
        given.add(url to metadata)
        // Like a speaker: it fetches the song itself (without signing in to anything).
        thread { runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { fetched.add(it.body!!.bytes()) } } }
    }
    override suspend fun play() { state = "PLAYING" }
    override suspend fun pause() { state = "PAUSED_PLAYBACK" }
    override suspend fun stop() { state = "STOPPED" }
    override suspend fun transport() = Transport(state, positionMs, 200_000, uri)
}

/** Speakers: Yamaha's API and UPnP as the speaker speaks them, and a speaker as one of the person's devices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeakersTest {
    private lateinit var test: TestServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var token: String? = null

    @Before
    fun start() {
        test = TestServer()
        token = runBlocking { ServerApi(OkHttpClient(), { test.base }, { null }).login(test.base, "maddie", "correct horse", "Test phone").token }
    }

    @After
    fun stop() {
        scope.cancel()
        test.close()
    }

    private fun <T> eventually(what: String, timeoutMs: Long = 10_000, value: () -> T?): T {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            value()?.let { return it }
            Thread.sleep(50)
        }
        error("Never: $what")
    }

    /** A web player, as the server sees one. */
    private inner class WebPlayer(http: OkHttpClient) : WebSocketListener() {
        val messages = LinkedBlockingQueue<JsonObject>()
        @Volatile var devices: List<JsonObject> = emptyList()
        val socket: WebSocket = http.newWebSocket(
            Request.Builder().url("${test.base}/api/connect/ws?id=web-device-1&name=Chrome&kind=web").header("Authorization", "Bearer $token").build(),
            this,
        )

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["type"]?.jsonPrimitive?.content == "devices") devices = message["devices"]!!.jsonArray.map { it.jsonObject }
            messages.add(message)
        }

        fun speaker(): JsonObject? = devices.firstOrNull { it["id"]?.jsonPrimitive?.content == "musiccast-abc123def456" }
        fun speakerPlaying(): Boolean? = speaker()?.get("state")?.takeIf { it is JsonObject }?.jsonObject?.get("playing")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        fun speakerSong(): String? = speaker()?.get("state")?.takeIf { it is JsonObject }?.jsonObject?.get("track")?.takeIf { it is JsonObject }?.jsonObject?.get("title")?.jsonPrimitive?.content
        fun command(action: String, extra: String = "") = socket.send("""{"type":"command","target":"musiccast-abc123def456","command":{"action":"$action"$extra}}""")
    }

    @Test
    fun `a speaker plays what the web player hands it, through the phone, and does what it's asked`() = runBlocking<Unit> {
        val authed = OkHttpClient.Builder().addInterceptor(ServerApi.authInterceptor({ test.base }, { token })).build()
        val api = ServerApi(authed, { test.base }, { token })
        val song = api.search("neon").allSongs.single()
        val song2 = ApiTrack(SourceType.JELLYFIN, "song2", "Paper Lanterns", "Velvet Satellites", "Low Orbit", 214_000, via = song.via)
        val speaker = FakeSpeaker(OkHttpClient())
        val relay = StreamRelay(authed).apply { start() }
        val hub = ConnectClient(RuntimeEnvironment.getApplication(), authed, { test.base }, { token }, scope, kind = "speaker", fixedId = "musiccast-abc123def456", fixedName = "Living Room")
        val session = SpeakerSession(speaker, hub, api, relay, { test.base }, { 0 }, scope, clock = System::currentTimeMillis, settleMs = 300)
        session.start()
        try {
            val web = WebPlayer(OkHttpClient())
            eventually("the speaker among the devices") { web.speaker() }.let { listed ->
                assertEquals("Living Room", listed["name"]!!.jsonPrimitive.content)
                assertEquals("speaker", listed["kind"]!!.jsonPrimitive.content)
            }

            // The web player has a queue; it moves to the speaker.
            val json = Json { encodeDefaults = false }
            val queue = listOf(song, song2).joinToString(",") { json.encodeToString(ApiTrack.serializer(), it) }
            web.socket.send("""{"type":"state","state":{"track":${json.encodeToString(ApiTrack.serializer(), song)},"queue":[$queue],"index":0,"positionMs":0,"durationMs":200000,"playing":false}}""")
            eventually("the web player's state") { web.devices.firstOrNull { it["id"]!!.jsonPrimitive.content == "web-device-1" && it["state"] is JsonObject } }
            web.socket.send("""{"type":"transfer","to":"musiccast-abc123def456"}""")

            // The speaker is switched on and given an address on the phone, which serves it the song.
            val (url, metadata) = (speaker.given.poll(10, TimeUnit.SECONDS) ?: error("The speaker was given nothing"))
            assertTrue(url, url.startsWith("http://127.0.0.1:${relay.port}/s/"))
            assertTrue(metadata.contains("<dc:title>Neon Tidepool</dc:title>"))
            assertTrue(metadata.contains("http-get:*:audio/flac:*"))
            assertTrue(speaker.on)
            assertArrayEquals(test.audio, speaker.fetched.poll(10, TimeUnit.SECONDS))
            eventually("the web player sees it playing") { web.speakerPlaying()?.takeIf { it } }
            assertEquals("Neon Tidepool", web.speakerSong())

            // Paused and played from the web player.
            web.command("pause")
            eventually("paused") { speaker.state.takeIf { it == "PAUSED_PLAYBACK" } }
            eventually("shown paused") { web.speakerPlaying()?.takeIf { !it } }
            web.command("play")
            eventually("playing again") { speaker.state.takeIf { it == "PLAYING" } }
            assertTrue("resumed where it was, not given the song again", speaker.given.isEmpty())

            // Its volume, in the speaker's own steps.
            web.command("volume", ""","volume":0.5""")
            eventually("volume set") { speaker.volume.takeIf { it == 30 } }

            // The song ends: the next one plays.
            speaker.positionMs = 199_000
            Thread.sleep(400)
            speaker.state = "STOPPED"
            val (_, next) = (speaker.given.poll(10, TimeUnit.SECONDS) ?: error("The speaker was given nothing"))
            assertTrue(next.contains("<dc:title>Paper Lanterns</dc:title>"))
            eventually("the web player sees the next song") { web.speakerSong()?.takeIf { it == "Paper Lanterns" } }

            // Someone plays something else on it, from its own app: it's theirs now.
            speaker.input = "spotify"
            speaker.uri = "spotify:track:1"
            eventually("shown as not playing") { web.speakerPlaying()?.takeIf { !it } }
            assertFalse(session.now.value?.playing == true)
            web.socket.close(1000, null)
        } finally {
            session.stop()
            relay.stop()
        }
    }

    @Test
    fun `Yamaha's API and the UPnP player, as a MusicCast speaker speaks them`() = runBlocking<Unit> {
        val calls = LinkedBlockingQueue<RecordedRequest>()
        val device = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    calls.add(request)
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path.endsWith("/main/getStatus") -> MockResponse().setBody("""{"response_code":0,"power":"standby","volume":40,"mute":false,"max_volume":161,"input":"net_radio"}""")
                        path.endsWith("/main/setVolume") || path.endsWith("/main/setPower") -> MockResponse().setBody("""{"response_code":0}""")
                        path == "/AVTransport/ctrl" -> {
                            val action = request.getHeader("SOAPACTION")!!.substringAfter('#').trim('"')
                            when (action) {
                                "GetTransportInfo" -> soap(action, "<CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>")
                                "GetPositionInfo" -> soap(action, "<Track>1</Track><TrackDuration>0:03:20</TrackDuration><TrackURI>http://192.168.1.5:4000/s/abc?x=1&amp;y=2</TrackURI><RelTime>0:01:05</RelTime>")
                                else -> soap(action, "")
                            }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        try {
            val base = device.url("/").toString().trimEnd('/')
            val info = SpeakerInfo("ABC", "Kitchen", "WX-010", "127.0.0.1", "$base/AVTransport/ctrl")
            val speaker = MusicCastSpeaker(info, OkHttpClient(), yxc = "$base/YamahaExtendedControl/v1")
            val status = speaker.status()
            assertFalse(status.on)
            assertEquals(40, status.volume)
            assertEquals(161, status.maxVolume)
            assertEquals("net_radio", status.input)
            speaker.setVolume(80)
            assertEquals("80", calls.last().requestUrl!!.queryParameter("volume"))

            val didl = MusicCastSpeaker.didl("http://192.168.1.5:4000/s/abc?x=1&y=2", "audio/flac", "Rock & Roll", "Led Zeppelin", "IV", null, 220_000)
            speaker.setUri("http://192.168.1.5:4000/s/abc?x=1&y=2", didl)
            val set = calls.last()
            assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", set.getHeader("SOAPACTION"))
            val body = set.body.readUtf8()
            // The address and its description are escaped once more inside the SOAP message.
            assertTrue(body.contains("<CurrentURI>http://192.168.1.5:4000/s/abc?x=1&amp;y=2</CurrentURI>"))
            assertTrue(body.contains("&lt;dc:title&gt;Rock &amp;amp; Roll&lt;/dc:title&gt;"))
            assertTrue(body.contains("duration=&quot;0:03:40.000&quot;"))

            val transport = speaker.transport()
            assertEquals("PLAYING", transport.state)
            assertEquals(65_000, transport.positionMs)
            assertEquals(200_000, transport.durationMs)
            assertEquals("http://192.168.1.5:4000/s/abc?x=1&y=2", transport.uri)
        } finally {
            device.shutdown()
        }
    }

    private fun soap(action: String, inner: String) = MockResponse().setHeader("Content-Type", "text/xml").setBody(
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$inner</u:${action}Response></s:Body></s:Envelope>""",
    )

    @Test
    fun `discovery answers and device descriptions are read`() {
        val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.40:49154/MediaRenderer/desc.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.1.40:49154/MediaRenderer/desc.xml", Ssdp.locationOf(answer))
        assertTrue(Ssdp.searchMessage().startsWith("M-SEARCH * HTTP/1.1\r\n"))
        val description = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:yamaha="urn:schemas-yamaha-com:device-1-0">
            <device><manufacturer>Yamaha Corporation</manufacturer><modelName>WX-030</modelName>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/RenderingControl/ctrl</controlURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/AVTransport/ctrl</controlURL></service>
            </serviceList></device>
            <yamaha:X_device><yamaha:X_yxcControlURL>/YamahaExtendedControl/v1/</yamaha:X_yxcControlURL></yamaha:X_device></root>"""
        assertTrue(MusicCastSpeaker.isMusicCast(description))
        assertFalse(MusicCastSpeaker.isMusicCast("<root><device><manufacturer>Sonos, Inc.</manufacturer></device></root>"))
        assertEquals("http://192.168.1.40:49154/AVTransport/ctrl", MusicCastSpeaker.transportUrl(description, "http://192.168.1.40:49154/MediaRenderer/desc.xml"))
        assertEquals(3_723_500, MusicCastSpeaker.parseTime("1:02:03.5"))
        assertEquals(0, MusicCastSpeaker.parseTime("NOT_IMPLEMENTED"))
    }
}
