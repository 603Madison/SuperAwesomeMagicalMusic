package com.music.server.listen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio fingerprints, for recognising a song from a few seconds of it heard through a microphone,
 * the way Shazam does (Wang, 2003). A song's spectrogram is reduced to its peaks (the loudest
 * points, each the loudest around it); pairs of nearby peaks become hashes of (frequency,
 * frequency, time between), each kept with when it happens. Of a clip's hashes, those that match
 * a song's all shifted by the same time say which song it is and where in it the clip was. Noise,
 * a room's echo and a phone's microphone lose some peaks, but most of the loud ones survive.
 *
 * Songs are fingerprinted sparsely (they're stored) and clips densely: a clip's pairs then include
 * the song's, even when noise has added peaks of its own.
 */
object Fingerprint {
    /** Audio is fingerprinted mono at this rate (frequencies up to 5.5 kHz, where melodies are). */
    const val RATE = 11_025
    const val FFT = 1_024
    const val HOP = 512
    const val FRAME_MS = HOP * 1000.0 / RATE
    private const val BINS = FFT / 2

    /**
     * How peaks are found and paired: each a maximum within [freqRadius] bins and [timeRadius]
     * frames and [threshold] (natural log) above its frame's mean, between [lowBin] and [highBin];
     * paired with up to [fanOut] peaks after it, within [maxDt] frames and [maxDf] bins.
     */
    data class Params(
        val freqRadius: Int,
        val timeRadius: Int,
        val threshold: Float,
        val fanOut: Int,
        // From about 150 Hz: below is bass and kick drums, which phones barely hear, and which
        // sound much alike from song to song.
        val lowBin: Int = 14,
        val highBin: Int = 480,
        val maxDt: Int = 48,
        val maxDf: Int = 200,
    )

    /** For songs in the library (about 50 hashes a second). */
    val SONG = Params(freqRadius = 8, timeRadius = 6, threshold = 1.5f, fanOut = 3)

    /** For what was heard. */
    val CLIP = Params(freqRadius = 6, timeRadius = 5, threshold = 1.0f, fanOut = 20)

    /** A hash with its frame: the hash in the high 32 bits, the frame in the low. */
    fun hash(packed: Long): Int = (packed ushr 32).toInt()

    fun frame(packed: Long): Int = packed.toInt()

    /** The hashes of [samples] (mono, at [RATE], -1 to 1), in time order. */
    fun hashes(samples: FloatArray, params: Params): LongArray {
        val spectrogram = spectrogram(samples)
        return pairs(peaks(spectrogram, params), params)
    }

    /**
     * Log magnitudes, [frame][bin], of Hann-windowed frames [FFT] long every [HOP] samples
     * (bins 0 until [BINS], each [RATE] / [FFT] Hz).
     */
    fun spectrogram(samples: FloatArray): Array<FloatArray> {
        val frames = if (samples.size < FFT) 0 else 1 + (samples.size - FFT) / HOP
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        return Array(frames) { f ->
            val start = f * HOP
            for (i in 0 until FFT) {
                re[i] = samples[start + i] * WINDOW[i]
                im[i] = 0.0
            }
            fft(re, im)
            FloatArray(BINS) { k -> ln(sqrt(re[k] * re[k] + im[k] * im[k]) + 1e-6).toFloat() }
        }
    }

    /** The spectrogram's peaks, in time order (and by frequency within a frame), as frame shl 16 or bin. */
    fun peaks(spectrogram: Array<FloatArray>, params: Params): IntArray {
        val frames = spectrogram.size
        val lo = params.lowBin
        val hi = minOf(params.highBin, BINS)
        val width = hi - lo
        if (frames == 0 || width <= 0) return IntArray(0)
        // The maximum around each point: first along frequency, then along time.
        val alongFreq = Array(frames) { t ->
            val row = spectrogram[t]
            FloatArray(width) { i ->
                var best = Float.NEGATIVE_INFINITY
                for (j in maxOf(0, i - params.freqRadius)..minOf(width - 1, i + params.freqRadius)) {
                    val v = row[lo + j]
                    if (v > best) best = v
                }
                best
            }
        }
        val means = FloatArray(frames) { t ->
            var sum = 0.0
            for (i in lo until hi) sum += spectrogram[t][i]
            (sum / width).toFloat()
        }
        val out = ArrayList<Int>()
        for (t in 0 until frames) {
            val row = spectrogram[t]
            val floor = means[t] + params.threshold
            for (i in 0 until width) {
                val v = row[lo + i]
                if (v <= floor || v < alongFreq[t][i]) continue
                var biggest = true
                for (u in maxOf(0, t - params.timeRadius)..minOf(frames - 1, t + params.timeRadius)) {
                    if (alongFreq[u][i] > v) {
                        biggest = false
                        break
                    }
                }
                if (biggest) out += (t shl 16) or (lo + i)
            }
        }
        return out.toIntArray()
    }

    /** Pairs of [peaks] (each with up to fanOut after it) as hashes. */
    fun pairs(peaks: IntArray, params: Params): LongArray {
        val out = ArrayList<Long>(peaks.size * minOf(params.fanOut, 8))
        for (i in peaks.indices) {
            val t1 = peaks[i] ushr 16
            val f1 = peaks[i] and 0xffff
            var made = 0
            for (j in i + 1 until peaks.size) {
                val dt = (peaks[j] ushr 16) - t1
                if (dt > params.maxDt) break
                val f2 = peaks[j] and 0xffff
                if (dt < 1 || abs(f2 - f1) > params.maxDf) continue
                val hash = ((f1 shr 1) shl 16) or ((f2 shr 1) shl 7) or dt
                out += (hash.toLong() shl 32) or t1.toLong()
                if (++made >= params.fanOut) break
            }
        }
        return out.toLongArray()
    }

    /**
     * Which song [clip]'s hashes match, given the songs' hashes for them ([found]: hash, item,
     * frame), and how well: the most hashes agreeing on one song and one time shift (a frame
     * either way counts too), against the best of any other song.
     */
    fun match(clip: LongArray, found: List<Triple<Int, Long, Int>>): Match? {
        if (clip.isEmpty() || found.isEmpty()) return null
        val atClip = HashMap<Int, IntArrayList>()
        for (packed in clip.distinct()) atClip.getOrPut(hash(packed)) { IntArrayList() }.add(frame(packed))
        val votes = HashMap<Long, Int>()
        for ((hash, item, frame) in found) {
            val frames = atClip[hash] ?: continue
            for (k in 0 until frames.size) {
                val key = (item shl 32) or ((frame - frames[k]).toLong() and 0xffffffffL)
                votes[key] = (votes[key] ?: 0) + 1
            }
        }
        var bestItem = -1L
        var bestOffset = 0
        var bestScore = 0
        val perItem = HashMap<Long, Int>()
        for ((key, count) in votes) {
            val item = key ushr 32
            val offset = key.toInt()
            val score = count + (votes[(item shl 32) or ((offset - 1).toLong() and 0xffffffffL)] ?: 0) +
                (votes[(item shl 32) or ((offset + 1).toLong() and 0xffffffffL)] ?: 0)
            if (score > (perItem[item] ?: 0)) perItem[item] = score
            if (score > bestScore || (score == bestScore && item == bestItem && count > (votes[(bestItem shl 32) or (bestOffset.toLong() and 0xffffffffL)] ?: 0))) {
                bestScore = score
                bestItem = item
                bestOffset = offset
            }
        }
        if (bestItem < 0) return null
        val runnerUp = perItem.filterKeys { it != bestItem }.values.maxOrNull() ?: 0
        return Match(bestItem, bestOffset, bestScore, runnerUp)
    }

    /** A clip's best match: [item], the clip's start [offset] frames into it, [score] hashes against [runnerUp]'s. */
    data class Match(val item: Long, val offset: Int, val score: Int, val runnerUp: Int) {
        /**
         * Sure enough to say: well ahead of any other song, and either plainly so, or as the
         * [candidate] the listen's last (shorter) try leant towards as well. A chance match
         * rarely comes out on top twice.
         */
        fun confident(candidate: Long? = null): Boolean =
            score >= runnerUp * LEAD && (score >= SURE || (score >= MIN_SCORE && item == candidate))

        /** Worth remembering for the next try. */
        val promising: Boolean get() = score >= HINT && score >= runnerUp * LEAD

        val offsetMs: Long get() = (offset * FRAME_MS).toLong()
    }

    /** Agreeing hashes that settle it on their own, that do when the last try agreed, and that are worth a second try. */
    const val SURE = 15
    const val MIN_SCORE = 10
    const val HINT = 6
    const val LEAD = 2.0

    private val WINDOW = DoubleArray(FFT) { 0.5 - 0.5 * cos(2 * PI * it / (FFT - 1)) }
    private val COS = DoubleArray(FFT / 2) { cos(-2 * PI * it / FFT) }
    private val SIN = DoubleArray(FFT / 2) { sin(-2 * PI * it / FFT) }
    private val REVERSED = IntArray(FFT) { i ->
        var r = 0
        var x = i
        var bits = FFT
        while (bits > 1) {
            r = (r shl 1) or (x and 1)
            x = x shr 1
            bits = bits shr 1
        }
        r
    }

    /** In-place radix-2 FFT of length [FFT]. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until FFT) {
            val j = REVERSED[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= FFT) {
            val half = size / 2
            val step = FFT / size
            var start = 0
            while (start < FFT) {
                for (k in 0 until half) {
                    val wr = COS[k * step]
                    val wi = SIN[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += size
            }
            size *= 2
        }
    }

    /** A growable list of ints. */
    class IntArrayList {
        private var data = IntArray(4)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        operator fun get(i: Int) = data[i]
    }
}
