package com.music.tuner.radio

import com.music.tuner.Signal
import com.music.tuner.Station
import com.music.tuner.TunerConfig
import com.music.tuner.audio.AdtsSplitter
import com.music.tuner.audio.Broadcast
import com.music.tuner.dsp.FmQuality
import com.music.tuner.dsp.FmStereoDecoder
import com.music.tuner.dsp.SignalMeter
import com.music.tuner.hd.HdDecoder
import com.music.tuner.hd.HdInfo
import com.music.tuner.hd.HdQuality
import com.music.tuner.proc.Child
import com.music.tuner.proc.Feeder
import com.music.tuner.proc.Supervised
import com.music.tuner.rds.CallLetters
import com.music.tuner.rds.RdsDecoder
import com.music.tuner.rds.RdsInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** What a station is playing now, and how well it comes in. */
data class LiveInfo(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val radiotext: String? = null,
    /** The name the station sends (RDS PS, or HD Radio's station name). */
    val stationName: String? = null,
    /** The HD Radio program's own name, when the station gives one. */
    val programName: String? = null,
    val slogan: String? = null,
    val callLetters: String? = null,
    val pi: String? = null,
    val genre: String? = null,
    val logo: File? = null,
    val art: File? = null,
    val signal: Signal? = null,
    /** HD Radio programs on the frequency (0 = HD1), with their types. */
    val programs: Map<Int, String> = emptyMap(),
    /** Something's wrong (the dongle can't be opened…). */
    val error: String? = null,
)

/** One station's sound, while it plays: its broadcast to listeners and what's on. */
interface StationAudio {
    val broadcast: Broadcast
    fun live(): LiveInfo
}

/** What a dongle is tuned to: one FM signal, or one HD Radio frequency (all its programs). */
data class Tuning(val khz: Int, val hd: Boolean) {
    override fun toString() = "${Station.frequencyLabel(khz)} ${if (hd) "HD" else "FM"}"

    companion object {
        fun of(station: Station) = Tuning(station.frequencyKhz, station.isHd)
    }
}

/** A dongle's work while it's tuned: started by [ReceiverFactory], stopped with [close]. */
interface Receiver : AutoCloseable {
    val tuning: Tuning

    /** [station]'s audio (starting its decoder if it's an HD program not playing yet). */
    fun audio(station: Station): StationAudio

    /** Stops [station]'s decoder (other programs on the frequency carry on). */
    fun release(station: Station)

    /** Something's wrong with the dongle, in words. */
    val error: String?
}

fun interface ReceiverFactory {
    fun open(device: String, tuning: Tuning, encode: Boolean): Receiver
}

class RealReceivers(private val config: TunerConfig) : ReceiverFactory {
    override fun open(device: String, tuning: Tuning, encode: Boolean): Receiver =
        if (tuning.hd) HdReceiver(device, tuning.khz, config, encode).start() else FmReceiver(device, tuning.khz, config, encode).start()
}

private val log = LoggerFactory.getLogger("Receiver")

private fun silence(rate: Int, ms: Int) = ByteArray(rate * ms / 1000 * 4)

/**
 * An FM station: rtl_fm's MPX, shared out (in this program, so one never waits for another) to
 * redsea for RDS, to the stereo decoder and signal meter, and on to ffmpeg for listeners.
 */
