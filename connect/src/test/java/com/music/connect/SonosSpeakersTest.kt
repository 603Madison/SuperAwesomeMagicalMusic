package com.music.connect

import com.music.connect.data.Settings
import com.music.connect.net.ApiTrack
import com.music.connect.net.ConnectClient
import com.music.connect.net.ConnectDevice
import com.music.connect.net.NetworkState
import com.music.connect.net.ServerApi
import com.music.connect.speakers.SonosSpeaker
import com.music.connect.speakers.SpeakerInfo
import com.music.connect.speakers.SpeakerKind
import com.music.connect.speakers.SpeakerMedia
import com.music.connect.speakers.SpeakerSession
import com.music.connect.speakers.Speakers
import com.music.connect.speakers.Ssdp
import com.music.connect.speakers.StreamRelay
import com.music.player.model.SourceType
import com.music.player.quality.NetworkClass
import com.music.player.speakers.sonos.FakeSonos
import com.music.player.speakers.sonos.SonosClient
import com.music.player.speakers.sonos.SonosException
import com.music.player.speakers.sonos.SonosXml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue

/** Sonos speakers through the phone, the way MusicCast ones are: found, grouped, and playing the queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SonosSpeakersTest {
    private lateinit var test: TestServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var token: String? = null
    private val lan = OkHttpClient()

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
    private inner class WebPlayer : WebSocketListener() {
        @Volatile var devices: List<JsonObject> = emptyList()
        val socket: WebSocket = OkHttpClient().newWebSocket(
            Request.Builder().url("${test.base}/api/connect/ws?id=web-device-1&name=Chrome&kind=web").header("Authorization", "Bearer $token").build(),
            this,
        )

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["type"]?.jsonPrimitive?.content == "devices") devices = message["devices"]!!.jsonArray.map { it.jsonObject }
        }

        fun device(id: String): JsonObject? = devices.firstOrNull { it["id"]?.jsonPrimitive?.content == id }
        fun speakers(): List<JsonObject> = devices.filter { it["kind"]?.jsonPrimitive?.content == "speaker" }
        fun state(id: String): JsonObject? = device(id)?.get("state")?.takeIf { it is JsonObject }?.jsonObject
        fun playing(id: String): Boolean? = state(id)?.get("playing")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        fun song(id: String): String? = state(id)?.get("track")?.takeIf { it is JsonObject }?.jsonObject?.get("title")?.jsonPrimitive?.content
        fun command(id: String, action: String, extra: String = "") = socket.send("""{"type":"command","target":"$id","command":{"action":"$action"$extra}}""")
    }

    private fun title(sonos: FakeSonos): String? = SonosXml.tag(sonos.metadata, "title")?.let(SonosXml::unescape)

    @Test
    fun `a Sonos plays the queue handed to it, through the phone, and does what it's asked`() = runBlocking<Unit> {
        FakeSonos().use { sonos ->
            val authed = OkHttpClient.Builder().addInterceptor(ServerApi.authInterceptor({ test.base }, { token })).build()
            val api = ServerApi(authed, { test.base }, { token })
            val song = api.search("neon").allSongs.single()
            val song2 = ApiTrack(SourceType.JELLYFIN, "song2", "Paper Lanterns", "Velvet Satellites", "Low Orbit", 214_000, via = song.via)
            val info = SonosSpeaker.infoOf(SonosClient(lan, sonos.baseUrl).topology().single())
            assertEquals(SpeakerKind.SONOS, info.kind)
            val id = info.connectId
            assertEquals("sonos-rincon000e58a0b1c201400", id)
            val relay = StreamRelay(authed).apply { start() }
            val hub = ConnectClient(RuntimeEnvironment.getApplication(), authed, { test.base }, { token }, scope, kind = "speaker", fixedId = id, fixedName = info.name, detail = info.detail)
            val session = SpeakerSession(SonosSpeaker(info, lan), hub, api, relay, { test.base }, { 0 }, scope, clock = System::currentTimeMillis, settleMs = 300)
            session.start()
            try {
                val web = WebPlayer()
                eventually("the Sonos among the devices") { web.device(id) }.let { listed ->
                    assertEquals("Living Room", listed["name"]!!.jsonPrimitive.content)
                    assertEquals("speaker", listed["kind"]!!.jsonPrimitive.content)
                    assertEquals("Sonos", listed["detail"]!!.jsonPrimitive.content)
                }

                // The web player's queue moves to the Sonos.
                val json = Json { encodeDefaults = false }
                val queue = listOf(song, song2).joinToString(",") { json.encodeToString(ApiTrack.serializer(), it) }
                web.socket.send("""{"type":"state","state":{"track":${json.encodeToString(ApiTrack.serializer(), song)},"queue":[$queue],"index":0,"positionMs":0,"durationMs":200000,"playing":false}}""")
                eventually("the web player's state") { web.state("web-device-1") }
                web.socket.send("""{"type":"transfer","to":"$id"}""")

                // It's given an address on the phone, which serves it the song, with what it is for the Sonos app.
                eventually("the Sonos to play") { sonos.state.takeIf { it == "PLAYING" } }
                assertTrue(sonos.uri, sonos.uri.startsWith("http://127.0.0.1:${relay.port}/s/"))
                assertEquals(listOf("Stop", "SetAVTransportURI", "Play"), sonos.actions().filter { it in setOf("Stop", "SetAVTransportURI", "Play") }.takeLast(3))
                assertEquals("Neon Tidepool", title(sonos))
                assertEquals("Velvet Satellites", SonosXml.tag(sonos.metadata, "creator"))
                assertEquals("object.item.audioItem.musicTrack", SonosXml.tag(sonos.metadata, "class"))
                assertTrue(sonos.metadata, "http-get:*:audio/flac:*" in sonos.metadata)
                assertArrayEquals(test.audio, lan.newCall(Request.Builder().url(sonos.uri).build()).execute().use { it.body.bytes() })
                eventually("the web player sees it playing") { web.playing(id)?.takeIf { it } }
                assertEquals("Neon Tidepool", web.song(id))
                assertEquals("Living Room", session.now.value?.speaker)

                // Paused and played again, carrying on rather than starting over.
                val loads = sonos.actions().count { it == "SetAVTransportURI" }
                web.command(id, "pause")
                eventually("paused") { sonos.state.takeIf { it == "PAUSED_PLAYBACK" } }
                eventually("shown paused") { web.playing(id)?.takeIf { !it } }
                web.command(id, "play")
                eventually("playing again") { sonos.state.takeIf { it == "PLAYING" } }
                assertEquals(loads, sonos.actions().count { it == "SetAVTransportURI" })

                // Its volume, Sonos's 0 to 100.
                web.command(id, "volume", ""","volume":0.5""")
                eventually("volume set") { sonos.volume.takeIf { it == 50 } }
                assertTrue("<Channel>Master</Channel><DesiredVolume>50</DesiredVolume>" in sonos.last("SetVolume")!!.body)

                // Seeking in the original file: the Sonos does it itself (through the relay, which passes byte ranges on).
                web.command(id, "seek", ""","positionMs":60000""")
                eventually("sought a minute in") { sonos.last("Seek")?.body?.takeIf { "<Unit>REL_TIME</Unit><Target>0:01:00</Target>" in it } }
                assertEquals(60_000, sonos.relTimeMs)
                assertEquals(loads, sonos.actions().count { it == "SetAVTransportURI" })
                eventually("the web player sees where it is") { web.state(id)?.get("positionMs")?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it >= 60_000 } }

                // The song ends: the next one plays.
                sonos.relTimeMs = 199_000
                Thread.sleep(2_000)
                sonos.state = "STOPPED"
                eventually("the next song") { title(sonos)?.takeIf { it == "Paper Lanterns" && sonos.state == "PLAYING" } }
                eventually("the web player sees the next song") { web.song(id)?.takeIf { it == "Paper Lanterns" } }

                // Someone plays something else on it, from the Sonos app: it's theirs now.
                sonos.uri = "x-sonos-spotify:spotify%3atrack%3a123"
                eventually("shown as not playing") { web.playing(id)?.takeIf { !it } }
                assertFalse(session.now.value?.playing == true)
                web.socket.close(1000, null)
            } finally {
                session.stop()
                relay.stop()
            }
        }
    }

    @Test
    fun `a station plays as Sonos radio, and pausing it stops it until it's played again`() = runBlocking<Unit> {
        FakeSonos().use { sonos ->
            val authed = OkHttpClient.Builder().addInterceptor(ServerApi.authInterceptor({ test.base }, { token })).build()
            val api = ServerApi(authed, { test.base }, { token })
            val station = ApiTrack(SourceType.RADIO, "groove-1", "Groove & Soul FM", "Radio", via = "radio-source")
            val info = SonosSpeaker.infoOf(SonosClient(lan, sonos.baseUrl).topology().single())
            val id = info.connectId
            val relay = StreamRelay(authed).apply { start() }
            val hub = ConnectClient(RuntimeEnvironment.getApplication(), authed, { test.base }, { token }, scope, kind = "speaker", fixedId = id, fixedName = info.name, detail = info.detail)
            val session = SpeakerSession(SonosSpeaker(info, lan), hub, api, relay, { test.base }, { 0 }, scope, clock = System::currentTimeMillis, settleMs = 300)
            session.start()
            try {
                val web = WebPlayer()
                eventually("the Sonos among the devices") { web.device(id) }
                val json = Json { encodeDefaults = false }
                val track = json.encodeToString(ApiTrack.serializer(), station)
                web.socket.send("""{"type":"state","state":{"track":$track,"queue":[$track],"index":0,"positionMs":0,"durationMs":0,"playing":false,"live":true}}""")
                eventually("the web player's state") { web.state("web-device-1") }
                web.socket.send("""{"type":"transfer","to":"$id"}""")

                eventually("the Sonos to play") { sonos.state.takeIf { it == "PLAYING" } }
                assertTrue(sonos.uri, sonos.uri.startsWith("x-rincon-mp3radio://127.0.0.1:${relay.port}/s/"))
                assertEquals("object.item.audioItem.audioBroadcast", SonosXml.tag(sonos.metadata, "class"))
                assertEquals("Groove & Soul FM", title(sonos))
                eventually("the web player sees it playing") { web.playing(id)?.takeIf { it } }

                // Sonos won't pause radio: it's stopped instead, and tuned in again when played.
                web.command(id, "pause")
                eventually("stopped") { sonos.state.takeIf { it == "STOPPED" } }
                eventually("shown paused") { web.playing(id)?.takeIf { !it } }
                val loads = sonos.actions().count { it == "SetAVTransportURI" }
                web.command(id, "play")
                eventually("tuned in again") { sonos.actions().count { it == "SetAVTransportURI" }.takeIf { it > loads && sonos.state == "PLAYING" } }
                assertTrue(sonos.uri.startsWith("x-rincon-mp3radio://"))
                web.socket.close(1000, null)
            } finally {
                session.stop()
                relay.stop()
            }
        }
    }

    @Test
    fun `what's sent to a Sonos, escaped as it expects, and what it says back`() = runBlocking<Unit> {
        FakeSonos().use { sonos ->
            val info = SonosSpeaker.infoOf(SonosClient(lan, sonos.baseUrl).topology().single())
            val speaker = SonosSpeaker(info, lan)
            assertEquals(listOf("flac", "alac", "mp3", "aac", "wav", "vorbis"), speaker.formats.codecs)
            assertFalse("opus" in speaker.formats.codecs)

            val url = "http://192.168.1.5:4000/s/abc?x=1&y=2"
            speaker.load(SpeakerMedia(url, "audio/mp4", "Rock & Roll <Live> \"Encore\"", "AC/DC & Friends", "Who's <Next>", "http://192.168.1.5:4000/s/art", 245_000))
            val body = sonos.last("SetAVTransportURI")!!.body
            // The song's details are XML inside the SOAP XML: escaped once inside, and again around it.
            assertTrue(body, "<CurrentURI>http://192.168.1.5:4000/s/abc?x=1&amp;y=2</CurrentURI>" in body)
            assertTrue(body, "&lt;dc:title&gt;Rock &amp;amp; Roll &amp;lt;Live&amp;gt; &amp;quot;Encore&amp;quot;&lt;/dc:title&gt;" in body)
            assertFalse("<dc:title>" in body)
            assertEquals("Rock & Roll <Live> \"Encore\"", title(sonos))
            assertEquals("Who's <Next>", SonosXml.unescape(SonosXml.tag(sonos.metadata, "album")!!))
            assertEquals("http://192.168.1.5:4000/s/art", SonosXml.unescape(SonosXml.tag(sonos.metadata, "albumArtURI")!!))
            assertTrue("""protocolInfo="http-get:*:audio/mp4:*" duration="0:04:05"""" in sonos.metadata)

            speaker.play()
            sonos.relTimeMs = 61_500
            val transport = speaker.transport()
            assertEquals("PLAYING", transport.state)
            assertEquals(61_000, transport.positionMs)
            assertEquals(url, transport.uri)
            assertTrue(speaker.seek(90_000))
            assertTrue("<Unit>REL_TIME</Unit><Target>0:01:30</Target>" in sonos.last("Seek")!!.body)
            assertEquals(90_000, speaker.transport().positionMs)

            val status = speaker.status()
            assertTrue(status.on)
            assertEquals(25, status.volume)
            assertEquals(100, status.maxVolume)
            speaker.setVolume(42)
            assertTrue("""<u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>42</DesiredVolume>""" in sonos.last("SetVolume")!!.body)
            speaker.pause()
            assertEquals("PAUSED_PLAYBACK", speaker.transport().state)

            // A station: Sonos's radio, which plays without an end (and can't be paused, only stopped).
            speaker.load(SpeakerMedia("http://192.168.1.5:4000/s/radio", "audio/mpeg", "Maddie's Radio", "Radio", live = true))
            assertEquals("x-rincon-mp3radio://192.168.1.5:4000/s/radio", sonos.uri)
            assertEquals("object.item.audioItem.audioBroadcast", SonosXml.tag(sonos.metadata, "class"))
            speaker.play()
            val refused = runCatching { speaker.pause() }.exceptionOrNull()
            assertTrue(refused is SonosException)
            assertEquals("Sonos refused Pause (error 701: it can't do that now)", refused!!.message)
            // Where it plays from is still ours to recognise (by the relay's private path).
            assertTrue(speaker.transport().uri.endsWith("/s/radio"))
        }
    }

    @Test
    fun `rooms playing together are one speaker, with the group's volume`() = runBlocking<Unit> {
        FakeSonos().use { sonos ->
            sonos.householdXml = household(sonos, kitchenJoined = true)
            val group = SonosClient(lan, sonos.baseUrl).topology().single()
            val info = SonosSpeaker.infoOf(group)
            assertEquals("Living Room + Kitchen", info.name)
            assertEquals(2, info.rooms)
            assertEquals("Sonos · 2 rooms", info.detail)
            assertEquals("127.0.0.1", info.host)
            val speaker = SonosSpeaker(info, lan)
            speaker.setVolume(70)
            assertEquals(listOf("SnapshotGroupVolume", "SetGroupVolume"), sonos.actions().drop(1))
            assertEquals(70, sonos.groupVolume)
            assertEquals(70, speaker.status().volume)

            // The kitchen leaves: the same speaker, renamed, with its own volume again.
            val alone = SonosSpeaker.infoOf(SonosClient(lan, sonos.baseUrl).also { sonos.householdXml = null }.topology().single())
            assertTrue(speaker.update(alone))
            assertEquals("Living Room", speaker.info.name)
            speaker.setVolume(30)
            assertEquals(30, sonos.volume)
            // Another speaker altogether isn't an update.
            assertFalse(speaker.update(alone.copy(deviceId = "RINCON_OTHER")))
        }
    }

    /** The living room (this fake, the coordinator), with the kitchen joined to it or on its own. */
    private fun household(sonos: FakeSonos, kitchenJoined: Boolean, kitchen: FakeSonos? = null): String {
        val kitchenUuid = kitchen?.uuid ?: "RINCON_KITCHEN01400"
        val kitchenLocation = (kitchen?.baseUrl ?: "http://192.0.2.5:1400") + "/xml/device_description.xml"
        val living = """<ZoneGroupMember UUID="${sonos.uuid}" Location="${sonos.baseUrl}/xml/device_description.xml" ZoneName="Living Room"/>"""
        val kitchenMember = """<ZoneGroupMember UUID="$kitchenUuid" Location="$kitchenLocation" ZoneName="Kitchen"/>"""
        return if (kitchenJoined) {
            """<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="${sonos.uuid}" ID="${sonos.uuid}:1">$living$kitchenMember</ZoneGroup></ZoneGroups></ZoneGroupState>"""
        } else {
            """<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="${sonos.uuid}" ID="${sonos.uuid}:1">$living</ZoneGroup>""" +
                """<ZoneGroup Coordinator="$kitchenUuid" ID="$kitchenUuid:2">$kitchenMember</ZoneGroup></ZoneGroups></ZoneGroupState>"""
        }
    }

    @Test
    fun `Sonos added by address show up as the person's devices, follow their groups, and aren't doubled with the server's`() = runBlocking<Unit> {
        FakeSonos().use { living ->
            FakeSonos(uuid = "RINCON_KITCHEN01400", room = "Kitchen").use { kitchen ->
                living.householdXml = household(living, kitchenJoined = false, kitchen = kitchen)
                kitchen.householdXml = living.householdXml
                val settings = MutableStateFlow(Settings(server = test.base, token = token, speakers = true, speakerAddresses = listOf(living.address)))
                val network = MutableStateFlow(NetworkState(NetworkClass.WIFI))
                var serverDevices = emptyList<ConnectDevice>()
                val authed = OkHttpClient.Builder().addInterceptor(ServerApi.authInterceptor({ test.base }, { token })).build()
                val speakers = Speakers(
                    RuntimeEnvironment.getApplication(), authed, lan, ServerApi(authed, { test.base }, { token }), settings, network, scope,
                    devices = { serverDevices },
                )
                speakers.start()
                val web = WebPlayer()
                try {
                    // Both rooms, each on its own.
                    eventually("both rooms found", timeoutMs = 20_000) { speakers.found.value.map { it.name }.takeIf { it == listOf("Kitchen", "Living Room") } }
                    eventually("both among the devices") { web.speakers().map { it["name"]!!.jsonPrimitive.content }.sorted().takeIf { it == listOf("Kitchen", "Living Room") } }
                    val livingId = "sonos-rincon000e58a0b1c201400"

                    // The kitchen joins the living room (in the Sonos app): one speaker, renamed in place.
                    living.householdXml = household(living, kitchenJoined = true, kitchen = kitchen)
                    kitchen.householdXml = living.householdXml
                    speakers.refresh()
                    assertEquals(listOf("Living Room + Kitchen"), speakers.found.value.map { it.name })
                    eventually("the group among the devices") {
                        web.speakers().takeIf { it.size == 1 && it.single()["name"]!!.jsonPrimitive.content == "Living Room + Kitchen" }
                    }.single().let { group ->
                        assertEquals(livingId, group["id"]!!.jsonPrimitive.content)
                        assertEquals("Sonos · 2 rooms", group["detail"]!!.jsonPrimitive.content)
                    }

                    // The server plays on this Sonos itself (it's at home too): the phone leaves it to the server.
                    serverDevices = listOf(ConnectDevice("speaker:sonos-${living.uuid}", "Living Room + Kitchen", kind = "speaker"))
                    speakers.refresh()
                    assertTrue(speakers.found.value.isEmpty())
                    assertNull(speakers.problemOf(living.uuid))
                    eventually("gone from the devices") { web.speakers().takeIf { it.isEmpty() } }
                } finally {
                    web.socket.close(1000, null)
                    settings.value = settings.value.copy(speakers = false)
                }
            }
        }
    }

    @Test
    fun `a search on the Wi-Fi leaves Sonos's answers to Sonos`() {
        val sonos = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.20:1400/xml/device_description.xml\r\nSERVER: Linux UPnP/1.0 Sonos/83.1-61240 (ZPS27)\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nX-RINCON-HOUSEHOLD: Sonos_abc\r\n\r\n"
        assertEquals("http://192.168.1.20:1400/xml/device_description.xml", Ssdp.locationOf(sonos))
        assertEquals("http://192.168.1.20:1400", com.music.player.speakers.sonos.SonosDiscovery.baseOf(sonos))
        val info: SpeakerInfo = SonosSpeaker.infoOf(
            com.music.player.speakers.sonos.SonosGroup(
                com.music.player.speakers.sonos.SonosMember("RINCON_1", "Den", "http://192.168.1.20:1400"),
                listOf(com.music.player.speakers.sonos.SonosMember("RINCON_1", "Den", "http://192.168.1.20:1400")),
            ),
        )
        assertEquals("http://192.168.1.20:1400/MediaRenderer/AVTransport/Control", info.transportUrl)
        assertEquals("Sonos", info.detail)
        assertEquals("sonos-rincon1", info.connectId)
    }
}
