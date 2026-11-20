package com.music.server.speakers

import com.music.server.Services
import com.music.server.json
import com.music.server.obj
import com.music.server.sources.SourceKind
import com.music.server.sources.StoredSource
import com.music.server.str
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/** A pretend Jellyfin where every song id is a 200-second FLAC whose file is [audio]. */
class FakeJellyfin : AutoCloseable {
    val audio = ByteArray(1000) { (it % 251).toByte() }
    val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (request.getHeader("Authorization")?.contains("jf-token") != true) return MockResponse().setResponseCode(401)
                return when {
                    url.encodedPath.endsWith("/Items") -> {
                        val id = url.queryParameter("ids") ?: "song1"
                        MockResponse().setBody(
                            """{"Items":[{"Id":"$id","Name":"Song $id","Artists":["Velvet Satellites"],"Album":"Neon","RunTimeTicks":2000000000,
                               "MediaSources":[{"Id":"ms-$id","Bitrate":900000,"MediaStreams":[{"Type":"Audio","Codec":"flac","BitRate":900000}]}]}]}""",
                        )
                    }
                    url.encodedPath.startsWith("/Audio/") -> {
                        if (request.getHeader("Range") == "bytes=100-199") {
                            MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/flac")
                                .setHeader("Content-Range", "bytes 100-199/1000").setBody(Buffer().write(audio.copyOfRange(100, 200)))
                        } else {
                            MockResponse().setHeader("Content-Type", "audio/flac").setBody(Buffer().write(audio))
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }

    /** Shares it with everyone, as an admin would. */
    suspend fun share(services: Services): StoredSource = services.sources.create(
        ownerId = null, kind = SourceKind.JELLYFIN, name = "Jellyfin",
        settings = buildJsonObject {
            put("serverUrl", server.url("/").toString().trimEnd('/'))
            put("username", "admin")
            put("userId", "u1")
            put("transcodeCodec", "MP3")
        },
        secrets = buildJsonObject { put("accessToken", "jf-token") },
    )

    override fun close() = server.shutdown()
}

/** A song as the web player holds it. */
fun song(id: String, via: String, title: String = "Song $id", durationMs: Long = 200_000) =
    """{"source":"JELLYFIN","sourceId":"$id","title":"$title","artist":"Velvet Satellites","album":"Neon","durationMs":$durationMs,"via":"$via"}"""

suspend fun ApplicationTestBuilder.token(username: String): String =
    createClient {}.post("/api/auth/login") { json("""{"username":"$username","password":"correct horse","app":true,"deviceName":"Test"}""") }.obj().str("token")

/**
 * A signed-in app's connection to the devices hub. It reads everything the server sends as it
 * comes, as a real app does (a connection nobody reads from backs up, and the server stops
 * reading it too), and keeps it for [next].
 */
class Peer(private val session: DefaultClientWebSocketSession) {
    private val inbox = Channel<JsonObject>(Channel.UNLIMITED)

    init {
        session.launch {
            for (frame in session.incoming) {
                if (frame is Frame.Text) inbox.send(Json.parseToJsonElement(frame.readText()).jsonObject)
            }
        }
    }

    suspend fun send(frame: Frame) = session.send(frame)

    /** The next message of [type] that [where] likes (others, like older device lists, are skipped). */
    suspend fun next(type: String, timeoutMs: Long = 8_000, where: (JsonObject) -> Boolean = { true }): JsonObject = withTimeout(timeoutMs) {
        while (true) {
            val message = inbox.receive()
            if (message["type"]?.jsonPrimitive?.content == type && where(message)) return@withTimeout message
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}

suspend fun ApplicationTestBuilder.device(token: String, id: String, name: String): Peer =
    Peer(
        createClient { install(WebSockets) }.webSocketSession("/api/connect/ws?id=$id&name=$name&kind=web") {
            header(HttpHeaders.Authorization, "Bearer $token")
        },
    )

/** The device [id] in a devices message, if it's there. */
fun JsonObject.deviceOf(id: String): JsonObject? = this["devices"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("id") == id }

/** The device list once [id] shows something [where] likes. */
suspend fun Peer.devicesWhen(id: String, where: (JsonObject) -> Boolean): JsonObject =
    next("devices") { message -> message.deviceOf(id)?.let(where) == true }

/** Waits for [condition], failing after a while. */
suspend fun eventually(timeoutMs: Long = 8_000, what: String = "it", condition: () -> Boolean) {
    val until = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for $what")
        delay(25)
    }
}

fun JsonObject.state(): JsonObject? = this["state"] as? JsonObject
fun JsonObject.track(): JsonObject? = state()?.get("track") as? JsonObject
