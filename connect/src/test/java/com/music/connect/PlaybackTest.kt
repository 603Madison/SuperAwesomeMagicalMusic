package com.music.connect

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.music.connect.net.ServerApi
import com.music.connect.playback.LibraryCallback
import com.music.connect.playback.MediaItems
import com.music.connect.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import java.time.Duration

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
        server.close()
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
        val kbps = c.settings.value.kbpsFor(network.networkClass, network.savingData)
        assertEquals(if (kbps == 0) "FLAC (original file)" else "MP3 $kbps kbps (transcoded)", c.playingDescription.value[song.key])
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
