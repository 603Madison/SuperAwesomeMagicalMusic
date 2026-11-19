package com.music.nowplaying

/**
 * How long Now Playing waits before listening again: every [everyMs] normally; after a song is
 * recognised, until about when it should be over (no point recognising it again); and longer
 * and longer while nothing it hears is recognised (music it doesn't know, say), up to [maxMs].
 */
class Pacing(private val everyMs: Long, private val maxMs: Long = 5 * 60_000L) {
    private var misses = 0

    /** Quiet, or not a moment to listen (a call, the phone playing music itself). */
    fun quiet(): Long {
        misses = 0
        return everyMs
    }

    /** Music, but not recognised. */
    fun missed(): Long {
        misses++
        return (everyMs shl (misses - 1).coerceAtMost(4)).coerceAtMost(maxMs)
    }

    /** [song] recognised: after it should be over (a little after), but not less than usual nor more than [maxMs]. */
    fun recognised(song: Recognition, now: Long = System.currentTimeMillis()): Long {
        misses = 0
        val left = song.remainingMs(now) ?: return everyMs
        return (left + 10_000).coerceIn(everyMs, maxMs)
    }
}
