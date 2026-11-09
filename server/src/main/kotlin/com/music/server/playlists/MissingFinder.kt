package com.music.server.playlists

import com.music.server.auth.UserStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Looks again, every few hours, for songs imported playlists named that weren't found (kept in
 * them greyed out): one that has since turned up (Lidarr added the album to Jellyfin, say) takes
 * its place and plays.
 */
class MissingFinder(
    private val playlists: PlaylistStore,
    private val users: UserStore,
    private val importer: Importer,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start(firstAfterMs: Long = FIRST_AFTER_MS, everyMs: Long = EVERY_MS) {
        if (job != null) return
        job = scope.launch {
            delay(firstAfterMs)
            while (true) {
                try {
                    lookAgain()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Looking for missing songs failed: {}", e.message)
                }
                delay(everyMs)
            }
        }
    }

    /** One round: up to [limit] missing songs, each person's in their own sources. How many were found. */
    suspend fun lookAgain(limit: Int = PER_ROUND): Int {
        var found = 0
        playlists.missingEntries(limit).groupBy { it.first }.forEach { (userId, entries) ->
            val user = users.byId(userId)?.takeUnless { it.disabled } ?: return@forEach
            val now = importer.findAgain(user, entries.map { it.third.track })
            entries.zip(now).filter { it.second != null }.groupBy { it.first.second }.forEach { (playlistId, pairs) ->
                runCatching { playlists.replace(userId, playlistId, pairs.associate { (entry, track) -> entry.third.entryId to track!! }) }
                    .onSuccess { found += pairs.size }
            }
        }
        if (found > 0) log.info("Found {} songs that were missing from playlists", found)
        return found
    }

    private companion object {
        val log = LoggerFactory.getLogger(MissingFinder::class.java)
        const val FIRST_AFTER_MS = 10L * 60 * 1000
        const val EVERY_MS = 6L * 60 * 60 * 1000
        const val PER_ROUND = 300
    }
}
