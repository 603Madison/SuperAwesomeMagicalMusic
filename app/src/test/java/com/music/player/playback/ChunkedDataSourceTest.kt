package com.music.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChunkedDataSourceTest {
    private val content = ByteArray(1_300_000) { (it * 31 % 251).toByte() }
    private lateinit var server: MockWebServer
    private val ranges = mutableListOf<String?>()
    private var honourRanges = true

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                ranges += range
                val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (!honourRanges || match == null) return MockResponse().setBody(Buffer().write(content))
                val start = match.groupValues[1].toInt()
                val last = match.groupValues[2].toIntOrNull()?.coerceAtMost(content.size - 1) ?: (content.size - 1)
                return MockResponse()
                    .setResponseCode(206)
                    .addHeader("Content-Range", "bytes $start-$last/${content.size}")
                    .setBody(Buffer().write(content, start, last - start + 1))
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun source(chunk: Boolean = true) =
        ChunkedDataSource(OkHttpDataSource.Factory(OkHttpClient()).createDataSource(), chunkBytes = 512L * 1024) { chunk }

    private fun readAll(source: ChunkedDataSource, spec: DataSpec): Pair<Long, ByteArray> {
        val length = source.open(spec)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        source.close()
        return length to out.toByteArray()
    }

    private fun url(query: String = "") = Uri.parse(server.url("/videoplayback").toString() + query)

    @Test
    fun `a youtube stream is read in short ranges, using the length its url gives`() {
        val (length, bytes) = readAll(source(), DataSpec(url("?clen=${content.size}")))
        assertEquals(content.size.toLong(), length)
        assertArrayEquals(content, bytes)
        assertEquals(listOf("bytes=0-524287", "bytes=524288-1048575", "bytes=1048576-1299999"), ranges)
    }

    @Test
    fun `without a length in the url, the server's content range gives it`() {
        val (length, bytes) = readAll(source(), DataSpec(url()))
        assertEquals(content.size.toLong(), length)
        assertArrayEquals(content, bytes)
        assertEquals(3, ranges.size)
    }

    @Test
    fun `reading from a position (a seek) starts the ranges there`() {
        val spec = DataSpec.Builder().setUri(url("?clen=${content.size}")).setPosition(1_000_000).build()
        val (length, bytes) = readAll(source(), spec)
        assertEquals(300_000L, length)
        assertArrayEquals(content.copyOfRange(1_000_000, content.size), bytes)
        assertEquals(listOf("bytes=1000000-1299999"), ranges)
    }

    @Test
    fun `a server that ignores ranges still gives the right bytes`() {
        honourRanges = false
        val (_, bytes) = readAll(source(), DataSpec(url("?clen=${content.size}")))
        assertArrayEquals(content, bytes)
    }

    @Test
    fun `other streams are read in one request`() {
        val (_, bytes) = readAll(source(chunk = false), DataSpec(url()))
        assertArrayEquals(content, bytes)
        assertEquals(1, ranges.size)
        assertFalse(ranges.single()?.contains("524287") == true)
    }

    @Test
    fun `only youtube's stream hosts are chunked`() {
        assertTrue(ChunkedDataSource.isYouTubeStream(Uri.parse("https://rr6---sn-ajab55-5c.googlevideo.com/videoplayback?clen=1")))
        assertFalse(ChunkedDataSource.isYouTubeStream(Uri.parse("https://jellyfin.example.com/Audio/1/stream")))
        assertEquals(3433755L, ChunkedDataSource.declaredLength(Uri.parse("https://x.googlevideo.com/videoplayback?a=1&clen=3433755")))
    }
}
