package com.music.mini.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// The server's API (server/src/main/kotlin), the parts Mini uses. The same calls as the
// Connect app's (connect/.../net/ServerApi.kt), kept separate so Mini stays small.

@Serializable
data class ApiTrack(
    /** The service: JELLYFIN, SUBSONIC, YOUTUBE_MUSIC, SIRIUSXM, RADIO… */
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val isrc: String? = null,
    /** The saved source on the server the song came from. */
    val via: String? = null,
) {
    val key: String get() = "${via.orEmpty()}|$source:$sourceId"

    val isChannel: Boolean get() = source == SIRIUSXM && sourceId.startsWith("channel:") && !isPandoraStation
    val isStationTrack: Boolean get() = source == SIRIUSXM && sourceId.startsWith("stationtrack:")
    val isRadio: Boolean get() = source == RADIO

    /** One of the person's own stations (their Radio, Rewind or Discovery), made by the server. */
    val isPersonal: Boolean get() = source == RADIO && via == PERSONAL

    /** A Pandora artist station itself (played by starting the station). */
    val isPandoraStation: Boolean get() = source == SIRIUSXM && sourceId.startsWith(PANDORA_STATION)

    /** Live: no end and no seeking (SiriusXM channels, radio stations). */
    val isLive: Boolean get() = isChannel || isRadio

    /** Can go in a playlist (live radio and station tracks can't). */
    val isSong: Boolean get() = !isLive && !isStationTrack && !isPandoraStation

    /** A song a playlist names that wasn't found in any source: never played. */
    val isMissing: Boolean get() = sourceId.startsWith("missing:")

    companion object {
        const val SIRIUSXM = "SIRIUSXM"
        const val RADIO = "RADIO"
        const val PANDORA_STATION = "channel:artist-station:"
        const val PERSONAL = "personal"
    }
}

@Serializable
data class User(val id: String, val username: String, val displayName: String, val role: String = "USER")

@Serializable
data class AuthConfig(val serverName: String, val version: String, val localLogin: Boolean = true)

@Serializable
data class SignedIn(val user: User, val token: String? = null)

@Serializable
data class ArtistSummary(val id: String, val name: String, val artworkUrl: String? = null)

@Serializable
data class AlbumSummary(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "",
    val year: Int? = null,
    val artworkUrl: String? = null,
    val trackCount: Int? = null,
)

@Serializable
data class TopResult(val kind: String, val artist: ArtistSummary? = null, val album: AlbumSummary? = null, val track: ApiTrack? = null)

@Serializable
data class SearchResponse(
    val query: String = "",
    val top: TopResult? = null,
    val songs: List<ApiTrack> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
)

@Serializable
data class ArtistPage(val artist: ArtistSummary, val topSongs: List<ApiTrack> = emptyList(), val albums: List<AlbumSummary> = emptyList(), val singles: List<AlbumSummary> = emptyList())

@Serializable
data class AlbumPage(val album: AlbumSummary, val tracks: List<ApiTrack>)

@Serializable
data class ResolvedStream(val track: ApiTrack, val streamUrl: String, val description: String = "", val kbps: Int? = null)

@Serializable
data class PlaylistSummary(val id: String, val name: String, val count: Int = 0, val durationMs: Long = 0)

@Serializable
data class PlaylistEntry(val entryId: String, val track: ApiTrack)

@Serializable
data class Playlist(val summary: PlaylistSummary, val entries: List<PlaylistEntry>)

@Serializable
data class Listen(val track: ApiTrack, val playedAt: Long, val listenedMs: Long = 0, val client: String = "android", val station: String? = null)

@Serializable
data class NowView(val title: String, val artist: String, val album: String? = null, val artworkUrl: String? = null, val song: ApiTrack? = null)

@Serializable
data class PersonalStation(val track: ApiTrack, val kind: String = "", val name: String, val tagline: String = "", val ready: Boolean = true)

/** What's on one of the person's own stations; [songKey] is what a thumb is given to. */
@Serializable
data class PersonalNow(val station: ApiTrack, val now: NowView? = null, val songKey: String? = null, val thumb: String? = null)

@Serializable
data class StationTracks(val station: ApiTrack, val tracks: List<ApiTrack>)

@Serializable
data class RadioNow(val title: String? = null)

/** A code sign-in, started (see the server's DeviceRoutes). */
@Serializable
data class DeviceStart(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String = verificationUri,
    val interval: Int = 5,
    val expiresIn: Int = 600,
)

/** How a code sign-in is going: pending, slow_down, approved, denied or expired. */
@Serializable
data class DevicePoll(val status: String, val interval: Int? = null, val user: User? = null, val token: String? = null, val message: String? = null)

