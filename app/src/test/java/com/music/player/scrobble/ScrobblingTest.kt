package com.music.player.scrobble

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.music.player.data.AppDatabase
import com.music.player.history.ListeningTracker
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.settings.ScrobbleSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScrobblingTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private lateinit var server: MockWebServer
    private val listenBrainz = mutableListOf<String>()
    private val lastFm = mutableListOf<String>()
    private var lastFmDown = false
    private var online = true

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/lb/1/submit-listens" -> {
                    listenBrainz += request.body.readUtf8()
                    MockResponse().setBody("""{"status":"ok"}""")
                }
                "/lastfm/" -> if (lastFmDown) {
                    MockResponse().setResponseCode(503)
                } else {
                    lastFm += request.body.readUtf8()
                    MockResponse().setBody("""{"scrobbles":{}}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private val settings get() = ScrobbleSettings(
        listenBrainzToken = "lb-token",
        listenBrainzUrl = server.url("/lb").toString(),
        lastFmApiKey = "key",
        lastFmSecret = "secret",
        lastFmUser = "me",
        lastFmSession = "session",
    )

    private fun scrobbler() = Scrobbler(
        db.scrobbles(),
        { settings },
        ListenBrainzClient(OkHttpClient()),
        LastFmClient(OkHttpClient(), endpoint = server.url("/lastfm/").toString()),
    ) { online }

    private val song = Track(SourceType.JELLYFIN, "j1", "Stick Season", "Noah Kahan", album = "Stick Season", durationMs = 182_000)

    @Test
    fun `a listen goes to both services, and only the one that failed gets it again`() = runBlocking {
        lastFmDown = true
        val scrobbler = scrobbler()
        scrobbler.scrobble(Listen(song, startedAtMs = 1_700_000_000_000))
        assertEquals(1, listenBrainz.size)
        assertTrue(listenBrainz.single().contains("\"listened_at\":1700000000") && listenBrainz.single().contains("\"track_name\":\"Stick Season\""))
        assertEquals(1, db.scrobbles().oldest(10).size)

        lastFmDown = false
        scrobbler.flush()
        assertEquals(1, listenBrainz.size) // not sent twice
        assertEquals(1, lastFm.size)
        assertTrue(lastFm.single().contains("method=track.scrobble") && lastFm.single().contains("timestamp%5B0%5D=1700000000"))
        assertTrue(db.scrobbles().oldest(10).isEmpty())
    }

    @Test
    fun `listens made offline wait until back online`() = runBlocking {
        online = false
        val scrobbler = scrobbler()
        scrobbler.scrobble(Listen(song, startedAtMs = 1_000))
        assertTrue(listenBrainz.isEmpty())
        online = true
        scrobbler.flush()
        assertEquals(1, listenBrainz.size)
    }

    @Test
    fun `last fm requests are signed as its docs describe`() {
        val params = mapOf("method" to "track.scrobble", "api_key" to "key", "sk" to "session", "artist[0]" to "A")
        val expected = MessageDigest.getInstance("MD5")
            .digest("api_keykeyartist[0]Amethodtrack.scrobblesksessionsecret".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, LastFmClient.signature(params, "secret"))
    }

    @Test
    fun `a listen is half the song or four minutes, of songs over 30 seconds`() {
        assertTrue(ListeningTracker.countsAsListen(180_000, 90_000))
        assertFalse(ListeningTracker.countsAsListen(180_000, 89_000))
        assertTrue(ListeningTracker.countsAsListen(20 * 60_000L, 4 * 60_000L))
        assertFalse(ListeningTracker.countsAsListen(25_000, 25_000))
        assertTrue(ListeningTracker.countsAsListen(null, 4 * 60_000L))
    }

    @Test
    fun `the tracker writes history as songs start and scrobbles ones listened to`() = runBlocking {
        var now = 0L
        val work = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + work)
        val tracker = ListeningTracker(db.history(), scrobbler(), scope) { now }
        tracker.songStarted(song, heardOn = null, heard = false, playing = true)
        now += 100_000
        tracker.songStarted(Track(SourceType.SIRIUSXM, "song:1", "Here", "Mumford & Sons"), heardOn = "Alt Nation", heard = true, playing = true)
        now += 10_000
        tracker.finish()
        while (work.children.any { it.isActive }) work.children.forEach { it.join() }
        val history = db.history().recent(10)
        assertEquals(listOf("Here", "Stick Season"), history.map { it.track.title })
        assertEquals("Alt Nation", history.first().heardOn)
        // The first song was listened to long enough; the second wasn't.
        assertEquals(1, listenBrainz.count { it.contains("\"listen_type\":\"single\"") })
    }
}
