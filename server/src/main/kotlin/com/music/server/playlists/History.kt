package com.music.server.playlists

import com.music.server.db.Database
import com.music.server.db.Row
import com.music.server.music.ApiTrack
import kotlinx.serialization.Serializable

/**
 * A song listened to: [listenedMs] of it, from [playedAt]. [station] is the live channel or radio
 * station it was heard on, if it was; a live listen whose song isn't known has the channel or
 * station itself as its [track].
 */
@Serializable
data class Listen(val track: ApiTrack, val playedAt: Long, val listenedMs: Long = 0, val client: String = "", val station: String? = null)

/** What each person has listened to, newest first; kept to the latest [MAX_PER_USER] (years of it, for Wrapped). */
class HistoryStore(private val db: Database) {
    suspend fun add(userId: String, listens: List<Listen>) = db.tx {
        listens.forEach { listen ->
            update(
                "INSERT INTO history (user_id, track, played_at, listened_ms, client, station) VALUES (?, ?, ?, ?, ?, ?)",
                userId,
                PlaylistStore.TrackJson.encodeToString(ApiTrack.serializer(), listen.track),
                listen.playedAt,
                listen.listenedMs.coerceAtLeast(0),
                listen.client.take(60),
                listen.station?.take(120)?.takeIf { it.isNotBlank() },
            )
        }
        // Everything older than the newest MAX_PER_USER goes (by the index, without counting them all).
        update(
            "DELETE FROM history WHERE user_id = ? AND played_at < (SELECT played_at FROM history WHERE user_id = ? ORDER BY played_at DESC LIMIT 1 OFFSET ?)",
            userId, userId, MAX_PER_USER - 1,
        )
    }

    suspend fun list(userId: String, limit: Int = 100, before: Long? = null): List<Listen> = db.tx {
        query(
            "SELECT * FROM history WHERE user_id = ? AND played_at < ? ORDER BY played_at DESC LIMIT ?",
            userId, before ?: Long.MAX_VALUE, limit.coerceIn(1, 500),
        ) { it.listen() }
    }

    /** Every listen from [from] (inclusive) to [to] (exclusive), oldest first. */
    suspend fun between(userId: String, from: Long, to: Long): List<Listen> = db.tx {
        query("SELECT * FROM history WHERE user_id = ? AND played_at >= ? AND played_at < ? ORDER BY played_at", userId, from, to) { it.listen() }
    }

    /** When the first listen kept was, if there is one. */
    suspend fun first(userId: String): Long? = db.tx {
        one("SELECT MIN(played_at) AS first FROM history WHERE user_id = ?", userId) { it.longOrNull("first") }
    }

    suspend fun clear(userId: String) = db.tx { update("DELETE FROM history WHERE user_id = ?", userId) }

    private fun Row.listen() = Listen(
        PlaylistStore.TrackJson.decodeFromString(ApiTrack.serializer(), string("track")),
        long("played_at"),
        long("listened_ms"),
        string("client"),
        stringOrNull("station"),
    )

    companion object {
        const val MAX_PER_USER = 100_000
    }
}
