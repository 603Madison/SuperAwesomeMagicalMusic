package com.music.player.lidarr

import android.util.Log
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.settings.LidarrConfig
import com.music.player.settings.SettingsRepository
import com.music.player.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface LidarrEvent {
    data class ArtistAdded(val artist: String) : LidarrEvent
    data class ArtistNotFound(val artist: String) : LidarrEvent
    data class Failed(val artist: String, val message: String) : LidarrEvent
}

/**
 * When a song has to be streamed from outside Jellyfin, make sure its artist is in Lidarr so
 * the music ends up in the library: if Lidarr already has the artist nothing happens,
 * otherwise the artist is looked up on MusicBrainz and added (monitored, searching for albums).
 */
class LidarrService(
    private val client: LidarrClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<LidarrEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LidarrEvent> = _events

    private val mutex = Mutex()
    /** Normalized artist names already handled this session, so each artist is checked once. */
    private val handled = mutableSetOf<String>()
    private var cachedArtists: List<LidarrArtist>? = null
    private var cachedAt = 0L

    /** Fire-and-forget: called whenever a non-Jellyfin track is played, downloaded or saved. */
    fun onTrackMissingFromJellyfin(track: Track) {
        if (track.source == SourceType.JELLYFIN) return
        scope.launch {
            val config = settings.current().lidarr
            if (!config.isConfigured || !config.autoAddArtists) return@launch
            runCatching { ensureArtist(config, track.artist) }
                .onFailure { e ->
                    Log.w(TAG, "Lidarr check failed for ${track.artist}", e)
                    _events.tryEmit(LidarrEvent.Failed(track.artist, e.message ?: e.javaClass.simpleName))
                }
        }
    }

    private suspend fun ensureArtist(config: LidarrConfig, creditedArtist: String): Unit = mutex.withLock {
        val candidates = listOf(TrackMatcher.cleanArtist(creditedArtist), TrackMatcher.primaryArtist(creditedArtist))
            .filter { it.isNotBlank() }
            .distinctBy(TrackMatcher::normalize)
        val key = TrackMatcher.normalize(candidates.firstOrNull() ?: return@withLock)
        if (key in handled) return@withLock

        val existing = libraryArtists(config)
        if (candidates.any { name -> existing.any { sameName(it.artistName, name) } }) {
            handled += key
            return@withLock
        }

        val match = candidates.firstNotNullOfOrNull { name ->
            client.lookup(config, name).firstOrNull { result ->
                result["artistName"]?.jsonPrimitive?.content?.let { sameName(it, name) } == true
            }
        }
        if (match == null) {
            handled += key
            _events.tryEmit(LidarrEvent.ArtistNotFound(candidates.first()))
            return@withLock
        }

        val added = addWithDefaults(config, match)
        cachedArtists = existing + added
        handled += key
        _events.tryEmit(LidarrEvent.ArtistAdded(added.artistName))
    }

    private suspend fun addWithDefaults(config: LidarrConfig, lookupResult: JsonObject): LidarrArtist {
        val options = client.serverOptions(config)
        val rootFolder = options.rootFolders.firstOrNull { it.path == config.rootFolderPath }
            ?: options.rootFolders.firstOrNull()
            ?: error("Lidarr has no root folder configured")
        val qualityProfile = config.qualityProfileId
            ?: rootFolder.defaultQualityProfileId?.takeIf { it > 0 }
            ?: options.qualityProfiles.firstOrNull()?.id
            ?: error("Lidarr has no quality profile")
        val metadataProfile = config.metadataProfileId
            ?: rootFolder.defaultMetadataProfileId?.takeIf { it > 0 }
            ?: options.metadataProfiles.firstOrNull()?.id
            ?: error("Lidarr has no metadata profile")
        return client.addArtist(config, lookupResult, qualityProfile, metadataProfile, rootFolder.path)
    }

    private suspend fun libraryArtists(config: LidarrConfig): List<LidarrArtist> {
        val now = System.currentTimeMillis()
        cachedArtists?.takeIf { now - cachedAt < CACHE_MS }?.let { return it }
        return client.artists(config).also {
            cachedArtists = it
            cachedAt = now
        }
    }

    private fun sameName(a: String, b: String): Boolean {
        val na = TrackMatcher.normalize(a).removePrefix("the ")
        val nb = TrackMatcher.normalize(b).removePrefix("the ")
        return na.isNotEmpty() && na == nb
    }

    private companion object {
        const val TAG = "LidarrService"
        const val CACHE_MS = 10 * 60 * 1000L
    }
}
