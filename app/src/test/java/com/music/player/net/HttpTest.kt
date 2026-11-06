package com.music.player.net

import com.music.player.downloads.DownloadRepository
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpTest {
    @Test
    fun `credential headers don't follow a redirect to another host`() {
        val elsewhere = MockWebServer().apply { enqueue(MockResponse().setBody("sign in")); start() }
        val home = MockWebServer().apply { start() }
        try {
            // "127.0.0.1" rather than "localhost": the same machine under another host name.
            val target = elsewhere.url("/login").newBuilder().host("127.0.0.1").build()
            home.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.toString()))
            val request = Request.Builder().url(home.url("/api/v1/system/status"))
                .header("X-Api-Key", "lidarr-key")
                .header("X-User-Auth-Token", "qobuz-token")
                .build()
            Http.createClient().newCall(request).execute().use { assertEquals("sign in", it.body!!.string()) }

            assertEquals("lidarr-key", home.takeRequest().getHeader("X-Api-Key"))
            val redirected = elsewhere.takeRequest()
            assertNull(redirected.getHeader("X-Api-Key"))
            assertNull(redirected.getHeader("X-User-Auth-Token"))
        } finally {
            home.shutdown()
            elsewhere.shutdown()
        }
    }

    @Test
    fun `credential headers stay on redirects within the same host`() {
        val home = MockWebServer().apply { start() }
        try {
            home.enqueue(MockResponse().setResponseCode(301).addHeader("Location", home.url("/api/v1/system/status/").toString()))
            home.enqueue(MockResponse().setBody("{}"))
            val request = Request.Builder().url(home.url("/api/v1/system/status")).header("X-Api-Key", "lidarr-key").build()
            Http.createClient().newCall(request).execute().close()
            home.takeRequest()
            assertEquals("lidarr-key", home.takeRequest().getHeader("X-Api-Key"))
        } finally {
            home.shutdown()
        }
    }

    @Test
    fun `download file extensions can't name a path`() {
        assertEquals("flac", DownloadRepository.safeExtension("flac"))
        assertEquals("m4a", DownloadRepository.safeExtension("M4A"))
        assertEquals("x", DownloadRepository.safeExtension("../x"))
        assertEquals("audio", DownloadRepository.safeExtension("/../"))
        assertEquals("webma", DownloadRepository.safeExtension("webma_opus"))
    }
}
