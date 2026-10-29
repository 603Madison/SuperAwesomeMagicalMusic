package com.music.player.net

import com.music.player.quality.NetworkClass
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkClassifierTest {
    private fun classify(
        satellite: Boolean = false,
        constrained: Boolean = false,
        wifi: Boolean = false,
        cellular: Boolean = false,
        metered: Boolean = false,
    ) = NetworkClassifier.classify(satellite, constrained, wifi, cellular, metered)

    @Test
    fun `satellite transport is satellite`() {
        assertEquals(NetworkClass.SATELLITE, classify(satellite = true))
    }

    @Test
    fun `bandwidth constrained cellular (T-Satellite) is satellite`() {
        assertEquals(NetworkClass.SATELLITE, classify(constrained = true, cellular = true, metered = true))
    }

    @Test
    fun `wifi and cellular`() {
        assertEquals(NetworkClass.WIFI, classify(wifi = true))
        assertEquals(NetworkClass.CELLULAR, classify(cellular = true, metered = true))
    }

    @Test
    fun `other transports fall back on meteredness`() {
        assertEquals(NetworkClass.CELLULAR, classify(metered = true))
        assertEquals(NetworkClass.WIFI, classify())
    }

    @Test
    fun `platform constants match the hard-coded values`() {
        assertEquals(10, NetworkClassifier.TRANSPORT_SATELLITE)
        assertEquals(37, NetworkClassifier.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
    }
}
