package com.music.player.playback

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackServiceTest {
    @Test
    fun `the playback service starts and stops`() {
        // The app connects to the service at launch, so a crash here crashes the app.
        Robolectric.buildService(PlaybackService::class.java).create().destroy()
    }
}
