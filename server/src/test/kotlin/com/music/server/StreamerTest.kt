package com.music.server

import com.music.player.net.Http
import com.music.player.sources.SourceStream
import com.music.server.music.StreamPlan
import com.music.server.music.Streamer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy

/** Songs passed through from YouTube's media servers, which trickle out audio asked for without a range. */
class StreamerTest {
    private val youtube = MockWebServer().apply { start() }
    private val http = Http.createClient().newBuilder()
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) })
        .build()
    private val streamer = Streamer(http, "ffmpeg", 1) { "http://127.0.0.1" }
    private val song = SourceStream(
        url = "http://rr2---sn-test.googlevideo.com:${youtube.port}/videoplayback?id=abc&itag=251",
        description = "Opus 160 kbps",
        fileExtension = "webm",
        kbps = 160,
        codec = "opus",
    )

    @After
    fun stop() = youtube.shutdown()

    @Test
    fun `a song asked for without a range is asked of YouTube with one, and sent whole`() = testApplication {
        routing { get("/song") { streamer.respond(call, song, StreamPlan(null, null), 0.0) { song } } }
        youtube.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-9/10").setHeader("Content-Type", "audio/webm").setBody("0123456789"))
        val whole = client.get("/song")
        assertEquals(HttpStatusCode.OK, whole.status)
        assertNull(whole.headers["Content-Range"])
        assertEquals("0123456789", whole.bodyAsText())
        val first = youtube.takeRequest()
        assertEquals("bytes=0-", first.getHeader("Range"))
        assertEquals("POST", first.method)

        // A range the player asked for is passed on as it is.
        youtube.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-9/10").setHeader("Content-Type", "audio/webm").setBody("456789"))
        val part = client.get("/song") { header("Range", "bytes=4-") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals("bytes 4-9/10", part.headers["Content-Range"])
        assertEquals("456789", part.bodyAsText())
        assertEquals("bytes=4-", youtube.takeRequest().getHeader("Range"))
    }

    @Test
    fun `the stream test reads like a browser and says how fast it came`() = kotlinx.coroutines.runBlocking {
        youtube.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2047/2048").setHeader("Content-Type", "audio/webm").setBody("x".repeat(2048)))
        val report = streamer.probe(song)
        assertTrue(report.toString(), report[0].startsWith("rr2---sn-test.googlevideo.com answered HTTP 206 in "))
        assertTrue(report.toString(), report[1].startsWith("Read 2 KB of audio in "))
        assertEquals("bytes=0-", youtube.takeRequest().getHeader("Range"))
    }
}
