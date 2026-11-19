package com.music.mini

import android.os.Looper
import android.view.KeyEvent
import com.music.mini.net.ApiTrack
import com.music.mini.playback.MiniPlayer
import com.music.mini.playback.PlayerState
import com.music.mini.ui.ListScreen
import com.music.mini.ui.MainActivity
import com.music.mini.ui.Row
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** A key pressed and let go, as a flip phone's keypad sends it, then the screen settles. */
fun MainActivity.press(code: Int) {
    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    settle()
}

/** Lets posted work and layout passes run. */
fun settle() {
    repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
}

/** Waits (letting the main thread run) until [condition] holds: for answers from the network. */
fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val until = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for $what")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        Thread.sleep(5)
    }
    settle()
}

val MainActivity.list: ListScreen get() = top as ListScreen

/** The row with the highlight, by its title. */
val MainActivity.focusedTitle: String?
    get() = when (val row = list.focusedRow()) {
        is Row.Item -> row.title
        is Row.Note -> row.text
        else -> null
    }

/** A player that only notes what it's asked. */
class FakePlayer : MiniPlayer {
    val asked = mutableListOf<String>()
    var queue: List<ApiTrack> = emptyList()
    var index = -1
    override val state = MutableStateFlow(PlayerState())

    override fun play(tracks: List<ApiTrack>, index: Int) {
        queue = tracks
        this.index = index
        asked += "play ${tracks[index].title}"
        update(playing = true)
    }

    override fun toggle() {
        asked += "toggle"
        update(playing = !state.value.playing)
    }

    override fun pause() {
        asked += "pause"
        update(playing = false)
    }

    override fun next() {
        asked += "next"
        if (index < queue.lastIndex) index++
        update(state.value.playing)
    }

    override fun previous() {
        asked += "previous"
        if (index > 0) index--
        update(state.value.playing)
    }

    private fun update(playing: Boolean) {
        val track = queue.getOrNull(index)
        state.value = PlayerState(track, playing, durationMs = track?.durationMs ?: 0, hasNext = index < queue.lastIndex)
    }
}
