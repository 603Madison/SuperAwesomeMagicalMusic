package com.music.player.playlists

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.music.player.widget.NowPlayingWidget
import com.music.player.widget.WidgetState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistImportTest {
    @Test
    fun `spotify playlist links of every shape give the playlist id`() {
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyPlaylists.playlistId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123"))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyPlaylists.playlistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyPlaylists.playlistId("https://open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(SpotifyPlaylists.playlistId("https://open.spotify.com/album/abc"))
        assertTrue(SpotifyPlaylists.isSpotify("https://open.spotify.com/playlist/x"))
        assertFalse(SpotifyPlaylists.isSpotify("https://music.youtube.com/playlist?list=PL1"))
    }

    @Test
    fun `a spotify playlist's name and songs are read from its embed page`() = runBlocking {
        val server = MockWebServer()
        val data = """{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Road Trip","trackList":[""" +
            """{"title":"Stick Season","subtitle":"Noah Kahan","duration":182000},""" +
            """{"title":"Here","subtitle":"Mumford & Sons, Chris Stapleton","duration":191000},""" +
            """{"title":"","subtitle":"Nobody"}]}}}}}}"""
        server.enqueue(MockResponse().setBody("""<html><script id="__NEXT_DATA__" type="application/json">$data</script></html>"""))
        server.start()
        try {
            val (name, songs) = SpotifyPlaylists(OkHttpClient(), embedBase = server.url("/embed/playlist/").toString())
                .read("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
            assertEquals("Road Trip", name)
            assertEquals(listOf("Stick Season", "Here"), songs.map { it.title })
            assertEquals("Mumford & Sons, Chris Stapleton", songs[1].artist)
            assertEquals(182_000L, songs[0].durationMs)
            assertEquals("/embed/playlist/37i9dQZF1DXcBWIGoYBM5M", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `the widget shows what's playing once placed, and is skipped when it isn't`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // No widgets placed: nothing to do, nothing to fail.
        NowPlayingWidget.update(context, WidgetState(title = "Stick Season", artist = "Noah Kahan", isPlaying = true))
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(NowPlayingWidget::class.java, com.music.player.R.layout.widget_now_playing)
        NowPlayingWidget.update(context, WidgetState(title = "Stick Season", artist = "Noah Kahan", isPlaying = true))
        val view = shadowOf(manager).getViewFor(id)
        assertEquals("Stick Season", view.findViewById<android.widget.TextView>(com.music.player.R.id.widget_title).text.toString())
        assertTrue(NowPlayingWidget.hasWidgets(context))
        assertTrue(manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java)).contains(id))
    }
}
