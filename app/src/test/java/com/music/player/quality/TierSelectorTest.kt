package com.music.player.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TierSelectorTest {
    // YouTube-like audio tiers.
    private val tiers = listOf(160, 48, 128, 70, 50)

    @Test
    fun `original picks the best tier`() {
        val choice = TierSelector.choose(tiers, StreamQuality.Original) { it }!!
        assertEquals(160, choice.tier)
        assertTrue(choice.withinCap)
    }

    @Test
    fun `cap rounds down to the nearest tier`() {
        assertEquals(160, TierSelector.choose(tiers, StreamQuality.Capped(256)) { it }!!.tier)
        assertEquals(128, TierSelector.choose(tiers, StreamQuality.Capped(128)) { it }!!.tier)
        assertEquals(70, TierSelector.choose(tiers, StreamQuality.Capped(96)) { it }!!.tier)
        assertEquals(50, TierSelector.choose(tiers, StreamQuality.Capped(64)) { it }!!.tier)
    }

    @Test
    fun `cap below every tier falls back to the lowest and flags it`() {
        val choice = TierSelector.choose(listOf(320, 1411), StreamQuality.Capped(64)) { it }!!
        assertEquals(320, choice.tier)
        assertFalse(choice.withinCap)
    }

    @Test
    fun `no tiers yields null`() {
        assertNull(TierSelector.choose(emptyList<Int>(), StreamQuality.Original) { it })
    }
}
