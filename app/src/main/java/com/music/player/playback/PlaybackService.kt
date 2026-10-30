package com.music.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.player.AppContainer
import com.music.player.MainActivity
import com.music.player.MusicApp
import com.music.player.quality.NetworkClass
import com.music.player.quality.StreamQuality
import com.music.player.sources.siriusxm.SiriusXmClient
import com.music.player.sources.siriusxm.SiriusXmDataSource
import com.music.player.sources.siriusxm.SxmChannel
import com.music.player.sources.siriusxm.SxmCut
import com.music.player.sources.siriusxm.SxmNowPlaying
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Plays everything: on-demand songs (resolved when loaded, see [StreamResolver]) and SiriusXM
 * live channels (HLS). As a [MediaLibraryService] it also serves Android Auto's browse tree.
 */
// ResolvingDataSource, the extractor flags and the bitmap loader are Media3 "unstable" APIs.
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var container: AppContainer
    private var session: MediaLibrarySession? = null
    private val scope = MainScope()
    private var lastRetuneAt = 0L

    override fun onCreate() {
        super.onCreate()
        container = (application as MusicApp).container

        val http = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(container.httpClient))
        val upstream = SiriusXmDataSource.Factory(http, container.siriusXm)
        val dataSourceFactory = ResolvingDataSource.Factory(upstream) { dataSpec -> resolve(dataSpec) }
        val extractors = DefaultExtractorsFactory()
            // Lets transcoded MP3/AAC streams of unknown length be seekable.
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        val loadControl = BufferControl { container.settingsState.value.bufferAheadSeconds * 1000L }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractors))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(ErrorHandler(player))
        player.addListener(TrackChanges(player))

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback(container, scope))
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(listOf(LibraryCallback.SAVE_SONG_BUTTON))
            .setBitmapLoader(
                NetworkAwareBitmapLoader(
                    DataSourceBitmapLoader.Builder(this)
                        .setDataSourceFactory(OkHttpDataSource.Factory(container.httpClient))
                        .build(),
                ) { artworkAllowed() },
            )
            .build()

        // On a network change (e.g. Wi-Fi -> satellite), queued songs re-pick their bitrate. The
        // current song keeps its stream so seeking within it stays consistent.
        // If the network now allows more (e.g. satellite -> Wi-Fi), the current song also switches
        // to a better copy, picking up where it was.
        scope.launch {
            container.networkMonitor.state.map { it.networkClass }.distinctUntilChanged().drop(1).collect {
                container.resolver.onNetworkOrQualityChanged(player.currentMediaItem?.mediaId)
                upgradeCurrentIfBetter(player)
            }
        }
        scope.launch {
            container.settings.settings.map { it.quality }.distinctUntilChanged().drop(1).collect {
                container.resolver.onNetworkOrQualityChanged(player.currentMediaItem?.mediaId)
                upgradeCurrentIfBetter(player)
            }
        }
        // Streams with several bitrates (SiriusXM's HLS) round down to the network's cap.
        scope.launch {
            combine(container.networkMonitor.state, container.settings.settings) { network, settings ->
                settings.quality.forNetwork(network.networkClass)
            }.distinctUntilChanged().collect { quality ->
                val maxBitrate = (quality as? StreamQuality.Capped)?.kbps?.times(1000) ?: Int.MAX_VALUE
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setMaxAudioBitrate(maxBitrate)
                    .build()
            }
        }
        scope.launch { trackSiriusXmNowPlaying(player) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        container.siriusXm.publishNowPlaying(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Called on ExoPlayer's loading thread whenever a `music://` item is opened. */
    private fun resolve(dataSpec: DataSpec): DataSpec {
        val track = TrackUri.decode(dataSpec.uri) ?: return dataSpec
        val (uri, headers) = try {
            runBlocking { container.resolver.resolveForPlayback(track) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message, e)
        }
        return dataSpec.buildUpon()
            .setUri(uri.toUri())
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers)
            .build()
    }

    /**
     * While a SiriusXM channel plays, works out which song is being heard (the live edge is
     * ahead of what's audible), publishes it for the app, and shows it in the notification and
     * Android Auto.
     */
    private suspend fun trackSiriusXmNowPlaying(player: ExoPlayer) {
        val window = Timeline.Window()
        while (scope.isActive) {
            val item = player.currentMediaItem
            val track = item?.toTrack()
            val channel = track?.let(SxmChannel::idOf)
            if (track == null || channel == null) {
                if (container.siriusXm.nowPlaying.value != null) container.siriusXm.publishNowPlaying(null)
            } else if (player.playWhenReady) {
                val timeline = player.currentTimeline
                val windowStart = if (timeline.isEmpty) C.TIME_UNSET
                else timeline.getWindow(player.currentMediaItemIndex, window).windowStartTimeMs
                val heardAt = if (windowStart != C.TIME_UNSET) windowStart + player.currentPosition
                else System.currentTimeMillis() - 30_000
                if (channel.first != SiriusXmClient.LINEAR && player.isPlaying) {
                    xtraElapsedMs += NOW_PLAYING_INTERVAL_MS
                    if (xtraElapsedMs % XTRA_PROGRESS_INTERVAL_MS == 0L) {
                        runCatching { container.siriusXm.onXtraProgress(channel.first, channel.second, xtraElapsedMs) }
                    }
                }
                val cut = runCatching { container.siriusXm.cutAt(channel.first, channel.second, heardAt) }
                    .onFailure { Log.w(TAG, "SiriusXM now playing failed", it) }
                    .getOrNull()
                val nowPlaying = SxmNowPlaying(track, cut)
                if (nowPlaying != container.siriusXm.nowPlaying.value) {
                    container.siriusXm.publishNowPlaying(nowPlaying)
                    showSong(player, cut)
                }
            }
            delay(NOW_PLAYING_INTERVAL_MS)
        }
    }

    /** Re-opens the current song at a better quality if the current network or settings allow it. */
    private suspend fun upgradeCurrentIfBetter(player: ExoPlayer) {
        val item = player.currentMediaItem ?: return
        val pinned = container.resolver.pinnedQuality(item.mediaId) ?: return // downloaded, live or not started
        val state = container.networkMonitor.state.value
        if (!state.isOnline) return
        val now = container.settings.current().quality.forNetwork(state.networkClass)
        if (!now.isHigherThan(pinned)) return
        container.resolver.release(item.mediaId)
        reload(player)
    }

    /** Makes ExoPlayer open the current item again (re-resolving its stream) at the same position. */
    private fun reload(player: ExoPlayer, positionMs: Long = player.currentPosition) {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        // A new tag makes the item differ, so ExoPlayer builds a fresh source instead of reusing it.
        player.replaceMediaItem(index, item.buildUpon().setTag(System.nanoTime()).build())
        player.seekTo(index, positionMs)
        player.prepare()
    }

    /** Moves an Xtra channel on to its next track when the current one ends. */
    private fun advanceXtra(player: ExoPlayer, index: Int) {
        val track = player.getMediaItemAt(index).toTrack()
        val (type, channelId) = track?.let(SxmChannel::idOf) ?: return
        scope.launch {
            try {
                container.siriusXm.nextXtraTrack(type, channelId)
                if (player.currentMediaItemIndex != index) player.seekTo(index, 0)
                reload(player, positionMs = 0)
                player.play()
                xtraElapsedMs = 0
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't continue the Xtra channel", e)
                container.reportPlaybackError(e.message ?: "Couldn't load the next track")
            }
        }
    }

    private var xtraElapsedMs = 0L

    /**
     * Track changes: songs left behind lose their pinned stream (so replaying them re-picks the
     * best copy for the network), and Xtra channels continue past the end of each track.
     */
    private inner class TrackChanges(private val player: ExoPlayer) : Player.Listener {
        private var previous: Pair<Int, String?> = -1 to null

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val (previousIndex, previousKey) = previous
            previous = player.currentMediaItemIndex to mediaItem?.mediaId
            if (previousKey != null && previousKey != mediaItem?.mediaId) container.resolver.release(previousKey)
            val leftXtra = previousIndex >= 0 && previousIndex < player.mediaItemCount &&
                player.getMediaItemAt(previousIndex).toTrack()?.let(SxmChannel::idOf)?.first?.let { it != SiriusXmClient.LINEAR } == true
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && leftXtra) {
                // The Xtra track ended and ExoPlayer moved on; stay on the channel instead.
                player.seekTo(previousIndex, 0)
                advanceXtra(player, previousIndex)
                return
            }
            mediaItem?.toTrack()?.let(SxmChannel::idOf)?.takeIf { it.first != SiriusXmClient.LINEAR }?.let { (type, id) ->
                xtraElapsedMs = 0
                scope.launch { container.siriusXm.onXtraStarted(type, id) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val channel = player.currentMediaItem?.toTrack()?.let(SxmChannel::idOf) ?: return
            if (channel.first != SiriusXmClient.LINEAR) advanceXtra(player, player.currentMediaItemIndex)
        }
    }

    private fun showSong(player: ExoPlayer, cut: SxmCut?) {
        val item = player.currentMediaItem ?: return
        val channel = item.toTrack() ?: return
        val metadata = item.mediaMetadata.buildUpon().apply {
            if (cut?.isSong == true) {
                setTitle(cut.title)
                setArtist(cut.artist)
                setAlbumTitle(channel.title)
                setArtworkUri((cut.artworkUrl ?: channel.artworkUrl)?.toUri())
            } else {
                setTitle(channel.title)
                setArtist(channel.artist)
                setAlbumTitle(channel.album)
                setArtworkUri(channel.artworkUrl?.toUri())
            }
        }.build()
        if (metadata != item.mediaMetadata) {
            // Same stream, new metadata: ExoPlayer updates it without interrupting playback.
            player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(metadata).build())
        }
    }

    private fun artworkAllowed(): Boolean =
        container.settingsState.value.loadArtworkOnSatellite ||
            container.networkMonitor.state.value.networkClass != NetworkClass.SATELLITE

    /**
     * Skips songs that can't be played (offline and not downloaded, removed upstream…), and
     * re-tunes a SiriusXM channel whose stream link expired.
     */
    private inner class ErrorHandler(private val player: ExoPlayer) : Player.Listener {
        private var consecutiveErrors = 0

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error", error)
            val channel = player.currentMediaItem?.toTrack()?.let(SxmChannel::idOf)
            if (channel != null && System.currentTimeMillis() - lastRetuneAt > RETUNE_INTERVAL_MS) {
                lastRetuneAt = System.currentTimeMillis()
                if (channel.first != SiriusXmClient.LINEAR) {
                    // An Xtra track's stream ran out or broke: carry on with the next track.
                    advanceXtra(player, player.currentMediaItemIndex)
                    return
                }
                if (error.cause is HttpDataSource.InvalidResponseCodeException) {
                    // The live stream's link expired: tune again.
                    scope.launch {
                        runCatching { container.siriusXm.streamUrl(channel.first, channel.second, forceRetune = true) }
                        reload(player)
                        player.play()
                    }
                    return
                }
            }
            container.reportPlaybackError(error.cause?.message ?: "Playback failed (${error.errorCodeName})")
            consecutiveErrors++
            if (consecutiveErrors < player.mediaItemCount && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveErrors = 0
        }
    }

    private class NetworkAwareBitmapLoader(
        private val delegate: BitmapLoader,
        private val allowed: () -> Boolean,
    ) : BitmapLoader by delegate {
        override fun loadBitmap(uri: Uri): ListenableFuture<android.graphics.Bitmap> =
            if (uri.scheme?.startsWith("http") == true && !allowed()) {
                Futures.immediateFailedFuture(IOException("Artwork is off on satellite networks"))
            } else {
                delegate.loadBitmap(uri)
            }
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val NOW_PLAYING_INTERVAL_MS = 10_000L
        const val RETUNE_INTERVAL_MS = 30_000L
        const val XTRA_PROGRESS_INTERVAL_MS = 30_000L
    }
}
