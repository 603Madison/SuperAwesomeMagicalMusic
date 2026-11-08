package com.music.player.browse

import com.music.player.model.Track
import com.music.player.sources.TrackMatcher

/**
 * Something found on one of several sources. [via] names the source (the server's saved-source id,
 * or the Lite app's source type); [library] is whether that source is a music library (Jellyfin),
 * whose copy is preferred.
 */
data class Found<T>(val via: String, val library: Boolean, val item: T)

/** One source's answer to a search, in that source's own order (best first). */
data class SourceResults(
    val via: String,
    val library: Boolean,
    val songs: List<Track> = emptyList(),
    val artists: List<ArtistRef> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
)

/** An artist, wherever they were found: [copies] holds each source's entry for them. */
data class Artist(val name: String, val artworkUrl: String?, val copies: List<Found<ArtistRef>>)

/** An album, wherever it was found: [copies] holds each source's entry for it. */
data class Album(
    val title: String,
    val artist: String,
    val year: Int?,
    val artworkUrl: String?,
    val trackCount: Int?,
    val copies: List<Found<AlbumRef>>,
)

sealed interface TopResult {
    data class OfArtist(val artist: Artist) : TopResult
    data class OfAlbum(val album: Album) : TopResult
    data class OfSong(val song: Found<Track>) : TopResult
}

data class SearchResults(
    val top: TopResult?,
    val songs: List<Found<Track>>,
    val artists: List<Artist>,
    val albums: List<Album>,
)

data class ArtistPage(
    val name: String,
    val artworkUrl: String?,
    val topSongs: List<Found<Track>>,
    val albums: List<Album>,
    val singles: List<Album>,
    val copies: List<Found<ArtistRef>>,
)

data class AlbumPage(val album: Album, val tracks: List<Found<Track>>)

/**
 * Search the way streaming apps do it: one set of results for all of a person's sources, with
 * the source left out of it. The same song, artist or album found on several sources is shown
 * once (the library's copy when there is one), and the top result is whatever the search most
 * likely meant: an artist, an album or a song.
 */
object UnifiedSearch {
    /** [results] in the order the sources are preferred: libraries first. */
    fun merge(query: String, results: List<SourceResults>, songLimit: Int = 50, artistLimit: Int = 12, albumLimit: Int = 16): SearchResults {
        val q = TrackMatcher.normalize(query)
        val songs = mergeSongs(results.map { r -> r.songs.map { Found(r.via, r.library, it) } })
            .map { group -> group to songScore(q, group.best.item) }
            .sortedWith(compareByDescending<Pair<Group<Track>, Int>> { it.second }.thenBy { it.first.rank }.thenBy { it.first.order })
        val albums = mergeAlbumGroups(results.map { r -> r.albums.map { Found(r.via, r.library, it) } })
            .map { group -> group to albumScore(q, group.best.item) }
            .sortedWith(compareByDescending<Pair<Group<AlbumRef>, Int>> { it.second }.thenBy { it.first.rank }.thenBy { it.first.order })
        // An artist whose songs or albums turned up too is more likely the one meant.
        val credits = songs.map { it.first.best.item.artist } + albums.map { it.first.best.item.artist }
        val artists = mergeArtistGroups(results.map { r -> r.artists.map { Found(r.via, r.library, it) } })
            .map { group -> Triple(group, nameScore(q, group.best.item.name), credits.count { TrackMatcher.isSameArtist(it, group.best.item.name) }) }
            .sortedWith(
                compareByDescending<Triple<Group<ArtistRef>, Int, Int>> { it.second }
                    .thenByDescending { it.third.coerceAtMost(1) }
                    .thenBy { it.first.rank }
                    .thenByDescending { it.first.sources }
                    .thenBy { it.first.order },
            )

        val top = listOfNotNull(
            artists.firstOrNull()?.let { (group, score, support) ->
                Candidate(score * 10 + group.sources.coerceAtMost(3) + (if (support > 0) 2 else 0), 2) { TopResult.OfArtist(group.toArtist()) }
            },
            albums.firstOrNull()?.let { (group, score) ->
                Candidate(score * 10 + group.sources.coerceAtMost(3), 1) { TopResult.OfAlbum(group.toAlbum()) }
            },
            songs.firstOrNull()?.let { (group, score) ->
                Candidate(score * 10 + group.sources.coerceAtMost(3), 0) { TopResult.OfSong(group.best) }
            },
        ).filter { it.strength >= 10 }.maxWithOrNull(compareBy<Candidate> { it.strength }.thenBy { it.priority })?.result?.invoke()

        return SearchResults(
            top = top,
            songs = songs.take(songLimit).map { it.first.best },
            artists = artists.take(artistLimit).map { it.first.toArtist() },
            albums = albums.take(albumLimit).map { it.first.toAlbum() },
        )
    }

    /** One artist's page from each source that has them, made into one. */
    fun artistPage(pages: List<Found<ArtistDetail>>): ArtistPage? {
        if (pages.isEmpty()) return null
        val copies = pages.map { Found(it.via, it.library, it.item.artist) }
        val name = copies.first().item.name
        val songs = mergeSongs(pages.map { p -> p.item.topTracks.map { Found(p.via, p.library, it) } })
            .sortedWith(compareBy<Group<Track>> { it.rank }.thenByDescending { it.sources }.thenBy { it.order })
            .map { it.best }
        val albums = mergeAlbumGroups(pages.map { p -> p.item.albums.map { Found(p.via, p.library, it) } }).map { it.toAlbum() }
            .sortedWith(compareByDescending<Album> { it.year ?: Int.MIN_VALUE })
        val (singles, full) = albums.partition { (it.trackCount ?: Int.MAX_VALUE) <= SINGLE_MAX_TRACKS }
        return ArtistPage(
            name = name,
            artworkUrl = copies.firstNotNullOfOrNull { it.item.artworkUrl },
            topSongs = songs.take(10),
            albums = full,
            singles = singles,
            copies = copies,
        )
    }

