package com.music.player.sources

import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.quality.StreamQuality
import com.music.player.settings.SettingsRepository
import com.music.player.sources.jellyfin.JellyfinSource
import com.music.player.sources.qobuz.QobuzSource
import com.music.player.sources.youtube.YouTubeMusicSource
import kotlinx.coroutines.CancellationException

class Catalog(
    val jellyfin: JellyfinSource,
    val qobuz: QobuzSource,
    val youtube: YouTubeMusicSource,
    private val settings: SettingsRepository,
) {
    fun source(type: SourceType): MusicSource = when (type) {
        SourceType.JELLYFIN -> jellyfin
        SourceType.QOBUZ -> qobuz
        SourceType.YOUTUBE_MUSIC -> youtube
        SourceType.SIRIUSXM -> siriusXmSongs
        SourceType.RADIO -> radioStations
    }

    /** SiriusXM songs can't be fetched on demand; they play from the other services. */
    private val siriusXmSongs: MusicSource = LiveOnly(SourceType.SIRIUSXM, "SiriusXM songs aren't available on demand")

    /** Radio stations play live (see StreamResolver); there are no songs to fetch from them. */
    private val radioStations: MusicSource = LiveOnly(SourceType.RADIO, "Radio stations only play live")

    private class LiveOnly(override val type: SourceType, private val reason: String) : MusicSource {
        override suspend fun isAvailable() = false
        override suspend fun search(query: String, limit: Int): List<Track> = emptyList()
        override suspend fun stream(sourceId: String, quality: StreamQuality): SourceStream =
            throw SourceUnavailableException(reason)
    }

    /** Streaming services to use when Jellyfin lacks a song, in the user's preferred order. */
    suspend fun fallbackOrder(): List<SourceType> = settings.current().fallbackOrder

    /**
     * Another service's copy of [track] that has artwork, to fill in details SiriusXM didn't
     * give. Jellyfin is asked first unless [includeJellyfin] is false.
     */
    suspend fun findCopyWithArtwork(track: Track, includeJellyfin: Boolean = true): Track? {
        val sources = (if (includeJellyfin) listOf(jellyfin) else emptyList()) + fallbackOrder().map(::source)
        for (source in sources) {
            if (source.type == track.source || !source.isAvailable()) continue
            val match = try {
                source.findMatch(track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (match?.artworkUrl != null) return match
        }
        return null
    }
}
