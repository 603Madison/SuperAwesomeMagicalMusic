package com.music.player.playlists

import com.music.player.browse.AlbumRef
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.sources.MusicSource
import com.music.player.sources.qobuz.QobuzSource
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
 * and Qobuz links bring their songs as they are (they play from Jellyfin first anyway, like
 * every song); Spotify, Apple Music, Tidal, Deezer and ListenBrainz songs, and typed ones, are
 * looked for in the Jellyfin library first, then on each streaming service in turn.
 */
class PlaylistImporter(
    private val readers: PlaylistReaders,
    private val youtube: YouTubeMusicSource,
    private val qobuz: QobuzSource,
    /** Where to look for songs, in order: the library, then the streaming services. */
    private val sources: suspend () -> List<MusicSource>,
    private val playlists: PlaylistRepository,
) {
    suspend fun import(url: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportResult {
        val link = url.trim()
        val service = PlaylistReaders.serviceOf(link)
            ?: throw IOException("Paste a link to a playlist or album on Spotify, Apple Music, Tidal, Deezer, Qobuz, YouTube or ListenBrainz")
        return when (service) {
            PlaylistService.YOUTUBE -> {
                val (name, tracks) = youtube.playlist(link)
                save(name.ifBlank { "Imported playlist" }, tracks, emptyList(), null)
            }
            PlaylistService.QOBUZ -> {
                if (!qobuz.isAvailable()) throw IOException("Qobuz playlists are read with your Qobuz account: sign in to Qobuz in Settings first")
                val id = PlaylistReaders.qobuzPlaylistId(link)
                if (id != null) {
                    val (name, tracks) = qobuz.playlist(id)
                    save(name.ifBlank { "Imported playlist" }, tracks, emptyList(), null)
                } else {
                    val albumId = PlaylistReaders.qobuzAlbumId(link) ?: throw IOException("That Qobuz link isn't a playlist or album")
                    val album = qobuz.album(AlbumRef(SourceType.QOBUZ, albumId, "", ""))
                    save(album.album.title.ifBlank { "Imported album" }, album.tracks, emptyList(), null)
                }
            }
            else -> {
                val read = readers.read(link)
                if (read.songs.isEmpty()) throw IOException("That playlist looks empty")
                val found = match(read.songs, onProgress)
                save(read.name, found.mapNotNull { it.second }, found.filter { it.second == null }.map { it.first }, read.note)
            }
        }
    }

    /** A typed or pasted list: "Artist - Title" lines, or a CSV export with title and artist columns. */
    suspend fun importText(text: String, name: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportResult {
        val songs = PlaylistReaders.parseText(text)
        if (songs.isEmpty()) throw IOException("There are no songs in that list")
        val found = match(songs, onProgress)
        return save(name.trim().ifBlank { "Imported playlist" }, found.mapNotNull { it.second }, found.filter { it.second == null }.map { it.first }, null)
    }

    private suspend fun save(name: String, tracks: List<Track>, missing: List<ListedSong>, note: String?): ImportResult {
        if (tracks.isEmpty()) throw IOException("None of the songs could be found")
        val id = playlists.create(name, tracks)
        return ImportResult(id, name, tracks.size, missing, note)
    }

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
