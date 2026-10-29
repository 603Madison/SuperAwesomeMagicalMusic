package com.music.player.playlists

import com.music.player.data.PlaylistDao
import com.music.player.data.PlaylistEntity
import com.music.player.data.PlaylistEntryEntity
import com.music.player.data.PlaylistSummary
import com.music.player.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class PlaylistRepository(
    private val dao: PlaylistDao,
    /** Called after every local change so the change can be pushed to Nextcloud. */
    private val onChanged: () -> Unit,
) {
    val playlists: Flow<List<PlaylistSummary>> = dao.observeSummaries()

    fun playlist(id: String): Flow<PlaylistEntity?> = dao.observe(id)

    fun tracks(id: String): Flow<List<Track>> = dao.observeEntries(id).map { entries -> entries.map { it.track } }

    suspend fun create(name: String, tracks: List<Track> = emptyList()): String {
        val now = System.currentTimeMillis()
        val playlist = PlaylistEntity(id = UUID.randomUUID().toString(), name = name.trim(), createdAt = now, updatedAt = now)
        dao.replace(playlist, entriesFor(playlist.id, tracks.distinctBy { it.key }))
        onChanged()
        return playlist.id
    }

    suspend fun rename(id: String, name: String) {
        val playlist = dao.get(id) ?: return
        dao.upsert(playlist.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
        onChanged()
    }

    suspend fun addTracks(id: String, tracks: List<Track>) = edit(id) { current ->
        val existing = current.map { it.key }.toSet()
        current + tracks.filter { it.key !in existing }.distinctBy { it.key }
    }

    suspend fun removeAt(id: String, position: Int) = edit(id) { current ->
        current.filterIndexed { index, _ -> index != position }
    }

    suspend fun move(id: String, from: Int, to: Int) = edit(id) { current ->
        if (from !in current.indices || to !in current.indices) return@edit current
        current.toMutableList().apply { add(to, removeAt(from)) }
    }

    suspend fun delete(id: String) {
        val playlist = dao.get(id) ?: return
        if (playlist.remoteFileName == null) {
            dao.purge(id)
        } else {
            // Keep a tombstone until the next sync removes the file from Nextcloud.
            dao.replace(playlist.copy(deleted = true, updatedAt = System.currentTimeMillis()), emptyList())
            onChanged()
        }
    }

    private suspend fun edit(id: String, transform: (List<Track>) -> List<Track>) {
        val playlist = dao.get(id) ?: return
        val current = dao.entries(id).map { it.track }
        val updated = transform(current)
        if (updated == current) return
        dao.replace(playlist.copy(updatedAt = System.currentTimeMillis()), entriesFor(id, updated))
        onChanged()
    }

    private fun entriesFor(playlistId: String, tracks: List<Track>) =
        tracks.mapIndexed { index, track -> PlaylistEntryEntity(playlistId, index, track) }
}
