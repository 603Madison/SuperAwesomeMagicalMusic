package com.music.server.music

import com.music.player.CoreLog
import com.music.player.browse.Album
import com.music.player.browse.AlbumPage
import com.music.player.browse.AlbumRef
import com.music.player.browse.Artist
import com.music.player.browse.ArtistPage
import com.music.player.browse.ArtistRef
import com.music.player.browse.Browsable
import com.music.player.browse.Found
import com.music.player.browse.SearchResults
import com.music.player.browse.SourceResults
import com.music.player.browse.TopResult
import com.music.player.browse.UnifiedSearch
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.sources.MusicSource
import com.music.player.sources.TrackMatcher
import com.music.server.ApiError
import com.music.server.auth.User
import com.music.server.sources.SourceRegistry
import com.music.server.sources.SourceStore
import com.music.server.sources.StoredSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/** An artist as clients see it; [id] opens their page. */
@Serializable
data class ArtistSummary(val id: String, val name: String, val artworkUrl: String? = null)

/** An album as clients see it; [id] opens it and [artistId] its artist's page. */
@Serializable
data class AlbumSummary(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String,
    val year: Int? = null,
    val artworkUrl: String? = null,
    val trackCount: Int? = null,
)

/** The search's best guess at what was meant: one of [artist], [album] or [track], as [kind] says. */
@Serializable
data class TopView(val kind: String, val artist: ArtistSummary? = null, val album: AlbumSummary? = null, val track: ApiTrack? = null)

@Serializable
data class ArtistPageView(val artist: ArtistSummary, val topSongs: List<ApiTrack>, val albums: List<AlbumSummary>, val singles: List<AlbumSummary>)

@Serializable
data class AlbumPageView(val album: AlbumSummary, val tracks: List<ApiTrack>)

/** What an artist's or album's id holds: its name, and its entries in the sources it was found on. */
@Serializable
data class BrowseKey(
    /** The artist's name, or the album's title. */
    val n: String,
    /** The album's artist. */
    val a: String? = null,
    val r: List<BrowseRef> = emptyList(),
) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(KeyJson.encodeToString(serializer(), this).toByteArray())

    companion object {
        private val KeyJson = Json { ignoreUnknownKeys = true }

        fun decode(id: String): BrowseKey = try {
            KeyJson.decodeFromString(serializer(), String(Base64.getUrlDecoder().decode(id)))
        } catch (e: IllegalArgumentException) {
            throw ApiError.badRequest("That link is broken")
        }

        fun artist(name: String, copies: List<Found<ArtistRef>> = emptyList()) =
            BrowseKey(name, r = copies.map { BrowseRef(it.via, it.item.source, it.item.id) })

        fun album(title: String, artist: String, copies: List<Found<AlbumRef>> = emptyList()) =
            BrowseKey(title, artist, copies.map { BrowseRef(it.via, it.item.source, it.item.id) })
    }
}

@Serializable
data class BrowseRef(val v: String, val s: SourceType, val i: String)

/** A search for songs, artists and albums at once, and their results. */
data class Everything(val groups: List<SearchGroup>, val results: SearchResults)

/**
 * Searching and browsing all of a person's sources as one catalog: songs, artists and albums,
 * each shown once, whichever sources have them (see [UnifiedSearch]). Artist and album pages
 * gather what every source has: the library's copy of an album found on Qobuz, the albums of an
 * artist found on YouTube.
 */
class Browser(private val store: SourceStore, private val registry: SourceRegistry) {
    /** The person's sources that hold music to browse, libraries first. */
    private suspend fun browsable(user: User): List<Pair<StoredSource, MusicSource>> =
        store.activeFor(user).filter { it.kind.isOnDemand }.sortedByDescending { it.kind.isLibrary }
            .mapNotNull { source -> registry.music(source)?.let { source to it } }

    suspend fun search(user: User, query: String, limit: Int = 25): Everything = coroutineScope {
        val answers = browsable(user).map { (source, music) ->
            async {
                val browse = music as? Browsable
                val songs = async { ask(source, "search") { music.search(query, limit) } }
                val artists = async { browse?.let { ask(source, "artist search") { it.searchArtists(query, 8) } } }
                val albums = async { browse?.let { ask(source, "album search") { it.searchAlbums(query, 12) } } }
                Answer(source, songs.await(), artists.await(), albums.await())
            }
        }.awaitAll()
        val groups = answers.map { answer ->
            SearchGroup(
                answer.source.id, answer.source.name, answer.source.kind,
                answer.songs.getOrDefault(emptyList()).map { ApiTrack.of(it, answer.source.id) },
                answer.songs.exceptionOrNull()?.let { "${answer.source.name}: ${it.message ?: it.javaClass.simpleName}" },
            )
        }
        val results = UnifiedSearch.merge(
            query,
            answers.map { answer ->
                SourceResults(
                    via = answer.source.id,
                    library = answer.source.kind.isLibrary,
                    songs = answer.songs.getOrDefault(emptyList()),
                    artists = answer.artists?.getOrNull().orEmpty(),
                    albums = answer.albums?.getOrNull().orEmpty(),
                )
            },
        )
        Everything(groups, results)
    }

