package com.music.server.music

import com.music.player.CoreLog
import com.music.player.lidarr.LidarrArtist
import com.music.player.lidarr.LidarrClient
import com.music.player.model.Track
import com.music.player.net.HttpException
import com.music.player.settings.LidarrConfig
import com.music.player.sources.TrackMatcher
import com.music.server.auth.User
import com.music.server.db.Database
import com.music.server.sources.SourceKind
import com.music.server.sources.SourceRegistry
import com.music.server.sources.SourceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class LidarrRequestView(val artist: String, val status: String, val detail: String, val updatedAt: Long)

/**
 * Asks Lidarr for the artists of songs that aren't in anyone's library yet (when the person's
 * Lidarr, or the shared one, is set to add artists automatically). Each artist is asked about
 * once; the outcome is kept so people can see what was added for them.
 */
class LidarrRequests(
    private val db: Database,
    private val store: SourceStore,
    private val registry: SourceRegistry,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val client = LidarrClient(http)
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val artistCache = ConcurrentHashMap<String, Pair<Long, List<LidarrArtist>>>()
    private val addLock = Mutex()

    fun request(user: User, track: Track) {
        val artist = TrackMatcher.cleanArtist(track.artist).ifBlank { return }
        val key = "${user.id}|${TrackMatcher.normalize(artist)}"
        if (!inFlight.add(key)) return
        scope.launch {
            try {
                process(user, artist)
            } catch (e: Exception) {
                CoreLog.w(TAG, "Lidarr request for $artist failed", e)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    suspend fun list(user: User): List<LidarrRequestView> = db.tx {
        query("SELECT * FROM lidarr_requests WHERE user_id = ? ORDER BY updated_at DESC LIMIT 200", user.id) {
            LidarrRequestView(it.string("artist"), it.string("status"), it.string("detail"), it.long("updated_at"))
        }
    }

    private suspend fun process(user: User, artist: String) {
        val source = store.activeFor(user).firstOrNull { it.kind == SourceKind.LIDARR } ?: return
        val config = registry.lidarrConfig(source)
        if (!config.isConfigured || !config.autoAddArtists) return
        val previous = db.tx {
            one("SELECT status, updated_at FROM lidarr_requests WHERE user_id = ? AND artist = ?", user.id, artist) {
                it.string("status") to it.long("updated_at")
            }
        }
        if (previous != null) {
            val (status, at) = previous
            val retryAfter = if (status == STATUS_FAILED) RETRY_FAILED_MS else RETRY_NOT_FOUND_MS
            if (status != STATUS_FAILED && status != STATUS_NOT_FOUND) return
            if (clock() - at < retryAfter) return
        }
        val (status, detail) = try {
            addLock.withLock { ensureArtist(source.id, config, artist) }
        } catch (e: IOException) {
            STATUS_FAILED to describe(e)
        }
        db.tx {
            update(
                """INSERT INTO lidarr_requests (user_id, artist, source_id, status, detail, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                   ON CONFLICT (user_id, artist) DO UPDATE SET source_id = excluded.source_id, status = excluded.status,
                   detail = excluded.detail, updated_at = excluded.updated_at""",
                user.id, artist, source.id, status, detail, clock(),
            )
        }
    }

    private suspend fun ensureArtist(sourceId: String, config: LidarrConfig, creditedArtist: String): Pair<String, String> {
        val candidates = listOf(TrackMatcher.cleanArtist(creditedArtist), TrackMatcher.primaryArtist(creditedArtist))
            .filter { it.isNotBlank() }
            .distinctBy(TrackMatcher::normalize)
        val existing = libraryArtists(sourceId, config)
        if (candidates.any { name -> existing.any { sameName(it.artistName, name) } }) return STATUS_PRESENT to "Already in Lidarr"
        val match = candidates.firstNotNullOfOrNull { name ->
            client.lookup(config, name).firstOrNull { result ->
                result["artistName"]?.jsonPrimitive?.content?.let { sameName(it, name) } == true
            }
        } ?: return STATUS_NOT_FOUND to "Lidarr couldn't find this artist"
        val added = addWithDefaults(config, match)
        artistCache[sourceId] = clock() to (existing + added)
        return STATUS_ADDED to "Added to Lidarr as ${added.artistName}"
    }

    private suspend fun addWithDefaults(config: LidarrConfig, lookupResult: JsonObject): LidarrArtist {
        val options = client.serverOptions(config)
        val rootFolder = options.rootFolders.firstOrNull { it.path == config.rootFolderPath }
            ?: options.rootFolders.firstOrNull()
            ?: throw IOException("Lidarr has no root folder set up")
        val qualityProfile = config.qualityProfileId
            ?: rootFolder.defaultQualityProfileId?.takeIf { it > 0 }
            ?: options.qualityProfiles.firstOrNull()?.id
            ?: throw IOException("Lidarr has no quality profile")
        val metadataProfile = config.metadataProfileId
            ?: rootFolder.defaultMetadataProfileId?.takeIf { it > 0 }
            ?: options.metadataProfiles.firstOrNull()?.id
            ?: throw IOException("Lidarr has no metadata profile")
        return client.addArtist(config, lookupResult, qualityProfile, metadataProfile, rootFolder.path)
    }

    private suspend fun libraryArtists(sourceId: String, config: LidarrConfig): List<LidarrArtist> {
        val now = clock()
        artistCache[sourceId]?.let { (at, artists) -> if (now - at < CACHE_MS) return artists }
        return client.artists(config).also { artistCache[sourceId] = now to it }
    }

    private fun describe(e: IOException): String = when (e) {
        is HttpException -> if (e.code == 401) "Lidarr rejected the API key" else "Lidarr answered HTTP ${e.code}"
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.UnknownHostException -> "Lidarr wasn't reachable"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun sameName(a: String, b: String): Boolean {
        val na = TrackMatcher.normalize(a).removePrefix("the ")
        val nb = TrackMatcher.normalize(b).removePrefix("the ")
        return na.isNotEmpty() && na == nb
    }

    companion object {
        private const val TAG = "LidarrRequests"
        const val STATUS_ADDED = "added"
        const val STATUS_PRESENT = "present"
        const val STATUS_NOT_FOUND = "not_found"
        const val STATUS_FAILED = "failed"
        private const val CACHE_MS = 10L * 60 * 1000
        private const val RETRY_FAILED_MS = 30L * 60 * 1000
        private const val RETRY_NOT_FOUND_MS = 7L * 24 * 3600 * 1000
    }
}
