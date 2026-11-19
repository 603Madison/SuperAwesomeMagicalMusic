package com.music.connect

import com.music.connect.net.ServerApi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The app's sign-in goes to its server, and nowhere else. */
class AuthInterceptorTest {
    private val server = MockWebServer().apply { start() }

    @After
    fun stop() = server.shutdown()

    private fun authorizationSent(base: String, url: String): String? {
        server.enqueue(MockResponse())
        val client = OkHttpClient.Builder().addInterceptor(ServerApi.authInterceptor({ base }, { "secret-token" })).build()
        client.newCall(Request.Builder().url(url).build()).execute().close()
        return server.takeRequest().getHeader("Authorization")
    }

    @Test
    fun `the token goes only to the server, as it was reached`() {
        val plain = server.url("/x").toString()
        assertEquals("Bearer secret-token", authorizationSent("http://${server.hostName}:${server.port}", plain))
        // The server is on HTTPS: the same name and port over plain HTTP doesn't get it.
        assertNull(authorizationSent("https://${server.hostName}:${server.port}", plain))
        // Another port on the same machine doesn't either.
        assertNull(authorizationSent("http://${server.hostName}:${server.port + 1}", plain))
    }
}
