package com.music.player.ui

import android.app.Application
import android.content.ComponentName
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.music.player.MainActivity
import com.music.player.MusicApp
import com.music.player.playback.PlaybackService
import com.music.player.playlists.PlaylistReaders
import com.music.player.settings.JellyfinConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Searching for an artist and opening their page and album, and importing a typed list, against a stand-in Jellyfin. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowseTest {
    private val jellyfin = MockWebServer()

    private val setUp = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
            jellyfin.dispatcher = library()
            jellyfin.start()
            val container = (app as MusicApp).container
            runBlocking {
                container.settings.setJellyfin(JellyfinConfig(jellyfin.url("/").toString().trimEnd('/'), "me", "u1", "token"))
                container.settings.setYouTubeMusicEnabled(false)
            }
        }

        override fun after() = jellyfin.shutdown()
    }

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(setUp).around(compose)

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun anArtistsNameFindsThemAndTheirAlbum() {
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("velvet satellites")
        waitForText("Top result")
        compose.onAllNodesWithText("Velvet Satellites").onFirst().performClick()
        waitForText("Popular")
        compose.waitForIdle()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Low Orbit"))
        compose.onAllNodesWithText("Low Orbit").onFirst().performClick()
        waitForText("2 songs", substring = true)
        compose.waitForIdle()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Paper Lanterns"))
        compose.onAllNodesWithText("Paper Lanterns").onFirst().assertExists()
    }

    @Test
    fun aTypedListIsImported() = runBlocking {
        val container = ApplicationProvider.getApplicationContext<MusicApp>().container
        val result = container.playlistImporter.importText("Velvet Satellites - Neon Tidepool\nNobody - Nothing At All", "Typed")
        assertEquals(1, result.added)
        assertEquals(listOf("Nothing At All"), result.notFound.map { it.title })
        assertEquals("Neon Tidepool", container.playlists.entries(result.playlistId).single().title)
        assertEquals(PlaylistReaders.parseText("Nobody - Nothing At All").single(), result.notFound.single())
    }

    /** The artist Velvet Satellites, their album Low Orbit, and its two songs. */
    private fun library() = object : Dispatcher() {
        val song = """{"Id":"song1","Name":"Neon Tidepool","Artists":["Velvet Satellites"],"Album":"Low Orbit","AlbumId":"al1","RunTimeTicks":2000000000}"""
        val song2 = """{"Id":"song2","Name":"Paper Lanterns","Artists":["Velvet Satellites"],"Album":"Low Orbit","AlbumId":"al1","RunTimeTicks":2140000000}"""
        val album = """{"Id":"al1","Name":"Low Orbit","AlbumArtist":"Velvet Satellites","ProductionYear":2024,"ChildCount":8}"""
        val artist = """{"Id":"ar1","Name":"Velvet Satellites"}"""

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val term = url.queryParameter("searchTerm")?.lowercase()
            fun named(name: String) = term != null && term.length > 2 && name.lowercase().contains(term)
            val items = when {
                url.encodedPath == "/Artists" -> listOf(artist).filter { named("Velvet Satellites") }
                url.queryParameter("includeItemTypes") == "MusicAlbum" -> listOf(album).filter {
                    url.queryParameter("ids") == "al1" || url.queryParameter("albumArtistIds") == "ar1" || url.queryParameter("artistIds") == "ar1" || named("Low Orbit")
                }
                url.encodedPath != "/Items" -> return MockResponse().setResponseCode(404)
                url.queryParameter("ids") == "ar1" -> listOf(artist)
                url.queryParameter("parentId") == "al1" || url.queryParameter("artistIds") == "ar1" -> listOf(song, song2)
                else -> listOf(song).filter { named("Neon Tidepool") }
            }
            return MockResponse().setBody("""{"Items":[${items.joinToString(",")}]}""")
        }
    }
}
