package com.music.player.playlists

import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.net.AppJson
import com.music.player.net.fetchString
import com.music.player.sources.MusicSource
import com.music.player.sources.youtube.NewPipeDownloader
import com.music.player.sources.youtube.YouTubeMusicSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

data class ImportResult(val playlistId: String, val name: String, val added: Int, val notFound: List<ListedSong>)

/**
 * Imports a playlist from a link: YouTube / YouTube Music playlists as their songs (which play
 * from Jellyfin first anyway, like every song); Spotify playlists by finding each song in your
 * Jellyfin library first, then on YouTube Music.
 */
class PlaylistImporter(
    private val youtube: YouTubeMusicSource,
    private val jellyfin: MusicSource,
    private val spotify: SpotifyPlaylists,
    private val playlists: PlaylistRepository,
) {
    suspend fun import(url: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportResult {
        val link = url.trim()
        val (name, tracks, missing) = when {
            SpotifyPlaylists.isSpotify(link) -> {
                val (name, songs) = spotify.read(link)
                if (songs.isEmpty()) throw IOException("That playlist looks empty")
                val found = match(songs, onProgress)
                Triple(name, found.mapNotNull { it.second }, found.filter { it.second == null }.map { it.first })
            }
            link.contains("youtube.com/") || link.contains("youtu.be/") -> {
                val (name, tracks) = youtube.playlist(link)
                Triple(name.ifBlank { "Imported playlist" }, tracks, emptyList())
            }
            else -> throw IOException("Paste a YouTube, YouTube Music or Spotify playlist link")
        }
        if (tracks.isEmpty()) throw IOException("None of the songs could be found")
        val id = playlists.create(name, tracks)
        return ImportResult(id, name, tracks.size, missing)
    }

    /** Finds each song, a few at a time: in Jellyfin first, else on YouTube Music. */
    private suspend fun match(songs: List<ListedSong>, onProgress: (Int, Int) -> Unit): List<Pair<ListedSong, Track?>> = coroutineScope {
        val permits = Semaphore(4)
        val done = AtomicInteger()
        val jellyfinReady = runCatching { jellyfin.isAvailable() }.getOrDefault(false)
        songs.map { song ->
            async {
                permits.withPermit {
                    // A heard-song placeholder, as for songs heard on the radio: matched like any song.
                    val wanted = Track(SourceType.SIRIUSXM, "import:${song.artist}:${song.title}", song.title, song.artist, durationMs = song.durationMs)
                    val match = (if (jellyfinReady) runCatching { jellyfin.findMatch(wanted) }.getOrNull() else null)
                        ?: runCatching { youtube.findMatch(wanted) }.getOrNull()
                    onProgress(done.incrementAndGet(), songs.size)
                    song to match
                }
            }
        }.awaitAll()
    }
}
