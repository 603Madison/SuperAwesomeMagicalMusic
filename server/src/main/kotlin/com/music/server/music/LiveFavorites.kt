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

    companion object {
        const val MAX_PER_USER = 500

        fun keyOf(track: ApiTrack) = "${track.via.orEmpty()}|${track.source.name}:${track.sourceId}"
    }
}
