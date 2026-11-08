package com.music.player.scrobble

import android.util.Log
import com.music.player.data.PendingScrobbleEntity
import com.music.player.data.ScrobbleDao
import com.music.player.model.Track
import com.music.player.settings.ScrobbleSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Sends listens to the scrobbling services set up. Listens are kept in a queue until each
 * service has them, so listening offline (or on satellite) loses nothing.
 */
class Scrobbler(
    private val dao: ScrobbleDao,
    private val settings: suspend () -> ScrobbleSettings,
    private val listenBrainz: ListenBrainzClient,
    private val lastFm: LastFmClient,
    private val isOnline: () -> Boolean,
) {
    private val mutex = Mutex()

    /** Whether [track] should be scrobbled at all, given where it came from. */
    suspend fun wants(heard: Boolean): Boolean {
        val config = settings()
        return config.enabled && (!heard || config.includeRadio)
    }

    suspend fun nowPlaying(track: Track) {
        val config = settings()
        if (!config.enabled || !isOnline()) return
        if (config.listenBrainzEnabled) send("now playing (ListenBrainz)") { listenBrainz.nowPlaying(config, track) }
        if (config.lastFmEnabled) send("now playing (Last.fm)") { lastFm.nowPlaying(config, track) }
    }

    suspend fun scrobble(listen: Listen) {
        if (!settings().enabled) return
        dao.insert(PendingScrobbleEntity(track = listen.track, playedAt = listen.startedAtMs))
        flush()
    }

    /** Sends whatever is waiting, oldest first, if online. */
    suspend fun flush() = mutex.withLock {
        val config = settings()
        if (!config.enabled || !isOnline()) return@withLock
        while (true) {
            val batch = dao.oldest(BATCH)
            if (batch.isEmpty()) return@withLock
            var progressed = false
            if (config.listenBrainzEnabled) {
                val todo = batch.filter { !it.listenBrainzDone }
                if (todo.isNotEmpty() && send("ListenBrainz") { listenBrainz.submit(config, todo.map { Listen(it.track, it.playedAt) }) }) {
                    dao.markListenBrainzDone(todo.map { it.id })
                    progressed = true
                }
            }
            if (config.lastFmEnabled) {
                val todo = batch.filter { !it.lastFmDone }
                if (todo.isNotEmpty() && send("Last.fm") { lastFm.scrobble(config, todo.map { Listen(it.track, it.playedAt) }) }) {
                    dao.markLastFmDone(todo.map { it.id })
                    progressed = true
                }
            }
            // Done with a listen once every service set up has it.
            val refreshed = dao.oldest(BATCH)
            val finished = refreshed.filter {
                (it.listenBrainzDone || !config.listenBrainzEnabled) && (it.lastFmDone || !config.lastFmEnabled)
            }
            if (finished.isNotEmpty()) dao.delete(finished.map { it.id })
            if (!progressed || finished.isEmpty()) {
                dao.recordAttempt(batch.map { it.id })
                // Give up on listens a service keeps refusing, rather than blocking the rest.
                dao.delete(batch.filter { it.attempts + 1 >= MAX_ATTEMPTS }.map { it.id })
                return@withLock
            }
        }
    }

    private suspend fun send(service: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: IOException) {
        log("scrobble ($service)", e)
        false
    }

    private fun log(what: String, e: Throwable) = Log.i(TAG, "Couldn't send $what", e)

    private companion object {
        const val TAG = "Scrobbler"
        const val BATCH = 50
        const val MAX_ATTEMPTS = 20
    }
}
