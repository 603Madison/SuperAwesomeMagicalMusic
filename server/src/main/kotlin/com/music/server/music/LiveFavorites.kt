package com.music.server.music

import com.music.server.db.Database
import com.music.server.playlists.PlaylistStore

/** Each person's favourite live channels and stations, newest first. */
class LiveFavorites(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun list(userId: String): List<ApiTrack> = db.tx {
        query("SELECT track FROM live_favorites WHERE user_id = ? ORDER BY added_at DESC", userId) { row ->
            PlaylistStore.TrackJson.decodeFromString(ApiTrack.serializer(), row.string("track"))
        }
    }

    suspend fun set(userId: String, track: ApiTrack, favorite: Boolean) = db.tx {
        val key = keyOf(track)
        if (favorite) {
            update(
                "INSERT OR REPLACE INTO live_favorites (user_id, key, track, added_at) VALUES (?, ?, ?, ?)",
                userId, key, PlaylistStore.TrackJson.encodeToString(ApiTrack.serializer(), track), clock(),
            )
            // A few hundred is plenty; the oldest go.
            update(
                "DELETE FROM live_favorites WHERE user_id = ? AND key NOT IN (SELECT key FROM live_favorites WHERE user_id = ? ORDER BY added_at DESC LIMIT ?)",
                userId, userId, MAX_PER_USER,
            )
        } else {
            update("DELETE FROM live_favorites WHERE user_id = ? AND key = ?", userId, key)
        }
    }

    /** Notes that the person played a live channel or station (or a Pandora station). */
    suspend fun played(userId: String, track: ApiTrack) = db.tx {
        val json = PlaylistStore.TrackJson.encodeToString(ApiTrack.serializer(), track)
        update(
            "INSERT INTO live_plays (user_id, key, track, played_at, plays) VALUES (?, ?, ?, ?, 1) " +
                "ON CONFLICT(user_id, key) DO UPDATE SET track = excluded.track, played_at = excluded.played_at, plays = plays + 1",
            userId, keyOf(track), json, clock(),
        )
        update(
            "DELETE FROM live_plays WHERE user_id = ? AND key NOT IN (SELECT key FROM live_plays WHERE user_id = ? ORDER BY played_at DESC LIMIT ?)",
            userId, userId, MAX_PER_USER,
        )
    }

    /**
     * The person's top channels and stations, for the sidebar: their favourites, the ones played
     * most recently first, then others they've played lately.
     */
    suspend fun top(userId: String, limit: Int): List<ApiTrack> = db.tx {
        val favorites = query(
            "SELECT f.track FROM live_favorites f LEFT JOIN live_plays p ON p.user_id = f.user_id AND p.key = f.key " +
                "WHERE f.user_id = ? ORDER BY COALESCE(p.played_at, 0) DESC, f.added_at DESC LIMIT ?",
            userId, limit,
        ) { PlaylistStore.TrackJson.decodeFromString(ApiTrack.serializer(), it.string("track")) }
        val others = if (favorites.size >= limit) emptyList() else query(
            "SELECT track FROM live_plays WHERE user_id = ? AND key NOT IN (SELECT key FROM live_favorites WHERE user_id = ?) ORDER BY played_at DESC LIMIT ?",
            userId, userId, limit - favorites.size,
        ) { PlaylistStore.TrackJson.decodeFromString(ApiTrack.serializer(), it.string("track")) }
        favorites + others
    }

    companion object {
        const val MAX_PER_USER = 500

        fun keyOf(track: ApiTrack) = "${track.via.orEmpty()}|${track.source.name}:${track.sourceId}"
    }
}
