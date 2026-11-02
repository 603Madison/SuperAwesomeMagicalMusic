package com.music.player.ui

import com.music.player.readableError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableErrorTest {
    @Test
    fun `binary data in an error is never shown`() {
        val garbage = "Couldn't load: " + String(CharArray(3000) { (it * 7919 % 0xFFFF).toChar() })
        val shown = readableError(garbage)
        assertTrue(shown.length <= 241)
        assertTrue(shown.startsWith("Couldn't load: "))
        assertTrue(shown.none { it.isISOControl() })
    }

    @Test
    fun `ordinary messages pass through`() {
        assertEquals("Couldn't start Noah Kahan Radio: HTTP 403", readableError("Couldn't start Noah Kahan Radio: HTTP 403"))
    }
}
