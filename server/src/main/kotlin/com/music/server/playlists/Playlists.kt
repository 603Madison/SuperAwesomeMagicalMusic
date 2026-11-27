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
    /** Whether it's this person's own (false: someone shared it with them). */
    val mine: Boolean = true,
    /** Whose it is, when it's someone else's. */
    val owner: PlaylistPerson? = null,
    /** The people it's shared with (besides its owner): they add, remove and reorder its songs too. */
    val members: List<PlaylistPerson> = emptyList(),
)

/** Someone a playlist is shared with, or who shares it. */
@Serializable
data class PlaylistPerson(val id: String, val username: String, val name: String)

@Serializable
data class PlaylistEntry(
    val entryId: String,
    val track: ApiTrack,
    val addedAt: Long,
    /** Who added it, on a shared playlist. */
    val addedBy: PlaylistPerson? = null,
)

@Serializable
data class Playlist(val summary: PlaylistSummary, val entries: List<PlaylistEntry>)

/**
 * Each person's playlists, and the ones shared with them. A playlist belongs to the person who
 * made it; they can share it with others on the server, who then add, remove and reorder its
 * songs too (only its owner renames, deletes or shares it; the others can leave it). Every change
 * bumps the playlist's revision.
 */
class PlaylistStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** How [userId] can use a playlist. */
    private enum class Access { OWNER, MEMBER }

    suspend fun list(userId: String): List<PlaylistSummary> = db.tx {
        query(
            """
            SELECT id, name FROM playlists WHERE user_id = ?
            UNION SELECT p.id, p.name FROM playlists p JOIN playlist_members m ON m.playlist_id = p.id WHERE m.user_id = ?
            ORDER BY name COLLATE NOCASE
            """.trimIndent(),
            userId, userId,
        ) { it.string("id") }.mapNotNull { summary(it, userId) }
    }

    suspend fun get(userId: String, id: String): Playlist = db.tx {
        access(userId, id)
        Playlist(summary(id, userId)!!, entries(id))
    }

    /** Whether [userId] may use the playlist (their own, or shared with them). */
    suspend fun canUse(userId: String, id: String): Boolean = db.tx { accessOrNull(userId, id) != null }

    /**
     * Shares the playlist with [memberId] (only its owner can): they see it with their playlists,
     * and add, remove and reorder its songs too.
     */
    suspend fun share(ownerId: String, id: String, memberId: String): PlaylistSummary = db.tx {
        owned(ownerId, id)
        if (memberId == ownerId) throw ApiError.badRequest("It's already yours")
        if (long("SELECT COUNT(*) FROM playlist_members WHERE playlist_id = ?", id) >= MAX_MEMBERS) {
            throw ApiError.badRequest("A playlist can be shared with up to $MAX_MEMBERS people")
        }
        update("INSERT OR IGNORE INTO playlist_members (playlist_id, user_id, added_at) VALUES (?, ?, ?)", id, memberId, clock())
        touch(id)
        summary(id, ownerId)!!
    }

    /**
     * Stops sharing the playlist with [memberId]: its owner can take anyone off, and anyone can
     * take themself off (leave it). The songs they added stay.
     */
    suspend fun unshare(actorId: String, id: String, memberId: String) = db.tx {
        val access = access(actorId, id)
        if (access != Access.OWNER && actorId != memberId) throw ApiError.forbidden("Only the playlist's owner can do that")
        update("DELETE FROM playlist_members WHERE playlist_id = ? AND user_id = ?", id, memberId)
        touch(id)
    }

    /** The people [id] is shared with, and its owner, as user ids: whose devices to tell of a change. */
    suspend fun people(id: String): Set<String> = db.tx {
        (query("SELECT user_id FROM playlists WHERE id = ?", id) { it.string("user_id") } +
            query("SELECT user_id FROM playlist_members WHERE playlist_id = ?", id) { it.string("user_id") }).toSet()
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
            insertEntries(playlistId, tracks.take(MAX_ENTRIES), 0, now, userId)
        }
        return get(userId, playlistId)
    }

    suspend fun rename(userId: String, id: String, name: String?, description: String?): PlaylistSummary = db.tx {
        owned(userId, id)
        name?.let { update("UPDATE playlists SET name = ? WHERE id = ?", cleanName(it), id) }
        description?.let { update("UPDATE playlists SET description = ? WHERE id = ?", it.take(2000), id) }
        touch(id)
        summary(id, userId)!!
    }

    suspend fun delete(userId: String, id: String) = db.tx {
        owned(userId, id)
        update("DELETE FROM playlists WHERE id = ?", id)
    }

    /** Adds [tracks] at [position] (the end if null). */
    suspend fun add(userId: String, id: String, tracks: List<ApiTrack>, position: Int? = null): Playlist = db.tx {
        access(userId, id)
        val count = long("SELECT COUNT(*) FROM playlist_entries WHERE playlist_id = ?", id).toInt()
        if (count + tracks.size > MAX_ENTRIES) throw ApiError.badRequest("Playlists hold up to $MAX_ENTRIES songs")
        val at = (position ?: count).coerceIn(0, count)
        update("UPDATE playlist_entries SET position = position + ? WHERE playlist_id = ? AND position >= ?", tracks.size, id, at)
        insertEntries(id, tracks, at, clock(), userId)
        touch(id)
        Playlist(summary(id, userId)!!, entries(id))
    }

    suspend fun remove(userId: String, id: String, entryIds: Set<String>): Playlist = db.tx {
        access(userId, id)
        entryIds.forEach { update("DELETE FROM playlist_entries WHERE playlist_id = ? AND entry_id = ?", id, it) }
        renumber(id, entries(id).map { it.entryId })
        touch(id)
        Playlist(summary(id, userId)!!, entries(id))
    }

    /**
     * Puts the playlist's songs in [order] (entry ids; any left out are removed), if it's still
     * at [revision]: someone else's change in between is not overwritten.
     */
    suspend fun reorder(userId: String, id: String, revision: Long, order: List<String>): Playlist = db.tx {
        access(userId, id)
        val current = long("SELECT revision FROM playlists WHERE id = ?", id)
        if (current != revision) throw ApiError.conflict("The playlist changed elsewhere; reload it and try again")
        val existing = entries(id).map { it.entryId }.toSet()
        val kept = order.distinct().filter { it in existing }
        (existing - kept.toSet()).forEach { update("DELETE FROM playlist_entries WHERE playlist_id = ? AND entry_id = ?", id, it) }
        renumber(id, kept)
        touch(id)
        Playlist(summary(id, userId)!!, entries(id))
    }

    /** Notes that the person started playing it (the sidebar lists playlists by when they were played). */
    suspend fun played(userId: String, id: String) = db.tx {
        when (access(userId, id)) {
            Access.OWNER -> update("UPDATE playlists SET played_at = ? WHERE id = ?", clock(), id)
            Access.MEMBER -> update("UPDATE playlist_members SET played_at = ? WHERE playlist_id = ? AND user_id = ?", clock(), id, userId)
        }
    }

    /** Songs that weren't found, across everyone's playlists, for looking for again: (user, playlist, entry). */
    suspend fun missingEntries(limit: Int): List<Triple<String, String, PlaylistEntry>> = db.tx {
        query(
            "SELECT p.user_id, e.* FROM playlist_entries e JOIN playlists p ON p.id = e.playlist_id WHERE e.track LIKE ? ORDER BY e.added_at LIMIT ?",
            "%\"sourceId\":\"${com.music.player.model.Track.MISSING_PREFIX}%", limit,
        ) { row ->
            Triple(row.string("user_id"), row.string("playlist_id"), entryOf(row))
        }
    }

    /** Puts songs found since in place of stand-ins ([replacements]: entry id to the song found). */
    suspend fun replace(userId: String, id: String, replacements: Map<String, ApiTrack>): Playlist = db.tx {
        access(userId, id)
        replacements.forEach { (entryId, track) ->
            update("UPDATE playlist_entries SET track = ? WHERE playlist_id = ? AND entry_id = ?", TrackJson.encodeToString(ApiTrack.serializer(), track), id, entryId)
        }
        if (replacements.isNotEmpty()) touch(id)
        Playlist(summary(id, userId)!!, entries(id))
    }

    private fun Tx.accessOrNull(userId: String, id: String): Access? {
        val owner = one("SELECT user_id FROM playlists WHERE id = ?", id) { it.string("user_id") } ?: return null
        if (owner == userId) return Access.OWNER
        return Access.MEMBER.takeIf { one("SELECT 1 FROM playlist_members WHERE playlist_id = ? AND user_id = ?", id, userId) { true } == true }
    }

    /** How [userId] can use the playlist; someone else's playlist not shared with them isn't there at all. */
    private fun Tx.access(userId: String, id: String): Access = accessOrNull(userId, id) ?: throw ApiError.notFound("No such playlist")

    /** Only its owner (renaming, deleting, sharing): others it's shared with are told so. */
    private fun Tx.owned(userId: String, id: String) {
        if (access(userId, id) != Access.OWNER) throw ApiError.forbidden("Only the playlist's owner can do that")
    }

    private fun Tx.touch(id: String) = update("UPDATE playlists SET updated_at = ?, revision = revision + 1 WHERE id = ?", clock(), id)

    private fun Tx.renumber(id: String, order: List<String>) = order.forEachIndexed { index, entryId ->
        update("UPDATE playlist_entries SET position = ? WHERE playlist_id = ? AND entry_id = ?", index, id, entryId)
    }

    private fun Tx.insertEntries(id: String, tracks: List<ApiTrack>, from: Int, now: Long, by: String) = tracks.forEachIndexed { index, track ->
        update(
            "INSERT INTO playlist_entries (playlist_id, entry_id, position, track, added_at, added_by) VALUES (?, ?, ?, ?, ?, ?)",
            id, Random.token(9), from + index, TrackJson.encodeToString(ApiTrack.serializer(), track), now, by,
        )
    }

    private fun Tx.person(row: com.music.server.db.Row, prefix: String): PlaylistPerson? {
        val userId = row.stringOrNull("${prefix}_id") ?: return null
        return PlaylistPerson(userId, row.stringOrNull("${prefix}_username").orEmpty(), row.stringOrNull("${prefix}_name").orEmpty())
    }

    private fun Tx.entryOf(row: com.music.server.db.Row) = PlaylistEntry(
        row.string("entry_id"),
        TrackJson.decodeFromString(ApiTrack.serializer(), row.string("track")),
        row.long("added_at"),
    )

    /** Its songs, with who added each when the playlist is shared (or was). */
    private fun Tx.entries(id: String): List<PlaylistEntry> {
        val shared = long("SELECT COUNT(*) FROM playlist_members WHERE playlist_id = ?", id) > 0 ||
            long("SELECT COUNT(*) FROM playlist_entries e JOIN playlists p ON p.id = e.playlist_id WHERE e.playlist_id = ? AND e.added_by IS NOT NULL AND e.added_by != p.user_id", id) > 0
        return query(
            """
            SELECT e.*, u.id AS by_id, u.username AS by_username, u.display_name AS by_name
            FROM playlist_entries e LEFT JOIN users u ON u.id = e.added_by
            WHERE e.playlist_id = ? ORDER BY e.position
            """.trimIndent(),
            id,
        ) { row -> entryOf(row).copy(addedBy = if (shared) person(row, "by") else null) }
    }

    /** The people [id] is shared with (besides its owner). */
    private fun Tx.members(id: String): List<PlaylistPerson> = query(
        """
        SELECT u.id AS m_id, u.username AS m_username, u.display_name AS m_name
        FROM playlist_members m JOIN users u ON u.id = m.user_id
        WHERE m.playlist_id = ? ORDER BY u.display_name COLLATE NOCASE
        """.trimIndent(),
        id,
    ) { person(it, "m")!! }

    /** The playlist as [viewer] sees it: theirs or shared with them, and when they last played it. */
    private fun Tx.summary(id: String, viewer: String): PlaylistSummary? {
        val entries = entries(id)
        val members = members(id)
        val memberPlayed = one("SELECT played_at FROM playlist_members WHERE playlist_id = ? AND user_id = ?", id, viewer) { it.longOrNull("played_at") }
        return one(
            """
            SELECT p.*, u.id AS o_id, u.username AS o_username, u.display_name AS o_name
            FROM playlists p JOIN users u ON u.id = p.user_id WHERE p.id = ?
            """.trimIndent(),
            id,
        ) { row ->
            val mine = row.string("user_id") == viewer
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
                playedAt = if (mine) row.longOrNull("played_at") else memberPlayed,
                missing = entries.count { it.track.toTrack().isMissing },
                mine = mine,
                owner = if (mine) null else person(row, "o"),
                members = members,
            )
        }
    }

    private fun cleanName(name: String) = name.trim().take(200).ifBlank { throw ApiError.badRequest("Give the playlist a name") }

    companion object {
        const val MAX_PLAYLISTS = 2_000
        const val MAX_ENTRIES = 10_000
        const val MAX_MEMBERS = 50
        private val ID = Regex("[A-Za-z0-9_-]{8,64}")
        val TrackJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
