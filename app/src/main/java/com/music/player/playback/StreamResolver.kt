package com.music.player.playback

import android.net.Uri
import android.util.LruCache
import com.music.player.data.DownloadDao
import com.music.player.data.MissingDetails
import com.music.player.lidarr.LidarrService
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.net.NetworkMonitor
import com.music.player.quality.NetworkClass
import com.music.player.quality.StreamQuality
import com.music.player.settings.SettingsRepository
import com.music.player.sources.Catalog
import com.music.player.sources.SelectedStream
import com.music.player.sources.SourceSelector
import com.music.player.sources.SourceStream
import com.music.player.sources.SourceUnavailableException
import com.music.player.sources.siriusxm.SiriusXmClient
import com.music.player.sources.siriusxm.SxmChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException

/**
 * Where a queued track is actually being played from, shown in the now-playing screen and the
 * song's info. [source] is null for downloads, whose [description] says where the file came from.
 */
data class PlaybackOrigin(
    val source: SourceType?,
    val description: String,
    /** The stream's nominal bitrate; lossless counts as 1411+. */
    val kbps: Int? = null,
    /** The copy that is playing, which may be another service's copy of the queued song. */
    val playing: Track? = null,
    /** The bitrate cap that applied when the stream was picked. */
    val quality: StreamQuality? = null,
    val downloaded: Boolean = false,
)

class OfflineException(message: String) : IOException(message)

/**
 * Turns a [Track] into something playable: a downloaded copy always wins, otherwise
 * [SourceSelector] picks a source (Jellyfin first, then streaming services, involving Lidarr when
 * Jellyfin lacks the song) at the bitrate set for the current network.
 *
 * Once a track has started, its source and quality are pinned, so re-opening it (seeking,
 * resuming, refreshing an expired URL) returns byte-identical media even if the network changed.
 */
