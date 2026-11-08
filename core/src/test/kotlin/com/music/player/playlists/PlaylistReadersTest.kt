package com.music.player.playlists

import com.music.player.model.SourceType
import com.music.player.model.Track
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistReadersTest {
    private val requests = mutableListOf<String>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                synchronized(requests) { requests += path }
                return when {
                    path == "/deezer/playlist/908622995" -> json("""{"id":908622995,"title":"En mode 60","nb_tracks":3,"tracks":{"data":[]}}""")
                    path == "/deezer/playlist/908622995/tracks?index=0&limit=100" -> json(
                        """{"data":[{"title":"Hey Jude (Remastered 2015)","duration":429,"isrc":"GBAYE0601690","artist":{"name":"The Beatles"},"album":{"title":"1 (Remastered 2015)"}},
                           {"title":"Respect","duration":147,"artist":{"name":"Aretha Franklin"},"album":{"title":"I Never Loved a Man"}}],
                           "total":3,"next":"https://api.deezer.com/playlist/908622995/tracks?index=2&limit=100"}""",
                    )
                    path == "/deezer/playlist/908622995/tracks?index=2&limit=100" -> json(
                        """{"data":[{"title":"Sweet Caroline","duration":203,"artist":{"name":"Neil Diamond"},"album":{"title":"Brother Love's Travelling Salvation Show"}}],"total":3}""",
                    )
                    path == "/deezer/playlist/1" -> json("""{"error":{"type":"DataException","message":"no data","code":800}}""")
                    path.startsWith("/apple/us/playlist/a-list-pop/pl.5ee8333dbe944d9f9151e97d92d1ead9") -> MockResponse().setBody(resource("apple-music.html"))
                    path == "/tidal/playlists/1b418bb8-90a7-4f87-901d-707993838346" -> MockResponse().setBody(resource("tidal-embed.html"))
                    path == "/lb/playlist/5bb057eb-b38a-4a8d-8218-c5004f052fb2" -> json(resource("listenbrainz.json"))
                    path == "/spotify/album/6dVIqQ8qmQ5GBnJ9shOYGE" -> MockResponse().setBody(
                        """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"type":"album","name":"OK Computer",""" +
                            """"trackList":[{"title":"Airbag","subtitle":"Radiohead","duration":287880}]}}}}}}</script>""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }

    private val readers = PlaylistReaders(
        OkHttpClient(),
        deezerApi = server.url("/deezer/").toString(),
        tidalEmbed = server.url("/tidal/").toString(),
        appleMusic = server.url("/apple/").toString(),
        listenBrainzApi = server.url("/lb/").toString(),
        spotifyEmbed = server.url("/spotify/").toString(),
    )

    @After
    fun stop() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun resource(name: String) = javaClass.getResource("/playlists/$name")!!.readText()

    @Test
    fun `links are recognised by service`() {
        assertEquals(PlaylistService.SPOTIFY, PlaylistReaders.serviceOf("https://open.spotify.com/album/6dVIqQ8qmQ5GBnJ9shOYGE?si=x"))
        assertEquals(PlaylistService.APPLE_MUSIC, PlaylistReaders.serviceOf("https://music.apple.com/us/playlist/a-list-pop/pl.5ee8333dbe944d9f9151e97d92d1ead9"))
        assertEquals(PlaylistService.TIDAL, PlaylistReaders.serviceOf("https://tidal.com/browse/playlist/1b418bb8-90a7-4f87-901d-707993838346"))
        assertEquals(PlaylistService.DEEZER, PlaylistReaders.serviceOf("https://link.deezer.com/s/30ZnS9nQd9N7xXDZqFcMr"))
        assertEquals(PlaylistService.LISTENBRAINZ, PlaylistReaders.serviceOf("https://listenbrainz.org/playlist/5bb057eb-b38a-4a8d-8218-c5004f052fb2/"))
        assertEquals(PlaylistService.QOBUZ, PlaylistReaders.serviceOf("https://open.qobuz.com/playlist/23113522"))
        assertEquals(PlaylistService.YOUTUBE, PlaylistReaders.serviceOf("https://music.youtube.com/playlist?list=PL1"))
        assertNull(PlaylistReaders.serviceOf("Radiohead - Creep"))

    }

    @Test
    fun `a qobuz link says how to import it instead`() = runBlocking {
        val error = runCatching { readers.read("https://open.qobuz.com/playlist/23113522") }.exceptionOrNull()
        assertEquals(PlaylistReaders.QOBUZ_UNREADABLE, error?.message)
        assertTrue(synchronized(requests) { requests.isEmpty() })
    }

    @Test
    fun `a deezer playlist is read a page at a time`() = runBlocking {
        val read = readers.read("https://www.deezer.com/fr/playlist/908622995")
        assertEquals("En mode 60", read.name)
        assertEquals(listOf("Hey Jude (Remastered 2015)", "Respect", "Sweet Caroline"), read.songs.map { it.title })
        assertEquals(ListedSong("Hey Jude (Remastered 2015)", "The Beatles", 429_000, "1 (Remastered 2015)", "GBAYE0601690"), read.songs[0])
        val missing = runCatching { readers.read("https://www.deezer.com/playlist/1") }.exceptionOrNull()
        assertTrue(missing?.message, missing?.message?.contains("private") == true)
        // Only Deezer's own short links are followed: nothing else is fetched for a look-alike.
        val lookAlike = runCatching { readers.read(server.url("/evil?link.deezer.com/").toString()) }.exceptionOrNull()
        assertTrue(lookAlike?.message, lookAlike?.message?.contains("isn't a playlist") == true)
        assertTrue(synchronized(requests) { requests.none { it.startsWith("/evil") } })
    }

    @Test
    fun `an apple music playlist is read from its page`() = runBlocking {
        val read = readers.read("https://music.apple.com/us/playlist/a-list-pop/pl.5ee8333dbe944d9f9151e97d92d1ead9")
        assertEquals("A-List Pop", read.name)
        assertEquals(listOf("Angel Of My Dreams", "Blue Moon", "Housewife"), read.songs.map { it.title })
        assertEquals(ListedSong("Angel Of My Dreams", "JADE", 197_639), read.songs[0])
    }

    @Test
    fun `a tidal playlist is read from its embeddable player`() = runBlocking {
        val read = readers.read("https://tidal.com/browse/playlist/1b418bb8-90a7-4f87-901d-707993838346")
        assertEquals("New Arrivals", read.name)
        assertEquals(3, read.songs.size)
        assertEquals(ListedSong("take me home (4 christmas)", "Olivia Rodrigo", 178_000), read.songs[0])
        assertEquals("Ya Hit (feat. Drake, Travis Scott & Loe Shimmy)", read.songs[1].title)
        assertEquals("Future, Drake, Travis Scott, Loe Shimmy", read.songs[1].artist)
        assertNull(read.note)
    }

    @Test
    fun `listenbrainz playlists and spotify albums are read`() = runBlocking {
        val lb = readers.read("https://listenbrainz.org/playlist/5bb057eb-b38a-4a8d-8218-c5004f052fb2")
        assertEquals("Claypool Hour", lb.name)
        assertEquals(ListedSong("John the Fisherman", "Primus", 217_600, "Frizzle Fry"), lb.songs[0])
        assertEquals(3, lb.songs.size)
        val album = readers.read("https://open.spotify.com/album/6dVIqQ8qmQ5GBnJ9shOYGE")
        assertEquals("OK Computer", album.name)
        assertEquals("Airbag", album.songs.single().title)
    }

    @Test
    fun `typed lists are read line by line`() {
        val songs = PlaylistReaders.parseText(
            """
            1. Radiohead - Creep
            2) Fleetwood Mac – Dreams (4:17)
            3 - Phil Collins - In the Air Tonight
            311 - Amber
            Stand by Me
            Paranoid Android by Radiohead
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Triple("Creep", "Radiohead", null),
                Triple("Dreams", "Fleetwood Mac", 257_000L),
                Triple("In the Air Tonight", "Phil Collins", null),
                Triple("Amber", "311", null),
                // Taken apart like "Title by Artist", but the whole line is kept and matched as a title too.
                Triple("Stand", "Me", null),
                Triple("Paranoid Android", "Radiohead", null),
            ),
            songs.map { Triple(it.title, it.artist, it.durationMs) },
        )
        assertEquals("Stand by Me", songs[4].line)
    }

    @Test
    fun `csv exports are read by their columns`() {
        val exportify = PlaylistReaders.parseText(
            listOf(
                "\"Track URI\",\"Track Name\",\"Artist URI(s)\",\"Artist Name(s)\",\"Album Name\",\"Track Duration (ms)\"",
                "\"spotify:track:1\",\"Here\",\"spotify:artist:1\",\"Mumford & Sons, Chris Stapleton\",\"Rushmere\",\"191000\"",
                "\"spotify:track:2\",\"Say \"\"Hello\"\"\",\"spotify:artist:2\",\"Someone\",\"Album, With Comma\",\"200000\"",
            ).joinToString("\n"),
        )
        assertEquals(ListedSong("Here", "Mumford & Sons, Chris Stapleton", 191_000, "Rushmere"), exportify[0])
        assertEquals(ListedSong("Say \"Hello\"", "Someone", 200_000, "Album, With Comma"), exportify[1])
        val tuneMyMusic = PlaylistReaders.parseText("Track name,Artist name,Album,Playlist name,Type,ISRC\nCreep,Radiohead,Pablo Honey,Mine,Playlist,GBAYE9200070\n")
        assertEquals(ListedSong("Creep", "Radiohead", null, "Pablo Honey", "GBAYE9200070"), tuneMyMusic.single())
        val tabs = PlaylistReaders.parseText("title\tartist\tduration\nDreams\tFleetwood Mac\t4:17")
        assertEquals(ListedSong("Dreams", "Fleetwood Mac", 257_000), tabs.single())
    }

    @Test
    fun `imported songs are matched by isrc, title and artist, or a typed line`() {
        val library = Track(SourceType.JELLYFIN, "1", "Creep", "Radiohead", durationMs = 238_000)
        val qobuz = Track(SourceType.QOBUZ, "2", "Creep", "Radiohead", durationMs = 239_000, isrc = "GBAYE9200070")
        val other = Track(SourceType.QOBUZ, "3", "Creep", "TLC", durationMs = 269_000)
        assertEquals(library, ImportMatcher.pick(ListedSong("Creep", "Radiohead", 238_500), listOf(library, qobuz, other)))
        assertEquals(qobuz, ImportMatcher.pick(ListedSong("Creep (2008 Remaster)", "Someone else", null, isrc = "gbaye9200070"), listOf(other, qobuz)))
        // Typed the other way round.
        assertEquals(library, ImportMatcher.pick(ListedSong("Radiohead", "Creep", null, line = "Creep - Radiohead"), listOf(other, library)))
        // "Stand by Me" isn't "Stand" by "Me".
        val standByMe = Track(SourceType.QOBUZ, "4", "Stand by Me", "Ben E. King", durationMs = 180_000)
        val typed = PlaylistReaders.parseText("Stand by Me").single()
        assertEquals("Stand by Me", ImportMatcher.query(typed))
        assertEquals(standByMe, ImportMatcher.pick(PlaylistReaders.parseText("Stand by Me").single(), listOf(standByMe)))
        assertEquals(standByMe, ImportMatcher.pick(ListedSong("Stand", "Me", null, line = "Stand by Me"), listOf(standByMe)))
        assertNull(ImportMatcher.pick(ListedSong("Creep", "Radiohead", 238_000), listOf(other)))
        assertEquals("Radiohead Creep", ImportMatcher.query(ListedSong("Creep", "Radiohead", null, line = "Radiohead - Creep")))
    }
}
