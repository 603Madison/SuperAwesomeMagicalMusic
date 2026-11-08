package com.music.connect.data

import android.util.Log
import com.music.connect.net.ApiJson
import com.music.connect.net.ApiTrack
import com.music.connect.net.Playlist
import com.music.connect.net.ServerApi
import com.music.connect.net.ServerException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

/** A playlist as the app shows it. */
data class PlaylistInfo(val id: String, val name: String, val count: Int, val artwork: List<String>, val keepOffline: Boolean)

data class Entry(val entryId: String, val track: ApiTrack)

/**
 * The person's playlists, kept on the phone so they're there offline (and on satellite, without
 * waiting for the server). Changes apply here at once and are queued for the server; [sync]
 * sends them, in order, then brings the phone's copies up to date. A change the server refuses
 * (the playlist was deleted elsewhere, say) is dropped rather than retried forever.
 */
class Library(
    private val db: ConnectDb,
    private val api: ServerApi,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called after a change, to send it when there's a connection. */
    private val onChanged: () -> Unit = {},
) {
    private val playlists = db.playlists()
    private val pending = db.pending()
    private val syncLock = Mutex()

    val all: Flow<List<PlaylistInfo>> = combine(playlists.all(), playlists.counts()) { rows, counts ->
        val byId = counts.associate { it.playlistId to it.count }
        rows.map { it.info(byId[it.id] ?: 0) }
    }

    fun playlist(id: String): Flow<PlaylistInfo?> = combine(playlists.one(id), playlists.entries(id)) { row, entries -> row?.info(entries.size) }

    fun entries(id: String): Flow<List<Entry>> = playlists.entries(id).map { rows -> rows.mapNotNull { it.toEntry() } }

    suspend fun entriesNow(id: String): List<Entry> = playlists.entriesNow(id).mapNotNull { it.toEntry() }

    val pendingCount: Flow<Int> = pending.count()

    suspend fun create(name: String, tracks: List<ApiTrack> = emptyList()): String {
        val id = newId()
        val now = clock()
        playlists.replace(PlaylistRow(id, name.trim().ifBlank { "New playlist" }, 0, now), tracks.mapIndexed { i, t -> entryRow(id, "local-${newId()}", i, t, now) })
        queue(KIND_CREATE, id, ApiJson.encodeToString(CreatePayload.serializer(), CreatePayload(name, tracks)))
        return id
    }

    suspend fun add(id: String, tracks: List<ApiTrack>) {
        val songs = tracks.filter { it.isSong || it.isStationTrack }.map(::asSong)
        if (songs.isEmpty()) return
        val start = playlists.count(id)
        val localIds = songs.map { "local-${newId()}" }
        playlists.insertEntries(songs.mapIndexed { i, t -> entryRow(id, localIds[i], start + i, t, clock()) })
        queue(KIND_ADD, id, ApiJson.encodeToString(AddPayload.serializer(), AddPayload(songs, localIds)))
    }

    suspend fun remove(id: String, entryIds: List<String>) {
        playlists.deleteEntries(id, entryIds)
        val (local, server) = entryIds.partition { it.startsWith("local-") }
        // Songs added here and not sent yet just leave the waiting change.
        if (local.isNotEmpty()) {
            pending.all().filter { it.playlistId == id && it.kind == KIND_ADD }.forEach { row ->
                val add = ApiJson.decodeFromString(AddPayload.serializer(), row.payload)
                val keep = add.localIds.indices.filter { add.localIds[it] !in local }
                if (keep.size == add.localIds.size) return@forEach
                if (keep.isEmpty()) pending.delete(row.id)
                else pending.update(row.copy(payload = ApiJson.encodeToString(AddPayload.serializer(), AddPayload(keep.map { add.tracks[it] }, keep.map { add.localIds[it] }))))
            }
        }
        if (server.isNotEmpty()) queue(KIND_REMOVE, id, ApiJson.encodeToString(ListSerializer(String.serializer()), server))
    }

    suspend fun rename(id: String, name: String) {
        val row = playlists.get(id) ?: return
        playlists.upsert(row.copy(name = name.trim()))
        queue(KIND_RENAME, id, ApiJson.encodeToString(String.serializer(), name.trim()))
    }

    suspend fun delete(id: String) {
        playlists.delete(id)
        playlists.clearEntries(id)
        queue(KIND_DELETE, id, "\"\"")
    }

    /** Reordering needs the server (another device may have changed the playlist meanwhile). */
    suspend fun reorder(id: String, order: List<String>) {
        sync()
        val row = playlists.get(id) ?: return
        if (order.any { it.startsWith("local-") }) throw ServerException(0, "Wait for the playlist to finish syncing first")
        store(api.reorderPlaylist(id, row.revision, order))
    }

    suspend fun setKeepOffline(id: String, keep: Boolean) {
        val row = playlists.get(id) ?: return
        playlists.upsert(row.copy(keepOffline = keep))
    }

    suspend fun offlinePlaylists(): List<String> = playlists.allNow().filter { it.keepOffline }.map { it.id }

    /** Sends waiting changes, then brings every playlist up to date. Throws if offline. */
    suspend fun sync() = syncLock.withLock {
        for (row in pending.all()) {
            try {
                apply(row)
            } catch (e: ServerException) {
                // The server understood and said no (404 for a playlist deleted elsewhere…): drop it.
                if (e.code in 400..499 && e.code != 401 && e.code != 408 && e.code != 429) {
                    Log.w(TAG, "Dropping a change the server refused: ${row.kind} ${e.message}")
                } else {
                    throw e
                }
            }
            pending.delete(row.id)
        }
        val remote = api.playlists()
        val local = playlists.allNow().associateBy { it.id }
        for (summary in remote) {
            val mine = local[summary.id]
            if (mine == null || mine.revision != summary.revision || playlists.count(summary.id) != summary.count) {
                store(api.playlist(summary.id), keepOffline = mine?.keepOffline ?: false)
            }
        }
        val remoteIds = remote.map { it.id }.toSet()
        // Deleted elsewhere (nothing here is waiting to create it: the queue is empty now).
        local.keys.filter { it !in remoteIds }.forEach { playlists.delete(it); playlists.clearEntries(it) }
    }

    private suspend fun apply(row: PendingRow) {
        when (row.kind) {
            KIND_CREATE -> {
                val create = ApiJson.decodeFromString(CreatePayload.serializer(), row.payload)
                try {
                    api.createPlaylist(row.playlistId, create.name, create.tracks)
                } catch (e: ServerException) {
                    if (e.code != 409) throw e // Already there: an earlier try got through.
                }
            }
            KIND_ADD -> api.addToPlaylist(row.playlistId, ApiJson.decodeFromString(AddPayload.serializer(), row.payload).tracks)
            KIND_REMOVE -> api.removeFromPlaylist(row.playlistId, ApiJson.decodeFromString(ListSerializer(String.serializer()), row.payload))
            KIND_RENAME -> api.renamePlaylist(row.playlistId, ApiJson.decodeFromString(String.serializer(), row.payload))
            KIND_DELETE -> try {
                api.deletePlaylist(row.playlistId)
            } catch (e: ServerException) {
                if (e.code != 404) throw e
            }
        }
    }

    private suspend fun store(playlist: Playlist, keepOffline: Boolean? = null) {
        val summary = playlist.summary
        val keep = keepOffline ?: playlists.get(summary.id)?.keepOffline ?: false
        playlists.replace(
            PlaylistRow(summary.id, summary.name, summary.revision, summary.updatedAt, ApiJson.encodeToString(ListSerializer(String.serializer()), summary.artwork), keep),
            playlist.entries.mapIndexed { i, e -> entryRow(summary.id, e.entryId, i, e.track, e.addedAt) },
        )
    }

    private suspend fun queue(kind: String, playlistId: String, payload: String) {
        pending.add(PendingRow(kind = kind, playlistId = playlistId, payload = payload, createdAt = clock()))
        onChanged()
    }

    private fun entryRow(playlistId: String, entryId: String, position: Int, track: ApiTrack, addedAt: Long) =
        EntryRow(playlistId, entryId, position, ApiJson.encodeToString(ApiTrack.serializer(), track), addedAt)

    private fun EntryRow.toEntry(): Entry? =
        runCatching { Entry(entryId, ApiJson.decodeFromString(ApiTrack.serializer(), track)) }.getOrNull()

    private fun PlaylistRow.info(count: Int) = PlaylistInfo(
        id, name, count,
        runCatching { ApiJson.decodeFromString(ListSerializer(String.serializer()), artwork) }.getOrDefault(emptyList()),
        keepOffline,
    )

    @Serializable private data class CreatePayload(val name: String, val tracks: List<ApiTrack>)
    @Serializable private data class AddPayload(val tracks: List<ApiTrack>, val localIds: List<String>)

    companion object {
        private const val TAG = "Library"
        const val KIND_CREATE = "create"
        const val KIND_ADD = "add"
        const val KIND_REMOVE = "remove"
        const val KIND_RENAME = "rename"
        const val KIND_DELETE = "delete"
        private val random = SecureRandom()

        fun newId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))

        /** A Pandora station track's link expires, so it's kept as the song itself. */
        fun asSong(track: ApiTrack): ApiTrack =
            if (track.isStationTrack) track.copy(sourceId = "song:" + track.sourceId.substringAfterLast(':'), via = null) else track

        /** Whether [e] means the server couldn't be reached (rather than a refusal). */
        fun isOffline(e: Throwable): Boolean = e is IOException && e !is ServerException
    }
}
