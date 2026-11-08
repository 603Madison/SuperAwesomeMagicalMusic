package com.music.connect

import com.music.connect.net.ApiTrack
import com.music.connect.net.ConnectClient
import com.music.connect.net.PlayState
import com.music.connect.net.ServerApi
import com.music.player.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The phone among the person's devices, against the real server: it sees the web player
 * playing, pauses it from afar, and takes over its queue from the same moment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DevicesTest {
    private lateinit var test: TestServer
    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var token: String? = null

    @Before
    fun start() {
        test = TestServer()
        token = runBlocking { ServerApi(http, { test.base }, { null }).login(test.base, "maddie", "correct horse", "Test phone").token }
    }

    @After
    fun stop() {
        scope.cancel()
        test.close()
    }

    /** A web player, as the server sees one. */
    private inner class WebPlayer(id: String) : WebSocketListener() {
        val messages = LinkedBlockingQueue<JsonObject>()
        val socket: WebSocket = http.newWebSocket(
            Request.Builder().url("${test.base}/api/connect/ws?id=$id&name=Chrome%20on%20Linux&kind=web").header("Authorization", "Bearer $token").build(),
            this,
        )

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.add(Json.parseToJsonElement(text).jsonObject)
        }

        /** The next message of [type] (others are skipped). */
        fun next(type: String, where: (JsonObject) -> Boolean = { true }): JsonObject {
            val until = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < until) {
                val message = messages.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (message["type"]?.jsonPrimitive?.content == type && where(message)) return message
            }
            error("No $type message")
        }
    }

    private val song = """{"source":"JELLYFIN","sourceId":"s1","title":"Neon Tidepool","artist":"Velvet Satellites","via":"src"}"""
    private val song2 = """{"source":"JELLYFIN","sourceId":"s2","title":"Paper Lanterns","artist":"Velvet Satellites","via":"src"}"""

    @Test
    fun `sees the web player, pauses it, and takes over what it was playing`() = runBlocking {
        val phone = ConnectClient(RuntimeEnvironment.getApplication(), http, { test.base }, { token }, scope)
        phone.start()
        withTimeout(5_000) { phone.devices.first { list -> list.any { it.id == phone.me } } }

        // The web player plays; the phone sees it, and would show it in its mini player.
        val web = WebPlayer("web-device-1")
        web.socket.send("""{"type":"state","state":{"track":$song,"queue":[$song,$song2],"index":0,"positionMs":30000,"durationMs":200000,"playing":true,"volume":0.8}}""")
        val seen = withTimeout(5_000) { phone.devices.first { list -> list.any { it.id == "web-device-1" && it.state?.playing == true } } }
        val remote = phone.remote(seen)!!
        assertEquals("Chrome on Linux", remote.name)
        assertEquals("Neon Tidepool", remote.state!!.track!!.title)
        assertTrue(phone.positionOf(remote) >= 30_000)

        // Paused from the phone.
        phone.command("web-device-1", "pause")
        val pause = web.next("command")["command"]!!.jsonObject
        assertEquals("pause", pause["action"]!!.jsonPrimitive.content)
        assertEquals(phone.me, pause["from"]!!.jsonPrimitive.content)

        // "This phone": the web player stops and the phone carries on, from the same song and moment.
        val take = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { phone.commands.first { it.action == "take" } } }
        phone.transfer(phone.me)
        assertEquals("pause", web.next("command")["command"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        val state = take.await().state!!
        assertEquals(listOf("Neon Tidepool", "Paper Lanterns"), state.queue.map { it.title })
        assertEquals(0, state.index)
        assertTrue(state.positionMs >= 30_000)
        assertTrue(state.playing)

        // Playing here: the web player sees the phone as the device playing.
        val track = ApiTrack(SourceType.JELLYFIN, "s1", "Neon Tidepool", "Velvet Satellites", via = "src")
        phone.report(PlayState(track = track, queue = state.queue, index = 0, positionMs = state.positionMs, durationMs = 200_000, playing = true))
        val now = web.next("devices") { m ->
            m["devices"]!!.jsonArray.any { d -> d.jsonObject["id"]!!.jsonPrimitive.content == phone.me && d.jsonObject["active"]!!.jsonPrimitive.content == "true" }
        }
        val listed = now["devices"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == phone.me }
        assertEquals("phone", listed["kind"]!!.jsonPrimitive.content)
        assertEquals(null, phone.remote(phone.devices.value))
        web.socket.close(1000, null)
        phone.stop()
    }

    @Test
    fun `signing in again connects again`() = runBlocking {
        val signedIn = token
        token = null
        val phone = ConnectClient(RuntimeEnvironment.getApplication(), http, { test.base }, { token }, scope)
        phone.start()
        // Not signed in: nothing to connect to.
        assertTrue(phone.devices.value.isEmpty())
        token = signedIn
        phone.restart()
        withTimeout(5_000) { phone.devices.first { list -> list.any { it.id == phone.me } } }
        phone.stop()
    }
}