    /** An artist's page: from the sources they were found on, and the others by their name. */
    suspend fun artist(user: User, key: BrowseKey): ArtistPage = coroutineScope {
        val pages = browsable(user).map { (source, music) ->
            async {
                val browse = music as? Browsable ?: return@async null
                val ref = key.r.firstOrNull { it.v == source.id }?.let { ArtistRef(it.s, it.i, key.n) }
                    ?: ask(source, "artist search") { browse.searchArtists(key.n, 5) }.getOrNull()
                        ?.firstOrNull { UnifiedSearch.artistKey(it.name) == UnifiedSearch.artistKey(key.n) }
                    ?: return@async null
                ask(source, "artist") { browse.artist(ref) }.getOrNull()?.let { Found(source.id, source.kind.isLibrary, it) }
            }
        }.awaitAll().filterNotNull()
        UnifiedSearch.artistPage(pages) ?: throw ApiError.notFound("None of your sources has ${key.n}")
    }

    /** An album's page: from the sources it was found on, and the others by its title and artist. */
    suspend fun album(user: User, key: BrowseKey): AlbumPage = coroutineScope {
        val artist = key.a.orEmpty()
        val wanted = UnifiedSearch.albumKey(key.n, artist)
        val pages = browsable(user).map { (source, music) ->
            async {
                val browse = music as? Browsable ?: return@async null
                val ref = key.r.firstOrNull { it.v == source.id }?.let { AlbumRef(it.s, it.i, key.n, artist) }
                    ?: ask(source, "album search") { browse.searchAlbums("${TrackMatcher.primaryArtist(artist)} ${TrackMatcher.searchTitle(key.n)}", 10) }.getOrNull()
                        ?.firstOrNull { UnifiedSearch.albumKey(it.title, it.artist) == wanted }
                    ?: return@async null
                ask(source, "album") { browse.album(ref) }.getOrNull()?.let { Found(source.id, source.kind.isLibrary, it) }
            }
        }.awaitAll().filterNotNull()
        UnifiedSearch.albumPage(pages) ?: throw ApiError.notFound("None of your sources has ${key.n}")
    }

    private class Answer(
        val source: StoredSource,
        val songs: Result<List<Track>>,
        val artists: Result<List<ArtistRef>>?,
        val albums: Result<List<AlbumRef>>?,
    )

    /** A source's answer, or why there isn't one; a source that takes too long is left out. */
    private suspend fun <T> ask(source: StoredSource, what: String, block: suspend () -> T): Result<T> {
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CoreLog.w(TAG, "${source.name}: $what failed", e)
                Result.failure(e)
            }
        }
        return result ?: Result.failure(Exception("${source.name} took too long to answer"))
    }

    private companion object {
        const val TAG = "Browser"
        const val TIMEOUT_MS = 25_000L
    }
}

/** Clients' views of what [Browser] finds, with artwork through the server. */
class BrowseViews(private val artwork: Artwork) {
    fun track(found: Found<Track>) = artwork.outgoing(ApiTrack.of(found.item, found.via))

    fun artist(artist: Artist) = ArtistSummary(BrowseKey.artist(artist.name, artist.copies).encode(), artist.name, artwork.wrap(artist.artworkUrl))

    fun album(album: Album) = AlbumSummary(
        id = BrowseKey.album(album.title, album.artist, album.copies).encode(),
        title = album.title,
        artist = album.artist,
        artistId = BrowseKey.artist(TrackMatcher.primaryArtist(album.artist)).encode(),
        year = album.year,
        artworkUrl = artwork.wrap(album.artworkUrl),
        trackCount = album.trackCount,
    )

    fun top(top: TopResult?): TopView? = when (top) {
        is TopResult.OfArtist -> TopView("artist", artist = artist(top.artist))
        is TopResult.OfAlbum -> TopView("album", album = album(top.album))
        is TopResult.OfSong -> TopView("song", track = track(top.song))
        null -> null
    }

    fun artistPage(page: ArtistPage) = ArtistPageView(
        artist = ArtistSummary(BrowseKey.artist(page.name, page.copies).encode(), page.name, artwork.wrap(page.artworkUrl)),
        topSongs = page.topSongs.map(::track),
        albums = page.albums.map(::album),
        singles = page.singles.map(::album),
    )

    fun albumPage(page: AlbumPage) = AlbumPageView(album(page.album), page.tracks.map(::track))
}
