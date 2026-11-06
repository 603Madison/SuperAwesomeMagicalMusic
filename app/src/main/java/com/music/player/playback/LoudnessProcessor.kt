package com.music.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Evens out volume between songs from different places (Jellyfin files, YouTube, Qobuz,
 * SiriusXM all play at different loudness).
 *
 * A song whose source knows its loudness (Jellyfin's normalization gain) gets that fixed gain,
 * which keeps its dynamics. Anything else is levelled by ear: a slowly moving estimate of how
 * loud the music is (quiet passages and silence don't count) sets the gain, which changes by at
 * most a couple of dB a second. Boosts go through a soft limiter so they never clip.
 */
@OptIn(UnstableApi::class)
class LoudnessProcessor : BaseAudioProcessor() {
    /** Off passes audio through untouched. */
    @Volatile
    var enabled: Boolean = true

    /** The fixed gain (dB, to -18 LUFS) for the song playing, or null to level adaptively. */
    @Volatile
    var knownGainDb: Float? = null

    private var sampleRate = 44_100
    private var channels = 2

    /** Mean square of the music heard so far, slowly updated; starts as typical modern loudness. */
    private var meanSquare = dbToPower(START_LEVEL_DB)
    private var gainDb = 0.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        if (!enabled) {
            output.put(input)
            output.flip()
            return
        }
        val bytesPerSample = if (float) 4 else 2
        val frames = remaining / (bytesPerSample * channels)
        val start = input.position()

        // How loud this block is (measured before any gain), and the gain to aim for.
        var sum = 0.0
        for (i in 0 until frames * channels) {
            val sample = read(input, start + i * bytesPerSample, float)
            sum += sample * sample
        }
        val blockPower = if (frames > 0) sum / (frames * channels) else 0.0
        val seconds = frames.toDouble() / sampleRate
        val known = knownGainDb
        val targetDb = if (known != null) {
            (known + KNOWN_GAIN_OFFSET_DB).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        } else {
            if (blockPower > dbToPower(SILENCE_DB)) {
                val weight = 1 - exp(-seconds / TIME_CONSTANT_S)
                meanSquare += (blockPower - meanSquare) * weight
            }
            (TARGET_DB - powerToDb(meanSquare)).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        }
        // A known gain applies at once; a measured one moves gently.
        val maxStep = if (known != null) Double.MAX_VALUE else MAX_DB_PER_SECOND * seconds
        val nextGainDb = gainDb + (targetDb - gainDb).coerceIn(-maxStep, maxStep)

        val from = dbToAmplitude(gainDb)
        val to = dbToAmplitude(nextGainDb)
        for (frame in 0 until frames) {
            // Glide across the block so gain changes never click.
            val gain = from + (to - from) * frame / frames.coerceAtLeast(1)
            for (channel in 0 until channels) {
                val offset = start + (frame * channels + channel) * bytesPerSample
                val value = limit(read(input, offset, float) * gain)
                if (float) {
                    output.putFloat(value.toFloat())
                } else {
                    output.putShort((value * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
        }
        gainDb = nextGainDb
        input.position(input.limit())
        output.flip()
    }

    private fun read(buffer: ByteBuffer, offset: Int, float: Boolean): Double =
        if (float) buffer.getFloat(offset).toDouble() else buffer.getShort(offset) / 32768.0

    override fun onReset() {
        meanSquare = dbToPower(START_LEVEL_DB)
        gainDb = 0.0
    }

    /** The gain in use (dB), for tests and the song's info. */
    val currentGainDb: Double get() = gainDb

    companion object {
        /** About -16 LUFS, between ReplayGain's -18 and the -14 streaming services use. */
        const val TARGET_DB = -16.0
        /** Jellyfin's gain brings songs to -18 LUFS; this lifts them to [TARGET_DB]. */
        const val KNOWN_GAIN_OFFSET_DB = 2.0
        const val MIN_GAIN_DB = -12.0
        const val MAX_GAIN_DB = 6.0
        private const val START_LEVEL_DB = -14.0
        private const val SILENCE_DB = -45.0
        private const val TIME_CONSTANT_S = 8.0
        private const val MAX_DB_PER_SECOND = 2.0
        private const val KNEE = 0.9

        fun dbToAmplitude(db: Double): Double = 10.0.pow(db / 20)
        private fun dbToPower(db: Double): Double = 10.0.pow(db / 10)
        private fun powerToDb(power: Double): Double = 10 * log10(power.coerceAtLeast(1e-12))

        /** Unchanged below the knee; above it, eased towards full scale instead of clipping. */
        fun limit(x: Double): Double {
            val magnitude = abs(x)
            if (magnitude <= KNEE) return x
            val eased = KNEE + (1 - KNEE) * tanh((magnitude - KNEE) / (1 - KNEE))
            return if (x < 0) -eased else eased
        }
    }
}
