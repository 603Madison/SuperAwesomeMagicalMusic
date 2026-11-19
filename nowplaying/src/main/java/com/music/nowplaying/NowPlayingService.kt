package com.music.nowplaying

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.music.player.listen.Fingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Now Playing at work: a foreground service (Android requires one to use the microphone in the
 * background) whose notification is also where the song shows. Every so often it listens for
 * three seconds; if that sounds like music, for seven more, works out the fingerprints on the
 * phone and asks the host what they are.
 */
class NowPlayingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val ok = runCatching { ServiceCompat.startForeground(this, NOTIFICATION, notification(null), type) }
            .onFailure { Log.w(TAG, "Couldn't start listening in the background", it) }
            .isSuccess
        if (!ok || !NowPlaying.isOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            scope.launch { listen() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        NowPlaying.setStatus(NowPlaying.Status.OFF)
        NowPlaying.setCurrent(null)
        super.onDestroy()
    }

    private suspend fun listen() {
        val host = application as? NowPlayingHost
        if (host == null) {
            Log.w(TAG, "The app doesn't host Now Playing")
            stopSelf()
            return
        }
        val pacing = Pacing(NowPlaying.everySeconds(this) * 1000L)
        var showing: Recognition? = null
        var missedSince = 0L
        while (scope.isActive) {
            NowPlaying.setStatus(NowPlaying.Status.LISTENING)
            val wait = try {
                when (val heard = if (moment()) hear() else null) {
                    null -> pacing.quiet()
                    else -> {
                        NowPlaying.setStatus(NowPlaying.Status.RECOGNISING)
                        val (samples, ms) = heard
                        val hashes = withContext(Dispatchers.Default) { Fingerprint.hashes(samples, Fingerprint.CLIP) }
                        val song = if (hashes.isEmpty()) null else host.recognise(hashes, ms, showing?.id)
                        if (song != null) {
                            showing = song
                            missedSince = 0
                            NowPlaying.remember(this, song)
                            pacing.recognised(song)
                        } else {
                            pacing.missed()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't recognise what's playing: ${e.message}")
                pacing.missed()
            }
            // A song stays showing until it should be over and a listen or two finds something else (or nothing).
            val now = System.currentTimeMillis()
            val song = showing
            if (song != null && (song.remainingMs(now) ?: 0) <= 0 && now - song.at > 60_000) {
                if (missedSince == 0L) missedSince = now else if (now - missedSince > 90_000) showing = null
            }
            NowPlaying.setCurrent(showing)
            show(showing)
            NowPlaying.setStatus(NowPlaying.Status.WAITING)
            delay(wait)
        }
    }

    /**
     * Whether now is a moment to listen: not while the phone plays music itself (it knows what
     * that is), during a call, in battery saver, or with the battery low and not charging.
     */
    private fun moment(): Boolean {
        val audio = getSystemService(AudioManager::class.java)
        if (audio != null && (audio.isMusicActive || audio.mode != AudioManager.MODE_NORMAL)) return false
        if (getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) return false
        val battery = getSystemService(BatteryManager::class.java)
        if (battery != null) {
            val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0 until LOW_BATTERY && !battery.isCharging) return false
        }
        return true
    }

    /** Three seconds; if they sound like music, seven more. The sound and how long it is, or null. */
    private suspend fun hear(): Pair<FloatArray, Long>? = withContext(Dispatchers.IO) {
        val mic = AmbientMic(this@NowPlayingService)
        if (!mic.open()) return@withContext null
        try {
            val first = mic.read(PROBE_MS)
            if (!Sound.worthRecognising(first, AmbientMic.RATE)) return@withContext null
            val rest = mic.read(CLIP_MS - PROBE_MS)
            val all = first + rest
            all to all.size * 1000L / AmbientMic.RATE
        } finally {
            mic.close()
        }
    }

    private fun show(song: Recognition?) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION, notification(song))
    }

    private fun notification(song: Recognition?): Notification {
        val host = application as? NowPlayingHost
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_now_playing)
            .setContentTitle(song?.title ?: "Now Playing")
            .setContentText(song?.artist ?: "Listening for music around you")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // On the lock screen, as it is: a song's name is nothing private.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(host?.openNowPlaying(this, song))
            .build()
    }

    companion object {
        private const val TAG = "NowPlaying"
        private const val CHANNEL = "now_playing"
        private const val NOTIFICATION = 7_301
        private const val PROBE_MS = 3_000L
        private const val CLIP_MS = 10_000L
        private const val LOW_BATTERY = 15

        internal fun channel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Now Playing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The song playing around you, and that Now Playing is on"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }
}
