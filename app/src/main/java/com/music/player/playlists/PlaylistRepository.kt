package com.music.player.playlists

import com.music.player.data.PlaylistDao
import com.music.player.data.PlaylistEntity
import com.music.player.data.PlaylistEntryEntity
import com.music.player.data.PlaylistSummary
import com.music.player.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Playlists belong to the connected Nextcloud account ([owner]), or to nobody when Nextcloud
 * isn't connected; only the current owner's playlists are listed and synced.
 */
class PlaylistRepository(
    private val dao: PlaylistDao,
    private val owner: Flow<String?>,
    /** Called after every local change so the change can be pushed to Nextcloud. */
    private val onChanged: () -> Unit,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val playlists: Flow<List<PlaylistSummary>> = owner.distinctUntilChanged().flatMapLatest { dao.observeSummaries(it) }

    suspend fun currentOwner(): String? = owner.first()

    suspend fun active(): List<PlaylistEntity> = dao.active(currentOwner())

    suspend fun entries(id: String): List<Track> = dao.entries(id).map { it.track }

    /** Finds the current owner's playlist called [name], creating it if needed. */
    suspend fun getOrCreate(name: String): String = dao.findByName(currentOwner(), name)?.id ?: create(name)

    fun playlist(id: String): Flow<PlaylistEntity?> = dao.observe(id)

    fun tracks(id: String): Flow<List<Track>> = dao.observeEntries(id).map { entries -> entries.map { it.track } }

    suspend fun create(name: String, tracks: List<Track> = emptyList()): String {
        val now = System.currentTimeMillis()
        val playlist = PlaylistEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            createdAt = now,
            updatedAt = now,
            ownerId = currentOwner(),
        )
        dao.replace(playlist, entriesFor(playlist.id, tracks.distinctBy { it.key }))
        onChanged()
        return playlist.id
    }

    /** Not synced to Nextcloud: whether a playlist stays downloaded is up to each device. */
    suspend fun setKeepDownloaded(id: String, keep: Boolean) {
        dao.setKeepDownloaded(id, keep)
        onChanged()
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
