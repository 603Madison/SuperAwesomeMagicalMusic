package com.music.server.speakers

import com.music.player.speakers.sonos.SonosClient
import com.music.player.speakers.sonos.SonosNetwork
import com.music.server.ApiError
import com.music.server.sources.FieldSpec
import com.music.server.sources.KindSpec
import com.music.server.sources.SourceKind
import com.music.server.sources.StoredSource
import com.music.server.speakers.musicassistant.MusicAssistantClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Sonos and Music Assistant, set up as sources: shared by the admins (everyone gets the
 * speakers), or added by someone for themselves (only they do). The source's on/off switch turns
 * them on and off; each person can hide a shared one, like any shared source.
 */
object SpeakerSetup {
    private val speakerUrl = FieldSpec(
        "speakerUrl", "This server's address for speakers", "url",
        help = "Optional: where speakers fetch songs from, e.g. http://192.168.1.10:8080. Worked out by itself, " +
            "unless the server is in a container with its own network (then use the computer's address and the published port).",
    )

    val kinds = listOf(
        KindSpec(
            SourceKind.SONOS, "Sonos",
            "Your Sonos speakers, as places to play: they show up with your devices, and anything (songs, radio, your stations) " +
                "can be moved to one and controlled from any of them. Grouped speakers play together.",
            listOf(
                FieldSpec(
                    "discover", "Find speakers on the network", "bool", default = "true",
                    help = "Needs the server on the speakers' network (in a container: host networking). Otherwise add one speaker's address.",
                ),
                FieldSpec(
                    "addresses", "Speakers' addresses",
                    help = "Optional, e.g. 192.168.1.20. One speaker is enough: the others in its household are found through it.",
                ),
                speakerUrl,
            ),
        ),
        KindSpec(
            SourceKind.MUSIC_ASSISTANT, "Music Assistant",
            "Music Assistant's players (Sonos, Chromecast, AirPlay, Squeezebox, DLNA and Home Assistant's media players), as places to play: " +
                "they show up with your devices, and music can be moved to one and controlled from any of them.",
            listOf(
                FieldSpec("serverUrl", "Music Assistant's address", "url", required = true, help = "e.g. http://homeassistant.local:8095"),
                FieldSpec(
                    "token", "Token", "password", secret = true,
                    help = "A long-lived token from Music Assistant's web interface (Music Assistant 2.7 and later ask for one).",
                ),
                speakerUrl,
            ),
        ),
    )

    /** "192.168.1.20, 192.168.1.21" as a list of addresses. */
    fun addresses(raw: String?): List<String> = raw.orEmpty().split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The settings and secrets for a Sonos or Music Assistant source from [fields], checking it
     * answers. [checkedUrl] refuses addresses the person may not point the server at.
     */
    suspend fun configure(kind: SourceKind, fields: JsonObject, previous: StoredSource?, http: OkHttpClient, checkedUrl: (String) -> String): Pair<JsonObject, JsonObject> {
        fun text(key: String): String? = (fields[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun given(key: String): String? = if (fields.containsKey(key)) text(key) else previous?.setting(key)
        val forSpeakers = given("speakerUrl")?.let { raw ->
            val url = raw.trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
            url.takeIf { it.toHttpUrlOrNull() != null } ?: throw ApiError.badRequest("This server's address for speakers isn't a web address")
        }
        return when (kind) {
            SourceKind.SONOS -> {
                val discover = (fields["discover"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
                    ?: previous?.settingBool("discover", true) ?: true
                val addresses = addresses(given("addresses"))
                val bases = addresses.map { address ->
                    val base = SonosClient.baseOf(address) ?: throw ApiError.badRequest("$address isn't a speaker's address")
                    checkedUrl(base)
                    base
                }
                if (!discover && bases.isEmpty()) throw ApiError.badRequest("Turn on finding speakers, or add a speaker's address")
                if (bases.isNotEmpty()) {
                    // One that's given must answer; the household's other speakers come through it.
                    val groups = SonosNetwork(http, bases, discover = false).refresh()
                    if (groups.isEmpty()) throw ApiError.badRequest("Couldn't reach a Sonos at ${addresses.joinToString(", ")}")
                }
                buildJsonObject {
                    put("discover", discover)
                    put("addresses", addresses.joinToString(", "))
                    forSpeakers?.let { put("speakerUrl", it) }
                } to JsonObject(emptyMap())
            }
            SourceKind.MUSIC_ASSISTANT -> {
                val raw = given("serverUrl") ?: throw ApiError.badRequest("Music Assistant's address is needed")
                val serverUrl = checkedUrl(MusicAssistantClient.normalize(raw) ?: throw ApiError.badRequest("That isn't a web address"))
                val token = text("token") ?: previous?.secret("token")?.ifEmpty { null }
                val players = try {
                    MusicAssistantClient(http, serverUrl, token).use { it.players() }
                } catch (e: SpeakerException) {
                    throw ApiError.badRequest(e.message ?: "Couldn't reach Music Assistant")
                } catch (e: java.io.IOException) {
                    throw ApiError.badRequest("Couldn't reach Music Assistant: ${e.message ?: e.javaClass.simpleName}")
                }
                if (players.isEmpty()) throw ApiError.badRequest("Music Assistant answered, but has no players")
                buildJsonObject {
                    put("serverUrl", serverUrl)
                    forSpeakers?.let { put("speakerUrl", it) }
                } to buildJsonObject { token?.let { put("token", it) } }
            }
            else -> throw ApiError.badRequest("That isn't a kind of speaker")
        }
    }

    /** Checks a saved Sonos or Music Assistant source reaches its speakers, saying which. */
    suspend fun test(source: StoredSource, http: OkHttpClient): String = when (source.kind) {
        SourceKind.SONOS -> {
            val network = SonosNetwork(http, addresses(source.setting("addresses")), source.settingBool("discover", true))
            val groups = network.refresh()
            if (groups.isEmpty()) throw ApiError.badRequest(network.problem ?: "No Sonos speakers found")
            "Working: ${count(groups.size, "place")} to play (${groups.joinToString(", ") { it.name }.take(200)})"
        }
        SourceKind.MUSIC_ASSISTANT -> try {
            MusicAssistantClient(http, source.setting("serverUrl").orEmpty(), source.secret("token").ifEmpty { null }).use { client ->
                val players = client.players().filter { it.available && !it.hidden && it.syncedTo == null }
                val version = (client.serverInfo?.get("server_version") as? JsonPrimitive)?.contentOrNull
                "Working: Music Assistant${version?.let { " $it" }.orEmpty()} with ${count(players.size, "player")}" +
                    if (players.isEmpty()) "" else " (${players.joinToString(", ") { it.name }.take(200)})"
            }
        } catch (e: java.io.IOException) {
            throw ApiError.badRequest(e.message ?: "Couldn't reach Music Assistant")
        }
        else -> throw ApiError.badRequest("Nothing to test")
    }

    private fun count(n: Int, what: String) = "$n $what${if (n == 1) "" else "s"}"
}
