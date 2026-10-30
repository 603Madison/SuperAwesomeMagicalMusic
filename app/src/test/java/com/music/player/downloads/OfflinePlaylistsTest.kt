package com.music.player.downloads

import androidx.test.core.app.ApplicationProvider
import com.music.player.MusicApp
import com.music.player.data.DownloadEntity
import com.music.player.data.DownloadStatus
import com.music.player.model.SourceType
import com.music.player.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflinePlaylistsTest {
    private val container = ApplicationProvider.getApplicationContext<MusicApp>().container
    private val dao = container.database.downloads()
    private val one = Track(SourceType.JELLYFIN, "1", "One", "Artist")
    private val two = Track(SourceType.YOUTUBE_MUSIC, "2", "Two", "Artist")

    @Test
    fun `kept playlists download new songs and drop ones no longer needed`() = runBlocking {
        val id = container.playlists.create("Road Trip", listOf(one))
        container.playlists.setKeepDownloaded(id, true)
        container.offlinePlaylists.sync()
        assertTrue(dao.get(one.key)!!.autoDownloaded)

        // Adding a song downloads it too.
        container.playlists.addTracks(id, listOf(two))
        container.offlinePlaylists.sync()
        assertTrue(dao.get(two.key) != null)

        // A song downloaded by hand stays when it leaves the playlist; automatic ones go.
        container.downloads.enqueue(listOf(two))
        container.playlists.removeAt(id, 0)
        container.playlists.removeAt(id, 0)
        container.offlinePlaylists.sync()
        assertNull(dao.get(one.key))
        assertEquals(false, dao.get(two.key)!!.autoDownloaded)
    }

    @Test
    fun `completed downloads in kept playlists are checked for better copies`() = runBlocking {
        val id = container.playlists.create("Mix", listOf(one))
        container.playlists.setKeepDownloaded(id, true)
        dao.upsert(DownloadEntity(one.key, one, DownloadStatus.COMPLETED, 1f, "/x.mp3", 10, createdAt = 0, kbps = 70))
        container.offlinePlaylists.sync()
        // The check is queued without touching the existing copy.
        assertEquals(DownloadStatus.COMPLETED, dao.get(one.key)!!.status)
        assertEquals("/x.mp3", dao.get(one.key)!!.filePath)
    }
}
