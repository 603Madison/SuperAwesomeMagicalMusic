package com.music.server.speakers

import com.music.player.sources.SourceStream
import com.music.server.music.OutputFormat
import com.music.server.serverTest
import com.music.server.testServices
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/** The addresses speakers play from: they work without signing in, but only for what they name, and not for long. */
class SpeakerStreamsTest {
    private val base = "http://192.168.1.10:8080"
    private val sonos = SpeakerTarget("sonos-x", "Kitchen", "Sonos", "127.0.0.1", SpeakerTarget.Profile.SONOS, "k") { error("not played") }
    private val assistant = sonos.copy(id = "ma-x", profile = SpeakerTarget.Profile.MUSIC_ASSISTANT)

    @Test
    fun `a signed address plays the song it names, and nothing else, until it expires`() {
        val now = AtomicLong(System.currentTimeMillis())
        FakeJellyfin().use { jellyfin ->
            serverTest(testServices(clock = { now.get() })) { services ->
                val library = jellyfin.share(services)
                val maddie = services.users.create("maddie", password = "correct horse")
                val streams = services.speakerStreams
                val track = Json.decodeFromString(com.music.server.music.ApiTrack.serializer(), song("song1", library.id, title = "Neon Tidepool"))
                    .copy(artworkUrl = services.artwork.wrap("https://img.example/cover.jpg"))

                val media = streams.media(sonos, base, maddie.id, track, 0)
                assertTrue(media.url, media.url.startsWith("$base/api/speakers/stream/"))
                assertTrue(media.url.endsWith(".flac"))
                assertEquals("audio/flac", media.mime)
                assertTrue(media.seekable)
                assertEquals("Neon Tidepool", media.title)
                assertEquals(SpeakerStreams.markerOf(media.url), media.marker)
                // The cover through the server, which needs no sign-in either (it's signed too).
                assertTrue(media.artUrl!!.startsWith("$base/api/art?u="))
                assertTrue(media.artUrl!!.endsWith("&px=600"))

                // No sign-in, no cookie: the address alone plays the song, with seeking.
                val speaker = createClient { }
                val path = media.url.removePrefix(base)
                val whole = speaker.get(path)
                assertEquals(HttpStatusCode.OK, whole.status)
                assertArrayEquals(jellyfin.audio, whole.readRawBytes())
                val part = speaker.get(path) { header("Range", "bytes=100-199") }
                assertEquals(HttpStatusCode.PartialContent, part.status)
                assertArrayEquals(jellyfin.audio.copyOfRange(100, 200), part.readRawBytes())
                assertEquals(HttpStatusCode.OK, speaker.head(path).status)

                // Another song (or source, or start, or person) with the same signature: no.
                val (payload, file) = path.removePrefix(SpeakerStreams.PATH).split('/')
                val grant = String(Base64.getUrlDecoder().decode(payload))
                fun forged(change: (String) -> String) =
                    SpeakerStreams.PATH + Base64.getUrlEncoder().withoutPadding().encodeToString(change(grant).toByteArray()) + "/" + file
                assertTrue(grant, "\"i\":\"song1\"" in grant)
                assertEquals(HttpStatusCode.NotFound, speaker.get(forged { it.replace("\"i\":\"song1\"", "\"i\":\"song2\"") }).status)
                assertEquals(HttpStatusCode.NotFound, speaker.get(forged { it.replace("\"e\":", "\"e\":9") }).status)
                assertEquals(HttpStatusCode.NotFound, speaker.get(forged { it.replace("\"k\":\"song\"", "\"k\":\"station\"") }).status)
                assertEquals(HttpStatusCode.NotFound, speaker.get(SpeakerStreams.PATH + payload + "/forged.flac").status)
                assertEquals(HttpStatusCode.NotFound, speaker.get(SpeakerStreams.PATH + "nonsense/" + file).status)
                // It's no sign-in: the API is still closed to it.
                assertEquals(HttpStatusCode.Unauthorized, speaker.get("/api/stream?via=${library.id}&id=song1") { header("Authorization", "Bearer $payload") }.status)
                assertEquals(HttpStatusCode.Unauthorized, speaker.get("/api/sources") { header("Authorization", "Bearer ${file.substringBefore('.')}") }.status)

                // A second song gets its own address; the first doesn't play it.
                val other = streams.media(sonos, base, maddie.id, track.copy(sourceId = "song2", title = "Paper Lanterns"), 0)
                assertFalse(other.marker == media.marker)
                assertTrue(String(Base64.getUrlDecoder().decode(other.url.removePrefix(base + SpeakerStreams.PATH).substringBefore('/'))).contains("\"i\":\"song2\""))

                // Someone whose account is turned off can't be played for.
                services.users.update(maddie.id, disabled = true)
                assertEquals(HttpStatusCode.NotFound, speaker.get(path).status)
                services.users.update(maddie.id, disabled = false)
                assertEquals(HttpStatusCode.OK, speaker.get(path).status)

                // An hour after the song would have finished, it's gone.
                now.addAndGet(200_000 + 3_600_000 + 1_000)
                assertEquals(HttpStatusCode.NotFound, speaker.get(path).status)
                assertNull(streams.verify(payload, file))
            }
        }
    }

