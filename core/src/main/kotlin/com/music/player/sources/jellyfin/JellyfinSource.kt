package com.music.player.sources.jellyfin

import com.music.player.AppInfo
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.net.AppJson
import com.music.player.net.HttpException
import com.music.player.net.Http
import com.music.player.net.fetchString
import com.music.player.quality.StreamQuality
import com.music.player.settings.JellyfinConfig
import com.music.player.settings.TranscodeCodec
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
    /** The signed-in server, as currently configured. */
    private val config: suspend () -> JellyfinConfig,
    /** This device's id for Jellyfin (created once and kept). */
    private val deviceId: suspend () -> String,
    /** The format Jellyfin transcodes to when a bitrate cap applies. */
    private val transcodeCodec: suspend () -> TranscodeCodec = { TranscodeCodec.MP3 },
) : MusicSource {
    override val type = SourceType.JELLYFIN

    override suspend fun isAvailable(): Boolean = config().isConfigured

    /** Signs in with a username and password and returns the config to persist. */
    suspend fun authenticate(serverUrl: String, username: String, password: String): JellyfinConfig {
        val base = Http.normalizeBaseUrl(serverUrl)
        val deviceId = deviceId()
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
        // Jellyfin matches titles literally: "fools gold" doesn't find "Fool's Gold", so also try
        // the apostrophe spellings of the query.
        return searchVariants(query).flatMap { term ->
            val url = itemsUrl(config)
                .addQueryParameter("searchTerm", term)
                .addQueryParameter("limit", limit.toString())
                .build()
            fetchItems(url, config, deviceId)
        }.distinctBy { it.id }.take(limit).map { it.toTrack(config.serverUrl) }
    }

    override suspend fun findMatch(track: Track): Track? {
        if (track.source == type) return track
        val (config, deviceId) = requireConfig()
        // Search by title and filter by artist…
        val byTitle = searchVariants(TrackMatcher.searchTitle(track.title)).flatMap { term ->
            val url = itemsUrl(config).addQueryParameter("searchTerm", term).addQueryParameter("limit", "50").build()
            fetchItems(url, config, deviceId)
        }.distinctBy { it.id }.map { it.toTrack(config.serverUrl) }
        TrackMatcher.bestMatch(track, byTitle)?.let { return it }
        // …then through the artist's songs, which survives titles spelled differently.
        return TrackMatcher.bestMatch(track, songsByArtist(config, deviceId, TrackMatcher.primaryArtist(track.artist)))
    }

    private suspend fun songsByArtist(config: JellyfinConfig, deviceId: String, artist: String): List<Track> {
        if (artist.isBlank()) return emptyList()
        val artistsUrl = "${config.serverUrl}/Artists".toHttpUrl().newBuilder()
            .addQueryParameter("userId", config.userId)
            .addQueryParameter("searchTerm", artist)
            .addQueryParameter("limit", "10")
            .build()
        val artistIds = fetchItems(artistsUrl, config, deviceId)
            .filter { it.name != null && TrackMatcher.isSameArtist(it.name, artist) }
            .map { it.id }
        if (artistIds.isEmpty()) return emptyList()
        val songsUrl = itemsUrl(config)
            .addQueryParameter("artistIds", artistIds.joinToString(","))
            .addQueryParameter("limit", "1000")
            .build()
        return fetchItems(songsUrl, config, deviceId).map { it.toTrack(config.serverUrl) }
    }

    override suspend fun stream(sourceId: String, quality: StreamQuality): SourceStream {
        val (config, deviceId) = requireConfig()
        val url = itemsUrl(config).addQueryParameter("ids", sourceId).build()
        val item = fetchItems(url, config, deviceId).firstOrNull()
            ?: throw SourceUnavailableException("Song is no longer in the Jellyfin library")
        val mediaSource = item.mediaSources.firstOrNull()
        val codec = transcodeCodec()
        val headers = mapOf("Authorization" to authorization(deviceId, config.accessToken))

        val sourceKbps = JellyfinStreamPlanner.kbpsOf(mediaSource)
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
                kbps = sourceKbps,
                gainDb = item.normalizationGain,
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
                kbps = plan.kbps ?: LOSSLESS_KBPS,
                gainDb = item.normalizationGain,
            )
        }
    }

    /**
     * The song's lyrics stored in Jellyfin (10.9+), as (start in ms or null, line) pairs; null
     * when it has none.
     */
    suspend fun lyrics(itemId: String): JfLyrics? {
        val (config, deviceId) = requireConfig()
        val url = "${config.serverUrl}/Audio".toHttpUrl().newBuilder().addPathSegment(itemId).addPathSegment("Lyrics").build()
        val request = Request.Builder().url(url).header("Authorization", authorization(deviceId, config.accessToken)).build()
        return try {
            AppJson.decodeFromString<JfLyrics>(http.fetchString(request)).takeIf { it.lyrics.isNotEmpty() }
        } catch (e: HttpException) {
            if (e.code == 404) null else throw e
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
        val config = config()
        if (!config.isConfigured) throw SourceUnavailableException("Jellyfin is not set up")
        return config to deviceId()
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
        val device = AppInfo.deviceName.replace("\"", "")
        val tokenPart = token?.let { ", Token=\"$it\"" }.orEmpty()
        return "MediaBrowser Client=\"${AppInfo.name}\", Device=\"$device\", DeviceId=\"$deviceId\", " +
            "Version=\"${AppInfo.version}\"$tokenPart"
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val LOSSLESS_KBPS = 1411
        private const val TICKS_PER_MS = 10_000L
        private val WORD_ENDING_IN_S = Regex("\\b(\\p{L}{2,})s\\b")

        /** The query, plus the same words with apostrophes before a final "s" ("fools" -> "fool's"). */
        fun searchVariants(query: String): List<String> {
            val trimmed = query.trim()
            if (trimmed.contains('\'') || trimmed.contains('’')) return listOf(trimmed)
            val withApostrophes = trimmed.replace(WORD_ENDING_IN_S) { "${it.groupValues[1]}'s" }
            return listOf(trimmed, withApostrophes).distinct()
        }
    }
}
