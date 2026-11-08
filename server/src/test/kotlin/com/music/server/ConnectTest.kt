package com.music.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The person's devices seeing and controlling each other, and playback moving between them. */
class ConnectTest {
    private suspend fun ApplicationTestBuilder.token(username: String): String {
        val response = createClient {}.post("/api/auth/login") { json("""{"username":"$username","password":"correct horse","app":true,"deviceName":"Test"}""") }
        return response.obj().str("token")
    }

    private suspend fun ApplicationTestBuilder.device(token: String, id: String, name: String, kind: String = "web", origin: String? = null): DefaultClientWebSocketSession =
        createClient { install(WebSockets) }.webSocketSession("/api/connect/ws?id=$id&name=$name&kind=$kind") {
            header(HttpHeaders.Authorization, "Bearer $token")
            origin?.let { header(HttpHeaders.Origin, it) }
        }

    /** The next message of [type] (others, like device lists, are skipped). */
    private suspend fun DefaultClientWebSocketSession.next(type: String, where: (JsonObject) -> Boolean = { true }): JsonObject = withTimeout(5_000) {
        while (true) {
            val frame = incoming.receive()
            if (frame !is Frame.Text) continue
            val message = Json.parseToJsonElement(frame.readText()).jsonObject
            if (message["type"]?.jsonPrimitive?.content == type && where(message)) return@withTimeout message
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private val song = """{"source":"JELLYFIN","sourceId":"s1","title":"Neon Tidepool","artist":"Velvet Satellites","via":"src"}"""
    private val song2 = """{"source":"JELLYFIN","sourceId":"s2","title":"Paper Lanterns","artist":"Velvet Satellites","via":"src"}"""

    @Test
    fun `devices see each other, and playback moves from one to another`() = serverTest { services ->
        services.users.create("maddie", password = "correct horse")
        services.users.create("sam", password = "correct horse")
        val maddie = token("maddie")
        val web = device(maddie, "web-device-1", "Chrome on Linux")
        val phone = device(maddie, "phone-device-1", "Pixel", kind = "phone")
        val sams = device(token("sam"), "sam-device-1", "Sam's laptop")

        // The web player plays; the phone sees it, playing, as the active device.
        web.send(Frame.Text("""{"type":"state","state":{"track":$song,"queue":[$song,$song2],"index":0,"positionMs":30000,"durationMs":200000,"playing":true,"volume":0.8}}"""))
        val seen = phone.next("devices") { m -> m["devices"]!!.jsonArray.any { it.jsonObject["state"]?.jsonObject?.get("playing")?.jsonPrimitive?.content == "true" } }
        val webSeen = seen["devices"]!!.jsonArray.map { it.jsonObject }.single { it.str("id") == "web-device-1" }
        assertEquals("true", webSeen.str("active"))
        assertEquals("Neon Tidepool", webSeen["state"]!!.jsonObject["track"]!!.jsonObject.str("title"))
        // The device list leaves out the queue; it goes along when playback moves.
        assertTrue(webSeen["state"]!!.jsonObject["queue"]!!.jsonArray.isEmpty())
        assertEquals("phone-device-1", seen.str("you"))

        // The phone pauses the web player from afar.
        phone.send(Frame.Text("""{"type":"command","target":"web-device-1","command":{"action":"pause"}}"""))
        val pause = web.next("command")["command"]!!.jsonObject
        assertEquals("pause", pause.str("action"))
        assertEquals("phone-device-1", pause.str("from"))

        // Playback moves to the phone: the web player stops, the phone takes the queue from there.
        phone.send(Frame.Text("""{"type":"transfer","to":"phone-device-1"}"""))
        assertEquals("pause", web.next("command")["command"]!!.jsonObject.str("action"))
        val take = phone.next("command")["command"]!!.jsonObject
        assertEquals("take", take.str("action"))
        val state = take["state"]!!.jsonObject
        assertEquals(2, state["queue"]!!.jsonArray.size)
        assertEquals("true", state.str("playing"))
        assertTrue(state.str("positionMs").toLong() >= 30_000)

        // When the phone starts, the web player is told to pause.
        phone.send(Frame.Text("""{"type":"state","state":{"track":$song,"index":0,"positionMs":31000,"durationMs":200000,"playing":true}}"""))
        web.next("devices") { m -> m["devices"]!!.jsonArray.any { it.jsonObject.str("id") == "phone-device-1" && it.jsonObject["state"]?.jsonObject?.get("playing")?.jsonPrimitive?.content == "true" } }
        web.send(Frame.Text("""{"type":"state","state":{"track":$song,"index":0,"positionMs":31000,"durationMs":200000,"playing":true}}"""))
        assertEquals("pause", phone.next("command")["command"]!!.jsonObject.str("action"))

        // Someone else's devices are their own.
        val samsDevices = sams.next("devices")["devices"]!!.jsonArray
        assertEquals(listOf("sam-device-1"), samsDevices.map { it.jsonObject.str("id") })
    }

    @Test
    fun `a page on another site can't connect with the person's cookie`() = serverTest { services ->
        services.users.create("maddie", password = "correct horse")
        val browser = createClient {
            install(io.ktor.client.plugins.cookies.HttpCookies)
            install(WebSockets)
        }
        browser.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }
        val elsewhere = browser.webSocketSession("/api/connect/ws?id=evil-device-1&name=x") { header(HttpHeaders.Origin, "https://evil.example") }
        val closed = withTimeout(5_000) { elsewhere.closeReason.await() }
        assertEquals("Not from this server's pages", closed?.message)
        // From the server's own page, it's fine.
        val home = browser.webSocketSession("/api/connect/ws?id=good-device-1&name=x") { header(HttpHeaders.Origin, "http://localhost") }
        val first = withTimeout(5_000) { home.incoming.receive() } as Frame.Text
        assertTrue(first.readText().contains("good-device-1"))
        assertNull(home.closeReason.isCompleted.takeIf { it })
    }
}
