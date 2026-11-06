package com.music.player.lidarr

import com.music.player.data.LidarrRequestDao
import com.music.player.data.LidarrRequestEntity
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.settings.LidarrConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import java.util.concurrent.TimeUnit

class LidarrQueueTest {
    private class InMemoryQueue : LidarrRequestDao {
        val rows = MutableStateFlow<Map<String, LidarrRequestEntity>>(emptyMap())
        override fun observeAll(): Flow<List<LidarrRequestEntity>> = MutableStateFlow(rows.value.values.toList())
        override suspend fun all() = rows.value.values.sortedBy { it.requestedAt }
        override suspend fun enqueue(request: LidarrRequestEntity) {
            if (request.artistKey !in rows.value) rows.value = rows.value + (request.artistKey to request)
        }
        override suspend fun recordFailure(key: String, error: String, at: Long) {
            rows.value[key]?.let { rows.value = rows.value + (key to it.copy(attempts = it.attempts + 1, lastError = error)) }
        }
        override suspend fun delete(key: String) {
            rows.value = rows.value - key
        }
    }

    private lateinit var server: MockWebServer
    private val queue = InMemoryQueue()
    private val posted = mutableListOf<String>()
    private var libraryArtists = "[]"
    private var down = false
    private var trackFiles = 0
    private val added = object : com.music.player.data.LidarrAddedDao {
        val rows = mutableMapOf<Int, com.music.player.data.LidarrAddedEntity>()
        override suspend fun upsert(artist: com.music.player.data.LidarrAddedEntity) { rows[artist.artistId] = artist }
        override suspend fun all() = rows.values.toList()
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(rows.values.toList())
        override suspend fun delete(artistId: Int) { rows.remove(artistId) }
    }
    private var statusChecks = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                return when {
                    path == "/api/v1/system/status" -> {
                        statusChecks++
                        if (down) MockResponse().setResponseCode(503) else MockResponse().setBody("""{"version":"2.9.6"}""")
                    }
                    path == "/api/v1/artist" && request.method == "GET" -> MockResponse().setBody(libraryArtists)
                    path == "/api/v1/artist/lookup" ->
                        MockResponse().setBody("""[{"artistName":"Daft Punk","foreignArtistId":"056e4f3e"}]""")
                    path == "/api/v1/rootfolder" -> MockResponse().setBody("""[{"id":1,"path":"/music","defaultQualityProfileId":2,"defaultMetadataProfileId":3}]""")
                    path == "/api/v1/qualityprofile" -> MockResponse().setBody("""[{"id":2,"name":"Lossless"}]""")
                    path == "/api/v1/metadataprofile" -> MockResponse().setBody("""[{"id":3,"name":"Standard"}]""")
                    path == "/api/v1/artist/9" -> MockResponse().setBody("""{"id":9,"artistName":"Daft Punk","statistics":{"trackFileCount":$trackFiles}}""")
                    path == "/api/v1/artist" && request.method == "POST" -> {
                        posted += request.body.readUtf8()
                        MockResponse().setBody("""{"id":9,"artistName":"Daft Punk"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = runCatching { server.shutdown() }.let { }

    private var now = 1_000_000L

    private fun TestScope.service(url: String) = LidarrService(
        client = LidarrClient(OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build()),
        config = { LidarrConfig(serverUrl = url, apiKey = "key") },
        queue = queue,
        scope = this,
        clock = { now },
        added = added,
    )

    private val song = Track(SourceType.SIRIUSXM, "song:1", "Get Lucky", "Daft Punk feat. Pharrell Williams")

    @Test
    fun `requests wait in the queue while Lidarr is unreachable`() = runTest {
        val url = server.url("/").toString()
        server.shutdown()
        val lidarr = service(url)
        lidarr.request(song)
        assertEquals(listOf("Daft Punk"), queue.all().map { it.artistName })
        assertTrue(lidarr.reachability.value is LidarrReachability.Unreachable)
    }

    @Test
    fun `queued requests are sent once Lidarr is reachable`() = runTest {
        queue.enqueue(LidarrRequestEntity("daft punk", "Daft Punk", "Get Lucky", requestedAt = 1))
        val lidarr = service(server.url("/").toString())
        assertEquals(1, lidarr.process())
        assertTrue(queue.all().isEmpty())
        assertEquals(1, posted.size)
        assertTrue(posted.single().contains("\"rootFolderPath\":\"/music\""))
        assertTrue(lidarr.reachability.value is LidarrReachability.Reachable)
    }

    @Test
    fun `artists Lidarr already has are dequeued without being added`() = runTest {
        libraryArtists = """[{"id":4,"artistName":"Daft Punk"}]"""
        val lidarr = service(server.url("/").toString())
        lidarr.request(song)
        assertTrue(queue.all().isEmpty())
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `while lidarr is unreachable, new requests wait instead of each trying again`() = runTest {
        down = true
        val lidarr = service(server.url("/").toString())
        lidarr.request(song)
        assertEquals(1, statusChecks)
        lidarr.request(Track(SourceType.YOUTUBE_MUSIC, "y1", "Stick Season", "Noah Kahan"))
        assertEquals(1, statusChecks)
        assertEquals(2, queue.all().size)

        // A while later, it's tried again (and everything queued is sent).
        now += 6 * 60 * 1000L
        down = false
        lidarr.request(Track(SourceType.YOUTUBE_MUSIC, "y2", "Scarlett", "Holly Humberstone"))
        assertEquals(2, statusChecks)
        assertTrue(queue.all().isEmpty())
    }

    @Test
    fun `added artists are watched until lidarr has their music`() = runTest {
        val lidarr = service(server.url("/").toString())
        val events = mutableListOf<LidarrEvent>()
        val collecting = launch { lidarr.events.collect { events += it } }
        lidarr.request(song)
        kotlinx.coroutines.yield()
        assertEquals(listOf(9), added.rows.keys.toList())

        // Later, once Lidarr has downloaded something, you're told and the watch ends.
        trackFiles = 12
        now += 20 * 60 * 1000L
        lidarr.process()
        kotlinx.coroutines.yield()
        assertTrue(added.rows.isEmpty())
        assertTrue(events.any { it is LidarrEvent.ArtistDownloaded && it.artist == "Daft Punk" })
        collecting.cancel()
    }
}
