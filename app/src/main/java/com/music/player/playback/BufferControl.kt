package com.music.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId

/**
 * Loads as far ahead as Settings asks, applied without restarting playback.
 *
 * Once a song has fully loaded, ExoPlayer carries on into the next songs in the queue, so a long
 * buffer also covers song changes: on satellite, where the link comes and goes, the next song is
 * already there when the current one ends. Loading stops at the buffer length (or at
 * [MAX_BUFFER_BYTES] of memory, which lossless songs reach first) and starts again once a quarter
 * has played, so the buffer stays nearly full while the radio idles between bursts.
 */
@OptIn(UnstableApi::class)
class BufferControl(
    private val delegate: DefaultLoadControl = DefaultLoadControl.Builder()
        .setTargetBufferBytes(MAX_BUFFER_BYTES)
        .build(),
    private val bufferAheadMs: () -> Long,
) : LoadControl by delegate {
    private var isLoading = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val allocated = delegate.getAllocator(parameters.playerId).totalBytesAllocated
        isLoading = keepLoading(
            isLoading = isLoading,
            bufferedMs = parameters.bufferedDurationUs / 1000,
            targetMs = bufferAheadMs(),
            memoryFull = allocated >= MAX_BUFFER_BYTES,
        )
        return isLoading
    }

    override fun onPrepared(playerId: PlayerId) {
        isLoading = false
        delegate.onPrepared(playerId)
    }

    override fun onStopped(playerId: PlayerId) {
        isLoading = false
        delegate.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        isLoading = false
        delegate.onReleased(playerId)
    }

    companion object {
        /** About 25 minutes at 320 kbps or 8 minutes of CD-quality FLAC. */
        const val MAX_BUFFER_BYTES = 64 * 1024 * 1024

        /** Below this much, loading never pauses, whatever the settings. */
        private const val MIN_BUFFER_MS = 15_000L

        fun keepLoading(isLoading: Boolean, bufferedMs: Long, targetMs: Long, memoryFull: Boolean): Boolean {
            val refillBelowMs = maxOf(targetMs * 3 / 4, minOf(targetMs, MIN_BUFFER_MS))
            return when {
                bufferedMs >= targetMs || memoryFull -> false
                bufferedMs < refillBelowMs -> true
                else -> isLoading
            }
        }
    }
}
