package com.music.player.sources.subsonic

import com.music.player.browse.AlbumRef
import com.music.player.browse.ArtistRef
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.quality.StreamQuality
import com.music.player.settings.SubsonicAuth
import com.music.player.settings.SubsonicConfig
import com.music.player.sources.ArtworkUrls
import com.music.player.sources.SourceUnavailableException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The Subsonic client against a pretend Navidrome. */
class SubsonicSourceTest {
    /** How the pretend server checks who's asking. */
    private var tokens = true
    private var passwords = true
    private var apiKeys = false

    /** Whether an empty search3 lists everything (as OpenSubsonic servers must). */
    private var emptySearchListsAll = true

    private val requests = CopyOnWriteArrayList<HttpUrl>()

    private val songs = listOf(
        song("s1", "Fool's Gold", "The Stone Roses", "al1", "The Stone Roses", 592, bitRate = 900, suffix = "flac", track = 11),
        song("s2", "I Wanna Be Adored", "The Stone Roses", "al1", "The Stone Roses", 293, bitRate = 320, suffix = "mp3", track = 1),
        song("s3", "Love Will Tear Us Apart", "Joy Division", "al2", "Substance", 205, bitRate = 128, suffix = "mp3", track = 1),
    )

    private fun song(id: String, title: String, artist: String, albumId: String, album: String, seconds: Int, bitRate: Int, suffix: String, track: Int) =
        """{"id":"$id","parent":"$albumId","isDir":false,"title":"$title","album":"$album","artist":"$artist","track":$track,"year":1989,
           "coverArt":"$albumId","size":1000,"contentType":"audio/$suffix","suffix":"$suffix","duration":$seconds,"bitRate":$bitRate,
           "albumId":"$albumId","artistId":"ar-${artist.length}","type":"music","isVideo":false,"playCount":${track * 3},
           "isrc":["GB${id.uppercase()}0000001"],"replayGain":{"trackGain":-7.5,"albumGain":-8.1},
           "artists":[{"id":"ar-${artist.length}","name":"$artist"}],"displayArtist":"$artist"}"""

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.53.3 (abc)","openSubsonic":true${if (body.isEmpty()) "" else ",$body"}}}""",
    )

    private fun failed(code: Int, message: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome","serverVersion":"0.53.3","openSubsonic":true,"error":{"code":$code,"message":"$message"}}}""",
    )

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                requests += url
                if (!url.encodedPath.startsWith("/music/rest/")) return MockResponse().setResponseCode(404)
                if (url.queryParameter("f") != "json" || url.queryParameter("v") == null || url.queryParameter("c") != "SAMM test") return failed(10, "Required parameter is missing")
                authFailure(url)?.let { return it }
                val endpoint = url.encodedPath.removePrefix("/music/rest/").removeSuffix(".view")
                fun p(name: String) = url.queryParameter(name)
                return when (endpoint) {
                    "ping" -> ok("")
                    "search3" -> {
                        val query = p("query").orEmpty().lowercase()
                        val songCount = p("songCount")?.toInt() ?: 20
                        val offset = p("songOffset")?.toInt() ?: 0
                        val found = when {
                            query.isEmpty() -> if (emptySearchListsAll) songs else emptyList()
                            else -> songs.filter { query.split(' ').all { word -> it.lowercase().contains(word) } }
                        }.drop(offset).take(songCount)
                        val artists = if ((p("artistCount")?.toInt() ?: 0) > 0 && "stone roses" in query) {
                            """"artist":[{"id":"ar-16","name":"The Stone Roses","coverArt":"ar-16","albumCount":1,"artistImageUrl":"https://lastfm.example/sr.jpg"}],"""
                        } else ""
                        val albums = if ((p("albumCount")?.toInt() ?: 0) > 0 && "stone roses" in query) {
                            """"album":{"id":"al1","name":"The Stone Roses","artist":"The Stone Roses","artistId":"ar-16","coverArt":"al1","songCount":2,"year":1989},"""
                        } else ""
                        ok(""""searchResult3":{$artists$albums"song":[${found.joinToString(",")}]}""")
                    }
                    "getAlbum" -> when (p("id")) {
                        "al1" -> ok(""""album":{"id":"al1","name":"The Stone Roses","artist":"The Stone Roses","artistId":"ar-16","coverArt":"al1","songCount":2,"year":1989,"song":[${songs[1]},${songs[0]}]}""")
                        "al2" -> ok(""""album":{"id":"al2","name":"Substance","artist":"Joy Division","coverArt":"al2","songCount":1,"year":1988,"song":${songs[2]}}""")
                        else -> failed(70, "Album not found")
                    }
                    "getAlbumList2" -> {
                        val offset = p("offset")?.toInt() ?: 0
                        val all = listOf("""{"id":"al1","name":"The Stone Roses"}""", """{"id":"al2","name":"Substance"}""")
                        ok(""""albumList2":{"album":[${all.drop(offset).take(p("size")!!.toInt()).joinToString(",")}]}""")
                    }
                    "getArtist" -> if (p("id") == "ar-16") {
                        ok(""""artist":{"id":"ar-16","name":"The Stone Roses","coverArt":"ar-16","albumCount":2,"album":[
                            {"id":"al0","name":"Garage Flower","artist":"The Stone Roses","year":1985},
                            {"id":"al1","name":"The Stone Roses","artist":"The Stone Roses","coverArt":"al1","songCount":2,"year":1989}]}""")
                    } else failed(70, "Artist not found")
                    "getTopSongs" -> ok(""""topSongs":{}""")
                    "getPlaylists" -> ok(""""playlists":{"playlist":[{"id":"pl1","name":"Madchester","songCount":2,"coverArt":"pl-pl1","owner":"maddie"}]}""")
                    "getPlaylist" -> if (p("id") == "pl1") ok(""""playlist":{"id":"pl1","name":"Madchester","songCount":2,"entry":[${songs[0]},${songs[2]}]}""") else failed(70, "Playlist not found")
                    "getSong" -> songs.firstOrNull { "\"id\":\"${p("id")}\"" in it }?.let { ok(""""song":$it""") } ?: failed(70, "Song not found")
                    else -> failed(0, "Unknown endpoint")
                }
            }
        }
        start()
    }

    /** The pretend server's answer to credentials it doesn't take, or null when they're good. */
    private fun authFailure(url: HttpUrl): MockResponse? {
        val key = url.queryParameter("apiKey")
        val user = url.queryParameter("u")
        if (key != null) {
            if (user != null) return failed(43, "Multiple conflicting authentication mechanisms provided")
            if (!apiKeys) return failed(42, "Provided authentication mechanism not supported")
            return if (key == "the-key") null else failed(44, "Invalid API key")
        }
        if (user != "maddie") return failed(40, "Wrong username or password")
        val token = url.queryParameter("t")
        val salt = url.queryParameter("s")
        val password = url.queryParameter("p")
        return when {
            token != null && salt != null -> when {
                !tokens -> failed(41, "Token authentication not supported for LDAP users")
                token == SubsonicSource.token("hunter22", salt) -> null
                else -> failed(40, "Wrong username or password")
            }
            password != null -> when {
                !passwords -> failed(42, "Provided authentication mechanism not supported")
                password == "enc:" + "hunter22".toByteArray().joinToString("") { "%02x".format(it) } -> null
                else -> failed(40, "Wrong username or password")
            }
            else -> failed(10, "Required parameter is missing")
        }
    }

    private val base = server.url("/music").toString().trimEnd('/')
    private val good = SubsonicConfig(serverUrl = base, username = "maddie", password = "hunter22")

    private fun source(config: SubsonicConfig = good) = SubsonicSource(OkHttpClient(), config = { config }, clientName = "SAMM test")

    @After
    fun stop() = server.shutdown()

    @Test
    fun `requests are signed with a fresh salted token, never the password`() = runBlocking {
        val subsonic = source()
        subsonic.search("gold", 10)
        subsonic.search("gold", 10)
        val asked = requests.filter { it.encodedPath.endsWith("search3.view") }
        assertEquals(2, asked.size)
        for (url in asked) {
            assertEquals("maddie", url.queryParameter("u"))
            assertEquals(SubsonicSource.API_VERSION, url.queryParameter("v"))
            assertEquals("json", url.queryParameter("f"))
            assertNull(url.queryParameter("p"))
            assertNull(url.queryParameter("apiKey"))
            assertFalse("hunter22" in url.toString())
            assertEquals(SubsonicSource.token("hunter22", url.queryParameter("s")!!), url.queryParameter("t"))
        }
        assertTrue("a new salt each time", asked[0].queryParameter("s") != asked[1].queryParameter("s"))
    }

    @Test
    fun `songs are mapped, with covers that carry no credentials`() = runBlocking {
        val found = source().search("fool's gold", 10).single()
        assertEquals(SourceType.SUBSONIC, found.source)
        assertEquals("s1", found.sourceId)
        assertEquals("Fool's Gold", found.title)
        assertEquals("The Stone Roses", found.artist)
        assertEquals("The Stone Roses", found.album)
        assertEquals(592_000L, found.durationMs)
        assertEquals("GBS10000001", found.isrc)
        val cover = found.artworkUrl!!.toHttpUrl()
        assertEquals("/music/rest/getCoverArt.view", cover.encodedPath)
        assertEquals("al1", cover.queryParameter("id"))
        assertEquals("512", cover.queryParameter("size"))
        for (secret in listOf("u", "t", "s", "p", "apiKey")) assertNull(secret, cover.queryParameter(secret))

        // Resized like the other services' artwork, and signed only when fetched.
        val small = ArtworkUrls.sized(found.artworkUrl!!, 100)
        assertEquals("128", small.toHttpUrl().queryParameter("size"))
        val signed = source().authorize(small)!!.toHttpUrl()
        assertEquals("al1", signed.queryParameter("id"))
        assertEquals("128", signed.queryParameter("size"))
        assertEquals(SubsonicSource.token("hunter22", signed.queryParameter("s")!!), signed.queryParameter("t"))
        // Only this server's covers.
        assertNull(source().authorize("https://elsewhere.example/music/rest/getCoverArt.view?id=al1"))
        assertNull(source().authorize("$base/rest/stream.view?id=s1"))
    }

    @Test
    fun `artists, albums and playlists are browsed`() = runBlocking {
        val subsonic = source()
        val artist = subsonic.searchArtists("stone roses", 5).single()
        assertEquals("The Stone Roses", artist.name)
        assertEquals("ar-16", artist.artworkUrl!!.toHttpUrl().queryParameter("id"))

        // A lone album, sent as itself rather than in a list, still counts; the artist's own albums join it.
        val albums = subsonic.searchAlbums("stone roses", 5)
        assertEquals(listOf("al1", "al0"), albums.map { it.id })
        assertEquals(AlbumRef(SourceType.SUBSONIC, "al1", "The Stone Roses", "The Stone Roses", 1989, "$base/rest/getCoverArt.view?id=al1&size=512", 2), albums[0])

        val album = subsonic.album(AlbumRef(SourceType.SUBSONIC, "al1", "The Stone Roses", "The Stone Roses"))
        assertEquals(listOf("I Wanna Be Adored", "Fool's Gold"), album.tracks.map { it.title })
        assertEquals(1989, album.album.year)
        val single = subsonic.album(AlbumRef(SourceType.SUBSONIC, "al2", "Substance", "Joy Division"))
        assertEquals(listOf("Love Will Tear Us Apart"), single.tracks.map { it.title })

        // No top songs from the server: the most played of their latest albums instead.
        val page = subsonic.artist(ArtistRef(SourceType.SUBSONIC, "ar-16", "The Stone Roses"))
        assertEquals(listOf("The Stone Roses", "Garage Flower"), page.albums.map { it.title })
        assertEquals(listOf("Fool's Gold", "I Wanna Be Adored"), page.topTracks.map { it.title })

        val playlist = subsonic.playlists().single()
        assertEquals(SubsonicPlaylist("pl1", "Madchester", 2, "$base/rest/getCoverArt.view?id=pl-pl1&size=512"), playlist)
        assertEquals(listOf("Fool's Gold", "Love Will Tear Us Apart"), subsonic.playlist("pl1").second.map { it.title })
    }

    @Test
    fun `songs from elsewhere are found in the library, by title or among the artist's albums`() = runBlocking {
        val subsonic = source()
        val byTitle = Track(SourceType.YOUTUBE_MUSIC, "yt2", "I Wanna Be Adored (Remastered)", "Stone Roses", durationMs = 293_000)
        assertEquals("s2", subsonic.lookUp(byTitle)?.sourceId)
        // Without the apostrophe this pretend server's title search misses: found among the artist's albums.
        val spelt = Track(SourceType.YOUTUBE_MUSIC, "yt1", "Fools Gold", "The Stone Roses", durationMs = 590_000)
        val before = requests.size
        assertEquals("s1", subsonic.lookUp(spelt)?.sourceId)
        assertTrue(requests.drop(before).any { it.encodedPath.endsWith("getAlbum.view") })
        assertEquals("s1", subsonic.findMatch(spelt)?.sourceId)
        // A Subsonic song stays as it is.
        val own = subsonic.search("adored", 5).single()
        assertEquals(own, subsonic.findMatch(own))
        assertNull(subsonic.lookUp(Track(SourceType.YOUTUBE_MUSIC, "yt3", "Creep", "Radiohead")))
    }

    @Test
    fun `streams are the original file unless it's over the cap`() = runBlocking {
        val subsonic = source()
        val original = subsonic.stream("s1", StreamQuality.Original)
        val url = original.url.toHttpUrl()
        assertEquals("/music/rest/stream.view", url.encodedPath)
        assertEquals("s1", url.queryParameter("id"))
        assertEquals("raw", url.queryParameter("format"))
        assertNull(url.queryParameter("maxBitRate"))
        assertTrue(original.supportsRangeRequests)
        assertEquals("flac", original.codec)
        assertEquals("flac", original.fileExtension)
        assertEquals(900, original.kbps)
        assertEquals(-7.5f, original.gainDb)
        assertEquals("FLAC (original file)", original.description)

        val capped = subsonic.stream("s1", StreamQuality.Capped(128))
        val cappedUrl = capped.url.toHttpUrl()
        assertEquals("mp3", cappedUrl.queryParameter("format"))
        assertEquals("128", cappedUrl.queryParameter("maxBitRate"))
        assertFalse(capped.supportsRangeRequests)
        assertEquals(128, capped.kbps)
        assertEquals("MP3 128 kbps (transcoded)", capped.description)

        // Already under the cap: as it is.
        val small = subsonic.stream("s3", StreamQuality.Capped(192))
        assertEquals("raw", small.url.toHttpUrl().queryParameter("format"))
        assertEquals("mp3", small.codec)

        val gone = assertThrows(SourceUnavailableException::class.java) { runBlocking { subsonic.stream("nope", StreamQuality.Original) } }
        assertEquals("Song is no longer in the library", gone.message)
    }

    @Test
    fun `the whole library is listed a page at a time, album by album where an empty search finds nothing`() = runBlocking {
        fun all(subsonic: SubsonicSource): List<String> {
            val ids = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                val page = runBlocking { subsonic.songs(cursor) }
                ids += page.tracks.map { it.sourceId }
                cursor = page.next
                assertTrue("too many pages", ++pages < 10)
            } while (cursor != null)
            return ids
        }
        assertEquals(listOf("s1", "s2", "s3"), all(source()))
        emptySearchListsAll = false
        assertEquals(listOf("s2", "s1", "s3"), all(source()))
    }

    @Test
    fun `signing in says what the server is, and falls back to the password where tokens aren't taken`() = runBlocking {
        val (config, server) = source().connect(good)
        assertEquals(SubsonicAuth.TOKEN, config.auth)
        assertEquals("Navidrome 0.53.3 (abc)", server.description)
        assertEquals("Navidrome", server.displayName)
        assertTrue(server.openSubsonic)

        // An LDAP account on Navidrome, say: no tokens, so the password itself (hex-encoded).
        tokens = false
        val (fallback, _) = source().connect(good)
        assertEquals(SubsonicAuth.PASSWORD, fallback.auth)
        assertEquals("enc:68756e7465723232", requests.last().queryParameter("p"))
        // A source still set to tokens learns that too, and carries on.
        assertEquals(1, source().search("gold", 5).size)

        // Neither tokens nor passwords: it wants an API key.
        passwords = false
        val refused = assertThrows(SubsonicException::class.java) { runBlocking { source().connect(good) } }
        assertTrue(refused.message, "API key" in refused.message!!)

        apiKeys = true
        val (keyed, _) = source().connect(SubsonicConfig(serverUrl = base, apiKey = "the-key"))
        assertEquals(SubsonicAuth.API_KEY, keyed.auth)
        assertEquals("the-key", requests.last().queryParameter("apiKey"))
        assertNull(requests.last().queryParameter("u"))
        assertEquals(1, source(keyed).search("gold", 5).size)
    }

    @Test
    fun `the server's errors are said plainly`() = runBlocking {
        val wrong = assertThrows(SubsonicException::class.java) { runBlocking { source().connect(good.copy(password = "nope")) } }
        assertEquals(40, wrong.code)
        assertEquals("Wrong username or password", wrong.message)

        apiKeys = true
        val badKey = assertThrows(SubsonicException::class.java) { runBlocking { source().connect(SubsonicConfig(serverUrl = base, apiKey = "wrong")) } }
        assertEquals("The server didn't accept that API key", badKey.message)

        assertEquals("This account isn't allowed to do that: Streaming is not allowed", SubsonicSource.messageFor(50, "Streaming is not allowed"))
        assertTrue(SubsonicSource.messageFor(41, null).contains("LDAP"))

        // Not a Subsonic server at all.
        val elsewhere = SubsonicSource(OkHttpClient(), config = { good.copy(serverUrl = server.url("/").toString().trimEnd('/')) }, clientName = "SAMM test")
        val notSubsonic = assertThrows(Exception::class.java) { runBlocking { elsewhere.ping() } }
        assertNotNull(notSubsonic.message)
    }
}
