package com.music.player.sources.qobuz

import com.music.player.browse.AlbumDetail
import com.music.player.browse.AlbumRef
import com.music.player.browse.ArtistDetail
import com.music.player.browse.ArtistRef
import com.music.player.browse.Browsable
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.net.AppJson
import com.music.player.net.fetchString
import com.music.player.quality.StreamQuality
import com.music.player.quality.TierSelector
import com.music.player.settings.QobuzConfig
import com.music.player.sources.MusicSource
import com.music.player.sources.SourceStream
import com.music.player.sources.SourceUnavailableException
import com.music.player.sources.TrackMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

/** Qobuz only offers these fixed qualities, so bitrate caps round down to the nearest one. */
enum class QobuzFormat(val formatId: Int, val nominalKbps: Int, val label: String, val extension: String) {
    MP3_320(5, 320, "MP3 320 kbps", "mp3"),
    FLAC_CD(6, 1_411, "FLAC 16-bit/44.1 kHz", "flac"),
    FLAC_HIRES_96(7, 4_608, "FLAC 24-bit/96 kHz", "flac"),
    FLAC_HIRES_192(27, 9_216, "FLAC 24-bit/192 kHz", "flac");

    companion object {
        fun choose(quality: StreamQuality) = TierSelector.choose(entries, quality) { it.nominalKbps }!!
        fun byId(id: Int?) = entries.firstOrNull { it.formatId == id }
    }
}

/**
 * Qobuz's API is only available to registered applications: the user supplies the app id and
 * secret they were issued, and signs in once to obtain a user auth token.
 */
