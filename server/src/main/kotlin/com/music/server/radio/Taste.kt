package com.music.server.radio

import com.music.player.model.SourceType
import com.music.server.music.ApiTrack
import com.music.server.playlists.Listen
import com.music.server.playlists.WrappedReport
import kotlin.math.exp
import kotlin.math.min

/**
 * The kinds of song a radio station rotates, as real stations sort their music:
 *  - [POWER]: what the person plays most right now (heavy rotation);
 *  - [RECURRENT]: songs they loved and haven't played lately;
 *  - [LIBRARY]: the rest of what they keep (their playlists, songs heard a couple of times);
 *  - [DISCOVERY]: songs they haven't heard, from artists they love.
 */
enum class Rotation { POWER, RECURRENT, LIBRARY, DISCOVERY }

/** One song as the person knows it. */
data class SongInfo(
    val key: String,
    val artistKey: String,
    val track: ApiTrack,
    /** Listens in the last year. */
    val plays: Int,
    /** Listens in the last 30 days. */
    val recentPlays: Int,
    val lastPlayedAt: Long?,
    val firstPlayedAt: Long?,
    /** The playlists it's in, by name. */
    val playlists: List<String> = emptyList(),
    /** A thumb given on a station: 1 up, -1 down. */
    val thumb: Int = 0,
)

/** A song a station could play next, and how much it wants to. */
data class Candidate(val key: String, val artistKey: String, val track: ApiTrack, val weight: Double, val rotation: Rotation, val info: SongInfo?)

