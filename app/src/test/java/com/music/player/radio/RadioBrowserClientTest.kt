package com.music.player.radio

import com.music.player.model.SourceType
import com.music.player.sources.radio.RadioBrowserClient
import com.music.player.sources.radio.RadioStation
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RadioBrowserClientTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()

    private val stations = """[
        {"stationuuid":"s1","name":" WXYZ 101.1 ","url":"http://a/pls","url_resolved":"http://stream/a.mp3",
         "favicon":"http://img/a.png","country":"United States","countrycode":"US","state":"New Hampshire",
         "tags":"rock,classic rock","codec":"MP3","bitrate":128,"hls":0,"geo_lat":43.2,"geo_long":-71.5},
        {"stationuuid":"s2","name":"Public Radio","url":"https://hls/live.m3u8","url_resolved":"","hls":1},
        {"stationuuid":"s3","name":"No stream","url":"","url_resolved":""}
    ]"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.requestUrl!!.encodedPath) {
                    "/json/stations/search" -> MockResponse().setBody(stations)
                    "/json/stations/byuuid/s9" -> MockResponse().setBody("""[{"stationuuid":"s9","name":"Late","url_resolved":"http://s9"}]""")
                    "/json/url/s1" -> MockResponse().setBody("""{"ok":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(servers: List<String> = listOf("${server.hostName}:${server.port}")) =
        RadioBrowserClient(OkHttpClient(), userAgent = "MusicApp/test", lookUpServers = { servers }, scheme = "http")

    @Test
    fun `nearby stations are searched by location, most played first, without broken ones`() = runTest {
        val found = client().nearby(43.0, -71.0, radiusKm = 50)
        // The station without a stream address is left out.
        assertEquals(listOf("s1", "s2"), found.map { it.id })
        val url = requests.single().requestUrl!!
        assertEquals("43.0", url.queryParameter("geo_lat"))
        assertEquals("-71.0", url.queryParameter("geo_long"))
        assertEquals("50000", url.queryParameter("geo_distance"))
        assertEquals("clickcount", url.queryParameter("order"))
        assertEquals("true", url.queryParameter("reverse"))
        assertEquals("true", url.queryParameter("hidebroken"))
        assertEquals("MusicApp/test", requests.single().getHeader("User-Agent"))
    }

    @Test
    fun `the next server is tried when one fails`() = runTest {
        val found = client(servers = listOf("127.0.0.1:1", "${server.hostName}:${server.port}")).search("wxyz")
        assertEquals(2, found.size)
        assertEquals("wxyz", requests.single().requestUrl!!.queryParameter("name"))
    }

    @Test
    fun `stations seen in a listing are played without asking again, others are looked up`() = runTest {
        val client = client()
        client.search("wxyz")
        requests.clear()
        assertEquals("http://stream/a.mp3", client.station("s1").streamUrl)
        assertTrue(requests.isEmpty())
        assertEquals("http://s9", client.station("s9").streamUrl)
        client.countPlay("s1")
        assertEquals("/json/url/s1", requests.last().requestUrl!!.encodedPath)
    }

    @Test
    fun `stations become live tracks that remember whether they're HLS`() = runTest {
        val (mp3, hls) = client().search("x")
        val track = mp3.toTrack()
        assertEquals(SourceType.RADIO, track.source)
        assertEquals("WXYZ 101.1", track.title)
        assertEquals("New Hampshire · United States", track.artist)
        assertEquals("Rock", track.album)
        assertEquals("MP3 128 kbps", mp3.format)
        assertTrue(track.isLive)
        assertEquals("s1" to false, RadioStation.idOf(track))
        assertEquals("s2" to true, RadioStation.idOf(hls.toTrack()))
        assertEquals("https://hls/live.m3u8", hls.streamUrl)
    }
}
