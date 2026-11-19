package com.music.server.radio

import com.music.player.CoreLog
import com.music.player.quality.StreamQuality
import com.music.server.ApiError
import com.music.server.Services
import com.music.server.auth.User
import com.music.server.db.Database
import com.music.server.music.ApiTrack
import com.music.server.music.BrowseKey
import com.music.server.playlists.PlaylistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What each person's stations played (to keep songs apart) and the thumbs given on them. */
class RadioStore(private val db: Database, private val clock: () -> Long) {
    suspend fun played(userId: String, station: String, song: Candidate) = db.tx {
        update(
            "INSERT INTO station_plays (user_id, station, song_key, artist_key, track, played_at) VALUES (?, ?, ?, ?, ?, ?)",
            userId, station, song.key, song.artistKey, PlaylistStore.TrackJson.encodeToString(ApiTrack.serializer(), song.track), clock(),
        )
        update(
            "DELETE FROM station_plays WHERE user_id = ? AND played_at < (SELECT played_at FROM station_plays WHERE user_id = ? ORDER BY played_at DESC LIMIT 1 OFFSET ?)",
            userId, userId, KEEP_PLAYS - 1,
        )
    }

    /** What the person's stations played lately, oldest first. */
    suspend fun recent(userId: String): List<Played> = db.tx {
        query(
            "SELECT station, song_key, artist_key, played_at FROM station_plays WHERE user_id = ? ORDER BY played_at DESC LIMIT ?",
            userId, KEEP_PLAYS,
        ) { Played(it.string("station"), it.string("song_key"), it.string("artist_key"), it.long("played_at")) }.asReversed()
    }

    suspend fun thumbs(userId: String): Map<String, Pair<Int, ApiTrack>> = db.tx {
        query("SELECT song_key, track, thumb FROM station_feedback WHERE user_id = ?", userId) {
            it.string("song_key") to (it.long("thumb").toInt() to PlaylistStore.TrackJson.decodeFromString(ApiTrack.serializer(), it.string("track")))
        }.toMap()
    }

    suspend fun setThumb(userId: String, key: String, track: ApiTrack, thumb: Int) = db.tx {
        if (thumb == 0) {
            update("DELETE FROM station_feedback WHERE user_id = ? AND song_key = ?", userId, key)
        } else {
            update(
                "INSERT OR REPLACE INTO station_feedback (user_id, song_key, track, thumb, at) VALUES (?, ?, ?, ?, ?)",
                userId, key, PlaylistStore.TrackJson.encodeToString(ApiTrack.serializer(), track), thumb, clock(),
            )
        }
    }

    private companion object {
        const val KEEP_PLAYS = 400
    }
}

/**
 * Everyone's own radio stations: [StationKind]s made from what they play and like, each on the
 * air (a [Transmitter]) while anyone listens, and off a little after the last listener leaves.
 */
class PersonalRadio(private val services: Services) {
    private val store = RadioStore(services.db, services.clock)
    private val transmitters = ConcurrentHashMap<String, Transmitter>()
    private val tastes = ConcurrentHashMap<String, Pair<Long, Taste>>()
    private val tasteRefreshing = ConcurrentHashMap.newKeySet<String>()
    private val discoveries = ConcurrentHashMap<String, Pair<Long, List<ApiTrack>>>()
    private val discovering = ConcurrentHashMap.newKeySet<String>()
    private val starting = Semaphore(1)

    init {
        // Stations nobody listens to go off the air.
        services.scope.launch {
            while (isActive) {
                delay(15_000)
                val now = services.clock()
                transmitters.entries.removeIf { (_, t) ->
                    val idle = t.idleSince?.let { now - it > IDLE_OFF_MS } == true
                    if (idle || !t.isRunning) t.stop()
                    idle || !t.isRunning
                }
            }
        }
    }

    private fun keyOf(userId: String, kind: StationKind) = "$userId|${kind.id}"

    /** The station on the air for [user], if it is. */
    fun onAir(user: User, kind: StationKind): Transmitter? = transmitters[keyOf(user.id, kind)]?.takeIf { it.isRunning }

