package com.music.tuner.rds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What one line of redsea's output (one RDS group, as JSON) says. */
data class RdsGroup(
    val pi: Int? = null,
    val ps: String? = null,
    val radiotext: String? = null,
    val progType: String? = null,
    /** Block error rate in percent (with redsea's `--bler`). */
    val bler: Int? = null,
    /** Call letters redsea worked out (with `--rbds`). */
    val callsign: String? = null,
    /** RadioText+: the song's title and artist (and album), tagged in the RadioText. */
    val rtPlus: Map<String, String> = emptyMap(),
    val itemRunning: Boolean? = null,
    val itemToggle: Int? = null,
)

/** Reads redsea's JSON lines (github.com/windytan/redsea). */
object Redsea {
    private val json = Json { ignoreUnknownKeys = true }

    /** One line, or null if it isn't a group (redsea also prints warnings). */
    fun parse(line: String): RdsGroup? {
        val text = line.trim()
        if (!text.startsWith("{")) return null
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        val plus = obj["radiotext_plus"] as? JsonObject
        val tags = (plus?.get("tags") as? JsonArray).orEmpty().mapNotNull { tag ->
            val o = tag as? JsonObject ?: return@mapNotNull null
            val type = (o["content-type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val data = (o["data"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            type to data
        }.toMap()
        return RdsGroup(
            pi = str("pi")?.let(CallLetters::parsePi),
            ps = str("ps"),
            radiotext = str("radiotext"),
            progType = str("prog_type"),
            bler = (obj["bler"] as? JsonPrimitive)?.intOrNull,
            callsign = str("callsign"),
            rtPlus = tags,
            itemRunning = (plus?.get("item_running") as? JsonPrimitive)?.booleanOrNull,
            itemToggle = (plus?.get("item_toggle") as? JsonPrimitive)?.intOrNull,
        )
    }
}

/** What's known about an FM station from its RDS, as it builds up. */
data class RdsInfo(
    val pi: Int? = null,
    val callLetters: String? = null,
    /** The station's name: the programme service name it sends most often (some scroll text through it). */
    val name: String? = null,
    val radiotext: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    /** Block error rate, percent (averaged by redsea over the last groups). */
    val bler: Int? = null,
    val groups: Int = 0,
    val updatedAt: Long = 0,
)

/**
 * Builds up an FM station's RDS from redsea's groups. [rbds]: North American codes, so the PI
 * code spells the call letters.
 */
class RdsDecoder(private val rbds: Boolean, private val clock: () -> Long = System::currentTimeMillis) {
    private val psSeen = LinkedHashMap<String, Int>()
    private var toggle: Int? = null

    @Volatile
    var info = RdsInfo()
        private set

    @Synchronized
    fun accept(line: String): Boolean {
        val group = Redsea.parse(line) ?: return false
        var next = info.copy(groups = info.groups + 1, updatedAt = clock())
        group.pi?.let { pi ->
            next = next.copy(pi = pi, callLetters = if (rbds) group.callsign?.takeIf { it.length in 3..4 } ?: CallLetters.of(pi) else null)
        }
        group.ps?.trim()?.takeIf { it.isNotEmpty() }?.let { ps ->
            psSeen[ps] = (psSeen[ps] ?: 0) + 1
            // Remember the most frequent few; a scrolling PS sends a new one every few seconds.
            if (psSeen.size > 40) psSeen.remove(psSeen.minByOrNull { it.value }!!.key)
            next = next.copy(name = psSeen.maxByOrNull { it.value }!!.key)
        }
        group.radiotext?.trim()?.takeIf { it.isNotEmpty() }?.let { next = next.copy(radiotext = it) }
        group.progType?.takeIf { it.isNotBlank() && it != "No PTY" && it != "None" }?.let { next = next.copy(genre = it) }
        group.bler?.let { next = next.copy(bler = it.coerceIn(0, 100)) }
        if (group.itemToggle != null && group.itemToggle != toggle) {
            // A new item (song) started: what was tagged belongs to the one before.
            if (toggle != null) next = next.copy(title = null, artist = null, album = null)
            toggle = group.itemToggle
        }
        if (group.itemRunning == false) next = next.copy(title = null, artist = null, album = null)
        if (group.rtPlus.isNotEmpty()) {
            next = next.copy(
                title = group.rtPlus["item.title"] ?: next.title,
                artist = group.rtPlus["item.artist"] ?: group.rtPlus["item.band"] ?: next.artist,
                album = group.rtPlus["item.album"] ?: next.album,
            )
        }
        info = next
        return true
    }

    /** The station's name when it sends PS often enough to trust (not one-off scrolling text). */
    @Synchronized
    fun steadyName(minimum: Int = 2): String? = psSeen.maxByOrNull { it.value }?.takeIf { it.value >= minimum }?.key
}
