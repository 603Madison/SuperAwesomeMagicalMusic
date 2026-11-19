package com.music.player.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A service's key stays with the service when a redirect leads somewhere else. */
class DropCredentialsOnRedirectTest {
    @Test
    fun `keys go along on the same server, and not to another port`() {
        val elsewhere = MockWebServer().apply { start() }
        val service = MockWebServer().apply { start() }
        try {
            elsewhere.enqueue(MockResponse().setBody("ok"))
            service.enqueue(MockResponse().setResponseCode(302).setHeader("Location", service.url("/api/again")))
            service.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/login")))
            val client = OkHttpClient.Builder().addNetworkInterceptor(Http.DropCredentialsOnRedirect).build()
            client.newCall(Request.Builder().url(service.url("/api/thing")).header("X-Api-Key", "lidarr-key").build()).execute().close()
            assertEquals("lidarr-key", service.takeRequest().getHeader("X-Api-Key"))
            assertEquals("lidarr-key", service.takeRequest().getHeader("X-Api-Key"))
            // Same name (localhost), another port: another server.
            assertNull(elsewhere.takeRequest().getHeader("X-Api-Key"))
        } finally {
            elsewhere.shutdown()
            service.shutdown()
        }
    }
}