    /** Tunes in to [kind] for [user]: on the air already, or started. */
    suspend fun tuneIn(user: User, kind: StationKind, zone: ZoneId?): Transmitter = starting.withPermit {
        val key = keyOf(user.id, kind)
        transmitters[key]?.takeIf { it.isRunning }?.let { running ->
            zone?.let { running.zone = it }
            return@withPermit running
        }
        if (transmitters.values.count { it.isRunning } >= MAX_ON_AIR) {
            throw ApiError(io.ktor.http.HttpStatusCode.ServiceUnavailable, "Too many stations are on the air right now; try again in a minute")
        }
        if (taste(user).isEmpty) throw ApiError.notFound("Listen to some music first: your stations are made from what you play")
        val transmitter = Transmitter(user.id, kind, Stations.nameOf(kind, user.displayName, user.username), programme(user, kind), services.clock)
        zone?.let { transmitter.zone = it }
        transmitters[key] = transmitter
        transmitter.start()
        transmitter
    }

    fun skip(user: User, kind: StationKind) {
        onAir(user, kind)?.skip() ?: throw ApiError.notFound("That station isn't on")
    }

    /** A thumb on what's on [kind] now ([key] says which song, in case it just changed). Down skips it. */
    suspend fun thumb(user: User, kind: StationKind, key: String, thumb: Int) {
        val transmitter = onAir(user, kind) ?: throw ApiError.notFound("That station isn't on")
        val song = listOfNotNull(transmitter.current?.song).plus(transmitter.recent.map { it.song }).firstOrNull { it.key == key }
            ?: throw ApiError.notFound("That song isn't on now")
        store.setThumb(user.id, key, song.track, thumb)
        tastes.remove(user.id)
        if (thumb < 0) {
            transmitters.values.filter { it.userId == user.id }.forEach { it.drop(key) }
            if (transmitter.current?.song?.key == key) transmitter.skip()
        }
    }

    suspend fun thumbs(user: User): Map<String, Int> = store.thumbs(user.id).mapValues { it.value.first }

    /** Whether [user] has anything for the stations to play. */
    suspend fun ready(user: User): Boolean = !taste(user).isEmpty

    /** The person's taste, kept for a while (and refreshed in the background once it's old). */
    suspend fun taste(user: User): Taste {
        val now = services.clock()
        val kept = tastes[user.id]
        if (kept != null) {
            if (now - kept.first > TASTE_MS && tasteRefreshing.add(user.id)) {
                services.scope.launch {
                    try {
                        tastes[user.id] = services.clock() to buildTaste(user)
                    } finally {
                        tasteRefreshing.remove(user.id)
                    }
                }
            }
            return kept.second
        }
        return buildTaste(user).also { tastes[user.id] = now to it }
    }

    private suspend fun buildTaste(user: User): Taste {
        val now = services.clock()
        val listens = services.history.between(user.id, now - YEAR_MS, now + 60_000)
        val playlists = services.playlists.list(user.id).take(MAX_PLAYLISTS).mapNotNull { summary ->
            runCatching { services.playlists.get(user.id, summary.id) }.getOrNull()?.let { list -> summary.name to list.entries.map { it.track } }
        }
        return Taste.build(listens, playlists, store.thumbs(user.id), now)
    }

    /**
     * Songs the person hasn't heard, from the artists they love: those artists' top songs on
     * their sources. Found in the background (it takes a few seconds), kept for half a day.
     */
    private fun discovery(user: User, taste: Taste): List<ApiTrack> {
        val now = services.clock()
        val kept = discoveries[user.id]
        if ((kept == null || now - kept.first > DISCOVERY_MS) && discovering.add(user.id)) {
            services.scope.launch {
                try {
                    discoveries[user.id] = services.clock() to findDiscoveries(user, taste)
                } catch (e: Exception) {
                    CoreLog.w(TAG, "Couldn't find new songs for ${user.username}: ${e.message}")
                } finally {
                    discovering.remove(user.id)
                }
            }
        }
        return kept?.second.orEmpty()
    }

    private suspend fun findDiscoveries(user: User, taste: Taste): List<ApiTrack> = coroutineScope {
        val gate = Semaphore(3)
        taste.artists.take(DISCOVERY_ARTISTS).map { (name, _) ->
            async {
                gate.withPermit {
                    runCatching { services.browser.artist(user, BrowseKey.artist(name)) }.getOrNull()?.topSongs.orEmpty()
                        .map { ApiTrack.of(it.item, it.via) }
                        .filter { Taste.songKeyOf(it) !in taste.songs }
                        .take(PER_ARTIST)
                }
            }
        }.awaitAll().flatten()
    }

