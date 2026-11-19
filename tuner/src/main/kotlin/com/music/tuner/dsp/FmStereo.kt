package com.music.tuner.dsp

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns an FM station's multiplex signal (the "MPX" rtl_fm demodulates: mono audio up to 15 kHz,
 * a 19 kHz pilot tone, the stereo difference on a 38 kHz subcarrier, RDS at 57 kHz) into stereo
 * audio:
 *
 *  - a phase-locked loop follows the pilot and makes the 38 kHz carrier from it;
 *  - L+R and L−R are low-passed at 15 kHz and decimated by [decimation] (171 kHz to 57 kHz);
 *  - [stereo] blends between mono and stereo (a noisy pilot means hiss in L−R, so weak stations
 *    play in mono), and only while the loop is locked;
 *  - de-emphasis turns the transmitter's treble boost back down.
 *
 * [gain] scales rtl_fm's output (about ±14,000 at full deviation) to 16-bit audio.
 */
class FmStereoDecoder(
    private val sampleRate: Double = 171_000.0,
    private val decimation: Int = 3,
    deemphasisSeconds: Double = 75e-6,
    private val gain: Float = 2.2f,
) {
    val outputRate: Int = (sampleRate / decimation).toInt()

    private val taps = Filters.lowpass(255, 15_500.0, sampleRate)
    private val mono = DecimatingFir(taps, decimation)
    private val difference = DecimatingFir(taps, decimation)
    private val pilot1 = BandPass(PILOT_HZ, 12.0, sampleRate)
    private val pilot2 = BandPass(PILOT_HZ, 12.0, sampleRate)
    private val deemphasisLeft = Deemphasis(deemphasisSeconds, outputRate.toDouble())
    private val deemphasisRight = Deemphasis(deemphasisSeconds, outputRate.toDouble())

    // The loop: a second-order PLL with a bandwidth of about 20 Hz.
    private val w0 = 2 * PI * PILOT_HZ / sampleRate
    private val kp: Double
    private val ki: Double
    private val maxPull = 2 * PI * 60 / sampleRate
    private var theta = 0.0
    private var integrator = 0.0
    private var power = 0.0
    private val powerAlpha = 1 - exp(-1 / (0.01 * sampleRate))
    private var lock = 0.0
    private val lockAlpha = 1 - exp(-1 / (0.05 * sampleRate))
    private var blend = 0f

    /** How much stereo is wanted, 0 (mono) to 1, from the signal quality. */
    @Volatile
    var stereo: Float = 1f

    init {
        val wn = 2 * PI * 20 / sampleRate
        val zeta = 0.707
        // The phase detector's gain is 1/2 with the input normalised to the pilot's level.
        kp = 2 * zeta * wn / 0.5
        ki = wn * wn / 0.5
    }

    /** How sure the loop is that it's locked to a pilot, 0 to 1. */
    val pilotLock: Double get() = ((lock - 0.5) / 0.35).coerceIn(0.0, 1.0)

    /** How much of the output is stereo right now. */
    val stereoBlend: Float get() = blend

    /**
     * Decodes [count] MPX samples from [input] into interleaved stereo in [output] (which needs
     * room for 2 × count / decimation + 2 values). Returns the number of stereo frames written.
     */
    fun process(input: ShortArray, count: Int, output: ShortArray): Int {
        var frames = 0
        for (i in 0 until count) {
            val x = input[i].toDouble()
            val bp = pilot2.process(pilot1.process(x))
            power += (bp * bp - power) * powerAlpha
            val amplitude = sqrt(2 * power)
            val s = sin(theta)
            val c = cos(theta)
            if (amplitude > 1.0) {
                val error = -bp * s / amplitude
                integrator = (integrator + ki * error).coerceIn(-maxPull, maxPull)
                theta += w0 + integrator + kp * error
                lock += (2 * bp * c / amplitude - lock) * lockAlpha
            } else {
                theta += w0
                lock -= lock * lockAlpha
            }
            if (theta > PI) theta -= 2 * PI
            // Locked, the pilot is cos(θ); the stereo subcarrier is then −sin(2θ).
            val carrier = -2 * s * c
            val xf = x.toFloat()
            val due = mono.push(xf)
            difference.push((x * 2 * carrier).toFloat())
            if (due) {
                val target = stereo * pilotLock.toFloat()
                blend += (target - blend) * 0.0005f
                val m = mono.output()
                val d = difference.output() * blend
                val left = deemphasisLeft.process(m + d) * gain
                val right = deemphasisRight.process(m - d) * gain
                output[2 * frames] = clip(left)
                output[2 * frames + 1] = clip(right)
                frames++
            }
        }
        return frames
    }

    private fun clip(v: Float): Short = max(-32768f, min(32767f, v)).toInt().toShort()

    companion object {
        const val PILOT_HZ = 19_000.0
    }
}

