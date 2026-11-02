package com.music.player.sources.siriusxm

import com.music.player.model.SourceType
import com.music.player.model.Track
import kotlinx.serialization.Serializable

data class SxmChannel(
    val id: String,
    val type: String,
    val name: String,
    val number: Int?,
    val category: String,
    val logoUrl: String?,
    /** Not included in the user's subscription. */
    val locked: Boolean,
) {
    /** Channels travel through the player as tracks: `channel:<type>:<id>`. */
    fun toTrack(): Track = Track(
        source = SourceType.SIRIUSXM,
        sourceId = "${Track.CHANNEL_PREFIX}$type:$id",
        title = name,
        artist = number?.let { "SiriusXM · Ch $it" } ?: "SiriusXM",
        album = category.ifBlank { null },
        artworkUrl = logoUrl,
    )

    companion object {
        /** (type, id) of a channel track, or null for anything else. */
        fun idOf(track: Track): Pair<String, String>? {
            if (!track.isSiriusXmChannel) return null
            val parts = track.sourceId.removePrefix(Track.CHANNEL_PREFIX).split(":", limit = 2)
            return if (parts.size == 2) parts[0] to parts[1] else null
        }
    }
}

/**
 * An artist on SiriusXM ([entityType] "talent" in its catalogue); [stationId] is set when
 * SiriusXM named the artist's Pandora station.
 */
data class SxmArtist(
    val id: String,
    val name: String,
    val artworkUrl: String?,
    val stationId: String? = null,
    val entityType: String = "talent",
)

/**
 * A Pandora artist station on SiriusXM: music by and like one artist, handed out a track at a
 * time like an Xtra channel. It travels through the player as a channel of type
 * [SiriusXmClient.ARTIST_STATION].
 */
@Serializable
data class SxmStation(val id: String, val name: String, val artworkUrl: String? = null) {
    fun toTrack(): Track = Track(
        source = SourceType.SIRIUSXM,
        sourceId = "${Track.CHANNEL_PREFIX}${SiriusXmClient.ARTIST_STATION}:$id",
        title = name,
        artist = "Pandora station · SiriusXM",
        album = "Pandora artist stations",
        artworkUrl = artworkUrl,
    )
}

/** One item ("cut") in a channel's schedule: usually a song, sometimes talk or an ad break. */
data class SxmCut(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val startMs: Long,
    val durationMs: Long,
    val artworkUrl: String?,
    val interstitial: Boolean,
    /** SiriusXM's id for the artist, which a Pandora artist station can be started from. */
    val artistId: String? = null,
) {
    val endMs: Long get() = startMs + durationMs

    val isSong: Boolean get() = !interstitial && title.isNotBlank() && artist.isNotBlank()

    /** The song as a playlist entry; it plays from Jellyfin or a streaming service. */
    fun toTrack(): Track = Track(
        source = SourceType.SIRIUSXM,
        sourceId = "song:$id",
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs.takeIf { it > 0 },
        artworkUrl = artworkUrl,
    )
}

/** What's playing on the SiriusXM channel the user is listening to. */
data class SxmNowPlaying(val channel: Track, val cut: SxmCut?)

/** SiriusXM's device grant and session tokens; persisted so the app doesn't sign in every launch. */
@Serializable
data class SxmSession(
    val deviceId: String? = null,
    val deviceGrant: String? = null,
    val anonToken: String? = null,
    val anonExpiresMs: Long = 0,
    val identityGrant: String? = null,
    val accessToken: String? = null,
    val accessExpiresMs: Long = 0,
)
