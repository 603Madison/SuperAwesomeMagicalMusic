package com.music.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BufferControlPlayerTest {
    @Test
    fun `a player can be built and released with the buffer control`() {
        // LoadControl's Java default methods throw; any that isn't forwarded crashes the player.
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setLoadControl(BufferControl { 300_000L })
            .build()
        player.prepare()
        player.release()
    }

    @Test
    fun `every LoadControl method ExoPlayer calls is forwarded`() {
        val missing = LoadControl::class.java.methods
            .filter { it.isDefault && !it.isAnnotationPresent(java.lang.Deprecated::class.java) }
            .filterNot { method ->
                runCatching { BufferControl::class.java.getDeclaredMethod(method.name, *method.parameterTypes) }.isSuccess
            }
            .map { it.name }
        assertEquals(emptyList<String>(), missing)
    }
}
