package com.music.player.sources.jellyfin

import android.os.Build
import com.music.player.BuildConfig
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.net.AppJson
import com.music.player.net.Http
import com.music.player.net.fetchString
import com.music.player.quality.StreamQuality
import com.music.player.settings.JellyfinConfig
import com.music.player.settings.SettingsRepository
import com.music.player.sources.MusicSource
import com.music.player.sources.SourceStream
import com.music.player.sources.SourceUnavailableException
import com.music.player.sources.TrackMatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class JellyfinSource(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) : MusicSource {
    override val type = SourceType.JELLYFIN

    override suspend fun isAvailable(): Boolean = settings.current().jellyfin.isConfigured

    /** Signs in with a username and password and returns the config to persist. */
    suspend fun authenticate(serverUrl: String, username: String, password: String): JellyfinConfig {
        val base = Http.normalizeBaseUrl(serverUrl)
        val deviceId = settings.ensureDeviceId()
        val request = Request.Builder()
            .url("$base/Users/AuthenticateByName")
            .header("Authorization", authorization(deviceId, token = null))
            .post(AppJson.encodeToString(JfAuthRequest(username, password)).toRequestBody(JSON))
            .build()
        val result = AppJson.decodeFromString<JfAuthResult>(http.fetchString(request))
        return JellyfinConfig(serverUrl = base, username = username, userId = result.user.id, accessToken = result.accessToken)
    }

    override suspend fun search(query: String, limit: Int): List<Track> {
        val (config, deviceId) = requireConfig()
        val url = itemsUrl(config)
            .addQueryParameter("searchTerm", query)
            .addQueryParameter("limit", limit.toString())
            .build()
        return fetchItems(url, config, deviceId).map { it.toTrack(config.serverUrl) }
    }

    override suspend fun findMatch(track: Track): Track? {
        if (track.source == type) return track
        // Jellyfin's search matches on the song title only, so search by title and filter by artist.
        val (config, deviceId) = requireConfig()
        val url = itemsUrl(config)
            .addQueryParameter("searchTerm", TrackMatcher.searchTitle(track.title))
            .addQueryParameter("limit", "50")
            .build()
        val candidates = fetchItems(url, config, deviceId).map { it.toTrack(config.serverUrl) }
        return TrackMatcher.bestMatch(track, candidates)
    }

    override suspend fun stream(sourceId: String, quality: StreamQuality): SourceStream {
        val (config, deviceId) = requireConfig()
        val url = itemsUrl(config).addQueryParameter("ids", sourceId).build()
        val item = fetchItems(url, config, deviceId).firstOrNull()
            ?: throw SourceUnavailableException("Song is no longer in the Jellyfin library")
        val mediaSource = item.mediaSources.firstOrNull()
        val codec = settings.current().jellyfinTranscodeCodec
        val headers = mapOf("Authorization" to authorization(deviceId, config.accessToken))

        return when (val plan = JellyfinStreamPlanner.plan(mediaSource, quality, codec)) {
            is JellyfinPlan.Direct -> SourceStream(
                url = "${config.serverUrl}/Audio/${item.id}/stream".toHttpUrl().newBuilder()
                    .addQueryParameter("static", "true")
                    .apply { mediaSource?.let { addQueryParameter("mediaSourceId", it.id) } }
                    .addQueryParameter("deviceId", deviceId)
                    .build().toString(),
                headers = headers,
                description = "${plan.codec?.uppercase() ?: "Original"} (original file)",
                fileExtension = JellyfinStreamPlanner.extensionForCodec(plan.codec),
            )
            is JellyfinPlan.Transcode -> SourceStream(
                url = "${config.serverUrl}/Audio/${item.id}/stream.${plan.container}".toHttpUrl().newBuilder()
                    .addQueryParameter("static", "false")
                    .addQueryParameter("audioCodec", plan.codec)
                    .addQueryParameter("maxAudioChannels", "2")
                    .apply {
                        mediaSource?.let { addQueryParameter("mediaSourceId", it.id) }
                        plan.kbps?.let {
                            addQueryParameter("audioBitRate", (it * 1000).toString())
                            // Constant bitrate keeps seeking accurate in a progressively transcoded stream.
                            addQueryParameter("enableAudioVbrEncoding", "false")
                        }
                    }
                    .addQueryParameter("deviceId", deviceId)
                    .build().toString(),
                headers = headers,
                description = plan.kbps?.let { "${plan.codec.uppercase()} $it kbps (transcoded)" }
                    ?: "${plan.codec.uppercase()} (lossless transcode)",
                fileExtension = plan.container,
                supportsRangeRequests = false,
            )
        }
    }

    private fun itemsUrl(config: JellyfinConfig): HttpUrl.Builder =
        "${config.serverUrl}/Items".toHttpUrl().newBuilder()
            .addQueryParameter("userId", config.userId)
            .addQueryParameter("includeItemTypes", "Audio")
            .addQueryParameter("recursive", "true")
            .addQueryParameter("fields", "MediaSources")
            .addQueryParameter("enableImageTypes", "Primary")

    private suspend fun fetchItems(url: HttpUrl, config: JellyfinConfig, deviceId: String): List<JfItem> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization(deviceId, config.accessToken))
            .build()
        return AppJson.decodeFromString<JfItemsResult>(http.fetchString(request)).items
    }

    private suspend fun requireConfig(): Pair<JellyfinConfig, String> {
        val config = settings.current().jellyfin
        if (!config.isConfigured) throw SourceUnavailableException("Jellyfin is not set up")
        return config to settings.ensureDeviceId()
    }

    private fun JfItem.toTrack(baseUrl: String): Track {
        val primaryTag = imageTags["Primary"]
        val artwork = when {
            primaryTag != null -> "$baseUrl/Items/$id/Images/Primary?maxHeight=512&tag=$primaryTag"
            albumId != null && albumPrimaryImageTag != null ->
                "$baseUrl/Items/$albumId/Images/Primary?maxHeight=512&tag=$albumPrimaryImageTag"
            else -> null
        }
        return Track(
            source = SourceType.JELLYFIN,
            sourceId = id,
            title = name ?: "Unknown title",
            artist = artists.joinToString(", ").ifBlank { albumArtist ?: "Unknown artist" },
            album = album,
            durationMs = runTimeTicks?.div(TICKS_PER_MS),
            artworkUrl = artwork,
        )
    }

    private fun authorization(deviceId: String, token: String?): String {
        val device = Build.MODEL.replace("\"", "")
        val tokenPart = token?.let { ", Token=\"$it\"" }.orEmpty()
        return "MediaBrowser Client=\"Music\", Device=\"$device\", DeviceId=\"$deviceId\", " +
            "Version=\"${BuildConfig.VERSION_NAME}\"$tokenPart"
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val TICKS_PER_MS = 10_000L
    }
}
