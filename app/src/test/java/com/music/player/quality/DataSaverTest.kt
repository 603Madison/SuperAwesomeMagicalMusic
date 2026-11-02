package com.music.player.quality

import com.music.player.net.NetworkState
import com.music.player.settings.QualitySettings
import org.junit.Assert.assertEquals
import org.junit.Test

class DataSaverTest {
    private val quality = QualitySettings(
        wifi = StreamQuality.Original,
        cellular = StreamQuality.Capped(192),
        satellite = StreamQuality.Capped(64),
        dataSaver = StreamQuality.Capped(96),
    )

    @Test
    fun `data saver lowers a metered network's bitrate to its own`() {
        val cellular = NetworkState(NetworkClass.CELLULAR, metered = true, dataSaver = true)
        assertEquals(StreamQuality.Capped(96), quality.forState(cellular))
    }

    @Test
    fun `whichever is lower wins`() {
        // Satellite is already below the Data Saver bitrate.
        val satellite = NetworkState(NetworkClass.SATELLITE, metered = true, dataSaver = true)
        assertEquals(StreamQuality.Capped(64), quality.forState(satellite))
        // Original on a metered hotspot is capped too.
        val hotspot = NetworkState(NetworkClass.WIFI, metered = true, dataSaver = true)
        assertEquals(StreamQuality.Capped(96), quality.forState(hotspot))
    }

    @Test
    fun `without data saver, or on unmetered wi-fi, the network's setting applies`() {
        assertEquals(StreamQuality.Capped(192), quality.forState(NetworkState(NetworkClass.CELLULAR, metered = true)))
        assertEquals(StreamQuality.Original, quality.forState(NetworkState(NetworkClass.WIFI, metered = false, dataSaver = true)))
    }
}
