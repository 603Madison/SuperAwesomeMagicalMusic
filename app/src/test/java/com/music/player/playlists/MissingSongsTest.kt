package com.music.player.playlists

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.music.player.data.AppDatabase
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.quality.StreamQuality
import com.music.player.sources.MusicSource
import com.music.player.sources.SourceStream
import com.music.player.sources.youtube.YouTubeMusicSource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Songs an imported list names that aren't found: kept, greyed out, and found later when they turn up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MissingSongsTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    /** A library that holds what it's given. */
    private class Library : MusicSource {
        val songs = mutableListOf(Track(SourceType.JELLYFIN, "s1", "Paranoid Android", "Radiohead", "OK Computer", 387_000))
        override val type = SourceType.JELLYFIN
        override suspend fun isAvailable() = true
        override suspend fun search(query: String, limit: Int): List<Track> =
            songs.filter { song -> query.lowercase().split(' ').any { it.length > 2 && "${song.title} ${song.artist}".lowercase().contains(it) } }
        override suspend fun stream(sourceId: String, quality: StreamQuality): SourceStream = error("Not played here")
    }

    @After
    fun close() = db.close()

    @Test
    fun `a song not found goes in greyed out, and takes its place once it turns up`() = runBlocking {
        val library = Library()
        val playlists = PlaylistRepository(db.playlists(), flowOf(null)) {}
        val http = OkHttpClient()
        val importer = PlaylistImporter(PlaylistReaders(http), YouTubeMusicSource(http) { false }, { listOf(library) }, playlists)

        val result = importer.importText("Radiohead - Paranoid Android\nNobody - Nothing At All", "Typed")
        assertEquals(1, result.added)
        assertEquals(listOf("Nothing At All"), result.notFound.map { it.title })
        val entries = playlists.entries(result.playlistId)
        assertEquals(listOf("Paranoid Android", "Nothing At All"), entries.map { it.title })
        assertTrue(entries[1].isMissing)
        assertEquals("Nobody", entries[1].artist)

        // Looked for again while it's nowhere: still missing.
        assertEquals(0 to 1, importer.findMissing())
        // It turns up in the library: found, in the same place.
        library.songs += Track(SourceType.JELLYFIN, "s9", "Nothing At All", "Nobody", "Later", 200_000)
        assertEquals(1 to 0, importer.findMissing(result.playlistId))
        val after = playlists.entries(result.playlistId)
        assertEquals(listOf("s1", "s9"), after.map { it.sourceId })
        assertFalse(after.any { it.isMissing })
    }
}
