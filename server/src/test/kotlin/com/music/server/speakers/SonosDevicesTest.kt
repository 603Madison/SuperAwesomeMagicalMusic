package com.music.server.speakers

import com.music.server.serverTest
import com.music.server.sources.SourceKind
import com.music.server.speakers.sonos.SonosXml
import com.music.server.str
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Sonos as one of the person's devices: the server plays the queue on it, and their apps control it. */
class SonosDevicesTest {
    private val timing = Speakers.Timing(settleMs = 400, busyPollMs = 80, idlePollMs = 150)

    @Test
    fun `a shared Sonos shows up in everyone's devices, plays what's moved to it, moves on by itself and does what it's told`() {
        FakeJellyfin().use { jellyfin ->
            FakeSonos().use { sonos ->
                serverTest { services ->
                    val library = jellyfin.share(services)
                    services.sources.create(
                        ownerId = null, kind = SourceKind.SONOS, name = "Sonos",
                        settings = buildJsonObject {
                            put("discover", false)
                            put("addresses", sonos.address)
                            put("speakerUrl", "http://speakers.test:8080")
                        },
                        secrets = buildJsonObject { },
                    )
                    services.users.create("maddie", password = "correct horse")
                    services.users.create("sam", password = "correct horse")
                    val speakers = Speakers(services, discovery = { emptySet() }, timing = timing)
                    speakers.sync()
                    val id = "speaker:sonos-${sonos.uuid}"

                    val web = device(token("maddie"), "web-device-1", "Chrome")
                    val listed = web.next("devices").deviceOf(id)!!
                    assertEquals("Living Room", listed.str("name"))
                    assertEquals("speaker", listed.str("kind"))
                    assertEquals("Sonos", listed.str("detail"))
                    val sams = device(token("sam"), "sam-device-1", "Sam's laptop")
                    assertTrue(sams.next("devices").deviceOf(id) != null)

                    // The web player plays two songs, then moves them to the Sonos part-way through the first.
                    val one = song("s1", library.id, title = "Neon Tidepool")
                    val two = song("s2", library.id, title = "Paper Lanterns")
                    web.send(Frame.Text("""{"type":"state","state":{"track":$one,"queue":[$one,$two],"index":0,"positionMs":30000,"durationMs":200000,"playing":true,"volume":0.8}}"""))
                    web.send(Frame.Text("""{"type":"transfer","to":"$id"}"""))
                    assertEquals("pause", web.next("command")["command"]!!.jsonObject.str("action"))
                    eventually(what = "the Sonos to play") { sonos.state == "PLAYING" }
                    assertTrue(sonos.uri, sonos.uri.startsWith("http://speakers.test:8080/api/speakers/stream/"))
                    assertEquals("Neon Tidepool", SonosXml.unescape(SonosXml.tag(sonos.metadata, "title")!!))
                    // Part-way: converted from there (the speaker gets the rest of the song).
                    assertTrue(sonos.uri.endsWith(".flac"))
                    assertTrue(sonos.metadata, Regex("""duration="0:02:(49|50)"""").containsMatchIn(sonos.metadata))

                    // Its state shows in the person's devices, as the one playing.
                    val maddieId = services.users.byUsername("maddie")!!.id
                    eventually(what = "hub ${services.connect.devices(maddieId)}") { services.connect.devices(maddieId).any { it.id == id && it.state?.playing == true } }
                    val playing = web.devicesWhen(id) { it.track()?.str("title") == "Neon Tidepool" && it.state()?.str("playing") == "true" }.deviceOf(id)!!
                    assertEquals("true", playing.str("active"))
                    assertTrue(playing.state()!!.str("positionMs").toLong() >= 30_000)
                    // Not in someone else's: it's theirs while they play on it.
                    val samId = services.users.byUsername("sam")!!.id
                    assertNull(services.connect.devices(samId).single { it.id == id }.state?.track)

                    // The address it was given plays without signing in.
                    val path = sonos.uri.removePrefix("http://speakers.test:8080")
                    val fetched = createClient { }.get(path)
                    assertEquals(HttpStatusCode.OK, fetched.status)
                    // Converted from 30 seconds in (the pretend file isn't real audio, so what comes out is beside the point).
                    assertEquals("flac", fetched.headers["X-SAMM-Transcoded"])

                    // The song plays on, and ends: the next one goes on by itself, from the start, as it is.
                    sonos.relTimeMs = 160_000
                    web.devicesWhen(id) { it.state()?.str("positionMs")?.toLong()?.let { p -> p >= 190_000 } == true }
                    sonos.state = "STOPPED"
                    eventually(what = "the next song") { "Paper Lanterns" in sonos.metadata && sonos.state == "PLAYING" }
                    assertTrue(sonos.uri.endsWith(".flac"))
                    val second = web.devicesWhen(id) { it.track()?.str("title") == "Paper Lanterns" }.deviceOf(id)!!
                    assertEquals("1", second.state()!!.str("index"))
                    // The original file this time: it plays (and seeks) as it is.
                    val original = createClient { }.get(sonos.uri.removePrefix("http://speakers.test:8080"))
                    assertArrayEquals(jellyfin.audio, original.readRawBytes())

                    // Controlled from the web player like any device.
                    sonos.relTimeMs = 5_000
                    web.send(Frame.Text("""{"type":"command","target":"$id","command":{"action":"pause"}}"""))
                    eventually(what = "pause") { sonos.state == "PAUSED_PLAYBACK" }
                    web.devicesWhen(id) { it.state()?.str("playing") == "false" }
                    web.send(Frame.Text("""{"type":"command","target":"$id","command":{"action":"volume","volume":0.3}}"""))
                    eventually(what = "volume") { sonos.volume == 30 }
                    web.send(Frame.Text("""{"type":"command","target":"$id","command":{"action":"seek","positionMs":60000}}"""))
                    eventually(what = "seek") { sonos.last("Seek")?.body?.contains("<Target>0:01:00</Target>") == true }
                    val loads = sonos.actions().count { it == "SetAVTransportURI" }
                    web.send(Frame.Text("""{"type":"command","target":"$id","command":{"action":"play"}}"""))
                    eventually(what = "resume") { sonos.state == "PLAYING" }
                    // Paused and played again: the same song carried on, not loaded again.
                    assertEquals(loads, sonos.actions().count { it == "SetAVTransportURI" })
                    // Previous: back to the start of the song, and again soon after, to the one before.
                    web.send(Frame.Text("""{"type":"command","target":"$id","command":{"action":"previous"}}"""))
                    web.send(Frame.Text("""{"type":"command","target":"$id","command":{"action":"previous"}}"""))
                    eventually(what = "previous") { "Neon Tidepool" in sonos.metadata }

                    // Someone else's commands do nothing while it plays for Maddie.
                    val before = sonos.calls.size
                    sams.send(Frame.Text("""{"type":"command","target":"$id","command":{"action":"pause"}}"""))
                    kotlinx.coroutines.delay(300)
                    assertFalse(sonos.calls.drop(before).any { it.action == "Pause" })

                    // Taken over by the Sonos app: it lets go.
                    sonos.uri = "x-sonos-spotify:spotify%3atrack%3a123"
                    web.devicesWhen(id) { it.track() == null }

                    // Moving the music back to the web player from the Sonos (here, after another go on it).
                    web.send(Frame.Text("""{"type":"transfer","to":"$id"}"""))
                    eventually(what = "playing again") { sonos.state == "PLAYING" && "Neon Tidepool" in sonos.metadata }
                    web.devicesWhen(id) { it.state()?.str("playing") == "true" }
                    web.send(Frame.Text("""{"type":"transfer","to":"web-device-1"}"""))
                    val take = web.next("command") { it["command"]!!.jsonObject.str("action") == "take" }["command"]!!.jsonObject
                    assertEquals("Neon Tidepool", take["state"]!!.jsonObject["track"]!!.jsonObject.str("title"))
                    assertEquals(2, take["state"]!!.jsonObject["queue"]!!.jsonArray.size)
                    eventually(what = "the Sonos to pause") { sonos.state == "PAUSED_PLAYBACK" }

                    // Hiding the shared source takes the speaker out of that person's devices.
                    val sonosSource = services.sources.allGlobal().first { it.kind == SourceKind.SONOS }
                    services.sources.setHidden(services.users.byUsername("maddie")!!.id, sonosSource.id, true)
                    speakers.sync()
                    web.next("devices") { it.deviceOf(id) == null }
                    assertTrue(services.connect.devices(samId).any { it.id == id })
                    eventually(what = "it to stop for the person who hid it") { sonos.state == "STOPPED" }

                    // Turned off altogether: gone for everyone.
                    services.sources.update(sonosSource.id, enabled = false)
                    speakers.sync()
                    sams.next("devices") { it.deviceOf(id) == null }
                    assertTrue(speakers.players().isEmpty())
                    speakers.close()
                }
            }
        }
    }

    @Test
    fun `a song that can't play on a speaker says so under its name`() {
        FakeSonos().use { sonos ->
            serverTest { services ->
                services.sources.create(
                    ownerId = null, kind = SourceKind.SONOS, name = "Sonos",
                    settings = buildJsonObject { put("discover", false); put("addresses", sonos.address); put("speakerUrl", "http://speakers.test:8080") },
                    secrets = buildJsonObject { },
                )
                services.users.create("maddie", password = "correct horse")
                val speakers = Speakers(services, discovery = { emptySet() }, timing = timing)
                speakers.sync()
                val id = "speaker:sonos-${sonos.uuid}"
                val web = device(token("maddie"), "web-device-1", "Chrome")
                val channel = """{"source":"SIRIUSXM","sourceId":"channel:channel-linear:abc","title":"Lithium","artist":"","via":"sxm"}"""
                web.send(Frame.Text("""{"type":"state","state":{"track":$channel,"queue":[$channel],"index":0,"playing":true,"live":true}}"""))
                web.send(Frame.Text("""{"type":"transfer","to":"$id"}"""))
                val shown = web.devicesWhen(id) { it["detail"]?.jsonPrimitive?.content?.startsWith("Couldn't play") == true }.deviceOf(id)!!
                assertEquals("Couldn't play: SiriusXM channels can't play on speakers yet", shown.str("detail"))
                assertFalse("SetAVTransportURI" in sonos.actions())
                speakers.close()
            }
        }
    }
}
