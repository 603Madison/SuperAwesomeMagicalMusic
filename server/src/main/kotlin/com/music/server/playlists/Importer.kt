package com.music.server.playlists

import com.music.player.playlists.ImportMatcher
import com.music.player.playlists.ListedSong
import com.music.player.playlists.PlaylistReaders
import com.music.player.playlists.PlaylistService
import com.music.player.sources.youtube.YouTubeMusicSource
import com.music.server.ApiError
import com.music.server.auth.User
import com.music.server.music.ApiTrack
import com.music.server.sources.SourceRegistry
import com.music.server.sources.SourceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.IOException

/** A playlist brought in from elsewhere: its songs as found in the person's sources, and those that weren't. */
data class Imported(val name: String, val origin: String, val tracks: List<ApiTrack>, val missing: List<ListedSong>, val note: String?)

/**
 * Brings in playlists (and albums) from other services, or a typed list of songs. Songs from
 * YouTube links come as they are (they play from the library anyway when it has them); the others
 * are looked for in the person's sources, their library first.
 */
class Importer(
    private val http: OkHttpClient,
    private val store: SourceStore,
    private val registry: SourceRegistry,
    private val readers: PlaylistReaders = PlaylistReaders(http),
) {
    suspend fun fromLink(user: User, link: String): Imported {
        val url = link.trim()
        val service = PlaylistReaders.serviceOf(url)
            ?: throw ApiError.badRequest("Paste a link to a playlist or album on Spotify, Apple Music, Tidal, Deezer, YouTube or ListenBrainz")
        val origin = "Imported from ${service.label}"
        return try {
            when (service) {
                PlaylistService.YOUTUBE -> {
                    val (name, tracks) = YouTubeMusicSource(http).playlist(url)
                    Imported(name.ifBlank { "Imported playlist" }, origin, tracks.map { ApiTrack.of(it, null) }, emptyList(), null)
                }
                PlaylistService.QOBUZ -> throw ApiError.badRequest(PlaylistReaders.QOBUZ_UNREADABLE)
                else -> {
                    val read = readers.read(url)
                    if (read.songs.isEmpty()) throw ApiError.badRequest("That playlist looks empty")
                    val (found, missing) = find(user, read.songs)
                    Imported(read.name, origin, found, missing, read.note)
                }
            }
        } catch (e: IOException) {
            throw ApiError.upstream(e.message ?: "${service.label} couldn't be reached")
        }
    }

    suspend fun fromText(user: User, text: String, name: String): Imported {
        val songs = PlaylistReaders.parseText(text)
        if (songs.isEmpty()) throw ApiError.badRequest("There are no songs in that list")
        val (found, missing) = find(user, songs)
        return Imported(name.trim().ifBlank { "Imported playlist" }, "Imported from a list", found, missing, null)
    }

    /**
     * Each song looked for in the person's sources, a few at a time: in their libraries the way a
     * song is found to play (by title, then among the artist's songs), then on each service.
     */
    private suspend fun find(user: User, songs: List<ListedSong>): Pair<List<ApiTrack>, List<ListedSong>> = coroutineScope {
        val sources = store.activeFor(user).filter { it.kind.isOnDemand }.sortedByDescending { it.kind.isLibrary }
            .mapNotNull { source -> registry.music(source)?.let { source to it } }
        val searches = Semaphore(SEARCHES_AT_ONCE)
        val matched = songs.map { song ->
            async {
                searches.withPermit {
                    song to sources.firstNotNullOfOrNull { (source, music) ->
                        val found = try {
                            ImportMatcher.findIn(song, music)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        found?.let { ApiTrack.of(it, source.id) }
                    }
                }
            }
        }.awaitAll()
        matched.mapNotNull { it.second } to matched.filter { it.second == null }.map { it.first }
    }

    private companion object {
        const val SEARCHES_AT_ONCE = 4
    }
}
