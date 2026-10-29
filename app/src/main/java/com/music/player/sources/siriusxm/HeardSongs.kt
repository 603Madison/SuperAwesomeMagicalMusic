package com.music.player.sources.siriusxm

import android.util.Log
import com.music.player.lidarr.LidarrService
import com.music.player.model.Track
import com.music.player.playlists.PlaylistRepository
import com.music.player.sources.MusicSource

/** A song heard on SiriusXM, as it will be saved. */
data class CapturedSong(
    val track: Track,
    /** The song is already in Jellyfin, so the library copy was saved. */
    val inLibrary: Boolean,
    /** The artist was sent to (or queued for) Lidarr. */
    val requestedFromLidarr: Boolean,
)

/**
 * Turns a song heard on SiriusXM into a playlist entry, running the usual source rules:
 * the Jellyfin copy is saved when the library has the song; otherwise the artist is requested
 * from Lidarr and the SiriusXM entry is saved, which plays from Qobuz or YouTube Music until
 * Lidarr has brought the album into Jellyfin.
 */
class HeardSongs(
    private val jellyfin: MusicSource,
    private val lidarr: LidarrService,
    private val playlists: PlaylistRepository,
) {
    suspend fun capture(cut: SxmCut): CapturedSong {
        val heard = cut.toTrack()
        if (jellyfin.isAvailable()) {
            val match = try {
                jellyfin.findMatch(heard)
            } catch (e: Exception) {
                // Jellyfin couldn't be asked, so we don't know the song is missing.
                Log.w(TAG, "Jellyfin lookup failed for ${heard.title}", e)
                return CapturedSong(heard, inLibrary = false, requestedFromLidarr = false)
            }
            if (match != null) return CapturedSong(match, inLibrary = true, requestedFromLidarr = false)
        }
        lidarr.onTrackMissingFromJellyfin(heard)
        return CapturedSong(heard, inLibrary = false, requestedFromLidarr = true)
    }

    /** One-tap save (e.g. from Android Auto) to the "Heard on SiriusXM" playlist. */
    suspend fun saveToHeardPlaylist(cut: SxmCut): CapturedSong {
        val captured = capture(cut)
        playlists.addTracks(playlists.getOrCreate(PLAYLIST_NAME), listOf(captured.track))
        return captured
    }

    companion object {
        const val PLAYLIST_NAME = "Heard on SiriusXM"
        private const val TAG = "HeardSongs"
    }
}
