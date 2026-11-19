package com.music.tuner

import java.io.File
import java.util.Locale

/**
 * Where you are, for the FM band: the channels stations can be on, and how much treble
 * transmitters boost (de-emphasis undoes it).
 */
enum class Region(
    val label: String,
    /** The lowest and highest channel, in kHz. */
    val firstKhz: Int,
    val lastKhz: Int,
    val spacingKhz: Int,
    /** De-emphasis time constant, in microseconds. */
    val deemphasisUs: Int,
    /** RDS station codes are North American RBDS codes, which spell call letters. */
    val rbds: Boolean,
) {
    US("United States, Canada and the Americas (87.9 to 107.9 MHz, every 200 kHz)", 87_900, 107_900, 200, 75, true),
    OTHER("Everywhere else (87.5 to 108 MHz, every 100 kHz)", 87_500, 108_000, 100, 50, false),
    ;

    /** Every channel, in kHz. */
    val channelsKhz: List<Int> get() = (firstKhz..lastKhz step spacingKhz).toList()

    fun isChannel(khz: Int) = khz in firstKhz..lastKhz && (khz - firstKhz) % spacingKhz == 0

    companion object {
        fun parse(value: String?): Region = when (value?.trim()?.uppercase(Locale.ROOT)) {
            null, "", "US", "USA", "NA", "AMERICAS", "CA" -> US
            else -> OTHER
        }
    }
}

/** The programs the tuner runs (their names, or full paths). */
data class Tools(
    val rtlFm: String = "rtl_fm",
    val rtlSdr: String = "rtl_sdr",
    val rtlPower: String = "rtl_power",
    val rtlTest: String = "rtl_test",
    val redsea: String = "redsea",
    val nrsc5: String = "nrsc5",
    val ffmpeg: String = "ffmpeg",
)

/** Start-up settings, from environment variables (see tuner/README.md). */
data class TunerConfig(
    val dataDir: File,
    val host: String = "0.0.0.0",
    val port: Int = 5004,
    /** When set, every request needs it (as a bearer token, or `?token=` for players that can't send one). */
    val token: String? = null,
    val region: Region = Region.US,
    /** Dongles by index or serial number; empty: every one rtl_test finds (or just the first). */
    val devices: List<String> = emptyList(),
    /** Tuner gain in dB; null lets the dongle choose. */
    val gain: Double? = null,
    /** The dongle's frequency error, in parts per million. */
    val ppm: Int = 0,
    /** How long a tuner keeps playing after its last listener leaves (someone tuning straight back gets it at once). */
    val graceSeconds: Int = 20,
    /** The quality listeners get, in kbps (AAC). */
    val bitrateKbps: Int = 160,
    /** The tuner's name, as the music server and HDHomeRun apps show it. */
    val name: String = "SuperAwesomeMagicalMusic Tuner",
    /** Where nrsc5 writes station logos and album art while a station plays. */
    val workDir: File = File(System.getProperty("java.io.tmpdir"), "samm-tuner"),
    val tools: Tools = Tools(),
) {
    val lineupFile: File get() = File(dataDir, "lineup.json")
    val deviceIdFile: File get() = File(dataDir, "device-id")

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): TunerConfig {
            fun text(key: String) = env[key]?.trim()?.takeIf { it.isNotEmpty() }
            return TunerConfig(
                dataDir = File(text("TUNER_DATA_DIR") ?: "/data"),
                host = text("TUNER_HOST") ?: "0.0.0.0",
                port = text("TUNER_PORT")?.toIntOrNull() ?: 5004,
                token = text("TUNER_TOKEN"),
                region = Region.parse(text("TUNER_REGION")),
                devices = text("TUNER_DEVICES")?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                gain = text("TUNER_GAIN")?.takeIf { it.lowercase() != "auto" }?.toDoubleOrNull(),
                ppm = text("TUNER_PPM")?.toIntOrNull() ?: 0,
                graceSeconds = text("TUNER_GRACE_SECONDS")?.toIntOrNull()?.coerceIn(0, 3600) ?: 20,
                bitrateKbps = text("TUNER_BITRATE")?.toIntOrNull()?.coerceIn(48, 320) ?: 160,
                name = text("TUNER_NAME") ?: "SuperAwesomeMagicalMusic Tuner",
                workDir = text("TUNER_WORK_DIR")?.let(::File) ?: File(System.getProperty("java.io.tmpdir"), "samm-tuner"),
                tools = Tools(
                    rtlFm = text("TUNER_RTL_FM") ?: "rtl_fm",
                    rtlSdr = text("TUNER_RTL_SDR") ?: "rtl_sdr",
                    rtlPower = text("TUNER_RTL_POWER") ?: "rtl_power",
                    rtlTest = text("TUNER_RTL_TEST") ?: "rtl_test",
                    redsea = text("TUNER_REDSEA") ?: "redsea",
                    nrsc5 = text("TUNER_NRSC5") ?: "nrsc5",
                    ffmpeg = text("TUNER_FFMPEG") ?: "ffmpeg",
                ),
            )
        }
    }
}
