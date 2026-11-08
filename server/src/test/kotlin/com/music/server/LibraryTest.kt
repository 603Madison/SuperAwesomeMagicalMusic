package com.music.server

import com.music.player.quality.StreamQuality
import com.music.player.sources.SourceStream
import com.music.server.music.OutputFormat
import com.music.server.util.Random
import com.music.server.util.SecretBox
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    /** A pretend Jellyfin with one song, whose file is [audio]. */
    private val audio = ByteArray(1000) { (it % 251).toByte() }

    private fun jellyfin(): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/Users/AuthenticateByName") ->
                        if ("hunter22" in request.body.readUtf8()) MockResponse().setBody("""{"AccessToken":"jf-token","User":{"Id":"u1"}}""")
                        else MockResponse().setResponseCode(401)
                    request.getHeader("Authorization")?.contains("jf-token") != true -> MockResponse().setResponseCode(401)
                    path.startsWith("/Users/u1/Items") || path.startsWith("/Items") -> MockResponse().setBody(
                        """{"Items":[{"Id":"song1","Name":"Fool's Gold","Artists":["The Stone Roses"],"Album":"The Stone Roses",
                           "RunTimeTicks":2400000000,"ImageTags":{"Primary":"t"},
                           "MediaSources":[{"Id":"ms1","Bitrate":900000,"MediaStreams":[{"Type":"Audio","Codec":"flac","BitRate":900000}]}]}]}""",
                    )
                    path.startsWith("/Audio/song1/stream") -> {
                        val range = request.getHeader("Range")
                        if (range == "bytes=100-199") {
                            MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/flac")
                                .setHeader("Content-Range", "bytes 100-199/1000").setBody(Buffer().write(audio.copyOfRange(100, 200)))
                        } else {
                            MockResponse().setHeader("Content-Type", "audio/flac").setBody(Buffer().write(audio))
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }

    @Test
    fun `secrets are sealed to their row`() {
        val box = SecretBox(Random.bytes(32))
        val sealed = box.seal("jf-token", "source:a")
        assertFalse("jf-token" in sealed)
        assertEquals("jf-token", box.open(sealed, "source:a"))
        assertThrows(Exception::class.java) { box.open(sealed, "source:b") }
    }

    @Test
    fun `a personal Jellyfin is signed in to, searched and streamed with seeking, its token never sent back`() {
        val server = jellyfin()
        try {
            serverTest { services ->
                services.users.create("maddie", password = "correct horse")
                val user = browser()
                user.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }

                val wrong = user.post("/api/sources") { csrf(); json("""{"kind":"JELLYFIN","fields":{"serverUrl":"${server.url("/")}","username":"maddie","password":"nope"}}""") }
                assertEquals(HttpStatusCode.BadRequest, wrong.status)

                val added = user.post("/api/sources") {
                    csrf(); json("""{"kind":"JELLYFIN","fields":{"serverUrl":"${server.url("/")}","username":"maddie","password":"hunter22"}}""")
                }
                assertEquals(HttpStatusCode.OK, added.status)
                val body = added.bodyAsText()
                assertFalse("jf-token" in body)
                assertFalse("hunter22" in body)
                val source = added.obj()
                assertEquals("personal", source.str("scope"))
                assertEquals(listOf("accessToken"), source["secretsSet"]!!.jsonArray.map { it.jsonPrimitive.content })

                // Stored encrypted.
                val raw = services.db.tx { one("SELECT secrets FROM sources WHERE id = ?", source.str("id")) { it.string("secrets") } }!!
                assertFalse("jf-token" in raw)

                // Someone else doesn't see it.
                val admin = browser()
                admin.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }
                assertTrue(admin.get("/api/sources").json().jsonArray.none { it.jsonObject.str("id") == source.str("id") })

                // Searching finds the library song (YouTube Music is shared but hidden for this test).
                val youtube = services.sources.allGlobal().first().id
                user.put("/api/sources/$youtube/hidden") { csrf(); json("""{"hidden":true}""") }
                val groups = user.get("/api/search?q=fools+gold").obj()["groups"]!!.jsonArray
                assertEquals(1, groups.size)
                val track = groups[0].jsonObject["tracks"]!!.jsonArray[0].jsonObject
                assertEquals("Fool's Gold", track.str("title"))
                assertTrue(track.str("artworkUrl").startsWith("/api/art?u="))

                // Playing it: the original file, seekable, through the server.
                val resolved = user.post("/api/play/resolve") { csrf(); json("""{"track":$track,"kbps":0,"codecs":["flac","mp3","aac","opus"]}""") }.obj()
                assertEquals("true", resolved.str("seekable"))
                assertEquals("false", resolved.str("transcoded"))
                val url = resolved.str("streamUrl")
                val whole = user.get(url)
                assertEquals(HttpStatusCode.OK, whole.status)
                assertArrayEquals(audio, whole.readRawBytes())
                val part = user.get(url) { header("Range", "bytes=100-199") }
                assertEquals(HttpStatusCode.PartialContent, part.status)
                assertEquals("bytes 100-199/1000", part.headers["Content-Range"])
                assertArrayEquals(audio.copyOfRange(100, 200), part.readRawBytes())

                // Other people can't stream from it.
                assertEquals(HttpStatusCode.NotFound, admin.get(url).status)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `artwork is only fetched for signed addresses, and only images`() = serverTest { services ->
        val images = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.path) {
                    "/cover.jpg" -> MockResponse().setHeader("Content-Type", "image/jpeg").setBody("JPEG")
                    else -> MockResponse().setHeader("Content-Type", "text/html").setBody("<script>alert(1)</script>")
                }
            }
            start()
        }
        try {
            val client = createClient { }
            val good = services.artwork.wrap(images.url("/cover.jpg").toString())!!
            assertEquals("JPEG", client.get(good).bodyAsText())
            assertEquals(HttpStatusCode.NotFound, client.get("/api/art?u=${images.url("/cover.jpg").toString().encodeURLParameter()}&s=forged").status)
            assertEquals(HttpStatusCode.NotFound, client.get(services.artwork.wrap(images.url("/page.html").toString())!!).status)
            assertEquals(images.url("/cover.jpg").toString(), services.artwork.unwrap(good))
            assertNull(services.artwork.unwrap("/api/art?u=x&s=forged"))
        } finally {
            images.shutdown()
        }
    }

    @Test
    fun `playlists keep order and refuse stale reorders`() = serverTest {
        val client = browser()
        client.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }
        fun song(n: Int) = """{"source":"YOUTUBE_MUSIC","sourceId":"id$n","title":"Song $n","artist":"Artist","durationMs":1000}"""
        val created = client.post("/api/playlists") { csrf(); json("""{"name":"Mix","tracks":[${song(1)},${song(2)}]}""") }.obj()
        val id = created["summary"]!!.jsonObject.str("id")
        val added = client.post("/api/playlists/$id/tracks") { csrf(); json("""{"tracks":[${song(3)}],"position":0}""") }.obj()
        val entries = added["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Song 3", "Song 1", "Song 2"), entries.map { it["track"]!!.jsonObject.str("title") })
        val revision = added["summary"]!!.jsonObject.str("revision")

        val reversed = entries.reversed().joinToString(",") { "\"${it.str("entryId")}\"" }
        val reordered = client.put("/api/playlists/$id/order") { csrf(); json("""{"revision":$revision,"entryIds":[$reversed]}""") }
        assertEquals(listOf("Song 2", "Song 1", "Song 3"), reordered.obj()["entries"]!!.jsonArray.map { it.jsonObject["track"]!!.jsonObject.str("title") })
        // The same revision again is out of date now.
        assertEquals(HttpStatusCode.Conflict, client.put("/api/playlists/$id/order") { csrf(); json("""{"revision":$revision,"entryIds":[]}""") }.status)

        val list = client.get("/api/playlists").json().jsonArray
        assertEquals("3", list[0].jsonObject.str("count"))
        assertEquals("3000", list[0].jsonObject.str("durationMs"))
    }

    @Test
    fun `streams are converted only when they have to be`() {
        val streamer = testServices().streamer
        val flac = SourceStream(url = "x", description = "FLAC", fileExtension = "flac", kbps = 1411, codec = "flac")
        val opus = SourceStream(url = "x", description = "Opus", fileExtension = "webm", kbps = 160, codec = "opus")
        val all = setOf("flac", "mp3", "aac", "opus")

        assertNull(streamer.plan(flac, StreamQuality.Original, all, 0.0).format)
        // Over the cap: converted down to it, as Opus where the player has it.
        val capped = streamer.plan(flac, StreamQuality.Capped(128), all, 0.0)
        assertEquals(OutputFormat.OPUS, capped.format)
        assertEquals(128, capped.kbps)
        // Safari without Opus gets AAC.
        val safari = streamer.plan(opus, StreamQuality.Original, setOf("mp3", "aac", "flac"), 0.0)
        assertEquals(OutputFormat.AAC, safari.format)
        // A player without FLAC still gets lossless where it can… or the best lossy copy.
        assertEquals(OutputFormat.FLAC, streamer.plan(flac.copy(codec = "alac", fileExtension = "m4a"), StreamQuality.Original, all, 0.0).format)
        assertEquals(OutputFormat.MP3, streamer.plan(flac, StreamQuality.Original, setOf("mp3"), 0.0).format)
        // Seeking in a stream that can't seek by itself needs converting from that point.
        val live = flac.copy(supportsRangeRequests = false, kbps = 128, codec = "mp3", fileExtension = "mp3")
        assertNull(streamer.plan(live, StreamQuality.Capped(128), all, 0.0).format)
        assertNotNull(streamer.plan(live, StreamQuality.Capped(128), all, 30.0).format)
    }
}