class StreamResolver(
    private val catalog: Catalog,
    private val downloads: DownloadDao,
    private val network: NetworkMonitor,
    private val settings: SettingsRepository,
    private val lidarr: LidarrService,
    private val siriusXm: SiriusXmClient,
    private val details: MissingDetails,
    private val scope: CoroutineScope,
) {
    /** Downloads already looked up for missing artwork this session. */
    private val lookedUp: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private sealed interface Pin {
        data class Local(val path: String) : Pin
        data class Remote(val track: Track, val quality: StreamQuality) : Pin
    }

    private data class CachedStream(val stream: SourceStream, val fetchedAt: Long)

    private val pins = LruCache<String, Pin>(200)
    private val streams = LruCache<String, CachedStream>(50)

    private val _origins = MutableStateFlow<Map<String, PlaybackOrigin>>(emptyMap())
    val origins: StateFlow<Map<String, PlaybackOrigin>> = _origins.asStateFlow()

    /** Returns the URI and HTTP headers ExoPlayer should load for [track]. */
    suspend fun resolveForPlayback(track: Track): Pair<String, Map<String, String>> {
        SxmChannel.idOf(track)?.let { (type, channelId) ->
            if (!network.state.value.isOnline) throw OfflineException("SiriusXM needs a connection")
            val xtra = type != SiriusXmClient.LINEAR
            val url = if (xtra) siriusXm.xtraUri(type, channelId) else siriusXm.streamUrl(type, channelId)
            publishOrigin(track.key, PlaybackOrigin(SourceType.SIRIUSXM, if (xtra) "SiriusXM · Xtra" else "SiriusXM · Live"))
            return url to mapOf("User-Agent" to SiriusXmClient.USER_AGENT)
        }
        when (val pin = pins.get(track.key)) {
            is Pin.Local -> if (File(pin.path).exists()) return localUri(pin.path)
            is Pin.Remote -> {
                val stream = cachedOrFetch(track.key, pin)
                return stream.url to stream.headers
            }
            null -> Unit
        }

        downloads.completed(track.key)?.takeIf { it.filePath?.let(::File)?.exists() == true }?.let { download ->
            val path = download.filePath!!
            pins.put(track.key, Pin.Local(path))
            val origin = PlaybackOrigin(
                source = null,
                description = listOfNotNull("Downloaded", download.qualityDescription).joinToString(" · "),
                kbps = download.effectiveKbps,
                playing = download.track,
                downloaded = true,
            )
            publishOrigin(track.key, origin)
            if (download.track.artworkUrl == null && network.state.value.isOnline && lookedUp.add(track.key)) {
                // Saved without artwork (e.g. from SiriusXM): borrow it from another service's copy.
                scope.launch {
                    val copy = runCatching { catalog.findCopyWithArtwork(download.track) }.getOrNull() ?: return@launch
                    publishOrigin(track.key, origin.copy(playing = details.fill(download.track, copy)))
                }
            }
            return localUri(path)
        }

        val state = network.state.value
        if (!state.isOnline) throw OfflineException("\"${track.title}\" isn't downloaded and you're offline")
        val quality = settings.current().quality.forNetwork(state.networkClass)
        // Off satellite, compare every service for the best copy; on satellite take the first fit.
        val selected = select(track, quality, thorough = state.networkClass != NetworkClass.SATELLITE)
        details.fill(track, selected.track)
        pins.put(track.key, Pin.Remote(selected.track, quality))
        streams.put(track.key, CachedStream(selected.stream, System.currentTimeMillis()))
        publishOrigin(
            track.key,
            PlaybackOrigin(
                source = selected.track.source,
                description = "${selected.track.source.label} · ${selected.stream.description}",
                kbps = selected.stream.kbps,
                playing = selected.track,
                quality = quality,
            ),
        )
        return selected.stream.url to selected.stream.headers
    }

    /** Picks the stream to save for offline listening at the download quality. */
    suspend fun resolveForDownload(track: Track): SelectedStream {
        if (track.isSiriusXmChannel) throw SourceUnavailableException("Live radio can't be downloaded")
        return select(track, settings.current().quality.download, thorough = true)
    }

    /** The quality a streaming track is pinned at, if it's streaming (not downloaded or live). */
    fun pinnedQuality(key: String): StreamQuality? = (pins.get(key) as? Pin.Remote)?.quality

    /** Forgets a track's source and quality, so it's chosen afresh the next time it's opened. */
    fun release(key: String) {
        pins.remove(key)
        streams.remove(key)
    }

    /**
     * Forgets source and quality choices for everything except [keepKey] (the item playing now)
     * so queued tracks pick up a new network's bitrate.
     */
    fun onNetworkOrQualityChanged(keepKey: String?) {
        val keepPin = keepKey?.let { pins.get(it) }
        val keepStream = keepKey?.let { streams.get(it) }
        pins.evictAll()
        streams.evictAll()
        if (keepKey != null) {
            keepPin?.let { pins.put(keepKey, it) }
            keepStream?.let { streams.put(keepKey, it) }
        }
    }

    private suspend fun cachedOrFetch(key: String, pin: Pin.Remote): SourceStream {
        val cached = streams.get(key)
        val maxAge = if (pin.track.source == SourceType.JELLYFIN) Long.MAX_VALUE else REMOTE_URL_TTL_MS
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < maxAge) return cached.stream
        val fresh = catalog.source(pin.track.source).stream(pin.track.sourceId, pin.quality)
        streams.put(key, CachedStream(fresh, System.currentTimeMillis()))
        return fresh
    }

    private suspend fun select(track: Track, quality: StreamQuality, thorough: Boolean): SelectedStream =
        SourceSelector.select(
            track = track,
            quality = quality,
            jellyfin = catalog.jellyfin,
            fallbackOrder = catalog.fallbackOrder().map(catalog::source),
            thorough = thorough,
            onMissingFromJellyfin = lidarr::onTrackMissingFromJellyfin,
        )

    private fun publishOrigin(key: String, origin: PlaybackOrigin) {
        _origins.update { (it + (key to origin)).entries.toList().takeLast(200).associate { e -> e.toPair() } }
    }

    private fun localUri(path: String) = Uri.fromFile(File(path)).toString() to emptyMap<String, String>()

    private companion object {
        /** Qobuz and YouTube URLs are short-lived; refresh them after this long. */
        const val REMOTE_URL_TTL_MS = 20 * 60 * 1000L
    }
}
