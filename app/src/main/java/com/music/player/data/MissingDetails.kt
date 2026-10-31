package com.music.player.data

import com.music.player.model.Track

/**
 * Fills in artwork and album that a saved song lacks (SiriusXM doesn't always send them) from
 * another service's copy of it, in playlists and downloads alike.
 */
class MissingDetails(private val playlists: PlaylistDao, private val downloads: DownloadDao) {
    /** Returns [track] with the details filled in, or [track] itself if [copy] adds nothing. */
    suspend fun fill(track: Track, copy: Track): Track {
        val filled = with(track, copy)
        if (filled == track) return track
        playlists.fillDetails(track.source.name, track.sourceId, copy.artworkUrl, copy.album)
        downloads.fillDetails(track.key, copy.artworkUrl, copy.album)
        return filled
    }

    companion object {
        fun with(track: Track, copy: Track): Track =
            track.copy(artworkUrl = track.artworkUrl ?: copy.artworkUrl, album = track.album ?: copy.album)
    }
}
