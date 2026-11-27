package com.music.server.listen

import com.music.player.listen.Fingerprint
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.quality.StreamQuality
import com.music.server.ApiJson
import com.music.server.Services
import com.music.server.auth.User
import com.music.server.db.Database
import com.music.server.music.ApiTrack
import com.music.server.sources.SourceKind
import com.music.server.sources.StoredSource
import com.music.server.util.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** What Listen heard. */
@Serializable
data class ListenResult(
    val found: Boolean,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    /** A copy that plays, from the person's sources (none if it isn't on any). */
    val song: ApiTrack? = null,
    /** Where the song was when the recording ended (the library knows exactly; AudD roughly). */
    val positionMs: Long? = null,
    /** "library" or "audd". */
    val via: String? = null,
    /** AudD's page for the song. */
    val link: String? = null,
    /** Why nothing was found, when there's more to say than that. */
    val message: String? = null,
    /** The kept record of it, in Recently heard. */
    val heardId: String? = null,
    /** Not sure yet: what to send with the next try (a longer recording), to be sure sooner. */
    val candidate: String? = null,
)

/** A song Listen recognised, kept for the person (their own Shazam library). */
@Serializable
data class Heard(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val song: ApiTrack? = null,
    val via: String,
    val link: String? = null,
    val at: Long,
)

/** How much of the person's library Listen knows, and what else it can use. */
@Serializable
data class ListenStatus(
    val learned: Int,
    val total: Int,
    val learning: Boolean,
    /** The server is set to learn libraries (an admin can turn it off). */
    val enabled: Boolean,
    /** The person has a library (a Jellyfin or Subsonic source) for it to learn. */
    val libraries: Boolean,
    /** AudD is set up for them, for what the library doesn't know. */
    val audd: Boolean,
)

/**
 * Listen: what's playing around you, from a few seconds through the microphone. The person's own
 * libraries first: the server learns every song in them (their fingerprints, kept in listen.db)
 * and matches what's heard against those, so nothing leaves the server. Then, for what isn't in
 * them, AudD, if they've added it as a source.
 */
class Listen(private val services: Services, file: File?) {
    val store = ListenStore(if (file != null) Database.file(file.path) else Database.inMemory())
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    var learning = false
        private set

    /** Learns the libraries in the background: soon after starting, then every few hours, or when asked. */
    fun start() {
        services.scope.launch {
            delay(START_DELAY_MS)
            while (isActive) {
                try {
                    learnAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Learning the library for Listen failed: {}", e.message)
                }
                withTimeoutOrNull(EVERY_MS) { wake.receive() }
            }
        }
    }

    /** Learns what's new soon (a library added, or learning turned on). */
    fun learnSoon() {
        wake.trySend(Unit)
    }

    /** Lists every library's songs, forgets those gone, and learns those not yet learned. */
    suspend fun learnAll() {
        if (!services.settings.get().learnLibrary) return
        val libraries = services.sources.all().filter { it.kind.isLibrary && it.enabled }
        store.forgetSourcesExcept(libraries.map { it.id }.toSet())
        for (library in libraries) {
            try {
                list(library)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Couldn't list {} for Listen: {}", library.name, e.message)
            }
        }
        learning = true
        try {
            var learned = 0
            while (services.settings.get().learnLibrary) {
                val item = store.next(services.clock() - RETRY_MS) ?: break
                if (learn(item)) learned++
                delay(PAUSE_MS)
            }
            if (learned > 0) log.info("Listen learned {} songs", learned)
        } finally {
            learning = false
        }
    }

    private suspend fun list(library: StoredSource) {
        val music = services.registry.library(library) ?: return
        val started = services.clock()
        val seen = HashSet<String>()
        var cursor: String? = null
        while (true) {
            val page = music.songs(cursor)
            store.sync(library.id, page.tracks.map { ApiTrack.of(it, library.id) }, started)
            // A server that hands out the same page again (ignoring where to start) has said all it will.
            val fresh = page.tracks.count { seen.add(it.sourceId) }
            if (page.tracks.isNotEmpty() && fresh == 0) break
            cursor = page.next ?: break
        }
        store.forgetUnseen(library.id, started)
    }

