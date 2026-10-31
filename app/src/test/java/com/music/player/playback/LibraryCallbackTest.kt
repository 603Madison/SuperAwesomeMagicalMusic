package com.music.player.playback

import android.os.Bundle
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.music.player.MusicApp
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.sources.siriusxm.SxmChannel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The browse tree Android Auto shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryCallbackTest {
    private val container = ApplicationProvider.getApplicationContext<MusicApp>().container
    private val callback = LibraryCallback(container, MainScope())
    private val songs = listOf(
        Track(SourceType.JELLYFIN, "1", "One", "Artist"),
        Track(SourceType.YOUTUBE_MUSIC, "2", "Two", "Artist"),
    )

    @Test
    fun `the root is answered at once, as Android Auto's legacy connection needs`() {
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val auto = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                "com.google.android.projection.gearhead", 0, 0, 0, 0, false, Bundle.EMPTY, true,
            )
            val session = service.get().onGetSession(auto) as MediaLibraryService.MediaLibrarySession
            val root = callback.onGetLibraryRoot(session, auto, null)
            // Media3 blocks the main thread on this future, so it must already be complete.
            assertTrue(root.isDone)
            assertEquals(LibraryIds.ROOT, root.get().value!!.mediaId)
        } finally {
            service.destroy()
        }
    }

    @Test
    fun `root lists playlists and downloads, plus SiriusXM only when signed in`() = runBlocking {
        val root = callback.children(LibraryIds.ROOT).map { it.mediaId }
        assertEquals(listOf(LibraryIds.PLAYLISTS, LibraryIds.DOWNLOADS), root)
    }

    @Test
    fun `playlists can be browsed and played from any song`() = runBlocking {
        val id = container.playlists.create("Road Trip", songs)
        val playlists = callback.children(LibraryIds.PLAYLISTS)
        val playlist = playlists.single { it.mediaMetadata.title == "Road Trip" }
        assertTrue(playlist.mediaMetadata.isBrowsable == true && playlist.mediaMetadata.isPlayable == true)

        val entries = callback.children(playlist.mediaId)
        assertEquals(listOf("One", "Two"), entries.map { it.mediaMetadata.title.toString() })

        val (queue, start) = callback.expand(entries[1].mediaId)!!
        assertEquals(songs, queue)
        assertEquals(1, start)
        assertEquals(songs to 0, callback.expand(LibraryIds.playlist(id)))
    }

    @Test
    fun `track ids carry the whole track, including SiriusXM channels`() {
        val channel = SxmChannel("octane", "channel-linear", "Octane", 37, "Rock", null, false).toTrack()
        assertEquals(channel, LibraryIds.parseTrack(LibraryIds.track(channel)))
        assertEquals("playlist-id" to 3, LibraryIds.parsePlaylist(LibraryIds.playlistEntry("playlist-id", 3)))
        assertEquals("playlist-id" to null, LibraryIds.parsePlaylist(LibraryIds.playlist("playlist-id")))
        assertTrue(channel.toMediaItem().localConfiguration!!.mimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8)
    }

    @Test
    fun `radio stations appear once there are favourites`() = runBlocking {
        val station = com.music.player.sources.radio.RadioStation("s1", "WXYZ", urlResolved = "http://stream/a.mp3")
        container.settings.setRadioFavorite(station, true)
        try {
            assertTrue(LibraryIds.RADIO in callback.children(LibraryIds.ROOT).map { it.mediaId })
            val stations = callback.children(LibraryIds.RADIO)
            assertEquals(listOf("WXYZ"), stations.map { it.mediaMetadata.title.toString() })
            assertEquals(listOf(station.toTrack()), callback.expand(stations.single().mediaId)!!.first)
        } finally {
            container.settings.setRadioFavorite(station, false)
        }
    }
}
