package com.music.connect.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.music.connect.Container
import com.music.connect.LiveNow
import com.music.connect.MainActivity
import com.music.connect.R
import com.music.connect.container
import com.music.connect.net.ApiTrack
import com.music.connect.net.ConnectCommand
import com.music.connect.net.PlayState
import com.music.player.model.SourceType
import com.music.player.quality.NetworkClass
import com.music.player.quality.StreamQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** A song's stream as chosen when it started: kept, so seeking doesn't ask the server again. */
data class StreamResolution(
    val url: String,
    val description: String,
    val gainDb: Float?,
    val kbps: Int,
    val atMs: Long,
    /** The stream's own bitrate, as the server knows it. */
    val streamKbps: Int? = null,
    val transcoded: Boolean = false,
    /** The kind of network it was picked on. */
    val network: NetworkClass? = null,
    /** Headers the address must be fetched with (YouTube's, when the phone fetches it). */
    val headers: Map<String, String> = emptyMap(),
    /** Fetched by the phone itself, straight from YouTube (the backup: see PlaybackService.choose). */
    val byPhone: Boolean = false,
    /** The song's YouTube id, when it's a YouTube Music song: for the backup. */
    val youtubeId: String? = null,
)

/** Where a song being played comes from, for its details (see TrackInfoDialog). */
data class StreamFacts(
    val description: String,
    val kbps: Int? = null,
    /** The bitrate cap it was picked under (0: original quality). */
    val capKbps: Int? = null,
    val transcoded: Boolean = false,
    val gainDb: Float? = null,
    val downloaded: Boolean = false,
    /** The network it was picked on. */
    val network: String? = null,
    /** Fetched by the phone straight from the service, not through the server. */
    val byPhone: Boolean = false,
)

