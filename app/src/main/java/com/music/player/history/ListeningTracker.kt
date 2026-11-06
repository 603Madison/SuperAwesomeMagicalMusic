package com.music.player.history

import com.music.player.data.HistoryDao
import com.music.player.data.HistoryEntity
import com.music.player.model.Track
import com.music.player.scrobble.Listen
import com.music.player.scrobble.Scrobbler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Follows what's being listened to: each song goes into the listening history as it starts,
 * and is scrobbled once it has been listened to long enough (half the song or four minutes,
 * as Last.fm and ListenBrainz define a listen). Called by PlaybackService on the main thread.
 */
class ListeningTracker(
    private val history: HistoryDao,
    private val scrobbler: Scrobbler,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Current(val track: Track, val heard: Boolean, val startedAt: Long) {
        var listenedMs = 0L
        var resumedAt: Long? = null
    }

    private var current: Current? = null
    private var entries = 0

    /** A song started: from the queue ([heard] false) or heard on a SiriusXM channel or station. */
    fun songStarted(track: Track, heardOn: String?, heard: Boolean, playing: Boolean) {
        if (current?.track?.key == track.key) return
        finish()
        val now = clock()
        current = Current(track, heard, now).apply { if (playing) resumedAt = now }
        scope.launch {
            history.insert(HistoryEntity(track = track, playedAt = now, heardOn = heardOn, heard = heard))
            if (++entries % 100 == 0) history.trim(HISTORY_SIZE)
            if (scrobbler.wants(heard)) scrobbler.nowPlaying(track)
        }
    }

    fun playingChanged(playing: Boolean) {
        val song = current ?: return
        val now = clock()
        if (playing) {
            if (song.resumedAt == null) song.resumedAt = now
        } else {
            song.resumedAt?.let { song.listenedMs += now - it }
            song.resumedAt = null
        }
    }

    /** Nothing (or no song) is playing any more: the last song counts if it was listened to enough. */
    fun finish() {
        val song = current ?: return
        current = null
        song.resumedAt?.let { song.listenedMs += clock() - it }
        if (!countsAsListen(song.track.durationMs, song.listenedMs)) return
        scope.launch {
            if (scrobbler.wants(song.heard)) scrobbler.scrobble(Listen(song.track, song.startedAt))
        }
    }

    companion object {
        const val HISTORY_SIZE = 2_000

        /** Over 30 seconds long, and played for half its length or four minutes. */
        fun countsAsListen(durationMs: Long?, listenedMs: Long): Boolean {
            if (durationMs != null && durationMs < 30_000) return false
            val needed = minOf(durationMs?.div(2) ?: Long.MAX_VALUE, 4 * 60_000L)
            return listenedMs >= needed
        }
    }
}
