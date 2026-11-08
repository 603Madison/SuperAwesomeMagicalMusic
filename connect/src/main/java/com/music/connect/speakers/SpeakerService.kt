package com.music.connect.speakers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.music.connect.MainActivity
import com.music.connect.R
import com.music.connect.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the app going while a speaker plays from it: the phone hands the speaker each song and
 * moves it on to the next, so it mustn't be put to sleep. Shows what's playing where, with a
 * pause button, and goes away a little after the speakers stop.
 */
class SpeakerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shown: List<SpeakerNow>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val speakers = container.speakers
        if (intent?.action == ACTION_PAUSE) scope.launch { speakers.pauseAll() }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(speakers.now.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        running = true
        holdLocks()
        if (watch == null) watch = scope.launch {
            var quietSince = 0L
            while (true) {
                val now = speakers.now.value
                if (now != shown) {
                    shown = now
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(now))
                }
                if (now.any { it.playing }) {
                    quietSince = 0
                } else {
                    if (quietSince == 0L) quietSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - quietSince > STAY_MS) break
                }
                delay(2_000)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun holdLocks() {
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "samm:speakers")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "samm:speakers")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun notification(now: List<SpeakerNow>): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Playing on speakers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "While a speaker plays from this phone"
                setShowBadge(false)
            })
        }
        val first = now.firstOrNull()
        val title = when {
            first == null -> "Speakers"
            now.size == 1 -> "Playing on ${first.speaker}"
            else -> "Playing on ${now.joinToString(", ") { it.speaker }}"
        }
        val text = first?.let { "${it.track.title} · ${it.track.artist}" } ?: "Ready to play on your speakers"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, SpeakerService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (now.any { it.playing }) addAction(0, "Pause", pause) }
            .build()
    }

    companion object {
        @Volatile var running = false
            private set
        private const val CHANNEL = "speakers"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_PAUSE = "com.music.connect.speakers.PAUSE"
        private const val STAY_MS = 2L * 60 * 1000

        /** Starts it when the app comes back to the front with a speaker playing. */
        fun keepAliveIfPlaying(context: Context) {
            val playing = context.container.speakers.now.value.any { it.playing }
            if (playing && !running) runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, SpeakerService::class.java)) }
        }
    }
}
