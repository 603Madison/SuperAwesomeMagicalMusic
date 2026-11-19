package com.music.nowplaying

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A quick look at a few seconds of sound, on the phone, before anything is asked of anyone:
 * whether it's loud enough, and tonal enough (not the hiss of a fan or traffic), to be music
 * worth recognising. Most of the time it's quiet, and nothing more happens.
 */
object Sound {
    /** Quieter than this (dBFS) is a quiet room. */
    const val QUIET_DB = -52.0

    /** Flatter than this (spectral flatness, 0 tonal to 1 noise) is noise, not music. */
    const val NOISE_FLATNESS = 0.45

    fun loudnessDb(samples: FloatArray): Double {
        if (samples.isEmpty()) return -120.0
        var sum = 0.0
        for (s in samples) sum += s * s
        val rms = sqrt(sum / samples.size)
        return if (rms <= 1e-9) -120.0 else 20 * log10(rms)
    }

    /**
     * Spectral flatness (geometric over arithmetic mean of the power spectrum, 300 Hz–4 kHz),
     * averaged over frames of [samples] at [rate]: near 0 for notes, near 1 for noise.
     */
    fun flatness(samples: FloatArray, rate: Int, frame: Int = 1024): Double {
        if (samples.size < frame) return 1.0
        val low = 300 * frame / rate
        val high = minOf(frame / 2 - 1, 4_000 * frame / rate)
        val re = DoubleArray(frame)
        val im = DoubleArray(frame)
        var total = 0.0
        var frames = 0
        var at = 0
        while (at + frame <= samples.size) {
            for (i in 0 until frame) {
                val window = 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / (frame - 1))
                re[i] = samples[at + i] * window
                im[i] = 0.0
            }
            fft(re, im)
            var logSum = 0.0
            var sum = 0.0
            for (k in low..high) {
                val power = re[k] * re[k] + im[k] * im[k] + 1e-12
                logSum += ln(power)
                sum += power
            }
            val n = high - low + 1
            total += kotlin.math.exp(logSum / n) / (sum / n)
            frames++
            at += frame
        }
        return total / frames
    }

    /** Whether [samples] (at [rate]) sound like music worth recognising. */
    fun worthRecognising(samples: FloatArray, rate: Int): Boolean =
        loudnessDb(samples) > QUIET_DB && flatness(samples, rate) < NOISE_FLATNESS

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * Math.PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = kotlin.math.cos(angle * k)
                    val wi = kotlin.math.sin(angle * k)
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                }
            }
            len = len shl 1
        }
    }
}
