package com.music.player.sources.youtube

import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.quality.StreamQuality
import com.music.player.quality.TierSelector
import com.music.player.settings.SettingsRepository
import com.music.player.sources.MusicSource
import com.music.player.sources.SourceStream
import com.music.player.sources.SourceUnavailableException
import com.music.player.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale

/**
 * YouTube Music via NewPipeExtractor. YouTube offers a handful of fixed audio-only formats
 * (roughly 48/50/70/128/160 kbps), so bitrate caps round down to the closest one.
 */
class YouTubeMusicSource(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) : MusicSource {
    override val type = SourceType.YOUTUBE_MUSIC

    private val service = ServiceList.YouTube

    @Volatile
    private var initialized = false

    override suspend fun isAvailable(): Boolean = settings.current().youTubeMusicEnabled

    override suspend fun search(query: String, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val handler = service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toTrack() }
            .take(limit)
    }

    override suspend fun stream(sourceId: String, quality: StreamQuality): SourceStream = withContext(Dispatchers.IO) {
        ensureInitialized()
        val info = StreamInfo.getInfo(service, watchUrl(sourceId))
        val candidates = info.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.kbps() > 0
        }
        // Prefer Opus over AAC when both sit at the chosen bitrate: it sounds better per bit.
        val choice = TierSelector.choose(candidates.sortedBy { if (it.format.isOpus()) 1 else 0 }, quality) { it.kbps() }
            ?: throw SourceUnavailableException("YouTube Music has no playable audio for this song")
        val stream = choice.tier
        val format = stream.format
        SourceStream(
            url = stream.content,
            headers = playbackHeaders(stream.content),
            description = "${if (format.isOpus()) "Opus" else "AAC"} ${stream.kbps()} kbps",
            withinCap = choice.withinCap,
            fileExtension = format?.suffix ?: "m4a",
            kbps = stream.kbps(),
        )
    }

    /** The songs of a YouTube or YouTube Music playlist, and its name. */
    suspend fun playlist(url: String, max: Int = 1_000): Pair<String, List<Track>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val info = PlaylistInfo.getInfo(service, url)
        val items = info.relatedItems.toMutableList()
        var page = info.nextPage
        while (page != null && items.size < max) {
            val more = PlaylistInfo.getMoreItems(service, url, page)
            items += more.items
            page = more.nextPage
        }
        info.name.orEmpty() to items.take(max).mapNotNull { it.toTrack() }
    }

    override suspend fun findMatch(track: Track): Track? {
        if (track.source == type) return track
        return TrackMatcher.bestMatch(track, search(TrackMatcher.searchQuery(track), limit = 15))
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val locale = Locale.getDefault()
            NewPipe.init(
                NewPipeDownloader(http),
                Localization.fromLocale(locale),
                ContentCountry(locale.country.ifBlank { "US" }),
            )
            initialized = true
        }
    }

    private fun StreamInfoItem.toTrack(): Track? {
        val id = videoId(url) ?: return null
        val artwork = thumbnails.maxByOrNull { it.width.coerceAtLeast(0) * it.height.coerceAtLeast(0) }?.url
        return Track(
            source = SourceType.YOUTUBE_MUSIC,
            sourceId = id,
            title = name,
            artist = TrackMatcher.cleanArtist(uploaderName.orEmpty()).ifBlank { "Unknown artist" },
            durationMs = duration.takeIf { it > 0 }?.times(1000),
            artworkUrl = artwork,
        )
    }

    /** Streams must be fetched with the same kind of client YouTube issued them to. */
    private fun playbackHeaders(url: String): Map<String, String> {
        val localization = Localization.fromLocale(Locale.getDefault())
        return when {
            YoutubeParsingHelper.isAndroidStreamingUrl(url) ->
                mapOf("User-Agent" to YoutubeParsingHelper.getAndroidUserAgent(localization))
            YoutubeParsingHelper.isIosStreamingUrl(url) ->
                mapOf("User-Agent" to YoutubeParsingHelper.getIosUserAgent(localization))
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
                mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(localization))
            else -> mapOf(
                "User-Agent" to NewPipeDownloader.BROWSER_USER_AGENT,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/",
            )
        }
    }

    private fun AudioStream.kbps(): Int = when {
        averageBitrate > 0 -> averageBitrate
        bitrate > 0 -> bitrate / 1000
        else -> 0
    }

    private fun MediaFormat?.isOpus() = this == MediaFormat.WEBMA_OPUS || this == MediaFormat.OPUS

    companion object {
        private val VIDEO_ID = Regex("[?&]v=([A-Za-z0-9_-]{11})")

        fun videoId(url: String): String? = VIDEO_ID.find(url)?.groupValues?.get(1)

        fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"
    }
}
