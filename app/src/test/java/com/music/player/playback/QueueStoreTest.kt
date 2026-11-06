package com.music.player.playback

import com.music.player.model.SourceType
import com.music.player.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueueStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the queue is remembered across restarts`() = runTest {
        val file = folder.newFile("queue.json").also { it.delete() }
        assertNull(QueueStore(file).load())
        val queue = SavedQueue(
            listOf(Track(SourceType.JELLYFIN, "1", "One", "A"), Track(SourceType.YOUTUBE_MUSIC, "2", "Two", "B")),
            index = 1,
            positionMs = 42_000,
            savedAt = 5,
        )
        QueueStore(file).save(queue)
        assertEquals(queue, QueueStore(file).load())
    }
}
