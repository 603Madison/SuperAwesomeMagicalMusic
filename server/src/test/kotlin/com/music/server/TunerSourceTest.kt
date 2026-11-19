package com.music.server

import com.music.player.sources.radio.RadioStation
import com.music.server.sources.SourceKind
import com.music.server.util.NetworkGuard
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A tuner source against a stand-in tuner (the tuner's API, as tuner/ serves it): its stations
 * listed with the other radio stations, played through the server, with what's on and its art,
 * and a station that's also online played from the better copy.
 */
class TunerSourceTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    /** The tuner's audio for 97.1: ADTS frames, or (for blending) real AAC. */
    @Volatile
    private var stream97: () -> MockResponse = { MockResponse().setHeader("Content-Type", "audio/aac").setBody(Buffer().write(adts(40))) }

    @Volatile
    private var score97 = 0.92

    private val tuner = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer tok") return MockResponse().setResponseCode(401).setBody("""{"error":"This tuner needs its token"}""")
                return when (request.path) {
                    "/api/status" -> json("""{"name":"Attic tuner","version":"1.30.0","region":"US","tuners":[{"index":0,"device":"0","state":"idle"}],"stations":2}""")
                    "/api/stations" -> json("[${station97()},$station101]")
                    "/api/stations/97.1" -> json(station97())
                    "/api/stations/101.5" -> json(station101)
                    "/api/stations/97.1/stream" -> stream97()
                    "/api/stations/101.5/stream" -> MockResponse().setResponseCode(503).setBody("""{"error":"All 1 tuner is busy (WXYZ FM). Try again when one is free."}""")
                    "/api/stations/97.1/logo", "/api/stations/97.1/art" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"No such station"}""")
                }
            }
        }
        start()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun station97() = """{"id":"97.1","frequency":"97.1","frequencyKhz":97100,"hd":false,"name":"WXYZ FM","callLetters":"WXYZ","genre":"Rock",
        "now":{"title":"Heroes","artist":"David Bowie","radiotext":"Heroes - David Bowie on WXYZ"},
        "signal":{"score":$score97,"detail":"Stereo, pilot 52 dB over the noise","measuredAt":1},"playing":true,"listeners":1,
        "logoUrl":"/api/stations/97.1/logo?v=ab12","artUrl":"/api/stations/97.1/art?v=cd34","streamUrl":"/api/stations/97.1/stream"}"""

    private val station101 = """{"id":"101.5","frequency":"101.5","frequencyKhz":101500,"hd":false,"name":"ROCK 101","callLetters":"KROC",
        "signal":{"score":0.4,"detail":"Mono","measuredAt":1},"streamUrl":"/api/stations/101.5/stream"}"""

    private fun adts(frames: Int): ByteArray = (0 until frames).map { frame(200) }.reduce { a, b -> a + b }

    private fun frame(length: Int): ByteArray {
        val b = ByteArray(length)
        b[0] = 0xFF.toByte(); b[1] = 0xF1.toByte(); b[2] = 0x4C.toByte()
        b[3] = (0x80 or ((length shr 11) and 0x03)).toByte()
        b[4] = ((length shr 3) and 0xFF).toByte()
        b[5] = (((length and 0x07) shl 5) or 0x1F).toByte()
        b[6] = 0xFC.toByte()
        return b
    }

    @After
    fun stop() = tuner.shutdown()

    /** Adds the tuner as a shared source (as the admin) and returns its id. */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.addTuner(services: Services, client: io.ktor.client.HttpClient): String {
        client.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }
        // Nothing from the real directory or YouTube Music here.
        services.tunerRadio.counterparts.search = { _, _ -> emptyList() }
        services.sources.allGlobal().filter { it.kind == SourceKind.YOUTUBE_MUSIC }.forEach { services.sources.delete(it.id) }
        val wrong = client.post("/api/sources") { csrf(); json("""{"kind":"TUNER","scope":"global","fields":{"serverUrl":"${tuner.url("/")}","token":"nope"}}""") }
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertTrue(wrong.bodyAsText(), wrong.bodyAsText().contains("didn't accept"))
        val added = client.post("/api/sources") { csrf(); json("""{"kind":"TUNER","scope":"global","fields":{"serverUrl":"${tuner.url("/")}","token":"tok"}}""") }
        assertEquals(added.bodyAsText(), HttpStatusCode.OK, added.status)
        assertFalse("tok\"" in added.bodyAsText())
        return added.obj().str("id")
    }

    @Test
    fun `a tuner's stations list, play, say what's on, and show their art`() = serverTest { services ->
        val client = browser()
        val src = addTuner(services, client)
        val test = client.post("/api/sources/$src/test") { csrf() }
        assertTrue(test.bodyAsText(), test.bodyAsText().contains("1 tuner and 2 stations"))

        val stations = client.get("/api/live/radio/$src/search?q=").json().jsonArray.map { it.jsonObject }
        assertEquals(listOf("tuner:97.1", "tuner:101.5"), stations.map { it.str("sourceId") })
        val wxyz = stations[0]
        assertEquals("WXYZ FM", wxyz.str("title"))
        assertEquals("97.1 FM, on your tuner", wxyz.str("artist"))
        assertEquals("RADIO", wxyz.str("source"))
        assertEquals(src, wxyz.str("via"))
        val logo = wxyz.str("artworkUrl")
        assertEquals("/api/live/tuner/$src/image/97.1/logo?v=ab12", logo)
        assertEquals(1, client.get("/api/live/radio/$src/search?q=kroc").json().jsonArray.size)

        // Played through the server, as any radio station is.
        val audio = client.get("/api/live/radio/$src/station/tuner%3A97.1/stream")
        assertEquals(HttpStatusCode.OK, audio.status)
        assertEquals("audio/aac", audio.headers["Content-Type"])
        assertTrue(audio.readRawBytes().contentEquals(adts(40)))

        // What's on: "Artist - Title" for every player, and the song with the broadcast's cover.
        val now = client.get("/api/live/radio/$src/station/tuner%3A97.1/now").obj()
        assertEquals("David Bowie - Heroes", now.str("title"))
        val song = now["now"]!!.jsonObject
        assertEquals("Heroes", song.str("title"))
        assertEquals("/api/live/tuner/$src/image/97.1/art?v=cd34", song.str("artworkUrl"))
        assertEquals("Heroes", song["song"]!!.jsonObject.str("title"))
        val signal = now["signal"]!!.jsonObject
        assertEquals("tuner", signal.str("via"))
        assertEquals(0.92, signal["score"]!!.jsonPrimitive.content.toDouble(), 0.001)

        // The logo, through the server (the tuner's token never leaves it).
        val image = client.get(logo)
        assertEquals("image/png", image.headers["Content-Type"])
        assertTrue(image.readRawBytes().contentEquals(png))
        assertTrue(tuner.takeRequestsMatching { it.path?.contains("token") == true }.isEmpty())

        // Every tuner busy: said plainly.
        val busy = client.get("/api/live/radio/$src/station/tuner%3A101.5/stream")
        assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
        assertTrue(busy.bodyAsText().contains("busy"))

        // A favourite, like any station.
        val favorite = client.put("/api/live/favorites") { csrf(); json("""{"track":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), wxyz)},"favorite":true}""") }
        assertEquals(HttpStatusCode.OK, favorite.status)
        assertEquals(logo, favorite.json().jsonArray[0].jsonObject.str("artworkUrl"))

        // Signed out: nothing.
        val stranger = createClient { }
        assertEquals(HttpStatusCode.Unauthorized, stranger.get(logo).status)
    }

    @Test
    fun `a station on the tuner and online is listed once, merged, with the internet copy's other stations after`() = serverTest { services ->
        val client = browser()
        val src = addTuner(services, client)
        val admin = services.users.byUsername("admin")!!
        val radio = services.sources.allGlobal().first { it.kind == SourceKind.RADIO }
        val online = listOf(
            RadioStation(id = "aaa-1", name = "WXYZ-FM The River", url = "https://wxyz.example/live.mp3", countryCode = "US", favicon = "https://wxyz.example/logo.png"),
            RadioStation(id = "bbb-2", name = "WFMU", url = "https://wfmu.example/live.mp3", countryCode = "US"),
        )
        val merged = services.tunerRadio.merge(admin, online, radio.id, "wxyz")
        assertEquals(listOf("tuner:97.1~aaa-1", "bbb-2"), merged.map { it.sourceId })
        assertEquals("97.1 FM, on your tuner or online", merged[0].artist)
        assertEquals(src, merged[0].via)
        // Found once, it's remembered: listed with everything else, it's merged too.
        val all = services.tunerRadio.merge(admin, emptyList(), radio.id, null)
        assertEquals(listOf("tuner:97.1~aaa-1", "tuner:101.5"), all.map { it.sourceId })
    }

    private fun ffmpeg() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)

    private fun aac(dir: File, name: String, hz: Int): ByteArray {
        val file = File(dir, name)
        val made = ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=$hz:duration=30", "-c:a", "aac", "-b:a", "64k", "-f", "adts", file.path)
            .redirectErrorStream(true).start()
        made.inputStream.readAllBytes()
        assertTrue(made.waitFor(60, TimeUnit.SECONDS))
        return file.readBytes()
    }

    @Test
    fun `a station on both plays from the tuner while it's clear, and from the internet when the tuner is busy`() {
        assumeTrue("ffmpeg isn't installed", ffmpeg())
        val dir = Files.createTempDirectory("blend").toFile()
        val tunerAudio = aac(dir, "tuner.aac", 440)
        val internetAudio = aac(dir, "internet.aac", 880)
        // Both at about the pace they'd play (64 kbps: 8 KB a second), so the stream goes on.
        stream97 = { MockResponse().setHeader("Content-Type", "audio/aac").setBody(Buffer().write(tunerAudio)).throttleBody(12_000, 1, TimeUnit.SECONDS) }
        val internet = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setHeader("Content-Type", "audio/aac").setBody(Buffer().write(internetAudio)).throttleBody(12_000, 1, TimeUnit.SECONDS)
            }
            start()
        }
        try {
            serverTest { services ->
                val client = browser()
                val src = addTuner(services, client)
                services.tunerRadio.internetReach = NetworkGuard.Reach.ANY
                services.tunerRadio.internetStation = { id -> RadioStation(id = id, name = "WXYZ-FM", url = internet.url("/live").toString(), countryCode = "US") }

                // A real server: the test engine doesn't hand over a response that never ends.
                val server = io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, port = 0, host = "127.0.0.1") { module(services) }.start(wait = false)
                val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
                val http = java.net.http.HttpClient.newBuilder().cookieHandler(java.net.CookieManager()).build()
                http.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI("$base/api/auth/login")).header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("""{"username":"admin","password":"break-glass-pw"}""")).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding(),
                )
                fun listen(id: String): ByteArray {
                    val response = http.send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI("$base/api/live/radio/$src/station/${id.replace(":", "%3A")}/stream")).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofInputStream(),
                    )
                    assertEquals(200, response.statusCode())
                    return response.body().use { it.readNBytes(4_000) }.also { assertEquals(4_000, it.size) }
                }

                val heard = listen("tuner:97.1~aaa-1")
                assertEquals(0xFF, heard[0].toInt() and 0xFF)
                val now = client.get("/api/live/radio/$src/station/tuner%3A97.1~aaa-1/now").obj()
                assertEquals("tuner", now["signal"]!!.jsonObject.str("via"))
                assertEquals(0, internet.requestCount)

                // 101.5's tuner is busy: its internet copy plays instead.
                services.tunerRadio.internetStation = { id -> RadioStation(id = id, name = "KROC", url = internet.url("/live").toString(), countryCode = "US") }
                val instead = listen("tuner:101.5~ccc-3")
                assertEquals(0xFF, instead[0].toInt() and 0xFF)
                assertNotNull(internet.takeRequest(5, TimeUnit.SECONDS))
                val now101 = client.get("/api/live/radio/$src/station/tuner%3A101.5~ccc-3/now").obj()
                assertEquals("internet", now101["signal"]!!.jsonObject.str("via"))
                services.tunerRadio.close()
                server.stop(100, 1_000)
            }
        } finally {
            internet.shutdown()
            dir.deleteRecursively()
        }
    }
}

private fun MockWebServer.takeRequestsMatching(test: (RecordedRequest) -> Boolean): List<RecordedRequest> {
    val all = mutableListOf<RecordedRequest>()
    while (true) all += takeRequest(10, TimeUnit.MILLISECONDS) ?: break
    return all.filter(test)
}
