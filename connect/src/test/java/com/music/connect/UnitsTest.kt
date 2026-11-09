package com.music.connect

import com.music.connect.data.Library
import com.music.connect.data.Settings
import com.music.connect.net.ApiTrack
import com.music.connect.net.ServerApi
import com.music.connect.net.SignIn
import com.music.connect.playback.MediaItems
import com.music.player.model.SourceType
import com.music.player.quality.NetworkClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnitsTest {
    @Test
    fun `the bitrate follows the network`() {
        val settings = Settings(kbpsWifi = 0, kbpsCellular = 320, kbpsSatellite = 64)
        assertEquals(0, settings.kbpsFor(NetworkClass.WIFI))
        assertEquals(320, settings.kbpsFor(NetworkClass.CELLULAR))
        assertEquals(64, settings.kbpsFor(NetworkClass.SATELLITE))
    }

    @Test
    fun `songs survive the trip through media item ids`() {
        val track = ApiTrack(SourceType.JELLYFIN, "abc", "Moth Light", "Hollow Pines", album = "Evergreen Hours", durationMs = 200_000, via = "src1")
        assertEquals(track, MediaItems.track(MediaItems.id(track)))
        assertNull(MediaItems.track("playlist:x"))
    }

    @Test
    fun `live radio goes straight to the server`() {
        val channel = ApiTrack(SourceType.SIRIUSXM, "channel:channel-linear:abc-123", "Hits 1", "SiriusXM", via = "sxm")
        assertEquals("/api/live/sxm/sxm/channel/channel-linear/abc-123" to true, MediaItems.livePath(channel))
        val radio = ApiTrack(SourceType.RADIO, "hls:uuid-1", "BBC", "UK", via = "r")
        assertEquals("/api/live/radio/r/station/uuid-1/stream" to true, MediaItems.livePath(radio))
        assertTrue(channel.isLive && radio.isLive)
        val station = ApiTrack(SourceType.SIRIUSXM, "stationtrack:st1:tr9", "Song", "Artist", via = "sxm")
        assertEquals("/api/live/sxm/sxm/station-track/tr9" to false, MediaItems.livePath(station))
        // Kept in playlists as the song itself, since station links expire.
        assertEquals(ApiTrack(SourceType.SIRIUSXM, "song:tr9", "Song", "Artist"), Library.asSong(station))
    }

    @Test
    fun `addresses and PKCE are what the server expects`() {
        assertEquals("https://music.example.com", ServerApi.normalize(" music.example.com/ "))
        assertEquals("http://192.168.1.5:8080", ServerApi.normalize("http://192.168.1.5:8080/"))
        assertNull(ServerApi.normalize(""))
        val challenge = SignIn.challenge(SignIn.token(48))
        assertTrue(challenge.matches(Regex("[A-Za-z0-9_-]{43}")))
    }
}
