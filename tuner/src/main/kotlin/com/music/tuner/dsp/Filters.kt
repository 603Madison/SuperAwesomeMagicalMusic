package com.music.tuner.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Filter designs and the small filters the FM decoder is made of. */
object Filters {
    /** A low-pass FIR (windowed sinc, Blackman window) with unity gain at 0 Hz. [count] should be odd. */
    fun lowpass(count: Int, cutoffHz: Double, sampleRate: Double): FloatArray {
        val m = count - 1
        val fc = cutoffHz / sampleRate
        val taps = DoubleArray(count) { i ->
            val x = i - m / 2.0
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val window = 0.42 - 0.5 * cos(2 * PI * i / m) + 0.08 * cos(4 * PI * i / m)
            sinc * window
        }
        val sum = taps.sum()
        return FloatArray(count) { (taps[it] / sum).toFloat() }
    }
}

/** A FIR filter that only works out every [factor]th output: filtering and decimating in one. */
class DecimatingFir(private val taps: FloatArray, private val factor: Int) {
    private val n = taps.size
    // Each sample is stored twice, so the newest n are always in one run.
    private val buffer = FloatArray(2 * n)
    private var position = 0
    private var phase = 0

    /** Adds a sample; true when an output is due. */
    fun push(x: Float): Boolean {
        buffer[position] = x
        buffer[position + n] = x
        position = if (position + 1 == n) 0 else position + 1
        phase++
        if (phase == factor) {
            phase = 0
            return true
        }
        return false
    }

    fun output(): Float {
        var sum = 0f
        val start = position
        for (i in 0 until n) sum += taps[i] * buffer[start + i]
        return sum
    }
}

/** A two-pole band-pass filter (RBJ's cookbook), with 0 dB and no phase shift at [centerHz]. */
class BandPass(centerHz: Double, q: Double, sampleRate: Double) {
    private val b0: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val w0 = 2 * PI * centerHz / sampleRate
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        b0 = alpha / a0
        b2 = -alpha / a0
        a1 = -2 * cos(w0) / a0
        a2 = (1 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}

/**
 * FM de-emphasis: transmitters boost treble (pre-emphasis, 75 µs in the Americas and 50 µs
 * elsewhere) to keep it above the hiss; this turns it back down, and the hiss with it.
 */
class Deemphasis(tauSeconds: Double, sampleRate: Double) {
    private val a = (1 - exp(-1 / (tauSeconds * sampleRate))).toFloat()
    private var y = 0f

    fun process(x: Float): Float {
        y += a * (x - y)
        return y
    }
}

/** An in-place radix-2 FFT. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n and (n - 1) == 0) { "The size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2 * PI / length
            val wRe = cos(angle)
            val wIm = sin(angle)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val next = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = next
                }
                start += length
            }
            length = length shl 1
        }
    }
}
