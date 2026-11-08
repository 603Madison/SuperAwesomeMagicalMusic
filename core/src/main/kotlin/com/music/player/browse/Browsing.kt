package com.music.player.browse

import com.music.player.model.Track
import com.music.player.sources.MusicSource
import com.music.player.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A source to search and browse: [via] names it, [library] says it's a music library. */
class BrowseSource(val via: String, val library: Boolean, val music: MusicSource) {
    val browse: Browsable? get() = music as? Browsable
}

/** One source's answer to a search: its songs (or why there are none), artists and albums. */
class SourceAnswer(val source: BrowseSource, val songs: Result<List<Track>>, val artists: List<ArtistRef>, val albums: List<AlbumRef>) {
    fun results() = SourceResults(source.via, source.library, songs.getOrDefault(emptyList()), artists, albums)
}

/**
 * Searching and browsing several sources as one catalog, for the server and the Lite app alike.
 * Every source is asked at once; one that fails or takes longer than [timeoutMs] is left out
 * (and reported to [onError]) rather than holding up the rest.
 */
class Browsing(
    private val timeoutMs: Long = 25_000,
    private val onError: (source: BrowseSource, what: String, error: Throwable) -> Unit = { _, _, _ -> },
) {
    /**
     * Each source's songs, artists and albums for [query]. [onProgress] hears the answers so far
     * each time another source answers, to show results as they come.
     */
    suspend fun search(
        sources: List<BrowseSource>,
        query: String,
        limit: Int = 25,
        onProgress: (List<SourceAnswer>) -> Unit = {},
    ): List<SourceAnswer> = coroutineScope {
        val done = arrayOfNulls<SourceAnswer>(sources.size)
        val lock = Mutex()
        sources.mapIndexed { i, source ->
            async {
                val browse = source.browse
                val songs = async { ask(source, "search") { source.music.search(query, limit) } }
                val artists = async { browse?.let { b -> ask(source, "artist search") { b.searchArtists(query, 8) }.getOrNull() }.orEmpty() }
                val albums = async { browse?.let { b -> ask(source, "album search") { b.searchAlbums(query, 12) }.getOrNull() }.orEmpty() }
                val answer = SourceAnswer(source, songs.await(), artists.await(), albums.await())
                lock.withLock {
                    done[i] = answer
                    onProgress(done.filterNotNull())
                }
                answer
            }
        }.awaitAll()
    }

    /** The answers as one set of results. */
    fun merge(query: String, answers: List<SourceAnswer>): SearchResults = UnifiedSearch.merge(query, answers.map { it.results() })

    /**
     * An artist's page: from [copies] (where a search found them) and from the other sources by
     * [name]. Null when none of the sources has them.
     */
    suspend fun artist(sources: List<BrowseSource>, name: String, copies: List<Found<ArtistRef>> = emptyList()): ArtistPage? = coroutineScope {
        val wanted = UnifiedSearch.artistKey(name)
        val pages = sources.map { source ->
            async {
                val browse = source.browse ?: return@async null
                val ref = copies.firstOrNull { it.via == source.via }?.item
                    ?: ask(source, "artist search") { browse.searchArtists(name, 5) }.getOrNull()?.firstOrNull { UnifiedSearch.artistKey(it.name) == wanted }
                    ?: return@async null
                ask(source, "artist") { browse.artist(ref) }.getOrNull()?.let { Found(source.via, source.library, it) }
            }
        }.awaitAll().filterNotNull()
        UnifiedSearch.artistPage(pages)
    }

    /**
     * An album's page: from [copies] (where a search found it) and from the other sources by
     * [title] and [artist]. Null when none of the sources has it.
     */
    suspend fun album(sources: List<BrowseSource>, title: String, artist: String, copies: List<Found<AlbumRef>> = emptyList()): AlbumPage? = coroutineScope {
        val wanted = UnifiedSearch.albumKey(title, artist)
        val plainTitle = TrackMatcher.searchTitle(title).ifBlank { title }
        val pages = sources.map { source ->
            async {
                val browse = source.browse ?: return@async null
                // Libraries match album titles only; services do better with the artist too.
                val query = if (source.library) plainTitle else "${TrackMatcher.primaryArtist(artist)} $plainTitle"
                val ref = copies.firstOrNull { it.via == source.via }?.item
                    ?: ask(source, "album search") { browse.searchAlbums(query, 10) }.getOrNull()?.firstOrNull { UnifiedSearch.albumKey(it.title, it.artist) == wanted }
                    ?: return@async null
                ask(source, "album") { browse.album(ref) }.getOrNull()?.let { Found(source.via, source.library, it) }
            }
        }.awaitAll().filterNotNull()
        UnifiedSearch.albumPage(pages)
    }

    private suspend fun <T> ask(source: BrowseSource, what: String, block: suspend () -> T): Result<T> {
        val result = withTimeoutOrNull(timeoutMs) {
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(source, what, e)
                Result.failure(e)
            }
        }
        return result ?: Result.failure<T>(Exception("It took too long to answer")).also { onError(source, what, it.exceptionOrNull()!!) }
    }
}
