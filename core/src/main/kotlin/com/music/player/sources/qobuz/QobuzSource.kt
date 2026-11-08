package com.music.player.sources.qobuz

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
    /** The signed-in account, as currently configured. */
    private val config: suspend () -> QobuzConfig,
) : MusicSource {
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
        private const val BASE_URL = "https://www.qobuz.com/api.json/0.2/"

        private fun endpoint(path: String): HttpUrl = (BASE_URL + path).toHttpUrl()

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
private data class QImage(val small: String? = null, val large: String? = null)

@Serializable
private data class QFileUrlResponse(
    val url: String? = null,
    @SerialName("format_id") val formatId: Int? = null,
    val sample: Boolean = false,
)