/** The audio the player is decoding, as it says. */
data class AudioDetails(val key: String?, val mime: String?, val codecs: String?, val sampleRateHz: Int?, val channels: Int?, val bitrateKbps: Int?)

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var c: Container
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val loudness get() = c.loudness
    private val resolutions = ConcurrentHashMap<String, StreamResolution>()
    private lateinit var queueStore: QueueStore

    // Listening, for history.
    private var listening: ApiTrack? = null
    private var listenedMs = 0L
    private var playingSince = 0L
    private var startedAt = 0L

    private var livePoll: Job? = null
    private var stationRefill: Job? = null

    override fun onCreate() {
        super.onCreate()
        c = container
        queueStore = QueueStore(filesDir)
        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(c.http))
        val dataSources = ResolvingDataSource.Factory(upstream) { spec -> resolve(spec) }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(loudness)).build()
        }
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            // On satellite the link comes and goes: load well ahead while it's there.
            .setLoadControl(BufferControl(bufferAheadMs = { if (c.network.state.value.networkClass == NetworkClass.SATELLITE) 10 * 60_000L else 2 * 60_000L }))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                c.audioDetails.value = AudioDetails(
                    key = player.currentMediaItem?.let(MediaItems::track)?.key,
                    mime = format.sampleMimeType,
                    codecs = format.codecs,
                    sampleRateHz = format.sampleRate.takeIf { it != Format.NO_VALUE },
                    channels = format.channelCount.takeIf { it != Format.NO_VALUE },
                    bitrateKbps = listOf(format.averageBitrate, format.bitrate, format.peakBitrate).firstOrNull { it != Format.NO_VALUE && it > 0 }?.let { it / 1000 },
                )
            }
        })
        loudness.enabled = c.settings.value.levelling
        scope.launch { c.settings.collect { loudness.enabled = it.levelling } }

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, player, LibraryCallback(c, queueStore, scope))
            .setSessionActivity(open)
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) })

        // This phone among the person's devices: say what's playing, and do what the others ask.
        c.devices.start()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = reportState()
        })
        scope.launch { c.devices.commands.collect(::obey) }
        watchForStalls()
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) reportState(force = true)
            }
        }
    }

    private var reported: String? = null
    private var reportedAt = 0L
    private var reportedPosition = 0L

    /** Tells the person's other devices what's playing, when that changes (or the position jumps). */
    private fun reportState(force: Boolean = false) {
        val track = player.currentMediaItem?.let(MediaItems::track)
        val playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        val position = player.currentPosition.coerceAtLeast(0)
        val volume = phoneVolume()
        val key = "${track?.key}|${player.currentMediaItemIndex}|$playing|${player.mediaItemCount}|$volume|${player.repeatMode}"
        val expected = reportedPosition + if (playing) SystemClock.elapsedRealtime() - reportedAt else 0
        if (!force && key == reported && abs(position - expected) < 3_000) return
        reported = key
        reportedAt = SystemClock.elapsedRealtime()
        reportedPosition = position
        c.devices.report(
            PlayState(
                track = track,
                queue = (0 until player.mediaItemCount).mapNotNull { MediaItems.track(player.getMediaItemAt(it)) },
                index = player.currentMediaItemIndex,
                positionMs = position,
                durationMs = player.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0,
                playing = playing,
                volume = volume,
                live = track?.isLive == true,
                repeat = when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "one"
                    Player.REPEAT_MODE_ALL -> "all"
                    else -> "off"
                },
            ),
        )
    }

    private var notedLive: String? = null

    /** A channel, station or Pandora station started: noted on the server for "Your radio". */
    private fun noteLivePlayed(track: ApiTrack) {
        val station = c.favoriteOf(track) ?: return
        if (station.key == notedLive) return
        notedLive = station.key
        c.scope.launch { runCatching { c.api.livePlayed(station) } }
    }

    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /** The phone's media volume (what its volume buttons change), 0 to 1. */
    private fun phoneVolume(): Double {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max
    }

    /** What another device asked: control what's playing here, or carry on what it was playing. */
    private fun obey(command: ConnectCommand) {
        when (command.action) {
            "play" -> {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
            "pause" -> player.pause()
            "next" -> player.seekToNextMediaItem()
            "previous" -> player.seekToPrevious()
            "seek" -> command.positionMs?.let { player.seekTo(it) }
            "volume" -> command.volume?.let { volume ->
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume.coerceIn(0.0, 1.0) * max).roundToInt(), 0)
                reportState(force = true)
            }
            "take" -> command.state?.takeIf { it.queue.isNotEmpty() }?.let { state ->
                val server = c.settings.value.server
                val items = state.queue.map { track -> MediaItems.playable(track, server) { url -> MediaItems.artworkUri(server, url, 512) } }
                // Live radio joins where the broadcast is now; songs carry on from the same moment.
                val start = if (state.live) C.TIME_UNSET else state.positionMs.coerceAtLeast(0)
                player.setMediaItems(items, state.index.coerceIn(0, items.size - 1), start)
                player.prepare()
                player.playWhenReady = state.playing
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        c.devices.stop()
        endListen()
        queueStore.save(player)
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    /**
     * Where a song plays from, chosen as it starts: the download if there is one, else the server's
     * pick at the bitrate for the network in use now. Live radio's address passes through.
     */
    private fun resolve(spec: DataSpec): DataSpec {
        val track = MediaItems.trackOf(spec.uri) ?: return spec
        val file = runBlocking { c.downloads.fileFor(track) }
        if (file != null) {
            describe(track, StreamFacts("Downloaded copy", downloaded = true))
            return spec.withUri(Uri.fromFile(file))
        }
        val network = c.network.state.value
        if (!network.isOnline) throw IOException("\"${track.title}\" isn't downloaded, and there's no connection")
        val settings = c.settings.value
        val kbps = settings.kbpsFor(network.networkClass)
        // A song keeps the stream it started with while it plays (seeking reopens it, and another
        // bitrate's bytes wouldn't fit); one fetched ahead is chosen again if the network changed.
        val now = System.currentTimeMillis()
        val pinned = resolutions[track.key]?.takeIf {
            // Kept a while through a network flapping back and forth; a new quality setting applies at once.
            it.kbps == kbps || track.key == currentKey || (it.network != network.networkClass && now - it.atMs < PIN_MS)
        }?.takeIf {
            // YouTube's own addresses stop working after a few hours (the server's don't: it asks again).
            !it.byPhone || track.key == currentKey || now - it.atMs < PHONE_ADDRESS_MS
        }
        val chosen = pinned ?: runBlocking { choose(track, kbps, network.networkClass) }.also { resolutions[track.key] = it }
        describe(
            track,
            StreamFacts(
                chosen.description, chosen.streamKbps, chosen.kbps, chosen.transcoded, chosen.gainDb,
                network = network.networkClass.label,
                byPhone = chosen.byPhone,
            ),
        )
        // (ConcurrentHashMap holds no nulls: most songs have no known gain.)
        chosen.gainDb?.let { gains[track.key] = it } ?: gains.remove(track.key)
        // This runs on the loader's thread, where the player can't be touched: compare with the
        // song the player's thread last said is playing.
        if (currentKey == track.key) loudness.knownGainDb = chosen.gainDb
        val located = spec.withUri(chosen.url.toUri())
        return if (chosen.headers.isEmpty()) located else located.withAdditionalHeaders(chosen.headers)
    }

    /**
     * YouTube Music songs whose copy from the server didn't play (or the server couldn't be
     * reached): the phone asks YouTube for these itself, by their YouTube id, for a while
     * ([BACKUP_MS]) before trying the server again. (id, since)
     */
    private val fromYouTube = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * The server's pick, always first. A YouTube Music song has a backup: the phone asks YouTube
     * itself, over its own connection, when the server can't be reached or can't get it, or when
     * the server's copy fails or stalls (see [fallBackToYouTube]). It's slower to start, so only then.
     */
    private suspend fun choose(track: ApiTrack, kbps: Int, network: NetworkClass): StreamResolution {
        fromYouTube[track.key]?.takeIf { System.currentTimeMillis() - it.second < BACKUP_MS }?.let { (id, _) ->
            return direct(track, id, kbps, network) ?: throw IOException("Neither your server nor YouTube could play \"${track.title}\"")
        }
        val resolved = try {
            // Comparing every service costs round trips: only where the link is quick.
            c.api.resolve(track, kbps, thorough = network == NetworkClass.WIFI)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (track.source != SourceType.YOUTUBE_MUSIC) throw e
            Log.w(TAG, "Your server couldn't get ${track.title}; asking YouTube directly", e)
            fromYouTube[track.key] = track.sourceId to System.currentTimeMillis()
            return direct(track, track.sourceId, kbps, network) ?: throw e
        }
        return StreamResolution(
            c.api.absolute(resolved.streamUrl), resolved.description, resolved.gainDb, kbps, System.currentTimeMillis(),
            resolved.kbps, resolved.transcoded, network,
            youtubeId = resolved.track.sourceId.takeIf { resolved.track.source == SourceType.YOUTUBE_MUSIC },
        )
    }

    /** [youtubeId] straight from YouTube, over the phone's own connection; null if YouTube didn't answer. */
    private suspend fun direct(track: ApiTrack, youtubeId: String, kbps: Int, network: NetworkClass): StreamResolution? = try {
        val stream = c.youtube.stream(youtubeId, StreamQuality.fromStored(kbps))
        StreamResolution(
            stream.url, stream.description, null, kbps, System.currentTimeMillis(), stream.kbps,
            network = network, headers = stream.headers, byPhone = true, youtubeId = youtubeId,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "YouTube didn't answer the phone for ${track.title} either", e)
        null
    }

    /**
     * The song playing now is fetched straight from YouTube from here on, when the server's copy
     * of it (a YouTube Music song) failed or stalled. False when there's nothing else to try.
     */
    private fun fallBackToYouTube(reason: String): Boolean {
        val key = currentKey ?: return false
        val failed = resolutions[key] ?: return false
        val id = failed.youtubeId?.takeIf { !failed.byPhone } ?: return false
        val now = System.currentTimeMillis()
        if (fromYouTube[key]?.let { now - it.second < BACKUP_MS } == true) return false
        fromYouTube[key] = id to now
        Log.w(TAG, "Your server's copy of ${player.currentMediaItem?.mediaMetadata?.title} $reason; asking YouTube directly")
        resolutions.remove(key)
        val at = player.currentPosition
        val index = player.currentMediaItemIndex
        player.stop()
        player.seekTo(index, at)
        player.prepare()
        return true
    }

    /**
     * Watches for a song that never starts: nothing loaded for [STALL_MS] while it should be
     * playing. A YouTube Music song from the server is asked of YouTube directly instead.
     */
    private fun watchForStalls() = scope.launch {
        var stuck: String? = null
        var since = 0L
        while (isActive) {
            delay(2_000)
            val key = currentKey
            val stalled = key != null && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING &&
                player.totalBufferedDuration < 500 && player.currentMediaItem?.let(MediaItems::track)?.isLive != true
            when {
                !stalled -> stuck = null
                stuck != key -> {
                    stuck = key
                    since = SystemClock.elapsedRealtime()
                }
                SystemClock.elapsedRealtime() - since >= STALL_MS -> {
                    stuck = null
                    fallBackToYouTube("stalled")
                }
            }
        }
    }

    private val gains = ConcurrentHashMap<String, Float>()

    /** The playing song's key, set on the player's thread for other threads to read. */
    @Volatile
    private var currentKey: String? = null

    private fun describe(track: ApiTrack, facts: StreamFacts) {
        c.playingDescription.value = c.playingDescription.value + (track.key to facts.description)
        c.streamFacts.value = c.streamFacts.value + (track.key to facts)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The same song with new details (what's on a live channel, see showLive): not a new song.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem?.let(MediaItems::track)?.key == currentKey && currentKey != null) return
            endListen()
            val track = mediaItem?.let(MediaItems::track)
            currentKey = track?.key
            if (track == null) return
            listening = track
            startedAt = System.currentTimeMillis()
            listenedMs = 0
            if (player.isPlaying) playingSince = SystemClock.elapsedRealtime()
            loudness.knownGainDb = gains[track.key]
            c.liveNow.value = null
            noteLivePlayed(track)
            watchLive(track)
            if (track.isStationTrack) refillStation(track)
            queueStore.save(player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                playingSince = SystemClock.elapsedRealtime()
            } else if (playingSince > 0) {
                listenedMs += SystemClock.elapsedRealtime() - playingSince
                playingSince = 0
                queueStore.save(player)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val track = player.currentMediaItem?.let(MediaItems::track)
            Log.w(TAG, "Couldn't play ${track?.title}", error)
            // A YouTube Music song from the server: YouTube may still give it to the phone.
            if (fallBackToYouTube("failed (${error.errorCodeName})")) {
                player.play()
                return
            }
            val cause = generateSequence(error as Throwable) { it.cause }.firstOrNull { it is IOException }
            c.tell(cause?.message?.takeIf { it.isNotBlank() } ?: "Couldn't play \"${track?.title ?: "this"}\"")
            // Skip what can't be played, as the Lite app does.
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    /** History: half the song (or four minutes), and at least 30 seconds, counts as a listen. */
    private fun endListen() {
        val track = listening ?: return
        if (playingSince > 0) listenedMs += SystemClock.elapsedRealtime() - playingSince
        playingSince = if (player.isPlaying) SystemClock.elapsedRealtime() else 0
        val length = track.durationMs ?: 0
        val needed = maxOf(30_000L, minOf(if (length > 0) length / 2 else 30_000L, 240_000L))
        if (listenedMs >= needed) c.recordListen(track, listenedMs, startedAt)
        if (track.isStationTrack && track.via != null) {
            val (stationId, trackId) = MediaItems.split(track.sourceId.removePrefix("stationtrack:"))
            val elapsed = listenedMs
            c.scope.launch { runCatching { c.api.stationProgress(track.via, stationId, trackId, elapsed, false) } }
        }
        listening = null
        listenedMs = 0
    }

    /** What's on a live channel or station, for the app and the notification. */
    private fun watchLive(track: ApiTrack) {
        livePoll?.cancel()
        if (!track.isLive && !track.isStationTrack) return
        val via = track.via ?: return
        if (track.isStationTrack) {
            val (stationId, trackId) = MediaItems.split(track.sourceId.removePrefix("stationtrack:"))
            c.scope.launch { runCatching { c.api.stationProgress(via, stationId, trackId, 0, true) } }
            return
        }
        livePoll = scope.launch {
            while (isActive) {
                // Where playback is in the stream, as a time (the live edge is ahead of what's heard);
                // read here, on the player's thread.
                val behindMs = player.currentLiveOffset.takeIf { it != C.TIME_UNSET } ?: 0L
                val now = withContext(Dispatchers.IO) {
                    runCatching {
                        if (track.isChannel) {
                            val (type, id) = MediaItems.split(track.sourceId.removePrefix("channel:"))
                            c.api.channelNow(via, type, id, System.currentTimeMillis() - behindMs)?.let { LiveNow(it.title, it.artist, it.artworkUrl, it.song) }
                        } else {
                            c.api.radioNow(via, track.sourceId.removePrefix("hls:")).title?.let { title ->
                                val at = title.indexOf(" - ")
                                if (at > 0) LiveNow(title.substring(at + 3).trim(), title.substring(0, at).trim(), null, null) else LiveNow(title, "", null, null)
                            }
                        }
                    }.getOrNull()
                }
                if (player.currentMediaItem?.let(MediaItems::track)?.key != track.key) break
                if (now != c.liveNow.value) {
                    c.liveNow.value = now
                    showLive(track, now)
                }
                delay(if (track.isChannel) 15_000 else 20_000)
            }
        }
    }

    /** Puts what's on into the notification (and the car's screen) without interrupting the stream. */
    private fun showLive(track: ApiTrack, now: LiveNow?) {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val server = c.settings.value.server
        val shown = if (now == null) track else track.copy(title = now.title, artist = now.artist.ifBlank { track.title }, album = track.title, artworkUrl = now.artworkUrl ?: track.artworkUrl)
        runCatching {
            player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(MediaItems.metadata(shown, { MediaItems.artworkUri(server, it, 512) })).build())
        }
    }

    /** Near the end of a Pandora station's queue, asks for more of it. */
    private fun refillStation(track: ApiTrack) {
        if (player.mediaItemCount - player.currentMediaItemIndex > 3 || stationRefill?.isActive == true) return
        val last = (player.mediaItemCount - 1).takeIf { it >= 0 }?.let { player.getMediaItemAt(it) }?.let(MediaItems::track) ?: return
        if (!last.isStationTrack || last.via == null) return
        val (stationId, _) = MediaItems.split(last.sourceId.removePrefix("stationtrack:"))
        stationRefill = scope.launch {
            val more = withContext(Dispatchers.IO) { runCatching { c.api.station(last.via, null, stationId, last.album, true) }.getOrNull() }
            val server = c.settings.value.server
            more?.tracks?.map { MediaItems.playable(it, server) { url -> MediaItems.artworkUri(server, url, 512) } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { player.addMediaItems(it) }
        }
    }

    private companion object {
        const val TAG = "PlaybackService"

        /** How long a stream fetched ahead stays chosen, whatever the network does meanwhile. */
        const val PIN_MS = 10 * 60_000L
        const val PHONE_ADDRESS_MS = 2 * 60 * 60_000L
        const val STALL_MS = 20_000L
        const val BACKUP_MS = 30 * 60_000L
    }
}
