package com.music.player.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the file is appended to, and compacted to the latest lines when it doubles`() {
        val file = folder.newFile("log.txt")
        val log = AppLog(file, clock = { 0L }, writer = { it.run() })
        repeat(799) { log.log("t", "line $it") }
        // Appended, not rewritten each time.
        assertEquals(799, file.readLines().size)
        log.log("t", "line 799")
        val compacted = file.readLines()
        assertEquals(400, compacted.size)
        assertTrue(compacted.first().endsWith("line 400"))
        assertTrue(compacted.last().endsWith("line 799"))

        // Restarting picks up where it left off.
        val reopened = AppLog(file, clock = { 0L }, writer = { it.run() })
        assertTrue(reopened.text().lines().last().endsWith("line 799"))
        assertEquals(400, reopened.text().lines().size)
    }
}
