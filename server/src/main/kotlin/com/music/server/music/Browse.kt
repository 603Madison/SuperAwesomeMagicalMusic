package com.music.server.music

import com.music.player.CoreLog
import com.music.player.browse.Album
import com.music.player.browse.AlbumPage
import com.music.player.browse.AlbumRef
import com.music.player.browse.Artist
import com.music.player.browse.ArtistPage
import com.music.player.browse.ArtistRef
import com.music.player.browse.BrowseSource
import com.music.player.browse.Browsing
import com.music.player.browse.Found
import com.music.player.browse.SearchResults
import com.music.player.browse.TopResult
import com.music.player.browse.UnifiedSearch
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.sources.TrackMatcher
import com.music.server.ApiError
import com.music.server.auth.User
import com.music.server.sources.SourceRegistry
import com.music.server.sources.SourceStore
import com.music.server.sources.StoredSource
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
    private val browsing = Browsing(TIMEOUT_MS) { source, what, error -> CoreLog.w(TAG, "${source.via}: $what failed", error) }

    /** The person's sources that hold music to browse, libraries first. */
    private suspend fun sources(user: User): List<Pair<StoredSource, BrowseSource>> =
        store.activeFor(user).filter { it.kind.isOnDemand }.sortedByDescending { it.kind.isLibrary }
            .mapNotNull { source -> registry.music(source)?.let { source to BrowseSource(source.id, source.kind.isLibrary, it) } }

    suspend fun search(user: User, query: String, limit: Int = 25): Everything {
        val sources = sources(user)
        val answers = browsing.search(sources.map { it.second }, query, limit)
        val groups = answers.mapIndexed { i, answer ->
            val source = sources[i].first
            SearchGroup(
                source.id, source.name, source.kind,
                answer.songs.getOrDefault(emptyList()).map { ApiTrack.of(it, source.id) },
                answer.songs.exceptionOrNull()?.let { "${source.name}: ${it.message ?: it.javaClass.simpleName}" },
            )
        }
        return Everything(groups, browsing.merge(query, answers))
    }

    /** An artist's page: from the sources they were found on, and the others by their name. */
    suspend fun artist(user: User, key: BrowseKey): ArtistPage {
        val copies = key.r.map { Found(it.v, false, ArtistRef(it.s, it.i, key.n)) }
        return browsing.artist(sources(user).map { it.second }, key.n, copies) ?: throw ApiError.notFound("None of your sources has ${key.n}")
    }

    /** An album's page: from the sources it was found on, and the others by its title and artist. */
    suspend fun album(user: User, key: BrowseKey): AlbumPage {
        val artist = key.a.orEmpty()
        val copies = key.r.map { Found(it.v, false, AlbumRef(it.s, it.i, key.n, artist)) }
        return browsing.album(sources(user).map { it.second }, key.n, artist, copies) ?: throw ApiError.notFound("None of your sources has ${key.n}")
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
