package com.music.player.lyrics

import androidx.test.core.app.ApplicationProvider
import com.music.player.MusicApp
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.requestUrl!!
                return when (url.encodedPath) {
                    "/api/get" -> when (url.queryParameter("track_name")) {
                        "Stick Season" -> MockResponse().setBody(
                            """{"trackName":"Stick Season","duration":182,"instrumental":false,"syncedLyrics":"[00:12.50] As you promised\n[00:15.00][01:15.00] Me that I would see you"}""",
                        )
                        else -> MockResponse().setResponseCode(404)
                    }
                    "/api/search" -> MockResponse().setBody(
                        """[{"trackName":"Scarlett","duration":300,"plainLyrics":"wrong one"},{"trackName":"Scarlett","duration":201,"plainLyrics":"Line one\nLine two"}]""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repository() = LyricsRepository(
        OkHttpClient(),
        ApplicationProvider.getApplicationContext<MusicApp>().container.jellyfin,
        userAgent = "Test/1.0",
        baseUrl = server.url("/").toString(),
    )

    @Test
    fun `lrc lines are parsed in time order, repeated lines included`() {
        val lines = LyricsRepository.parseLrc("[ti:Song]\n[00:12.50] A\n[00:15.00][01:15.00] B\n[00:01.2]C")
        assertEquals(listOf(1_200L, 12_500L, 15_000L, 75_000L), lines.map { it.timeMs })
        assertEquals(listOf("C", "A", "B", "B"), lines.map { it.text })
        val lyrics = Lyrics(lines, "test")
        assertEquals(-1, lyrics.lineAt(500))
        assertEquals(1, lyrics.lineAt(13_000))
    }

    @Test
    fun `synced lyrics come from lrclib, asked with title, artist and length`() = runBlocking {
        val song = Track(SourceType.YOUTUBE_MUSIC, "y1", "Stick Season", "Noah Kahan", durationMs = 182_000)
        val lyrics = repository().find(song)!!
        assertTrue(lyrics.synced)
        assertEquals("As you promised", lyrics.lines.first().text)
        val asked = requests.single().requestUrl!!
        assertEquals("Noah Kahan", asked.queryParameter("artist_name"))
        assertEquals("182", asked.queryParameter("duration"))
        assertEquals("Test/1.0", requests.single().getHeader("User-Agent"))
    }

    @Test
    fun `without an exact match, the search result closest in length is used`() = runBlocking {
        val song = Track(SourceType.YOUTUBE_MUSIC, "y2", "Scarlett", "Holly Humberstone", durationMs = 200_000)
        val lyrics = repository().find(song)!!
        assertEquals(listOf("Line one", "Line two"), lyrics.lines.map { it.text })
    }

    @Test
    fun `songs without lyrics are only looked up once`() = runBlocking {
        val repository = repository()
        val song = Track(SourceType.YOUTUBE_MUSIC, "y3", "Nothing Here", "Nobody")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return if (request.requestUrl!!.encodedPath == "/api/search") MockResponse().setBody("[]") else MockResponse().setResponseCode(404)
            }
        }
        assertNull(repository.find(song))
        val asked = requests.size
        assertNull(repository.find(song))
        assertEquals(asked, requests.size)
    }
}
