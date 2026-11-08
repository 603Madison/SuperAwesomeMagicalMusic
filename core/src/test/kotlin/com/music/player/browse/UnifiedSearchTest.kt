package com.music.player.browse

import com.music.player.model.SourceType.JELLYFIN
import com.music.player.model.SourceType.QOBUZ
import com.music.player.model.SourceType.YOUTUBE_MUSIC
import com.music.player.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSearchTest {
    private fun song(source: com.music.player.model.SourceType, id: String, title: String, artist: String, album: String? = null) =
        Track(source, id, title, artist, album, durationMs = 200_000)

    private val radiohead = listOf(
        SourceResults(
            "home", library = true,
            songs = listOf(song(JELLYFIN, "j1", "Creep", "Radiohead", "Pablo Honey")),
            artists = listOf(ArtistRef(JELLYFIN, "ja", "Radiohead")),
            albums = listOf(AlbumRef(JELLYFIN, "jal", "OK Computer", "Radiohead", 1997, "jellyfin-art", 12)),
        ),
        SourceResults(
            "qobuz", library = false,
            songs = listOf(song(QOBUZ, "q1", "Creep", "Radiohead"), song(QOBUZ, "q2", "Karma Police", "Radiohead")),
            artists = listOf(ArtistRef(QOBUZ, "qa", "Radiohead", "qobuz-art"), ArtistRef(QOBUZ, "qb", "Radiohead Tribute Band")),
            albums = listOf(AlbumRef(QOBUZ, "qal", "OK Computer (Remastered)", "Radiohead", 2009, "qobuz-album-art", 12), AlbumRef(QOBUZ, "qkid", "Kid A", "Radiohead", 2000)),
        ),
        SourceResults(
            "youtube", library = false,
            songs = listOf(song(YOUTUBE_MUSIC, "y1", "Creep", "Radiohead - Topic")),
            artists = listOf(ArtistRef(YOUTUBE_MUSIC, "ya", "Radiohead"), ArtistRef(YOUTUBE_MUSIC, "yb", "Thom Yorke")),
        ),
    )

    @Test
    fun `an artist's name finds the artist, once, with the library's copy`() {
        val results = UnifiedSearch.merge("radiohead", radiohead)
        val top = results.top as TopResult.OfArtist
        assertEquals("Radiohead", top.artist.name)
        assertEquals(listOf("home", "qobuz", "youtube"), top.artist.copies.map { it.via })
        // The library has no picture of them; another source does.
        assertEquals("qobuz-art", top.artist.artworkUrl)
        assertEquals(listOf("Radiohead", "Radiohead Tribute Band", "Thom Yorke"), results.artists.map { it.name })
        // Creep is on all three: one song, played from the library.
        assertEquals(listOf("Creep", "Karma Police"), results.songs.map { it.item.title })
        assertEquals("home", results.songs[0].via)
        // OK Computer is one album whichever edition, with the library's cover.
        assertEquals(listOf("OK Computer", "Kid A"), results.albums.map { it.title })
        assertEquals("jellyfin-art", results.albums[0].artworkUrl)
        assertEquals(2, results.albums[0].copies.size)
    }

    @Test
    fun `an album's title finds the album rather than a namesake artist`() {
        val results = UnifiedSearch.merge(
            "ok computer",
            listOf(
                radiohead[0].copy(artists = emptyList()),
                radiohead[1].copy(artists = emptyList()),
                // YouTube has an obscure artist called "Ok Computer", with no songs here.
                SourceResults("youtube", false, artists = listOf(ArtistRef(YOUTUBE_MUSIC, "junk", "Ok Computer")), albums = listOf(AlbumRef(YOUTUBE_MUSIC, "yal", "OK Computer", "Radiohead"))),
            ),
        )
        val top = results.top as TopResult.OfAlbum
        assertEquals("OK Computer", top.album.title)
        assertEquals(3, top.album.copies.size)
    }

    @Test
    fun `a song's title finds the song, and title with artist does too`() {
        val creep = UnifiedSearch.merge("creep", radiohead.map { it.copy(artists = it.artists + ArtistRef(it.songs.first().source, "c", "Creep Show")) })
        assertEquals("Creep", ((creep.top as TopResult.OfSong).song.item.title))
        val both = UnifiedSearch.merge("creep radiohead", radiohead)
        assertEquals("Creep", ((both.top as TopResult.OfSong).song.item.title))
        assertEquals("home", (both.top as TopResult.OfSong).song.via)
    }

    @Test
    fun `nothing relevant means no top result`() {
        val results = UnifiedSearch.merge("zzz", listOf(SourceResults("q", false, songs = listOf(song(QOBUZ, "1", "Something", "Else")))))
        assertEquals(null, results.top)
        assertEquals(1, results.songs.size)
    }

    @Test
    fun `relevance prefers exact names, then all the words`() {
        assertEquals(3, UnifiedSearch.relevance("beatles", "The Beatles"))
        assertEquals(3, UnifiedSearch.relevance("ac dc", "AC/DC"))
        assertEquals(2, UnifiedSearch.relevance("radio", "Radiohead"))
        assertEquals(2, UnifiedSearch.relevance("ok comp", "OK Computer"))
        assertEquals(1, UnifiedSearch.relevance("creep radiohead", "Radiohead"))
        assertEquals(0, UnifiedSearch.relevance("creep", "Karma Police"))
    }

    @Test
    fun `an artist's pages from several sources become one`() {
        val page = UnifiedSearch.artistPage(
            listOf(
                Found("home", true, ArtistDetail(ArtistRef(JELLYFIN, "ja", "Radiohead"), listOf(song(JELLYFIN, "j1", "Creep", "Radiohead")), listOf(AlbumRef(JELLYFIN, "jal", "OK Computer", "Radiohead", 1997, trackCount = 12)))),
                Found(
                    "qobuz", false,
                    ArtistDetail(
                        ArtistRef(QOBUZ, "qa", "Radiohead", "qobuz-art"),
                        listOf(song(QOBUZ, "q2", "Karma Police", "Radiohead"), song(QOBUZ, "q1", "Creep", "Radiohead")),
                        listOf(
                            AlbumRef(QOBUZ, "qal", "OK Computer", "Radiohead", 1997, trackCount = 12),
                            AlbumRef(QOBUZ, "qkid", "Kid A", "Radiohead", 2000, trackCount = 10),
                            AlbumRef(QOBUZ, "qs", "Daydreaming", "Radiohead", 2016, trackCount = 1),
                        ),
                    ),
                ),
            ),
        )!!
        assertEquals("Radiohead", page.name)
        assertEquals("qobuz-art", page.artworkUrl)
        assertEquals(listOf("Creep", "Karma Police"), page.topSongs.map { it.item.title })
        assertEquals("home", page.topSongs[0].via)
        assertEquals(listOf("Kid A", "OK Computer"), page.albums.map { it.title })
        assertEquals(listOf("Daydreaming"), page.singles.map { it.title })
    }

    @Test
    fun `an album page has the fullest track list, with the library's copies`() {
        val library = AlbumDetail(AlbumRef(JELLYFIN, "jal", "OK Computer", "Radiohead", 1997), listOf(song(JELLYFIN, "j2", "Paranoid Android", "Radiohead")))
        val qobuz = AlbumDetail(
            AlbumRef(QOBUZ, "qal", "OK Computer", "Radiohead", 1997, "art", 3),
            listOf(song(QOBUZ, "q1", "Airbag", "Radiohead"), song(QOBUZ, "q2", "Paranoid Android", "Radiohead"), song(QOBUZ, "q3", "Subterranean Homesick Alien", "Radiohead")),
        )
        val page = UnifiedSearch.albumPage(listOf(Found("home", true, library), Found("qobuz", false, qobuz)))!!
        assertEquals(listOf("qobuz", "home", "qobuz"), page.tracks.map { it.via })
        assertEquals("art", page.album.artworkUrl)
        // A complete library copy is used as it is.
        val complete = library.copy(tracks = qobuz.tracks.map { it.copy(source = JELLYFIN) })
        assertTrue(UnifiedSearch.albumPage(listOf(Found("home", true, complete), Found("qobuz", false, qobuz)))!!.tracks.all { it.via == "home" })
    }
}