/** What a person listens to and likes: their songs, their artists (best first), and what they don't want. */
class Taste(
    val songs: Map<String, SongInfo>,
    /** Artists by how much they're listened to, recent listening counting most: (name, score). */
    val artists: List<Pair<String, Double>>,
    val disliked: Set<String>,
    val now: Long,
) {
    val isEmpty: Boolean get() = songs.values.none { it.thumb >= 0 }

    /** The songs a station can play from [rotation]; [discovery] is what was found for the person. */
    fun pool(rotation: Rotation, discovery: List<ApiTrack>): List<Candidate> {
        val usable = songs.values.filter { it.thumb >= 0 && it.key !in disliked }
        return when (rotation) {
            Rotation.POWER -> usable
                .filter { it.recentPlays >= 2 || it.thumb > 0 }
                .map { it to it.recentPlays + 3.0 * it.thumb + if (it.playlists.isNotEmpty()) 0.5 else 0.0 }
                .sortedByDescending { it.second }
                .take(POWER_SIZE)
                .map { (song, weight) -> Candidate(song.key, song.artistKey, song.track, weight, rotation, song) }
            Rotation.RECURRENT -> {
                val older = usable.filter { it.plays >= 3 && (it.lastPlayedAt ?: 0) < now - RECURRENT_AFTER_MS }
                val pool = older.ifEmpty { usable.filter { it.plays >= 2 && (it.lastPlayedAt ?: 0) < now - WEEK_MS } }
                pool.map { Candidate(it.key, it.artistKey, it.track, it.plays.toDouble(), rotation, it) }
            }
            Rotation.LIBRARY -> usable
                .filter { it.playlists.isNotEmpty() || (it.plays in 1..2 && (it.lastPlayedAt ?: 0) < now - WEEK_MS) }
                .map { Candidate(it.key, it.artistKey, it.track, 1.0, rotation, it) }
            Rotation.DISCOVERY -> {
                val scores = artists.toMap().mapKeys { (name, _) -> artistKeyOf(name) }
                discovery
                    .map { it to songKeyOf(it) }
                    .filter { (_, key) -> key !in songs && key !in disliked }
                    .distinctBy { it.second }
                    .map { (track, key) ->
                        val artist = artistKeyOf(track.artist)
                        Candidate(key, artist, track, 1.0 + (scores[artist] ?: 0.0), rotation, null)
                    }
            }
        }
    }

    /** The person's own copy of a song (to play it as they know it), if they have one. */
    fun known(key: String): SongInfo? = songs[key]

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val WEEK_MS = 7 * DAY_MS
        private const val RECENT_MS = 30 * DAY_MS
        private const val RECURRENT_AFTER_MS = 21 * DAY_MS
        private const val POWER_SIZE = 60

        /** A listen counts for a song once half a minute of it was heard (older listens don't say). */
        private const val COUNTS_AFTER_MS = 30_000L

        /** The same song from any service (as Wrapped counts songs), in characters clients pass back easily. */
        fun songKeyOf(track: ApiTrack): String = WrappedReport.songKey(track).replace('\u0000', '|')

        fun artistKeyOf(artist: String): String = WrappedReport.normal(WrappedReport.mainArtist(artist))

        private fun ApiTrack.isLiveItem() = source == SourceType.RADIO || (source == SourceType.SIRIUSXM && !sourceId.startsWith("song:"))

        /**
         * Builds a taste from a year of [listens] (any order), the person's [playlists] (name and
         * songs) and the thumbs given on their stations ([thumbs]: song key to thumb and song).
         */
        fun build(listens: List<Listen>, playlists: List<Pair<String, List<ApiTrack>>>, thumbs: Map<String, Pair<Int, ApiTrack>>, now: Long): Taste {
            data class Acc(var track: ApiTrack, var plays: Int = 0, var recent: Int = 0, var last: Long? = null, var first: Long? = null)
            val acc = LinkedHashMap<String, Acc>()
            val artistScore = HashMap<String, Double>()
            val artistName = HashMap<String, MutableMap<String, Int>>()
            for (listen in listens.sortedBy { it.playedAt }) {
                val track = listen.track
                if (track.isLiveItem() || track.title.isBlank() || track.artist.isBlank()) continue
                if (listen.listenedMs in 1 until COUNTS_AFTER_MS) continue
                val key = songKeyOf(track)
                val entry = acc.getOrPut(key) { Acc(track) }
                // The latest copy, for playing it the way it was last heard.
                entry.track = track
                entry.plays++
                if (listen.playedAt >= now - RECENT_MS) entry.recent++
                entry.last = listen.playedAt
                if (entry.first == null) entry.first = listen.playedAt
                val ageDays = (now - listen.playedAt).coerceAtLeast(0) / DAY_MS.toDouble()
                val heard = if (listen.listenedMs > 0) min(listen.listenedMs, 240_000L) / 240_000.0 else 0.75
                val artist = artistKeyOf(track.artist)
                artistScore.merge(artist, exp(-ageDays / 60.0) * heard, Double::plus)
                artistName.getOrPut(artist) { HashMap() }.merge(WrappedReport.mainArtist(track.artist), 1, Int::plus)
            }
            val inPlaylists = HashMap<String, MutableList<String>>()
            for ((name, tracks) in playlists) {
                for (track in tracks) {
                    if (track.isLiveItem() || track.title.isBlank()) continue
                    val key = songKeyOf(track)
                    inPlaylists.getOrPut(key) { mutableListOf() }.let { if (name !in it) it += name }
                    if (key !in acc) acc[key] = Acc(track)
                    // Keeping a song counts a little toward its artist, so new playlists shape the stations too.
                    val artist = artistKeyOf(track.artist)
                    artistScore.merge(artist, 0.2, Double::plus)
                    artistName.getOrPut(artist) { HashMap() }.merge(WrappedReport.mainArtist(track.artist), 1, Int::plus)
                }
            }
            for ((key, thumb) in thumbs) {
                if (key !in acc && thumb.first > 0) acc[key] = Acc(thumb.second)
                if (thumb.first > 0) artistScore.merge(artistKeyOf(thumb.second.artist), 1.0, Double::plus)
            }
            val songs = acc.mapValues { (key, a) ->
                SongInfo(
                    key = key,
                    artistKey = artistKeyOf(a.track.artist),
                    track = a.track,
                    plays = a.plays,
                    recentPlays = a.recent,
                    lastPlayedAt = a.last,
                    firstPlayedAt = a.first,
                    playlists = inPlaylists[key].orEmpty(),
                    thumb = thumbs[key]?.first ?: 0,
                )
            }
            val artists = artistScore.entries
                .sortedByDescending { it.value }
                .map { (key, score) -> (artistName[key]?.maxByOrNull { it.value }?.key ?: key) to score }
            return Taste(songs, artists, thumbs.filterValues { it.first < 0 }.keys, now)
        }
    }
}

/** A song a station played: for keeping songs and artists apart. */
data class Played(val station: String, val key: String, val artistKey: String, val at: Long)

/**
 * Picks what a station plays, like a station's music scheduler: a clock of rotations to follow,
 * with each song kept apart from its last play and each artist from their last few songs.
 */
