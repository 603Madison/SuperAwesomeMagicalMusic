package com.music.player.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradesTest {
    @Test
    fun `lossy copies are replaced by clearly better ones`() {
        assertTrue(Upgrades.isBetter(70, false, 160))
        assertTrue(Upgrades.isBetter(160, false, 320))
        assertFalse(Upgrades.isBetter(160, false, 170))
        assertFalse(Upgrades.isBetter(320, false, 160))
    }

    @Test
    fun `lossless beats lossy, and only hi-res beats lossless`() {
        assertTrue(Upgrades.isBetter(320, false, 1411))
        assertFalse(Upgrades.isBetter(900, true, 1411))
        assertFalse(Upgrades.isBetter(1411, true, 1411))
        assertTrue(Upgrades.isBetter(1411, true, 4608))
    }

    @Test
    fun `unknown bitrates never trigger a re-download`() {
        assertFalse(Upgrades.isBetter(null, false, 320))
        assertFalse(Upgrades.isBetter(160, false, null))
    }
}
