package com.music.server.radio

import com.music.player.model.SourceType
import com.music.server.music.ApiTrack
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/** A person's own stations, made from what they listen to and like. */
enum class StationKind(val id: String, val suffix: String, val tagline: String) {
    MINE("mine", "Radio", "Your favourites, and what you'll love next"),
    REWIND("rewind", "Rewind", "The songs you couldn't stop playing, back on the air"),
    DISCOVERY("discovery", "Discovery", "New to you, from the artists you love"),
    ;

    companion object {
        fun of(id: String): StationKind? = entries.firstOrNull { it.id == id.removePrefix("hls:") }
    }
}

object Stations {
    /**
     * The pseudo-source personal stations go "via": they play as radio stations (a stream with
     * no end), so every player (old ones too) plays them, favourites keep them, and so on.
     */
    const val VIA = "personal"

    /** Where a station's logo is (the server's own, not an upstream image). */
    const val ART_PATH = "/api/radio/art"

    /** "Madison's Radio": the first word of the person's name (or their username). */
    fun nameOf(kind: StationKind, displayName: String, username: String): String {
        val first = displayName.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() } ?: username
        return "$first’s ${kind.suffix}"
    }

    fun track(kind: StationKind, displayName: String, username: String) = ApiTrack(
        source = SourceType.RADIO,
        sourceId = kind.id,
        title = nameOf(kind, displayName, username),
        artist = kind.tagline,
        artworkUrl = "$ART_PATH?station=${kind.id}",
        via = VIA,
    )

    fun isPersonal(track: ApiTrack) = track.via == VIA && track.source == SourceType.RADIO && StationKind.of(track.sourceId) != null
}

/** Something the DJ says, over the start of [songKey], [atMs] (on-air time). */
data class DjLine(val id: String, val text: String, val atMs: Long, val songKey: String)

/**
 * What the station's DJ says between songs: back-announcing, introducing, station IDs and time
 * checks, as a radio host who knows your listening would. Not every song: every few, and on the
 * hour. Short, since it's spoken over the start of a song.
 */
class Dj(private val random: Random = Random.Default) {
    private var songsSinceTalk = 0
    private var lastHour: Int? = null
    private var nextTalkAfter = 3

    /**
     * The line for the start of [now] (just after [before] ended, or was [skipped]: a skipped
     * song isn't announced), or null to let the music play. [coming] is what's lined up after it.
     */
    fun line(station: String, kind: StationKind, before: Candidate?, now: Candidate, coming: List<Candidate>, atMs: Long, zone: ZoneId, skipped: Boolean = false): DjLine? {
        val time = Instant.ofEpochMilli(atMs).atZone(zone)
        val topOfHour = lastHour != null && time.hour != lastHour
        lastHour = time.hour
        songsSinceTalk++
        // A first song (just tuned in) plays without a word.
        if (before == null) return null
        if (!topOfHour && songsSinceTalk < nextTalkAfter) return null
        songsSinceTalk = 0
        nextTalkAfter = 3 + random.nextInt(2)

        val parts = mutableListOf<String>()
        val that = "That was ${quoted(before.track.title)} by ${before.track.artist}."
        val id = pick("You're listening to $station.", "This is $station.", "$station, just for you.", "$station: all yours.")
        if (topOfHour) {
            parts += "It's ${clock(time)} on ${weekday(time)} ${partOfDay(time.hour)}."
            parts += id
        } else if (skipped) {
            if (random.nextBoolean()) parts += id
        } else {
            when (random.nextInt(3)) {
                0 -> parts += that
                1 -> parts += id
                else -> {
                    parts += that
                    if (random.nextBoolean()) parts += id
                }
            }
        }
        parts += intro(now, kind, zone)
        val artists = coming.take(3).map { it.track.artist }.distinct().filter { it != now.track.artist }
        if (artists.size >= 2 && random.nextInt(3) == 0) parts += "Still to come: ${list(artists)}."
        return DjLine("${now.key}@$atMs", parts.joinToString(" "), atMs, now.key)
    }

    /** What the DJ says about the song starting. */
    private fun intro(song: Candidate, kind: StationKind, zone: ZoneId): String {
        val title = quoted(song.track.title)
        val artist = song.track.artist
        val info = song.info
        return when {
            info?.thumb == 1 -> pick("Here's one you gave a thumbs up: $title.", "You liked this one: $artist, $title.")
            song.rotation == Rotation.DISCOVERY -> pick(
                "Something new for you now, from $artist: $title.",
                "New to you: $title, by $artist.",
                "You love $artist, so try this one: $title.",
            ) + if (kind == StationKind.DISCOVERY && random.nextInt(3) == 0) " Thumbs up if it's a keeper." else ""
            info != null && info.recentPlays >= 3 -> pick(
                "You've played this ${info.recentPlays} times this month: $title.",
                "One of your favourites right now. $artist, $title.",
                "On repeat for you lately: $title.",
            )
            info != null && song.rotation == Rotation.RECURRENT && info.lastPlayedAt != null -> {
                val month = Instant.ofEpochMilli(info.lastPlayedAt).atZone(zone).month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                pick("Here's one you haven't played since $month: $title.", "A blast from your past: $artist, $title.", "Remember this one? $title.")
            }
            info != null && info.playlists.isNotEmpty() -> "From your ${info.playlists.first()} playlist: $title."
            else -> pick("Here's $artist with $title.", "Next up, $artist: $title.", "$title, by $artist.")
        }
    }

    private fun pick(vararg options: String) = options[random.nextInt(options.size)]

    companion object {
        /** A title without the brackets that read badly aloud ("(feat. …)", "[Remastered]"). */
        fun quoted(title: String) = title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim().ifEmpty { title }

        fun list(items: List<String>) = when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }

        fun clock(time: java.time.ZonedDateTime): String {
            val hour = time.hour % 12
            return if (time.minute == 0) "${if (hour == 0) 12 else hour} o'clock" else "${if (hour == 0) 12 else hour}:${"%02d".format(time.minute)}"
        }

        fun weekday(time: java.time.ZonedDateTime): String = time.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

        fun partOfDay(hour: Int) = when (hour) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "night"
        }
    }
}
