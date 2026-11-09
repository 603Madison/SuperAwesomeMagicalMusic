package com.music.connect

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.music.connect.net.ApiTrack
import com.music.connect.net.ServerApi
import com.music.connect.playback.LibraryCallback
import com.music.connect.playback.MediaItems
import com.music.connect.playback.PlaybackService
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.quality.StreamQuality
import com.music.player.sources.MusicSource
import com.music.player.sources.SourceStream
import java.time.Duration
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** The playback service playing a song from the server, the way the phone does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackTest {
    private lateinit var server: TestServer
    private lateinit var service: ServiceController<PlaybackService>
    private val c get() = RuntimeEnvironment.getApplication().container

    @Before
    fun start() {
        server = TestServer(TestServer.wav())
        runBlocking {
            val signedIn = ServerApi(OkHttpClient(), { server.base }, { null }).login(server.base, "maddie", "correct horse", "Test phone")
            c.prefs.signIn(server.base, signedIn.token!!, signedIn.user, "Test")
        }
        waitFor("signed in") { c.settings.value.signedIn }
        service = Robolectric.buildService(PlaybackService::class.java).create()
    }

    @After
    fun stop() {
        service.destroy()
        runCatching { server.close() }
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for $what")
            // Advances the clock too: the player's own loop runs on it.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(10)
        }
    }

    private fun session(): MediaLibraryService.MediaLibrarySession {
        val controller = MediaSession.ControllerInfo.createTestOnlyControllerInfo("com.music.connect", 0, 0, 0, 0, false, Bundle.EMPTY, false)
        return service.get().onGetSession(controller)
    }

    @Test
    fun `a song is resolved through the server as it starts`() {
        val song = runBlocking { c.api.search("neon").allSongs.single() }
        val player = session().player
        player.setMediaItem(MediaItems.playable(song, server.base) { null })
        player.prepare()
        player.play()
        // The stream is chosen on the loader's thread, which mustn't touch the player (ExoPlayer
        // throws if it does, and the song fails).
        waitFor("the player to load it") { player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED || player.playerError != null }
        val causes = player.playerError?.let { generateSequence(it as Throwable) { e -> e.cause }.toList() }.orEmpty()
        val trace = causes.lastOrNull()?.let { e -> e.toString() + "\n" + e.stackTrace.take(12).joinToString("\n") }.orEmpty()
        assertTrue(trace, causes.none { it is IllegalStateException })
        // Robolectric has no audio decoders, so a decoder error is the furthest a song can get here.
        assertTrue(trace, player.playerError == null || player.playerError!!.errorCodeName.contains("DECOD"))
        // At the cap for the network the phone is on (Robolectric's is mobile data: 320 kbps).
        val network = c.network.state.value
        val kbps = c.settings.value.kbpsFor(network.networkClass)
        assertEquals(if (kbps == 0) "FLAC (original file)" else "MP3 $kbps kbps (transcoded)", c.playingDescription.value[song.key])
    }

    @Test
    fun `a YouTube Music song is asked of YouTube directly when the server can't be reached`() {
        // YouTube, as the phone sees it: one song, whose audio is a short WAV file.
        val youtube = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(TestServer.wav()))
            }
            start()
        }
        val asked = mutableListOf<String>()
        c.youtube = object : MusicSource {
            override val type = SourceType.YOUTUBE_MUSIC
            override suspend fun isAvailable() = true
            override suspend fun search(query: String, limit: Int) = emptyList<Track>()
            override suspend fun stream(sourceId: String, quality: StreamQuality): SourceStream {
                asked += sourceId
                return SourceStream(youtube.url("/videoplayback?id=$sourceId").toString(), mapOf("X-Client" to "phone"), "Opus 160 kbps", fileExtension = "webm", kbps = 160, codec = "opus")
            }
        }
        try {
            server.close()
            val song = ApiTrack(SourceType.YOUTUBE_MUSIC, "dQw4w9WgXcQ", "Backup Song", "Someone", via = "yt")
            val player = session().player
            player.setMediaItem(MediaItems.playable(song, server.base) { null })
            player.prepare()
            player.play()
            waitFor("YouTube to be asked") { c.streamFacts.value[song.key] != null || player.playerError != null }
            val facts = c.streamFacts.value[song.key]
            assertEquals(player.playerError?.toString(), true, facts?.byPhone)
            assertEquals("Opus 160 kbps", facts?.description)
            assertEquals(listOf("dQw4w9WgXcQ"), asked)
            // Fetched with the headers YouTube's address needs.
            assertEquals("phone", youtube.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS)?.getHeader("X-Client"))
        } finally {
            youtube.shutdown()
        }
    }

    @Test
    fun `Android Auto's root is answered at once`() {
        val auto = MediaSession.ControllerInfo.createTestOnlyControllerInfo("com.google.android.projection.gearhead", 0, 0, 0, 0, false, Bundle.EMPTY, true)
        val session = service.get().onGetSession(auto)
        val root = LibraryCallback(c, com.music.connect.playback.QueueStore(RuntimeEnvironment.getApplication().filesDir), kotlinx.coroutines.MainScope())
            .onGetLibraryRoot(session, auto, null)
        assertTrue(root.isDone)
        assertEquals(LibraryCallback.ROOT, root.get().value!!.mediaId)
    }
}