@Serializable
private data class ErrorBody(val error: String)

/** An error the server explained, fit to show. */
class ServerException(val code: Int, message: String) : IOException(message)

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * Calls to the person's server. [base] is the server's address and [token] the sign-in; both are
 * read at each call, so signing in or out takes effect at once.
 */
class Api(
    private val http: OkHttpClient,
    private val base: () -> String,
    private val token: () -> String?,
) {
    suspend fun authConfig(server: String = base()): AuthConfig = get("$server/api/auth/config", AuthConfig.serializer(), auth = false)

    suspend fun startCodeSignIn(server: String, deviceName: String): DeviceStart =
        post("$server/api/auth/device/start", ApiJson.encodeToString(StartBody.serializer(), StartBody(deviceName)), DeviceStart.serializer(), auth = false)

    suspend fun pollCodeSignIn(server: String, deviceCode: String): DevicePoll =
        post("$server/api/auth/device/poll", ApiJson.encodeToString(PollBody.serializer(), PollBody(deviceCode)), DevicePoll.serializer(), auth = false)

    /** Hands a sign-in ticket from SuperAwesomeMagicalOrchestrator to the server at [server], for Mini's own token. */
    suspend fun orchestratorSignIn(server: String, ticket: String, deviceName: String): SignedIn =
        post("$server/api/auth/orchestrator", ApiJson.encodeToString(TicketBody.serializer(), TicketBody(ticket, deviceName)), SignedIn.serializer(), auth = false)

    suspend fun logout() = runCatching { post("${base()}/api/auth/logout", "{}", Unit.serializer()) }

    suspend fun search(query: String): SearchResponse = get("${base()}/api/search?q=${enc(query)}&limit=20", SearchResponse.serializer())

    /** An artist's page: by a search's [id], or by [name] (a song's artist). */
    suspend fun artist(id: String?, name: String? = null): ArtistPage =
        get("${base()}/api/artist?" + if (id != null) "id=${enc(id)}" else "name=${enc(name.orEmpty())}", ArtistPage.serializer())

    /** An album: by a search's [id], or by [title] and [artist] (a song's album). */
    suspend fun album(id: String?, title: String? = null, artist: String? = null): AlbumPage =
        get("${base()}/api/album?" + if (id != null) "id=${enc(id)}" else "title=${enc(title.orEmpty())}&artist=${enc(artist.orEmpty())}", AlbumPage.serializer())

    suspend fun resolve(track: ApiTrack, kbps: Int): ResolvedStream = post(
        "${base()}/api/play/resolve",
        ApiJson.encodeToString(ResolveBody.serializer(), ResolveBody(track, kbps, CODECS, thorough = false)),
        ResolvedStream.serializer(),
    )

    suspend fun playlists(): List<PlaylistSummary> = get("${base()}/api/playlists", ListSerializer(PlaylistSummary.serializer()))

    suspend fun playlist(id: String): Playlist = get("${base()}/api/playlists/${enc(id)}", Playlist.serializer())

    suspend fun addToPlaylist(id: String, tracks: List<ApiTrack>): Playlist =
        post("${base()}/api/playlists/${enc(id)}/tracks", ApiJson.encodeToString(AddBody.serializer(), AddBody(tracks)), Playlist.serializer())

    suspend fun addListens(listens: List<Listen>) =
        post("${base()}/api/history", ApiJson.encodeToString(ListensBody.serializer(), ListensBody(listens)), Unit.serializer())

    suspend fun history(): List<Listen> = get("${base()}/api/history?limit=100", ListSerializer(Listen.serializer()))

    suspend fun liveFavorites(): List<ApiTrack> = get("${base()}/api/live/favorites", ListSerializer(ApiTrack.serializer()))

    suspend fun personalStations(): List<PersonalStation> = get("${base()}/api/radio/personal", ListSerializer(PersonalStation.serializer()))

    suspend fun personalNow(id: String, zone: String): PersonalNow =
        get("${base()}/api/radio/personal/${enc(id)}/now?tz=${enc(zone)}", PersonalNow.serializer())

    suspend fun personalSkip(id: String) = post("${base()}/api/radio/personal/${enc(id)}/skip", "{}", Unit.serializer())

    suspend fun personalThumb(id: String, key: String, thumb: String) = post(
        "${base()}/api/radio/personal/${enc(id)}/thumb",
        ApiJson.encodeToString(StationThumbBody.serializer(), StationThumbBody(key, thumb)),
        Unit.serializer(),
    )

    suspend fun station(src: String, stationId: String, name: String?, more: Boolean): StationTracks = post(
        "${base()}/api/live/sxm/${enc(src)}/station",
        ApiJson.encodeToString(StationBody.serializer(), StationBody(null, stationId, name, more)),
        StationTracks.serializer(),
    )

    suspend fun stationThumb(src: String, stationId: String, trackId: String, thumb: String, elapsedMs: Long) = post(
        "${base()}/api/live/sxm/${enc(src)}/station-track/${enc(stationId)}/${enc(trackId)}/thumb",
        ApiJson.encodeToString(ThumbBody.serializer(), ThumbBody(thumb, elapsedMs)),
        Unit.serializer(),
    )

    suspend fun radioNow(src: String, id: String): RadioNow = get("${base()}/api/live/radio/${enc(src)}/station/${enc(id)}/now", RadioNow.serializer())

    /** The full address of a server path (artwork and stream paths come back relative). */
    fun absolute(path: String): String = if (path.startsWith("http://") || path.startsWith("https://")) path else base() + path

    // ---- plumbing ----

    private suspend fun <T> get(url: String, serializer: KSerializer<T>, auth: Boolean = true): T = send("GET", url, null, serializer, auth)

    private suspend fun <T> post(url: String, body: String, serializer: KSerializer<T>, auth: Boolean = true): T = send("POST", url, body, serializer, auth)

    private suspend fun <T> send(method: String, url: String, body: String?, serializer: KSerializer<T>, auth: Boolean): T =
        withContext(Dispatchers.IO) {
            val requestBody = body?.toRequestBody(JSON)
            val request = Request.Builder().url(url).method(method, requestBody).apply {
                if (auth) token()?.let { header("Authorization", "Bearer $it") }
            }.build()
            http.newCall(request).execute().use { parse(it, serializer) }
        }

    private fun <T> parse(response: okhttp3.Response, serializer: KSerializer<T>): T {
        val text = response.body.string()
        if (!response.isSuccessful) {
            val message = runCatching { ApiJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
            throw ServerException(response.code, message ?: "The server answered HTTP ${response.code}")
        }
        @Suppress("UNCHECKED_CAST")
        if (serializer == Unit.serializer()) return Unit as T
        return ApiJson.decodeFromString(serializer, text)
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    @Serializable private data class StartBody(val deviceName: String)
    @Serializable private data class PollBody(val deviceCode: String)
    @Serializable private data class TicketBody(val ticket: String, val deviceName: String)
    @Serializable private data class ResolveBody(val track: ApiTrack, val kbps: Int, val codecs: List<String>, val thorough: Boolean)
    @Serializable private data class AddBody(val tracks: List<ApiTrack>)
    @Serializable private data class ListensBody(val listens: List<Listen>)
    @Serializable private data class StationThumbBody(val key: String, val thumb: String)
    @Serializable private data class ArtistRef(val id: String, val name: String)
    @Serializable private data class StationBody(val artist: ArtistRef?, val stationId: String?, val name: String?, val more: Boolean)
    @Serializable private data class ThumbBody(val thumb: String, val elapsedMs: Long)

    companion object {
        private val JSON = "application/json".toMediaType()

        /** What ExoPlayer plays everywhere, so the server only converts what's over the bitrate cap. */
        val CODECS = listOf("mp3", "aac", "opus", "vorbis", "flac", "wav")

        /**
         * Accepts "music.example.com", "http://host:8080/" and the like; with no scheme, HTTPS,
         * except for addresses that look like the home network's (an IP address, a name with no
         * dots, or one ending .local, .lan or .home.arpa), which are usually plain HTTP.
         */
        fun normalize(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val hasScheme = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
            val withScheme = when {
                hasScheme -> trimmed
                looksLocal(trimmed) -> "http://$trimmed"
                else -> "https://$trimmed"
            }
            return withScheme.toHttpUrlOrNull()?.let { withScheme }
        }

        private fun looksLocal(address: String): Boolean {
            val host = address.substringBefore('/').substringBefore(':')
            return host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || '.' !in host || host.endsWith(".local", true) ||
                host.endsWith(".lan", true) || host.endsWith(".home.arpa", true)
        }

        /**
         * Adds the sign-in to requests for the server (and nothing else): the same scheme, name and
         * port, so it's never sent unencrypted to a server reached over HTTPS.
         */
        fun authInterceptor(base: () -> String, token: () -> String?) = Interceptor { chain ->
            val request = chain.request()
            val server = base().toHttpUrlOrNull()
            val sameServer = server != null && request.url.scheme == server.scheme && request.url.host.equals(server.host, ignoreCase = true) &&
                request.url.port == server.port
            val tokenValue = token()
            if (sameServer && tokenValue != null && request.header("Authorization") == null) {
                chain.proceed(request.newBuilder().header("Authorization", "Bearer $tokenValue").build())
            } else {
                chain.proceed(request)
            }
        }
    }
}
