package com.music.server

import com.music.player.sources.subsonic.SubsonicSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/** A Subsonic server (Navidrome, say) as a library: added, searched first, played and its covers shown. */
class SubsonicTest {
    private val audio = ByteArray(1000) { (it % 251).toByte() }
    private val asked = CopyOnWriteArrayList<HttpUrl>()

    private fun ok(body: String) = MockResponse().setBody(
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.53.3","openSubsonic":true${if (body.isEmpty()) "" else ",$body"}}}""",
    )

    private fun failed(code: Int, message: String) =
        MockResponse().setBody("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}""")

    private val song = """{"id":"nd-1","title":"Fool's Gold","artist":"The Stone Roses","album":"The Stone Roses","albumId":"al1","coverArt":"al-al1",
        "duration":592,"bitRate":900,"suffix":"flac","contentType":"audio/flac","type":"music","isrc":["GBARL8900001"]}"""

    private fun navidrome(): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                asked += url
                val salt = url.queryParameter("s")
                if (url.queryParameter("u") != "maddie" || salt == null || url.queryParameter("t") != SubsonicSource.token("hunter22", salt)) {
                    return failed(40, "Wrong username or password")
                }
                return when (url.encodedPath.removePrefix("/rest/").removeSuffix(".view")) {
                    "ping" -> ok("")
                    "search3" -> {
                        val query = url.queryParameter("query").orEmpty().lowercase()
                        val match = (url.queryParameter("songCount")?.toInt() ?: 0) > 0 && (query.isEmpty() || "fool" in query || "love" in query)
                        ok(""""searchResult3":{"song":[${if (match) song else ""}]}""")
                    }
                    "getPlaylists" -> ok(""""playlists":{"playlist":[{"id":"pl1","name":"Madchester","songCount":1,"coverArt":"pl-pl1"}]}""")
                    "getPlaylist" -> if (url.queryParameter("id") == "pl1") ok(""""playlist":{"id":"pl1","name":"Madchester","entry":[$song]}""") else failed(70, "Not found")
                    "getSong" -> if (url.queryParameter("id") == "nd-1") ok(""""song":$song""") else failed(70, "Not found")
                    "getCoverArt" -> MockResponse().setHeader("Content-Type", "image/jpeg").setBody("JPEG ${url.queryParameter("id")} ${url.queryParameter("size")}")
                    "stream" -> {
                        if (url.queryParameter("id") != "nd-1" || url.queryParameter("format") != "raw") return MockResponse().setResponseCode(404)
                        if (request.getHeader("Range") == "bytes=100-199") {
                            MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/flac")
                                .setHeader("Content-Range", "bytes 100-199/1000").setBody(Buffer().write(audio.copyOfRange(100, 200)))
                        } else {
                            MockResponse().setHeader("Content-Type", "audio/flac").setBody(Buffer().write(audio))
                        }
                    }
                    else -> failed(0, "Unknown")
                }
            }
        }
        start()
    }

    @Test
    fun `a Subsonic server is added, searched first and played, its credentials never leaving the server`() {
        val navidrome = navidrome()
        val address = navidrome.url("/").toString().trimEnd('/')
        // The mock server is on this machine, which people's own sources can't be unless allowed.
        val config = ServerConfig(dataDir = java.nio.file.Files.createTempDirectory("samm-test").toFile(), adminPassword = "break-glass-pw", localSources = true)
        try {
            serverTest(testServices(config)) { services ->
                services.users.create("maddie", password = "correct horse")
                val user = browser()
                user.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }

                // Offered in Settings › Sources.
                val kinds = user.get("/api/sources/kinds").obj()["kinds"]!!.jsonArray.map { it.jsonObject }
                val kind = kinds.first { it.str("kind") == "SUBSONIC" }
                assertEquals(listOf("serverUrl", "username", "password", "apiKey"), kind["fields"]!!.jsonArray.map { it.jsonObject.str("key") })

                val wrong = user.post("/api/sources") { csrf(); json("""{"kind":"SUBSONIC","fields":{"serverUrl":"$address","username":"maddie","password":"nope"}}""") }
                assertEquals(HttpStatusCode.BadRequest, wrong.status)
                assertTrue(wrong.bodyAsText(), "Wrong username or password" in wrong.bodyAsText())

                val added = user.post("/api/sources") { csrf(); json("""{"kind":"SUBSONIC","fields":{"serverUrl":"$address","username":"maddie","password":"hunter22"}}""") }
                assertEquals(HttpStatusCode.OK, added.status)
                assertFalse("hunter22" in added.bodyAsText())
                val source = added.obj()
                assertTrue(source.str("name"), source.str("name").startsWith("Navidrome ("))
                assertEquals(listOf("password"), source["secretsSet"]!!.jsonArray.map { it.jsonPrimitive.content })
                assertEquals("TOKEN", source["settings"]!!.jsonObject.str("auth"))
                // The password is kept (every request is signed with it), encrypted.
                val raw = services.db.tx { one("SELECT secrets FROM sources WHERE id = ?", source.str("id")) { it.string("secrets") } }!!
                assertFalse("hunter22" in raw)

                val tested = user.post("/api/sources/${source.str("id")}/test") { csrf() }.obj()
                assertEquals(tested.toString(), "true", tested.str("ok"))
                assertTrue(tested.str("message"), "Navidrome 0.53.3" in tested.str("message"))

                // Searching finds the library's song (YouTube Music is shared but hidden for this test).
                val youtube = services.sources.allGlobal().first().id
                user.put("/api/sources/$youtube/hidden") { csrf(); json("""{"hidden":true}""") }
                val search = user.get("/api/search?q=fools+gold")
                val groups = search.obj()["groups"]!!.jsonArray
                val track = groups.single().jsonObject["tracks"]!!.jsonArray.single().jsonObject
                assertEquals("SUBSONIC", track.str("source"))
                assertEquals("Fool's Gold", track.str("title"))
                assertEquals("GBARL8900001", track.str("isrc"))

                // Its cover comes through the server, which signs the request to Subsonic; the
                // address the app gets has no credentials in it.
                val art = track.str("artworkUrl")
                assertTrue(art, art.startsWith("/api/art?u="))
                val upstream = URLDecoder.decode(art.substringAfter("u=").substringBefore('&'), Charsets.UTF_8).toHttpUrl()
                assertEquals(listOf("id", "size"), upstream.queryParameterNames.sorted())
                val cover = user.get("$art&px=200")
                assertEquals(HttpStatusCode.OK, cover.status)
                assertEquals("JPEG al-al1 256", cover.bodyAsText())

                // Playing it: the original file, seekable, relayed by the server.
                val resolved = user.post("/api/play/resolve") { csrf(); json("""{"track":$track,"kbps":0,"codecs":["flac","mp3","aac","opus"]}""") }.obj()
                assertEquals("true", resolved.str("seekable"))
                val url = resolved.str("streamUrl")
                assertFalse(url, "t=" in url || "hunter22" in url)
                assertArrayEquals(audio, user.get(url).readRawBytes())
                val part = user.get(url) { header("Range", "bytes=100-199") }
                assertEquals(HttpStatusCode.PartialContent, part.status)
                assertArrayEquals(audio.copyOfRange(100, 200), part.readRawBytes())

                // A song found on another service plays from the library when it has it.
                val elsewhere = """{"source":"YOUTUBE_MUSIC","sourceId":"yt-fg","title":"Fools Gold (Remastered)","artist":"The Stone Roses","durationMs":591000}"""
                val fromLibrary = user.post("/api/play/resolve") { csrf(); json("""{"track":$elsewhere,"kbps":0}""") }.obj()
                assertEquals("SUBSONIC", fromLibrary["track"]!!.jsonObject.str("source"))
                assertEquals("nd-1", fromLibrary["track"]!!.jsonObject.str("sourceId"))

                // Its playlists can be brought in, their songs as the library has them.
                val playlists = user.get("/api/sources/${source.str("id")}/playlists").json().jsonArray
                assertEquals("Madchester", playlists.single().jsonObject.str("name"))
                assertTrue(playlists.single().jsonObject.str("artworkUrl").startsWith("/api/art?u="))
                val imported = user.post("/api/playlists/import") { csrf(); json("""{"source":"${source.str("id")}","playlist":"pl1"}""") }.obj()
                assertEquals("1", imported.str("found"))
                val entry = imported["playlist"]!!.jsonObject["entries"]!!.jsonArray.single().jsonObject["track"]!!.jsonObject
                assertEquals("nd-1", entry.str("sourceId"))
                assertEquals(source.str("id"), entry.str("via"))
                assertEquals("Madchester", imported["playlist"]!!.jsonObject["summary"]!!.jsonObject.str("name"))

                // The stream test shows the host only.
                val report = user.get("/api/diagnostics/stream?via=${source.str("id")}&id=nd-1&kbps=0").bodyAsText()
                assertTrue(report, "Found it in" in report)
                assertFalse(report, "t=" in report || "hunter22" in report)

                // Every request to Subsonic was signed with a token, never the password.
                assertTrue(asked.isNotEmpty())
                assertTrue(asked.none { it.queryParameter("p") != null || "hunter22" in it.toString() })
            }
        } finally {
            navidrome.shutdown()
        }
    }
}
