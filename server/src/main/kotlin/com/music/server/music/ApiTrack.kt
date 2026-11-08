package com.music.server.music

import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.sources.ArtworkUrls
import com.music.server.util.SecretBox
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable

/**
 * A song as the server's clients see it: a [Track] plus [via], the saved source it came from (one
 * person may have two Jellyfin servers). Playlists and history store it the same way.
 */
@Serializable
data class ApiTrack(
    val source: SourceType,
    val sourceId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val isrc: String? = null,
    val via: String? = null,
) {
    fun toTrack() = Track(source, sourceId, title, artist, album, durationMs, artworkUrl, isrc)

    companion object {
        fun of(track: Track, via: String?) = ApiTrack(
            track.source, track.sourceId, track.title, track.artist, track.album, track.durationMs, track.artworkUrl, track.isrc, via,
        )
    }
}

/**
 * Artwork goes through the server: the services' image addresses may only be reachable from the
 * server (a Jellyfin on the home network), and the person's browser or phone needn't talk to
 * every service. The server only fetches addresses it handed out itself, signed so it can't be
 * used to fetch anything else.
 */
class Artwork(private val box: SecretBox) {
    /** The server's address for [url] (an upstream image), or [url] itself if it's already one. */
    fun wrap(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith(PATH)) return url
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        return "$PATH?u=${url.encodeURLParameter()}&s=${box.sign(url)}"
    }

    /** The upstream address inside one of [wrap]'s, if it's genuine. */
    fun unwrap(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith(PATH)) return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val query = url.substringAfter('?', "")
        val params = query.split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "")
            if (key.isEmpty()) null else key to java.net.URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
        }.toMap()
        val upstream = params["u"] ?: return null
        return upstream.takeIf { box.verifySignature(it, params["s"].orEmpty()) }
    }

    fun verify(upstream: String, signature: String) = box.verifySignature(upstream, signature)

    /** [track] for sending out: artwork through the server. */
    fun outgoing(track: ApiTrack) = track.copy(artworkUrl = wrap(track.artworkUrl))

    /** [track] as received: the upstream artwork address, if it was a genuine one. */
    fun incoming(track: ApiTrack) = track.copy(artworkUrl = unwrap(track.artworkUrl))

    companion object {
        const val PATH = "/api/art"

        fun sized(upstream: String, px: Int?): String = if (px == null) upstream else ArtworkUrls.sized(upstream, px)
    }
}
