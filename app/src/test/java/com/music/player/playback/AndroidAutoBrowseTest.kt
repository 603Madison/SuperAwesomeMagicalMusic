package com.music.player.playback

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.service.media.MediaBrowserService
import android.support.v4.media.MediaBrowserCompat
import androidx.test.core.app.ApplicationProvider
import com.music.player.MusicApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Browses the library the way Android Auto does: with AndroidX's MediaBrowserCompat, the hints
 * Android Auto sends, and paged subscriptions, over the legacy MediaBrowserService interface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAutoBrowseTest {
    private val app = ApplicationProvider.getApplicationContext<MusicApp>()

    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    /** Root hints Android Auto sends when it connects. */
    private fun autoHints() = Bundle().apply {
        putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
        putInt("androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT", 4)
        putInt("androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_SUPPORTED_FLAGS", MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
        putInt("android.media.extras.MEDIA_ART_SIZE_HINT_PIXELS", 256)
    }

    @Test
    fun `android auto's media browser connects and browses every folder`() {
        val component = ComponentName(app, PlaybackService::class.java)
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val binder = service.get().onBind(Intent(MediaBrowserService.SERVICE_INTERFACE).setComponent(component))
            shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
            val playlist = kotlinx.coroutines.runBlocking {
                app.container.playlists.create(
                    "Road Trip",
                    listOf(com.music.player.model.Track(com.music.player.model.SourceType.JELLYFIN, "1", "One", "Artist")),
                )
            }

            var connected = false
            var failed = false
            val browser = MediaBrowserCompat(
                app,
                component,
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() { connected = true }
                    override fun onConnectionFailed() { failed = true }
                },
                autoHints(),
            )
            browser.connect()
            waitFor("the connection") { connected || failed }
            assertTrue("connection refused", connected)

            fun children(parentId: String): List<MediaBrowserCompat.MediaItem> {
                var items: List<MediaBrowserCompat.MediaItem>? = null
                var error = false
                val paging = Bundle().apply {
                    putInt(MediaBrowserCompat.EXTRA_PAGE, 0)
                    putInt(MediaBrowserCompat.EXTRA_PAGE_SIZE, 100)
                }
                browser.subscribe(
                    parentId,
                    paging,
                    object : MediaBrowserCompat.SubscriptionCallback() {
                        override fun onChildrenLoaded(parentId: String, children: List<MediaBrowserCompat.MediaItem>, options: Bundle) {
                            items = children
                        }

                        override fun onError(parentId: String, options: Bundle) {
                            error = true
                        }
                    },
                )
                waitFor("$parentId's children") { items != null || error }
                check(!error) { "Browsing $parentId failed" }
                browser.unsubscribe(parentId)
                return items!!
            }

            val root = children(browser.root)
            assertEquals(listOf(LibraryIds.PLAYLISTS, LibraryIds.DOWNLOADS), root.map { it.mediaId })
            assertTrue(root.all { it.isBrowsable })
            val playlists = children(LibraryIds.PLAYLISTS)
            assertEquals(listOf(LibraryIds.playlist(playlist)), playlists.map { it.mediaId })
            assertEquals(listOf("One"), children(playlists.single().mediaId!!).map { it.description.title.toString() })
            children(LibraryIds.DOWNLOADS)
            // Each request is recorded for troubleshooting.
            assertTrue(app.container.appLog.text().contains("children of ${LibraryIds.PLAYLISTS}"))
            browser.disconnect()
        } finally {
            service.destroy()
        }
    }
}
