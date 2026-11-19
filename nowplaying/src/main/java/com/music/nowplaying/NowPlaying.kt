package com.music.nowplaying

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Now Playing: turn it on (from the app, while it's open: Android only lets the microphone be used
 * in the background by a service started while the app is in front), and every minute or so the
 * phone listens for a few seconds. When there's music, its fingerprints are recognised (see
 * [NowPlayingHost]) and the song shows in a quiet notification, on the lock screen too.
 */
object NowPlaying {
    private const val PREFS = "now_playing"
    private const val KEY_ON = "on"
    private const val KEY_EVERY = "every_seconds"
    private const val MAX_HISTORY = 200

    /** What Now Playing is doing, for the app to show. */
    enum class Status { OFF, WAITING, LISTENING, RECOGNISING }

    private val statusFlow = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = statusFlow.asStateFlow()

    private val currentFlow = MutableStateFlow<Recognition?>(null)

    /** The song playing around the phone now (as last recognised), or null. */
    val current: StateFlow<Recognition?> = currentFlow.asStateFlow()

    private val historyFlow = MutableStateFlow<List<Recognition>?>(null)

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    /** How often it listens, in seconds (30 to 300). */
    fun everySeconds(context: Context): Int = prefs(context).getInt(KEY_EVERY, 60).coerceIn(30, 300)

    fun setEverySeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_EVERY, seconds.coerceIn(30, 300)).apply()
    }

    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Turns it on and starts listening. Call it while the app is in front, with the microphone
     * permission granted (false if it isn't).
     */
    fun turnOn(context: Context): Boolean {
        if (!hasMicrophone(context)) return false
        prefs(context).edit().putBoolean(KEY_ON, true).apply()
        start(context)
        return true
    }

    fun turnOff(context: Context) {
        prefs(context).edit().putBoolean(KEY_ON, false).apply()
        context.stopService(Intent(context, NowPlayingService::class.java))
        statusFlow.value = Status.OFF
        currentFlow.value = null
    }

    /** Starts it if it's on but not running (the app opened after a restart, say). */
    fun resume(context: Context) {
        if (isOn(context) && hasMicrophone(context) && statusFlow.value == Status.OFF) start(context)
    }

    private fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, NowPlayingService::class.java))
    }

    /** Songs recognised, newest first (kept on the phone). */
    fun history(context: Context): List<Recognition> {
        historyFlow.value?.let { return it }
        val loaded = runCatching { Json.decodeFromString(ListSerializer(Recognition.serializer()), historyFile(context).readText()) }.getOrDefault(emptyList())
        historyFlow.value = loaded
        return loaded
    }

    fun clearHistory(context: Context) {
        historyFlow.value = emptyList()
        historyFile(context).delete()
    }

    internal fun setStatus(status: Status) {
        statusFlow.value = status
    }

    internal fun setCurrent(song: Recognition?) {
        currentFlow.value = song
    }

    /** Keeps [song] in the history (once: hearing it again a little later only updates when). */
    internal fun remember(context: Context, song: Recognition) {
        val before = history(context)
        val latest = before.firstOrNull()
        val updated = if (latest != null && latest.sameSong(song) && song.at - latest.at < 15 * 60_000) {
            listOf(song) + before.drop(1)
        } else {
            (listOf(song) + before).take(MAX_HISTORY)
        }
        historyFlow.value = updated
        runCatching { historyFile(context).writeText(Json.encodeToString(ListSerializer(Recognition.serializer()), updated)) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun historyFile(context: Context) = File(context.filesDir, "now_playing_history.json")
}
