package com.music.tuner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/** How well a station comes in: [score] from 0 (static) to 1 (clear), and what that's made of. */
@Serializable
data class Signal(
    val score: Double,
    val detail: String,
    /** When it was measured (milliseconds since 1970). */
    val measuredAt: Long,
    /** Analog FM: playing in stereo. */
    val stereo: Boolean? = null,
)

/**
 * A station to listen to: an FM signal ([program] null), or one of the HD Radio programs on it
 * (HD1, HD2… as [program] 0, 1…), which are separate stations on the same frequency.
 */
@Serializable
data class Station(
    val id: String,
    val frequencyKhz: Int,
    val program: Int? = null,
    /** What to call it: the name the station sends, else its call letters, else its frequency. */
    val name: String,
    val callLetters: String? = null,
    /** The RDS station code (PI), e.g. "0x54A8". */
    val pi: String? = null,
    /** The kind of programme (RDS PTY, or HD Radio's program type), e.g. "Rock". */
    val genre: String? = null,
    val slogan: String? = null,
    /** HD Radio programs found on an FM signal (0 = HD1), when it has any. */
    val hdPrograms: List<Int> = emptyList(),
    /** The last time it was measured: at the scan, or while someone listened. */
    val signal: Signal? = null,
    /** Added by hand rather than found by a scan. */
    val manual: Boolean = false,
) {
    val isHd: Boolean get() = program != null

    /** e.g. "97.1". */
    val frequencyLabel: String get() = frequencyLabel(frequencyKhz)

    /** e.g. "97.1 FM" or "97.1 HD2". */
    val bandLabel: String get() = if (program == null) "$frequencyLabel FM" else "$frequencyLabel HD${program + 1}"

    companion object {
        fun frequencyLabel(khz: Int): String = if (khz % 100 == 0) String.format(Locale.ROOT, "%.1f", khz / 1000.0) else String.format(Locale.ROOT, "%.2f", khz / 1000.0)

        fun idOf(khz: Int, program: Int?): String = if (program == null) frequencyLabel(khz) else "${frequencyLabel(khz)}-hd${program + 1}"

        /** (frequency in kHz, program) of an id made by [idOf]. */
        fun parseId(id: String): Pair<Int, Int?>? {
            val match = Regex("""(\d{2,3}(?:\.\d{1,2})?)(?:-hd([1-8]))?""").matchEntire(id.lowercase(Locale.ROOT)) ?: return null
            val khz = (match.groupValues[1].toDouble() * 1000).let { Math.round(it).toInt() }
            return khz to match.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toInt() - 1 }
        }
    }
}

@Serializable
data class Lineup(
    val region: Region,
    val scannedAt: Long? = null,
    val stations: List<Station> = emptyList(),
)

/** The stations found, kept in the data folder so a restart doesn't need a new scan. */
class LineupStore(private val file: File, private val region: Region) {
    private val log = LoggerFactory.getLogger("Lineup")
    private var lineup: Lineup = load()

    @Synchronized
    fun get(): Lineup = lineup

    fun station(id: String): Station? = get().stations.firstOrNull { it.id == id }

    @Synchronized
    fun replace(stations: List<Station>, scannedAt: Long) {
        // Stations added by hand stay, unless the scan found them too.
        val found = stations.map { it.id }.toSet()
        val kept = lineup.stations.filter { it.manual && it.id !in found }
        save(Lineup(region, scannedAt, sorted(stations + kept)))
    }

    @Synchronized
    fun add(station: Station) {
        save(lineup.copy(stations = sorted(lineup.stations.filter { it.id != station.id } + station)))
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val before = lineup.stations.size
        save(lineup.copy(stations = lineup.stations.filter { it.id != id }))
        return lineup.stations.size != before
    }

    /** Remembers what a station was last heard as (its signal, and names learned while playing). */
    @Synchronized
    fun update(id: String, change: (Station) -> Station) {
        val current = lineup.stations.firstOrNull { it.id == id } ?: return
        val changed = change(current)
        if (changed != current) save(lineup.copy(stations = lineup.stations.map { if (it.id == id) changed else it }))
    }

    private fun sorted(stations: List<Station>) = stations.sortedWith(compareBy<Station> { it.frequencyKhz }.thenBy { it.program ?: -1 })

    private fun load(): Lineup {
        if (!file.exists()) return Lineup(region)
        return try {
            val saved = JSON.decodeFromString(Lineup.serializer(), file.readText())
            if (saved.region != region) {
                log.warn("The saved stations were found for another region ({}); scan again for {}", saved.region, region)
            }
            saved.copy(region = region)
        } catch (e: Exception) {
            log.warn("Couldn't read {} ({}); starting with no stations", file, e.message)
            Lineup(region)
        }
    }

    private fun save(new: Lineup) {
        lineup = new
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(JSON.encodeToString(Lineup.serializer(), new))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            log.warn("Couldn't save the stations to {}: {}", file, e.message)
        }
    }

    companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
