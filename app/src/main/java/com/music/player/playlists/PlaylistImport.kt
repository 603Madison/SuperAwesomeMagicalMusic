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

/** A song listed in someone else's playlist: what's needed to find it here. */
data class ListedSong(val title: String, val artist: String, val durationMs: Long?)

data class ImportResult(val playlistId: String, val name: String, val added: Int, val notFound: List<ListedSong>)

/**
 * Reads Spotify playlists from their public embed page (Spotify's API needs a developer key).
 * The page lists the playlist's name and songs, though for long playlists only the first
 * hundred or so.
 */
class SpotifyPlaylists(private val http: OkHttpClient, private val embedBase: String = "https://open.spotify.com/embed/playlist/") {
    suspend fun read(url: String): Pair<String, List<ListedSong>> {
        val id = playlistId(url) ?: throw IOException("That isn't a Spotify playlist link")
        val html = http.fetchString(Request.Builder().url("$embedBase$id").header("User-Agent", NewPipeDownloader.BROWSER_USER_AGENT).build())
        val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: throw IOException("Spotify didn't send the playlist (is it private?)")
        val entity = AppJson.parseToJsonElement(json).dig("props", "pageProps", "state", "data", "entity") as? JsonObject
            ?: throw IOException("Spotify's playlist page has changed; import isn't possible for now")
        val name = entity.string("name") ?: entity.string("title") ?: "Imported playlist"
        val songs = (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val title = track.string("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = track.string("subtitle")?.replace(' ', ' ')?.trim().orEmpty()
            ListedSong(title, artist, (track["duration"] as? JsonPrimitive)?.longOrNull)
        }
        return name to songs
    }

    companion object {
        private val NEXT_DATA = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        private val ID = Regex("(?:playlist[/:])([A-Za-z0-9]{10,40})")

        fun playlistId(url: String): String? = ID.find(url)?.groupValues?.get(1)

        fun isSpotify(url: String) = url.contains("spotify.com/") || url.startsWith("spotify:")

        private fun JsonElement.dig(vararg path: String): JsonElement? =
            path.fold(this as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

        private fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull
    }
}

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
