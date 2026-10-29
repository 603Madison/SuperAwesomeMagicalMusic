package com.music.player.lidarr

import com.music.player.data.LidarrRequestDao
import com.music.player.data.LidarrRequestEntity
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.settings.LidarrConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                return when {
                    path == "/api/v1/system/status" -> MockResponse().setBody("""{"version":"2.9.6"}""")
                    path == "/api/v1/artist" && request.method == "GET" -> MockResponse().setBody(libraryArtists)
                    path == "/api/v1/artist/lookup" ->
                        MockResponse().setBody("""[{"artistName":"Daft Punk","foreignArtistId":"056e4f3e"}]""")
                    path == "/api/v1/rootfolder" -> MockResponse().setBody("""[{"id":1,"path":"/music","defaultQualityProfileId":2,"defaultMetadataProfileId":3}]""")
                    path == "/api/v1/qualityprofile" -> MockResponse().setBody("""[{"id":2,"name":"Lossless"}]""")
                    path == "/api/v1/metadataprofile" -> MockResponse().setBody("""[{"id":3,"name":"Standard"}]""")
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

    private fun TestScope.service(url: String) = LidarrService(
        client = LidarrClient(OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build()),
        config = { LidarrConfig(serverUrl = url, apiKey = "key") },
        queue = queue,
        scope = this,
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
}
