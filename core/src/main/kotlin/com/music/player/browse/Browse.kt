package com.music.player.browse

import com.music.player.model.SourceType
import com.music.player.model.Track
import kotlinx.serialization.Serializable

/** An artist in one source's catalog. */
@Serializable
data class ArtistRef(
    val source: SourceType,
    /** The artist's id in that source (a Jellyfin item id, a Qobuz artist id, a YouTube channel link). */
    val id: String,
    val name: String,
    val artworkUrl: String? = null,
)

/** An album in one source's catalog. */
@Serializable
data class AlbumRef(
    val source: SourceType,
    /** The album's id in that source (a Jellyfin item id, a Qobuz album id, a YouTube playlist link). */
    val id: String,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val artworkUrl: String? = null,
    val trackCount: Int? = null,
)

data class AlbumDetail(val album: AlbumRef, val tracks: List<Track>)

data class ArtistDetail(
    val artist: ArtistRef,
    /** The artist's best-known songs, most popular first where the source knows. */
    val topTracks: List<Track>,
    val albums: List<AlbumRef>,
)

/** A source whose catalog can be browsed by artist and album, not only searched for songs. */
interface Browsable {
    suspend fun searchArtists(query: String, limit: Int = 10): List<ArtistRef>

    suspend fun searchAlbums(query: String, limit: Int = 10): List<AlbumRef>

    suspend fun album(ref: AlbumRef): AlbumDetail

    suspend fun artist(ref: ArtistRef): ArtistDetail
}
