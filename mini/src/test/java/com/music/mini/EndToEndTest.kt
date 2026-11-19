package com.music.mini

import android.os.Bundle
import android.view.KeyEvent
import android.widget.EditText
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.music.mini.net.ApiJson
import com.music.mini.net.ApiTrack
import com.music.mini.playback.MediaItems
import com.music.mini.playback.PlaybackService
import com.music.mini.ui.CodeScreen
import com.music.mini.ui.HomeScreen
import com.music.mini.ui.MainActivity
import com.music.mini.ui.NowPlayingScreen
import com.music.mini.ui.TrackListScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Mini against the real server (in-process, with an in-memory database and a stand-in Jellyfin):
 * signing in with a code that someone signed in elsewhere allows, then finding a playlist and
 * playing from it, and the playback service streaming a song through the server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EndToEndTest {
    private lateinit var test: TestServer
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val mini get() = RuntimeEnvironment.getApplication().mini
    private val player = FakePlayer()
    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()

    @Before
    fun start() {
        test = TestServer(TestServer.wav())
        mini.player = player
    }

    @After
    fun stop() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        runCatching { test.close() }
    }

    /** maddie, signed in on her phone with a password (as the Connect app would be). */
    private fun phoneToken(): String = call("POST", "/api/auth/login", """{"username":"maddie","password":"correct horse","app":true,"deviceName":"Phone"}""")
        .jsonObject["token"]!!.jsonPrimitive.content

    private fun call(method: String, path: String, body: String? = null, token: String? = null) = http.newCall(
        Request.Builder().url(test.base + path).method(method, body?.toRequestBody(json)).apply {
            token?.let { header("Authorization", "Bearer $it") }
        }.build(),
    ).execute().use { response ->
        val text = response.body.string()
        assertTrue("$method $path: ${response.code} $text", response.isSuccessful)
        Json.parseToJsonElement(text)
    }

    @Test
    fun `signs in with a code allowed on another device, then plays from a playlist`() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        (activity.focusedView as EditText).setText(test.base)
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("the code") { (activity.top as? CodeScreen)?.started != null }
        val userCode = (activity.top as CodeScreen).started!!.userCode
        assertTrue(userCode, Regex("[A-Z]{4}-[A-Z]{4}").matches(userCode))
        assertTrue((activity.top as CodeScreen).started!!.verificationUri.endsWith("/link"))

        // On the phone: see what's asking, and allow it.
        val phone = phoneToken()
        val asking = call("GET", "/api/auth/device/request?code=${userCode.lowercase()}", token = phone).jsonObject
        assertTrue(asking.toString(), asking["deviceName"]!!.jsonPrimitive.content.startsWith("Mini on "))
        call("POST", "/api/auth/device/decide", """{"userCode":"$userCode","allow":true}""", token = phone)

        waitFor("Mini to sign in", 30_000) { activity.top is HomeScreen }
        assertTrue(mini.prefs.signedIn)
        assertEquals(test.base, mini.prefs.server)
        // Listed with maddie's devices, under its own name.
        val devices = call("GET", "/api/me/sessions", token = phone).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(devices.toString(), devices.any { it.startsWith("Mini on ") })

        // A playlist made elsewhere is there.
        val song = runBlocking { mini.api.search("neon").songs.first() }
        call("POST", "/api/playlists", """{"name":"Road trip","tracks":[${ApiJson.encodeToString(ApiTrack.serializer(), song)}]}""", token = phone)
        activity.press(KeyEvent.KEYCODE_3)
        waitFor("playlists") { activity.focusedTitle == "Road trip" }
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(activity.top is TrackListScreen)
        waitFor("its songs") { activity.focusedTitle == "Neon Tidepool" }
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("play Neon Tidepool"), player.asked)
        assertTrue(activity.top is NowPlayingScreen)

        // The stream comes through the server with Mini's sign-in, at the quality chosen.
        val resolved = runBlocking { mini.api.resolve(song, 0) }
        val bytes = mini.http.newCall(Request.Builder().url(mini.api.absolute(resolved.streamUrl)).build()).execute().use { it.body.bytes() }
        assertTrue(bytes.contentEquals(test.audio))
    }

    @Test
    fun `the playback service asks the server for the song as it starts`() {
        val phone = phoneToken()
        mini.prefs.signIn(test.base, phone, com.music.mini.net.User("u1", "maddie", "Maddie"), "Test")
        // The original file: converting needs ffmpeg, which tests don't have.
        mini.prefs.kbps = 0
        val song = runBlocking { mini.api.search("neon").songs.first() }
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val info = MediaSession.ControllerInfo.createTestOnlyControllerInfo("com.music.mini", 0, 0, 0, 0, false, Bundle.EMPTY, false)
            val session = service.get().onGetSession(info)
            val player = session.player
            player.setMediaItem(MediaItems.playable(song, test.base))
            player.prepare()
            player.play()
            waitFor("the player to load it") {
                player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED || player.playerError != null
            }
            // Robolectric has no audio decoders, so a decoder error is the furthest a song can get here.
            val error = player.playerError
            assertTrue(error?.toString(), error == null || error.errorCodeName.contains("DECOD"))
            val asked = generateSequence { test.jellyfin.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }.map { it.path.orEmpty() }.toList()
            assertTrue(asked.toString(), asked.any { it.startsWith("/Audio/song1/stream") })
        } finally {
            service.destroy()
        }
    }
}
