package com.music.player.nextcloud

import com.music.player.data.PlaylistEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSyncDecisionTest {
    private fun playlist(updatedAt: Long, syncedUpdatedAt: Long?, deleted: Boolean = false, name: String = "Road Trip") =
        PlaylistEntity(
            id = "12345678-aaaa",
            name = name,
            createdAt = 0,
            updatedAt = updatedAt,
            deleted = deleted,
            syncedUpdatedAt = syncedUpdatedAt,
        )

    @Test
    fun `unknown remote playlists are imported`() {
        assertEquals(RemoteAction.IMPORT, PlaylistSyncService.decide(null, 100))
    }

    @Test
    fun `unchanged local copy takes remote edits`() {
        assertEquals(RemoteAction.OVERWRITE_LOCAL, PlaylistSyncService.decide(playlist(100, 100), 200))
    }

    @Test
    fun `newer local edits win`() {
        assertEquals(RemoteAction.KEEP_LOCAL, PlaylistSyncService.decide(playlist(300, 100), 200))
    }

    @Test
    fun `newer remote edits win a conflict`() {
        assertEquals(RemoteAction.OVERWRITE_LOCAL, PlaylistSyncService.decide(playlist(150, 100), 200))
    }

    @Test
    fun `locally deleted playlists stay deleted`() {
        assertEquals(RemoteAction.IGNORE_DELETED, PlaylistSyncService.decide(playlist(300, 100, deleted = true), 200))
    }

    @Test
    fun `file names are sanitized and made unique`() {
        assertEquals("AC_DC_ Best.json", PlaylistSyncService.fileNameFor(playlist(0, null, name = "AC/DC: Best"), emptySet()))
        assertEquals(
            "Road Trip (12345678).json",
            PlaylistSyncService.fileNameFor(playlist(0, null), setOf("Road Trip.json")),
        )
        assertEquals(
            "Road Trip.json",
            PlaylistSyncService.fileNameFor(playlist(0, null).copy(remoteFileName = "Road Trip.json"), setOf("Road Trip.json")),
        )
    }
}
