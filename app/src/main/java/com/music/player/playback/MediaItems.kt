package com.music.player.playback

import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.music.player.model.Track
import com.music.player.net.AppJson
import com.music.player.sources.radio.RadioStation

private const val EXTRA_TRACK = "com.music.player.TRACK"

fun Track.toMediaItem(): MediaItem {
    val uri = TrackUri.encode(this)
    return MediaItem.Builder()
        .setMediaId(key)
        .setUri(uri)
        // SiriusXM channels are live HLS streams; the type can't be sniffed from the URI.
        .setMimeType(if (isSiriusXmChannel || RadioStation.idOf(this)?.second == true) MimeTypes.APPLICATION_M3U8 else null)
        // Play radio at normal speed rather than speeding up or slowing down to hold a live offset.
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
        // The URI is dropped when items cross from a MediaController to the session; the
        // request metadata survives, and the session rebuilds the URI from it.
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUrl?.toUri())
                .setDurationMs(durationMs)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(if (isLive) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(bundleOf(EXTRA_TRACK to AppJson.encodeToString(Track.serializer(), this)))
                .build(),
        )
        .build()
}

fun MediaItem.toTrack(): Track? =
    mediaMetadata.extras?.getString(EXTRA_TRACK)
        ?.let { runCatching { AppJson.decodeFromString(Track.serializer(), it) }.getOrNull() }
        ?: requestMetadata.mediaUri?.let(TrackUri::decode)
        ?: localConfiguration?.uri?.let(TrackUri::decode)

/** Restores the playback URI (and stream type) on items received from a controller. */
fun MediaItem.withPlaybackUri(): MediaItem? {
    if (localConfiguration != null) return this
    return toTrack()?.toMediaItem()
}
