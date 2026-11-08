package com.music.server.playlists

import com.music.server.db.Database
import com.music.server.music.ApiTrack
import kotlinx.serialization.Serializable

@Serializable
data class Listen(val track: ApiTrack, val playedAt: Long, val listenedMs: Long = 0, val client: String = "")

/** What each person has listened to, newest first; kept to the latest [MAX_PER_USER]. */
class HistoryStore(private val db: Database) {
    suspend fun add(userId: String, listens: List<Listen>) = db.tx {
        listens.forEach { listen ->
            update(
                "INSERT INTO history (user_id, track, played_at, listened_ms, client) VALUES (?, ?, ?, ?, ?)",
                userId,
                PlaylistStore.TrackJson.encodeToString(ApiTrack.serializer(), listen.track),
                listen.playedAt,
                listen.listenedMs.coerceAtLeast(0),
                listen.client.take(60),
            )
        }
        update(
            "DELETE FROM history WHERE user_id = ? AND id NOT IN (SELECT id FROM history WHERE user_id = ? ORDER BY played_at DESC LIMIT ?)",
            userId, userId, MAX_PER_USER,
        )
    }

    suspend fun list(userId: String, limit: Int = 100, before: Long? = null): List<Listen> = db.tx {
        query(
            "SELECT * FROM history WHERE user_id = ? AND played_at < ? ORDER BY played_at DESC LIMIT ?",
            userId, before ?: Long.MAX_VALUE, limit.coerceIn(1, 500),
        ) { row ->
            Listen(
                PlaylistStore.TrackJson.decodeFromString(ApiTrack.serializer(), row.string("track")),
                row.long("played_at"),
                row.long("listened_ms"),
                row.string("client"),
            )
        }
    }

    suspend fun clear(userId: String) = db.tx { update("DELETE FROM history WHERE user_id = ?", userId) }

    companion object {
        const val MAX_PER_USER = 5_000
    }
}
