package com.music.player.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** When music should stop: at a time, or when the song playing ends. */
sealed interface SleepTimerState {
    data class At(val endsAtMs: Long) : SleepTimerState
    data object EndOfSong : SleepTimerState
}

/**
 * The sleep timer. Set from the app; PlaybackService carries it out (fading out at the end), so
 * it works however playback was started, Android Auto included.
 */
class SleepTimer(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow<SleepTimerState?>(null)
    val state: StateFlow<SleepTimerState?> = _state.asStateFlow()

    fun stopIn(minutes: Int) {
        _state.value = SleepTimerState.At(clock() + minutes * 60_000L)
    }

    fun stopAtEndOfSong() {
        _state.value = SleepTimerState.EndOfSong
    }

    fun cancel() {
        _state.value = null
    }

    companion object {
        val CHOICES_MINUTES = listOf(15, 30, 45, 60, 90)
    }
}
