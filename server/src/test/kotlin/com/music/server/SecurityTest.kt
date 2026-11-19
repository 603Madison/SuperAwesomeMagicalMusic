package com.music.server

import com.music.server.auth.LoginThrottle
import com.music.server.auth.safeReturn
import com.music.server.music.MediaTypes
import com.music.server.sources.SourceKind
import com.music.server.util.NetworkGuard
import com.music.server.util.NetworkGuard.reach
import com.music.server.util.RecentLog
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** The fixes from the 1.29 security review, each held in place. */
class SecurityTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun `the server's requests keep away from metadata, its own machine and the local network, as asked`() {
        // Cloud metadata: never.
        assertNotNull(NetworkGuard.refusal(ip("169.254.169.254"), NetworkGuard.Reach.ANY))
        assertNotNull(NetworkGuard.refusal(ip("fe80::1"), NetworkGuard.Reach.ANY))
        // A home server on the LAN is fine for an admin's source…
        assertNull(NetworkGuard.refusal(ip("192.168.1.20"), NetworkGuard.Reach.ANY))
        assertNull(NetworkGuard.refusal(ip("127.0.0.1"), NetworkGuard.Reach.ANY))
        // …someone's own source can't be the server itself…
        assertNotNull(NetworkGuard.refusal(ip("127.0.0.1"), NetworkGuard.Reach.NO_LOOPBACK))
        assertNotNull(NetworkGuard.refusal(ip("::1"), NetworkGuard.Reach.NO_LOOPBACK))
        assertNull(NetworkGuard.refusal(ip("192.168.1.20"), NetworkGuard.Reach.NO_LOOPBACK))
        // …and a radio station is on the internet, nowhere else.
        for (local in listOf("10.0.0.5", "172.16.3.4", "192.168.0.1", "100.64.1.1", "0.0.0.0", "fd12::1")) {
            assertNotNull(local, NetworkGuard.refusal(ip(local), NetworkGuard.Reach.PUBLIC))
        }
        assertNull(NetworkGuard.refusal(ip("8.8.8.8"), NetworkGuard.Reach.PUBLIC))
        assertNull(NetworkGuard.refusal(ip("2606:4700::1111"), NetworkGuard.Reach.PUBLIC))
        // A public server can't redirect into the LAN.
        assertNotNull(NetworkGuard.refusal(ip("192.168.1.20"), NetworkGuard.Reach.ANY, redirectedFromPublic = true))
    }

    @Test
    fun `a request tagged for the internet isn't sent to the server's own machine`() {
        val local = MockWebServer().apply { enqueue(MockResponse().setBody("secret")) }
        local.start()
        try {
            val client = OkHttpClient.Builder().addNetworkInterceptor(NetworkGuard.interceptor()).build()
            val radio = Request.Builder().url(local.url("/stream")).reach(NetworkGuard.Reach.PUBLIC).build()
            assertThrows(NetworkGuard.Refused::class.java) { client.newCall(radio).execute().close() }
            // Untagged (an admin's own source on this machine), it goes.
            client.newCall(Request.Builder().url(local.url("/x")).build()).execute().use { assertEquals("secret", it.body!!.string()) }
            // A client for people's own sources refuses this machine by default.
            val guarded = OkHttpClient.Builder().addNetworkInterceptor(NetworkGuard.interceptor(NetworkGuard.Reach.NO_LOOPBACK)).build()
            assertThrows(NetworkGuard.Refused::class.java) { guarded.newCall(Request.Builder().url(local.url("/y")).build()).execute().close() }
        } finally {
            local.shutdown()
        }
    }

    @Test
    fun `only audio and video are relayed, under a sandbox`() {
        assertEquals("audio/mpeg", MediaTypes.relayed("audio/mpeg")?.toString())
        assertEquals("application/vnd.apple.mpegurl", MediaTypes.relayed("application/vnd.apple.mpegurl; charset=utf-8")?.withoutParameters()?.toString())
        assertNotNull(MediaTypes.relayed("application/ogg"))
        assertNotNull(MediaTypes.relayed("video/mp4"))
        for (bad in listOf("text/html", "image/svg+xml", "application/javascript", "text/xml", "audio/x-html", "application/xhtml+xml", "nonsense", "")) {
            assertNull(bad, MediaTypes.relayed(bad))
        }
    }

    @Test
    fun `artwork the server didn't sign isn't fetched`() {
        val art = testServices().artwork
        assertNull(art.unwrap("http://169.254.169.254/latest/meta-data/"))
        assertNull(art.unwrap("https://evil.example/x.png"))
        assertEquals("/api/radio/art?station=mine", art.unwrap("/api/radio/art?station=mine"))
        val signed = art.wrap("https://images.example/cover.jpg")!!
        assertEquals("https://images.example/cover.jpg", art.unwrap(signed))
    }

    @Test
    fun `sign-in only sends people back to this server`() {
        assertEquals("/library?tab=albums", safeReturn("/library?tab=albums"))
        for (bad in listOf("//evil.example", "/\\evil.example", "/\t/evil.example", "/\n/evil.example", "https://evil.example", "\\\\evil", "/\u0000x", null)) {
            assertEquals(bad.toString(), "/", safeReturn(bad))
        }
    }

    @Test
    fun `wrong passwords from one place don't lock the account everywhere`() {
        var now = 0L
        val throttle = LoginThrottle(clock = { now })
        val here = LoginThrottle.Attempt("203.0.113.5", "Maddie")
        repeat(10) { throttle.attempt(here) }
        assertThrows(ApiError::class.java) { throttle.attempt(here) }
        // Maddie, from home, can still sign in.
        val home = LoginThrottle.Attempt("198.51.100.7", "maddie")
        throttle.attempt(home)
        throttle.succeeded(home)
        // Spread over many addresses, the account's own limit holds.
        repeat(100) { throttle.attempt(LoginThrottle.Attempt("198.18.0.${it + 1}", "maddie")) }
        assertThrows(ApiError::class.java) { throttle.attempt(LoginThrottle.Attempt("198.18.1.1", "maddie")) }
        // And it all passes with time.
        now += 16 * 60 * 1000
        throttle.attempt(here)
    }

    @Test
    fun `too much at once is turned away`() = serverTest {
        val big = "x".repeat(2 * 1024 * 1024)
        val response = createClient { }.post("/api/auth/login") { json("""{"username":"admin","password":"$big"}""") }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `a shared source's details are for whoever set it up`() = serverTest { services ->
        services.users.create("sam", password = "correct horse")
        services.sources.create(
            ownerId = null,
            kind = SourceKind.JELLYFIN,
            name = "Home",
            settings = JsonObject(mapOf("serverUrl" to JsonPrimitive("http://jellyfin.lan:8096"), "username" to JsonPrimitive("maddie"))),
            secrets = JsonObject(mapOf("password" to JsonPrimitive("hunter2"))),
        )
        val sam = createClient { }.post("/api/auth/login") { json("""{"username":"sam","password":"correct horse","app":true}""") }.obj().str("token")
        val seen = createClient { }.get("/api/sources") { bearerAuth(sam) }.json().jsonArray.map { it.jsonObject }.first { it.str("name") == "Home" }
        assertTrue(seen["settings"]!!.jsonObject.isEmpty())
        assertTrue(seen["secretsSet"]!!.jsonArray.isEmpty())
        val admin = createClient { }.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw","app":true}""") }.obj().str("token")
        val own = createClient { }.get("/api/sources") { bearerAuth(admin) }.json().jsonArray.map { it.jsonObject }.first { it.str("name") == "Home" }
        assertEquals("http://jellyfin.lan:8096", own["settings"]!!.jsonObject.str("serverUrl"))
    }

    @Test
    fun `someone's own source can't be the server's own machine`() = serverTest { services ->
        services.users.create("sam", password = "correct horse")
        val sam = browser()
        sam.post("/api/auth/login") { json("""{"username":"sam","password":"correct horse"}""") }
        val response = sam.post("/api/sources") { csrf(); json("""{"kind":"JELLYFIN","fields":{"serverUrl":"http://127.0.0.1:8096","username":"sam","password":"x"}}""") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText(), "own address" in response.bodyAsText())
    }

    @Test
    fun `logged text stays on one line`() {
        assertEquals("Song | 2026-01-01 ERROR forged", RecentLog.oneLine("Song\n2026-01-01 ERROR forged"))
        assertFalse(RecentLog.oneLine("a\u001b[31mred\r\nb").any { it.code < 0x20 })
    }
}
