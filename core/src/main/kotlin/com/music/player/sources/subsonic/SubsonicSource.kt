package com.music.player.sources.subsonic

import com.music.player.AppInfo
import com.music.player.browse.AlbumDetail
import com.music.player.browse.AlbumRef
import com.music.player.browse.ArtistDetail
import com.music.player.browse.ArtistRef
import com.music.player.browse.Browsable
import com.music.player.browse.UnifiedSearch
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.net.AppJson
import com.music.player.net.fetchString
import com.music.player.quality.StreamQuality
import com.music.player.settings.SubsonicAuth
import com.music.player.settings.SubsonicConfig
import com.music.player.sources.LibraryPage
import com.music.player.sources.MusicLibrary
import com.music.player.sources.SourceStream
import com.music.player.sources.SourceUnavailableException
import com.music.player.sources.TrackMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

/** A Subsonic server's answer to a request it couldn't do, said plainly (see [SubsonicSource.messageFor]). */
class SubsonicException(val code: Int, message: String) : Exception(message)

/** What a Subsonic server says about itself. */
data class SubsonicServer(
    /** Its name, from OpenSubsonic servers ("navidrome", "gonic"…). */
    val name: String?,
    /** Its own version, from OpenSubsonic servers. */
    val version: String?,
    /** The Subsonic API version it speaks. */
    val apiVersion: String,
    val openSubsonic: Boolean,
) {
    /** Its name as people know it ("Navidrome"), where it says. */
    val displayName: String? get() = name?.let { KNOWN_NAMES[it.lowercase()] ?: it.replaceFirstChar(Char::uppercase) }

    /** e.g. "Navidrome 0.53.3", or "a Subsonic server (API 1.16.1)". */
    val description: String
        get() = displayName?.let { listOfNotNull(it, version).joinToString(" ") } ?: "a Subsonic server (API $apiVersion)"

    private companion object {
        val KNOWN_NAMES = mapOf(
            "navidrome" to "Navidrome", "gonic" to "Gonic", "airsonic" to "Airsonic", "airsonic-advanced" to "Airsonic-Advanced",
            "ampache" to "Ampache", "lms" to "LMS", "funkwhale" to "Funkwhale", "nextcloud music" to "Nextcloud Music", "supysonic" to "Supysonic",
        )
    }
}

/** A playlist kept on the Subsonic server. */
data class SubsonicPlaylist(val id: String, val name: String, val songCount: Int?, val artworkUrl: String?)

/**
 * A music library on a server that speaks the Subsonic API: Navidrome, Gonic, Airsonic(-Advanced),
 * Ampache, LMS, Nextcloud Music, Funkwhale and others (http://www.subsonic.org/pages/api.jsp, with
 * the OpenSubsonic extensions where they help).
 *
 * Every request proves who's asking in its query: by default with a token (md5 of the password
 * and a new salt each time, so the password itself never travels), the password itself for
 * servers that can't check tokens, or an OpenSubsonic API key. So addresses with those in them
 * (streams) stay inside whoever uses this: the server relays streams, and artwork addresses
 * ([coverUrl]) carry no credentials at all; [authorize] adds them when the image is fetched.
 */
