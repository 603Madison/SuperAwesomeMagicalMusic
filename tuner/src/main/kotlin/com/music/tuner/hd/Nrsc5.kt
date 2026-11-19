package com.music.tuner.hd

import java.io.File
import java.util.Locale

/** One thing nrsc5 logged (github.com/theori-io/nrsc5, src/main.c), without its time stamp. */
sealed interface Nrsc5Event {
    data object Synchronized : Nrsc5Event
    data object LostSync : Nrsc5Event
    data class Mer(val lower: Double, val upper: Double) : Nrsc5Event
    data class Ber(val ber: Double, val average: Double) : Nrsc5Event
    /** Of the last 32 audio packets, [errors] failed their check. */
    data class AudioErrors(val errors: Int) : Nrsc5Event
    data class StationName(val name: String) : Nrsc5Event
    data class Slogan(val slogan: String) : Nrsc5Event
    data class Title(val title: String) : Nrsc5Event
    data class Artist(val artist: String) : Nrsc5Event
    data class Album(val album: String) : Nrsc5Event
    data class Genre(val genre: String) : Nrsc5Event
    /** An ID3 XHDR: [param] 0 shows the image [lot] as the cover, 1 goes back to the logo. */
    data class Xhdr(val param: Int, val mime: Long, val lot: Int) : Nrsc5Event
    data class SigService(val audio: Boolean, val number: Int, val name: String) : Nrsc5Event
    data class SigData(val port: Int, val mime: Long) : Nrsc5Event
    data class LotFile(val port: Int, val lot: Int, val name: String, val size: Int, val mime: Long) : Nrsc5Event
    /** An audio program the station carries (0 = HD1). */
    data class AudioProgram(val program: Int, val public: Boolean, val type: String) : Nrsc5Event
    data class AudioBitrate(val kbps: Double) : Nrsc5Event
    /** nrsc5 couldn't start (no device, bad arguments…). */
    data class Fatal(val message: String) : Nrsc5Event
}

object Nrsc5Log {
    private val TIME = Regex("""^\d{2}:\d{2}:\d{2} """)
    private val MER = Regex("""^MER: (-?[\d.]+) dB \(lower\), (-?[\d.]+) dB \(upper\)""")
    private val BER = Regex("""^BER: ([\d.eE+-]+), avg: ([\d.eE+-]+)""")
    private val CRC = Regex("""^Audio packet CRC mismatches: (\d+)""")
    private val XHDR = Regex("""^XHDR: (-?\d+) ([0-9A-Fa-f]{1,8}) (-?\d+)""")
    private val SIG = Regex("""^SIG Service: type=(audio|data) number=(\d+) name=(.*)$""")
    private val SIG_DATA = Regex("""^\s*Data component: id=\d+ port=([0-9A-Fa-f]+) .*mime=([0-9A-Fa-f]{1,8})""")
    private val LOT = Regex("""^LOT file: port=([0-9A-Fa-f]+) lot=(\d+) name=(.*?) size=(\d+) mime=([0-9A-Fa-f]{1,8})""")
    // nrsc5 logs a program from its service descriptor ("Audio program") and its audio service ("Audio service").
    private val PROGRAM = Regex("""^Audio (?:program|service) (\d+): (public|restricted), type: (.*?), (?:sound experience|codec)""")
    private val BITRATE = Regex("""^Audio bit rate: ([\d.]+) kbps""")
    private val FATAL = listOf("Open device failed", "Open IQ", "Unable to open audio device", "Set frequency failed", "Invalid", "Connection failed")

