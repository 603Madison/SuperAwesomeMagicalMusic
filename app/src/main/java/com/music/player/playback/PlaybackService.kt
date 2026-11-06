package com.music.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.DefaultRenderersFactory
import android.content.Context
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
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
import com.music.player.sources.siriusxm.SxmStationTrack
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
        container.appLog.log("service", "playback service started")

        val http = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(container.httpClient))
        // YouTube streams are read in short ranges (see ChunkedDataSource).
        val upstream = ChunkedDataSource.Factory(SiriusXmDataSource.Factory(http, container.siriusXm))
        val dataSourceFactory = ResolvingDataSource.Factory(upstream) { dataSpec -> resolve(dataSpec) }
        val extractors = DefaultExtractorsFactory()
            // Lets transcoded MP3/AAC streams of unknown length be seekable.
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        val loadControl = BufferControl { container.settingsState.value.bufferAheadSeconds * 1000L }

        val loudness = container.loudness
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(loudness))
                    .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(AppMediaSourceFactory(dataSourceFactory, extractors))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(ErrorHandler(player))
        player.addAnalyticsListener(DetailsListener())
        player.addListener(TrackChanges(player))

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val buttons = SessionButtons(container)
        session = MediaLibrarySession.Builder(this, player, LibraryCallback(container, scope, buttons))
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(buttons.buttons(player))
            .setBitmapLoader(
                NetworkAwareBitmapLoader(
                    DataSourceBitmapLoader.Builder(this)
                        .setDataSourceFactory(OkHttpDataSource.Factory(container.httpClient))
                        .build(),
                ) { artworkAllowed() },
            )
            .build()

        scope.launch { runSleepTimer(player) }

        // Volume levelling: Jellyfin's own loudness value where the song has one, else by ear.
        scope.launch {
            container.settings.settings.map { it.playback.levelVolume }.distinctUntilChanged().collect { loudness.enabled = it }
        }
        val updateKnownGain = {
            loudness.knownGainDb = player.currentMediaItem?.let { container.resolver.origins.value[it.mediaId]?.gainDb }
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = updateKnownGain()
        })
        scope.launch { container.resolver.origins.collect { updateKnownGain() } }

        // Android Auto's buttons follow what's playing: thumbs on station songs, and the
        // bookmark button turns into a tick once the song is bookmarked.
        // SiriusXM's now-playing updates every few seconds; only send buttons that changed.
        var shownButtons: List<Triple<Int, CharSequence, String?>>? = null
        refreshButtons = {
            val current = buttons.buttons(player)
            val summary = current.map { Triple(it.icon, it.displayName, it.sessionCommand?.customAction) }
            if (summary != shownButtons) {
                shownButtons = summary
                session?.setMediaButtonPreferences(current)
            }
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                refreshButtons()
                startAtSongFor = mediaItem?.mediaId?.takeIf {
                    mediaItem.toTrack()?.let(SxmChannel::idOf)?.first == SiriusXmClient.LINEAR
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                refreshButtons()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) startChannelAtSongStart(player)
            }
        })
        scope.launch {
            combine(container.bookmarks.items, container.siriusXm.thumbs, container.siriusXm.nowPlaying) { _, _, _ -> }
                .collect { refreshButtons() }
        }

        // On a network change (e.g. Wi-Fi -> satellite), queued songs re-pick their bitrate. The
        // current song keeps its stream so seeking within it stays consistent.
        // If the network now allows more (e.g. satellite -> Wi-Fi), the current song also switches
        // to a better copy, picking up where it was. When Data Saver comes on, it switches down.
        scope.launch {
            container.networkMonitor.state.map { it.networkClass to it.savingData }.distinctUntilChanged().drop(1).collect { (_, savingData) ->
                container.resolver.onNetworkOrQualityChanged(player.currentMediaItem?.mediaId)
                upgradeCurrentIfBetter(player, orLower = savingData)
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
                settings.quality.forState(network)
            }.distinctUntilChanged().collect { quality ->
                val maxBitrate = (quality as? StreamQuality.Capped)?.kbps?.times(1000) ?: Int.MAX_VALUE
                container.siriusXm.maxBitrate = maxBitrate
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setMaxAudioBitrate(maxBitrate)
                    .build()
            }
        }
        scope.launch { trackSiriusXmNowPlaying(player) }
        // Songs saved without artwork (e.g. from SiriusXM) show the copy that's playing's.
        scope.launch {
            container.resolver.origins.collect { origins ->
                player.currentMediaItem?.let { item -> origins[item.mediaId]?.playing?.let { showCopyDetails(player, it) } }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onBind(intent: Intent?): android.os.IBinder? {
        container.appLog.log("service", "bind: ${intent?.action}")
        return super.onBind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        container.appLog.log("service", "playback service stopped")
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
                    runCatching { container.siriusXm.onXtraHeard(channel.first, channel.second, heardAt) }
                }
                val cut = runCatching { container.siriusXm.cutAt(channel.first, channel.second, heardAt) }
                    .onFailure { Log.w(TAG, "SiriusXM now playing failed", it) }
                    .getOrNull()
                val nowPlaying = SxmNowPlaying(track, cut)
                if (nowPlaying != container.siriusXm.nowPlaying.value) {
                    container.siriusXm.publishNowPlaying(nowPlaying)
                    showSong(player, cut)
                }
                refreshButtons()
            }
            delay(NOW_PLAYING_INTERVAL_MS)
        }
    }

    /**
     * Re-opens the current song at a better quality if the current network or settings allow it,
     * or (with [orLower], when Data Saver comes on) at a lower one if they now ask for less.
     */
    private suspend fun upgradeCurrentIfBetter(player: ExoPlayer, orLower: Boolean = false) {
        val item = player.currentMediaItem ?: return
        val pinned = container.resolver.pinnedQuality(item.mediaId) ?: return // downloaded, live or not started
        val state = container.networkMonitor.state.value
        if (!state.isOnline) return
        val now = container.settings.current().quality.forState(state)
        if (now == pinned || !(now.isHigherThan(pinned) || orLower)) return
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

    /**
     * Track changes: songs left behind lose their pinned stream, so replaying them re-picks the
     * best copy for the network.
     */
    private inner class TrackChanges(private val player: ExoPlayer) : Player.Listener {
        private var previousKey: String? = null
        private var previousTrack: com.music.player.model.Track? = null

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val key = previousKey
            previousKey = mediaItem?.mediaId
            if (key != null && key != mediaItem?.mediaId) container.resolver.release(key)
            // The next song may have been resolved (and its copy found) before it started.
            mediaItem?.let { container.resolver.origins.value[it.mediaId]?.playing }?.let { showCopyDetails(player, it) }

            val finished = previousTrack
            val current = mediaItem?.toTrack()
            previousTrack = current
            onStationTrackChange(player, finished, current)
        }
    }

    /**
     * Pandora station tracks: tells SiriusXM which played, and when the last one queued for a
     * station starts, adds the next few after it (as SiriusXM's own player asks for more).
     */
    private fun onStationTrackChange(player: ExoPlayer, finished: com.music.player.model.Track?, current: com.music.player.model.Track?) {
        finished?.let(SxmStationTrack::idOf)?.takeIf { finished.key != current?.key }?.let { (stationId, trackId) ->
            scope.launch { container.siriusXm.onStationTrack(stationId, trackId, finished.durationMs ?: 0, start = false) }
        }
        val (stationId, trackId) = current?.let(SxmStationTrack::idOf) ?: return
        scope.launch { container.siriusXm.onStationTrack(stationId, trackId, 0, start = true) }
        val index = player.currentMediaItemIndex
        val laterFromStation = (index + 1 until player.mediaItemCount).any {
            player.getMediaItemAt(it).toTrack()?.let(SxmStationTrack::idOf)?.first == stationId
        }
        if (laterFromStation || !moreStationTracksRequested.add(trackId)) return
        val stationName = container.settingsState.value.siriusXmStations.firstOrNull { it.id == stationId }?.name ?: "Pandora station"
        scope.launch {
            val more = try {
                container.siriusXm.stationTracks(stationId, more = true)
            } catch (e: Exception) {
                container.appLog.log("player", "more tracks for station $stationId failed: $e")
                emptyList()
            }
            container.appLog.log("player", "station $stationId: ${more.size} more tracks")
            if (more.isEmpty()) return@launch
            // After the last track of this station still in the queue.
            val last = (0 until player.mediaItemCount).lastOrNull {
                player.getMediaItemAt(it).toTrack()?.let(SxmStationTrack::idOf)?.first == stationId
            } ?: return@launch
            player.addMediaItems(last + 1, more.map { it.toTrack(stationName).toMediaItem() })
        }
    }

    /**
     * Carries out the sleep timer: at the time set, fades out over a few seconds and pauses; for
     * "end of song", lets ExoPlayer pause when the song ends.
     */
    private suspend fun runSleepTimer(player: ExoPlayer) {
        val timer = container.sleepTimer
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && timer.state.value == SleepTimerState.EndOfSong) {
                    timer.cancel()
                }
            }
        })
        timer.state.collectLatest { state ->
            player.pauseAtEndOfMediaItems = state == SleepTimerState.EndOfSong
            if (state !is SleepTimerState.At) return@collectLatest
            delay((state.endsAtMs - System.currentTimeMillis() - SLEEP_FADE_MS).coerceAtLeast(0))
            val volume = player.volume
            try {
                val steps = 20
                for (step in 1..steps) {
                    player.volume = volume * (steps - step) / steps
                    delay(SLEEP_FADE_MS / steps)
                }
                player.pause()
                container.appLog.log("player", "sleep timer: paused")
            } finally {
                player.volume = volume
                if (timer.state.value == state) timer.cancel()
            }
        }
    }

    /** Updates Android Auto's buttons for what's playing (set up in onCreate). */
    private var refreshButtons: () -> Unit = {}

    /** A live channel just tuned, to be started at the beginning of the song that's on. */
    private var startAtSongFor: String? = null

    /**
     * Starts a just-tuned live channel at the beginning of the song that's on, as SiriusXM's own
     * app does, rather than partway through it. Only for songs that began within the stream's
     * reach and a little while ago (not talk shows, which run for hours).
     */
    private fun startChannelAtSongStart(player: ExoPlayer) {
        val mediaId = startAtSongFor ?: return
        val item = player.currentMediaItem?.takeIf { it.mediaId == mediaId } ?: return
        startAtSongFor = null
        val (type, channelId) = item.toTrack()?.let(SxmChannel::idOf) ?: return
        scope.launch {
            if (!container.settings.current().playback.siriusXmFromSongStart) return@launch
            val timeline = player.currentTimeline
            if (timeline.isEmpty) return@launch
            val windowStart = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).windowStartTimeMs
            if (windowStart == C.TIME_UNSET) return@launch
            val heardAt = windowStart + player.currentPosition
            val cut = runCatching { container.siriusXm.cutAt(type, channelId, heardAt) }.getOrNull() ?: return@launch
            val into = heardAt - cut.startMs
            if (!cut.isSong || cut.startMs < windowStart || into !in SONG_START_RANGE_MS) return@launch
            // Still the same channel, and nobody has moved it meanwhile.
            if (player.currentMediaItem?.mediaId != mediaId) return@launch
            // The live window may have moved on while the schedule was fetched.
            val nowStart = player.currentTimeline.takeIf { !it.isEmpty }
                ?.getWindow(player.currentMediaItemIndex, Timeline.Window())?.windowStartTimeMs
                ?.takeIf { it != C.TIME_UNSET } ?: return@launch
            container.appLog.log("player", "starting ${cut.title} from its beginning (${into / 1000}s back)")
            player.seekTo((cut.startMs - nowStart).coerceAtLeast(0))
        }
    }

    /** Station tracks more were already asked for after, so a replay doesn't ask again. */
    private val moreStationTracksRequested = HashSet<String>()

    /** Fills in the current song's artwork and album from the copy playing, where it has none. */
    private fun showCopyDetails(player: ExoPlayer, copy: com.music.player.model.Track) {
        val item = player.currentMediaItem ?: return
        if (item.toTrack()?.isLive != false) return
        val metadata = item.mediaMetadata
        val artwork = metadata.artworkUri ?: copy.artworkUrl?.toUri()
        val album = metadata.albumTitle ?: copy.album
        if (artwork == metadata.artworkUri && album == metadata.albumTitle) return
        val updated = metadata.buildUpon().setArtworkUri(artwork).setAlbumTitle(album).build()
        // Same stream, new metadata: ExoPlayer updates it without interrupting playback.
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(updated).build())
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

    /** Records the decoder's input format and the bitrate of what's downloaded, per item (see PlaybackDetails). */
    private inner class DetailsListener : AnalyticsListener {
        private val window = Timeline.Window()

        private fun mediaId(eventTime: AnalyticsListener.EventTime): String? {
            val timeline = eventTime.timeline
            if (timeline.isEmpty || eventTime.windowIndex >= timeline.windowCount) return null
            return timeline.getWindow(eventTime.windowIndex, window).mediaItem.mediaId
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            mediaId(eventTime)?.let { container.playbackDetails.onFormat(it, format) }
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
        ) {
            mediaId(eventTime)?.let { container.playbackDetails.onOutput(it, audioTrackConfig.sampleRate) }
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) {
            // Segments of streams (HLS) say which stretch of audio they hold; whole files don't.
            if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
            if (mediaLoadData.mediaStartTimeMs == C.TIME_UNSET || mediaLoadData.mediaEndTimeMs == C.TIME_UNSET) return
            val id = mediaId(eventTime) ?: return
            container.playbackDetails.onSegment(id, loadEventInfo.bytesLoaded, mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs)
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
            container.appLog.log("player", "error ${error.errorCodeName} on ${player.currentMediaItem?.mediaId}: ${error.cause ?: error.message}")
            val channel = player.currentMediaItem?.toTrack()?.let(SxmChannel::idOf)
            if (channel != null && System.currentTimeMillis() - lastRetuneAt > RETUNE_INTERVAL_MS) {
                lastRetuneAt = System.currentTimeMillis()
                if (channel.first != SiriusXmClient.LINEAR) {
                    // The Xtra stream broke (e.g. its links expired): start the channel afresh.
                    container.siriusXm.resetXtra(channel.second)
                    player.seekToDefaultPosition(player.currentMediaItemIndex)
                    player.prepare()
                    player.play()
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
        const val SLEEP_FADE_MS = 8_000L

        /** How far into a song a tuned channel may be for it to start from the beginning. */
        val SONG_START_RANGE_MS = 5_000L..8 * 60_000L
    }
}
