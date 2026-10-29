package com.music.player.downloads

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class ChunkedDownloaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val downloader = ChunkedDownloader(OkHttpClient())
    private val content = Random(42).nextBytes((ChunkedDownloader.CHUNK_BYTES * 2 + 12_345).toInt())

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `range capable server is fetched in chunks`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val (start, end) = request.getHeader("Range")!!.removePrefix("bytes=").split("-").map { it.toLong() }
                val last = minOf(end, content.size - 1L)
                return MockResponse()
                    .setResponseCode(206)
                    .addHeader("Content-Range", "bytes $start-$last/${content.size}")
                    .setBody(Buffer().write(content, start.toInt(), (last - start + 1).toInt()))
            }
        }
        val file = temp.newFile()
        downloader.download(server.url("/song").toString(), emptyMap(), file, useRanges = true) { _, _ -> }
        assertArrayEquals(content, file.readBytes())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a dropped chunk is retried without restarting the download`() = runTest {
        var failedOnce = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val (start, end) = request.getHeader("Range")!!.removePrefix("bytes=").split("-").map { it.toLong() }
                if (start > 0 && !failedOnce) {
                    failedOnce = true
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                        .setResponseCode(206)
                        .addHeader("Content-Range", "bytes $start-$end/${content.size}")
                        .setBody(Buffer().write(content, start.toInt(), (end - start + 1).toInt()))
                }
                val last = minOf(end, content.size - 1L)
                return MockResponse()
                    .setResponseCode(206)
                    .addHeader("Content-Range", "bytes $start-$last/${content.size}")
                    .setBody(Buffer().write(content, start.toInt(), (last - start + 1).toInt()))
            }
        }
        val file = temp.newFile()
        downloader.download(server.url("/song").toString(), emptyMap(), file, useRanges = true) { _, _ -> }
        assertArrayEquals(content, file.readBytes())
        // 3 chunks + 1 retry; the first chunk was not fetched again.
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `server ignoring range sends the whole file once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(content)))
        val file = temp.newFile()
        downloader.download(server.url("/song").toString(), emptyMap(), file, useRanges = true) { _, _ -> }
        assertArrayEquals(content, file.readBytes())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `live transcodes are fetched without range headers`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(content)))
        val file = temp.newFile()
        downloader.download(server.url("/stream.mp3").toString(), mapOf("Authorization" to "token"), file, useRanges = false) { _, _ -> }
        val request = server.takeRequest()
        assertNull(request.getHeader("Range"))
        assertEquals("token", request.getHeader("Authorization"))
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `content range total is parsed`() {
        assertEquals(4096L, ChunkedDownloader.parseTotal("bytes 0-1023/4096"))
        assertNull(ChunkedDownloader.parseTotal("bytes 0-1023/*"))
        assertNull(ChunkedDownloader.parseTotal(null))
    }
}
