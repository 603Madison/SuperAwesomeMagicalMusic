package com.music.player.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType(val label: String, val onDemand: Boolean = true) {
    JELLYFIN("Jellyfin"),
    QOBUZ("Qobuz"),
    YOUTUBE_MUSIC("YouTube Music"),

    /**
     * Live radio. A SiriusXM track is either a channel (`channel:<type>:<id>`, played live) or a
     * song heard on a channel (`song:<id>`), which plays from Jellyfin or a streaming service.
     */
    SIRIUSXM("SiriusXM", onDemand = false),

    /** Local and internet radio stations from the Radio Browser directory, played live. */
    RADIO("Radio", onDemand = false),
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

    val isSiriusXmChannel: Boolean get() = source == SourceType.SIRIUSXM && sourceId.startsWith(CHANNEL_PREFIX)

    /** A live stream (a SiriusXM channel or a radio station): no seeking, saving or downloading. */
    val isLive: Boolean get() = isSiriusXmChannel || isSiriusXmStationTrack || source == SourceType.RADIO

    /** A track of a Pandora artist station on SiriusXM; its stream link only lasts a while. */
    val isSiriusXmStationTrack: Boolean get() = source == SourceType.SIRIUSXM && sourceId.startsWith(STATION_TRACK_PREFIX)

    /**
     * A song an imported playlist named that wasn't found in any source: kept in its place (shown
     * greyed out, not played) and looked for again now and then.
     */
    val isMissing: Boolean get() = sourceId.startsWith(MISSING_PREFIX)

    companion object {
        const val CHANNEL_PREFIX = "channel:"
        const val STATION_TRACK_PREFIX = "stationtrack:"
        const val MISSING_PREFIX = "missing:"

        /** A stand-in for a song that wasn't found (its own id, so the same song can be in twice). */
        fun missing(title: String, artist: String, album: String? = null, durationMs: Long? = null, isrc: String? = null): Track = Track(
            source = SourceType.JELLYFIN,
            sourceId = MISSING_PREFIX + java.util.UUID.randomUUID().toString().replace("-", "").take(16),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            isrc = isrc,
        )
    }
}
