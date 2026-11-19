package com.music.connect.listen

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * The microphone, for Listen: as plain as the phone allows (no noise suppression or levelling,
 * which are made for voices and smudge music), kept in memory while listening, and handed over
 * as a WAV file of everything so far.
 */
class MicRecorder(private val context: Context) {
    private val pcm = ByteArrayOutputStream()
    private var record: AudioRecord? = null
    private var reader: Thread? = null

    @Volatile
    private var running = false

    /** Starts recording (the caller has the microphone permission); false if the microphone couldn't be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val unprocessed = audio?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = runCatching { AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE)) }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            return false
        }
        synchronized(pcm) { pcm.reset() }
        recorder.startRecording()
        record = recorder
        running = true
        reader = thread(name = "listen-mic") {
            val buffer = ByteArray(4_096)
            while (running) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n > 0) synchronized(pcm) { pcm.write(buffer, 0, n) }
            }
        }
        return true
    }

    /** Everything heard so far, as a WAV file. */
    fun wav(): ByteArray = synchronized(pcm) { wavOf(pcm.toByteArray(), RATE) }

    fun stop() {
        running = false
        reader?.join(500)
        reader = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
    }

    companion object {
        const val RATE = 16_000

        /** 16-bit mono [pcm] at [rate] as a WAV file. */
        fun wavOf(pcm: ByteArray, rate: Int): ByteArray {
            val out = ByteArrayOutputStream(pcm.size + 44)
            fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
            out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
            return out.toByteArray()
        }
    }
}
