package com.music.mini.playback

import android.net.Uri
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.music.mini.net.ApiJson
import com.music.mini.net.ApiTrack

/**
 * Songs travel through the player as media items whose id holds the whole song ("t:" and the
 * song as JSON), as in the Connect app. A song's stream is only chosen when it starts (the
 * service asks the server then): items carry a `samm://song` address until then. Live radio's
 * address is the server's, as it is.
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

    fun track(item: MediaItem?): ApiTrack? = item?.let { track(it.mediaId) }

    fun metadata(track: ApiTrack, server: String): MediaMetadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .setAlbumTitle(track.album)
        .setArtworkUri(artworkUri(server, track.artworkUrl, 256))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(if (track.isLive) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_MUSIC)
        .build()

    /** The playable item for [track]: its address and type filled in. */
    @OptIn(UnstableApi::class)
    fun playable(track: ApiTrack, server: String): MediaItem {
        val builder = MediaItem.Builder().setMediaId(id(track)).setMediaMetadata(metadata(track, server))
        val live = livePath(track)
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
            // One of the person's own stations: the server's broadcast, the DJ's time checks in their time zone.
            track.isPersonal -> "/api/live/radio/${enc(via)}/station/${enc(track.sourceId)}/stream?tz=${enc(java.util.TimeZone.getDefault().id)}" to false
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

    fun artworkUri(server: String, url: String?, px: Int): Uri? = artworkUrl(server, url, px)?.let(Uri::parse)

    fun artworkUrl(server: String, url: String?, px: Int): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("/api/art?") -> "$server$url&px=$px"
        url.startsWith("/api/radio/art?") -> "$server$url"
        url.startsWith("http") -> url
        else -> null
    }
}
