package com.music.tuner.scan

import com.music.tuner.LineupStore
import com.music.tuner.Signal
import com.music.tuner.Station
import com.music.tuner.TunerConfig
import com.music.tuner.proc.Child
import com.music.tuner.radio.Commands
import com.music.tuner.radio.FmReceiver
import com.music.tuner.radio.HdReceiver
import com.music.tuner.radio.Tuner
import com.music.tuner.radio.TunerPool
import com.music.tuner.rds.CallLetters
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

@Serializable
data class ScanProgress(
    val running: Boolean = false,
    /** sweeping (looking across the band), checking (listening to each station found), done, failed or cancelled. */
    val phase: String = "idle",
    val message: String = "No scan yet",
    /** The frequency being checked. */
    val current: String? = null,
    val checked: Int = 0,
    val total: Int = 0,
    val found: Int = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

/** What listening to one frequency for a few seconds found. */
data class Probe(
    val khz: Int,
    val name: String? = null,
    val callLetters: String? = null,
    val pi: String? = null,
    val genre: String? = null,
    val rdsGroups: Int = 0,
    val signal: Signal? = null,
    /** HD Radio: the station's name, and its programs (0 = HD1) and their types. */
    val hdName: String? = null,
    val hdPrograms: Map<Int, String> = emptyMap(),
    val hdSignal: Signal? = null,
    val slogan: String? = null,
)

/** How the scan listens: a sweep of the band, then each candidate. Tests swap in their own. */
interface ScanRadio {
    fun sweep(device: String): List<PowerBin>
    fun probeFm(device: String, khz: Int, stop: () -> Boolean): Probe
    fun probeHd(device: String, khz: Int, stop: () -> Boolean): Probe
}

/**
 * Finds the stations: an rtl_power sweep of the FM band for signals standing out of the noise,
 * then a few seconds on each, for its RDS name and code, how clean it is and whether it carries
 * HD Radio (and which programs). The lineup is saved when it's done.
 */
class Scanner(
    private val config: TunerConfig,
    private val pool: TunerPool,
    private val lineup: LineupStore,
    private val radio: ScanRadio = RealScanRadio(config),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger("Scan")

    @Volatile
    var progress = ScanProgress()
        private set

    @Volatile
    private var cancelled = false
    private var thread: Thread? = null

    /** Starts a scan on a free tuner (or says all are busy). */
    @Synchronized
    fun start(): ScanProgress {
        if (progress.running) return progress
        val tuner = pool.reserve()
        cancelled = false
        progress = ScanProgress(running = true, phase = "sweeping", message = "Looking across the FM band", startedAt = clock())
        thread = Thread({ run(tuner) }, "scan").apply { isDaemon = true; start() }
        return progress
    }

    fun cancel() {
        cancelled = true
    }

    fun await(ms: Long) = thread?.join(ms)

    private fun run(tuner: Tuner) {
        try {
            val bins = radio.sweep(tuner.device)
            val carriers = RtlPower.carriers(bins, config.region).sortedBy { it.khz }
            log.info("The sweep found {} signals to check", carriers.size)
            if (bins.isEmpty()) {
                finish("failed", "The sweep found nothing: is the dongle working, with an antenna?", 0)
                return
            }
            val stations = mutableListOf<Station>()
            carriers.forEachIndexed { i, carrier ->
                if (cancelled) return@forEachIndexed
                val label = Station.frequencyLabel(carrier.khz)
                progress = progress.copy(phase = "checking", message = "Listening to $label", current = label, checked = i, total = carriers.size, found = stations.size)
                stations += check(tuner, carrier)
            }
            if (cancelled) {
                finish("cancelled", "Cancelled; the stations found before stay as they were", stations.size)
                return
            }
            lineup.replace(stations, clock())
            val analog = stations.count { !it.isHd }
            val hd = stations.count { it.isHd }
            finish("done", "Found $analog station${if (analog == 1) "" else "s"}" + if (hd > 0) " and $hd HD Radio program${if (hd == 1) "" else "s"}" else "", stations.size)
        } catch (e: Exception) {
            log.warn("The scan failed", e)
            finish("failed", "The scan failed: ${e.message ?: e}", 0)
        } finally {
            pool.release(tuner)
        }
    }

    private fun finish(phase: String, message: String, found: Int) {
        log.info("Scan {}: {}", phase, message)
        progress = progress.copy(running = false, phase = phase, message = message, current = null, found = found, checked = progress.total, finishedAt = clock())
    }

    private fun check(tuner: Tuner, carrier: Carrier): List<Station> {
        val fm = radio.probeFm(tuner.device, carrier.khz) { cancelled }
        val hd = if (config.region.rbds && !cancelled) radio.probeHd(tuner.device, carrier.khz) { cancelled } else Probe(carrier.khz)
        val heard = fm.rdsGroups > 0 || (fm.signal?.score ?: 0.0) >= 0.2 || hd.hdPrograms.isNotEmpty()
        if (!heard) {
            log.info("{}: nothing clear enough to keep", Station.frequencyLabel(carrier.khz))
            return emptyList()
        }
        val callLetters = fm.callLetters ?: CallLetters.fromName(hd.hdName)
        val label = Station.frequencyLabel(carrier.khz)
        val analog = Station(
            id = Station.idOf(carrier.khz, null),
            frequencyKhz = carrier.khz,
            name = fm.name ?: callLetters ?: "$label FM",
            callLetters = callLetters,
            pi = fm.pi,
            genre = fm.genre,
            slogan = hd.slogan,
            hdPrograms = hd.hdPrograms.keys.sorted(),
            signal = fm.signal,
        )
        val programs = hd.hdPrograms.toSortedMap().map { (program, type) ->
            Station(
                id = Station.idOf(carrier.khz, program),
                frequencyKhz = carrier.khz,
                program = program,
                name = "${callLetters ?: hd.hdName ?: label} HD${program + 1}",
                callLetters = callLetters,
                pi = fm.pi,
                genre = type.takeIf { it.isNotBlank() && it != "None" },
                slogan = hd.slogan,
                signal = hd.hdSignal,
            )
        }
        log.info("{}: {}{}", label, analog.name, if (programs.isEmpty()) "" else " with HD Radio (${programs.joinToString { "HD${(it.program ?: 0) + 1}" }})")
        return listOf(analog) + programs
    }
}

/** The real thing: rtl_power, then rtl_fm with redsea, and rtl_sdr with nrsc5. */
class RealScanRadio(private val config: TunerConfig) : ScanRadio {
    override fun sweep(device: String): List<PowerBin> {
        val child = Child.start("rtl_power", Commands.rtlPower(config.tools, device, config.region, config.gain, config.ppm, 4))
        return try {
            val lines = child.stdout.bufferedReader().readLines()
            child.process.waitFor(30, TimeUnit.SECONDS)
            RtlPower.parse(lines.asSequence())
        } finally {
            child.stop()
        }
    }

    override fun probeFm(device: String, khz: Int, stop: () -> Boolean): Probe {
        val receiver = FmReceiver(device, khz, config, encode = false).start()
        try {
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            while (!stop() && elapsed() < 9_000) {
                Thread.sleep(250)
                val info = receiver.rds.info
                val measured = receiver.meter.measurements >= 8
                // Name and code in, or (after a few seconds) clearly no RDS: enough.
                if (measured && info.pi != null && receiver.rds.steadyName() != null) break
                if (measured && elapsed() > 4_000 && info.groups == 0) break
            }
            val info = receiver.rds.info
            return Probe(
                khz = khz,
                name = receiver.rds.steadyName(),
                callLetters = info.callLetters,
                pi = info.pi?.let { String.format("0x%04X", it) },
                genre = info.genre,
                rdsGroups = info.groups,
                signal = receiver.signal(),
            )
        } finally {
            receiver.close()
        }
    }

    override fun probeHd(device: String, khz: Int, stop: () -> Boolean): Probe {
        val receiver = HdReceiver(device, khz, config, encode = false).start()
        try {
            val program = receiver.program(0)
            val started = System.nanoTime()
            var syncedAt: Long? = null
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            while (!stop()) {
                Thread.sleep(250)
                val info = program.decoder.info
                if (info.synced && syncedAt == null) syncedAt = elapsed()
                // Not found in 10 seconds: no HD Radio here.
                if (syncedAt == null && elapsed() > 10_000) break
                // Found: a few seconds more for its name and the list of its programs.
                if (syncedAt != null && (elapsed() - syncedAt > 8_000 || (info.programs.isNotEmpty() && info.stationName != null && elapsed() - syncedAt > 2_000))) break
                if (info.fatal != null) break
            }
            val info = program.decoder.info
            if (syncedAt == null) return Probe(khz)
            return Probe(
                khz = khz,
                hdName = info.stationName,
                hdPrograms = info.programs.ifEmpty { mapOf(0 to "") },
                hdSignal = program.signal(),
                slogan = info.slogan,
            )
        } finally {
            receiver.close()
        }
    }
}