/**
 * How clean an FM signal is, from the MPX: the 16–18.5 kHz gap between the audio and the pilot
 * carries nothing, so whatever is there is noise; compared with the pilot (always sent at the
 * same level), that's the signal-to-noise ratio, and it rises and falls with reception. Checked
 * about four times a second on a block of [size] samples.
 */
class SignalMeter(private val sampleRate: Double = 171_000.0, private val size: Int = 8192, private val every: Int = 5) {
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
    private val block = DoubleArray(size)
    private var filled = 0
    private var skipped = 0
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    /** The pilot's level over the noise in each 21 Hz of the gap (dB), smoothed; null until measured. */
    @Volatile
    var snrDb: Double? = null
        private set

    /** Whether the station sends a stereo pilot. */
    @Volatile
    var pilot: Boolean = false
        private set

    @Volatile
    var measurements: Int = 0
        private set

    fun feed(input: ShortArray, count: Int) {
        for (i in 0 until count) {
            if (skipped < size * (every - 1)) {
                skipped++
                continue
            }
            block[filled++] = input[i].toDouble()
            if (filled == size) {
                measure()
                filled = 0
                skipped = 0
            }
        }
    }

    private fun measure() {
        for (i in 0 until size) {
            re[i] = block[i] * window[i]
            im[i] = 0.0
        }
        Fft.transform(re, im)
        val binHz = sampleRate / size
        fun bin(hz: Double) = (hz / binHz).toInt()
        fun power(k: Int) = re[k] * re[k] + im[k] * im[k]
        val pilotCenter = Math.round(19_000.0 / binHz).toInt()
        var pilotPower = 0.0
        for (k in pilotCenter - 3..pilotCenter + 3) pilotPower += power(k)
        val noiseBins = (bin(16_000.0)..bin(18_500.0)).map(::power).sorted()
        // The median: a stray tone in the gap doesn't count as noise.
        val noise = max(noiseBins[noiseBins.size / 2], 1e-9)
        var programme = 0.0
        for (k in bin(100.0)..bin(15_000.0)) programme += power(k)
        val pilotOverNoise = 10 * log10(max(pilotPower, 1e-9) / noise)
        val pilotOverProgramme = 10 * log10(max(pilotPower, 1e-9) / max(programme, 1e-9))
        pilot = pilotOverNoise > 15 && pilotOverProgramme > -40
        // A mono station has no pilot to measure against: its programme, about 6 dB above where
        // a pilot would be, stands in (less exact, since programme levels vary).
        val reference = if (pilot) pilotPower else programme / 4
        val measured = 10 * log10(max(reference, 1e-9) / noise)
        snrDb = snrDb?.let { 0.7 * it + 0.3 * measured } ?: measured
        measurements++
    }

    /** How much stereo to play, from the signal-to-noise ratio: mono below 40 dB, stereo from 55 dB. */
    fun stereoWanted(): Float {
        val snr = snrDb ?: return 0f
        if (!pilot) return 0f
        return ((snr - STEREO_FROM_DB) / (STEREO_FULL_DB - STEREO_FROM_DB)).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        const val STEREO_FROM_DB = 40.0
        const val STEREO_FULL_DB = 55.0
    }
}

/** An FM signal's quality, 0 to 1, for "use the tuner when it's clear, the internet when it's static". */
object FmQuality {
    /** Static even in mono at or below this (dB, as [SignalMeter] measures it). */
    const val STATIC_DB = 20.0

    /** Clear, in stereo, from this. */
    const val CLEAR_DB = 55.0

    fun score(snrDb: Double?, blerPercent: Int?): Double? {
        val snr = snrDb ?: return null
        val fromNoise = ((snr - STATIC_DB) / (CLEAR_DB - STATIC_DB)).coerceIn(0.0, 1.0)
        // RDS gets through until a signal is weak, then its error rate climbs quickly.
        val fromRds = blerPercent?.let { (1 - it / 30.0).coerceIn(0.0, 1.0) } ?: return fromNoise
        return 0.75 * fromNoise + 0.25 * fromRds
    }

    fun detail(snrDb: Double?, pilot: Boolean, stereoBlend: Float, blerPercent: Int?): String {
        val parts = mutableListOf<String>()
        parts += when {
            snrDb == null -> "Measuring"
            !pilot -> "Mono"
            stereoBlend > 0.9f -> "Stereo"
            stereoBlend < 0.1f -> "Mono (too weak for stereo)"
            else -> "Partly stereo"
        }
        if (snrDb != null) parts += String.format(Locale.ROOT, "%s %.0f dB over the noise", if (pilot) "pilot" else "programme", snrDb)
        if (blerPercent != null) parts += "RDS $blerPercent% errors"
        return parts.joinToString(", ")
    }
}