class FmReceiver(
    private val device: String,
    private val khz: Int,
    private val config: TunerConfig,
    private val encode: Boolean = true,
) : Receiver, StationAudio {
    override val tuning = Tuning(khz, hd = false)
    private val label = Station.frequencyLabel(khz)
    val rds = RdsDecoder(config.region.rbds)
    val meter = SignalMeter(Commands.MPX_RATE.toDouble())
    private val decoder = FmStereoDecoder(Commands.MPX_RATE.toDouble(), 3, config.region.deemphasisUs * 1e-6)
    override val broadcast = Broadcast()

    @Volatile
    override var error: String? = null
        private set

    @Volatile
    var samples = 0L
        private set

    private val redsea = Feeder(
        "redsea $label",
        { Commands.redsea(config.tools, config.region) },
        onStdout = { input -> input.bufferedReader().forEachLine { rds.accept(it) } },
        onLine = { log.debug("redsea {}: {}", label, it) },
    )
    private val encoder = if (!encode) null else Feeder(
        "ffmpeg $label",
        { Commands.encoder(config.tools, decoder.outputRate, config.bitrateKbps) },
        onStdout = { AdtsSplitter(broadcast::publish).pump(it) },
        onLine = { log.warn("ffmpeg {}: {}", label, it) },
        silence = silence(decoder.outputRate, 300),
    )
    private val radio = Supervised(
        "rtl_fm $label",
        { Commands.rtlFm(config.tools, device, khz, config.gain, config.ppm) },
        body = ::pump,
        onLine = { line ->
            DongleTrouble.of(line, device)?.let { error = it }
            log.debug("rtl_fm {}: {}", label, line)
        },
        onStop = { why -> if (error == null) error = "rtl_fm stopped ($why)" },
    )

    fun start(): FmReceiver {
        encoder?.start()
        redsea.start()
        radio.start()
        return this
    }

    private fun pump(child: Child) {
        val input = child.stdout
        val bytes = ByteArray(16_384)
        val shorts = ShortArray(bytes.size / 2)
        val stereo = ShortArray(2 * shorts.size / 3 + 4)
        while (true) {
            val read = input.readNBytes(bytes, 0, bytes.size)
            if (read < 2) return
            val count = read / 2
            for (i in 0 until count) shorts[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
            redsea.offer(bytes.copyOf(count * 2))
            meter.feed(shorts, count)
            if (encoder != null) {
                decoder.stereo = meter.stereoWanted()
                val frames = decoder.process(shorts, count, stereo)
                encoder.offer(toBytes(stereo, frames * 2))
            }
            samples += count
            error = null
        }
    }

    fun signal(): Signal? {
        val info = rds.info
        val snr = meter.snrDb ?: return null
        val score = FmQuality.score(snr, info.bler) ?: return null
        val blend = if (encode) decoder.stereoBlend else meter.stereoWanted()
        return Signal(
            score = Math.round(score * 100) / 100.0,
            detail = FmQuality.detail(snr, meter.pilot, blend, info.bler),
            measuredAt = System.currentTimeMillis(),
            stereo = meter.pilot && blend > 0.5f,
        )
    }

    override fun live(): LiveInfo {
        val info: RdsInfo = rds.info
        return LiveInfo(
            title = info.title,
            artist = info.artist,
            album = info.album,
            radiotext = info.radiotext,
            stationName = rds.steadyName(),
            callLetters = info.callLetters,
            pi = info.pi?.let { String.format("0x%04X", it) },
            genre = info.genre,
            // A dongle that can't be opened gives nothing to hear: as bad as static.
            signal = signal() ?: error?.let { Signal(0.0, it, System.currentTimeMillis()) },
            error = error,
        )
    }

    override fun audio(station: Station): StationAudio = this

    override fun release(station: Station) = close()

    override fun close() {
        radio.stop()
        redsea.stop()
        encoder?.stop()
        broadcast.closeAll()
    }
}

/**
 * An HD Radio frequency: rtl_sdr's I/Q, shared out to one nrsc5 for each program someone's
 * listening to (so HD1 and HD2 of a station play from one dongle).
 */
class HdReceiver(
    private val device: String,
    private val khz: Int,
    private val config: TunerConfig,
    private val encode: Boolean = true,
) : Receiver {
    override val tuning = Tuning(khz, hd = true)
    private val label = Station.frequencyLabel(khz)
    private val programs = ConcurrentHashMap<Int, HdProgram>()

    @Volatile
    override var error: String? = null
        private set

    private val radio = Supervised(
        "rtl_sdr $label",
        { Commands.rtlSdr(config.tools, device, khz, config.gain, config.ppm) },
        body = ::pump,
        onLine = { line ->
            DongleTrouble.of(line, device)?.let { error = it }
            log.debug("rtl_sdr {}: {}", label, line)
        },
        onStop = { why -> if (error == null) error = "rtl_sdr stopped ($why)" },
    )

    fun start(): HdReceiver {
        radio.start()
        return this
    }

    /** [program]'s decoder, started if it isn't running. */
    fun program(program: Int): HdProgram = programs.computeIfAbsent(program) {
        HdProgram(khz, program, config, encode).also { it.start() }
    }

    private fun pump(child: Child) {
        val input = child.stdout
        while (true) {
            val chunk = ByteArray(65_536)
            val read = input.readNBytes(chunk, 0, chunk.size)
            if (read <= 0) return
            val bytes = if (read == chunk.size) chunk else chunk.copyOf(read)
            for (p in programs.values) p.offer(bytes)
            error = null
        }
    }

    override fun audio(station: Station): StationAudio = program(station.program ?: 0)

    override fun release(station: Station) {
        programs.remove(station.program ?: 0)?.close()
    }

    val running: Collection<HdProgram> get() = programs.values

    override fun close() {
        radio.stop()
        programs.values.forEach { it.close() }
        programs.clear()
    }

    inner class HdProgram(khz: Int, val program: Int, config: TunerConfig, encode: Boolean) : StationAudio {
        private val name = "${Station.frequencyLabel(khz)} HD${program + 1}"
        val filesDir: File = File(config.workDir, "hd-$device-$khz-$program").apply {
            deleteRecursively()
            mkdirs()
        }
        val decoder = HdDecoder(program, filesDir)
        override val broadcast = Broadcast()
        private val encoder = if (!encode) null else Feeder(
            "ffmpeg $name",
            { Commands.encoder(config.tools, Commands.HD_AUDIO_RATE, config.bitrateKbps) },
            onStdout = { AdtsSplitter(broadcast::publish).pump(it) },
            onLine = { log.warn("ffmpeg {}: {}", name, it) },
            silence = silence(Commands.HD_AUDIO_RATE, 300),
        )
        private val nrsc5 = Feeder(
            "nrsc5 $name",
            { Commands.nrsc5(config.tools, program, filesDir) },
            onStdout = { input -> forward(input) },
            onLine = { line ->
                decoder.accept(line)
                log.debug("nrsc5 {}: {}", name, line)
            },
            dir = filesDir,
            // About three seconds of I/Q.
            capacity = 128,
        )

        fun start() {
            encoder?.start()
            nrsc5.start()
        }

        fun offer(iq: ByteArray) = nrsc5.offer(iq)

        private fun forward(input: InputStream) {
            val buffer = ByteArray(8_192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return
                if (read > 0) encoder?.offer(buffer.copyOf(read))
            }
        }

        fun signal(): Signal? {
            val info = decoder.info
            val score = HdQuality.score(info) ?: return null
            return Signal(Math.round(score * 100) / 100.0, HdQuality.detail(info), System.currentTimeMillis())
        }

        override fun live(): LiveInfo {
            val info: HdInfo = decoder.info
            return LiveInfo(
                title = info.title,
                artist = info.artist,
                album = info.album,
                stationName = HdDecoder.cleanName(info.stationName),
                programName = HdDecoder.cleanName(info.programName),
                slogan = info.slogan,
                callLetters = CallLetters.fromName(info.stationName),
                genre = info.programs[program] ?: info.genre,
                logo = info.logo?.takeIf { it.isFile },
                art = info.art?.takeIf { it.isFile },
                signal = signal() ?: (error ?: info.fatal)?.let { Signal(0.0, it, System.currentTimeMillis()) },
                programs = info.programs,
                error = error ?: info.fatal,
            )
        }

        fun close() {
            nrsc5.stop()
            encoder?.stop()
            broadcast.closeAll()
            filesDir.deleteRecursively()
        }
    }
}

private fun toBytes(samples: ShortArray, count: Int): ByteArray {
    val out = ByteArray(count * 2)
    for (i in 0 until count) {
        val v = samples[i].toInt()
        out[2 * i] = v.toByte()
        out[2 * i + 1] = (v shr 8).toByte()
    }
    return out
}
