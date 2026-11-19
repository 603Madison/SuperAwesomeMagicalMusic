package com.music.connect.playback

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The DJ on the person's own stations: says its lines (introductions, time checks) over the start
 * of songs, with the music dipped under its voice, as a radio host talks over an intro. Uses the
 * phone's text-to-speech; everything runs on [scope] (the player's thread).
 */
class DjVoice(context: Context, private val player: Player, private val scope: CoroutineScope) {
    @Volatile private var ready = false
    private val said = LinkedHashSet<String>()
    private val waiting = mutableListOf<Job>()
    private var duckJob: Job? = null
    private var dipped = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) scope.launch { setUp() }
    }

    private fun setUp() {
        runCatching { tts.language = Locale.getDefault() }
        tts.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setSpeechRate(1.03f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                scope.launch { dip(true) }
            }

            override fun onDone(utteranceId: String?) {
                scope.launch { if (!tts.isSpeaking) dip(false) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onDone(utteranceId)
        })
        ready = true
    }

    /** Says [text] (once for [id]) at [atMs] on this phone's clock; too late to say over its song, it's left unsaid. */
    fun say(id: String, text: String, atMs: Long) {
        if (!said.add(id)) return
        while (said.size > 100) said.remove(said.first())
        val wait = atMs - System.currentTimeMillis()
        // Too late to say over the start of the song: left unsaid, rather than talking over the middle of it.
        if (wait < -6_000) return
        waiting += scope.launch {
            delay(wait.coerceAtLeast(0))
            if (ready && player.isPlaying) tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        }
        waiting.removeAll { it.isCompleted }
    }

    /** Stops talking (a skip, a pause, another station). */
    fun quiet() {
        waiting.forEach { it.cancel() }
        waiting.clear()
        if (ready) tts.stop()
        dip(false)
    }

    /** The music under the voice: down to a third while it talks, back after, eased. */
    private fun dip(down: Boolean) {
        if (dipped == down) return
        dipped = down
        duckJob?.cancel()
        duckJob = scope.launch {
            val from = player.volume
            val to = if (down) 0.3f else 1f
            for (step in 1..12) {
                player.volume = from + (to - from) * step / 12f
                delay(30)
            }
        }
    }

    fun release() {
        quiet()
        tts.shutdown()
    }
}
