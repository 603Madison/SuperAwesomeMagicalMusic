package com.music.player.sources

import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.quality.StreamQuality
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SourceSelectorTest {
    private class FakeSource(
        override val type: SourceType,
        private val catalog: List<Track> = emptyList(),
        private val available: Boolean = true,
        private val withinCap: Boolean = true,
        private val failure: Exception? = null,
    ) : MusicSource {
        val streamed = mutableListOf<String>()

        override suspend fun isAvailable() = available

        override suspend fun search(query: String, limit: Int): List<Track> {
            failure?.let { throw it }
            return catalog
        }

        override suspend fun stream(sourceId: String, quality: StreamQuality): SourceStream {
            failure?.let { throw it }
            streamed += sourceId
            return SourceStream(url = "https://${type.name}/$sourceId", description = "test", withinCap = withinCap, fileExtension = "mp3")
        }

        override suspend fun findMatch(track: Track): Track? {
            failure?.let { throw it }
            if (track.source == type) return track
            return TrackMatcher.bestMatch(track, catalog)
        }
    }

    private val song = Track(SourceType.YOUTUBE_MUSIC, "yt1", "Song", "Artist", durationMs = 200_000)
    private val librarySong = song.copy(source = SourceType.JELLYFIN, sourceId = "jf1")
    private val qobuzSong = song.copy(source = SourceType.QOBUZ, sourceId = "q1")

    private val missing = mutableListOf<Track>()

    private suspend fun select(jellyfin: FakeSource, vararg fallback: FakeSource, quality: StreamQuality = StreamQuality.Original) =
        SourceSelector.select(song, quality, jellyfin, fallback.toList()) { missing += it }

    @Test
    fun `jellyfin copy wins over the track's own service`() = runTest {
        val selected = select(
            FakeSource(SourceType.JELLYFIN, listOf(librarySong)),
            FakeSource(SourceType.YOUTUBE_MUSIC),
        )
        assertEquals(librarySong, selected.track)
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `missing from jellyfin streams the track's own service and notifies lidarr`() = runTest {
        val selected = select(
            FakeSource(SourceType.JELLYFIN),
            FakeSource(SourceType.QOBUZ, listOf(qobuzSong)),
            FakeSource(SourceType.YOUTUBE_MUSIC),
        )
        assertEquals(SourceType.YOUTUBE_MUSIC, selected.track.source)
        assertEquals(listOf(song), missing)
    }

    @Test
    fun `jellyfin not configured still counts as missing`() = runTest {
        select(FakeSource(SourceType.JELLYFIN, available = false), FakeSource(SourceType.YOUTUBE_MUSIC))
        assertEquals(listOf(song), missing)
    }

    @Test
    fun `unreachable jellyfin falls back without bothering lidarr`() = runTest {
        val selected = select(
            FakeSource(SourceType.JELLYFIN, failure = IOException("timeout")),
            FakeSource(SourceType.YOUTUBE_MUSIC),
        )
        assertEquals(SourceType.YOUTUBE_MUSIC, selected.track.source)
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `failing service falls back to the next one in order`() = runTest {
        val selected = select(
            FakeSource(SourceType.JELLYFIN),
            FakeSource(SourceType.QOBUZ, listOf(qobuzSong)),
            FakeSource(SourceType.YOUTUBE_MUSIC, failure = IOException("blocked")),
        )
        assertEquals(qobuzSong, selected.track)
    }

    @Test
    fun `service that can't meet the cap loses to one that can`() = runTest {
        // Qobuz can't go below 320 kbps; YouTube can.
        val qobuz = FakeSource(SourceType.QOBUZ, listOf(qobuzSong), withinCap = false)
        val youtube = FakeSource(SourceType.YOUTUBE_MUSIC, listOf(song))
        val picked = SourceSelector.select(qobuzSong, StreamQuality.Capped(64), FakeSource(SourceType.JELLYFIN), listOf(qobuz, youtube)) {}
        assertEquals(song, picked.track)
    }

    @Test
    fun `service that can't meet the cap is used when nothing else has the song`() = runTest {
        val qobuz = FakeSource(SourceType.QOBUZ, listOf(qobuzSong), withinCap = false)
        val youtube = FakeSource(SourceType.YOUTUBE_MUSIC)
        val picked = SourceSelector.select(qobuzSong, StreamQuality.Capped(64), FakeSource(SourceType.JELLYFIN), listOf(qobuz, youtube)) {}
        assertEquals(qobuzSong, picked.track)
    }

    @Test
    fun `nothing available reports every failure`() = runTest {
        try {
            select(
                FakeSource(SourceType.JELLYFIN, failure = IOException("jellyfin down")),
                FakeSource(SourceType.YOUTUBE_MUSIC, failure = IOException("youtube down")),
            )
            fail("Expected SourceUnavailableException")
        } catch (e: SourceUnavailableException) {
            assertTrue(e.message!!.contains("jellyfin down"))
            assertTrue(e.message!!.contains("youtube down"))
        }
    }
}
