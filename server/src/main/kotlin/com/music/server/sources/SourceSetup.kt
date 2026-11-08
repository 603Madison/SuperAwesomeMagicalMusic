package com.music.server.sources

import com.music.player.lidarr.LidarrClient
import com.music.player.net.Http
import com.music.player.net.HttpException
import com.music.player.settings.JellyfinConfig
import com.music.player.settings.LidarrConfig
import com.music.player.settings.QobuzConfig
import com.music.player.settings.SiriusXmConfig
import com.music.player.settings.TranscodeCodec
import com.music.player.sources.MusicSource
import com.music.player.sources.jellyfin.JellyfinSource
import com.music.player.sources.qobuz.QobuzSource
import com.music.player.sources.radio.RadioBrowserClient
import com.music.player.sources.siriusxm.SiriusXmClient
import com.music.player.sources.youtube.YouTubeMusicSource
import com.music.server.ApiError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** One thing to fill in when adding a source, for the web player's forms. */
@Serializable
data class FieldSpec(
    val key: String,
    val label: String,
    /** text, url, password, bool or select. */
    val type: String = "text",
    val required: Boolean = false,
    /** Never sent back; left empty when editing, it keeps what's saved. */
    val secret: Boolean = false,
    val help: String? = null,
    val options: List<String>? = null,
    val default: String? = null,
)

@Serializable
data class KindSpec(val kind: SourceKind, val label: String, val description: String, val fields: List<FieldSpec>)

/**
 * Adding and changing sources: what each kind asks for, and signing in to the service so only
 * what's needed is kept (Jellyfin's token rather than the password, for one).
 */
class SourceSetup(private val http: OkHttpClient) {
    private val lidarr = LidarrClient(http)

    val kinds: List<KindSpec> = listOf(
        KindSpec(
            SourceKind.JELLYFIN, "Jellyfin",
            "Your music library. Songs from any service are looked for here first.",
            listOf(
                FieldSpec("serverUrl", "Server address", "url", required = true, help = "e.g. https://jellyfin.example.com"),
                FieldSpec("username", "Username", required = true),
                FieldSpec("password", "Password", "password", secret = true, help = "Used once to sign in; only Jellyfin's sign-in token is kept."),
                FieldSpec("transcodeCodec", "Format for lower bitrates", "select", options = TranscodeCodec.entries.map { it.name }, default = "MP3"),
            ),
        ),
        KindSpec(
            SourceKind.YOUTUBE_MUSIC, "YouTube Music",
            "YouTube Music's catalogue, without an account.",
            emptyList(),
        ),
        KindSpec(
            SourceKind.SIRIUSXM, "SiriusXM",
            "Live channels, Xtra channels and Pandora artist stations, with your SiriusXM streaming account.",
            listOf(
                FieldSpec("username", "Email or username", required = true),
                FieldSpec("password", "Password", "password", required = true, secret = true, help = "Kept (encrypted) so the server can sign in again when SiriusXM's session ends."),
            ),
        ),
        KindSpec(
            SourceKind.RADIO, "Radio",
            "Tens of thousands of local and internet radio stations from Radio Browser, by name or near you.",
            emptyList(),
        ),
        KindSpec(
            SourceKind.LIDARR, "Lidarr",
            "Adds artists to Lidarr when their songs are played from outside your Jellyfin library.",
            listOf(
                FieldSpec("serverUrl", "Server address", "url", required = true, help = "e.g. https://lidarr.example.com"),
                FieldSpec("apiKey", "API key", "password", required = true, secret = true, help = "Lidarr: Settings > General > Security"),
                FieldSpec("autoAddArtists", "Add artists automatically", "bool", default = "true"),
                FieldSpec("searchForMissingAlbums", "Search for their albums straight away", "bool", default = "true"),
            ),
        ),
    )

