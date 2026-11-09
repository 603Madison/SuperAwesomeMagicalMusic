package com.music.player.net

import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy

/** Audio from YouTube's media servers is asked for the way NewPipe's player asks: POST, numbered. */
class YouTubeMediaRequestsTest {
    private val server = MockWebServer().apply { start() }
    private val http = Http.createClient().newBuilder()
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) })
        .build()

    @After
    fun stop() = server.shutdown()

    @Test
    fun `a media request becomes a numbered POST, with its range kept`() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("audio"))
        val url = "http://rr3---sn-abc.googlevideo.com:${server.port}/videoplayback?id=x&itag=140"
        http.newCall(Request.Builder().url(url).header("Range", "bytes=100-").build()).execute().use {
            assertEquals(206, it.code)
            assertEquals("audio", it.body?.string())
        }
        val seen = server.takeRequest()
        assertEquals("POST", seen.method)
        assertEquals("x\u0000", seen.body.readUtf8())
        assertEquals("bytes=100-", seen.getHeader("Range"))
        assertEquals("identity", seen.getHeader("Accept-Encoding"))
        assertNotNull(seen.requestUrl?.queryParameter("rn"))
        assertEquals("140", seen.requestUrl?.queryParameter("itag"))
    }

    @Test
    fun `other requests are left alone`() {
        server.enqueue(MockResponse().setBody("page"))
        server.enqueue(MockResponse().setBody("other"))
        http.newCall(Request.Builder().url("http://www.youtube.com:${server.port}/videoplayback?id=x").build()).execute().close()
        http.newCall(Request.Builder().url("http://rr3---sn-abc.googlevideo.com:${server.port}/generate_204").build()).execute().close()
        repeat(2) {
            val seen = server.takeRequest()
            assertEquals("GET", seen.method)
            assertEquals(null, seen.requestUrl?.queryParameter("rn"))
        }
    }
}
