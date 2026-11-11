package com.music.server.playlists

import com.music.player.model.SourceType
import com.music.server.Services
import com.music.server.auth.requireUser
import com.music.server.music.ApiTrack
import com.music.server.music.Artwork
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A year of listening, like Spotify Wrapped: the top songs, artists, albums and stations, how long
 * and when, and a few highlights. Minutes are listened time; a song counts once per listen.
 */
@Serializable
data class Wrapped(
    val year: Int,
    /** The years there is any listening for, newest first. */
    val years: List<Int>,
    /** It's this year: the year so far. */
    val soFar: Boolean,
    val listens: Int,
    val minutes: Long,
    val songs: Int,
    val artists: Int,
    val albums: Int,
    /** Days with any listening. */
    val days: Int,
    /** Up to 50, for a playlist of them. */
    val topSongs: List<WrappedSong>,
    val topArtists: List<WrappedArtist>,
    val topAlbums: List<WrappedAlbum>,
    /** Live channels and stations, by minutes. */
    val topStations: List<WrappedShare>,
    /** Minutes from each service (Jellyfin, YouTube Music, SiriusXM, …), by its source name. */
    val sources: List<WrappedShare>,
    /** Minutes on each kind of device: "web" (the web player) or "android" (the app). */
    val devices: List<WrappedShare>,
    /** Minutes in each month, January first. */
    val months: List<Long>,
    /** Minutes in each hour of the day, midnight first, in the person's time zone. */
    val hours: List<Long>,
    /** Minutes on each day of the week, Monday first. */
    val weekdays: List<Long>,
    val biggestDay: WrappedDay? = null,
    /** The most days in a row with some listening. */
    val longestStreak: Int,
    val firstSong: WrappedSong? = null,
    /** The song played most times in one day. */
    val onRepeat: WrappedRepeat? = null,
    /** Artists first heard this year (only when there's listening from before it). */
    val discoveries: List<WrappedArtist>,
    val newArtists: Int,
    val personality: WrappedPersonality,
)

@Serializable
data class WrappedSong(val track: ApiTrack, val plays: Int, val minutes: Long, val firstPlayedAt: Long? = null)

@Serializable
data class WrappedArtist(val name: String, val plays: Int, val minutes: Long, val artworkUrl: String? = null, val topSong: String? = null)

@Serializable
data class WrappedAlbum(val title: String, val artist: String, val plays: Int, val minutes: Long, val artworkUrl: String? = null)

@Serializable
data class WrappedShare(val name: String, val minutes: Long)

/** [date] as yyyy-mm-dd, in the person's time zone. */
@Serializable
data class WrappedDay(val date: String, val minutes: Long, val listens: Int)

@Serializable
data class WrappedRepeat(val track: ApiTrack, val plays: Int, val date: String)

@Serializable
data class WrappedPersonality(val name: String, val description: String)

object WrappedReport {
    private const val TOP_SONGS = 50
    private const val TOP = 10

    /** A listen that's only a live channel or station (its song unknown): time, but no song. */
    private fun ApiTrack.isLiveItem() = source == SourceType.RADIO || (source == SourceType.SIRIUSXM && sourceId.startsWith("channel:"))

    /** Older listens may not say how long they were heard: a song's length, up to three minutes. */
    private fun Listen.ms(): Long = listenedMs.takeIf { it > 0 } ?: (track.durationMs ?: 180_000L).coerceIn(30_000L, 180_000L)

    private fun minutes(ms: Long) = ms / 60_000