    /** Fingerprints one song: its audio from the library, through ffmpeg. */
    private suspend fun learn(item: ListenStore.Item): Boolean {
        val library = services.sources.get(item.sourceId)
        val music = library?.let { services.registry.library(it) }
        if (music == null) {
            store.failed(item.id, services.clock())
            return false
        }
        return try {
            val stream = music.stream(item.songId, StreamQuality.Original)
            val (relay, url) = services.streamer.openRelay(stream)
            val samples = try {
                decode(url, MAX_SONG_SECONDS)
            } finally {
                services.streamer.closeRelay(relay)
            }
            if (samples.size < Fingerprint.RATE * 2) throw IllegalStateException("no audio")
            val hashes = withContext(Dispatchers.Default) { Fingerprint.hashes(samples, Fingerprint.SONG) }
            store.learned(item.id, hashes, services.clock())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("Listen couldn't learn {}: {}", item.track.title, e.message)
            store.failed(item.id, services.clock())
            false
        }
    }

    suspend fun status(user: User): ListenStatus {
        val sources = services.sources.activeFor(user)
        val libraries = sources.filter { it.kind.isLibrary }.map { it.id }
        val (learned, total) = store.counts(libraries)
        return ListenStatus(
            learned = learned,
            total = total,
            learning = learning,
            enabled = services.settings.get().learnLibrary,
            libraries = libraries.isNotEmpty(),
            audd = sources.any { it.kind == SourceKind.AUDD },
        )
    }

