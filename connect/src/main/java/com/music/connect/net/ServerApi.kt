package com.music.connect.net

import com.music.player.model.SourceType
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

// The server's API (server/src/main/kotlin), as the app uses it.

@Serializable
data class ApiTrack(
    val source: SourceType,
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
    /** Identifies the song (and the source it came from) everywhere in the app. */
    val key: String get() = "${via.orEmpty()}|${source.name}:$sourceId"

    val isChannel: Boolean get() = source == SourceType.SIRIUSXM && sourceId.startsWith("channel:")
    val isStationTrack: Boolean get() = source == SourceType.SIRIUSXM && sourceId.startsWith("stationtrack:")
    val isRadio: Boolean get() = source == SourceType.RADIO

    /** Live: no end and no seeking (SiriusXM channels, radio stations). */
    val isLive: Boolean get() = isChannel || isRadio

    /** Can be downloaded and kept in playlists (live radio and station tracks can't). */
    val isSong: Boolean get() = !isLive && !isStationTrack
}

@Serializable
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val oidc: Boolean = false,
    val hasPassword: Boolean = false,
)

@Serializable
data class OidcButton(val label: String)

@Serializable
data class AuthConfig(val serverName: String, val version: String, val localLogin: Boolean, val oidc: OidcButton? = null)

@Serializable
data class SignedIn(val user: User, val token: String? = null)

@Serializable
data class Me(val user: User)

@Serializable
data class SearchGroup(val sourceId: String, val sourceName: String, val kind: String, val tracks: List<ApiTrack>, val error: String? = null)

/** An artist, wherever they were found; [id] opens their page. */
@Serializable
data class ArtistSummary(val id: String, val name: String, val artworkUrl: String? = null)

/** An album, wherever it was found; [id] opens it, [artistId] its artist. */
@Serializable
data class AlbumSummary(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String,
    val year: Int? = null,
    val artworkUrl: String? = null,
    val trackCount: Int? = null,
)

/** The likeliest match: an artist, an album or a song, as [kind] says. */
@Serializable
data class TopResult(val kind: String, val artist: ArtistSummary? = null, val album: AlbumSummary? = null, val track: ApiTrack? = null)

/** One set of results across all the person's sources ([groups] is each source's songs, from older servers). */
@Serializable
data class SearchResponse(
    val query: String,
    val groups: List<SearchGroup> = emptyList(),
    val top: TopResult? = null,
    val songs: List<ApiTrack> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
) {
    /** The songs found, from any server version. */
    val allSongs: List<ApiTrack> get() = songs.ifEmpty { groups.sortedByDescending { it.kind == "JELLYFIN" }.flatMap { it.tracks } }
}

@Serializable
data class ArtistPage(val artist: ArtistSummary, val topSongs: List<ApiTrack>, val albums: List<AlbumSummary> = emptyList(), val singles: List<AlbumSummary> = emptyList())

@Serializable
data class AlbumPage(val album: AlbumSummary, val tracks: List<ApiTrack>)

@Serializable
data class ImportResult(val playlist: Playlist, val found: Int, val missing: List<String> = emptyList(), val note: String? = null)

@Serializable
data class ResolvedStream(
    val track: ApiTrack,
    val streamUrl: String,
    val description: String,
    val kbps: Int? = null,
    val withinCap: Boolean = true,
    val gainDb: Float? = null,
    val seekable: Boolean = true,
    val transcoded: Boolean = false,
    val extension: String = "",
)

@Serializable
data class PlaylistSummary(
    val id: String,
    val name: String,
    val description: String = "",
    val count: Int = 0,
    val durationMs: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val revision: Long = 0,
    val artwork: List<String> = emptyList(),
)

@Serializable
data class PlaylistEntry(val entryId: String, val track: ApiTrack, val addedAt: Long = 0)

@Serializable
data class Playlist(val summary: PlaylistSummary, val entries: List<PlaylistEntry>)

@Serializable
data class Listen(val track: ApiTrack, val playedAt: Long, val listenedMs: Long = 0, val client: String = "android")

@Serializable
data class SourceView(val id: String, val kind: String, val name: String, val scope: String, val enabled: Boolean, val hidden: Boolean = false)

