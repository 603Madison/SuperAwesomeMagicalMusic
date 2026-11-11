package com.music.connect

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.music.connect.net.ApiTrack
import com.music.connect.net.Listen
import com.music.connect.net.ServerApi
import com.music.connect.net.User
import com.music.connect.playback.PlayerConnection
import com.music.connect.ui.LocalPlayer
import com.music.connect.ui.MainScaffold
import com.music.connect.ui.SignInScreen
import com.music.connect.data.Palette
import com.music.connect.data.ThemeMode
import com.music.connect.ui.theme.ConnectTheme
import com.music.player.model.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** The screens, drawn as they'd look (saved to build/screens for a look), and that they come up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val out = File("build/screens").apply { mkdirs() }

    /** Draws the window as it is now. */
    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `sign-in`() {
        compose.setContent { ConnectTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { SignInScreen(false, null) } } }
        compose.onNodeWithText("Server address").assertExists()
        save("sign-in")
    }

    @Test
    fun `playlists, offline, in the dark theme`() = playlistsOffline(ThemeMode.DARK, "-dark")

    @Test
    fun `playlists, offline`() = playlistsOffline(ThemeMode.SYSTEM, "")

    @Test
    fun `playlists, offline, with Bold text on`() = playlistsOffline(ThemeMode.SYSTEM, "-bold", bold = true)

    @Test
    fun `playlists, offline, in the Ocean theme, dark`() = playlistsOffline(ThemeMode.DARK, "-ocean-dark", palette = Palette.OCEAN)

    private fun playlistsOffline(mode: ThemeMode, suffix: String, bold: Boolean = false, palette: Palette = Palette.AURORA) {
        val app = RuntimeEnvironment.getApplication()
        val c = app.container
        runBlocking {
            c.prefs.signIn("https://music.example.com", "token", User("u1", "maddie", "Maddie", "USER"), "Home music")
            val songs = listOf(
                ApiTrack(SourceType.JELLYFIN, "a1", "Neon Tidepool", "Velvet Satellites", "Low Orbit", 200_000, via = "j"),
                ApiTrack(SourceType.JELLYFIN, "a2", "Paper Lanterns", "Velvet Satellites", "Low Orbit", 214_000, via = "j"),
                ApiTrack(SourceType.QOBUZ, "q1", "Glasshouse Summer", "The Marigold Static", "Glasshouse", 187_000, via = "q"),
            )
            c.library.create("Late-night drive", songs)
            c.library.create("Focus")
            c.library.setKeepOffline(c.library.all.first().first { it.name == "Focus" }.id, true)
            // Set either way: the setting is kept between tests.
            c.prefs.update { it.copy(boldText = bold, palette = palette) }
        }
        val player = PlayerConnection(app) { "https://music.example.com" }
        compose.setContent {
            val settings = c.settings.collectAsState().value
            ConnectTheme(mode, bold = settings.boldText, palette = settings.palette) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalPlayer provides player) { MainScaffold() }
                }
            }
        }
        compose.onAllNodesWithText("Search").onFirst().assertExists()
        save("search-offline$suffix")
        compose.onNodeWithText("Playlists").performClickSafe()
        compose.waitForIdle()
        compose.onNodeWithText("Late-night drive").assertExists()
        save("playlists$suffix")
        compose.onNodeWithText("Late-night drive").performClickSafe()
        compose.waitForIdle()
        compose.onNodeWithText("Neon Tidepool").assertExists()
        save("playlist$suffix")
        compose.onNodeWithText("Settings").performClickSafe()
        compose.waitForIdle()
        compose.onNodeWithText("Bold text").assertExists()
        save("settings$suffix")
        // Another theme, chosen in Settings, is kept and drawn at once.
        compose.onNodeWithText("Ember").performClickSafe()
        compose.waitUntil(10_000) { c.settings.value.palette == Palette.EMBER }
        compose.waitForIdle()
        assertEquals(Palette.EMBER, c.settings.value.palette)
        save("settings-ember$suffix")
    }

    @Test
    fun `search, an artist and an album`() {
        val server = TestServer()
        try {
            val app = RuntimeEnvironment.getApplication()
            val c = app.container
            runBlocking {
                val signedIn = ServerApi(OkHttpClient(), { server.base }, { null }).login(server.base, "maddie", "correct horse", "Test phone")
                c.prefs.signIn(server.base, signedIn.token!!, signedIn.user, "Home music")
            }
            val player = PlayerConnection(app) { server.base }
            compose.setContent {
                ConnectTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CompositionLocalProvider(LocalPlayer provides player) { MainScaffold() }
                    }
                }
            }
            compose.onNode(hasSetTextAction()).performTextInput("velvet satellites")
            waitForText("Top result")
            save("search")
            compose.onAllNodesWithText("Velvet Satellites")[0].performClick()
            waitForText("Popular")
            save("artist")
            compose.onNodeWithText("Low Orbit").performClick()
            waitForText("2 songs", substring = true)
            compose.onNodeWithText("Paper Lanterns").assertExists()
            save("album")
        } finally {
            server.close()
        }
    }

    @Test
    fun `Wrapped, and its story`() {
        val server = TestServer()
        try {
            val app = RuntimeEnvironment.getApplication()
            val c = app.container
            runBlocking {
                val signedIn = ServerApi(OkHttpClient(), { server.base }, { null }).login(server.base, "maddie", "correct horse", "Test phone")
                c.prefs.signIn(server.base, signedIn.token!!, signedIn.user, "Home music")
                c.settings.first { it.token == signedIn.token }
                // A year's start, in this phone's time zone, and some listening after it.
                val start = LocalDate.now().withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val hour = 3_600_000L
                val neon = ApiTrack(SourceType.JELLYFIN, "song1", "Neon Tidepool", "Velvet Satellites", "Low Orbit", 200_000)
                val lanterns = ApiTrack(SourceType.JELLYFIN, "song2", "Paper Lanterns", "Velvet Satellites", "Low Orbit", 214_000)
                val glass = ApiTrack(SourceType.QOBUZ, "q1", "Glasshouse Summer", "The Marigold Static", "Glasshouse", 187_000)
                val channel = ApiTrack(SourceType.SIRIUSXM, "channel:xtra:altap", "Alt Nation Advanced Placement", "SiriusXM")
                val listens = (0 until 9).map { Listen(neon, start + it * 26 * hour + 20 * hour, 200_000) } +
                    (0 until 5).map { Listen(lanterns, start + it * 30 * hour + 21 * hour, 214_000) } +
                    (0 until 4).map { Listen(glass, start + it * 50 * hour + 9 * hour, 187_000, client = "web") } +
                    Listen(channel, start + 5 * 24 * hour + 18 * hour, 1_800_000, station = "Alt Nation Advanced Placement")
                c.api.addListens(listens)
            }
            val player = PlayerConnection(app) { server.base }
            compose.setContent {
                ConnectTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CompositionLocalProvider(LocalPlayer provides player) { MainScaffold() }
                    }
                }
            }
            // The banner on Search's start opens it.
            waitForText("Wrapped", substring = true)
            compose.onAllNodesWithText("Wrapped", substring = true).onFirst().performClick()
            waitForText("minutes of music", substring = true)
            compose.onNodeWithText("Play your story").assertExists()
            compose.onAllNodesWithText("Velvet Satellites").onFirst().assertExists()
            save("wrapped")
            // The story: the first slide, then the song of the year, then (left to run) the end.
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Play your story").performClick()
            compose.mainClock.advanceTimeBy(1_000)
            saveWithTopWindow("wrapped-story")
            compose.mainClock.advanceTimeBy(15_000)
            compose.onNodeWithText("Your song of the year".uppercase()).assertExists()
            saveWithTopWindow("wrapped-story-song")
            compose.mainClock.autoAdvance = true
            waitForText("Watch again")
            saveWithTopWindow("wrapped-story-end")
        } finally {
            server.close()
        }
    }

    @Test
    fun `another device playing, and the devices sheet`() {
        val server = TestServer()
        val http = OkHttpClient()
        var web: okhttp3.WebSocket? = null
        try {
            val app = RuntimeEnvironment.getApplication()
            val c = app.container
            val token = runBlocking {
                val signedIn = ServerApi(http, { server.base }, { null }).login(server.base, "maddie", "correct horse", "Test phone")
                c.prefs.signIn(server.base, signedIn.token!!, signedIn.user, "Home music")
                c.settings.first { it.token == signedIn.token }
                signedIn.token!!
            }
            c.devices.start()
            // The web player, playing.
            val song = """{"source":"JELLYFIN","sourceId":"s1","title":"Neon Tidepool","artist":"Velvet Satellites","via":"src"}"""
            web = http.newWebSocket(
                okhttp3.Request.Builder().url("${server.base}/api/connect/ws?id=web-device-1&name=Chrome%20on%20Linux&kind=web").header("Authorization", "Bearer $token").build(),
                object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        webSocket.send("""{"type":"state","state":{"track":$song,"queue":[$song],"index":0,"positionMs":72000,"durationMs":214000,"playing":true,"volume":0.7}}""")
                    }
                },
            )
            val player = PlayerConnection(app) { server.base }
            compose.setContent {
                ConnectTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CompositionLocalProvider(LocalPlayer provides player) { MainScaffold() }
                    }
                }
            }
            waitForText("Playing on Chrome on Linux")
            compose.onNodeWithText("Neon Tidepool").assertExists()
            save("remote")
            compose.onNodeWithTag("remote-player").performClick()
            waitForText("This phone")
            compose.onNodeWithText("Play on").assertExists()
            compose.mainClock.advanceTimeBy(1_000)
            saveWithTopWindow("devices")
        } finally {
            web?.close(1000, null)
            app().container.devices.stop()
            server.close()
        }
    }

    @Test
    fun `favourite stations made on the web, on the Radio tab`() {
        val server = TestServer()
        try {
            val app = RuntimeEnvironment.getApplication()
            val c = app.container
            val maddie = runBlocking { server.services.users.byUsername("maddie")!! }
            runBlocking {
                val radio = "radio"
                listOf("KEXP" to "Seattle", "WFMU" to "Jersey City", "Radio Paradise" to "California").forEachIndexed { i, (name, place) ->
                    server.services.liveFavorites.set(maddie.id, com.music.server.music.ApiTrack(SourceType.RADIO, "station-$i", name, place, via = radio), true)
                }
                val signedIn = ServerApi(OkHttpClient(), { server.base }, { null }).login(server.base, "maddie", "correct horse", "Test phone")
                c.prefs.signIn(server.base, signedIn.token!!, signedIn.user, "Home music")
                c.settings.first { it.token == signedIn.token }
            }
            val player = PlayerConnection(app) { server.base }
            compose.setContent {
                ConnectTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CompositionLocalProvider(LocalPlayer provides player) { MainScaffold() }
                    }
                }
            }
            compose.onNodeWithText("Radio").performClick()
            waitForText("Favourites")
            compose.onNodeWithText("Radio Paradise").assertExists()
            compose.onNodeWithText("KEXP").assertExists()
            save("radio-favourites")
        } finally {
            server.close()
        }
    }

    private fun app() = RuntimeEnvironment.getApplication()

    /** Draws the window with the one above it (a sheet or dialog) on top. */
    private fun saveWithTopWindow(name: String) {
        compose.waitForIdle()
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>
        val base = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            val canvas = Canvas(bitmap)
            base.draw(canvas)
            views.lastOrNull()?.takeIf { it !== base }?.draw(canvas)
        }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickSafe() {
        performClick()
    }
}