    private fun programme(user: User, kind: StationKind): Programme = object : Programme {
        private val programmer = Programmer(kind)
        private val dj = Dj()
        private var position = kotlin.random.Random.nextInt(programmer.clock.size)
        private val unplayable = ConcurrentHashMap.newKeySet<String>()

        /** What this station put on the air (kept here too, since saving it happens in the background). */
        private val aired = java.util.concurrent.ConcurrentLinkedDeque<Played>()

        override suspend fun nextSong(scheduled: List<Candidate>): Candidate? {
            val taste = taste(user)
            val played = (store.recent(user.id) + aired).distinctBy { "${it.key}@${it.at}" }.sortedBy { it.at }
            return programmer.pick(taste, discovery(user, taste), played, scheduled, position++, services.clock(), kotlin.random.Random.Default, unplayable)
        }

        override suspend fun open(song: Candidate, fromMs: Long): PcmSource = try {
            decode(user, song, fromMs)
        } catch (e: Exception) {
            unplayable += song.key
            throw e
        }

        override fun encoder(): Process = ProcessBuilder(
            services.config.ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
            "-f", "s16le", "-ar", Pcm.RATE.toString(), "-ac", "2", "-i", "pipe:0",
            "-c:a", "aac", "-b:a", "192k", "-f", "adts", "pipe:1",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

        override fun aired(now: Transmitter.OnAir, before: Transmitter.OnAir?, coming: List<Candidate>, atMs: Long): DjLine? {
            aired += Played(kind.id, now.song.key, now.song.artistKey, services.clock())
            while (aired.size > 50) aired.pollFirst()
            services.scope.launch { runCatching { store.played(user.id, kind.id, now.song) } }
            val transmitter = transmitters[keyOf(user.id, kind)]
            return dj.line(transmitter?.name ?: kind.suffix, kind, before?.song, now.song, coming, atMs, transmitter?.zone ?: ZoneId.systemDefault())
        }
    }

    /** Where [song] plays from for [user], decoding from [fromMs] (levelled, its leading silence cut). */
    private suspend fun decode(user: User, song: Candidate, fromMs: Long): PcmSource {
        val resolution = services.catalog.resolve(user, song.track.toTrack(), song.track.via, StreamQuality.Original, thorough = false)
        val (relayId, relayUrl) = services.streamer.openRelay(resolution.stream)
        val seek = if (fromMs > 0) listOf("-ss", "%.3f".format(java.util.Locale.ROOT, fromMs / 1000.0)) else emptyList()
        val filters = buildList {
            if (fromMs == 0L) add("silenceremove=start_periods=1:start_duration=0.05:start_threshold=-50dB")
            add("loudnorm=I=-16:TP=-1.5:LRA=11")
            add("aresample=${Pcm.RATE}")
        }.joinToString(",")
        val command = buildList {
            addAll(listOf(services.config.ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin"))
            if (resolution.stream.supportsRangeRequests) {
                addAll(seek)
                addAll(listOf("-i", relayUrl))
            } else {
                addAll(listOf("-seekable", "0", "-i", relayUrl))
                addAll(seek)
            }
            addAll(listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-af", filters, "-ac", "2", "-ar", Pcm.RATE.toString(), "-f", "s16le", "pipe:1"))
        }
        val process = withContext(Dispatchers.IO) { ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
        val input = process.inputStream.buffered(64 * 1024)
        // Waits for the first audio, so a song that won't play is known now (not mid-air).
        input.mark(1)
        val first = withContext(Dispatchers.IO) { input.read() }
        if (first < 0) {
            process.destroy()
            services.streamer.closeRelay(relayId)
            throw IOException("${resolution.source.name} sent nothing to play")
        }
        input.reset()
        return object : PcmSource {
            override fun read(into: ByteArray, offset: Int, length: Int): Int = try {
                input.read(into, offset, length)
            } catch (e: IOException) {
                -1
            }

            override fun close() {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                services.streamer.closeRelay(relayId)
            }
        }
    }

    companion object {
        private const val TAG = "Radio"
        private const val IDLE_OFF_MS = 45_000L
        private const val MAX_ON_AIR = 8
        private const val TASTE_MS = 10 * 60_000L
        private const val DISCOVERY_MS = 12 * 60 * 60_000L
        private const val YEAR_MS = 365 * Taste.DAY_MS
        private const val MAX_PLAYLISTS = 50
        private const val DISCOVERY_ARTISTS = 10
        private const val PER_ARTIST = 6
    }
}
