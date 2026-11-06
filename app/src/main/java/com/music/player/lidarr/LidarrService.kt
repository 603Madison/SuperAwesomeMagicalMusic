package com.music.player.lidarr

import android.util.Log
import com.music.player.data.LidarrRequestDao
import com.music.player.data.LidarrRequestEntity
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.net.HttpException
import com.music.player.settings.LidarrConfig
import com.music.player.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

sealed interface LidarrEvent {
    data class ArtistAdded(val artist: String) : LidarrEvent
    data class ArtistNotFound(val artist: String) : LidarrEvent
    data class Failed(val artist: String, val message: String) : LidarrEvent
}

/** Whether the Lidarr server answered the last time the queue was processed. */
sealed interface LidarrReachability {
    data object Unknown : LidarrReachability
    data class Reachable(val at: Long) : LidarrReachability
    data class Unreachable(val at: Long, val reason: String) : LidarrReachability
}

/**
 * When a song has to come from outside Jellyfin, make sure its artist is in Lidarr so the music
 * ends up in the library.
 *
 * Lidarr usually only answers on the home network, so requests are queued in the database and
 * sent whenever the server can be reached: right away when it can, otherwise on the next network
 * change, periodic check or manual retry. For each queued artist, nothing happens if Lidarr already
 * has them; otherwise they are looked up on MusicBrainz and added (monitored, searching for albums).
 */
