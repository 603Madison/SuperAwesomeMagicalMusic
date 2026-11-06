package com.music.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.music.player.data.AppDatabase
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.sources.SourceStream
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackCacheTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val content = ByteArray(300_000) { (it % 251).toByte() }
    private lateinit var server: MockWebServer
    private var requests = 0
    private var enabled = true
    private lateinit var cache: PlaybackCache

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests++
                return MockResponse().setBody(Buffer().write(content))
            }
        }
        server.start()
        java.io.File(context.filesDir, "playback-cache").deleteRecursively()
        cache = PlaybackCache(context, db.cachedCopies(), maxBytes = { 50L * 1024 * 1024 }, enabled = { enabled })
    }

    @After
    fun tearDown() {
        server.shutdown()
        cache.cache.release()
        db.close()
    }

    private val song = Track(SourceType.JELLYFIN, "j1", "Stick Season", "Noah Kahan")
    private fun stream(description: String, ranges: Boolean = true) =
        SourceStream(url = server.url("/song").toString(), description = description, fileExtension = "flac", supportsRangeRequests = ranges, kbps = 900)

    private fun read(key: String?, uri: Uri = Uri.parse(server.url("/song").toString())): ByteArray {
        val source = cache.dataSourceFactory(OkHttpDataSource.Factory(OkHttpClient())).createDataSource()
        source.open(DataSpec.Builder().setUri(uri).setKey(key).build())
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        source.close()
        return out.toByteArray()
    }

    @Test
    fun `each copy and format gets its own key, and none when caching is off or the audio is live`() {
        val flac = cache.keyFor(song, stream("FLAC (original file)"))!!
        val mp3 = cache.keyFor(song, stream("MP3 192 kbps (transcoded)", ranges = false))!!
        assertTrue(flac.startsWith(PlaybackCache.RESUMABLE))
        assertTrue(mp3.startsWith(PlaybackCache.WHOLE))
        assertNotEquals(flac, mp3)
        assertNull(cache.keyFor(Track(SourceType.RADIO, "r1", "KISS 108", "Radio"), stream("MP3")))
        enabled = false
        assertNull(cache.keyFor(song, stream("FLAC (original file)")))
    }

    @Test
    fun `a song played once plays again from the cache, with no connection, once indexed`() = runBlocking {
        val key = cache.keyFor(song, stream("FLAC (original file)"))!!
        cache.remember(song.key, key, song, "FLAC (original file)", 900, -3f)
        assertArrayEquals(content, read(key))
        assertEquals(1, requests)

        cache.recordIfComplete(song.key)
        val cached = cache.completeCopy(song.key)
        assertNotNull(cached)
        assertEquals(-3f, cached!!.gainDb)
        // Played from the cache: the address never reaches the network.
        assertArrayEquals(content, read(key, Uri.parse(PlaybackCache.cachedUri(key))))
        assertEquals(1, requests)
    }

    @Test
    fun `a transcode isn't cached while streaming, only when fetched whole`() = runBlocking {
        val key = cache.keyFor(song, stream("MP3 192 kbps (transcoded)", ranges = false))!!
        read(key)
        assertFalse(cache.isComplete(key))
        // Fetched ahead whole, it plays from the cache.
        val writer = androidx.media3.datasource.cache.CacheWriter(
            cache.writer(OkHttpDataSource.Factory(OkHttpClient()).createDataSource()),
            DataSpec.Builder().setUri(server.url("/song").toString()).setKey(key).build(),
            null,
            null,
        )
        writer.cache()
        assertTrue(cache.isComplete(key))
        val before = requests
        assertArrayEquals(content, read(key))
        assertEquals(before, requests)
    }

    @Test
    fun `an evicted copy is dropped from the index`() = runBlocking {
        val key = cache.keyFor(song, stream("FLAC (original file)"))!!
        cache.remember(song.key, key, song, "FLAC", 900, null)
        read(key)
        cache.recordIfComplete(song.key)
        cache.discard(key)
        assertNull(cache.completeCopy(song.key))
        assertNull(db.cachedCopies().get(song.key))
    }
}
