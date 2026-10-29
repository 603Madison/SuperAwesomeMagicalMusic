package com.music.player.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType(val label: String) {
    JELLYFIN("Jellyfin"),
    QOBUZ("Qobuz"),
    YOUTUBE_MUSIC("YouTube Music"),
}

/**
 * A song from any source. [sourceId] is the identifier inside that source (Jellyfin item id,
 * Qobuz track id or YouTube video id), so a track can always be fetched again later — for
 * playback, downloads, or after a playlist is restored from Nextcloud on another device.
 */
@Serializable
data class Track(
    val source: SourceType,
    val sourceId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val isrc: String? = null,
) {
    val key: String get() = "${source.name}:$sourceId"
}
