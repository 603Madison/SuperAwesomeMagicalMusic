package com.music.player.sources

import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.quality.StreamQuality

/** A resolved, directly fetchable audio stream. */
data class SourceStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** Human readable, e.g. "FLAC (original)" or "Opus 128 kbps". */
    val description: String,
    /** False when the source could not go as low as the requested cap (e.g. Qobuz below 320 kbps). */
    val withinCap: Boolean = true,
    /** File extension to use when saving the stream for offline listening. */
    val fileExtension: String,
    /** False for live transcodes, which must be fetched in a single request. */
    val supportsRangeRequests: Boolean = true,
    /** Nominal bitrate, used to compare copies across services; lossless counts as 1411+. */
    val kbps: Int? = null,
    /** The gain (dB) that brings this song to -18 LUFS, where the source knows it. */
    val gainDb: Float? = null,
)

interface MusicSource {
    val type: SourceType

    suspend fun isAvailable(): Boolean

    suspend fun search(query: String, limit: Int = 25): List<Track>

    suspend fun stream(sourceId: String, quality: StreamQuality): SourceStream

    /** Looks for [track] (which may come from another source) in this source's catalog. */
    suspend fun findMatch(track: Track): Track? {
        if (track.source == type) return track
        return TrackMatcher.bestMatch(track, search(TrackMatcher.searchQuery(track), limit = 15))
    }
}

class SourceUnavailableException(message: String) : Exception(message)
