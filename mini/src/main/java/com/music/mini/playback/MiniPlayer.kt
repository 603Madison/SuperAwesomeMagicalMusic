package com.music.mini.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.music.mini.net.ApiTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the screens show about playback. */
data class PlayerState(
    val track: ApiTrack? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val error: String? = null,
) {
    val live: Boolean get() = track?.isLive == true
}

/** The player as the screens use it (the real one is in [PlaybackService]; tests use their own). */
interface MiniPlayer {
    val state: StateFlow<PlayerState>
    fun play(tracks: List<ApiTrack>, index: Int)
    fun toggle()
    fun pause()
    fun next()
    fun previous()
}

/** The playback service's player, through a media controller (connected the first time it's needed). */
class ControllerPlayer(private val context: Context, private val server: () -> String) : MiniPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private var connecting = false
    private val waiting = mutableListOf<(MediaController) -> Unit>()
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Runs [action] on the controller, once it's connected. */
    private fun withController(action: (MediaController) -> Unit) {
        controller?.let { return action(it) }
        waiting += action
        if (connecting) return
        connecting = true
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({
            connecting = false
            val connected = runCatching { future.get() }.getOrNull()
            if (connected == null) {
                waiting.clear()
                return@addListener
            }
            controller = connected
            connected.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            refresh()
            waiting.toList().also { waiting.clear() }.forEach { it(connected) }
        }, MoreExecutors.directExecutor())
    }

    /** Connects now, to show what's playing (after the app was closed, say). */
    fun connect() = withController { }

    override fun play(tracks: List<ApiTrack>, index: Int) {
        if (tracks.isEmpty()) return
        val server = server()
        withController { c ->
            c.setMediaItems(tracks.map { MediaItems.playable(it, server) }, index.coerceIn(0, tracks.lastIndex), 0)
            c.prepare()
            c.play()
        }
    }

    override fun toggle() = withController { c ->
        if (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)) {
            c.pause()
        } else {
            // After an error or at the end, start again from where it stopped.
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    override fun pause() = withController { it.pause() }

    override fun next() = withController { c ->
        if (c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) c.seekToNext()
    }

    override fun previous() = withController { c ->
        if (c.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) c.seekToPrevious()
    }

    private fun refresh() {
        val c = controller ?: return
        val error: PlaybackException? = c.playerError
        _state.value = PlayerState(
            track = MediaItems.track(c.currentMediaItem),
            playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
            buffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = duration(c),
            hasNext = c.hasNextMediaItem() || MediaItems.track(c.currentMediaItem)?.isPersonal == true,
            error = error?.let { describe(it) },
        )
        // The position moves on its own: while playing, checked every second.
        if (c.isPlaying && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive && controller?.isPlaying == true) {
                    delay(1_000)
                    controller?.let { _state.value = _state.value.copy(positionMs = it.currentPosition.coerceAtLeast(0), durationMs = duration(it)) }
                }
            }
        }
    }

    private fun duration(c: MediaController): Long =
        c.duration.takeIf { it > 0 } ?: (MediaItems.track(c.currentMediaItem)?.durationMs ?: 0)

    private fun describe(error: PlaybackException): String {
        val cause = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.lastOrNull()
        return cause ?: "This couldn't be played"
    }
}
