package com.music.player.playlists

import com.music.player.model.Track
import com.music.player.sources.MusicSource
import com.music.player.sources.youtube.YouTubeMusicSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

data class ImportResult(
    val playlistId: String,
    val name: String,
    val added: Int,
    val notFound: List<ListedSong>,
    /** Why the list may be incomplete (Apple Music and Tidal share only the start of long playlists). */
    val note: String? = null,
)

/**
 * Imports a playlist (or album) from a link, or from a typed or pasted list of songs. YouTube
 * links bring their songs as they are (they play from Jellyfin first anyway, like every song);
 * Spotify, Apple Music, Tidal, Deezer and ListenBrainz songs, and typed ones, are looked for in
 * the Jellyfin library first, then on each streaming service in turn. (Qobuz links can't be read:
 * see [PlaylistReaders].)
 */
class PlaylistImporter(
    private val readers: PlaylistReaders,
    private val youtube: YouTubeMusicSource,
    /** Where to look for songs, in order: the library, then the streaming services. */
    private val sources: suspend () -> List<MusicSource>,
    private val playlists: PlaylistRepository,
) {
    suspend fun import(url: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportResult {
        val link = url.trim()
        val service = PlaylistReaders.serviceOf(link)
            ?: throw IOException("Paste a link to a playlist or album on Spotify, Apple Music, Tidal, Deezer, YouTube or ListenBrainz")
        return when (service) {
            PlaylistService.YOUTUBE -> {
                val (name, tracks) = youtube.playlist(link)
                save(name.ifBlank { "Imported playlist" }, tracks, emptyList(), null)
            }
            else -> {
                val read = readers.read(link)
                if (read.songs.isEmpty()) throw IOException("That playlist looks empty")
                val found = match(read.songs, onProgress)
                save(read.name, found.map { (song, track) -> track ?: standIn(song) }, found.filter { it.second == null }.map { it.first }, read.note)
            }
        }
    }

    /** A typed or pasted list: "Artist - Title" lines, or a CSV export with title and artist columns. */
    suspend fun importText(text: String, name: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportResult {
        val songs = PlaylistReaders.parseText(text)
        if (songs.isEmpty()) throw IOException("There are no songs in that list")
        val found = match(songs, onProgress)
        return save(name.trim().ifBlank { "Imported playlist" }, found.map { (song, track) -> track ?: standIn(song) }, found.filter { it.second == null }.map { it.first }, null)
    }

    /**
     * Looks again for songs that weren't found (in [playlistId], or in every playlist): those found
     * now take their places. Returns how many were found, and how many are still missing.
     */
    suspend fun findMissing(playlistId: String? = null): Pair<Int, Int> {
        val ids = playlistId?.let { listOf(it) } ?: playlists.active().map { it.id }
        var found = 0
        var still = 0
        for (id in ids) {
            val missing = playlists.entries(id).filter { it.isMissing }
            if (missing.isEmpty()) continue
            val matched = match(missing.map { ListedSong(it.title, it.artist, it.durationMs, it.album, it.isrc) }) { _, _ -> }
            val replacements = missing.zip(matched).mapNotNull { (standIn, match) -> match.second?.let { standIn.sourceId to it } }.toMap()
            if (replacements.isNotEmpty()) playlists.replaceMissing(id, replacements)
            found += replacements.size
            still += missing.size - replacements.size
        }
        return found to still
    }

    private suspend fun save(name: String, tracks: List<Track>, missing: List<ListedSong>, note: String?): ImportResult {
        if (tracks.isEmpty()) throw IOException("There are no songs in it")
        // Songs that weren't found go in too, greyed out, and are looked for again now and then.
        val id = playlists.create(name, tracks)
        return ImportResult(id, name, tracks.count { !it.isMissing }, missing, note)
    }

    /** A song that wasn't found, kept in the playlist in its place. */
    private fun standIn(song: ListedSong): Track = Track.missing(song.title.ifBlank { song.line.orEmpty() }, song.artist, song.album, song.durationMs, song.isrc)

    /** Finds each song, a few at a time, in the first source that has it. */
    private suspend fun match(songs: List<ListedSong>, onProgress: (Int, Int) -> Unit): List<Pair<ListedSong, Track?>> = coroutineScope {
        val permits = Semaphore(4)
        val done = AtomicInteger()
        val sources = sources()
        songs.map { song ->
            async {
                permits.withPermit {
                    val match = sources.firstNotNullOfOrNull { source ->
                        try {
                            ImportMatcher.findIn(song, source)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                    onProgress(done.incrementAndGet(), songs.size)
                    song to match
                }
            }
        }.awaitAll()
    }
}