class Programmer(private val kind: StationKind) {
    /** The clock the station follows, one rotation per song, round and round. */
    val clock: List<Rotation> = when (kind) {
        StationKind.MINE -> listOf(
            Rotation.POWER, Rotation.DISCOVERY, Rotation.POWER, Rotation.RECURRENT, Rotation.LIBRARY,
            Rotation.POWER, Rotation.DISCOVERY, Rotation.POWER, Rotation.RECURRENT, Rotation.POWER, Rotation.LIBRARY, Rotation.DISCOVERY,
        )
        StationKind.REWIND -> listOf(
            Rotation.RECURRENT, Rotation.RECURRENT, Rotation.LIBRARY, Rotation.RECURRENT, Rotation.RECURRENT, Rotation.LIBRARY, Rotation.RECURRENT, Rotation.POWER,
        )
        StationKind.DISCOVERY -> listOf(
            Rotation.DISCOVERY, Rotation.DISCOVERY, Rotation.POWER, Rotation.DISCOVERY, Rotation.DISCOVERY, Rotation.RECURRENT,
            Rotation.DISCOVERY, Rotation.DISCOVERY, Rotation.LIBRARY,
        )
    }

    /** How long a song stays off the air after it's played (on any of the person's stations). */
    private val songRestMs: Long = when (kind) {
        StationKind.MINE -> 4 * HOUR_MS
        StationKind.REWIND -> 8 * HOUR_MS
        StationKind.DISCOVERY -> 24 * HOUR_MS
    }

    /** How many songs go by before an artist plays again on this station. */
    private val artistRest: Int = if (kind == StationKind.DISCOVERY) 2 else 3

    private fun fallbacks(rotation: Rotation): List<Rotation> = when (rotation) {
        Rotation.POWER -> listOf(Rotation.POWER, Rotation.LIBRARY, Rotation.RECURRENT, Rotation.DISCOVERY)
        Rotation.RECURRENT -> listOf(Rotation.RECURRENT, Rotation.LIBRARY, Rotation.POWER, Rotation.DISCOVERY)
        Rotation.LIBRARY -> listOf(Rotation.LIBRARY, Rotation.RECURRENT, Rotation.POWER, Rotation.DISCOVERY)
        Rotation.DISCOVERY -> listOf(Rotation.DISCOVERY, Rotation.POWER, Rotation.LIBRARY, Rotation.RECURRENT)
    }

    /**
     * The song for clock position [position], given what was [played] lately (newest last, all
     * the person's stations) and what's already lined up ([lined], next in line last). Rules are
     * relaxed, rather than the station falling silent, when nothing fits: artists first, then
     * how long songs rest. Null when the person has nothing to play at all.
     */
    fun pick(
        taste: Taste,
        discovery: List<ApiTrack>,
        played: List<Played>,
        lined: List<Candidate>,
        position: Int,
        now: Long,
        random: kotlin.random.Random,
        exclude: Set<String> = emptySet(),
    ): Candidate? {
        val wanted = clock[Math.floorMod(position, clock.size)]
        val restedSongs = played.filter { now - it.at < songRestMs }.map { it.key }.toSet() + lined.map { it.key } + exclude
        val recentArtists = played.filter { it.station == kind.id }.map { it.artistKey } + lined.map { it.artistKey }
        val lastArtists = recentArtists.takeLast(artistRest).toSet()
        val lastArtist = recentArtists.lastOrNull()
        // A song heard on a station in the last week isn't discovered again.
        val heardThisWeek = played.filter { now - it.at < Taste.WEEK_MS }.map { it.key }.toSet()
        val attempts = listOf(
            { c: Candidate -> c.key !in restedSongs && c.artistKey !in lastArtists },
            { c: Candidate -> c.key !in restedSongs && c.artistKey != lastArtist },
            { c: Candidate -> c.key !in restedSongs },
            { c: Candidate -> c.key !in lined.map { it.key } && c.key !in exclude && c.key != played.lastOrNull()?.key },
        )
        for (rule in attempts) {
            for (rotation in fallbacks(wanted)) {
                val pool = taste.pool(rotation, discovery)
                    .filter { rotation != Rotation.DISCOVERY || it.key !in heardThisWeek }
                    .filter(rule)
                weighted(pool, random)?.let { return it }
            }
        }
        return null
    }

    private fun weighted(pool: List<Candidate>, random: kotlin.random.Random): Candidate? {
        if (pool.isEmpty()) return null
        val total = pool.sumOf { it.weight.coerceAtLeast(0.05) }
        var at = random.nextDouble() * total
        for (c in pool) {
            at -= c.weight.coerceAtLeast(0.05)
            if (at <= 0) return c
        }
        return pool.last()
    }

    companion object {
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
