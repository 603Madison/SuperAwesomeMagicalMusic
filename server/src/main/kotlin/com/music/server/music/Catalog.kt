package com.music.server.music

import com.music.player.CoreLog
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.quality.StreamQuality
import com.music.player.sources.SourceStream
import com.music.player.sources.SourceUnavailableException
import com.music.player.sources.jellyfin.JellyfinSource
import com.music.server.ApiError
import com.music.server.auth.User
import com.music.server.sources.SourceKind
import com.music.server.sources.SourceRegistry
import com.music.server.sources.SourceStore
import com.music.server.sources.StoredSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SearchGroup(
    val sourceId: String,
    val sourceName: String,
    val kind: SourceKind,
    val tracks: List<ApiTrack>,
    val error: String? = null,
)

/** The copy picked to play for a song: possibly another source's copy of it. */
data class Resolution(val source: StoredSource, val track: Track, val stream: SourceStream)

/**
 * A person's music across their sources: searching all of them, and picking where a song plays
 * from, by the same rules as the Lite app:
 *
 *  1. Libraries (Jellyfin) first: a song from any service is looked for there.
 *  2. Not in any library: Lidarr is asked to get the artist (if set up).
 *  3. Otherwise a streaming service: the song's own first, then the others. With [thorough]
 *     every service is asked at once and the best copy within the bitrate cap wins; without,
 *     the first copy within the cap does (fewer round trips on a slow link).
 *  4. A copy over the cap (Qobuz can't go below 320 kbps) only when nothing fits.
 */
class Catalog(
    private val store: SourceStore,
    private val registry: SourceRegistry,
    private val lidarr: LidarrRequests,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val streams = ConcurrentHashMap<String, Pair<Long, SourceStream>>()

    suspend fun search(user: User, query: String, limit: Int = 25): List<SearchGroup> = coroutineScope {
        val sources = store.activeFor(user).filter { it.kind.isOnDemand }
        sources.map { source ->
            async {
                val music = registry.music(source) ?: return@async null
                val result = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    runCatching { music.search(query, limit) }
                }
                when {
                    result == null -> SearchGroup(source.id, source.name, source.kind, emptyList(), "${source.name} took too long to answer")
                    result.isFailure -> {
                        val error = result.exceptionOrNull()!!
                        if (error is CancellationException) throw error
                        CoreLog.w(TAG, "Search failed on ${source.name}", error)
                        SearchGroup(source.id, source.name, source.kind, emptyList(), "${source.name}: ${error.message ?: error.javaClass.simpleName}")
                    }
                    else -> SearchGroup(source.id, source.name, source.kind, result.getOrThrow().map { ApiTrack.of(it, source.id) })
                }
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun resolve(user: User, wanted: Track, via: String?, quality: StreamQuality, thorough: Boolean): Resolution {
        // Songs heard on SiriusXM are found in the person's sources like any other; live streams aren't.
        if (wanted.isLive) throw ApiError.badRequest("Live radio doesn't play through this")
        val sources = store.activeFor(user)
        val errors = mutableListOf<String>()

        // 1. Libraries, the one the song came from first.
        var missingFromLibraries = true
        for (library in sources.filter { it.kind.isLibrary }.sortedByDescending { it.id == via }) {
            val music = registry.music(library) as? JellyfinSource ?: continue
            try {
                val copy = when {
                    wanted.source == SourceType.JELLYFIN && via == library.id -> wanted
                    else -> music.lookUp(wanted)
                }
                if (copy != null) return Resolution(library, copy, streamOf(library, copy.sourceId, quality))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The library couldn't be asked, so the song isn't known to be missing.
                missingFromLibraries = false
                errors += "${library.name}: ${e.message ?: e.javaClass.simpleName}"
                CoreLog.w(TAG, "${library.name} failed for ${wanted.key}", e)
            }
        }
        // 2. Lidarr.
        if (missingFromLibraries) lidarr.request(user, wanted)

        // 3. Streaming services: the song's own first.
        val services = sources.filter { it.kind.isOnDemand && !it.kind.isLibrary }
            .sortedWith(compareByDescending<StoredSource> { it.id == via }.thenByDescending { it.kind.type == wanted.source })
        val candidates: List<Resolution> = if (thorough) {
            coroutineScope { services.map { async { attempt(it, wanted, quality, errors) } }.awaitAll().filterNotNull() }
        } else {
            buildList {
                for (service in services) {
                    val found = attempt(service, wanted, quality, errors) ?: continue
                    add(found)
                    if (found.stream.withinCap) break
                }
            }
        }
        // 4. Best within the cap, else anything.
        candidates.filter { it.stream.withinCap }.maxByOrNull { it.stream.kbps ?: 0 }?.let { return it }
        candidates.firstOrNull()?.let { return it }
        throw ApiError.upstream(
            if (errors.isEmpty()) "\"${wanted.title}\" isn't available from any of your sources"
            else "Couldn't play \"${wanted.title}\": ${errors.joinToString("; ")}",
        )
    }

    private suspend fun attempt(source: StoredSource, wanted: Track, quality: StreamQuality, errors: MutableList<String>): Resolution? = try {
        val music = registry.music(source)
        music?.findMatch(wanted)?.let { copy -> Resolution(source, copy, streamOf(source, copy.sourceId, quality)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        synchronized(errors) { errors += "${source.name}: ${e.message ?: e.javaClass.simpleName}" }
        CoreLog.w(TAG, "${source.name} failed for ${wanted.key}", e)
        null
    }

    /**
     * The stream for [sourceId] on [source] at [quality]. Kept for a while, since a player fetches
     * a song in several pieces; [fresh] asks again (the kept address stopped working).
     */
    suspend fun streamOf(source: StoredSource, sourceId: String, quality: StreamQuality, fresh: Boolean = false): SourceStream {
        val key = "${source.id}|${source.updatedAt}|$sourceId|${quality.toStored()}"
        val now = clock()
        if (!fresh) streams[key]?.let { (at, stream) -> if (now - at < STREAM_CACHE_MS) return stream }
        val music = registry.music(source) ?: throw ApiError.badRequest("${source.name} doesn't play songs")
        val stream = try {
            music.stream(sourceId, quality)
        } catch (e: SourceUnavailableException) {
            throw ApiError.upstream(e.message ?: "Not available")
        }
        if (streams.size > 2_000) streams.entries.removeIf { now - it.value.first > STREAM_CACHE_MS }
        streams[key] = now to stream
        return stream
    }

    private companion object {
        const val TAG = "Catalog"
        const val SEARCH_TIMEOUT_MS = 25_000L

        /** Streaming services' addresses last a few hours; this is well inside that. */
        const val STREAM_CACHE_MS = 15L * 60 * 1000
    }
}
