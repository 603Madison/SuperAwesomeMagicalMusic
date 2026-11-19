package com.music.mini.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.music.mini.Mini
import com.music.mini.R
import com.music.mini.mini
import com.music.mini.net.ApiTrack
import com.music.mini.net.Listen
import com.music.mini.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays in the background, with the notification, the lock screen and headset buttons (all
 * through the media session). Songs are looked up on the server as they start, at Mini's quality
 * setting; live radio streams straight from the server.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var m: Mini
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val streams = ConcurrentHashMap<String, Pair<Int, String>>()

    // Listening, for the person's history (and so Recently played).
    private var listening: ApiTrack? = null
    private var listenedMs = 0L
    private var playingSince = 0L
    private var startedAt = 0L
    private var stationRefill: Job? = null

    override fun onCreate() {
        super.onCreate()
        m = mini
        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(m.http))
        val dataSources = ResolvingDataSource.Factory(upstream) { spec -> resolve(spec) }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            // Phones with little memory: a minute ahead is plenty at Mini's bitrates.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
                    .setTargetBufferBytes(4 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, StationSkipping(player)).setSessionActivity(open).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away while nothing plays: nothing to keep going for.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        noteListened()
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    /** Where a song plays from, asked of the server as it starts (at the quality set now). */
    private fun resolve(spec: DataSpec): DataSpec {
        val track = MediaItems.trackOf(spec.uri) ?: return spec
        val kbps = m.prefs.kbps
        val known = streams[track.key]?.takeIf { it.first == kbps }?.second
        val url = known ?: try {
            runBlocking { m.api.absolute(m.api.resolve(track, kbps).streamUrl) }.also { streams[track.key] = kbps to it }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "\"${track.title}\" couldn't be found", e)
        }
        return spec.withUri(android.net.Uri.parse(url))
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = System.currentTimeMillis()
            if (isPlaying) {
                playingSince = now
            } else if (playingSince > 0) {
                listenedMs += now - playingSince
                playingSince = 0
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            noteListened()
            listening = MediaItems.track(mediaItem)
            startedAt = System.currentTimeMillis()
            listenedMs = 0
            playingSince = if (player.isPlaying) startedAt else 0
            listening?.takeIf { it.isStationTrack }?.let { refillStation() }
        }
    }

    /** Adds the song that was playing to the person's history, if it was listened to for a while. */
    private fun noteListened() {
        val track = listening ?: return
        val heard = listenedMs + if (playingSince > 0) System.currentTimeMillis() - playingSince else 0
        listening = null
        if (track.isLive || track.isMissing) return
        val enough = minOf(30_000L, (track.durationMs ?: 60_000L) / 2)
        if (heard < enough) return
        val listen = Listen(track, startedAt, heard)
        m.scope.launch { runCatching { m.api.addListens(listOf(listen)) }.onFailure { Log.i(TAG, "Couldn't note a listen: ${it.message}") } }
    }

    /** Near the end of a Pandora station's songs, asks for more of it. */
    private fun refillStation() {
        if (player.mediaItemCount - player.currentMediaItemIndex > 3 || stationRefill?.isActive == true) return
        val last = MediaItems.track(player.getMediaItemAt(player.mediaItemCount - 1)) ?: return
        val via = last.via ?: return
        if (!last.isStationTrack) return
        val (stationId, _) = MediaItems.split(last.sourceId.removePrefix("stationtrack:"))
        stationRefill = scope.launch {
            val more = withContext(Dispatchers.IO) { runCatching { m.api.station(via, stationId, last.album, more = true) }.getOrNull() }
            val server = m.prefs.server
            more?.tracks?.map { MediaItems.playable(it, server) }?.takeIf { it.isNotEmpty() }?.let { player.addMediaItems(it) }
        }
    }

    /** Skips the song on one of the person's own stations (the station moves on; the stream goes on). */
    private fun skipPersonal(track: ApiTrack) {
        m.scope.launch { runCatching { m.api.personalSkip(track.sourceId) }.onFailure { m.tell(it.message ?: "The station didn't skip") } }
    }

    /**
     * The player as the notification, headsets and the app see it: on one of the person's own
     * stations, "next" skips the song (the station moves on) though there's no next item.
     */
    private inner class StationSkipping(player: Player) : ForwardingPlayer(player) {
        private fun personal() = MediaItems.track(currentMediaItem)?.takeIf { it.isPersonal }
        private val skips = setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)

        override fun getAvailableCommands(): Player.Commands {
            val commands = super.getAvailableCommands()
            return if (personal() == null) commands else commands.buildUpon().addAll(*skips.toIntArray()).build()
        }

        override fun isCommandAvailable(command: Int): Boolean = (command in skips && personal() != null) || super.isCommandAvailable(command)

        override fun seekToNext() {
            personal()?.let(::skipPersonal) ?: super.seekToNext()
        }

        override fun seekToNextMediaItem() {
            personal()?.let(::skipPersonal) ?: super.seekToNextMediaItem()
        }
    }

    private companion object {
        const val TAG = "MiniPlayback"
    }
}
