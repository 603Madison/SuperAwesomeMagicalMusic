package com.music.connect.playback

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.music.connect.net.ApiJson
import com.music.connect.net.ApiTrack

/**
 * Songs travel through the player as media items whose id holds the whole song ("t:" and the
 * song as JSON), so Android Auto, the notification and resuming after a restart can rebuild
 * them. A song's stream is only chosen when it starts (see [StreamResolution]): items carry a
 * `samm://song` address until then. Live radio's address is the server's, as it is.
 */
object MediaItems {
    const val SCHEME = "samm"
    private const val TRACK_PREFIX = "t:"
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun id(track: ApiTrack): String =
        TRACK_PREFIX + Base64.encodeToString(ApiJson.encodeToString(ApiTrack.serializer(), track).toByteArray(), FLAGS)

    fun track(mediaId: String): ApiTrack? {
        if (!mediaId.startsWith(TRACK_PREFIX)) return null
        return runCatching {
            ApiJson.decodeFromString(ApiTrack.serializer(), String(Base64.decode(mediaId.removePrefix(TRACK_PREFIX), FLAGS)))
        }.getOrNull()
    }

    fun track(item: MediaItem): ApiTrack? = track(item.mediaId)

    /** An item for the queue, with what's shown about it (the UI's version; the service fills in the address). */
    fun of(track: ApiTrack, artwork: (String?) -> Uri?): MediaItem = MediaItem.Builder()
        .setMediaId(id(track))
        .setMediaMetadata(metadata(track, artwork))
        .build()

    fun metadata(track: ApiTrack, artwork: (String?) -> Uri?, extras: Bundle? = null): MediaMetadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .setAlbumTitle(track.album)
        .setArtworkUri(artwork(track.artworkUrl))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(if (track.isLive) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_MUSIC)
        .apply { extras?.let { setExtras(it) } }
        .build()

    /** The playable item for [track]: its address and type filled in. */
    @OptIn(UnstableApi::class)
    fun playable(track: ApiTrack, server: String, artwork: (String?) -> Uri?): MediaItem {
        val live = livePath(track)
        val builder = MediaItem.Builder()
            .setMediaId(id(track))
            .setMediaMetadata(metadata(track, artwork))
        return if (live != null) {
            builder.setUri(server + live.first).apply { if (live.second) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
                .build()
        } else {
            builder.setUri(Uri.Builder().scheme(SCHEME).authority("song").appendQueryParameter("id", id(track)).build())
                .setCustomCacheKey(track.key)
                .build()
        }
    }

    /** The server path for live radio and station tracks, and whether it's HLS. */
    fun livePath(track: ApiTrack): Pair<String, Boolean>? {
        val via = track.via ?: return null
        fun enc(s: String) = Uri.encode(s)
        return when {
            track.isChannel -> {
                val (type, id) = split(track.sourceId.removePrefix("channel:"))
                "/api/live/sxm/${enc(via)}/channel/${enc(type)}/${enc(id)}" to true
            }
            track.isStationTrack -> {
                val (_, trackId) = split(track.sourceId.removePrefix("stationtrack:"))
                "/api/live/sxm/${enc(via)}/station-track/${enc(trackId)}" to false
            }
            track.isRadio -> {
                val hls = track.sourceId.startsWith("hls:")
                "/api/live/radio/${enc(via)}/station/${enc(track.sourceId.removePrefix("hls:"))}/stream" to hls
            }
            else -> null
        }
    }

    /** "a:b:c" as ("a", "b:c"). */
    fun split(value: String): Pair<String, String> {
        val at = value.indexOf(':')
        return if (at < 0) value to "" else value.substring(0, at) to value.substring(at + 1)
    }

    /** The song behind a `samm://song` address. */
    fun trackOf(uri: Uri): ApiTrack? = if (uri.scheme == SCHEME) uri.getQueryParameter("id")?.let(::track) else null

    fun artworkUri(server: String, url: String?, px: Int): Uri? = when {
        url.isNullOrBlank() -> null
        url.startsWith("/api/art?") -> "$server$url&px=$px".toUri()
        url.startsWith("http") -> url.toUri()
        else -> null
    }
}