    private val FEATURING = Regex("""\s*[(\[](feat\.?|ft\.?|featuring|with)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val VERSION = Regex("""\s+-\s+(\d{4}\s+)?(remaster(ed)?|single version|radio edit|mono|stereo)\b.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(,|;|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+)\s*""", RegexOption.IGNORE_CASE)

    /** The same song from any service: its title without "(feat. …)" or " - Remastered", and its main artist. */
    private fun songKey(track: ApiTrack) = "${normal(track.title.replace(FEATURING, "").replace(VERSION, ""))}\u0000${normal(mainArtist(track.artist))}"

    /** The first of several artists ("A, B" or "A feat. B"); "A & B" stays one. */
    fun mainArtist(artist: String): String = artist.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    private fun normal(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()

    fun build(year: Int, zone: ZoneId, listens: List<Listen>, years: List<Int>, heardBefore: Set<String>, now: Long): Wrapped {
        val songs = listens.filter { !it.track.isLiveItem() && it.track.title.isNotBlank() }
        val totalMs = listens.sumOf { it.ms() }
        fun dateOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

        // Songs: by title and main artist, shown as the latest copy played that isn't live.
        val bySong = songs.groupBy { songKey(it.track) }
        val topSongs = bySong.values
            .map { plays -> WrappedSong(plays.last().track, plays.size, minutes(plays.sumOf { it.ms() }), plays.first().playedAt) to plays.sumOf { it.ms() } }
            .sortedWith(compareByDescending<Pair<WrappedSong, Long>> { it.first.plays }.thenByDescending { it.second })
            .map { it.first }

        // Artists: by minutes, as Spotify ranks them.
        val byArtist = songs.groupBy { normal(mainArtist(it.track.artist)) }.filterKeys { it.isNotEmpty() }
        fun artistOf(plays: List<Listen>): WrappedArtist {
            val name = plays.groupingBy { mainArtist(it.track.artist) }.eachCount().maxBy { it.value }.key
            val favourite = plays.groupBy { songKey(it.track) }.maxBy { it.value.size }.value
            return WrappedArtist(
                name, plays.size, minutes(plays.sumOf { it.ms() }),
                favourite.lastOrNull { it.track.artworkUrl != null }?.track?.artworkUrl ?: plays.lastOrNull { it.track.artworkUrl != null }?.track?.artworkUrl,
                favourite.last().track.title,
            )
        }
        val artistsByTime = byArtist.entries.sortedByDescending { (_, plays) -> plays.sumOf { it.ms() } }
        val topArtists = artistsByTime.take(TOP).map { artistOf(it.value) }

        val byAlbum = songs.filter { !it.track.album.isNullOrBlank() }.groupBy { "${normal(it.track.album!!)}\u0000${normal(mainArtist(it.track.artist))}" }
        val topAlbums = byAlbum.values.sortedByDescending { plays -> plays.sumOf { it.ms() } }.take(TOP).map { plays ->
            val latest = plays.last().track
            WrappedAlbum(latest.album!!, mainArtist(latest.artist), plays.size, minutes(plays.sumOf { it.ms() }), plays.lastOrNull { it.track.artworkUrl != null }?.track?.artworkUrl)
        }

        val stationMs = listens.filter { !it.station.isNullOrBlank() }.groupBy { it.station!!.trim() }.mapValues { (_, plays) -> plays.sumOf { it.ms() } }
        val topStations = stationMs.entries.sortedByDescending { it.value }.take(TOP).map { WrappedShare(it.key, minutes(it.value)) }

        fun shares(key: (Listen) -> String) = listens.groupBy(key).map { (name, plays) -> WrappedShare(name, minutes(plays.sumOf { it.ms() })) }
            .filter { it.name.isNotBlank() }.sortedByDescending { it.minutes }
        val sources = shares { it.track.source.name }
        val devices = shares { it.client.ifBlank { "android" } }

        val months = LongArray(12)
        val hours = LongArray(24)
        val weekdays = LongArray(7)
        val byDay = sortedMapOf<LocalDate, MutableList<Listen>>()
        listens.forEach { listen ->
            val at = Instant.ofEpochMilli(listen.playedAt).atZone(zone)
            val ms = listen.ms()
            months[at.monthValue - 1] += ms
            hours[at.hour] += ms
            weekdays[at.dayOfWeek.value - DayOfWeek.MONDAY.value] += ms
            byDay.getOrPut(at.toLocalDate()) { mutableListOf() }.add(listen)
        }
        val biggestDay = byDay.maxByOrNull { (_, plays) -> plays.sumOf { it.ms() } }?.let { (date, plays) ->
            WrappedDay(date.toString(), minutes(plays.sumOf { it.ms() }), plays.size)
        }
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        byDay.keys.forEach { day ->
            run = if (previous?.plusDays(1) == day) run + 1 else 1
            longest = maxOf(longest, run)
            previous = day
        }
        val onRepeat = songs.groupBy { dateOf(it.playedAt) to songKey(it.track) }
            .maxByOrNull { it.value.size }
            ?.takeIf { it.value.size >= 3 }
            ?.let { (key, plays) -> WrappedRepeat(plays.last().track, plays.size, key.first.toString()) }

        // Discoveries: artists not heard before this year (when there's listening from before it to compare with).
        val discovered = if (heardBefore.isEmpty()) emptyList() else artistsByTime.filter { it.key !in heardBefore }
        val first = songs.firstOrNull()?.let { WrappedSong(it.track, 1, minutes(it.ms()), it.playedAt) }

        return Wrapped(
            year = year,
            years = years,
            soFar = Instant.ofEpochMilli(now).atZone(zone).year == year,
            listens = listens.size,
            minutes = minutes(totalMs),
            songs = bySong.size,
            artists = byArtist.size,
            albums = byAlbum.size,
            days = byDay.size,
            topSongs = topSongs.take(TOP_SONGS),
            topArtists = topArtists,
            topAlbums = topAlbums,
            topStations = topStations,
            sources = sources,
            devices = devices,
            months = months.map(::minutes),
            hours = hours.map(::minutes),
            weekdays = weekdays.map(::minutes),
            biggestDay = biggestDay,
            longestStreak = longest,
            firstSong = first,
            onRepeat = onRepeat,
            discoveries = discovered.take(5).map { artistOf(it.value) },
            newArtists = discovered.size,
            personality = personality(totalMs, hours, stationMs.values.sum(), topArtists.firstOrNull(), byArtist.size, songs.size),
        )
    }

    /** Which kind of listener the year shows, by the first thing that stands out. */
    private fun personality(totalMs: Long, hours: LongArray, stationMs: Long, top: WrappedArtist?, artists: Int, songListens: Int): WrappedPersonality {
        if (totalMs <= 0) return WrappedPersonality("Just Getting Started", "Play some music and your year will take shape here.")
        val peak = hours.indices.maxBy { hours[it] }
        val topShare = top?.let { it.minutes * 60_000.0 / totalMs } ?: 0.0
        val radioShare = stationMs.toDouble() / totalMs
        val night = (hours.slice(22..23).sum() + hours.slice(0..3).sum()).toDouble() / totalMs
        val morning = hours.slice(5..9).sum().toDouble() / totalMs
        return when {
            top != null && topShare >= 0.25 ->
                WrappedPersonality("The Superfan", "${top.name} was ${(topShare * 100).toInt()}% of your listening. That's devotion.")
            radioShare >= 0.5 ->
                WrappedPersonality("The Dial Spinner", "You let the DJs drive: ${(radioShare * 100).toInt()}% of your time was live radio.")
            night >= 0.35 ->
                WrappedPersonality("The Night Owl", "Your music came alive after dark, peaking around ${clock(peak)}.")
            morning >= 0.35 ->
                WrappedPersonality("The Early Bird", "You started your days with music, peaking around ${clock(peak)}.")
            songListens >= 50 && artists >= songListens / 3 ->
                WrappedPersonality("The Explorer", "$artists different artists. You never stopped looking for something new.")
            else ->
                WrappedPersonality("The All-Rounder", "A bit of everything, any time of day, with ${clock(peak)} your favourite hour.")
        }
    }

    private fun clock(hour: Int) = when (hour) {
        0 -> "midnight"
        12 -> "noon"
        in 1..11 -> "$hour am"
        else -> "${hour - 12} pm"
    }

    /** The year's start and end in [zone], as times. */
    fun span(year: Int, zone: ZoneId): Pair<Long, Long> =
        ZonedDateTime.of(year, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli() to
            ZonedDateTime.of(year + 1, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()

    /** The main artists in [listens], as compared for discoveries. */
    fun artistsIn(listens: List<Listen>): Set<String> =
        listens.filter { !it.track.isLiveItem() }.map { normal(mainArtist(it.track.artist)) }.filter { it.isNotEmpty() }.toSet()
}

/** A person's Wrapped for a year (this year by default), in their time zone (`tz`, an IANA name). */
fun Route.wrappedRoutes(services: Services) {
    get("/api/wrapped") {
        val user = call.requireUser(services)
        val params = call.request.queryParameters
        val zone = params["tz"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val now = services.clock()
        val thisYear = Instant.ofEpochMilli(now).atZone(zone).year
        val first = services.history.first(user.id)
        val firstYear = first?.let { Instant.ofEpochMilli(it).atZone(zone).year } ?: thisYear
        val year = params["year"]?.toIntOrNull()?.coerceIn(minOf(firstYear, thisYear), thisYear) ?: thisYear
        val (from, to) = WrappedReport.span(year, zone)
        val listens = services.history.between(user.id, from, to)
        val before = if (first != null && first < from) WrappedReport.artistsIn(services.history.between(user.id, 0, from)) else emptySet()
        val years = (thisYear downTo minOf(firstYear, thisYear)).toList()
        call.respond(WrappedReport.build(year, zone, listens, years, before, now).out(services.artwork))
    }
}

/** Artwork through the server, as everywhere else. */
private fun Wrapped.out(art: Artwork) = copy(
    topSongs = topSongs.map { it.copy(track = art.outgoing(it.track)) },
    topArtists = topArtists.map { it.copy(artworkUrl = art.wrap(it.artworkUrl)) },
    topAlbums = topAlbums.map { it.copy(artworkUrl = art.wrap(it.artworkUrl)) },
    firstSong = firstSong?.let { it.copy(track = art.outgoing(it.track)) },
    onRepeat = onRepeat?.let { it.copy(track = art.outgoing(it.track)) },
    discoveries = discoveries.map { it.copy(artworkUrl = art.wrap(it.artworkUrl)) },
)
