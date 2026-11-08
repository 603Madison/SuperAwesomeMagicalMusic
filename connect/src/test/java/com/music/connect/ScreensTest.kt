package com.music.connect

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.music.connect.net.ApiTrack
import com.music.connect.net.ServerApi
import com.music.connect.net.User
import com.music.connect.playback.PlayerConnection
import com.music.connect.ui.LocalPlayer
import com.music.connect.ui.MainScaffold
import com.music.connect.ui.SignInScreen
import com.music.connect.ui.theme.ConnectTheme
import com.music.player.model.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

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
    fun `playlists, offline`() {
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
        }
        val player = PlayerConnection(app) { "https://music.example.com" }
        compose.setContent {
            ConnectTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalPlayer provides player) { MainScaffold() }
                }
            }
        }
        compose.onNodeWithText("Search").assertExists()
        save("search-offline")
        compose.onNodeWithText("Playlists").performClickSafe()
        compose.waitForIdle()
        compose.onNodeWithText("Late-night drive").assertExists()
        save("playlists")
        compose.onNodeWithText("Late-night drive").performClickSafe()
        compose.waitForIdle()
        compose.onNodeWithText("Neon Tidepool").assertExists()
        save("playlist")
        compose.onNodeWithText("Settings").performClickSafe()
        compose.waitForIdle()
        save("settings")
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

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickSafe() {
        performClick()
    }
}
