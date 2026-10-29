package com.music.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.player.AppContainer
import com.music.player.MainActivity
import com.music.player.MusicApp
import com.music.player.quality.NetworkClass
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

// ResolvingDataSource, the extractor flags and the bitmap loader are Media3 "unstable" APIs.
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var container: AppContainer
    private var session: MediaSession? = null
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        container = (application as MusicApp).container

        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(container.httpClient))
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
        player.addListener(SkipBrokenItems(player, container::reportPlaybackError))

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
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

    private fun artworkAllowed(): Boolean =
        container.settingsState.value.loadArtworkOnSatellite ||
            container.networkMonitor.state.value.networkClass != NetworkClass.SATELLITE

    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.withPlaybackUri() }.toMutableList())
    }

    /** A song that can't be played (offline and not downloaded, removed upstream…) is skipped. */
    private class SkipBrokenItems(
        private val player: ExoPlayer,
        private val report: (String) -> Unit,
    ) : Player.Listener {
        private var consecutiveErrors = 0

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error", error)
            report(error.cause?.message ?: "Playback failed (${error.errorCodeName})")
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
    }
}
