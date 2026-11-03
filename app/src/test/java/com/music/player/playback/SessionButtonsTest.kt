package com.music.player.playback

import androidx.media3.session.CommandButton
import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner
import com.music.player.MusicApp
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.sources.siriusxm.SxmStationTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The bookmark and thumb buttons Android Auto shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionButtonsTest {
    private val container = ApplicationProvider.getApplicationContext<MusicApp>().container
    private val buttons = SessionButtons(container)

    private fun player(track: Track) = androidx.media3.exoplayer.ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build().apply {
        setMediaItem(track.toMediaItem())
    }

    @Test
    fun `a library song gets only the bookmark button, which bookmarks it and takes it back`() = runBlocking<Unit> {
        val song = Track(SourceType.JELLYFIN, "j1", "Stick Season", "Noah Kahan")
        val player = player(song)
        try {
            assertEquals(listOf(CommandButton.ICON_PLUS), buttons.buttons(player).map { it.icon })

            assertEquals(true, buttons.toggleBookmark(player))
            val bookmark = container.bookmarks.items.value.single { it.track.key == song.key }
            assertFalse(bookmark.heard)
            assertEquals(listOf(CommandButton.ICON_CHECK_CIRCLE_FILLED), buttons.buttons(player).map { it.icon })

            assertEquals(false, buttons.toggleBookmark(player))
            assertTrue(container.bookmarks.items.value.none { it.id == bookmark.id })
        } finally {
            player.release()
        }
    }

    @Test
    fun `a pandora station song gets thumbs, and is bookmarked as the song heard`() = runBlocking<Unit> {
        val stationSong = SxmStationTrack("st1", "t1", "https://example.com/t1.mp4", null)
            .toTrack("Noah Kahan Radio")
            .copy(title = "Here", artist = "Mumford & Sons & Chris Stapleton")
        val player = player(stationSong)
        try {
            assertEquals(
                listOf(CommandButton.ICON_THUMB_DOWN_UNFILLED, CommandButton.ICON_THUMB_UP_UNFILLED, CommandButton.ICON_PLUS),
                buttons.buttons(player).map { it.icon },
            )
            assertEquals(true, buttons.toggleBookmark(player))
            val bookmark = container.bookmarks.items.value.last()
            assertTrue(bookmark.heard)
            assertEquals("Here", bookmark.track.title)
            assertFalse(bookmark.track.isSiriusXmStationTrack)
            container.bookmarks.remove(bookmark.id)
        } finally {
            player.release()
        }
    }
}
