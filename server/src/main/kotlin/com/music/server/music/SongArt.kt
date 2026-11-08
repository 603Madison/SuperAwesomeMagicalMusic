package com.music.server.music

import com.music.player.model.Track
import com.music.player.sources.jellyfin.JellyfinSource
import com.music.server.auth.User
import com.music.server.sources.SourceRegistry
import com.music.server.sources.SourceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Covers for songs heard on the radio that the station doesn't show one for: the cover of the
 * person's own copy (their library first, then each service), looked up once and remembered.
 */
class SongArt(
    private val store: SourceStore,
    private val registry: SourceRegistry,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val at: Long, val art: Deferred<String?>)

    private val found = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_ENTRIES
    }

    /** [song]'s cover, waiting at most [waitMs] for a first look (it carries on, for next time). */
    suspend fun of(user: User, song: Track, waitMs: Long = 2_500): String? {
        song.artworkUrl?.let { return it }
        val key = "${user.id}|${song.artist.lowercase()}|${song.title.lowercase()}"
        val entry = synchronized(found) {
            found[key]?.takeIf { clock() - it.at < KEEP_MS || it.art.isActive }
                ?: Entry(clock(), scope.async { look(user, song) }).also { found[key] = it }
        }
        return if (waitMs <= 0) entry.art.takeIf { it.isCompleted }?.let { runCatching { it.await() }.getOrNull() }
        else withTimeoutOrNull(waitMs) { runCatching { entry.art.await() }.getOrNull() }
    }

    private suspend fun look(user: User, song: Track): String? {
        val sources = store.activeFor(user).filter { it.kind.isOnDemand }.sortedByDescending { it.kind.isLibrary }
        for (source in sources) {
            val music = registry.music(source) ?: continue
            val copy = try {
                if (music is JellyfinSource) music.lookUp(song) else music.findMatch(song)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            copy?.artworkUrl?.let { return it }
        }
        return null
    }

    private companion object {
        const val MAX_ENTRIES = 2_000
        const val KEEP_MS = 6L * 3600 * 1000
    }
}
