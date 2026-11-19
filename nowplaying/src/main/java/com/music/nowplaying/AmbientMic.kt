package com.music.nowplaying

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.music.player.listen.Fingerprint

/**
 * The microphone, a few seconds at a time, at the rate the fingerprints use (11,025 Hz, which
 * every Android phone records at). Opened for each listen and closed straight after, so the
 * microphone is in use (and Android's microphone dot shows) only while it listens.
 */
internal class AmbientMic(private val context: Context) {
    private var record: AudioRecord? = null

    /** Opens it; false if it couldn't be (another app using it, no permission). */
    @SuppressLint("MissingPermission")
    fun open(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val unprocessed = audio?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        // The sound as it is: no noise suppression or levelling, which are made for voices.
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = runCatching { AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE)) }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            return false
        }
        runCatching { recorder.startRecording() }.onFailure {
            recorder.release()
            return false
        }
        record = recorder
        return true
    }

    /** The next [ms] of sound, as samples from -1 to 1 (fewer if the microphone stops). */
    fun read(ms: Long): FloatArray {
        val recorder = record ?: return FloatArray(0)
        val wanted = (RATE * ms / 1000).toInt()
        val out = FloatArray(wanted)
        val buffer = ShortArray(2_048)
        var got = 0
        while (got < wanted) {
            val n = recorder.read(buffer, 0, minOf(buffer.size, wanted - got))
            if (n <= 0) break
            for (i in 0 until n) out[got + i] = buffer[i] / 32768f
            got += n
        }
        return if (got == wanted) out else out.copyOf(got)
    }

    fun close() {
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    companion object {
        const val RATE = Fingerprint.RATE
    }
}
