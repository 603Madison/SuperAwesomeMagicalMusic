package com.music.player.ui

import com.music.player.ui.components.formatBufferLength
import com.music.player.ui.components.formatTime
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test
    fun `buffer lengths and times read naturally`() {
        assertEquals("30 s", formatBufferLength(30))
        assertEquals("5 min", formatBufferLength(300))
        assertEquals("1 min 30 s", formatBufferLength(90))
        assertEquals("1 h 30 min", formatBufferLength(5400))
        assertEquals("3:07", formatTime(187_000))
        assertEquals("1:02:05", formatTime(3_725_000))
    }
}
