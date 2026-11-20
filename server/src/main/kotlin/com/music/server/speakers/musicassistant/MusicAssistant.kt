package com.music.server.speakers.musicassistant

import com.music.server.speakers.Renderer
import com.music.server.speakers.RendererStatus
import com.music.server.speakers.SpeakerException
import com.music.server.speakers.SpeakerMedia
import com.music.server.speakers.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** A player Music Assistant has (it may be a Sonos, a Chromecast, AirPlay, a Squeezebox, DLNA…). */
data class MaPlayer(
    val id: String,
    val name: String,
    /** The kind of player it is, from the Music Assistant provider that found it ("chromecast", "airplay"…). */
    val provider: String,
    val available: Boolean,
    /** Playing in sync with another player (which is the one to play to). */
    val syncedTo: String?,
    val hidden: Boolean,
) {
    /** "Chromecast", "AirPlay"…, for under its name. */
    val kindLabel: String
        get() = when (val domain = provider.substringBefore("--").lowercase()) {
            "chromecast" -> "Chromecast"
            "airplay" -> "AirPlay"
            "sonos", "sonos_s1" -> "Sonos"
            "squeezelite", "slimproto" -> "Squeezebox"
            "dlna" -> "DLNA"
            "hass_players", "hass" -> "Home Assistant"
            "snapcast" -> "Snapcast"
            "bluesound" -> "BluOS"
            "fully_kiosk" -> "Fully Kiosk"
            "player_group", "universal_group", "sync_group" -> "Group"
            "sendspin", "builtin_player" -> "Web player"
            "" -> "Player"
            else -> domain.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

    companion object {
        fun from(json: JsonObject): MaPlayer? {
            val id = json.string("player_id") ?: return null
            return MaPlayer(
                id = id,
                name = json.string("display_name") ?: json.string("name") ?: id,
                provider = json.string("provider").orEmpty(),
                available = json.bool("available") ?: true,
                syncedTo = json.string("synced_to"),
                hidden = json.bool("hide_in_ui") == true || json.bool("hidden") == true || json.bool("enabled") == false,
            )
        }
    }
}

/** What Music Assistant refused ([code] is its error code), or that it couldn't be reached (no code). */
class MusicAssistantException(message: String, val code: Int? = null) : SpeakerException(message)

/**
 * Music Assistant's server API: a websocket at `ws://host:8095/ws` taking commands
 * (`{"message_id", "command", "args"}`) and answering each (`result`, or `error_code` and
 * `details`; long lists come in `partial` pieces). It says what it is first; from schema 28
 * (Music Assistant 2.7) a token must be given with the `auth` command before anything else.
 */
class MusicAssistantClient(
    http: OkHttpClient,
    /** e.g. http://homeassistant.local:8095 */
    private val serverUrl: String,
    private val token: String?,
    private val timeoutMs: Long = 15_000,
) : AutoCloseable {
    private val http = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS).build()
    private val connecting = Mutex()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var info: CompletableDeferred<JsonObject> = CompletableDeferred()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val ids = AtomicLong()

    private class Pending(val answer: CompletableDeferred<JsonElement>, val parts: MutableList<JsonElement> = mutableListOf())

    /** The server's description of itself (version, schema), once connected. */
    @Volatile var serverInfo: JsonObject? = null
        private set

    /** Runs [command] and returns its result. */
    suspend fun command(command: String, args: JsonObject = JsonObject(emptyMap())): JsonElement {
        val ws = connect()
        return send(ws, command, args)
    }

    private suspend fun send(ws: WebSocket, command: String, args: JsonObject): JsonElement {
        val id = ids.incrementAndGet().toString()
        val waiting = Pending(CompletableDeferred())
        pending[id] = waiting
        try {
            val message = buildJsonObject {
                put("message_id", id)
                put("command", command)
                put("args", args)
            }
            if (!ws.send(message.toString())) throw MusicAssistantException("Lost the connection to Music Assistant")
            return withTimeout(timeoutMs) { waiting.answer.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw MusicAssistantException("Music Assistant didn't answer $command")
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun connect(): WebSocket = connecting.withLock {
        socket?.let { return it }
        val started = CompletableDeferred<JsonObject>()
        info = started
        val request = Request.Builder().url(serverUrl.trimEnd('/') + "/ws").build()
        val ws = http.newWebSocket(request, Listener())
        try {
            val server = withTimeout(timeoutMs) { started.await() }
            serverInfo = server
            val schema = (server["schema_version"] as? JsonPrimitive)?.intOrNull ?: 0
            if (schema >= AUTH_SCHEMA || !token.isNullOrBlank()) {
                if (token.isNullOrBlank()) throw MusicAssistantException("Music Assistant needs a token: make a long-lived token in its web interface")
                try {
                    val result = send(ws, "auth", buildJsonObject { put("token", token) })
                    if (result is JsonNull || (result as? JsonPrimitive)?.booleanOrNull == false) {
                        throw MusicAssistantException("Music Assistant didn't accept the token")
                    }
                } catch (e: MusicAssistantException) {
                    // Before tokens, the server didn't know the command; the token isn't needed then.
                    if (schema >= AUTH_SCHEMA) throw if (e.code != null) MusicAssistantException("Music Assistant didn't accept the token (${e.message})") else e
                }
            }
            socket = ws
            return ws
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ws.cancel()
            throw MusicAssistantException("Music Assistant didn't answer at $serverUrl")
        } catch (e: Throwable) {
            ws.cancel()
            throw e
        }
    }

    override fun close() {
        socket?.close(1000, "Bye")
        socket = null
    }

    /** Every player Music Assistant knows. */
    suspend fun players(): List<MaPlayer> =
        (command("players/all") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(MaPlayer::from) }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val id = message.string("message_id")
            if (id == null) {
                // The first message says what the server is; the rest are events (not needed: players are polled).
                if (message.containsKey("server_version") || message.containsKey("schema_version")) info.complete(message)
                return
            }
            val waiting = pending[id] ?: return
            val code = (message["error_code"] as? JsonPrimitive)?.intOrNull
            if (code != null || message.containsKey("error")) {
                val details = message.string("details") ?: message.string("error") ?: "error $code"
                waiting.answer.completeExceptionally(MusicAssistantException("Music Assistant: $details", code ?: 0))
                return
            }
            val result = message["result"] ?: JsonNull
            if ((message["partial"] as? JsonPrimitive)?.booleanOrNull == true) {
                synchronized(waiting.parts) { if (result is JsonArray) waiting.parts.addAll(result) else waiting.parts.add(result) }
                return
            }
            val whole = synchronized(waiting.parts) {
                if (waiting.parts.isEmpty()) result else JsonArray(waiting.parts + ((result as? JsonArray) ?: listOf(result)).filter { it !is JsonNull })
            }
            waiting.answer.complete(whole)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed(webSocket, "Lost the connection to Music Assistant: ${t.message ?: t.javaClass.simpleName}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            closed(webSocket, "Music Assistant closed the connection${reason.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}")
        }

        private fun closed(webSocket: WebSocket, why: String) {
            if (socket === webSocket) socket = null
            info.completeExceptionally(MusicAssistantException(why))
            pending.values.forEach { it.answer.completeExceptionally(MusicAssistantException(why)) }
        }
    }

    companion object {
        /** The API schema from which Music Assistant wants a token (2.7). */
        const val AUTH_SCHEMA = 28

        /** "homeassistant.local", "ws://host:8095/ws" or "http://host:8095" as the server's web address. */
        fun normalize(address: String): String? {
            var text = address.trim().trimEnd('/')
            if (text.isEmpty()) return null
            text = text.removeSuffix("/ws")
            text = when {
                text.startsWith("ws://") -> "http://" + text.removePrefix("ws://")
                text.startsWith("wss://") -> "https://" + text.removePrefix("wss://")
                text.startsWith("http://") || text.startsWith("https://") -> text
                else -> "http://$text"
            }
            val hasPort = Regex("^https?://(\\[[^]]+]|[^/:]+):\\d+").containsMatchIn(text)
            if (!hasPort && text.startsWith("http://")) {
                val host = text.removePrefix("http://").substringBefore('/')
                text = "http://$host:$DEFAULT_PORT" + text.removePrefix("http://$host")
            }
            return text
        }

        const val DEFAULT_PORT = 8095
    }
}

/**
 * One Music Assistant player. Each song goes to it as a one-item queue (replacing what was
 * there), described as a track (title, artist, album, cover) so Music Assistant and its players
 * show it; a Music Assistant too old or too strict for that is given the bare address.
 */
class MusicAssistantRenderer(private val client: MusicAssistantClient, private val playerId: String) : Renderer {
    private fun args(vararg extra: Pair<String, JsonElement>) = JsonObject(mapOf("player_id" to JsonPrimitive(playerId)) + extra)

    override suspend fun load(media: SpeakerMedia) {
        try {
            client.command(
                "player_queues/play_media",
                buildJsonObject {
                    put("queue_id", playerId)
                    putJsonArray("media") { add(item(media)) }
                    put("option", "replace")
                },
            )
        } catch (e: MusicAssistantException) {
            if (e.code == null) throw e
            client.command(
                "player_queues/play_media",
                buildJsonObject {
                    put("queue_id", playerId)
                    put("media", media.url)
                    put("option", "replace")
                },
            )
        }
    }

    override suspend fun play() {
        client.command("players/cmd/play", args())
    }

    override suspend fun pause() {
        client.command("players/cmd/pause", args())
    }

    override suspend fun stop() {
        client.command("players/cmd/stop", args())
    }

    override suspend fun seek(positionMs: Long) {
        client.command("players/cmd/seek", args("position" to JsonPrimitive(positionMs / 1000)))
    }

    override suspend fun setVolume(level: Double) {
        client.command("players/cmd/volume_set", args("volume_level" to JsonPrimitive((level.coerceIn(0.0, 1.0) * 100).roundToInt())))
    }

    override suspend fun status(): RendererStatus {
        val player = client.command("players/get", args()) as? JsonObject ?: throw MusicAssistantException("Music Assistant has no player $playerId")
        // The queue says what it's playing (the song's address is in it) and how far in.
        val queue = runCatching { client.command("player_queues/get_active_queue", args()) as? JsonObject }.getOrNull()
        val state = queue?.string("state") ?: player.string("playback_state") ?: player.string("state")
        val elapsed = (queue?.get("elapsed_time") as? JsonPrimitive)?.doubleOrNull
            ?: ((player["current_media"] as? JsonObject)?.get("elapsed_time") as? JsonPrimitive)?.doubleOrNull
            ?: (player["elapsed_time"] as? JsonPrimitive)?.doubleOrNull
            ?: 0.0
        val current = queue?.get("current_item")?.takeIf { it !is JsonNull } ?: player["current_media"]
        return RendererStatus(
            transport = when (state?.lowercase()) {
                "playing" -> Transport.PLAYING
                "paused" -> Transport.PAUSED
                "buffering", "loading" -> Transport.BUFFERING
                else -> Transport.STOPPED
            },
            positionMs = (elapsed * 1000).toLong(),
            playing = current?.toString().orEmpty(),
            volume = (player["volume_level"] as? JsonPrimitive)?.intOrNull?.let { it / 100.0 },
        )
    }

    companion object {
        /** A song or station as a Music Assistant media item (from its built-in provider, which plays addresses). */
        fun item(media: SpeakerMedia): JsonObject = buildJsonObject {
            val type = if (media.live) "radio" else "track"
            put("item_id", media.url)
            put("provider", "builtin")
            put("name", media.title)
            put("media_type", type)
            put("uri", "builtin://$type/${media.url}")
            putJsonArray("provider_mappings") {
                addJsonObject {
                    put("item_id", media.url)
                    put("provider_domain", "builtin")
                    put("provider_instance", "builtin")
                    put("available", true)
                }
            }
            if (!media.live) {
                media.durationMs?.let { put("duration", (it / 1000).toInt()) }
                putJsonArray("artists") {
                    addJsonObject {
                        put("item_id", media.artist)
                        put("provider", "builtin")
                        put("name", media.artist)
                        put("media_type", "artist")
                    }
                }
                media.album?.takeIf { it.isNotBlank() }?.let { album ->
                    putJsonObject("album") {
                        put("item_id", album)
                        put("provider", "builtin")
                        put("name", album)
                        put("media_type", "album")
                    }
                }
            }
            putJsonObject("metadata") {
                media.artUrl?.let { art ->
                    putJsonArray("images") {
                        addJsonObject {
                            put("type", "thumb")
                            put("path", art)
                            put("provider", "builtin")
                            put("remotely_accessible", true)
                        }
                    }
                }
            }
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
