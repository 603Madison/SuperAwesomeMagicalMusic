package com.music.player.playback

import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.music.player.model.Track
import com.music.player.net.AppJson

private const val EXTRA_TRACK = "com.music.player.TRACK"

fun Track.toMediaItem(): MediaItem {
    val uri = TrackUri.encode(this)
    return MediaItem.Builder()
        .setMediaId(key)
        .setUri(uri)
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

/** Restores the playback URI on items received from a controller. */
fun MediaItem.withPlaybackUri(): MediaItem {
    if (localConfiguration != null) return this
    val uri = requestMetadata.mediaUri ?: toTrack()?.let(TrackUri::encode) ?: return this
    return buildUpon().setUri(uri).build()
}