class QobuzSource(
    private val http: OkHttpClient,
    private val baseUrl: String = BASE_URL,
    /** The signed-in account, as currently configured. */
    private val config: suspend () -> QobuzConfig,
) : MusicSource, Browsable {
    override val type = SourceType.QOBUZ

    override suspend fun isAvailable(): Boolean = config().isConfigured

    suspend fun login(appId: String, appSecret: String, username: String, password: String): QobuzConfig {
        val url = endpoint("user/login").newBuilder()
            .addQueryParameter(if ('@' in username) "email" else "username", username)
            .addQueryParameter("password", md5(password))
            .addQueryParameter("app_id", appId)
            .build()
        val response = AppJson.decodeFromString<QLoginResponse>(
            http.fetchString(Request.Builder().url(url).header("X-App-Id", appId).build()),
        )
        val token = response.userAuthToken ?: throw SourceUnavailableException("Qobuz did not return a session token")
        return QobuzConfig(appId = appId, appSecret = appSecret, username = username, userAuthToken = token)
    }

    /** Checks that a pasted user auth token works. */
    suspend fun verify(config: QobuzConfig) {
        searchWith(config, "music", 1)
    }

    override suspend fun search(query: String, limit: Int): List<Track> = searchWith(requireConfig(), query, limit)

    private suspend fun searchWith(config: QobuzConfig, query: String, limit: Int): List<Track> {
        val url = endpoint("track/search").newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("app_id", config.appId)
            .build()
        val result = AppJson.decodeFromString<QSearchResponse>(http.fetchString(authorized(url, config)))
        return result.tracks?.items.orEmpty().filter { it.streamable }.map { it.toTrack() }
    }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistRef> {
        val config = requireConfig()
        val url = endpoint("artist/search").newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("app_id", config.appId)
            .build()
        return AppJson.decodeFromString<QArtistSearch>(http.fetchString(authorized(url, config))).artists?.items.orEmpty().map { it.toRef() }
    }

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumRef> {
        val config = requireConfig()
        val url = endpoint("album/search").newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("app_id", config.appId)
            .build()
        return AppJson.decodeFromString<QAlbumSearch>(http.fetchString(authorized(url, config))).albums?.items.orEmpty()
            .filter { it.streamable }.map { it.toRef() }
    }

    override suspend fun album(ref: AlbumRef): AlbumDetail {
        val config = requireConfig()
        val url = endpoint("album/get").newBuilder()
            .addQueryParameter("album_id", ref.id)
            .addQueryParameter("app_id", config.appId)
            .build()
        val album = AppJson.decodeFromString<QFullAlbum>(http.fetchString(authorized(url, config)))
        val summary = album.toRef()
        // An album's own tracks don't repeat the album, so it's added to each.
        val inAlbum = QAlbum(album.title, album.artist, album.image)
        return AlbumDetail(summary, album.tracks?.items.orEmpty().filter { it.streamable }.map { it.copy(album = it.album ?: inAlbum).toTrack() })
    }

    override suspend fun artist(ref: ArtistRef): ArtistDetail = coroutineScope {
        val config = requireConfig()
        val url = endpoint("artist/get").newBuilder()
            .addQueryParameter("artist_id", ref.id)
            .addQueryParameter("extra", "albums")
            .addQueryParameter("limit", "100")
            .addQueryParameter("app_id", config.appId)
            .build()
        // Qobuz has no list of an artist's best-known songs, so its search for them stands in.
        val songs = async { searchWith(config, ref.name, 50).filter { TrackMatcher.isSameArtist(it.artist, ref.name) }.take(10) }
        val artist = AppJson.decodeFromString<QFullArtist>(http.fetchString(authorized(url, config)))
        val albums = artist.albums?.items.orEmpty().filter { it.streamable }.map { it.toRef() }
        ArtistDetail(ref.copy(name = artist.name ?: ref.name, artworkUrl = artist.picture() ?: ref.artworkUrl), songs.await(), albums)
    }

    /** A Qobuz playlist's name and songs (which needs a Qobuz account). */
    suspend fun playlist(id: String, max: Int = 2_000): Pair<String, List<Track>> {
        val config = requireConfig()
        val tracks = mutableListOf<Track>()
        var name = ""
        var offset = 0
        while (tracks.size < max) {
            val url = endpoint("playlist/get").newBuilder()
                .addQueryParameter("playlist_id", id)
                .addQueryParameter("extra", "tracks")
                .addQueryParameter("limit", PAGE.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("app_id", config.appId)
                .build()
            val page = AppJson.decodeFromString<QPlaylist>(http.fetchString(authorized(url, config)))
            name = page.name ?: name
            val items = page.tracks?.items.orEmpty()
            tracks += items.filter { it.streamable }.map { it.toTrack() }
            offset += items.size
            if (items.isEmpty() || offset >= (page.tracks?.total ?: 0)) break
        }
        return name to tracks.take(max)
    }

    override suspend fun stream(sourceId: String, quality: StreamQuality): SourceStream {
        val config = requireConfig()
        val choice = QobuzFormat.choose(quality)
        val requested = choice.tier
        val file = fileUrl(config, sourceId, requested.formatId)
        if (file.url.isNullOrBlank()) {
            throw SourceUnavailableException("Qobuz has no stream for this song")
        }
        if (file.sample) {
            throw SourceUnavailableException("Qobuz only offers a preview of this song on your account")
        }
        // Qobuz returns the best quality the account and release allow, up to what was requested.
        val delivered = QobuzFormat.byId(file.formatId) ?: requested
        return SourceStream(
            url = file.url,
            description = delivered.label,
            withinCap = choice.withinCap,
            fileExtension = delivered.extension,
            kbps = delivered.nominalKbps,
            codec = delivered.extension,
        )
    }

    private suspend fun fileUrl(config: QobuzConfig, trackId: String, formatId: Int): QFileUrlResponse {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val url = endpoint("track/getFileUrl").newBuilder()
            .addQueryParameter("request_ts", timestamp)
            .addQueryParameter("request_sig", signature(trackId, formatId, timestamp, config.appSecret))
            .addQueryParameter("track_id", trackId)
            .addQueryParameter("format_id", formatId.toString())
            .addQueryParameter("intent", "stream")
            .addQueryParameter("app_id", config.appId)
            .build()
        return AppJson.decodeFromString(http.fetchString(authorized(url, config)))
    }

    private fun endpoint(path: String): HttpUrl = (baseUrl + path).toHttpUrl()

    private fun authorized(url: HttpUrl, config: QobuzConfig) = Request.Builder()
        .url(url)
        .header("X-App-Id", config.appId)
        .header("X-User-Auth-Token", config.userAuthToken)
        .build()

    private suspend fun requireConfig(): QobuzConfig {
        val config = config()
        if (!config.isConfigured) throw SourceUnavailableException("Qobuz is not set up")
        return config
    }

    private fun QAlbumItem.toRef() = AlbumRef(
        source = SourceType.QOBUZ,
        id = id,
        title = if (version.isNullOrBlank()) title else "$title ($version)",
        artist = artist?.name ?: "Unknown artist",
        year = releaseDate?.take(4)?.toIntOrNull(),
        artworkUrl = image?.large ?: image?.small,
        trackCount = tracksCount,
    )

    private fun QFullAlbum.toRef() = QAlbumItem(id, title.orEmpty(), version, artist, image, releaseDate, tracksCount).toRef()

    private fun QArtistItem.toRef() = ArtistRef(SourceType.QOBUZ, id.toString(), name.orEmpty(), picture())

    private fun QTrack.toTrack(): Track = Track(
        source = SourceType.QOBUZ,
        sourceId = id.toString(),
        title = if (version.isNullOrBlank()) title else "$title ($version)",
        artist = performer?.name ?: album?.artist?.name ?: "Unknown artist",
        album = album?.title,
        durationMs = duration?.let { it * 1000L },
        artworkUrl = album?.image?.large ?: album?.image?.small,
        isrc = isrc,
    )

    companion object {
        const val BASE_URL = "https://www.qobuz.com/api.json/0.2/"

        private const val PAGE = 500

        /** request_sig for track/getFileUrl, as computed by Qobuz's own players. */
        fun signature(trackId: String, formatId: Int, timestamp: String, appSecret: String): String =
            md5("trackgetFileUrlformat_id${formatId}intentstreamtrack_id$trackId$timestamp$appSecret")

        fun md5(value: String): String =
            MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
private data class QLoginResponse(@SerialName("user_auth_token") val userAuthToken: String? = null)

@Serializable
private data class QSearchResponse(val tracks: QPage? = null)

@Serializable
private data class QPage(val items: List<QTrack> = emptyList())

@Serializable
private data class QTrack(
    val id: Long,
    val title: String,
    val version: String? = null,
    val duration: Int? = null,
    val isrc: String? = null,
    val performer: QArtist? = null,
    val album: QAlbum? = null,
    val streamable: Boolean = true,
)

@Serializable
private data class QArtist(val name: String? = null)

@Serializable
private data class QAlbum(val title: String? = null, val artist: QArtist? = null, val image: QImage? = null)

@Serializable
private data class QImage(
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
    val extralarge: String? = null,
)

@Serializable
private data class QArtistSearch(val artists: QArtistPage? = null)

@Serializable
private data class QArtistPage(val items: List<QArtistItem> = emptyList())

@Serializable
private data class QArtistItem(val id: Long, val name: String? = null, val image: QImage? = null, val picture: String? = null) {
    fun picture(): String? = image?.large ?: image?.extralarge ?: image?.medium ?: picture
}

@Serializable
private data class QFullArtist(
    val name: String? = null,
    val image: QImage? = null,
    val picture: String? = null,
    val albums: QAlbumPage? = null,
) {
    fun picture(): String? = image?.large ?: image?.extralarge ?: image?.medium ?: picture
}

@Serializable
private data class QAlbumSearch(val albums: QAlbumPage? = null)

@Serializable
private data class QAlbumPage(val items: List<QAlbumItem> = emptyList())

@Serializable
private data class QAlbumItem(
    val id: String,
    val title: String,
    val version: String? = null,
    val artist: QArtist? = null,
    val image: QImage? = null,
    @SerialName("release_date_original") val releaseDate: String? = null,
    @SerialName("tracks_count") val tracksCount: Int? = null,
    val streamable: Boolean = true,
)

@Serializable
private data class QFullAlbum(
    val id: String,
    val title: String? = null,
    val version: String? = null,
    val artist: QArtist? = null,
    val image: QImage? = null,
    @SerialName("release_date_original") val releaseDate: String? = null,
    @SerialName("tracks_count") val tracksCount: Int? = null,
    val tracks: QTrackPage? = null,
)

@Serializable
private data class QTrackPage(val items: List<QTrack> = emptyList(), val total: Int? = null)

@Serializable
private data class QPlaylist(val name: String? = null, val tracks: QTrackPage? = null)

@Serializable
private data class QFileUrlResponse(
    val url: String? = null,
    @SerialName("format_id") val formatId: Int? = null,
    val sample: Boolean = false,
)