@Serializable
data class ChannelView(val track: ApiTrack, val number: Int? = null, val category: String = "", val locked: Boolean = false)

@Serializable
data class NowView(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val startMs: Long = 0,
    val durationMs: Long = 0,
    val song: ApiTrack? = null,
)

@Serializable
data class ArtistView(val id: String, val name: String, val artworkUrl: String? = null, val stationId: String? = null, val entityType: String = "talent")

@Serializable
data class StationTracks(val station: ApiTrack, val tracks: List<ApiTrack>)

@Serializable
data class RadioNow(val title: String? = null)

@Serializable
data class LyricLine(val timeMs: Long? = null, val text: String)

@Serializable
data class LyricsView(val lines: List<LyricLine> = emptyList(), val source: String = "", val synced: Boolean = false)

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
 * Calls to the person's server. [base] is the server's address and [token] the app's sign-in;
 * both are read at each call, so signing in or out takes effect at once.
 */
class ServerApi(
    private val http: OkHttpClient,
    private val base: () -> String,
    private val token: () -> String?,
) {
    suspend fun authConfig(server: String = base()): AuthConfig = get("$server/api/auth/config", AuthConfig.serializer(), auth = false)

    suspend fun login(server: String, username: String, password: String, deviceName: String): SignedIn =
        post("$server/api/auth/login", ApiJson.encodeToString(LoginBody.serializer(), LoginBody(username, password, true, deviceName)), SignedIn.serializer(), auth = false)

    suspend fun exchangeCode(server: String, code: String, verifier: String, deviceName: String): SignedIn =
        post("$server/api/auth/app/token", ApiJson.encodeToString(TokenBody.serializer(), TokenBody(code, verifier, deviceName)), SignedIn.serializer(), auth = false)

    suspend fun logout() = runCatching { post("${base()}/api/auth/logout", "{}", Unit.serializer()) }

    suspend fun me(): Me = get("${base()}/api/me", Me.serializer())

    suspend fun search(query: String): SearchResponse =
        get("${base()}/api/search?q=${enc(query)}&limit=25", SearchResponse.serializer())

    /** An artist's page: by a search's [id], or by [name]. */
    suspend fun artist(id: String?, name: String? = null): ArtistPage =
        get("${base()}/api/artist?" + if (id != null) "id=${enc(id)}" else "name=${enc(name.orEmpty())}", ArtistPage.serializer())

    /** An album: by a search's [id], or by [title] and [artist]. */
    suspend fun album(id: String?, title: String? = null, artist: String? = null): AlbumPage =
        get("${base()}/api/album?" + if (id != null) "id=${enc(id)}" else "title=${enc(title.orEmpty())}&artist=${enc(artist.orEmpty())}", AlbumPage.serializer())

    /** Brings in a playlist from a link, or from a typed [text] list; finding every song takes a while. */
    suspend fun importPlaylist(url: String?, text: String?, name: String?): ImportResult = withContext(Dispatchers.IO) {
        val body = ApiJson.encodeToString(ImportBody.serializer(), ImportBody(url.orEmpty(), text.orEmpty(), name.orEmpty()))
        val slow = http.newBuilder().readTimeout(10, java.util.concurrent.TimeUnit.MINUTES).callTimeout(10, java.util.concurrent.TimeUnit.MINUTES).build()
        slow.newCall(request("POST", "${base()}/api/playlists/import", body, true)).execute().use { parse(it, ImportResult.serializer()) }
    }

    suspend fun resolve(track: ApiTrack, kbps: Int, thorough: Boolean): ResolvedStream =
        post(
            "${base()}/api/play/resolve",
            ApiJson.encodeToString(ResolveBody.serializer(), ResolveBody(track, kbps, CODECS, thorough)),
            ResolvedStream.serializer(),
        )

    suspend fun lyrics(track: ApiTrack): LyricsView =
        post("${base()}/api/lyrics", ApiJson.encodeToString(LyricsBody.serializer(), LyricsBody(track)), LyricsView.serializer())

    suspend fun playlists(): List<PlaylistSummary> = get("${base()}/api/playlists", ListSerializer(PlaylistSummary.serializer()))

    suspend fun playlist(id: String): Playlist = get("${base()}/api/playlists/${enc(id)}", Playlist.serializer())

    suspend fun createPlaylist(id: String, name: String, tracks: List<ApiTrack>): Playlist =
        post("${base()}/api/playlists", ApiJson.encodeToString(NewPlaylistBody.serializer(), NewPlaylistBody(name, tracks, id)), Playlist.serializer())

    suspend fun renamePlaylist(id: String, name: String) =
        send("PATCH", "${base()}/api/playlists/${enc(id)}", ApiJson.encodeToString(RenameBody.serializer(), RenameBody(name)), PlaylistSummary.serializer())

    suspend fun deletePlaylist(id: String) = send("DELETE", "${base()}/api/playlists/${enc(id)}", null, Unit.serializer())

    suspend fun addToPlaylist(id: String, tracks: List<ApiTrack>): Playlist =
        post("${base()}/api/playlists/${enc(id)}/tracks", ApiJson.encodeToString(AddBody.serializer(), AddBody(tracks)), Playlist.serializer())

    suspend fun removeFromPlaylist(id: String, entryIds: List<String>): Playlist =
        post("${base()}/api/playlists/${enc(id)}/remove", ApiJson.encodeToString(RemoveBody.serializer(), RemoveBody(entryIds)), Playlist.serializer())

    suspend fun reorderPlaylist(id: String, revision: Long, entryIds: List<String>): Playlist =
        send("PUT", "${base()}/api/playlists/${enc(id)}/order", ApiJson.encodeToString(OrderBody.serializer(), OrderBody(revision, entryIds)), Playlist.serializer())

    suspend fun addListens(listens: List<Listen>) =
        post("${base()}/api/history", ApiJson.encodeToString(ListensBody.serializer(), ListensBody(listens)), Unit.serializer())

    suspend fun history(): List<Listen> = get("${base()}/api/history?limit=200", ListSerializer(Listen.serializer()))

    suspend fun sources(): List<SourceView> = get("${base()}/api/sources", ListSerializer(SourceView.serializer()))

    suspend fun channels(src: String): List<ChannelView> = get("${base()}/api/live/sxm/${enc(src)}/channels", ListSerializer(ChannelView.serializer()))

    suspend fun channelNow(src: String, type: String, id: String, atMs: Long): NowView? =
        getOrNull("${base()}/api/live/sxm/${enc(src)}/now?type=${enc(type)}&id=${enc(id)}&at=$atMs", NowView.serializer())

    suspend fun artists(src: String, query: String): List<ArtistView> =
        get("${base()}/api/live/sxm/${enc(src)}/artists?q=${enc(query)}", ListSerializer(ArtistView.serializer()))

    suspend fun station(src: String, artist: ArtistView?, stationId: String?, name: String?, more: Boolean): StationTracks =
        post(
            "${base()}/api/live/sxm/${enc(src)}/station",
            ApiJson.encodeToString(StationBody.serializer(), StationBody(artist, stationId, name, more)),
            StationTracks.serializer(),
        )

    suspend fun stationProgress(src: String, stationId: String, trackId: String, elapsedMs: Long, start: Boolean) = post(
        "${base()}/api/live/sxm/${enc(src)}/station-track/${enc(stationId)}/${enc(trackId)}/progress",
        ApiJson.encodeToString(ProgressBody.serializer(), ProgressBody(elapsedMs, start)),
        Unit.serializer(),
    )

    suspend fun stationThumb(src: String, stationId: String, trackId: String, thumb: String, elapsedMs: Long) = post(
        "${base()}/api/live/sxm/${enc(src)}/station-track/${enc(stationId)}/${enc(trackId)}/thumb",
        ApiJson.encodeToString(ThumbBody.serializer(), ThumbBody(thumb, elapsedMs)),
        Unit.serializer(),
    )

    suspend fun radioSearch(src: String, query: String, country: String?): List<ApiTrack> =
        get("${base()}/api/live/radio/${enc(src)}/search?q=${enc(query)}${country?.let { "&country=${enc(it)}" }.orEmpty()}", ListSerializer(ApiTrack.serializer()))

    suspend fun radioNearby(src: String, lat: Double, lon: Double): List<ApiTrack> =
        get("${base()}/api/live/radio/${enc(src)}/nearby?lat=$lat&lon=$lon", ListSerializer(ApiTrack.serializer()))

    suspend fun radioNow(src: String, id: String): RadioNow = get("${base()}/api/live/radio/${enc(src)}/station/${enc(id)}/now", RadioNow.serializer())

    /** The full address of a server path (artwork and stream paths come back relative). */
    fun absolute(path: String): String = if (path.startsWith("http://") || path.startsWith("https://")) path else base() + path

    // ---- plumbing ----

    private suspend fun <T> get(url: String, serializer: KSerializer<T>, auth: Boolean = true): T = send("GET", url, null, serializer, auth)

    private suspend fun <T> getOrNull(url: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        http.newCall(request("GET", url, null, true)).execute().use { response ->
            if (response.code == 204) return@withContext null
            parse(response, serializer)
        }
    }

    private suspend fun <T> post(url: String, body: String, serializer: KSerializer<T>, auth: Boolean = true): T = send("POST", url, body, serializer, auth)

    private suspend fun <T> send(method: String, url: String, body: String?, serializer: KSerializer<T>, auth: Boolean = true): T =
        withContext(Dispatchers.IO) {
            http.newCall(request(method, url, body, auth)).execute().use { parse(it, serializer) }
        }

    private fun request(method: String, url: String, body: String?, auth: Boolean): Request {
        val requestBody = body?.toRequestBody(JSON) ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(JSON)
        return Request.Builder().url(url).method(method, requestBody).apply {
            if (auth) token()?.let { header("Authorization", "Bearer $it") }
        }.build()
    }

    private fun <T> parse(response: okhttp3.Response, serializer: KSerializer<T>): T {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = runCatching { ApiJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
            throw ServerException(response.code, message ?: "The server answered HTTP ${response.code}")
        }
        @Suppress("UNCHECKED_CAST")
        if (serializer == Unit.serializer()) return Unit as T
        return ApiJson.decodeFromString(serializer, text)
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    @Serializable private data class LoginBody(val username: String, val password: String, val app: Boolean, val deviceName: String)
    @Serializable private data class TokenBody(val code: String, val verifier: String, val deviceName: String)
    @Serializable private data class ResolveBody(val track: ApiTrack, val kbps: Int, val codecs: List<String>, val thorough: Boolean)
    @Serializable private data class LyricsBody(val track: ApiTrack)
    @Serializable private data class NewPlaylistBody(val name: String, val tracks: List<ApiTrack>, val id: String)
    @Serializable private data class RenameBody(val name: String)
    @Serializable private data class ImportBody(val url: String, val text: String, val name: String)
    @Serializable private data class AddBody(val tracks: List<ApiTrack>)
    @Serializable private data class RemoveBody(val entryIds: List<String>)
    @Serializable private data class OrderBody(val revision: Long, val entryIds: List<String>)
    @Serializable private data class ListensBody(val listens: List<Listen>)
    @Serializable private data class StationBody(val artist: ArtistView?, val stationId: String?, val name: String?, val more: Boolean)
    @Serializable private data class ProgressBody(val elapsedMs: Long, val start: Boolean)
    @Serializable private data class ThumbBody(val thumb: String, val elapsedMs: Long)

    companion object {
        private val JSON = "application/json".toMediaType()

        /** What ExoPlayer plays, so the server only converts what's over the bitrate cap. */
        val CODECS = listOf("flac", "alac", "mp3", "aac", "opus", "vorbis", "wav")

        /** Accepts "music.example.com", "http://host:8080/" and the like. */
        fun normalize(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
            return withScheme.toHttpUrlOrNull()?.let { withScheme }
        }

        /** Adds the app's sign-in to requests for the server (and nothing else). */
        fun authInterceptor(base: () -> String, token: () -> String?) = Interceptor { chain ->
            val request = chain.request()
            val server = base().toHttpUrlOrNull()
            val sameServer = server != null && request.url.host == server.host && request.url.port == server.port
            val tokenValue = token()
            if (sameServer && tokenValue != null && request.header("Authorization") == null) {
                chain.proceed(request.newBuilder().header("Authorization", "Bearer $tokenValue").build())
            } else {
                chain.proceed(request)
            }
        }
    }
}
