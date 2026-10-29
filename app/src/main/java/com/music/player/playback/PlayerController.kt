package com.music.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.music.player.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

/** UI-side handle on [PlaybackService], via a Media3 [MediaController]. */
class PlayerController(private val context: Context, private val scope: CoroutineScope) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pending: ((MediaController) -> Unit)? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) =
            refresh(queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED))
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val newFuture = MediaController.Builder(context, token).buildAsync()
        future = newFuture
        newFuture.addListener({
            val connected = runCatching { newFuture.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            refresh(queueChanged = true)
            pending?.invoke(connected)
            pending = null
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        ticker?.cancel()
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
    }

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) = withController {
        if (tracks.isEmpty()) return@withController
        it.shuffleModeEnabled = shuffle
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(tracks.indices)
        it.setMediaItems(tracks.map(Track::toMediaItem), start, 0L)
        it.prepare()
        it.play()
    }

    /** Voice requests: the session's library callback turns the query into a queue. */
    fun playFromSearch(query: String) = withController {
        it.setMediaItem(
            MediaItem.Builder()
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
                .build(),
        )
        it.prepare()
        it.play()
    }

    fun playNext(track: Track) = withController {
        if (it.mediaItemCount == 0) return@withController play(listOf(track))
        it.addMediaItem(it.currentMediaItemIndex + 1, track.toMediaItem())
    }

    fun addToQueue(tracks: List<Track>) = withController {
        if (it.mediaItemCount == 0) return@withController play(tracks)
        it.addMediaItems(tracks.map(Track::toMediaItem))
    }

    fun togglePlayPause() = withController {
        if (it.isPlaying) {
            it.pause()
        } else {
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            it.play()
        }
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
    fun skipTo(index: Int) = withController { it.seekTo(index, 0L) }
    fun removeFromQueue(index: Int) = withController { it.removeMediaItem(index) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val current = controller
        if (current != null) {
            action(current)
        } else {
            pending = action
            connect()
        }
    }

    private fun refresh(queueChanged: Boolean) {
        val player = controller ?: return
        val queue = if (queueChanged) {
            (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toTrack() }
        } else {
            _state.value.queue
        }
        _state.value = PlayerUiState(
            current = player.currentMediaItem?.toTrack(),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 }
                ?: player.currentMediaItem?.mediaMetadata?.durationMs
                ?: 0L,
            queue = queue,
            currentIndex = player.currentMediaItemIndex,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
        if (player.isPlaying) startTicker() else ticker?.cancel()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(500)
                val player = controller ?: break
                _state.value = _state.value.copy(positionMs = player.currentPosition.coerceAtLeast(0))
            }
        }
    }
}