    /**
     * One album's page from each source that has it, made into one: the fullest track list
     * (the library's when it's complete), with the library's copy of each song where it has one.
     */
    fun albumPage(pages: List<Found<AlbumDetail>>): AlbumPage? {
        val listed = pages.filter { it.item.tracks.isNotEmpty() }
        if (listed.isEmpty()) return null
        val fullest = listed.withIndex().maxWith(compareBy<IndexedValue<Found<AlbumDetail>>> { it.value.item.tracks.size }.thenBy { -it.index }).value
        val libraryTracks = listed.filter { it.library }.flatMap { p -> p.item.tracks.map { Found(p.via, true, it) } }
        val tracks = fullest.item.tracks.map { track ->
            libraryTracks.firstOrNull { TrackMatcher.matches(it.item, track) } ?: Found(fullest.via, fullest.library, track)
        }
        val copies = pages.map { Found(it.via, it.library, it.item.album) }
        val album = Group(copies, 0, 0).toAlbum().let { if (it.trackCount == null) it.copy(trackCount = tracks.size) else it }
        return AlbumPage(album, tracks)
    }

    /** How well [text] (a name or title) answers [q] (normalized): 3 exactly, 2 all its words, 1 it's in the search. */
    fun relevance(q: String, text: String): Int {
        val t = TrackMatcher.normalize(text)
        if (q.isEmpty() || t.isEmpty()) return 0
        if (t == q || t.removePrefix("the ") == q.removePrefix("the ")) return 3
        val qWords = q.split(' ')
        val tWords = t.split(' ')
        val allThere = qWords.withIndex().all { (i, w) -> w in tWords || (i == qWords.lastIndex && tWords.any { it.startsWith(w) }) }
        if (allThere) return 2
        if (tWords.filter { it != "the" }.let { it.isNotEmpty() && qWords.containsAll(it) }) return 1
        return 0
    }

    private fun nameScore(q: String, name: String) = relevance(q, name)

    private fun titledScore(q: String, title: String, artist: String): Int {
        val plain = TrackMatcher.searchTitle(title)
        val byTitle = maxOf(relevance(q, title), relevance(q, plain))
        // "radiohead" finds Radiohead's songs (2), and "creep radiohead" the song (3).
        val withArtist = maxOf(relevance(q, "$plain ${TrackMatcher.primaryArtist(artist)}"), relevance(q, "${TrackMatcher.primaryArtist(artist)} $plain"))
        return maxOf(byTitle, withArtist)
    }

    private fun songScore(q: String, track: Track) = titledScore(q, track.title, track.artist)

    private fun albumScore(q: String, album: AlbumRef) = titledScore(q, album.title, album.artist)

    private class Candidate(val strength: Int, val priority: Int, val result: () -> TopResult)

    /** Copies of one thing; [rank] is its best place in any source's list, [order] the first source it's from. */
    private class Group<T>(val copies: List<Found<T>>, val rank: Int, val order: Int) {
        /** The library's copy if there is one, else the first source's. */
        val best: Found<T> get() = copies.firstOrNull { it.library } ?: copies.first()
        val sources: Int get() = copies.map { it.via }.distinct().size
    }

    private fun <T> group(lists: List<List<Found<T>>>, key: (T) -> String): List<Group<T>> {
        val groups = LinkedHashMap<String, MutableList<Triple<Found<T>, Int, Int>>>()
        lists.forEachIndexed { order, list ->
            list.forEachIndexed { rank, found -> groups.getOrPut(key(found.item)) { mutableListOf() } += Triple(found, rank, order) }
        }
        return groups.values.map { entries ->
            val copies = entries.sortedWith(compareBy<Triple<Found<T>, Int, Int>> { it.third }.thenBy { it.second })
            Group(copies.map { it.first }, entries.minOf { it.second }, entries.minOf { it.third })
        }
    }

    private fun mergeSongs(lists: List<List<Found<Track>>>) = group(lists) { songKey(it) }

    private fun mergeAlbumGroups(lists: List<List<Found<AlbumRef>>>) = group(lists) { albumKey(it.title, it.artist) }

    private fun mergeArtistGroups(lists: List<List<Found<ArtistRef>>>) = group(lists) { artistKey(it.name) }

    fun songKey(track: Track) =
        "${TrackMatcher.normalizeTitle(track.title)}|${TrackMatcher.variant(track.title).sorted()}|${artistKey(TrackMatcher.primaryArtist(track.artist))}"

    fun albumKey(title: String, artist: String) =
        "${TrackMatcher.normalizeTitle(title)}|${TrackMatcher.variant(title).sorted()}|${artistKey(TrackMatcher.primaryArtist(artist))}"

    fun artistKey(name: String) = TrackMatcher.normalize(TrackMatcher.cleanArtist(name)).removePrefix("the ")

    private fun Group<ArtistRef>.toArtist() = Artist(best.item.name, copies.firstNotNullOfOrNull { it.item.artworkUrl }, copies)

    private fun Group<AlbumRef>.toAlbum(): Album {
        val main = best.item
        return Album(
            title = main.title,
            artist = main.artist,
            year = main.year ?: copies.firstNotNullOfOrNull { it.item.year },
            artworkUrl = main.artworkUrl ?: copies.firstNotNullOfOrNull { it.item.artworkUrl },
            trackCount = main.trackCount ?: copies.firstNotNullOfOrNull { it.item.trackCount },
            copies = copies,
        )
    }

    /** Releases with this few songs are singles. */
    private const val SINGLE_MAX_TRACKS = 3
}
