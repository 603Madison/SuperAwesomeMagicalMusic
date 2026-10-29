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
import androidx.media3.exoplayer.DefaultLoadControl
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
        val loadControl = DefaultLoadControl.Builder()
            // Large buffers make loading bursty with long idle gaps, which suits satellite links.
            .setBufferDurationsMs(60_000, 300_000, 2_500, 5_000)
            .build()

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
        scope.launch {
            container.networkMonitor.state.map { it.networkClass }.distinctUntilChanged().drop(1).collect {
                container.resolver.onNetworkOrQualityChanged(player.currentMediaItem?.mediaId)
            }
        }
        scope.launch {
            container.settings.settings.map { it.quality }.distinctUntilChanged().drop(1).collect {
                container.resolver.onNetworkOrQualityChanged(player.currentMediaItem?.mediaId)
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
                val cut = runCatching { container.siriusXm.cutAt(channel.second, heardAt) }
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
            if (channel != null && error.cause is HttpDataSource.InvalidResponseCodeException &&
                System.currentTimeMillis() - lastRetuneAt > RETUNE_INTERVAL_MS
            ) {
                lastRetuneAt = System.currentTimeMillis()
                scope.launch {
                    runCatching { container.siriusXm.streamUrl(channel.first, channel.second, forceRetune = true) }
                    player.prepare()
                    player.play()
                }
                return
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
    }
}