    /**
     * What's in [audio] (a recording, [contentType]): from the person's libraries, or (when
     * [final], the last try of a listen, and they have it) AudD.
     */
    suspend fun recognise(user: User, audio: ByteArray, contentType: String?, final: Boolean, candidate: String? = null): ListenResult {
        val samples = decodeUpload(audio)
        val heardMs = samples.size * 1000L / Fingerprint.RATE
        if (heardMs < MIN_CLIP_MS) return ListenResult(found = false, message = "That was too short to tell")
        val sources = services.sources.activeFor(user)
        val clip = withContext(Dispatchers.Default) { Fingerprint.hashes(samples, Fingerprint.CLIP) }
        val (found, leaning) = inLibrary(user, clip, heardMs, candidate)
        if (found != null) return found
        val audd = sources.firstOrNull { it.kind == SourceKind.AUDD }
        if (final && audd != null) {
            val heard = try {
                AudD(services.http).recognise(audd.secret("apiToken"), audio, contentType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("AudD failed: {}", e.message)
                return ListenResult(found = false, message = e.message ?: "AudD couldn't be asked")
            }
            if (heard != null) {
                val copy = playable(user, heard)
                val song = copy?.let { services.artwork.outgoing(if (it.artworkUrl == null) it.copy(artworkUrl = heard.artworkUrl) else it) }
                return remember(
                    user,
                    ListenResult(
                        found = true,
                        title = heard.title,
                        artist = heard.artist,
                        album = heard.album ?: song?.album,
                        artworkUrl = services.artwork.wrap(heard.artworkUrl) ?: song?.artworkUrl,
                        song = song,
                        positionMs = heard.positionMs?.let { it + heardMs },
                        via = "audd",
                        link = heard.link,
                    ),
                )
            }
        }
        // Not sure yet, and more to come: the next, longer try says.
        if (!final && leaning != null) return ListenResult(found = false, candidate = leaning)
        val status = status(user)
        val message = when {
            !status.libraries && !status.audd -> "Listen recognises songs in your library: add your Jellyfin or Subsonic server in Settings › Sources (or AudD, for any song)"
            status.total > 0 && status.learned < status.total -> "Not one of the ${status.learned} songs learned from your library so far (of ${status.total})"
            !final -> null
            leaning != null && !status.audd -> "Not sure: it sounded a little like one of your songs. Try again closer to the music."
            status.audd -> "Not in your library, and AudD didn't recognise it"
            else -> "Not in your library. Add AudD in Settings › Sources to recognise any song."
        }
        return ListenResult(found = false, message = message)
    }

    /**
     * What [clip] (fingerprints of [heardMs] of sound) is in the person's libraries: the song, if
     * sure (kept in their Recently heard), and the one it leans towards if not.
     */
    private suspend fun inLibrary(user: User, clip: LongArray, heardMs: Long, candidate: String?): Pair<ListenResult?, String?> {
        val libraries = services.sources.activeFor(user).filter { it.kind.isLibrary }.map { it.id }
        if (libraries.isEmpty() || clip.isEmpty()) return null to null
        val distinct = clip.map(Fingerprint::hash).distinct().toIntArray()
        val match = withContext(Dispatchers.Default) { Fingerprint.match(clip, store.lookup(distinct, libraries)) } ?: return null to null
        val leaning = match.item.toString().takeIf { match.promising }
        if (!match.confident(candidate?.toLongOrNull())) return null to leaning
        val item = store.item(match.item) ?: return null to leaning
        val song = services.artwork.outgoing(item.track)
        val result = ListenResult(
            found = true,
            title = song.title,
            artist = song.artist,
            album = song.album,
            artworkUrl = song.artworkUrl,
            song = song,
            positionMs = match.offsetMs + heardMs,
            via = "library",
            candidate = match.item.toString(),
        )
        return remember(user, result) to leaning
    }

    /**
     * What a phone heard, from the fingerprints it worked out itself ([hashes], as
     * `Fingerprint.hashes` gives them, of [heardMs] of sound): Now Playing, which listens in the
     * background and sends no sound. Only the person's libraries are asked (AudD needs the sound).
     */
    suspend fun recogniseHashes(user: User, hashes: LongArray, heardMs: Long, candidate: String? = null): ListenResult {
        if (heardMs < MIN_CLIP_MS) return ListenResult(found = false, message = "That was too short to tell")
        val frames = heardMs / Fingerprint.FRAME_MS + 2
        // Fingerprints from beyond what was heard aren't from this recording.
        val clip = hashes.filter { (it and 0xffffffffL) <= frames }.toLongArray()
        val (found, leaning) = inLibrary(user, clip, heardMs, candidate)
        return found ?: ListenResult(found = false, candidate = leaning)
    }

    /** A copy of [heard] that plays: in a library, then on a streaming service. */
    private suspend fun playable(user: User, heard: AudD.Song): ApiTrack? {
        val wanted = Track(
            source = SourceType.SIRIUSXM,
            sourceId = "song:heard",
            title = heard.title,
            artist = heard.artist,
            album = heard.album,
            durationMs = heard.durationMs,
        )
        val sources = services.sources.activeFor(user)
        for (source in sources.filter { it.kind.isLibrary } + sources.filter { it.kind.isOnDemand && !it.kind.isLibrary }) {
            val music = services.registry.music(source) ?: continue
            val copy = try {
                (music as? com.music.player.sources.MusicLibrary)?.lookUp(wanted) ?: music.findMatch(wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (copy != null && copy.source != SourceType.SIRIUSXM) return ApiTrack.of(copy, source.id)
        }
        return null
    }

    /** Keeps what was recognised in the person's Recently heard (once, if it's heard again straight away). */
    private suspend fun remember(user: User, result: ListenResult): ListenResult {
        val now = services.clock()
        val id = services.db.tx {
            val last = one("SELECT id, title, artist, at FROM heard WHERE user_id = ? ORDER BY at DESC LIMIT 1", user.id) {
                Triple(it.string("id"), it.string("title") + "\u0000" + it.string("artist"), it.long("at"))
            }
            if (last != null && last.second == result.title + "\u0000" + result.artist && now - last.third < SAME_LISTEN_MS) {
                update("UPDATE heard SET at = ? WHERE id = ?", now, last.first)
                last.first
            } else {
                val id = Random.id()
                update(
                    "INSERT INTO heard (id, user_id, title, artist, album, artwork, song, via, link, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, user.id, result.title.orEmpty(), result.artist.orEmpty(), result.album,
                    services.artwork.unwrap(result.artworkUrl),
                    result.song?.let { ApiJson.encodeToString(ApiTrack.serializer(), services.artwork.incoming(it)) },
                    result.via.orEmpty(), result.link, now,
                )
                update(
                    "DELETE FROM heard WHERE user_id = ? AND id NOT IN (SELECT id FROM heard WHERE user_id = ? ORDER BY at DESC LIMIT ?)",
                    user.id, user.id, MAX_HEARD,
                )
                id
            }
        }
        return result.copy(heardId = id)
    }

    suspend fun heard(user: User, limit: Int = 100): List<Heard> = services.db.tx {
        query("SELECT * FROM heard WHERE user_id = ? ORDER BY at DESC LIMIT ?", user.id, limit) { row ->
            Heard(
                id = row.string("id"),
                title = row.string("title"),
                artist = row.string("artist"),
                album = row.stringOrNull("album"),
                artworkUrl = row.stringOrNull("artwork"),
                song = row.stringOrNull("song")?.let { runCatching { ApiJson.decodeFromString(ApiTrack.serializer(), it) }.getOrNull() },
                via = row.string("via"),
                link = row.stringOrNull("link"),
                at = row.long("at"),
            )
        }
    }.map { it.copy(artworkUrl = services.artwork.wrap(it.artworkUrl), song = it.song?.let(services.artwork::outgoing)) }

    suspend fun forget(user: User, id: String): Boolean = services.db.tx { update("DELETE FROM heard WHERE user_id = ? AND id = ?", user.id, id) } > 0

    /** A recording people sent, as samples: only audio formats, only the file itself (no playlists that point elsewhere). */
    private suspend fun decodeUpload(audio: ByteArray): FloatArray = withContext(Dispatchers.IO) {
        val file = Files.createTempFile("samm-listen", ".audio").toFile()
        try {
            file.writeBytes(audio)
            decode(file.path, MAX_CLIP_SECONDS, untrusted = true)
        } finally {
            file.delete()
        }
    }

    /** [input] (a file or the server's own relay) as mono samples at [Fingerprint.RATE], at most [maxSeconds]. */
    private suspend fun decode(input: String, maxSeconds: Int, untrusted: Boolean = false): FloatArray = withContext(Dispatchers.IO) {
        val guard = if (untrusted) listOf("-protocol_whitelist", "file", "-format_whitelist", UPLOAD_FORMATS) else emptyList()
        val process = try {
            ProcessBuilder(
                listOf(services.config.ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin") + guard +
                    listOf("-t", maxSeconds.toString(), "-i", input, "-vn", "-ac", "1", "-ar", Fingerprint.RATE.toString(), "-f", "s16le", "pipe:1"),
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: java.io.IOException) {
            // The server's image has it; a server run some other way may not.
            throw com.music.server.ApiError(io.ktor.http.HttpStatusCode.ServiceUnavailable, "Listen needs ffmpeg on the server, and it isn't installed")
        }
        try {
            val bytes = process.inputStream.use { it.readNBytes(maxSeconds * Fingerprint.RATE * 2) }
            process.waitFor(30, TimeUnit.SECONDS)
            FloatArray(bytes.size / 2) { i ->
                ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
            }
        } finally {
            process.destroy()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger("samm.listen")
        private const val START_DELAY_MS = 60_000L
        private const val EVERY_MS = 6 * 60 * 60 * 1000L
        private const val RETRY_MS = 24 * 60 * 60 * 1000L
        private const val PAUSE_MS = 100L
        private const val MAX_SONG_SECONDS = 15 * 60
        private const val MAX_CLIP_SECONDS = 20
        private const val MIN_CLIP_MS = 2_000L
        private const val SAME_LISTEN_MS = 10 * 60 * 1000L
        private const val MAX_HEARD = 500
        private const val UPLOAD_FORMATS = "matroska,webm,ogg,wav,mp3,mov,mp4,m4a,3gp,3g2,mj2,aac,flac"
    }
}
