package com.music.server.tuner

import com.music.player.CoreLog
import com.music.player.sources.radio.RadioStation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a station on the tuner and one from the internet directory are the same station.
 * In the Americas the tuner knows call letters (from RDS, or HD Radio's station name), and
 * internet listings nearly always carry them ("WXYZ-FM", "WXYZ 97.1"); elsewhere it's the name
 * the station sends (RDS PS) with the frequency.
 */
object StationMatch {
    /** Sure enough to treat them as one. */
    const val SAME = 60

    private val HD_TAG = Regex("^HD([1-8])$")

    /** How sure (0 to 100) that [internet] is [tuner]; 0 when it isn't (or can't be relayed as one). */
    fun score(tuner: TunerStation, internet: RadioStation, region: String): Int {
        // HLS streams play through their own path in the players; only Icecast-style ones blend.
        if (internet.isHls) return 0
        if (region == "US" && internet.countryCode.isNotBlank() && internet.countryCode.uppercase(Locale.ROOT) !in AMERICAS) return 0
        val words = words(internet.name) + words(internet.tags)
        // HD2 matches only an HD2 stream; the main station (and HD1, its digital twin) no other HD program's.
        val internetHd = words.firstNotNullOfOrNull { HD_TAG.matchEntire(it)?.groupValues?.get(1)?.toInt() }
        val tunerHd = tuner.hdProgram?.takeIf { it >= 2 }
        if (tunerHd != null && internetHd != tunerHd) return 0
        if (tunerHd == null && internetHd != null && internetHd != 1) return 0

        val callLetters = tuner.callLetters?.uppercase(Locale.ROOT)
        if (callLetters != null && (callLetters in words || "${callLetters}FM" in words)) return 100
        // A station listed with call letters that aren't these is another station (the same
        // frequency in another town). A word of the station's own name ("KISS 108") isn't that.
        val ownWords = words(tuner.name)
        val otherCallLetters = callLetters != null &&
            words.any { it.length == 4 && it[0] in "KW" && it.all { c -> c in 'A'..'Z' } && it != callLetters && it !in COMMON_WORDS && it !in ownWords }

        val frequency = Regex("(?<![\\d.])${Regex.escape(tuner.frequency)}(?![\\d])").containsMatchIn(internet.name)
        val name = normal(tuner.name.replace(Regex("(?i)\\bHD[1-8]\\b"), ""))
        val internetName = normal(internet.name)
        val sameName = name.length >= 4 && internetName.length >= 4 && (internetName.contains(name) || name.contains(internetName))
        val sharedWord = (words(tuner.name) - COMMON_WORDS).any { it.length >= 3 && it in words }
        return when {
            otherCallLetters -> 0
            sameName && frequency -> 85
            frequency && sharedWord -> 70
            sameName -> 60
            else -> 0
        }
    }

    /** What to search the directory for to find [tuner]'s internet stream. */
    fun query(tuner: TunerStation): String? =
        tuner.callLetters?.takeIf { it.length in 3..4 }
            ?: tuner.name.replace(Regex("(?i)\\bHD[1-8]\\b"), "").replace(Regex("\\b\\d{2,3}([.,]\\d)?\\b\\s*(FM)?"), " ").trim().takeIf { it.length >= 3 }

    /** The best of [candidates] for [tuner] (most sure, then most listened to), if any is sure enough. */
    fun best(tuner: TunerStation, candidates: List<RadioStation>, region: String): RadioStation? =
        candidates.map { it to score(tuner, it, region) }
            .filter { it.second >= SAME }
            .maxWithOrNull(compareBy<Pair<RadioStation, Int>> { it.second }.thenBy { it.first.clickCount }.thenBy { it.first.bitrate })
            ?.first

    private fun words(text: String): Set<String> = text.uppercase(Locale.ROOT).split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }.toSet()

    private fun normal(text: String) = text.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val AMERICAS = setOf("US", "CA", "MX", "PR")

    /** Words in many stations' names, which say nothing about which one. */
    private val COMMON_WORDS = setOf("FM", "AM", "RADIO", "THE", "HD", "NEWS", "MUSIC", "ROCK", "JAZZ", "LIVE", "STREAM", "PUBLIC", "STATION", "HITS", "CLASSIC", "COUNTRY", "KIDS", "WEST", "EAST")
}

/**
 * Each tuner station's internet stream (or that it has none), looked up in the directory once and
 * remembered for half a day. Lookups run in the background: a list of stations shows at once and
 * merges what's been found.
 */
class Counterparts(
    private val scope: CoroutineScope,
    @Volatile var search: suspend (query: String, countryCode: String?) -> List<RadioStation>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val at: Long) {
        @Volatile
        var done = false

        @Volatile
        var result: RadioStation? = null
        var found: Deferred<RadioStation?>? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(tuner: TunerStation) = "${tuner.callLetters ?: tuner.name}|${tuner.frequency}|${tuner.hdProgram ?: 0}"

    private fun entry(tuner: TunerStation, region: String): Entry? {
        val query = StationMatch.query(tuner) ?: return null
        val key = key(tuner)
        entries[key]?.takeIf { clock() - it.at < KEEP_MS }?.let { return it }
        if (entries.size > 2_000) entries.clear()
        val entry = Entry(clock())
        entries[key] = entry
        entry.found = scope.async {
            try {
                val country = if (region == "US") "US" else null
                StationMatch.best(tuner, search(query, country), region).also { entry.result = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CoreLog.i(TAG, "Couldn't look for ${tuner.name} online: ${e.message}")
                null
            } finally {
                entry.done = true
            }
        }
        return entry
    }

    /** What's known now (starting a lookup if nothing is). */
    fun known(tuner: TunerStation, region: String): RadioStation? {
        val entry = entry(tuner, region) ?: return null
        return if (entry.done) entry.result else null
    }

    /** Waits up to [waitMs] for the answer. */
    suspend fun find(tuner: TunerStation, region: String, waitMs: Long = 4_000): RadioStation? {
        val entry = entry(tuner, region) ?: return null
        if (entry.done) return entry.result
        return withTimeoutOrNull(waitMs) { runCatching { entry.found?.await() }.getOrNull() }
    }

    /** Remembers [internet] as [tuner]'s stream (seen in a search, so no lookup is needed). */
    fun learn(tuner: TunerStation, internet: RadioStation) {
        entries[key(tuner)] = Entry(clock()).apply {
            result = internet
            done = true
        }
    }

    private companion object {
        const val TAG = "Tuner"
        const val KEEP_MS = 12L * 3600 * 1000
    }
}
