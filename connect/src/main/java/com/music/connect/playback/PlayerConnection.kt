package com.music.connect.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.music.connect.net.ApiTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUi(
    val track: ApiTrack? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<ApiTrack> = emptyList(),
    val index: Int = -1,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
) {
    val live: Boolean get() = track?.isLive == true
}

/** The app's screens' view of the player (in the playback service), through a media controller. */
class PlayerConnection(private val context: Context, private val server: () -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerUi())
    val state: StateFlow<PlayerUi> = _state.asStateFlow()

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            refresh()
        }, MoreExecutors.directExecutor())
        // The position moves on its own; check it a few times a second.
        scope.launch {
            while (true) {
                delay(500)
                controller?.let { c -> _state.value = _state.value.copy(positionMs = c.currentPosition.coerceAtLeast(0), durationMs = duration(c)) }
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).mapNotNull { MediaItems.track(c.getMediaItemAt(it)) }
        _state.value = PlayerUi(
            track = c.currentMediaItem?.let(MediaItems::track),
            playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
            buffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = duration(c),
            queue = queue,
            index = c.currentMediaItemIndex,
            repeat = c.repeatMode,
            shuffle = c.shuffleModeEnabled,
        )
    }

    private fun duration(c: MediaController): Long =
        c.duration.takeIf { it > 0 } ?: (c.currentMediaItem?.let(MediaItems::track)?.durationMs ?: 0)

    private fun items(tracks: List<ApiTrack>): List<MediaItem> =
        tracks.map { MediaItems.of(it) { url -> MediaItems.artworkUri(server(), url, 512) } }

    fun play(tracks: List<ApiTrack>, start: Int = 0) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        c.setMediaItems(items(tracks), start.coerceIn(0, tracks.size - 1), 0)
        c.prepare()
        c.play()
    }

    fun playShuffled(tracks: List<ApiTrack>) = play(tracks.shuffled())

    fun playNext(tracks: List<ApiTrack>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(tracks)
        c.addMediaItems(c.currentMediaItemIndex + 1, items(tracks))
    }

    fun addToQueue(tracks: List<ApiTrack>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(tracks)
        c.addMediaItems(items(tracks))
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPrevious()
    fun seek(ms: Long) = controller?.seekTo(ms)

    /** How much is loaded ahead of where it's playing. */
    fun bufferedMs(): Long = controller?.totalBufferedDuration?.coerceAtLeast(0) ?: 0
    fun jumpTo(index: Int) = controller?.let { it.seekTo(index, 0); it.play() }
    fun remove(index: Int) = controller?.removeMediaItem(index)
    fun move(from: Int, to: Int) = controller?.moveMediaItem(from, to)

    fun cycleRepeat() = controller?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() = controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun release() {
        controller?.release()
        controller = null
    }
}
