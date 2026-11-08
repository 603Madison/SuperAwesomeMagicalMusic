package com.music.player.playlists

import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.net.AppJson
import com.music.player.net.HttpException
import com.music.player.net.call
import com.music.player.net.fetchString
import com.music.player.sources.TrackMatcher
import com.music.player.sources.youtube.NewPipeDownloader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A playlist (or album) read from another service; [note] says when it's incomplete. */
data class ReadPlaylist(val name: String, val songs: List<ListedSong>, val note: String? = null)

/** The services a pasted link can come from. */
enum class PlaylistService(val label: String) {
    SPOTIFY("Spotify"),
    APPLE_MUSIC("Apple Music"),
    TIDAL("Tidal"),
    DEEZER("Deezer"),
    LISTENBRAINZ("ListenBrainz"),

    /** Read with the person's own Qobuz account, as playable Qobuz songs. */
    QOBUZ("Qobuz"),

    /** Read as playable YouTube songs. */
    YOUTUBE("YouTube"),
}

/**
 * Reads playlists (and albums) shared from other services, from the links people paste: Spotify,
 * Apple Music, Tidal, Deezer and ListenBrainz, from their public pages and APIs (none needs an
 * account). Their songs are then looked for in the person's own sources ([ImportMatcher]).
 * YouTube and Qobuz links are read by those sources themselves, since their songs play as they are.
 */
