package com.music.player.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.music.player.AppContainer
import com.music.player.model.Track
import com.music.player.sources.siriusxm.SxmCut
import com.music.player.sources.siriusxm.SxmStationTrack
import com.music.player.sources.siriusxm.SxmThumb

/**
 * The extra buttons Android Auto (and the media notification) show: a bookmark button, which
 * keeps the song for later so it can be put in a playlist on the phone rather than while
 * driving, and thumbs up and down on Pandora station songs.
 */
class SessionButtons(private val container: AppContainer) {
    /**
     * What's playing, as a key a bookmark remembers: the song on a SiriusXM channel (which
     * changes while the channel plays on), else the item itself. Null when there's no song.
     */
    fun playingKey(player: Player): String? {
        val track = player.currentMediaItem?.toTrack() ?: return null
        if (!track.isSiriusXmChannel) return track.key
        val cut = channelSong(track) ?: return null
        return "${track.key}#${cut.id}"
    }

    private fun channelSong(channel: Track): SxmCut? =
        container.siriusXm.nowPlaying.value?.takeIf { it.channel.key == channel.key }?.cut?.takeIf { it.isSong }

    /**
     * Bookmarks the song playing, or takes the bookmark back if it was just made. Returns
     * true when bookmarked, false when taken back, null when there's no song to bookmark.
     */
    suspend fun toggleBookmark(player: Player): Boolean? {
        val key = playingKey(player) ?: return null
        container.bookmarks.forPlaying(key)?.let {
            container.bookmarks.remove(it.id)
            return false
        }
        val track = player.currentMediaItem?.toTrack() ?: return null
        val station = SxmStationTrack.idOf(track)
        when {
            track.isSiriusXmChannel -> {
                val cut = channelSong(track) ?: return null
                container.bookmarks.add(cut.toTrack(), heard = true, heardOn = track.title, playingKey = key)
            }
            station != null -> {
                val (stationId, trackId) = station
                // Station tracks' links expire, so keep the song itself, found again when saved.
                val song = SxmCut(trackId, track.title, track.artist, track.album, 0, track.durationMs ?: 0, track.artworkUrl, interstitial = false)
                val stationName = container.settingsState.value.siriusXmStations.firstOrNull { it.id == stationId }?.name
                container.bookmarks.add(song.toTrack(), heard = true, heardOn = stationName ?: "Pandora station", playingKey = key)
            }
            else -> container.bookmarks.add(track, heard = false, heardOn = if (track.isLive) track.title else null, playingKey = key)
        }
        container.appLog.log("session", "bookmarked ${track.title}")
        return true
    }

    /**
     * Thumbs a Pandora station song up or down; giving the thumb it already has takes it back.
     * A thumb down skips the song, as SiriusXM's own player does.
     */
    suspend fun rate(player: Player, thumb: SxmThumb): Boolean {
        val track = player.currentMediaItem?.toTrack() ?: return false
        val (stationId, trackId) = SxmStationTrack.idOf(track) ?: return false
        val current = container.siriusXm.thumbs.value[trackId]
        val wanted = if (current == thumb) SxmThumb.NONE else thumb
        val positionMs = player.currentPosition
        if (wanted == SxmThumb.DOWN && player.hasNextMediaItem()) player.seekToNextMediaItem()
        return container.siriusXm.rateStationTrack(stationId, trackId, wanted, positionMs)
    }

    /** The buttons for what's playing now. */
    fun buttons(player: Player): List<CommandButton> {
        val track = player.currentMediaItem?.toTrack()
        val buttons = ArrayList<CommandButton>()
        track?.let(SxmStationTrack::idOf)?.let { (_, trackId) ->
            val thumb = container.siriusXm.thumbs.value[trackId]
            buttons += button(
                if (thumb == SxmThumb.DOWN) CommandButton.ICON_THUMB_DOWN_FILLED else CommandButton.ICON_THUMB_DOWN_UNFILLED,
                "Thumbs down",
                THUMB_DOWN,
            )
            buttons += button(
                if (thumb == SxmThumb.UP) CommandButton.ICON_THUMB_UP_FILLED else CommandButton.ICON_THUMB_UP_UNFILLED,
                "Thumbs up",
                THUMB_UP,
            )
        }
        val key = track?.let { playingKey(player) }
        buttons += if (key != null && container.bookmarks.forPlaying(key) != null) {
            button(CommandButton.ICON_CHECK_CIRCLE_FILLED, "Bookmarked", BOOKMARK)
        } else {
            button(CommandButton.ICON_PLUS, "Bookmark for later", BOOKMARK)
        }
        return buttons
    }

    private fun button(icon: Int, name: String, command: SessionCommand) =
        CommandButton.Builder(icon).setDisplayName(name).setSessionCommand(command).build()

    companion object {
        val BOOKMARK = SessionCommand("com.music.player.BOOKMARK", Bundle.EMPTY)
        val THUMB_UP = SessionCommand("com.music.player.THUMB_UP", Bundle.EMPTY)
        val THUMB_DOWN = SessionCommand("com.music.player.THUMB_DOWN", Bundle.EMPTY)
        val COMMANDS = listOf(BOOKMARK, THUMB_UP, THUMB_DOWN)
    }
}
