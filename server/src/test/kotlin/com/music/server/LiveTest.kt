package com.music.server

import com.music.server.music.LiveProxy
import com.music.server.util.Random
import com.music.server.util.SecretBox
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import okhttp3.OkHttpClient
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LiveTest {
    private val proxy = LiveProxy(OkHttpClient(), SecretBox(Random.bytes(32)))

    @Test
    fun `playlists are rewritten to come through the server, with SiriusXM keys from the session`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS="mp4a.40.2"
            256k/playlist.m3u8?token=abc
            #EXT-X-I-FRAME-STREAM-INF:URI="iframes.m3u8"
        """.trimIndent()
        val out = proxy.rewrite(master, "https://cdn.example.com/live/ch1/master.m3u8", "src1")
        val variant = out.lines().first { it.startsWith("/api/live/hls/src1/p?u=") }
        assertTrue(variant, "https%3A%2F%2Fcdn.example.com%2Flive%2Fch1%2F256k%2Fplaylist.m3u8%3Ftoken%3Dabc" in variant)
        assertFalse("I-FRAME" in out)

        val media = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="sxmkey://1234-abcd"
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:10.0,
            seg1.aac
            #EXTINF:10.0,
            https://other.example.com/seg2.aac
        """.trimIndent()
        val rewritten = proxy.rewrite(media, "https://cdn.example.com/live/ch1/256k/playlist.m3u8", "src1") { "/api/live/sxm/src1/key/$it" }
        assertTrue(rewritten, "URI=\"/api/live/sxm/src1/key/1234-abcd\"" in rewritten)
        assertTrue(rewritten, "#EXT-X-MAP:URI=\"/api/live/hls/src1/s?u=https%3A%2F%2Fcdn.example.com%2Flive%2Fch1%2F256k%2Finit.mp4" in rewritten)
        val segments = rewritten.lines().filter { it.startsWith("/api/live/hls/src1/s?") }
        assertEquals(2, segments.size)
        assertTrue("other.example.com" in segments[1])
    }

    @Test
    fun `only addresses a playlist named can be fetched, by people who can use the source`() = serverTest { services ->
        val cdn = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.path) {
                    "/live.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXTINF:10,\nseg.ts\n")
                    "/seg.ts" -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody("SEGMENT")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        try {
            val radio = services.sources.allGlobal().first { it.kind.name == "RADIO" }.id
            val client = browser()
            client.get("/api/health")
            assertEquals(HttpStatusCode.Unauthorized, client.get(services.live.proxied(radio, 'p', cdn.url("/live.m3u8").toString())).status)

            client.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }
            val playlist = client.get(services.live.proxied(radio, 'p', cdn.url("/live.m3u8").toString()))
            assertEquals(HttpStatusCode.OK, playlist.status)
            val segment = playlist.bodyAsText().lines().first { it.startsWith("/api/live/hls/") }
            assertEquals("SEGMENT", client.get(segment).bodyAsText())

            // A forged address, or another source's signature, gets nothing.
            assertEquals(HttpStatusCode.NotFound, client.get("/api/live/hls/$radio/s?u=http%3A%2F%2F127.0.0.1%2Fsecret&s=forged").status)
            assertEquals(HttpStatusCode.NotFound, client.get(segment.replace("/hls/$radio/", "/hls/someone-else/")).status)
        } finally {
            cdn.shutdown()
        }
    }

    @Test
    fun `song titles are taken out of Icecast streams and kept`() {
        val audio = ByteArray(40) { it.toByte() }
        val meta = "StreamTitle='Velvet Satellites - Neon Tidepool';".toByteArray()
        val padded = meta.copyOf(((meta.size + 15) / 16) * 16)
        val stream = ByteArrayOutputStream().apply {
            write(audio, 0, 16); write(padded.size / 16); write(padded)
            write(audio, 16, 16); write(0)
            write(audio, 32, 8)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        var title: String? = null
        LiveProxy.copyWithoutMetadata(ByteArrayInputStream(stream), out, 16) { title = it }
        assertArrayEquals(audio, out.toByteArray())
        assertEquals("Velvet Satellites - Neon Tidepool", title)
    }

    @Test
    fun `radio streams come through without their titles, which the player can ask for`() {
        val audio = ByteArray(64) { (it * 3).toByte() }
        val meta = "StreamTitle='Hollow Pines - Moth Light';".toByteArray().let { it.copyOf(((it.size + 15) / 16) * 16) }
        val icy = MockWebServer().apply {
            enqueue(
                MockResponse().setHeader("Content-Type", "audio/mpeg").setHeader("icy-metaint", "32")
                    .setBody(Buffer().write(audio, 0, 32).writeByte(meta.size / 16).write(meta).write(audio, 32, 32).writeByte(0)),
            )
            start()
        }
        try {
            serverTest { services ->
                // Straight through the proxy (looking a station up needs the real directory).
                application {
                    routing {
                        get("/test/radio") { services.live.respondRadio(call, icy.url("/stream").toString(), "r|1") }
                    }
                }
                val client = browser()
                val response = client.get("/test/radio")
                assertEquals("audio/mpeg", response.headers["Content-Type"])
                assertArrayEquals(audio, response.readRawBytes())
                assertEquals("Hollow Pines - Moth Light", services.live.radioTitle("r|1"))
                assertEquals("1", icy.takeRequest().getHeader("Icy-MetaData"))
            }
        } finally {
            icy.shutdown()
        }
    }
}
