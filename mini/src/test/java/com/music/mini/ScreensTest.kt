package com.music.mini

import android.view.KeyEvent
import android.widget.EditText
import android.widget.TextView
import com.music.mini.net.User
import com.music.mini.ui.CodeScreen
import com.music.mini.ui.HomeScreen
import com.music.mini.ui.MainActivity
import com.music.mini.ui.NowPlayingScreen
import com.music.mini.ui.OptionsScreen
import com.music.mini.ui.SearchScreen
import com.music.mini.ui.ServerScreen
import com.music.mini.ui.TrackListScreen
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * The screens driven with keys alone, as on a flip phone with no touch screen: the D-pad moves
 * the highlight, OK chooses, Back (or the right soft key) goes back, the left soft key opens
 * options, and the number keys pick from menus. The server is a stand-in with canned answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreensTest {
    private val server = MockWebServer()
    private val player = FakePlayer()
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val mini get() = RuntimeEnvironment.getApplication().mini
    private val base get() = server.url("/").toString().trimEnd('/')
    private var polls = 0
    private var approved = false

    private fun song(id: String, title: String, artist: String = "Velvet Satellites") =
        """{"source":"JELLYFIN","sourceId":"$id","title":"$title","artist":"$artist","album":"Low Orbit","durationMs":200000,"via":"lib"}"""

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = when {
                    path == "/api/auth/config" -> """{"serverName":"Home Music","version":"1.30.0","localLogin":true}"""
                    path == "/api/auth/device/start" -> """{"deviceCode":"${"d".repeat(43)}","userCode":"BCDF-GHJK","verificationUri":"$base/link","verificationUriComplete":"$base/link?code=BCDF-GHJK","interval":1,"expiresIn":600}"""
                    path == "/api/auth/device/poll" -> {
                        polls++
                        if (approved) """{"status":"approved","token":"t0ken-for-the-flip-phone","user":{"id":"u1","username":"maddie","displayName":"Maddie"}}""" else """{"status":"pending"}"""
                    }
                    path == "/api/playlists" -> """[{"id":"p1","name":"Road trip","count":3},{"id":"p2","name":"Rainy day","count":1}]"""
                    path == "/api/playlists/p1" -> """{"summary":{"id":"p1","name":"Road trip","count":3},"entries":[
                        {"entryId":"e1","track":${song("s1", "Neon Tidepool")}},
                        {"entryId":"e2","track":${song("s2", "Paper Lanterns")}},
                        {"entryId":"e3","track":${song("s3", "Glass Harbour")}}]}"""
                    path.startsWith("/api/search") -> """{"query":"neon","top":{"kind":"song","track":${song("s1", "Neon Tidepool")}},
                        "songs":[${song("s1", "Neon Tidepool")},${song("s4", "Neon Nights", "Other Band")}],
                        "artists":[{"id":"ar1","name":"Velvet Satellites"}],"albums":[{"id":"al1","title":"Low Orbit","artist":"Velvet Satellites","year":2024}]}"""
                    path == "/api/radio/personal" -> """[{"track":{"source":"RADIO","sourceId":"radio","title":"Your Radio","artist":"Made for you","via":"personal"},"kind":"radio","name":"Your Radio","tagline":"Songs you love"}]"""
                    path == "/api/live/favorites" -> """[{"source":"RADIO","sourceId":"st1","title":"Jazz FM","artist":"London","via":"rb"}]"""
                    path.startsWith("/api/radio/personal/radio/now") -> """{"station":{"source":"RADIO","sourceId":"radio","title":"Your Radio","artist":"","via":"personal"},"now":{"title":"Neon Tidepool","artist":"Velvet Satellites"},"songKey":"k1"}"""
                    path == "/api/radio/personal/radio/thumb" -> "{}"
                    else -> return MockResponse().setResponseCode(404).setBody("""{"error":"No such API"}""")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        mini.player = player
    }

    @After
    fun stop() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        server.shutdown()
    }

    private fun signedIn() = mini.prefs.signIn(base, "t0ken", User("u1", "maddie", "Maddie"), "Home Music")

    private fun launch() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
    }

    private fun MainActivity.text(view: android.view.View?) = (view as? TextView)?.text?.toString()

    @Test
    fun `the home menu's highlight moves with the D-pad and goes round the ends`() {
        signedIn()
        launch()
        assertTrue(activity.top is HomeScreen)
        assertEquals("Now playing", activity.focusedTitle)
        activity.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Search", activity.focusedTitle)
        activity.press(KeyEvent.KEYCODE_DPAD_UP)
        activity.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Settings", activity.focusedTitle)
        activity.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Now playing", activity.focusedTitle)
    }

    @Test
    fun `a playlist plays from the song chosen, and Back finds everything as it was`() {
        signedIn()
        launch()
        // 3 is the third item of the menu: Playlists.
        activity.press(KeyEvent.KEYCODE_3)
        waitFor("playlists") { activity.focusedTitle == "Road trip" }
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(activity.top is TrackListScreen)
        waitFor("the playlist's songs") { activity.focusedTitle == "Neon Tidepool" }
        activity.press(KeyEvent.KEYCODE_DPAD_DOWN)
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("play Paper Lanterns"), player.asked)
        assertEquals(3, player.queue.size)
        assertTrue(activity.top is NowPlayingScreen)

        // Now playing: OK plays and pauses, right and left move through the songs.
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        activity.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        activity.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf("play Paper Lanterns", "toggle", "next", "previous"), player.asked)

        // Back: to the playlist, its song still highlighted; the soft key does the same.
        activity.press(KeyEvent.KEYCODE_BACK)
        assertTrue(activity.top is TrackListScreen)
        assertEquals("Paper Lanterns", activity.focusedTitle)
        activity.press(KeyEvent.KEYCODE_SOFT_RIGHT)
        assertEquals("Road trip", activity.focusedTitle)
        activity.press(KeyEvent.KEYCODE_BACK)
        assertTrue(activity.top is HomeScreen)
        assertEquals("Playlists", activity.focusedTitle)
        // Home shows what's playing.
        assertEquals("Paper Lanterns · Velvet Satellites", (activity.list.rows[0] as com.music.mini.ui.Row.Item).detail)
    }

    @Test
    fun `a song's options add it to a playlist`() {
        signedIn()
        launch()
        activity.press(KeyEvent.KEYCODE_3)
        waitFor("playlists") { activity.focusedTitle == "Road trip" }
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("the playlist's songs") { activity.focusedTitle == "Neon Tidepool" }
        activity.press(KeyEvent.KEYCODE_SOFT_LEFT)
        assertTrue(activity.top is OptionsScreen)
        assertEquals("Add to playlist", activity.focusedTitle)
        activity.press(KeyEvent.KEYCODE_1)
        waitFor("the playlists to add to") { activity.focusedTitle == "Road trip" }
        assertEquals("Add to playlist", activity.top!!.title)
        activity.press(KeyEvent.KEYCODE_BACK)
        // The options menu closed as it was chosen: back at the song.
        assertTrue(activity.top is TrackListScreen)
        assertEquals("Neon Tidepool", activity.focusedTitle)
    }

    @Test
    fun `search types, searches on OK, goes down into the results and up back to the box`() {
        signedIn()
        launch()
        activity.press(KeyEvent.KEYCODE_2)
        assertTrue(activity.top is SearchScreen)
        val field = activity.focusedView as EditText
        field.setText("neon")
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("results") { activity.focusedTitle == "Neon Tidepool" }
        assertEquals("/api/search?q=neon&limit=20", server.takeRequestsUntil { it.path!!.startsWith("/api/search") }.path)
        activity.press(KeyEvent.KEYCODE_DPAD_DOWN)
        // Headings are skipped: the first song under "Songs".
        assertEquals(2, activity.list.focusedIndex())
        activity.press(KeyEvent.KEYCODE_DPAD_UP)
        activity.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(activity.focusedView is EditText)
        activity.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Neon Tidepool", activity.focusedTitle)
    }

    @Test
    fun `radio lists the person's own stations and favourites, and next skips on their own station`() {
        signedIn()
        launch()
        activity.press(KeyEvent.KEYCODE_4)
        waitFor("stations") { activity.focusedTitle == "Your Radio" }
        activity.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Jazz FM", activity.focusedTitle)
        activity.press(KeyEvent.KEYCODE_DPAD_UP)
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("play Your Radio"), player.asked)
        assertTrue(activity.top is NowPlayingScreen)
        // What's on, from the station.
        waitFor("what's on") { activity.text(activity.findViewByText("Neon Tidepool")) != null }
        activity.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("next", player.asked.last())
        // Thumbs are in the options.
        activity.press(KeyEvent.KEYCODE_SOFT_LEFT)
        assertEquals("Thumbs up: more like this", activity.focusedTitle)
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("the thumb") { server.requestCount > 0 && activity.top is NowPlayingScreen }
    }

    @Test
    fun `the stream quality starts low, and a menu number picks another`() {
        signedIn()
        launch()
        assertEquals(96, mini.prefs.kbps)
        activity.press(KeyEvent.KEYCODE_6)
        assertEquals("Stream quality", activity.focusedTitle)
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        // The highlight starts on the one chosen now.
        assertEquals("96 kbps: data saver", activity.focusedTitle)
        activity.press(KeyEvent.KEYCODE_5)
        assertEquals(320, mini.prefs.kbps)
        assertEquals("Stream quality", activity.focusedTitle)
        assertEquals("320 kbps: high", (activity.list.focusedRow() as com.music.mini.ui.Row.Item).detail)
    }

    @Test
    fun `signing in takes the address, then a code that signs in once it's allowed elsewhere`() {
        launch()
        assertTrue(activity.top is ServerScreen)
        val field = activity.focusedView as EditText
        field.setText(base.removePrefix("http://"))
        // An address with a port on the home network gets http://, so this reaches the stand-in.
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("the code") { (activity.top as? CodeScreen)?.started != null }
        assertNotNull(activity.findViewByText("BCDF-GHJK"))
        assertNotNull(activity.findViewByText("On your phone or computer, go to\n${base.removePrefix("http://")}/link\nand type this code."))
        waitFor("a few polls", 20_000) { polls >= 2 }
        assertFalse(mini.prefs.signedIn)
        approved = true
        waitFor("signing in", 20_000) { activity.top is HomeScreen }
        assertTrue(mini.prefs.signedIn)
        assertEquals("t0ken-for-the-flip-phone", mini.prefs.token)
        assertEquals("Home Music", activity.top!!.title)
        assertEquals("Now playing", activity.focusedTitle)
        // Back from the first screen goes to the background (it doesn't go back to signing in).
        activity.press(KeyEvent.KEYCODE_BACK)
        assertTrue(activity.top is HomeScreen)
    }

    @Test
    fun `the code screen's QR code holds the link with the code`() {
        val bitmap = CodeScreen.qrBitmap("https://music.example.com/link?code=BCDF-GHJK", 200)
        assertTrue(bitmap.width in 100..200)
        assertEquals(bitmap.width, bitmap.height)
    }
}

/** The requests so far, until one that [matches]. */
private fun MockWebServer.takeRequestsUntil(matches: (RecordedRequest) -> Boolean): RecordedRequest {
    while (true) {
        val request = takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("No such request")
        if (matches(request)) return request
    }
}

/** The visible text view showing exactly [text], if there is one. */
fun MainActivity.findViewByText(text: String): TextView? {
    fun find(view: android.view.View): TextView? {
        if (view is TextView && view.text.toString() == text && view.isShown) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    return find(window.decorView)
}
