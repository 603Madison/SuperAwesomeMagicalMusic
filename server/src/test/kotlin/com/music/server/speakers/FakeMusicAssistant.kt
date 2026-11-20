package com.music.server.speakers

import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A pretend Music Assistant server (2.7, schema 28): its websocket API at /ws, as the real one
 * answers (server info first, then `auth`, then commands; long lists in partial pieces).
 */
class FakeMusicAssistant(private val token: String = "ma-token", private val schema: Int = 28) : AutoCloseable {
    data class Call(val command: String, val args: JsonObject)

    class Player(val id: String, val name: String, val provider: String, val extra: Map<String, JsonElement> = emptyMap()) {
        @Volatile var state = "idle"
        @Volatile var elapsed = 0.0
        @Volatile var volume = 20
        @Volatile var current: JsonElement = JsonNull
    }

    val calls = CopyOnWriteArrayList<Call>()
    /** Refuses media items given as objects (an older or stricter Music Assistant). */
    @Volatile var strict = false
    val players = ConcurrentHashMap<String, Player>().apply {
        put("kitchen", Player("kitchen", "Kitchen", "chromecast--Xa1b2"))
        put("office", Player("office", "Office", "airplay"))
        put("hidden", Player("hidden", "Hidden", "dlna", mapOf("hide_in_ui" to JsonPrimitive(true))))
        put("follower", Player("follower", "Follower", "squeezelite", mapOf("synced_to" to JsonPrimitive("office"))))
        put("gone", Player("gone", "Gone", "sonos", mapOf("available" to JsonPrimitive(false))))
    }

    fun commands(): List<String> = calls.map { it.command }
    fun last(command: String): Call? = calls.lastOrNull { it.command == command }

    private val server: EmbeddedServer<NettyApplicationEngine, *> = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
        install(WebSockets)
        routing {
            webSocket("/ws") {
                send(Frame.Text(buildJsonObject {
                    put("server_id", "f00dfeed")
                    put("server_version", "2.7.2")
                    put("schema_version", schema)
                    put("min_supported_schema_version", 24)
                    put("base_url", "http://127.0.0.1:8095")
                    put("homeassistant_addon", false)
                    put("onboard_done", true)
                }.toString()))
                var authed = false
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = Json.parseToJsonElement(frame.readText()).jsonObject
                    val id = message["message_id"]!!.let { (it as JsonPrimitive).content }
                    val command = (message["command"] as JsonPrimitive).content
                    val args = message["args"] as? JsonObject ?: JsonObject(emptyMap())
                    calls += Call(command, args)
                    fun ok(result: JsonElement, partial: Boolean = false) = Frame.Text(buildJsonObject {
                        put("message_id", id)
                        put("result", result)
                        if (partial) put("partial", true)
                    }.toString())
                    fun error(code: Int, details: String) = Frame.Text(buildJsonObject {
                        put("message_id", id)
                        put("error_code", code)
                        put("details", details)
                    }.toString())
                    if (command == "auth") {
                        if (args["token"]?.let { (it as JsonPrimitive).content } == token) {
                            authed = true
                            send(ok(buildJsonObject { put("authenticated", true); put("user", buildJsonObject { put("username", "samm") }) }))
                        } else {
                            send(error(22, "Invalid or expired token"))
                        }
                        continue
                    }
                    if (schema >= 28 && !authed) {
                        send(error(20, "Authentication required. Please send auth command first."))
                        continue
                    }
                    val player = (args["player_id"] ?: args["queue_id"])?.let { players[(it as JsonPrimitive).content] }
                    when (command) {
                        "players/all" -> {
                            // In two pieces, as long lists come.
                            val all = players.values.sortedBy { it.id }.map(::playerJson)
                            send(ok(JsonArray(all.take(2)), partial = true))
                            send(ok(JsonArray(all.drop(2))))
                        }
                        "players/get" -> send(player?.let { ok(playerJson(it)) } ?: error(404, "No such player"))
                        "player_queues/get_active_queue" -> send(player?.let {
                            ok(buildJsonObject {
                                put("queue_id", it.id)
                                put("state", it.state)
                                put("elapsed_time", it.elapsed)
                                put("current_item", it.current)
                            })
                        } ?: error(404, "No such queue"))
                        "player_queues/play_media" -> {
                            val media = args["media"]!!
                            if (player == null) {
                                send(error(404, "Queue is not available"))
                            } else if (strict && media is JsonArray && media.firstOrNull() is JsonObject) {
                                send(error(999, "Invalid data"))
                            } else {
                                val first = if (media is JsonArray) media.first() else media
                                player.current = buildJsonObject {
                                    put("queue_item_id", "qi-${calls.size}")
                                    put("name", (first as? JsonObject)?.get("name") ?: JsonPrimitive("from url"))
                                    put("media_item", if (first is JsonObject) first else buildJsonObject { put("item_id", first) ; put("uri", first) })
                                }
                                player.state = "playing"
                                player.elapsed = 0.0
                                send(ok(JsonNull))
                            }
                        }
                        "players/cmd/pause" -> { player?.state = "paused"; send(ok(JsonNull)) }
                        "players/cmd/play" -> { player?.state = "playing"; send(ok(JsonNull)) }
                        "players/cmd/stop" -> { player?.state = "idle"; send(ok(JsonNull)) }
                        "players/cmd/seek" -> { player?.elapsed = (args["position"] as JsonPrimitive).doubleOrNull ?: 0.0; send(ok(JsonNull)) }
                        "players/cmd/volume_set" -> { player?.volume = (args["volume_level"] as JsonPrimitive).intOrNull ?: 0; send(ok(JsonNull)) }
                        else -> send(error(999, "Invalid command: $command"))
                    }
                }
            }
        }
    }.start(wait = false)

    val url: String = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"

    private fun playerJson(player: Player): JsonObject = buildJsonObject {
        put("player_id", player.id)
        put("provider", player.provider)
        put("type", "player")
        put("name", player.name)
        put("available", true)
        put("playback_state", player.state)
        put("state", player.state)
        put("elapsed_time", player.elapsed)
        put("volume_level", player.volume)
        put("volume_muted", false)
        put("group_members", buildJsonArray { })
        put("synced_to", JsonNull)
        put("enabled", true)
        put("hide_in_ui", false)
        put("display_name", player.name)
        put("supported_features", buildJsonArray { add(JsonPrimitive("volume_set")); add(JsonPrimitive("seek")) })
        put("device_info", buildJsonObject { put("model", "Speaker"); put("manufacturer", "Test") })
        player.extra.forEach { (key, value) -> put(key, value) }
    }

    /** What the player was last given to play (the media item, or the bare address). */
    fun playing(id: String): JsonObject? = (players[id]?.current as? JsonObject)?.get("media_item") as? JsonObject

    fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    override fun close() = server.stop(100, 500)
}
