package com.music.server.listen

import com.music.player.listen.Fingerprint
import com.music.server.ApiJson
import com.music.server.db.Database
import com.music.server.music.ApiTrack

/**
 * The songs Listen has learned: each library song, and its fingerprint's hashes (by hash, for
 * looking up what a clip matches). Kept in a database of its own, listen.db: it can always be
 * learned again, so it needn't be backed up with everything else, and it can get large (about
 * 15 MB for every 100 songs).
 */
class ListenStore(private val db: Database) {
    data class Item(val id: Long, val sourceId: String, val songId: String, val track: ApiTrack)

    init {
        db.blockingTx {
            execute(
                """CREATE TABLE IF NOT EXISTS items (
                    id INTEGER PRIMARY KEY,
                    source_id TEXT NOT NULL,
                    song_id TEXT NOT NULL,
                    track TEXT NOT NULL,
                    state TEXT NOT NULL DEFAULT 'new',
                    hashes INTEGER NOT NULL DEFAULT 0,
                    tried_at INTEGER,
                    seen_at INTEGER NOT NULL,
                    UNIQUE (source_id, song_id)
                )""",
            )
            execute("CREATE INDEX IF NOT EXISTS items_state ON items (state, tried_at)")
            execute(
                """CREATE TABLE IF NOT EXISTS hashes (
                    hash INTEGER NOT NULL,
                    item INTEGER NOT NULL,
                    t INTEGER NOT NULL,
                    PRIMARY KEY (hash, item, t)
                ) WITHOUT ROWID""",
            )
        }
    }

    /** Notes [tracks] as in library [sourceId] now ([now]): new ones are to be learned. */
    suspend fun sync(sourceId: String, tracks: List<ApiTrack>, now: Long) = db.tx {
        batch(
            """INSERT INTO items (source_id, song_id, track, seen_at) VALUES (?, ?, ?, ?)
               ON CONFLICT (source_id, song_id) DO UPDATE SET track = excluded.track, seen_at = excluded.seen_at""",
            tracks.asSequence().map { arrayOf<Any?>(sourceId, it.sourceId, ApiJson.encodeToString(ApiTrack.serializer(), it), now) }.iterator(),
        )
    }

    /** Forgets library [sourceId]'s songs not seen since [before] (gone from the library). */
    suspend fun forgetUnseen(sourceId: String, before: Long): Int = forget("source_id = ? AND seen_at < ?", sourceId, before)

    /** Forgets libraries that aren't on the server any more (or are turned off). */
    suspend fun forgetSourcesExcept(ids: Set<String>): Int {
        val known = db.tx { query("SELECT DISTINCT source_id FROM items") { it.string("source_id") } }
        return known.filter { it !in ids }.sumOf { forget("source_id = ?", it) }
    }

    private suspend fun forget(where: String, vararg args: Any?): Int = db.tx {
        val ids = query("SELECT id FROM items WHERE $where", *args) { it.long("id") }
        // One pass over the hashes for all of them (they're kept by hash, not by song).
        ids.chunked(500).forEach { chunk -> update("DELETE FROM hashes WHERE item IN (${chunk.joinToString(",") { "?" }})", *chunk.toTypedArray()) }
        ids.chunked(500).forEach { chunk -> update("DELETE FROM items WHERE id IN (${chunk.joinToString(",") { "?" }})", *chunk.toTypedArray()) }
        ids.size
    }

    /** A song to learn: one not tried yet, or one that failed before [retryBefore]. */
    suspend fun next(retryBefore: Long): Item? = db.tx {
        one(
            "SELECT * FROM items WHERE state = 'new' OR (state = 'failed' AND tried_at < ?) ORDER BY state DESC, id LIMIT 1",
            retryBefore,
        ) { it.toItem() }
    }

    suspend fun learned(id: Long, hashes: LongArray, now: Long) = db.tx {
        batch(
            "INSERT OR IGNORE INTO hashes (hash, item, t) VALUES (?, ?, ?)",
            hashes.asSequence().map { arrayOf<Any?>(Fingerprint.hash(it), id, Fingerprint.frame(it)) }.iterator(),
        )
        update("UPDATE items SET state = 'learned', hashes = ?, tried_at = ? WHERE id = ?", hashes.size, now, id)
    }

    suspend fun failed(id: Long, now: Long) = db.tx { update("UPDATE items SET state = 'failed', tried_at = ? WHERE id = ?", now, id) }

    suspend fun item(id: Long): Item? = db.tx { one("SELECT * FROM items WHERE id = ?", id) { it.toItem() } }

    /** The songs (in libraries [sourceIds]) with any of [hashes]: (hash, song, frame). */
    suspend fun lookup(hashes: IntArray, sourceIds: Collection<String>): List<Triple<Int, Long, Int>> {
        if (hashes.isEmpty() || sourceIds.isEmpty()) return emptyList()
        val sources = sourceIds.toList()
        return db.tx {
            hashes.toList().chunked(500).flatMap { chunk ->
                query(
                    """SELECT h.hash, h.item, h.t FROM hashes h JOIN items i ON i.id = h.item
                       WHERE h.hash IN (${chunk.joinToString(",") { "?" }}) AND i.source_id IN (${sources.joinToString(",") { "?" }})""",
                    *(chunk.map<Int, Any?> { it } + sources).toTypedArray(),
                ) { Triple(it.int("hash"), it.long("item"), it.int("t")) }
            }
        }
    }

    /** (learned, all) songs in libraries [sourceIds]. */
    suspend fun counts(sourceIds: Collection<String>): Pair<Int, Int> {
        if (sourceIds.isEmpty()) return 0 to 0
        val marks = sourceIds.joinToString(",") { "?" }
        return db.tx {
            one(
                "SELECT COALESCE(SUM(state = 'learned'), 0) AS learned, COUNT(*) AS total FROM items WHERE source_id IN ($marks)",
                *sourceIds.toTypedArray(),
            ) { it.int("learned") to it.int("total") } ?: (0 to 0)
        }
    }

    private fun com.music.server.db.Row.toItem() = Item(
        id = long("id"),
        sourceId = string("source_id"),
        songId = string("song_id"),
        track = ApiJson.decodeFromString(ApiTrack.serializer(), string("track")),
    )
}
