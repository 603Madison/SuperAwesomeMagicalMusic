package com.music.player.sources

import com.music.player.model.SourceType
import com.music.player.model.Track
import java.text.Normalizer
import kotlin.math.abs

/**
 * Decides whether two catalog entries are the same song, so a Qobuz/YouTube track can be
 * swapped for the copy in the user's Jellyfin library (and vice versa).
 */
object TrackMatcher {
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val BRACKETED = Regex("[(\\[{][^)\\]}]*[)\\]}]")
    private val FEATURING = Regex("\\s+(feat\\.?|ft\\.?|featuring|with)\\s+.*$", RegexOption.IGNORE_CASE)
    private val VERSION_SUFFIX = Regex(
        "\\s+-\\s+.*\\b(remaster(ed)?|mono|stereo|single version|album version|radio edit|explicit|clean)\\b.*$",
        RegexOption.IGNORE_CASE,
    )
    private val ARTIST_SEPARATORS = Regex("\\s*(,|;|/|&|\\band\\b|\\bx\\b|\\+)\\s*", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val APOSTROPHES = Regex("['’‘`´]")
    private val TOPIC_SUFFIX = Regex("\\s+-\\s+Topic$", RegexOption.IGNORE_CASE)
    private val VARIANT_PARTS = Regex("[(\\[{]([^)\\]}]*)[)\\]}]|\\s+-\\s+([^()\\[\\]{}]+)$")
    private val CREDIT_PART = Regex("^(feat|ft|featuring|with|from|taken from|prod|produced by)\\b.*", RegexOption.IGNORE_CASE)

    /**
     * Words in a title's tags that don't make it a different recording: remasters, explicit or
     * clean, the album or single or radio version, and the "Official Audio" of YouTube uploads.
     */
    private val NEUTRAL_WORDS = setOf(
        "remaster", "remastered", "remasterd", "digital", "digitally", "deluxe", "edition", "expanded",
        "anniversary", "special", "mono", "stereo", "explicit", "clean", "dirty", "censored", "uncensored",
        "single", "album", "original", "version", "radio", "edit", "mix", "lp", "ep", "bonus", "track",
        "the", "a", "an", "and", "of", "official", "audio", "video", "music", "lyric", "lyrics", "visualizer",
        "hd", "hq", "4k", "24bit", "hi", "res", "mastered", "for", "itunes",
    )
    private const val DURATION_TOLERANCE_MS = 12_000L
    private const val RADIO_DURATION_TOLERANCE_MS = 45_000L

    fun normalize(text: String): String {
        val stripped = Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "")
        return stripped.lowercase()
            // "Fool's Gold" and "Fools Gold" are the same song; so are "Rock & Roll" and "Rock and Roll".
            .replace(APOSTROPHES, "")
            .replace("&", " and ")
            .replace(NON_ALNUM, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /** "Song (feat. X) [2011 Remaster] - Mono" -> "Song": for search boxes, which do their own normalization. */
    fun searchTitle(title: String): String =
        title.replace(BRACKETED, " ").replace(VERSION_SUFFIX, "").replace(FEATURING, "").replace(Regex("\\s+"), " ").trim()

    /** "Song (feat. X) [2011 Remaster] - Mono" -> "song" */
    fun normalizeTitle(title: String): String = normalize(searchTitle(title))

    /** Strips "feat." credits and YouTube's " - Topic" channel suffix. */
    fun cleanArtist(artist: String): String =
        artist.replace(TOPIC_SUFFIX, "").replace(BRACKETED, " ").replace(FEATURING, "").trim()

    /** Every individual artist credited in [artist], e.g. "A & B, C feat. D" -> [a, b, c]. */
    fun artistNames(artist: String): Set<String> {
        val cleaned = cleanArtist(artist)
        val parts = cleaned.split(ARTIST_SEPARATORS).map(::normalize).filter { it.isNotEmpty() }
        return (parts + normalize(cleaned)).filter { it.isNotEmpty() }.toSet()
    }

    /** The main credited artist: used when asking Lidarr about an artist. */
    fun primaryArtist(artist: String): String {
        val cleaned = cleanArtist(artist)
        return cleaned.split(Regex("\\s*[,;/]\\s*")).firstOrNull()?.trim().orEmpty().ifEmpty { cleaned }
    }

    fun isSameArtist(a: String, b: String): Boolean {
        val namesA = artistNames(a).map(::stripLeadingThe)
        val namesB = artistNames(b).map(::stripLeadingThe)
        return namesA.any { it in namesB }
    }

    /**
     * What sets a recording apart, from its title's tags: "Scarlett (garden version)" -> [garden],
     * "Song (Live at Wembley)" -> [live, at, wembley], "Song - 2011 Remaster" -> []. Featured
     * artists, soundtrack credits, years and words like "remastered" or "explicit" don't count.
     */
    fun variant(title: String): Set<String> =
        VARIANT_PARTS.findAll(title)
            .map { (it.groupValues[1].ifEmpty { it.groupValues[2] }).trim() }
            .filterNot { CREDIT_PART.matches(it) }
            .flatMap { normalize(it).split(' ') }
            .filter { it.isNotEmpty() && it !in NEUTRAL_WORDS && !it.all(Char::isDigit) }
            .toSet()

    /**
     * Whether two titles can be the same recording: both plain, or both tagged alike ("Live"
     * fits "Live at Wembley"). A garden version, live take, acoustic version or remix isn't the
     * original song.
     */
    fun sameVariant(a: String, b: String): Boolean {
        val va = variant(a)
        val vb = variant(b)
        return va == vb || (va.isNotEmpty() && vb.isNotEmpty() && (va.containsAll(vb) || vb.containsAll(va)))
    }

    fun matches(candidate: Track, wanted: Track): Boolean {
        if (normalizeTitle(candidate.title) != normalizeTitle(wanted.title)) return false
        if (!sameVariant(candidate.title, wanted.title)) return false
        if (!isSameArtist(candidate.artist, wanted.artist)) return false
        val d1 = candidate.durationMs
        val d2 = wanted.durationMs
        // Radio plays edits and trims cuts, so songs heard on SiriusXM get more slack.
        val tolerance = if (candidate.source == SourceType.SIRIUSXM || wanted.source == SourceType.SIRIUSXM) {
            RADIO_DURATION_TOLERANCE_MS
        } else {
            DURATION_TOLERANCE_MS
        }
        return d1 == null || d2 == null || abs(d1 - d2) <= tolerance
    }

    /** The best match for [wanted] among [candidates]: the same tags first, then the closest duration. */
    fun bestMatch(wanted: Track, candidates: List<Track>): Track? {
        val tags = variant(wanted.title)
        return candidates.filter { matches(it, wanted) }.minWithOrNull(
            compareBy<Track> { if (variant(it.title) == tags) 0 else 1 }.thenBy { candidate ->
                val d1 = candidate.durationMs
                val d2 = wanted.durationMs
                if (d1 == null || d2 == null) Long.MAX_VALUE / 2 else abs(d1 - d2)
            },
        )
    }

    /** A search query likely to find [track] on another service. */
    fun searchQuery(track: Track): String = "${primaryArtist(track.artist)} ${searchTitle(track.title)}".trim()

    private fun stripLeadingThe(name: String): String = name.removePrefix("the ")
}