class PlaylistReaders(
    private val http: OkHttpClient,
    private val deezerApi: String = "https://api.deezer.com/",
    private val tidalEmbed: String = "https://embed.tidal.com/",
    private val appleMusic: String = "https://music.apple.com/",
    private val listenBrainzApi: String = "https://api.listenbrainz.org/1/",
    spotifyEmbed: String = "https://open.spotify.com/embed/",
) {
    private val spotify = SpotifyPlaylists(http, spotifyEmbed)

    /** Reads a Spotify, Apple Music, Tidal, Deezer or ListenBrainz link. */
    suspend fun read(link: String): ReadPlaylist {
        val url = link.trim()
        return when (serviceOf(url)) {
            PlaylistService.SPOTIFY -> spotify.read(url).let { (name, songs) -> ReadPlaylist(name, songs) }
            PlaylistService.DEEZER -> deezer(url)
            PlaylistService.APPLE_MUSIC -> apple(url)
            PlaylistService.TIDAL -> tidal(url)
            PlaylistService.LISTENBRAINZ -> listenBrainz(url)
            else -> throw IOException("That link can't be read here")
        }
    }

    private fun get(url: String) = Request.Builder().url(url).header("User-Agent", NewPipeDownloader.BROWSER_USER_AGENT).build()

    // Deezer: its public API, a page at a time.
    private suspend fun deezer(link: String): ReadPlaylist {
        val url = if (DEEZER_SHORT.containsMatchIn(link)) http.call(get(link)) { it.request.url.toString() } else link
        val match = DEEZER_ID.find(url) ?: throw IOException("That Deezer link isn't a playlist or album")
        val (kind, id) = match.destructured
        val about = deezerJson("$deezerApi$kind/$id")
        val name = about.string("title") ?: "Imported from Deezer"
        val songs = mutableListOf<ListedSong>()
        while (songs.size < MAX_SONGS) {
            val page = deezerJson("$deezerApi$kind/$id/tracks?index=${songs.size}&limit=$DEEZER_PAGE")
            val data = (page["data"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.deezerSong(if (kind == "album") name else null) }
            songs += data
            if (data.isEmpty() || page["next"] == null) break
        }
        return ReadPlaylist(name, songs)
    }

    private suspend fun deezerJson(url: String): JsonObject {
        val json = AppJson.parseToJsonElement(http.fetchString(get(url))) as? JsonObject ?: throw IOException("Deezer sent something unexpected")
        (json["error"] as? JsonObject)?.let { error ->
            throw IOException(if ((error["code"] as? JsonPrimitive)?.intOrNull == 800) "Deezer can't find that (is it private?)" else "Deezer: ${error.string("message")}")
        }
        return json
    }

    private fun JsonObject.deezerSong(album: String?): ListedSong? {
        val title = string("title")?.takeIf { it.isNotBlank() } ?: return null
        return ListedSong(
            title = title,
            artist = (get("artist") as? JsonObject)?.string("name").orEmpty(),
            durationMs = (get("duration") as? JsonPrimitive)?.longOrNull?.times(1000),
            album = album ?: (get("album") as? JsonObject)?.string("title"),
            isrc = string("isrc"),
        )
    }

    // Apple Music: the playlist or album page carries its songs as data (only the first part of a long playlist).
    private suspend fun apple(link: String): ReadPlaylist {
        val path = APPLE_PATH.find(link)?.groupValues?.get(1) ?: throw IOException("That Apple Music link isn't a playlist or album")
        val html = try {
            http.fetchString(get(appleMusic + path))
        } catch (e: HttpException) {
            throw IOException(if (e.code == 404) "Apple Music can't find that (is it shared publicly?)" else "Apple Music didn't answer (HTTP ${e.code})")
        }
        val json = APPLE_DATA.find(html)?.groupValues?.get(1) ?: throw IOException("Apple Music's page has changed; import isn't possible for now")
        val root = AppJson.parseToJsonElement(json)
        val data = (root as? JsonObject)?.get("data") as? JsonArray ?: root as? JsonArray
        val sections = ((data?.firstOrNull() as? JsonObject)?.dig("data", "sections") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val header = sections.firstOrNull { it.string("id")?.contains("header") == true }?.items()?.firstOrNull()
        val name = header?.string("title") ?: "Imported from Apple Music"
        val isAlbum = "/album/" in path
        val songs = sections.filter { it.string("id")?.startsWith("track-list") == true && it.string("id")?.contains("footer") != true }
            .flatMap { it.items() }
            .mapNotNull { item ->
                val title = item.string("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artist = item.string("artistName") ?: (item["subtitleLinks"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.string("title") }?.joinToString(", ")
                ListedSong(
                    title = title,
                    artist = artist.orEmpty(),
                    durationMs = (item["duration"] as? JsonPrimitive)?.longOrNull,
                    album = if (isAlbum) name else null,
                )
            }
        val total = (header?.get("trackCount") as? JsonPrimitive)?.intOrNull
        val note = if (total != null && total > songs.size) "Apple Music only shares the first ${songs.size} of its $total songs" else null
        return ReadPlaylist(name, songs, note)
    }

    // Tidal: the embeddable player's page lists the first 50 songs.
    private suspend fun tidal(link: String): ReadPlaylist {
        val match = TIDAL_ID.find(link) ?: throw IOException("That Tidal link isn't a playlist or album")
        val (kind, id) = match.destructured
        val html = try {
            http.fetchString(get("${tidalEmbed}${kind}s/$id"))
        } catch (e: HttpException) {
            throw IOException(if (e.code == 404) "Tidal can't find that (is it public?)" else "Tidal didn't answer (HTTP ${e.code})")
        }
        val name = TIDAL_NAME.find(html)?.groupValues?.get(1)?.let(::unescape) ?: "Imported from Tidal"
        val songs = TIDAL_ITEM.findAll(html).mapNotNull { item ->
            val body = item.groupValues[1]
            val title = TIDAL_TITLE.find(body)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artists = TIDAL_ARTIST.find(body)?.groupValues?.get(1)?.let { slot ->
                TAG_TEXT.findAll(slot).map { unescape(it.groupValues[1]).trim() }.filter { it.isNotEmpty() }.toList()
                    .ifEmpty { listOf(unescape(slot.replace(Regex("<[^>]+>"), "")).trim()) }
            }.orEmpty()
            val duration = TIDAL_DURATION.find(body)?.groupValues?.get(1)?.let(::clockMs)
            ListedSong(title, artists.joinToString(", "), duration, album = if (kind == "album") name else null)
        }.toList()
        if (songs.isEmpty()) throw IOException("Tidal didn't list any songs (is it public?)")
        val note = if (songs.size >= TIDAL_MAX) "Tidal only shares the first $TIDAL_MAX songs of a playlist" else null
        return ReadPlaylist(name, songs, note)
    }

    // ListenBrainz: its API gives the playlist as JSPF.
    private suspend fun listenBrainz(link: String): ReadPlaylist {
        val id = LISTENBRAINZ_ID.find(link)?.groupValues?.get(1) ?: throw IOException("That ListenBrainz link isn't a playlist")
        val json = try {
            http.fetchString(get("${listenBrainzApi}playlist/$id"))
        } catch (e: HttpException) {
            throw IOException(if (e.code == 404) "ListenBrainz can't find that playlist (is it public?)" else "ListenBrainz didn't answer (HTTP ${e.code})")
        }
        val playlist = (AppJson.parseToJsonElement(json) as? JsonObject)?.get("playlist") as? JsonObject
            ?: throw IOException("ListenBrainz sent something unexpected")
        val songs = (playlist["track"] as? JsonArray).orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val title = track.string("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ListedSong(title, track.string("creator").orEmpty(), (track["duration"] as? JsonPrimitive)?.longOrNull, track.string("album"))
        }
        return ReadPlaylist(playlist.string("title") ?: "Imported from ListenBrainz", songs)
    }

    companion object {
        const val MAX_SONGS = 2_000
        private const val DEEZER_PAGE = 100
        private const val TIDAL_MAX = 50

        private val DEEZER_SHORT = Regex("(?:link\\.deezer\\.com|deezer\\.page\\.link)/")
        private val DEEZER_ID = Regex("deezer\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(playlist|album)/(\\d+)")
        private val APPLE_PATH = Regex("music\\.apple\\.com/((?:[a-z]{2}/)?(?:playlist|album)/[^?#\\s]+)")
        private val APPLE_DATA = Regex("<script[^>]*id=\"serialized-server-data\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        private val TIDAL_ID = Regex("tidal\\.com/(?:browse/)?(playlist|album)/([0-9A-Za-z-]+)")
        private val TIDAL_NAME = Regex("<h1[^>]*class=\"media-album\"[^>]*>(?:\\s*<a[^>]*>)?([^<]+)")
        private val TIDAL_ITEM = Regex("<list-item[^>]*>(.*?)</list-item>", RegexOption.DOT_MATCHES_ALL)
        private val TIDAL_TITLE = Regex("<span slot=\"title\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
        private val TIDAL_ARTIST = Regex("<span slot=\"artist\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
        private val TIDAL_DURATION = Regex("<time slot=\"duration\">([0-9:]+)</time>")
        private val TAG_TEXT = Regex("<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        private val LISTENBRAINZ_ID = Regex("listenbrainz\\.org/(?:1/)?playlist/([0-9a-fA-F-]{36})")
        private val QOBUZ_PLAYLIST = Regex("qobuz\\.com/(?:[a-z]{2}-[a-z]{2}/)?playlists?/(?:[^/?#]+/)?(\\d+)")
        private val QOBUZ_ALBUM = Regex("qobuz\\.com/(?:[a-z]{2}-[a-z]{2}/)?album/(?:[^/?#]+/)?([0-9A-Za-z]+)(?:[/?#]|$)")

        /** Which service [link] is from, if it's one whose playlists can be read. */
        fun serviceOf(link: String): PlaylistService? {
            val url = link.trim()
            return when {
                SpotifyPlaylists.isSpotify(url) -> PlaylistService.SPOTIFY
                "music.apple.com/" in url -> PlaylistService.APPLE_MUSIC
                "tidal.com/" in url -> PlaylistService.TIDAL
                "deezer.com/" in url || DEEZER_SHORT.containsMatchIn(url) -> PlaylistService.DEEZER
                "listenbrainz.org/" in url -> PlaylistService.LISTENBRAINZ
                "qobuz.com/" in url -> PlaylistService.QOBUZ
                "youtube.com/" in url || "youtu.be/" in url -> PlaylistService.YOUTUBE
                else -> null
            }
        }

        fun qobuzPlaylistId(link: String): String? = QOBUZ_PLAYLIST.find(link)?.groupValues?.get(1)

        fun qobuzAlbumId(link: String): String? = QOBUZ_ALBUM.find(link)?.groupValues?.get(1)

        /**
         * Songs typed or pasted as text: a CSV (or tab-separated) export with title and artist
         * columns, as Exportify, TuneMyMusic and Soundiiz make; or one song a line, as
         * "Artist - Title" (or "Title by Artist", or just a title).
         */
        fun parseText(text: String): List<ListedSong> {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return emptyList()
            table(lines)?.let { return it }
            return lines.mapNotNull(::parseLine).take(MAX_SONGS)
        }

        private val NUMBERING = Regex("^(\\d{1,4}[.)]\\s*|[-*•]\\s+)")
        private val NUMBER_DASH = Regex("^\\d{1,4}\\s+[-–—]\\s+")
        private val DASH = Regex("\\s+[-–—]\\s+")
        private val BY = Regex("^(.+?)\\s+by\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val TRAILING_TIME = Regex("\\s*[(\\[]?(\\d{1,2}:\\d{2})[)\\]]?$")

        private fun parseLine(raw: String): ListedSong? {
            var line = raw.replace(NUMBERING, "").trim()
            // "3 - Artist - Title" is numbered; "311 - Amber" isn't.
            NUMBER_DASH.find(line)?.let { number -> if (DASH.containsMatchIn(line.substring(number.range.last + 1))) line = line.substring(number.range.last + 1) }
            val duration = TRAILING_TIME.find(line)?.let { match -> line = line.removeRange(match.range).trim(); clockMs(match.groupValues[1]) }
            if (line.isEmpty()) return null
            DASH.split(line, 2).takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { (artist, title) ->
                return ListedSong(title.trim(), artist.trim(), duration, line = line)
            }
            BY.find(line)?.let { match -> return ListedSong(match.groupValues[1].trim(), match.groupValues[2].trim(), duration, line = line) }
            return ListedSong(line, "", duration, line = line)
        }

        private fun table(lines: List<String>): List<ListedSong>? {
            val separator = when {
                '\t' in lines[0] -> '\t'
                ',' in lines[0] -> ','
                ';' in lines[0] -> ';'
                else -> return null
            }
            val header = splitRow(lines[0], separator).map { it.trim().lowercase() }
            fun column(vararg names: String) = header.indexOfFirst { it in names }
            val title = column("track name", "title", "track", "song", "song name", "name", "track title")
            val artist = column("artist name(s)", "artist name", "artist", "artists", "artist(s)", "creator", "performer")
            if (title < 0 || artist < 0) return null
            val album = column("album name", "album", "album title", "release")
            val isrc = column("isrc")
            val durationMs = header.indexOfFirst { it.contains("duration") && it.contains("ms") }
            val duration = header.indexOfFirst { it.contains("duration") || it == "length" || it == "time" }
            return lines.drop(1).mapNotNull { line ->
                val cells = splitRow(line, separator)
                fun cell(i: Int) = cells.getOrNull(i)?.trim()?.takeIf { i >= 0 && it.isNotEmpty() }
                val songTitle = cell(title) ?: return@mapNotNull null
                // Exportify separates several artists with commas inside the cell.
                val artists = cell(artist).orEmpty()
                val ms = cell(durationMs)?.toLongOrNull()
                    ?: cell(duration)?.let { if (':' in it) clockMs(it) else it.toDoubleOrNull()?.let { s -> if (s > 10_000) s.toLong() else (s * 1000).toLong() } }
                ListedSong(songTitle, artists, ms, cell(album), cell(isrc))
            }.take(MAX_SONGS)
        }

        /** One CSV row: quoted cells may hold separators and doubled quotes. */
        private fun splitRow(line: String, separator: Char): List<String> {
            val cells = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && line.getOrNull(i + 1) == '"' -> {
                        cell.append('"')
                        i++
                    }
                    quoted && c == '"' -> quoted = false
                    !quoted && c == '"' && cell.isBlank() -> {
                        quoted = true
                        cell.setLength(0)
                    }
                    !quoted && c == separator -> {
                        cells += cell.toString()
                        cell.setLength(0)
                    }
                    else -> cell.append(c)
                }
                i++
            }
            cells += cell.toString()
            return cells
        }

        private fun clockMs(clock: String): Long? {
            val parts = clock.split(':').map { it.toLongOrNull() ?: return null }
            return parts.fold(0L) { total, part -> total * 60 + part } * 1000
        }

        private fun unescape(text: String): String = text
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: it.value }
            .trim()

        private fun JsonElement.dig(vararg path: String): JsonElement? =
            path.fold(this as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

        private fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.items(): List<JsonObject> = (get("items") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }
}

/** Finds the songs of an imported playlist among the songs a person's sources offer. */
object ImportMatcher {
    /** [song] as a track to look for. */
    fun wanted(song: ListedSong) = Track(SourceType.YOUTUBE_MUSIC, "", song.title, song.artist, song.album, song.durationMs, isrc = song.isrc)

    /** What to search for to find [song]: a typed line as it was typed. */
    fun query(song: ListedSong): String = when {
        song.line != null -> song.line.replace(Regex("\\s+[-–—]\\s+"), " ")
        song.artist.isBlank() -> TrackMatcher.searchTitle(song.title)
        else -> TrackMatcher.searchQuery(wanted(song))
    }

    /**
     * The first of [candidates] (best first: the library's first) that is [song]: the same
     * recording by ISRC, or by title, artist and length. A typed line may have the artist and
     * title the other way round, or be just a title (whose first song is taken).
     */
    fun pick(song: ListedSong, candidates: List<Track>): Track? {
        val wanted = wanted(song)
        val swapped = if (song.line != null && song.artist.isNotBlank()) wanted.copy(title = song.artist, artist = song.title) else null
        val wholeLine = song.line?.let { TrackMatcher.normalizeTitle(it) }
        return candidates.firstOrNull { candidate ->
            when {
                song.isrc != null && candidate.isrc.equals(song.isrc, ignoreCase = true) -> true
                song.artist.isNotBlank() && TrackMatcher.matches(candidate, wanted) -> true
                swapped != null && TrackMatcher.matches(candidate, swapped) -> true
                else -> wholeLine != null && TrackMatcher.normalizeTitle(candidate.title) == wholeLine && TrackMatcher.sameVariant(candidate.title, song.line!!)
            }
        }
    }
}
