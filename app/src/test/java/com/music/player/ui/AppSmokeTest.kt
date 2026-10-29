package com.music.player.ui

import android.app.Application
import android.content.ComponentName
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.music.player.MainActivity
import com.music.player.playback.PlaybackService
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Launches the real activity and visits every tab, to catch crashes in composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppSmokeTest {
    val compose = createAndroidComposeRule<MainActivity>()

    // Robolectric can't bind a MediaSessionService, so the UI runs without a player connection.
    private val noPlaybackService = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(noPlaybackService).around(compose)

    private fun openTab(label: String) {
        compose.onAllNodesWithText(label).onFirst().performClick()
        compose.waitForIdle()
    }

    @Test
    fun everyTabRenders() {
        compose.onNodeWithText("Songs, artists, albums").assertIsDisplayed()

        openTab("Playlists")
        compose.onNodeWithText("New playlist", useUnmergedTree = true).assertIsDisplayed()

        openTab("Radio")
        compose.onNodeWithText("Sign in to SiriusXM in Settings", substring = true).assertIsDisplayed()

        openTab("Downloads")
        compose.onNodeWithText("Nothing downloaded yet", substring = true).assertIsDisplayed()

        openTab("Settings")
        compose.onNodeWithText("Account (authentik)").assertIsDisplayed()
        compose.onNodeWithText("Streaming quality").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Satellite").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Jellyfin (preferred source)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lidarr").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SiriusXM").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nextcloud playlist sync").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun creatingAPlaylistOpensIt() {
        openTab("Playlists")
        compose.onNodeWithText("New playlist", useUnmergedTree = true).performClick()
        compose.onAllNodesWithText("Name").onFirst().performTextInput("Road Trip")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Road Trip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("0 songs · 0 downloaded").assertIsDisplayed()
    }
}
