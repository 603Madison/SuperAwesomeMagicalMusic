package com.music.player.bookmarks

import com.music.player.model.SourceType
import com.music.player.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookmarksTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val song = Track(SourceType.SIRIUSXM, "song:abc", "Here", "Mumford & Sons")

    @Test
    fun `bookmarks are kept across restarts until saved or discarded`() = runTest {
        val file = folder.newFile("bookmarks.json").also { it.delete() }
        val bookmarks = Bookmarks(file, clock = { 1_000L })
        val added = bookmarks.add(song, heard = true, heardOn = "Noah Kahan Radio", playingKey = "key-1")

        val reopened = Bookmarks(file)
        val restored = reopened.items.value.single()
        assertEquals(added, restored)
        assertEquals("Noah Kahan Radio", restored.heardOn)
        assertTrue(restored.heard)
        assertEquals(added.id, reopened.forPlaying("key-1")?.id)

        reopened.remove(added.id)
        assertTrue(Bookmarks(file).items.value.isEmpty())
        assertNull(reopened.forPlaying("key-1"))
    }

    @Test
    fun `an unreadable file starts empty rather than failing`() {
        val file = folder.newFile("bookmarks.json").apply { writeText("not json") }
        assertTrue(Bookmarks(file).items.value.isEmpty())
    }
}