    fun parse(line: String): Nrsc5Event? {
        val text = TIME.replaceFirst(line.trimEnd('\r', '\n'), "")
        fun after(prefix: String) = text.removePrefix(prefix).trim().takeIf { text.startsWith(prefix) && it.isNotEmpty() }
        return when {
            text == "Synchronized" -> Nrsc5Event.Synchronized
            text == "Lost synchronization" -> Nrsc5Event.LostSync
            text.startsWith("MER: ") -> MER.find(text)?.let { Nrsc5Event.Mer(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            text.startsWith("BER: ") -> BER.find(text)?.let { m -> m.groupValues[1].toDoubleOrNull()?.let { Nrsc5Event.Ber(it, m.groupValues[2].toDoubleOrNull() ?: it) } }
            text.startsWith("Audio packet CRC") -> CRC.find(text)?.let { Nrsc5Event.AudioErrors(it.groupValues[1].toInt()) }
            text.startsWith("Station name: ") -> after("Station name: ")?.let { Nrsc5Event.StationName(it) }
            text.startsWith("Slogan: ") -> after("Slogan: ")?.let { Nrsc5Event.Slogan(it) }
            text.startsWith("Title: ") -> after("Title: ")?.let { Nrsc5Event.Title(it) }
            text.startsWith("Artist: ") -> after("Artist: ")?.let { Nrsc5Event.Artist(it) }
            text.startsWith("Album: ") -> after("Album: ")?.let { Nrsc5Event.Album(it) }
            text.startsWith("Genre: ") -> after("Genre: ")?.let { Nrsc5Event.Genre(it) }
            text.startsWith("XHDR: ") -> XHDR.find(text)?.let { Nrsc5Event.Xhdr(it.groupValues[1].toInt(), it.groupValues[2].toLong(16), it.groupValues[3].toInt()) }
            text.startsWith("SIG Service: ") -> SIG.find(text)?.let { Nrsc5Event.SigService(it.groupValues[1] == "audio", it.groupValues[2].toInt(), it.groupValues[3].trim()) }
            text.trimStart().startsWith("Data component: ") -> SIG_DATA.find(text)?.let { Nrsc5Event.SigData(it.groupValues[1].toInt(16), it.groupValues[2].toLong(16)) }
            text.startsWith("LOT file: ") -> LOT.find(text)?.let {
                Nrsc5Event.LotFile(it.groupValues[1].toInt(16), it.groupValues[2].toInt(), it.groupValues[3], it.groupValues[4].toInt(), it.groupValues[5].toLong(16))
            }
            text.startsWith("Audio program ") || text.startsWith("Audio service ") -> PROGRAM.find(text)?.let { Nrsc5Event.AudioProgram(it.groupValues[1].toInt(), it.groupValues[2] == "public", it.groupValues[3].trim()) }
            text.startsWith("Audio bit rate: ") -> BITRATE.find(text)?.let { Nrsc5Event.AudioBitrate(it.groupValues[1].toDouble()) }
            FATAL.any { text.startsWith(it) } -> Nrsc5Event.Fatal(text)
            else -> null
        }
    }

    const val MIME_PRIMARY_IMAGE = 0xBE4B7536L
    const val MIME_STATION_LOGO = 0xD9C72536L
}

/** What's known about one HD Radio program, from nrsc5's log as it plays. */
data class HdInfo(
    val synced: Boolean = false,
    val stationName: String? = null,
    val slogan: String? = null,
    /** The program's own name in the station's service list (e.g. "WXYZ HD2"), when it gives one. */
    val programName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val mer: Double? = null,
    val ber: Double? = null,
    /** Failed audio packets in the last 32. */
    val audioErrors: Int = 0,
    val bitrateKbps: Double? = null,
    /** Programs the station carries (0 = HD1) and their types. */
    val programs: Map<Int, String> = emptyMap(),
    val logo: File? = null,
    val art: File? = null,
    val fatal: String? = null,
    val updatedAt: Long = 0,
)

/**
 * Follows nrsc5's log for [program] (0 = HD1). nrsc5 writes the station's files (logos and album
 * art) to [filesDir] as "<lot>_<name>"; only files it announced, inside that folder, are used.
 */
class HdDecoder(private val program: Int, private val filesDir: File?, private val clock: () -> Long = System::currentTimeMillis) {
    private var service: Int? = null
    private val logoPorts = HashSet<Int>()
    private val artPorts = HashSet<Int>()
    private val lots = HashMap<Int, File>()
    private var wantedLot: Int? = null
    private var newSong = false

    @Volatile
    var info = HdInfo()
        private set

    @Synchronized
    fun accept(line: String): Nrsc5Event? {
        val event = Nrsc5Log.parse(line) ?: return null
        var next = info
        when (event) {
            Nrsc5Event.Synchronized -> next = next.copy(synced = true)
            Nrsc5Event.LostSync -> next = next.copy(synced = false)
            is Nrsc5Event.Mer -> next = next.copy(mer = minOf(event.lower, event.upper))
            is Nrsc5Event.Ber -> next = next.copy(ber = event.ber)
            is Nrsc5Event.AudioErrors -> next = next.copy(audioErrors = event.errors)
            is Nrsc5Event.StationName -> next = next.copy(stationName = event.name)
            is Nrsc5Event.Slogan -> next = next.copy(slogan = event.slogan)
            // An ID3 tag comes as Title, Artist, Album… lines together: a title starts a new song.
            is Nrsc5Event.Title -> {
                if (event.title != next.title) next = next.copy(artist = null, album = null)
                next = next.copy(title = event.title)
                newSong = true
            }
            is Nrsc5Event.Artist -> next = next.copy(artist = event.artist)
            is Nrsc5Event.Album -> next = next.copy(album = event.album)
            is Nrsc5Event.Genre -> next = next.copy(genre = event.genre)
            is Nrsc5Event.Xhdr -> when (event.param) {
                0 -> {
                    wantedLot = event.lot.takeIf { it >= 0 }
                    next = next.copy(art = wantedLot?.let { lots[it] })
                }
                // Back to the station's logo: the song has no cover.
                1 -> {
                    wantedLot = null
                    next = next.copy(art = null)
                }
            }
            is Nrsc5Event.SigService -> {
                service = if (event.audio) event.number else null
                // Audio services are numbered from 1: HD1 is service 1.
                if (event.audio && event.number == program + 1) next = next.copy(programName = event.name.takeIf { it.isNotBlank() })
            }
            is Nrsc5Event.SigData -> if (service == program + 1) {
                when (event.mime) {
                    Nrsc5Log.MIME_STATION_LOGO -> logoPorts += event.port
                    Nrsc5Log.MIME_PRIMARY_IMAGE -> artPorts += event.port
                }
            }
            is Nrsc5Event.LotFile -> {
                val file = fileOf(event)
                if (file != null && event.port in logoPorts) next = next.copy(logo = file)
                if (file != null && event.port in artPorts) {
                    lots[event.lot] = file
                    if (lots.size > 50) lots.remove(lots.keys.min())
                    // The cover the tag asked for, or (with no tag naming one) the newest.
                    if (wantedLot == event.lot || (wantedLot == null && newSong)) next = next.copy(art = file)
                }
            }
            // Restricted programs are paid services nobody can decode without a key.
            is Nrsc5Event.AudioProgram -> if (event.public) next = next.copy(programs = next.programs + (event.program to event.type))
            is Nrsc5Event.AudioBitrate -> next = next.copy(bitrateKbps = event.kbps)
            is Nrsc5Event.Fatal -> next = next.copy(fatal = event.message)
        }
        info = next.copy(updatedAt = clock())
        return event
    }

    private fun fileOf(event: Nrsc5Event.LotFile): File? {
        val dir = filesDir ?: return null
        val name = "${event.lot}_${event.name}"
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val file = File(dir, name)
        return file.takeIf { it.canonicalFile.parentFile == dir.canonicalFile }
    }

    companion object {
        /** A name for display: "WXYZ-FM" is shown as it is; an empty name isn't. */
        fun cleanName(name: String?): String? = name?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > 64) it.take(64) else it }

        fun programLabel(program: Int) = String.format(Locale.ROOT, "HD%d", program + 1)
    }
}
