package com.music.player.browse

import com.music.player.model.SourceType
import com.music.player.settings.QobuzConfig
import com.music.player.sources.qobuz.QobuzSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class QobuzBrowseTest {
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (request.getHeader("X-User-Auth-Token") != "token") return MockResponse().setResponseCode(401)
                val body = when (url.encodedPath) {
                    "/api/artist/search" -> """{"artists":{"items":[{"id":56,"name":"Radiohead","image":{"large":"https://img/rh.jpg"}}]}}"""
                    "/api/album/search" -> """{"albums":{"items":[
                        {"id":"0634904078164","title":"OK Computer","version":"Remastered","artist":{"name":"Radiohead"},"image":{"large":"https://img/okc.jpg"},"release_date_original":"1997-05-21","tracks_count":12},
                        {"id":"x","title":"Gone","artist":{"name":"Radiohead"},"streamable":false}]}}"""
                    "/api/album/get" -> """{"id":"0634904078164","title":"OK Computer","artist":{"name":"Radiohead"},"image":{"large":"https://img/okc.jpg"},"tracks_count":2,
                        "tracks":{"items":[{"id":1,"title":"Airbag","duration":287,"performer":{"name":"Radiohead"}},{"id":2,"title":"Paranoid Android","duration":387,"performer":{"name":"Radiohead"}}]}}"""
                    "/api/artist/get" -> """{"id":56,"name":"Radiohead","albums":{"items":[{"id":"a1","title":"Kid A","artist":{"name":"Radiohead"},"release_date_original":"2000-10-02","tracks_count":10}]}}"""
                    "/api/track/search" -> """{"tracks":{"items":[{"id":3,"title":"Creep","performer":{"name":"Radiohead"},"album":{"title":"Pablo Honey"}},{"id":4,"title":"Creep","performer":{"name":"TLC"}}]}}"""
                    "/api/playlist/get" -> {
                        val offset = url.queryParameter("offset")!!.toInt()
                        val items = (offset until minOf(offset + 2, 3)).joinToString(",") { """{"id":$it,"title":"Song $it","performer":{"name":"A"},"album":{"title":"B"}}""" }
                        """{"name":"Jazz","tracks":{"total":3,"items":[$items]}}"""
                    }
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        start()
    }

    private val qobuz = QobuzSource(OkHttpClient(), server.url("/api/").toString()) { QobuzConfig(appId = "app", appSecret = "s", username = "u", userAuthToken = "token") }

    @After
    fun stop() = server.shutdown()

    @Test
    fun `artists and albums are searched, and albums opened`() = runBlocking {
        assertEquals(listOf(ArtistRef(SourceType.QOBUZ, "56", "Radiohead", "https://img/rh.jpg")), qobuz.searchArtists("radiohead", 5))
        val album = qobuz.searchAlbums("ok computer", 5).single()
        assertEquals(AlbumRef(SourceType.QOBUZ, "0634904078164", "OK Computer (Remastered)", "Radiohead", 1997, "https://img/okc.jpg", 12), album)
        val detail = qobuz.album(album)
        assertEquals(listOf("Airbag", "Paranoid Android"), detail.tracks.map { it.title })
        assertEquals("OK Computer", detail.tracks[0].album)
        assertEquals("https://img/okc.jpg", detail.tracks[0].artworkUrl)
        assertEquals(287_000L, detail.tracks[0].durationMs)
    }

    @Test
    fun `an artist has their albums and their songs only`() = runBlocking {
        val artist = qobuz.artist(ArtistRef(SourceType.QOBUZ, "56", "Radiohead"))
        assertEquals(listOf("Kid A"), artist.albums.map { it.title })
        assertEquals(listOf("Radiohead"), artist.topTracks.map { it.artist })
    }

    @Test
    fun `playlists are read a page at a time`() = runBlocking {
        val (name, tracks) = qobuz.playlist("123")
        assertEquals("Jazz", name)
        assertEquals(listOf("Song 0", "Song 1", "Song 2"), tracks.map { it.title })
    }
}
