package com.music.server

import com.music.server.auth.DeviceLogins
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Signing in with a code shown on another device (a flip phone), approved by someone signed in. */
class DeviceLoginTest {
    private var now = 1_700_000_000_000L
    private fun clockedServices() = testServices(clock = { now })

    private suspend fun HttpClient.start(name: String = "Mini on TCL Flip Pro") =
        post("/api/auth/device/start") { json("""{"deviceName":"$name"}""") }.obj()

    private suspend fun HttpClient.poll(deviceCode: String) =
        post("/api/auth/device/poll") { json("""{"deviceCode":"$deviceCode"}""") }.obj()

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.signedInBrowser(): HttpClient = browser().also {
        it.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }
    }

    @Test
    fun `a device signs in once someone approves its code`() = serverTest(clockedServices()) { services ->
        services.users.create("maddie", password = "correct horse")
        val device = createClient { }
        val started = device.start()
        val userCode = started.str("userCode")
        assertTrue(userCode, Regex("[A-Z]{4}-[A-Z]{4}").matches(userCode))
        assertTrue(userCode.filter { it != '-' }.all { it in DeviceLogins.ALPHABET })
        assertEquals("http://localhost/link", started.str("verificationUri"))
        assertEquals("http://localhost/link?code=$userCode", started.str("verificationUriComplete"))
        assertEquals("5", started.str("interval"))
        assertEquals("600", started.str("expiresIn"))
        val deviceCode = started.str("deviceCode")
        assertTrue(deviceCode.length >= 40)

        assertEquals("pending", device.poll(deviceCode).str("status"))

        // The person types it however they like: lower case, no dash.
        val browser = signedInBrowser()
        val typed = userCode.lowercase().replace("-", "")
        val request = browser.get("/api/auth/device/request?code=$typed").obj()
        assertEquals("Mini on TCL Flip Pro", request.str("deviceName"))
        assertEquals(userCode, request.str("userCode"))
        // Changes made with the cookie need the web player's header, as always.
        assertEquals(HttpStatusCode.Forbidden, browser.post("/api/auth/device/decide") { json("""{"userCode":"$typed","allow":true}""") }.status)
        assertEquals(HttpStatusCode.OK, browser.post("/api/auth/device/decide") { csrf(); json("""{"userCode":"$typed","allow":true}""") }.status)

        now += 5_000
        val approved = device.poll(deviceCode)
        assertEquals("approved", approved.str("status"))
        assertEquals("maddie", approved["user"]!!.jsonObject.str("username"))
        val token = approved.str("token")

        // A sign-in like the app's: listed with the person's devices under the name it gave.
        val me = device.get("/api/me") { bearerAuth(token) }.obj()
        assertEquals("APP", me["session"]!!.jsonObject.str("kind"))
        val sessions = browser.get("/api/me/sessions").json().jsonArray.map { it.jsonObject.str("name") }
        assertTrue(sessions.toString(), "Mini on TCL Flip Pro" in sessions)
        assertEquals(HttpStatusCode.OK, device.get("/api/playlists") { bearerAuth(token) }.status)

        // Once only: neither the device code nor the user code works again.
        now += 5_000
        assertEquals("expired", device.poll(deviceCode).str("status"))
        assertEquals(HttpStatusCode.NotFound, browser.get("/api/auth/device/request?code=$typed").status)
    }

    @Test
    fun `a turned-down sign-in gets nothing`() = serverTest(clockedServices()) { services ->
        services.users.create("maddie", password = "correct horse")
        val device = createClient { }
        val started = device.start()
        val browser = signedInBrowser()
        assertEquals(
            HttpStatusCode.OK,
            browser.post("/api/auth/device/decide") { csrf(); json("""{"userCode":"${started.str("userCode")}","allow":false}""") }.status,
        )
        val answer = device.poll(started.str("deviceCode"))
        assertEquals("denied", answer.str("status"))
        assertNull(answer["token"])
        // The code is spent: it can't be approved afterwards.
        assertEquals(
            HttpStatusCode.NotFound,
            browser.post("/api/auth/device/decide") { csrf(); json("""{"userCode":"${started.str("userCode")}","allow":true}""") }.status,
        )
    }

    @Test
    fun `codes expire after ten minutes`() = serverTest(clockedServices()) { services ->
        services.users.create("maddie", password = "correct horse")
        val device = createClient { }
        val started = device.start()
        now += 10 * 60_000L + 1
        val browser = signedInBrowser()
        assertEquals(HttpStatusCode.NotFound, browser.get("/api/auth/device/request?code=${started.str("userCode")}").status)
        assertEquals("expired", device.poll(started.str("deviceCode")).str("status"))
        // A device code that was never given out is no different.
        assertEquals("expired", device.poll("x".repeat(43)).str("status"))
    }

    @Test
    fun `asking too often is told to slow down`() = serverTest(clockedServices()) {
        val device = createClient { }
        val deviceCode = device.start().str("deviceCode")
        assertEquals("pending", device.poll(deviceCode).str("status"))
        now += 1_000
        val slow = device.poll(deviceCode)
        assertEquals("slow_down", slow.str("status"))
        assertEquals("10", slow.str("interval"))
        // At the new pace it's fine again.
        now += 10_000
        assertEquals("pending", device.poll(deviceCode).str("status"))
    }

    @Test
    fun `approving needs a sign-in, and guessing codes is cut off`() = serverTest(clockedServices()) { services ->
        services.users.create("maddie", password = "correct horse")
        val device = createClient { }
        val started = device.start()
        assertEquals(HttpStatusCode.Unauthorized, device.get("/api/auth/device/request?code=${started.str("userCode")}").status)
        assertEquals(HttpStatusCode.Unauthorized, device.post("/api/auth/device/decide") { json("""{"userCode":"${started.str("userCode")}","allow":true}""") }.status)

        val browser = signedInBrowser()
        repeat(10) {
            assertEquals(HttpStatusCode.NotFound, browser.get("/api/auth/device/request?code=BBBB-BBBB").status)
        }
        // Even the right code waits now.
        assertEquals(HttpStatusCode.TooManyRequests, browser.get("/api/auth/device/request?code=${started.str("userCode")}").status)
        // A quarter of an hour later it's fine.
        now += 15 * 60_000L + 1
        val fresh = device.start()
        assertEquals(HttpStatusCode.OK, browser.get("/api/auth/device/request?code=${fresh.str("userCode")}").status)
    }

    @Test
    fun `starting sign-ins is limited for each address`() = serverTest(clockedServices()) {
        val device = createClient { }
        repeat(10) { assertEquals(HttpStatusCode.OK, device.post("/api/auth/device/start") { json("{}") }.status) }
        assertEquals(HttpStatusCode.TooManyRequests, device.post("/api/auth/device/start") { json("{}") }.status)
        now += 60_000
        // And a device with no name gets a plain one.
        val started = device.start("")
        val browser = browser().also {
            it.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }
        }
        assertEquals("A device", browser.get("/api/auth/device/request?code=${started.str("userCode")}").obj().str("deviceName"))
    }

    @Test
    fun `codes are read however they're typed`() {
        assertEquals("BCDFGHJK", DeviceLogins.normalize("bcdf-ghjk"))
        assertEquals("BCDFGHJK", DeviceLogins.normalize(" BCDF GHJK "))
        assertNull(DeviceLogins.normalize("BCDF-GHJ"))
        // Vowels and digits are never in a code.
        assertNull(DeviceLogins.normalize("ABCD-EFGH"))
        assertEquals("BCDF-GHJK", DeviceLogins.display("BCDFGHJK"))
    }
}
