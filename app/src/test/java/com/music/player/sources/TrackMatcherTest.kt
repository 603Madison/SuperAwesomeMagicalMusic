package com.music.player.sources

import com.music.player.model.SourceType
import com.music.player.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {
    private fun track(title: String, artist: String, durationMs: Long? = null, source: SourceType = SourceType.QOBUZ, id: String = title) =
        Track(source, id, title, artist, durationMs = durationMs)

    @Test
    fun `titles ignore remaster tags, featured artists and accents`() {
        assertEquals("here comes the sun", TrackMatcher.normalizeTitle("Here Comes The Sun - Remastered 2009"))
        assertEquals("here comes the sun", TrackMatcher.normalizeTitle("Here Comes the Sun (2019 Mix)"))
        assertEquals("despacito", TrackMatcher.normalizeTitle("Despacito (feat. Daddy Yankee)"))
        assertEquals("cafe del mar", TrackMatcher.normalizeTitle("Café del Mar"))
        assertEquals("one more time", TrackMatcher.normalizeTitle("One More Time feat. Romanthony"))
    }

    @Test
    fun `search titles drop tags but keep punctuation for the server to normalize`() {
        assertEquals("Don't Stop Me Now", TrackMatcher.searchTitle("Don't Stop Me Now (2011 Remaster)"))
        assertEquals("Get Lucky", TrackMatcher.searchTitle("Get Lucky (feat. Pharrell Williams)"))
        assertEquals("Daft Punk Get Lucky", TrackMatcher.searchQuery(track("Get Lucky - Radio Edit", "Daft Punk, Pharrell Williams")))
    }

    @Test
    fun `artists are split into individual credits`() {
        assertEquals(setOf("simon", "garfunkel", "simon garfunkel"), TrackMatcher.artistNames("Simon & Garfunkel"))
        assertTrue(TrackMatcher.isSameArtist("Daft Punk, Pharrell Williams", "Daft Punk"))
        assertTrue(TrackMatcher.isSameArtist("The Beatles", "Beatles"))
        assertTrue(TrackMatcher.isSameArtist("Daft Punk - Topic", "Daft Punk"))
        assertFalse(TrackMatcher.isSameArtist("Paul Simon", "Simon & Garfunkel"))
    }

    @Test
    fun `primary artist drops featured and secondary credits`() {
        assertEquals("Daft Punk", TrackMatcher.primaryArtist("Daft Punk, Pharrell Williams"))
        assertEquals("Luis Fonsi", TrackMatcher.primaryArtist("Luis Fonsi feat. Daddy Yankee"))
        assertEquals("Simon & Garfunkel", TrackMatcher.primaryArtist("Simon & Garfunkel"))
    }

    @Test
    fun `same song across services matches`() {
        val library = track("Get Lucky (feat. Pharrell Williams)", "Daft Punk", 369_000, SourceType.JELLYFIN)
        val youtube = track("Get Lucky", "Daft Punk, Pharrell Williams", 368_000, SourceType.YOUTUBE_MUSIC)
        assertTrue(TrackMatcher.matches(library, youtube))
    }

    @Test
    fun `different songs or very different durations do not match`() {
        val wanted = track("Get Lucky", "Daft Punk", 248_000)
        assertFalse(TrackMatcher.matches(track("Get Lucky", "Someone Else", 248_000), wanted))
        assertFalse(TrackMatcher.matches(track("Lose Yourself to Dance", "Daft Punk", 248_000), wanted))
        // The 6-minute album version is not the 4-minute radio edit.
        assertFalse(TrackMatcher.matches(track("Get Lucky", "Daft Punk", 369_000), wanted))
    }

    @Test
    fun `best match prefers the closest duration`() {
        val wanted = track("Song", "Artist", 200_000)
        val far = track("Song", "Artist", 210_000, id = "far")
        val near = track("Song", "Artist", 201_000, id = "near")
        assertEquals("near", TrackMatcher.bestMatch(wanted, listOf(far, near))?.sourceId)
        assertNull(TrackMatcher.bestMatch(wanted, listOf(track("Other", "Artist"))))
    }
}