    @Test
    fun `a song started part-way is converted from there, and stations and channels are told apart`() {
        FakeJellyfin().use { jellyfin ->
            serverTest { services ->
                val library = jellyfin.share(services)
                val maddie = services.users.create("maddie", password = "correct horse")
                val streams = services.speakerStreams
                val track = Json.decodeFromString(com.music.server.music.ApiTrack.serializer(), song("song1", library.id))
                val partWay = streams.media(sonos, base, maddie.id, track, 30_000)
                assertFalse(partWay.seekable)
                assertEquals(30_000, partWay.offsetMs)
                assertEquals(170_000L, partWay.durationMs)
                assertEquals("audio/flac", partWay.mime)
                assertTrue(String(Base64.getUrlDecoder().decode(partWay.url.removePrefix(base + SpeakerStreams.PATH).substringBefore('/'))).contains("\"o\":30000"))

                // One of the person's own stations: an endless AAC stream.
                val station = com.music.server.music.ApiTrack(com.music.player.model.SourceType.RADIO, "radio", "Maddie's Radio", "", via = "personal")
                val live = streams.media(sonos, base, maddie.id, station, 0)
                assertTrue(live.live)
                assertEquals("audio/aac", live.mime)
                assertTrue(live.url.endsWith(".aac"))
                assertEquals("Radio", live.artist)

                val channel = com.music.server.music.ApiTrack(com.music.player.model.SourceType.SIRIUSXM, "channel:channel-linear:abc", "Lithium", "", via = "sxm")
                val refused = runCatching { streams.media(sonos, base, maddie.id, channel, 0) }.exceptionOrNull()
                assertEquals("SiriusXM channels can't play on speakers yet", refused?.message)
                val hls = station.copy(sourceId = "hls:abc", via = library.id)
                assertTrue(runCatching { streams.media(sonos, base, maddie.id, hls, 0) }.exceptionOrNull() is SpeakerException)
            }
        }
    }

    @Test
    fun `each kind of speaker gets audio it plays`() {
        val services = testServices()
        val streams = services.speakerStreams
        fun stream(codec: String, kbps: Int, ext: String) = SourceStream("http://x/a.$ext", description = codec, fileExtension = ext, kbps = kbps, codec = codec)
        // Sonos: CD-quality FLAC and MP3 as they are; hi-res FLAC and YouTube's Opus become MP3.
        assertNull(streams.plan(stream("flac", 900, "flac"), SpeakerTarget.Profile.SONOS, 0.0).format)
        assertNull(streams.plan(stream("mp3", 320, "mp3"), SpeakerTarget.Profile.SONOS, 0.0).format)
        assertNull(streams.plan(stream("aac", 256, "m4a"), SpeakerTarget.Profile.SONOS, 0.0).format)
        assertEquals(OutputFormat.MP3, streams.plan(stream("flac", 4608, "flac"), SpeakerTarget.Profile.SONOS, 0.0).format)
        assertEquals(320, streams.plan(stream("flac", 4608, "flac"), SpeakerTarget.Profile.SONOS, 0.0).kbps)
        assertEquals(OutputFormat.MP3, streams.plan(stream("opus", 160, "webm"), SpeakerTarget.Profile.SONOS, 0.0).format)
        // Started part-way: CD-quality stays lossless.
        assertEquals(OutputFormat.FLAC, streams.plan(stream("flac", 900, "flac"), SpeakerTarget.Profile.SONOS, 30.0).format)
        // Music Assistant converts for its players itself.
        assertNull(streams.plan(stream("opus", 160, "webm"), SpeakerTarget.Profile.MUSIC_ASSISTANT, 0.0).format)
        assertTrue(assistant.profile.code == "ma")
    }
}