class SubsonicSource(
    private val http: OkHttpClient,
    /** The server and account, as currently configured. */
    private val config: suspend () -> SubsonicConfig,
    /** The client name servers show (the `c` parameter). */
    private val clientName: String = AppInfo.name,
) : MusicLibrary, Browsable {
    override val type = SourceType.SUBSONIC

    /** The server turned tokens down (an LDAP account, say): the password is sent instead from then on. */
    @Volatile
    private var tokensRefused = false

    /** The API version asked for: lowered for an old server that says it only speaks an older one. */
    @Volatile
    private var apiVersion = API_VERSION

    override suspend fun isAvailable(): Boolean = config().isConfigured

    /**
     * Signs in to the server [config] describes, working out how it wants to be asked (a token,
     * the password or an API key): the config to keep, and what the server said about itself.
     */
    suspend fun connect(config: SubsonicConfig): Pair<SubsonicConfig, SubsonicServer> {
        if (config.apiKey.isNotBlank()) {
            val keyed = config.copy(auth = SubsonicAuth.API_KEY)
            return keyed to ping(keyed)
        }
        if (config.username.isBlank() || config.password.isBlank()) throw SubsonicException(10, "A username and password, or an API key, are needed")
        val token = config.copy(auth = SubsonicAuth.TOKEN)
        return try {
            token to ping(token)
        } catch (e: SubsonicException) {
            if (e.code != 41 && e.code != 42) throw e
            // No tokens for this account: the password itself, if the server takes that.
            val password = config.copy(auth = SubsonicAuth.PASSWORD)
            try {
                password to ping(password)
            } catch (again: SubsonicException) {
                if (again.code == 42) throw SubsonicException(42, "This server wants an API key instead of a password: make one in its settings and enter it here")
                throw again
            }
        }
    }

    /** Checks the server answers and takes the details: what it says about itself. */
    suspend fun ping(config: SubsonicConfig? = null): SubsonicServer {
        val response = request("ping", emptyList(), config ?: requireConfig(), probing = config != null)
        return SubsonicServer(
            name = response.str("type"),
            version = response.str("serverVersion"),
            apiVersion = response.str("version") ?: apiVersion,
            openSubsonic = (response["openSubsonic"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    override suspend fun search(query: String, limit: Int): List<Track> {
        val config = requireConfig()
        return search3(config, query.trim(), songs = limit).list("song").filter(::isSong).map { it.toTrack(config) }
    }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistRef> {
        val config = requireConfig()
        return search3(config, query.trim(), artists = limit).list("artist").map { it.toArtist(config) }
    }

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumRef> = coroutineScope {
        val config = requireConfig()
        val byTitle = async { search3(config, query.trim(), albums = limit).list("album").map { it.toAlbum(config) } }
        // An artist's name finds their albums too (some servers only search album titles).
        val byArtist = async {
            runCatching {
                artistsNamed(config, query).take(2).flatMap { artist -> albumsOf(config, artist.id).take(20) }
            }.getOrDefault(emptyList())
        }
        (byTitle.await() + byArtist.await()).distinctBy { it.id }
    }

    override suspend fun album(ref: AlbumRef): AlbumDetail {
        val config = requireConfig()
        val album = request("getAlbum", listOf("id" to ref.id), config).obj("album") ?: throw SourceUnavailableException("That album is no longer in the library")
        return AlbumDetail(album.toAlbum(config), album.list("song").filter(::isSong).map { it.toTrack(config) })
    }

    override suspend fun artist(ref: ArtistRef): ArtistDetail = coroutineScope {
        val config = requireConfig()
        val artist = request("getArtist", listOf("id" to ref.id), config).obj("artist") ?: throw SourceUnavailableException("That artist is no longer in the library")
        val albums = artist.list("album").map { it.toAlbum(config) }.sortedByDescending { it.year ?: 0 }
        val about = artist.toArtist(config).copy(name = ref.name.ifBlank { artist.str("name") ?: ref.name }).let { if (it.artworkUrl == null) it.copy(artworkUrl = ref.artworkUrl) else it }
        // The server's idea of their best-known songs (from Last.fm, where it knows them)…
        val top = runCatching {
            request("getTopSongs", listOf("artist" to about.name, "count" to "10"), config).obj("topSongs")?.list("song").orEmpty()
                .filter(::isSong).map { it.toTrack(config) }
        }.getOrDefault(emptyList())
        // …else the most played songs of their latest albums.
        val songs = top.ifEmpty {
            albums.take(4).map { album -> async { runCatching { songsOfAlbum(config, album.id) }.getOrDefault(emptyList()) } }.awaitAll()
                .flatten().sortedByDescending { it.second }.map { it.first }.distinctBy { TrackMatcher.normalizeTitle(it.title) }.take(10)
        }
        ArtistDetail(about, songs, albums)
    }

    override suspend fun lookUp(track: Track): Track? = coroutineScope {
        val config = requireConfig()
        // By title, filtered by artist…
        val byTitle = search3(config, TrackMatcher.searchTitle(track.title), songs = 50).list("song").filter(::isSong).map { it.toTrack(config) }
        TrackMatcher.bestMatch(track, byTitle)?.let { return@coroutineScope it }
        // …then through the artist's albums, which survives titles spelled differently.
        val artist = TrackMatcher.primaryArtist(track.artist)
        if (artist.isBlank()) return@coroutineScope null
        val ids = search3(config, artist, artists = 10).list("artist")
            .filter { a -> a.str("name")?.let { TrackMatcher.isSameArtist(it, artist) } == true }
            .mapNotNull { it.str("id") }.take(2)
        val albums = ids.flatMap { albumsOf(config, it) }.take(MAX_ALBUMS_TO_LOOK_IN)
        val permits = Semaphore(4)
        val songs = albums.map { album -> async { permits.withPermit { runCatching { songsOfAlbum(config, album.id) }.getOrDefault(emptyList()) } } }
            .awaitAll().flatten().map { it.first }
        TrackMatcher.bestMatch(track, songs)
    }

    /**
     * Every song, a page at a time: through an empty search, which OpenSubsonic servers (and
     * Navidrome, and most others) answer with everything; for servers that don't, album by album.
     * The cursor says which: "s:<offset>" or "a:<album offset>".
     */
    override suspend fun songs(cursor: String?): LibraryPage {
        val config = requireConfig()
        if (cursor == null) {
            val first = runCatching { search3(config, "", songs = SONGS_PAGE).list("song").filter(::isSong) }.getOrDefault(emptyList())
            if (first.isNotEmpty()) return LibraryPage(first.map { it.toTrack(config) }, "s:${first.size}")
            return albumPage(config, 0)
        }
        val offset = cursor.substringAfter(':').toIntOrNull() ?: 0
        if (cursor.startsWith("a:")) return albumPage(config, offset)
        val page = search3(config, "", songs = SONGS_PAGE, songOffset = offset).list("song")
        if (page.isEmpty()) return LibraryPage(emptyList(), null)
        return LibraryPage(page.filter(::isSong).map { it.toTrack(config) }, "s:${offset + page.size}")
    }

    private suspend fun albumPage(config: SubsonicConfig, offset: Int): LibraryPage = coroutineScope {
        val albums = request(
            "getAlbumList2",
            listOf("type" to "alphabeticalByName", "size" to ALBUMS_PAGE.toString(), "offset" to offset.toString()),
            config,
        ).obj("albumList2")?.list("album").orEmpty().mapNotNull { it.str("id") }
        if (albums.isEmpty()) return@coroutineScope LibraryPage(emptyList(), null)
        val permits = Semaphore(4)
        val songs = albums.map { id -> async { permits.withPermit { songsOfAlbum(config, id).map { it.first } } } }.awaitAll().flatten()
        LibraryPage(songs, "a:${offset + albums.size}")
    }

    /** The playlists on the server this account can see. */
    suspend fun playlists(): List<SubsonicPlaylist> {
        val config = requireConfig()
        return request("getPlaylists", emptyList(), config).obj("playlists")?.list("playlist").orEmpty().mapNotNull { it.toPlaylist(config) }
    }

    /** One of [playlists], with its songs in order. */
    suspend fun playlist(id: String): Pair<SubsonicPlaylist, List<Track>> {
        val config = requireConfig()
        val playlist = request("getPlaylist", listOf("id" to id), config).obj("playlist")
        val about = playlist?.toPlaylist(config) ?: throw SourceUnavailableException("That playlist is no longer on the server")
        return about to playlist.list("entry").filter(::isSong).map { it.toTrack(config) }
    }

    /**
     * The song as the server has it (the original file) when that fits [quality], else turned
     * into MP3 at the cap by the server. Originals can be fetched in pieces (seeking); the
     * server's transcodes can't.
     */
    override suspend fun stream(sourceId: String, quality: StreamQuality): SourceStream {
        val config = requireConfig()
        val song = try {
            request("getSong", listOf("id" to sourceId), config).obj("song")
        } catch (e: SubsonicException) {
            if (e.code == 70) null else throw e
        } ?: throw SourceUnavailableException("Song is no longer in the library")
        val suffix = song.str("suffix")?.lowercase()
        val codec = codecOf(suffix, song.str("contentType"))
        val bitRate = song.int("bitRate")?.takeIf { it > 0 }
        val kbps = bitRate ?: if (codec in LOSSLESS) LOSSLESS_KBPS else null
        val gain = (song["replayGain"] as? JsonObject)?.let { (it["trackGain"] as? JsonPrimitive)?.doubleOrNull }?.toFloat()
        val cap = (quality as? StreamQuality.Capped)?.kbps
        val original = cap == null || (kbps != null && kbps <= cap * BITRATE_TOLERANCE && codec in DIRECT_PLAY)
        return if (original) {
            SourceStream(
                url = url(config, "stream", listOf("id" to sourceId, "format" to "raw")).toString(),
                description = "${codec?.uppercase() ?: suffix?.uppercase() ?: "Original"} (original file)",
                fileExtension = extensionFor(codec, suffix),
                kbps = kbps,
                gainDb = gain,
                codec = codec,
            )
        } else {
            SourceStream(
                url = url(config, "stream", listOf("id" to sourceId, "format" to "mp3", "maxBitRate" to cap.toString())).toString(),
                description = "MP3 $cap kbps (transcoded)",
                fileExtension = "mp3",
                supportsRangeRequests = false,
                kbps = cap,
                gainDb = gain,
                codec = "mp3",
            )
        }
    }

    /**
     * [url] (one of [coverUrl]'s, possibly resized) with this account's credentials added, to
     * fetch it; null if it isn't this server's artwork.
     */
    suspend fun authorize(url: String): String? {
        val config = config().takeIf { it.isConfigured } ?: return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        val base = "${config.serverUrl}/rest/getCoverArt".toHttpUrlOrNull() ?: return null
        if (parsed.scheme != base.scheme || parsed.host != base.host || parsed.port != base.port) return null
        if (parsed.encodedPath != base.encodedPath && parsed.encodedPath != base.encodedPath + ".view") return null
        val id = parsed.queryParameter("id") ?: return null
        return url(config, "getCoverArt", listOfNotNull("id" to id, parsed.queryParameter("size")?.let { "size" to it })).toString()
    }

    /** The address of a cover, without credentials (see [authorize]). */
    fun coverUrl(config: SubsonicConfig, id: String, size: Int = COVER_SIZE): String =
        "${config.serverUrl}/rest/getCoverArt.view".toHttpUrl().newBuilder()
            .addQueryParameter("id", id)
            .addQueryParameter("size", size.toString())
            .build().toString()

    private suspend fun search3(config: SubsonicConfig, query: String, songs: Int = 0, albums: Int = 0, artists: Int = 0, songOffset: Int = 0): JsonObject =
        request(
            "search3",
            listOf(
                "query" to query,
                "songCount" to songs.toString(), "songOffset" to songOffset.toString(),
                "albumCount" to albums.toString(), "artistCount" to artists.toString(),
            ),
            config,
        ).obj("searchResult3") ?: JsonObject(emptyMap())

    /** The artists called [name], as the library has them. */
    private suspend fun artistsNamed(config: SubsonicConfig, name: String): List<ArtistRef> {
        val wanted = UnifiedSearch.artistKey(name)
        if (wanted.isBlank()) return emptyList()
        return search3(config, name.trim(), artists = 5).list("artist").map { it.toArtist(config) }.filter { UnifiedSearch.artistKey(it.name) == wanted }
    }

    private suspend fun albumsOf(config: SubsonicConfig, artistId: String): List<AlbumRef> =
        request("getArtist", listOf("id" to artistId), config).obj("artist")?.list("album").orEmpty().map { it.toAlbum(config) }.sortedByDescending { it.year ?: 0 }

    /** An album's songs, each with how often it's been played. */
    private suspend fun songsOfAlbum(config: SubsonicConfig, albumId: String): List<Pair<Track, Int>> =
        request("getAlbum", listOf("id" to albumId), config).obj("album")?.list("song").orEmpty().filter(::isSong)
            .map { it.toTrack(config) to (it.int("playCount") ?: 0) }

    /**
     * Asks the server for [endpoint] and returns its answer (the inside of `subsonic-response`),
     * or throws [SubsonicException] with what went wrong, said plainly. A server that turns
     * tokens down is asked again with the password (unless [probing]: trying exactly the way
     * [config] says), and one that only speaks an older API version is asked again in that.
     */
    private suspend fun request(
        endpoint: String,
        params: List<Pair<String, String>>,
        config: SubsonicConfig,
        probing: Boolean = false,
        retry: Boolean = true,
    ): JsonObject {
        val version = apiVersion
        val auth = authFor(config, probing)
        val body = http.fetchString(Request.Builder().url(url(config, endpoint, params, auth, version)).build())
        val response = runCatching { AppJson.parseToJsonElement(body) }.getOrNull()?.let { (it as? JsonObject)?.get("subsonic-response") as? JsonObject }
            ?: throw SubsonicException(0, "That address doesn't answer like a Subsonic server")
        if (response.str("status") == "ok") return response
        val error = response.obj("error")
        val code = error?.int("code") ?: 0
        if (retry && !probing && code in 41..42 && auth == SubsonicAuth.TOKEN && config.password.isNotEmpty()) {
            tokensRefused = true
            return request(endpoint, params, config, probing, retry = false)
        }
        val theirs = response.str("version")
        if (retry && code == 30 && theirs != null && theirs != version && isOlder(theirs, version)) {
            apiVersion = theirs
            return request(endpoint, params, config, probing, retry = false)
        }
        throw SubsonicException(code, messageFor(code, error?.str("message"), error?.str("helpUrl"), theirs))
    }

    private fun authFor(config: SubsonicConfig, probing: Boolean = false): SubsonicAuth = when {
        config.apiKey.isNotBlank() -> SubsonicAuth.API_KEY
        config.auth == SubsonicAuth.PASSWORD || (tokensRefused && !probing) -> SubsonicAuth.PASSWORD
        else -> SubsonicAuth.TOKEN
    }

    /** The address for [endpoint] with [params], the client's details and the credentials. */
    private fun url(
        config: SubsonicConfig,
        endpoint: String,
        params: List<Pair<String, String>>,
        auth: SubsonicAuth = authFor(config),
        version: String = apiVersion,
    ): HttpUrl = "${config.serverUrl}/rest/$endpoint.view".toHttpUrl().newBuilder().apply {
        for ((key, value) in params) addQueryParameter(key, value)
        addQueryParameter("v", version)
        addQueryParameter("c", clientName)
        addQueryParameter("f", "json")
        when (auth) {
            SubsonicAuth.API_KEY -> addQueryParameter("apiKey", config.apiKey)
            SubsonicAuth.PASSWORD -> {
                addQueryParameter("u", config.username)
                addQueryParameter("p", "enc:" + config.password.toByteArray().toHex())
            }
            SubsonicAuth.TOKEN -> {
                val salt = salt()
                addQueryParameter("u", config.username)
                addQueryParameter("t", token(config.password, salt))
                addQueryParameter("s", salt)
            }
        }
    }.build()

    private suspend fun requireConfig(): SubsonicConfig {
        val config = config()
        if (!config.isConfigured) throw SourceUnavailableException("The Subsonic server is not set up")
        return config
    }

    private fun isSong(item: JsonObject): Boolean {
        if (item.str("id") == null) return false
        if ((item["isDir"] as? JsonPrimitive)?.booleanOrNull == true || (item["isVideo"] as? JsonPrimitive)?.booleanOrNull == true) return false
        val kind = item.str("type")?.lowercase()
        return kind == null || kind == "music" || kind == "audiobook"
    }

    private fun JsonObject.toTrack(config: SubsonicConfig) = Track(
        source = SourceType.SUBSONIC,
        sourceId = str("id")!!,
        title = str("title") ?: str("name") ?: "Unknown title",
        artist = str("displayArtist") ?: str("artist") ?: list("artists").mapNotNull { it.str("name") }.joinToString(", ").ifBlank { "Unknown artist" },
        album = str("album"),
        durationMs = int("duration")?.takeIf { it > 0 }?.let { it * 1000L },
        artworkUrl = str("coverArt")?.let { coverUrl(config, it) },
        isrc = when (val isrc = this["isrc"]) {
            is JsonArray -> (isrc.firstOrNull() as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> isrc.contentOrNull
            else -> null
        }?.takeIf { it.isNotBlank() },
    )

    private fun JsonObject.toAlbum(config: SubsonicConfig) = AlbumRef(
        source = SourceType.SUBSONIC,
        id = str("id").orEmpty(),
        title = str("name") ?: str("title") ?: str("album") ?: "Unknown album",
        artist = str("displayArtist") ?: str("artist") ?: list("artists").mapNotNull { it.str("name") }.joinToString(", ").ifBlank { "Unknown artist" },
        year = int("year")?.takeIf { it > 0 },
        artworkUrl = str("coverArt")?.let { coverUrl(config, it) },
        trackCount = int("songCount"),
    )

    private fun JsonObject.toArtist(config: SubsonicConfig) = ArtistRef(
        source = SourceType.SUBSONIC,
        id = str("id").orEmpty(),
        name = str("name") ?: "Unknown artist",
        artworkUrl = str("coverArt")?.let { coverUrl(config, it) } ?: str("artistImageUrl")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
    )

    private fun JsonObject.toPlaylist(config: SubsonicConfig): SubsonicPlaylist? {
        val id = str("id") ?: return null
        return SubsonicPlaylist(id, str("name") ?: "Playlist", int("songCount"), str("coverArt")?.let { coverUrl(config, it) })
    }

    companion object {
        /** The newest API version (1.16.1, which OpenSubsonic builds on); older servers are asked in theirs. */
        const val API_VERSION = "1.16.1"
        private const val COVER_SIZE = 512
        private const val SONGS_PAGE = 500
        private const val ALBUMS_PAGE = 20
        private const val MAX_ALBUMS_TO_LOOK_IN = 30
        private const val LOSSLESS_KBPS = 1411

        /** Allows for container overhead and embedded artwork inflating the reported bitrate. */
        private const val BITRATE_TOLERANCE = 1.05

        private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "ape", "wavpack", "dsd")

        /** Codecs players handle without converting (anything else is converted by the server). */
        private val DIRECT_PLAY = setOf("mp3", "aac", "flac", "opus", "vorbis")

        private val random = SecureRandom()

        /** What the server's [code] means, for the person (with the server's own words where they add something). */
        fun messageFor(code: Int, theirs: String?, helpUrl: String? = null, theirVersion: String? = null): String {
            val help = helpUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { " (see $it)" }.orEmpty()
            return when (code) {
                10 -> "The server said something was missing from the request" + theirs?.let { ": $it" }.orEmpty()
                20 -> "The server needs a newer app: it speaks Subsonic API ${theirVersion ?: "newer than $API_VERSION"}"
                30 -> "The server is too old: it speaks Subsonic API ${theirVersion ?: "older than this needs"}"
                40 -> "Wrong username or password"
                41 -> "This account can't sign in with a token (an LDAP account?)$help"
                42 -> "The server doesn't accept this way of signing in$help"
                43 -> "The server was sent two kinds of sign-in at once"
                44 -> "The server didn't accept that API key$help"
                50 -> "This account isn't allowed to do that" + theirs?.let { ": $it" }.orEmpty()
                60 -> "The server's trial has ended (it wants a Subsonic Premium licence)"
                70 -> "The server doesn't have that" + theirs?.let { ": $it" }.orEmpty()
                else -> theirs?.takeIf { it.isNotBlank() }?.let { "The server said: $it" } ?: "The server answered with an error (code $code)"
            }
        }

        /** Subsonic's token: md5(password + salt), in hex. */
        fun token(password: String, salt: String): String = MessageDigest.getInstance("MD5").digest((password + salt).toByteArray()).toHex()

        private fun salt(): String = ByteArray(8).also(random::nextBytes).toHex()

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        /** Whether API version [a] (e.g. "1.12.0") is older than [b]. */
        private fun isOlder(a: String, b: String): Boolean {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d < 0
            }
            return false
        }

        /** The codec of a file with [suffix] (and [contentType]), as the server's other code names them. */
        fun codecOf(suffix: String?, contentType: String?): String? = when (suffix ?: contentType?.substringAfter('/')?.lowercase()) {
            "mp3", "mpeg" -> "mp3"
            "flac", "x-flac" -> "flac"
            "ogg", "oga", "vorbis" -> "vorbis"
            "opus" -> "opus"
            "m4a", "mp4", "aac", "m4b" -> "aac"
            "alac" -> "alac"
            "wav", "x-wav" -> "wav"
            "aif", "aiff" -> "aiff"
            "ape" -> "ape"
            "wv" -> "wavpack"
            "dsf", "dff" -> "dsd"
            else -> null
        }

        private fun extensionFor(codec: String?, suffix: String?): String = when (codec) {
            "aac" -> "m4a"
            "vorbis" -> "ogg"
            null -> suffix?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: "audio"
            else -> suffix?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: codec
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

        /** A list in the answer: some servers send a lone item as itself rather than in a list. */
        private fun JsonObject.list(key: String): List<JsonObject> = when (val value: JsonElement? = this[key]) {
            is JsonArray -> value.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(value)
            else -> emptyList()
        }
    }
}