class LidarrService(
    private val client: LidarrClient,
    private val config: suspend () -> LidarrConfig,
    private val queue: LidarrRequestDao,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _events = MutableSharedFlow<LidarrEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LidarrEvent> = _events

    private val _reachability = MutableStateFlow<LidarrReachability>(LidarrReachability.Unknown)
    val reachability: StateFlow<LidarrReachability> = _reachability.asStateFlow()

    val pending: Flow<List<LidarrRequestEntity>> = queue.observeAll()

    private val mutex = Mutex()

    /** Normalized artist names already dealt with this session, so each is only queued once. */
    private val handled = mutableSetOf<String>()
    private var cachedArtists: List<LidarrArtist>? = null
    private var cachedAt = 0L

    /** Fire-and-forget: called when a song that isn't in Jellyfin is played, downloaded or saved. */
    fun onTrackMissingFromJellyfin(track: Track) {
        if (track.source == SourceType.JELLYFIN) return
        scope.launch { request(track) }
    }

    /** Queues the artist of [track], then tries to reach Lidarr. */
    suspend fun request(track: Track) {
        val settings = config()
        if (!settings.isConfigured || !settings.autoAddArtists) return
        val name = TrackMatcher.cleanArtist(track.artist).ifBlank { return }
        val key = TrackMatcher.normalize(name)
        if (key.isEmpty() || synchronized(handled) { key in handled }) return
        queue.enqueue(LidarrRequestEntity(artistKey = key, artistName = name, trackTitle = track.title, requestedAt = clock()))
        // Lidarr was just found unreachable (away from home, say): leave it queued rather than
        // trying again for every song. A network change or the queue worker sends it later.
        val reachability = _reachability.value
        if (reachability is LidarrReachability.Unreachable && clock() - reachability.at < RETRY_UNREACHABLE_MS) return
        process()
    }

    /** Fire-and-forget [process], e.g. after a network change. */
    fun processInBackground() {
        scope.launch { runCatching { process() }.onFailure { Log.w(TAG, "Lidarr queue failed", it) } }
    }

    fun remove(artistKey: String) {
        scope.launch { queue.delete(artistKey) }
    }

    /** Sends every queued request if Lidarr can be reached. Returns how many were completed. */
    suspend fun process(): Int = mutex.withLock {
        val settings = config()
        if (!settings.isConfigured) return 0
        val requests = queue.all()
        if (requests.isEmpty()) return 0

        try {
            client.testConnection(settings)
            _reachability.value = LidarrReachability.Reachable(clock())
        } catch (e: IOException) {
            // Not on the home network (or the server is down): keep everything queued.
            _reachability.value = LidarrReachability.Unreachable(clock(), describe(e))
            return 0
        }

        var completed = 0
        for (request in requests) {
            try {
                when (val outcome = ensureArtist(settings, request.artistName)) {
                    is Outcome.Added -> _events.tryEmit(LidarrEvent.ArtistAdded(outcome.name))
                    Outcome.AlreadyPresent -> Unit
                    Outcome.NotFound -> _events.tryEmit(LidarrEvent.ArtistNotFound(request.artistName))
                }
                queue.delete(request.artistKey)
                synchronized(handled) { handled += request.artistKey }
                completed++
            } catch (e: IOException) {
                val message = e.message ?: e.javaClass.simpleName
                queue.recordFailure(request.artistKey, message, clock())
                if (e !is HttpException) {
                    // The connection dropped: stop and try again later.
                    _reachability.value = LidarrReachability.Unreachable(clock(), message)
                    break
                }
                _events.tryEmit(LidarrEvent.Failed(request.artistName, message))
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                queue.recordFailure(request.artistKey, message, clock())
                _events.tryEmit(LidarrEvent.Failed(request.artistName, message))
            }
        }
        completed
    }

    private sealed interface Outcome {
        data class Added(val name: String) : Outcome
        data object AlreadyPresent : Outcome
        data object NotFound : Outcome
    }

    private suspend fun ensureArtist(settings: LidarrConfig, creditedArtist: String): Outcome {
        val candidates = listOf(TrackMatcher.cleanArtist(creditedArtist), TrackMatcher.primaryArtist(creditedArtist))
            .filter { it.isNotBlank() }
            .distinctBy(TrackMatcher::normalize)

        val existing = libraryArtists(settings)
        if (candidates.any { name -> existing.any { sameName(it.artistName, name) } }) return Outcome.AlreadyPresent

        val match = candidates.firstNotNullOfOrNull { name ->
            client.lookup(settings, name).firstOrNull { result ->
                result["artistName"]?.jsonPrimitive?.content?.let { sameName(it, name) } == true
            }
        } ?: return Outcome.NotFound

        val added = addWithDefaults(settings, match)
        cachedArtists = existing + added
        return Outcome.Added(added.artistName)
    }

    private suspend fun addWithDefaults(settings: LidarrConfig, lookupResult: JsonObject): LidarrArtist {
        val options = client.serverOptions(settings)
        val rootFolder = options.rootFolders.firstOrNull { it.path == settings.rootFolderPath }
            ?: options.rootFolders.firstOrNull()
            ?: error("Lidarr has no root folder configured")
        val qualityProfile = settings.qualityProfileId
            ?: rootFolder.defaultQualityProfileId?.takeIf { it > 0 }
            ?: options.qualityProfiles.firstOrNull()?.id
            ?: error("Lidarr has no quality profile")
        val metadataProfile = settings.metadataProfileId
            ?: rootFolder.defaultMetadataProfileId?.takeIf { it > 0 }
            ?: options.metadataProfiles.firstOrNull()?.id
            ?: error("Lidarr has no metadata profile")
        return client.addArtist(settings, lookupResult, qualityProfile, metadataProfile, rootFolder.path)
    }

    private suspend fun libraryArtists(settings: LidarrConfig): List<LidarrArtist> {
        val now = clock()
        cachedArtists?.takeIf { now - cachedAt < CACHE_MS }?.let { return it }
        return client.artists(settings).also {
            cachedArtists = it
            cachedAt = now
        }
    }

    private fun describe(e: IOException): String = when (e) {
        is HttpException -> if (e.code == 401) "the API key was rejected" else "it answered HTTP ${e.code}"
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.UnknownHostException,
        is java.net.NoRouteToHostException,
        -> "not reachable from this network"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun sameName(a: String, b: String): Boolean {
        val na = TrackMatcher.normalize(a).removePrefix("the ")
        val nb = TrackMatcher.normalize(b).removePrefix("the ")
        return na.isNotEmpty() && na == nb
    }

    private companion object {
        const val TAG = "LidarrService"
        const val CACHE_MS = 10 * 60 * 1000L

        /** After finding Lidarr unreachable, new requests just wait in the queue this long. */
        const val RETRY_UNREACHABLE_MS = 5 * 60 * 1000L
    }
}
