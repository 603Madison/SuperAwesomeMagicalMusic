package com.music.server.playlists

import com.music.server.ApiError
import com.music.server.db.Database
import com.music.server.db.Tx
import com.music.server.music.ApiTrack
import com.music.server.util.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlaylistSummary(
    val id: String,
    val name: String,
    val description: String,
    val count: Int,
    val durationMs: Long,
    val createdAt: Long,
    val updatedAt: Long,
    /** Goes up with every change, so the app can tell whether its copy is current. */
    val revision: Long,
    /** Artwork of the first few songs, for a cover. */
    val artwork: List<String>,
    /** When it was last played (on any of the person's devices), if ever. */
    val playedAt: Long? = null,
    /** How many of its songs weren't found (kept greyed out, and looked for again now and then). */
    val missing: Int = 0,
)

@Serializable
data class PlaylistEntry(val entryId: String, val track: ApiTrack, val addedAt: Long)

@Serializable
data class Playlist(val summary: PlaylistSummary, val entries: List<PlaylistEntry>)

/** Each person's playlists. Every change bumps the playlist's revision. */
class PlaylistStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun list(userId: String): List<PlaylistSummary> = db.tx {
        query("SELECT id FROM playlists WHERE user_id = ? ORDER BY name COLLATE NOCASE", userId) { it.string("id") }
            .mapNotNull { summary(it) }
    }

    suspend fun get(userId: String, id: String): Playlist = db.tx {
        owned(userId, id)
        Playlist(summary(id)!!, entries(id))
    }

    suspend fun create(userId: String, name: String, description: String = "", tracks: List<ApiTrack> = emptyList(), id: String? = null): Playlist {
        val playlistId = id?.takeIf { it.matches(ID) } ?: Random.id()
        val now = clock()
        db.tx {
            if (one("SELECT 1 FROM playlists WHERE id = ?", playlistId) { true } == true) throw ApiError.conflict("That playlist already exists")
            if (long("SELECT COUNT(*) FROM playlists WHERE user_id = ?", userId) >= MAX_PLAYLISTS) throw ApiError.badRequest("That's a lot of playlists: $MAX_PLAYLISTS is the limit")
            update(
                "INSERT INTO playlists (id, user_id, name, description, created_at, updated_at, revision) VALUES (?, ?, ?, ?, ?, ?, 1)",
                playlistId, userId, cleanName(name), description.take(2000), now, now,
            )
            insertEntries(playlistId, tracks, 0, now)
        }
        return get(userId, playlistId)
    }

    suspend fun rename(userId: String, id: String, name: String?, description: String?): PlaylistSummary = db.tx {
        owned(userId, id)
        name?.let { update("UPDATE playlists SET name = ? WHERE id = ?", cleanName(it), id) }
        description?.let { update("UPDATE playlists SET description = ? WHERE id = ?", it.take(2000), id) }
        touch(id)
        summary(id)!!
    }

    suspend fun delete(userId: String, id: String) = db.tx {
        owned(userId, id)
        update("DELETE FROM playlists WHERE id = ?", id)
    }

    /** Adds [tracks] at [position] (the end if null). */
    suspend fun add(userId: String, id: String, tracks: List<ApiTrack>, position: Int? = null): Playlist = db.tx {
        owned(userId, id)
        val count = long("SELECT COUNT(*) FROM playlist_entries WHERE playlist_id = ?", id).toInt()
        if (count + tracks.size > MAX_ENTRIES) throw ApiError.badRequest("Playlists hold up to $MAX_ENTRIES songs")
        val at = (position ?: count).coerceIn(0, count)
        update("UPDATE playlist_entries SET position = position + ? WHERE playlist_id = ? AND position >= ?", tracks.size, id, at)
        insertEntries(id, tracks, at, clock())
        touch(id)
        Playlist(summary(id)!!, entries(id))
    }

    suspend fun remove(userId: String, id: String, entryIds: Set<String>): Playlist = db.tx {
        owned(userId, id)
        entryIds.forEach { update("DELETE FROM playlist_entries WHERE playlist_id = ? AND entry_id = ?", id, it) }
        renumber(id, entries(id).map { it.entryId })
        touch(id)
        Playlist(summary(id)!!, entries(id))
    }

    /**
     * Puts the playlist's songs in [order] (entry ids; any left out are removed), if it's still
     * at [revision]: someone else's change in between is not overwritten.
     */
    suspend fun reorder(userId: String, id: String, revision: Long, order: List<String>): Playlist = db.tx {
        owned(userId, id)
        val current = long("SELECT revision FROM playlists WHERE id = ?", id)
        if (current != revision) throw ApiError.conflict("The playlist changed elsewhere; reload it and try again")
        val existing = entries(id).map { it.entryId }.toSet()
        val kept = order.distinct().filter { it in existing }
        (existing - kept.toSet()).forEach { update("DELETE FROM playlist_entries WHERE playlist_id = ? AND entry_id = ?", id, it) }
        renumber(id, kept)
        touch(id)
        Playlist(summary(id)!!, entries(id))
    }

    /** Notes that the person started playing it (the sidebar lists playlists by when they were played). */
    suspend fun played(userId: String, id: String) = db.tx {
        owned(userId, id)
        update("UPDATE playlists SET played_at = ? WHERE id = ?", clock(), id)
    }

    /** Songs that weren't found, across everyone's playlists, for looking for again: (user, playlist, entry). */
    suspend fun missingEntries(limit: Int): List<Triple<String, String, PlaylistEntry>> = db.tx {
        query(
            "SELECT p.user_id, e.* FROM playlist_entries e JOIN playlists p ON p.id = e.playlist_id WHERE e.track LIKE ? ORDER BY e.added_at LIMIT ?",
            "%\"sourceId\":\"${com.music.player.model.Track.MISSING_PREFIX}%", limit,
        ) { row ->
            Triple(row.string("user_id"), row.string("playlist_id"), PlaylistEntry(row.string("entry_id"), TrackJson.decodeFromString(ApiTrack.serializer(), row.string("track")), row.long("added_at")))
        }
    }

    /** Puts songs found since in place of stand-ins ([replacements]: entry id to the song found). */
    suspend fun replace(userId: String, id: String, replacements: Map<String, ApiTrack>): Playlist = db.tx {
        owned(userId, id)
        replacements.forEach { (entryId, track) ->
            update("UPDATE playlist_entries SET track = ? WHERE playlist_id = ? AND entry_id = ?", TrackJson.encodeToString(ApiTrack.serializer(), track), id, entryId)
        }
        if (replacements.isNotEmpty()) touch(id)
        Playlist(summary(id)!!, entries(id))
    }

    private fun Tx.owned(userId: String, id: String) {
        val owner = one("SELECT user_id FROM playlists WHERE id = ?", id) { it.string("user_id") }
        if (owner != userId) throw ApiError.notFound("No such playlist")
    }

    private fun Tx.touch(id: String) = update("UPDATE playlists SET updated_at = ?, revision = revision + 1 WHERE id = ?", clock(), id)

    private fun Tx.renumber(id: String, order: List<String>) = order.forEachIndexed { index, entryId ->
        update("UPDATE playlist_entries SET position = ? WHERE playlist_id = ? AND entry_id = ?", index, id, entryId)
    }

    private fun Tx.insertEntries(id: String, tracks: List<ApiTrack>, from: Int, now: Long) = tracks.forEachIndexed { index, track ->
        update(
            "INSERT INTO playlist_entries (playlist_id, entry_id, position, track, added_at) VALUES (?, ?, ?, ?, ?)",
            id, Random.token(9), from + index, TrackJson.encodeToString(ApiTrack.serializer(), track), now,
        )
    }

    private fun Tx.entries(id: String): List<PlaylistEntry> =
        query("SELECT * FROM playlist_entries WHERE playlist_id = ? ORDER BY position", id) { row ->
            PlaylistEntry(row.string("entry_id"), TrackJson.decodeFromString(ApiTrack.serializer(), row.string("track")), row.long("added_at"))
        }

    private fun Tx.summary(id: String): PlaylistSummary? {
        val entries = entries(id)
        return one("SELECT * FROM playlists WHERE id = ?", id) { row ->
            PlaylistSummary(
                id = row.string("id"),
                name = row.string("name"),
                description = row.string("description"),
                count = entries.size,
                durationMs = entries.sumOf { it.track.durationMs ?: 0 },
                createdAt = row.long("created_at"),
                updatedAt = row.long("updated_at"),
                revision = row.long("revision"),
                artwork = entries.mapNotNull { it.track.artworkUrl }.distinct().take(4),
                playedAt = row.longOrNull("played_at"),
                missing = entries.count { it.track.toTrack().isMissing },
            )
        }
    }

    private fun cleanName(name: String) = name.trim().take(200).ifBlank { throw ApiError.badRequest("Give the playlist a name") }

    companion object {
        const val MAX_PLAYLISTS = 2_000
        const val MAX_ENTRIES = 10_000
        private val ID = Regex("[A-Za-z0-9_-]{8,64}")
        val TrackJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