    /**
     * The settings and secrets to save for [kind] from what was filled in, signing in where the
     * service needs it. [previous] is the source being changed: secrets left empty keep its values.
     */
    suspend fun configure(kind: SourceKind, id: String, fields: JsonObject, previous: StoredSource?): Pair<JsonObject, JsonObject> {
        fun text(key: String): String? = (fields[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun bool(key: String, default: Boolean): Boolean =
            (fields[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() } ?: previous?.settingBool(key, default) ?: default
        fun required(key: String, label: String): String = text(key) ?: previous?.setting(key) ?: throw ApiError.badRequest("$label is needed")

        return when (kind) {
            SourceKind.JELLYFIN -> {
                val serverUrl = url(required("serverUrl", "The server address"))
                val username = required("username", "The username")
                val codec = text("transcodeCodec")?.let { name -> TranscodeCodec.entries.firstOrNull { it.name == name } }
                    ?: previous?.setting("transcodeCodec")?.let { name -> TranscodeCodec.entries.firstOrNull { it.name == name } }
                    ?: TranscodeCodec.MP3
                val password = text("password")
                val sameAccount = previous != null && previous.setting("serverUrl") == serverUrl && previous.setting("username") == username
                val config = if (password == null && sameAccount && previous!!.secret("accessToken").isNotEmpty()) {
                    JellyfinConfig(serverUrl, username, previous.setting("userId").orEmpty(), previous.secret("accessToken"))
                } else {
                    if (password == null) throw ApiError.badRequest("The password is needed to sign in to Jellyfin")
                    val signIn = JellyfinSource(http, config = { JellyfinConfig() }, deviceId = { deviceId(id) })
                    service("Jellyfin") { signIn.authenticate(serverUrl, username, password) }
                }
                buildJsonObject {
                    put("serverUrl", config.serverUrl)
                    put("username", config.username)
                    put("userId", config.userId)
                    put("transcodeCodec", codec.name)
                } to buildJsonObject { put("accessToken", config.accessToken) }
            }
            // Qobuz's API only works with keys Qobuz gives its partners, so it can't be set up here.
            SourceKind.QOBUZ -> throw ApiError.badRequest("Qobuz can't be added: its API needs an app ID and secret that Qobuz only gives its partners")
            SourceKind.YOUTUBE_MUSIC, SourceKind.RADIO -> JsonObject(emptyMap()) to JsonObject(emptyMap())
            SourceKind.SIRIUSXM -> {
                val username = required("username", "The username")
                val password = text("password") ?: previous?.secret("password")?.ifEmpty { null } ?: throw ApiError.badRequest("The password is needed")
                val config = SiriusXmConfig(username, password)
                val channels = service("SiriusXM") {
                    SiriusXmClient(http, credentials = { config }, loadSession = { null }, saveSession = {}).verify(config)
                }
                if (channels == 0) throw ApiError.badRequest("SiriusXM signed in but listed no channels")
                buildJsonObject { put("username", username) } to buildJsonObject { put("password", password) }
            }
            SourceKind.LIDARR -> {
                val serverUrl = url(required("serverUrl", "The server address"))
                val apiKey = text("apiKey") ?: previous?.secret("apiKey")?.ifEmpty { null } ?: throw ApiError.badRequest("The API key is needed")
                val config = LidarrConfig(serverUrl = serverUrl, apiKey = apiKey)
                service("Lidarr") { lidarr.testConnection(config) }
                buildJsonObject {
                    put("serverUrl", serverUrl)
                    put("autoAddArtists", bool("autoAddArtists", true))
                    put("searchForMissingAlbums", bool("searchForMissingAlbums", true))
                    (text("qualityProfileId")?.toIntOrNull() ?: previous?.settingInt("qualityProfileId"))?.let { put("qualityProfileId", it) }
                    (text("metadataProfileId")?.toIntOrNull() ?: previous?.settingInt("metadataProfileId"))?.let { put("metadataProfileId", it) }
                    (text("rootFolderPath") ?: previous?.setting("rootFolderPath"))?.let { put("rootFolderPath", it) }
                } to buildJsonObject { put("apiKey", apiKey) }
            }
        }
    }

    /** Checks that a saved source still works, with a short description of what was found. */
    suspend fun test(source: StoredSource, registry: SourceRegistry): String = when (source.kind) {
        SourceKind.LIDARR -> "Lidarr " + service("Lidarr") { lidarr.testConnection(registry.lidarrConfig(source)) }
        SourceKind.SIRIUSXM -> {
            val channels = service("SiriusXM") { registry.siriusXm(source)!!.channels(refresh = true) }
            "Working: ${channels.count { !it.locked }} channels in your subscription"
        }
        SourceKind.RADIO -> {
            val found = service("Radio Browser") { registry.radio.search("jazz", limit = 5) }
            "Working: Radio Browser answered with ${found.size} stations"
        }
        else -> {
            val music = registry.music(source) ?: throw ApiError.badRequest("Nothing to test")
            val found = service(source.kind.label) { music.search("love", 3) }
            "Working: a test search found ${found.size} song${if (found.size == 1) "" else "s"}"
        }
    }

    private fun url(raw: String): String {
        val url = Http.normalizeBaseUrl(raw)
        if (!url.startsWith("http://") && !url.startsWith("https://") || url.toHttpUrlOrNull() == null) {
            throw ApiError.badRequest("That isn't a web address")
        }
        return url
    }

    companion object {
        fun deviceId(sourceId: String) = "samm-server-$sourceId"

        /** Runs a call to a service, turning its failures into a message for the person. */
        suspend fun <T> service(label: String, block: suspend () -> T): T = try {
            block()
        } catch (e: ApiError) {
            throw e
        } catch (e: HttpException) {
            throw ApiError.badRequest(
                when (e.code) {
                    401, 403 -> "$label didn't accept those details"
                    404 -> "$label wasn't found at that address"
                    else -> "$label answered HTTP ${e.code}"
                },
            )
        } catch (e: IOException) {
            throw ApiError.badRequest("Couldn't reach $label: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiError.badRequest("$label: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/**
 * The service clients for saved sources, made once per version of each source and kept, so
 * their sign-ins and caches are shared by everyone using the source.
 */
class SourceRegistry(private val http: OkHttpClient, private val store: SourceStore) {
    private val instances = ConcurrentHashMap<String, Pair<Long, MusicSource>>()
    private val siriusXmClients = ConcurrentHashMap<String, Pair<Long, SiriusXmClient>>()
    private val youtube by lazy { YouTubeMusicSource(http) }

    /** Radio Browser's directory, the same for everyone. */
    val radio by lazy { RadioBrowserClient(http, Http.USER_AGENT) }

    /** The signed-in SiriusXM client for a SiriusXM source; its session is kept between starts. */
    fun siriusXm(source: StoredSource): SiriusXmClient? {
        if (source.kind != SourceKind.SIRIUSXM) return null
        siriusXmClients[source.id]?.let { (at, client) -> if (at == source.updatedAt) return client }
        val config = SiriusXmConfig(source.setting("username").orEmpty(), source.secret("password"))
        val client = SiriusXmClient(
            http,
            credentials = { config },
            loadSession = { store.loadState(source.id) },
            saveSession = { store.saveState(source.id, it) },
        )
        siriusXmClients[source.id] = source.updatedAt to client
        return client
    }

    fun music(source: StoredSource): MusicSource? = when (source.kind) {
        SourceKind.JELLYFIN -> cached(source) {
            val config = JellyfinConfig(
                serverUrl = source.setting("serverUrl").orEmpty(),
                username = source.setting("username").orEmpty(),
                userId = source.setting("userId").orEmpty(),
                accessToken = source.secret("accessToken"),
            )
            val codec = TranscodeCodec.entries.firstOrNull { it.name == source.setting("transcodeCodec") } ?: TranscodeCodec.MP3
            JellyfinSource(http, config = { config }, deviceId = { SourceSetup.deviceId(source.id) }, transcodeCodec = { codec })
        }
        SourceKind.QOBUZ -> cached(source) {
            val config = QobuzConfig(
                appId = source.setting("appId").orEmpty(),
                appSecret = source.secret("appSecret"),
                username = source.setting("username").orEmpty(),
                userAuthToken = source.secret("userAuthToken"),
            )
            QobuzSource(http) { config }
        }
        SourceKind.YOUTUBE_MUSIC -> youtube
        SourceKind.LIDARR, SourceKind.SIRIUSXM, SourceKind.RADIO -> null
    }

    fun jellyfin(source: StoredSource): JellyfinSource? = music(source) as? JellyfinSource

    fun lidarrConfig(source: StoredSource) = LidarrConfig(
        serverUrl = source.setting("serverUrl").orEmpty(),
        apiKey = source.secret("apiKey"),
        autoAddArtists = source.settingBool("autoAddArtists", true),
        qualityProfileId = source.settingInt("qualityProfileId"),
        metadataProfileId = source.settingInt("metadataProfileId"),
        rootFolderPath = source.setting("rootFolderPath"),
        searchForMissingAlbums = source.settingBool("searchForMissingAlbums", true),
    )

    fun forget(id: String) {
        instances.remove(id)
        siriusXmClients.remove(id)
    }

    private fun cached(source: StoredSource, make: () -> MusicSource): MusicSource {
        val existing = instances[source.id]
        if (existing != null && existing.first == source.updatedAt) return existing.second
        return make().also { instances[source.id] = source.updatedAt to it }
    }
}
