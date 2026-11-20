package com.music.server.speakers

import com.music.player.net.Http
import com.music.server.browser
import com.music.server.csrf
import com.music.server.json
import com.music.server.obj
import com.music.server.serverTest
import com.music.server.speakers.musicassistant.MaPlayer
import com.music.server.speakers.musicassistant.MusicAssistantClient
import com.music.server.speakers.musicassistant.MusicAssistantRenderer
import com.music.server.str
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAssistantTest {
    private val http = Http.createClient()

    @Test
    fun `it signs in with a token and lists the players, however many pieces they come in`() = runBlocking {
        FakeMusicAssistant().use { ma ->
            val none = runCatching { MusicAssistantClient(http, ma.url, null).use { it.players() } }.exceptionOrNull()
            assertEquals("Music Assistant needs a token: make a long-lived token in its web interface", none?.message)
            val wrong = runCatching { MusicAssistantClient(http, ma.url, "nope").use { it.players() } }.exceptionOrNull()
            assertTrue(wrong?.message, wrong?.message?.startsWith("Music Assistant didn't accept the token") == true)

            MusicAssistantClient(http, ma.url, "ma-token").use { client ->
                val players = client.players()
                assertEquals(listOf("follower", "gone", "hidden", "kitchen", "office"), players.map { it.id })
                val kitchen = players.single { it.id == "kitchen" }
                assertEquals("Kitchen", kitchen.name)
                assertEquals("Chromecast", kitchen.kindLabel)
                assertEquals("AirPlay", players.single { it.id == "office" }.kindLabel)
                assertTrue(players.single { it.id == "hidden" }.hidden)
                assertEquals("office", players.single { it.id == "follower" }.syncedTo)
                assertFalse(players.single { it.id == "gone" }.available)
                assertEquals("2.7.2", (client.serverInfo!!["server_version"] as JsonPrimitive).content)
                // Asked on the same connection, after signing in once.
                client.players()
                assertEquals(listOf("auth", "players/all", "players/all"), ma.calls.takeLast(3).map { it.command })
            }
        }
        // An older Music Assistant (before tokens) needs none.
        FakeMusicAssistant(schema = 27).use { old ->
            assertEquals(5, MusicAssistantClient(http, old.url, null).use { it.players() }.size)
        }
    }

    @Test
    fun `addresses people type become Music Assistant's web address`() {
        assertEquals("http://homeassistant.local:8095", MusicAssistantClient.normalize("homeassistant.local"))
        assertEquals("http://192.168.1.5:8095", MusicAssistantClient.normalize("ws://192.168.1.5:8095/ws"))
        assertEquals("https://ma.example.com", MusicAssistantClient.normalize("https://ma.example.com/"))
        assertEquals("http://10.0.0.2:9000", MusicAssistantClient.normalize("http://10.0.0.2:9000"))
        assertEquals(null, MusicAssistantClient.normalize("  "))
        assertEquals("Squeezebox", MaPlayer("x", "x", "squeezelite--abc", true, null, false).kindLabel)
    }

    @Test
    fun `a song goes to a player with what to show, and the player is controlled and watched`() = runBlocking {
        FakeMusicAssistant().use { ma ->
            MusicAssistantClient(http, ma.url, "ma-token").use { client ->
                val renderer = MusicAssistantRenderer(client, "kitchen")
                val url = "http://192.168.1.10:8080/api/speakers/stream/abc/SIG-1.flac"
                val media = SpeakerMedia(url, "audio/flac", "Rock & Roll", "AC/DC", "High Voltage", "http://192.168.1.10:8080/api/art?u=x&s=y&px=600", 245_000, marker = "SIG-1")
                renderer.load(media)
                val sent = ma.last("player_queues/play_media")!!.args
                assertEquals("kitchen", sent.str("queue_id"))
                assertEquals("replace", sent.str("option"))
                val item = sent["media"]!!.jsonArray.single().jsonObject
                assertEquals("track", item.str("media_type"))
                assertEquals("builtin", item.str("provider"))
                assertEquals(url, item.str("item_id"))
                assertEquals("Rock & Roll", item.str("name"))
                assertEquals("AC/DC", item["artists"]!!.jsonArray.single().jsonObject.str("name"))
                assertEquals("High Voltage", item["album"]!!.jsonObject.str("name"))
                assertEquals("245", item.str("duration"))
                assertEquals(media.artUrl, item["metadata"]!!.jsonObject["images"]!!.jsonArray.single().jsonObject.str("path"))

                ma.players["kitchen"]!!.elapsed = 61.5
                ma.players["kitchen"]!!.volume = 35
                val status = renderer.status()
                assertEquals(Transport.PLAYING, status.transport)
                assertEquals(61_500, status.positionMs)
                assertTrue(media.marker in status.playing)
                assertEquals(0.35, status.volume!!, 0.001)

                renderer.pause()
                assertEquals(Transport.PAUSED, renderer.status().transport)
                renderer.seek(90_000)
                assertEquals("90", ma.last("players/cmd/seek")!!.args.str("position"))
                renderer.setVolume(0.42)
                assertEquals(42, ma.players["kitchen"]!!.volume)
                renderer.stop()
                assertEquals(Transport.STOPPED, renderer.status().transport)

                // A Music Assistant that won't take a described item gets the bare address.
                ma.strict = true
                renderer.load(media.copy(live = true))
                assertEquals(url, ma.last("player_queues/play_media")!!.args.str("media"))
                assertTrue("SIG-1" in renderer.status().playing)
            }
        }
    }

    @Test
    fun `Music Assistant's players are devices, music moves to one, and its queue goes on from the server`() {
        FakeJellyfin().use { jellyfin ->
            FakeMusicAssistant().use { ma ->
                serverTest { services ->
                    val library = jellyfin.share(services)
                    val admin = browser()
                    admin.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }
                    // Set up like any shared source; a wrong token is refused there and then.
                    val wrong = admin.post("/api/sources") { csrf(); json("""{"kind":"MUSIC_ASSISTANT","scope":"global","fields":{"serverUrl":"${ma.url}","token":"nope"}}""") }
                    assertEquals(HttpStatusCode.BadRequest, wrong.status)
                    assertTrue(wrong.bodyAsText(), "didn't accept the token" in wrong.bodyAsText())
                    val added = admin.post("/api/sources") {
                        csrf(); json("""{"kind":"MUSIC_ASSISTANT","scope":"global","fields":{"serverUrl":"${ma.url}","token":"ma-token","speakerUrl":"http://speakers.test:8080"}}""")
                    }
                    assertEquals(HttpStatusCode.OK, added.status)
                    val source = added.obj()
                    assertFalse("ma-token" in added.bodyAsText())
                    assertEquals(listOf("token"), source["secretsSet"]!!.jsonArray.map { it.jsonPrimitive.content })
                    val tested = admin.post("/api/sources/${source.str("id")}/test") { csrf() }.obj()
                    assertEquals("true", tested.str("ok"))
                    assertEquals("Working: Music Assistant 2.7.2 with 2 players (Kitchen, Office)", tested.str("message"))

                    services.users.create("maddie", password = "correct horse")
                    val speakers = Speakers(services, discovery = { emptySet() }, timing = Speakers.Timing(settleMs = 400, busyPollMs = 80, idlePollMs = 150))
                    speakers.sync()
                    val web = device(token("maddie"), "web-device-1", "Chrome")
                    val listed = web.next("devices")
                    // Hidden, unavailable and grouped-in players aren't places to play.
                    assertEquals(
                        listOf("speaker:ma-kitchen", "speaker:ma-office"),
                        listed["devices"]!!.jsonArray.map { it.jsonObject.str("id") }.filter { it.startsWith("speaker:") }.sorted(),
                    )
                    assertEquals("Music Assistant · Chromecast", listed.deviceOf("speaker:ma-kitchen")!!.str("detail"))

                    val id = "speaker:ma-kitchen"
                    val one = song("s1", library.id, title = "Neon Tidepool")
                    val two = song("s2", library.id, title = "Paper Lanterns")
                    web.send(Frame.Text("""{"type":"state","state":{"track":$one,"queue":[$one,$two],"index":0,"positionMs":0,"durationMs":200000,"playing":true}}"""))
                    web.send(Frame.Text("""{"type":"transfer","to":"$id"}"""))
                    eventually(what = "Music Assistant to play") { ma.text(ma.playing("kitchen")?.get("name")) == "Neon Tidepool" }
                    val address = ma.text(ma.playing("kitchen")!!["item_id"])!!
                    assertTrue(address, address.startsWith("http://speakers.test:8080/api/speakers/stream/"))
                    web.devicesWhen(id) { it.track()?.str("title") == "Neon Tidepool" && it.state()?.str("playing") == "true" }

                    // Music Assistant plays it to the end and stops: the server sends the next.
                    ma.players["kitchen"]!!.elapsed = 195.0
                    web.devicesWhen(id) { it.state()?.str("positionMs")?.toLong()?.let { p -> p >= 190_000 } == true }
                    ma.players["kitchen"]!!.state = "idle"
                    eventually(what = "the next song") { ma.text(ma.playing("kitchen")?.get("name")) == "Paper Lanterns" }
                    web.devicesWhen(id) { it.track()?.str("title") == "Paper Lanterns" }

                    // Controlled like any device.
                    web.send(Frame.Text("""{"type":"command","target":"$id","command":{"action":"pause"}}"""))
                    eventually(what = "pause") { ma.players["kitchen"]!!.state == "paused" }
                    web.send(Frame.Text("""{"type":"command","target":"$id","command":{"action":"volume","volume":0.6}}"""))
                    eventually(what = "volume") { ma.players["kitchen"]!!.volume == 60 }
                    web.send(Frame.Text("""{"type":"command","target":"$id","command":{"action":"play"}}"""))
                    eventually(what = "play") { ma.players["kitchen"]!!.state == "playing" }
                    assertEquals("Paper Lanterns", ma.text(ma.playing("kitchen")?.get("name")))

                    // Music Assistant moving on to something of its own near the end (its "don't stop
                    // the music") doesn't stop the server's queue: here, the queue is at its end, so it stops.
                    ma.players["kitchen"]!!.elapsed = 196.0
                    web.devicesWhen(id) { it.state()?.str("positionMs")?.toLong()?.let { p -> p >= 190_000 } == true }
                    ma.players["kitchen"]!!.current = JsonObject(mapOf("name" to JsonPrimitive("Something similar")))
                    web.devicesWhen(id) { it.state()?.str("playing") == "false" }
                    assertEquals(2, ma.calls.count { it.command == "player_queues/play_media" })

                    // The address it was given plays for Music Assistant without signing in: the file as it is
                    // (Music Assistant converts for its players itself).
                    val fetched = createClient { }.get(address.removePrefix("http://speakers.test:8080"))
                    assertEquals(HttpStatusCode.OK, fetched.status)
                    assertEquals(null, fetched.headers["X-SAMM-Transcoded"])
                    speakers.close()
                }
            }
        }
    }
}
