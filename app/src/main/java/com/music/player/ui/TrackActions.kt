package com.music.player.ui

import com.music.player.AppContainer
import com.music.player.model.Track
import com.music.player.sources.siriusxm.SxmCut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Actions available on any song, wherever it is listed. */
class TrackActions(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val showAddToPlaylist: (List<Track>) -> Unit,
    private val showMessage: (String) -> Unit,
) {
    fun play(tracks: List<Track>, index: Int = 0) = container.player.play(tracks, index)

    fun shuffle(tracks: List<Track>) = container.player.play(tracks, shuffle = true)

    fun playNext(track: Track) {
        container.player.playNext(track)
        showMessage("\"${track.title}\" will play next")
    }

    fun addToQueue(tracks: List<Track>) {
        container.player.addToQueue(tracks)
        showMessage(if (tracks.size == 1) "Added to queue" else "Added ${tracks.size} songs to queue")
    }

    fun addToPlaylist(tracks: List<Track>) = showAddToPlaylist(tracks)

    fun download(tracks: List<Track>) {
        scope.launch {
            container.downloads.enqueue(tracks)
            showMessage(if (tracks.size == 1) "Downloading \"${tracks.first().title}\"" else "Downloading ${tracks.size} songs")
        }
    }

    /**
     * Saves a song heard on SiriusXM: the Jellyfin copy if the library has it, otherwise the
     * artist is requested from Lidarr and the song is saved to play from a streaming service.
     */
    fun saveHeardSong(cut: SxmCut) {
        scope.launch {
            val captured = container.heardSongs.capture(cut)
            showAddToPlaylist(listOf(captured.track))
            when {
                captured.inLibrary -> showMessage("\"${cut.title}\" is in your Jellyfin library")
                captured.requestedFromLidarr && container.settingsState.value.lidarr.isConfigured ->
                    showMessage("Not in Jellyfin yet — requested ${cut.artist} from Lidarr")
            }
        }
    }

    fun removeDownload(track: Track) {
        scope.launch { container.downloads.delete(track.key) }
    }
}
