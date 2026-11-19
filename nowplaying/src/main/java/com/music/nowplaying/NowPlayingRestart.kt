package com.music.nowplaying

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * After the phone restarts (or the app updates), Now Playing can't simply start again: Android
 * only lets the microphone be used in the background by a service started from the app, or from
 * a tap on its notification. So, if it was on, a notification offers to turn it back on.
 */
class NowPlayingRestart : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!NowPlaying.isOn(context) || !NowPlaying.hasMicrophone(context)) return
        val host = context.applicationContext as? NowPlayingHost ?: return
        NowPlayingService.channel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val open: PendingIntent = host.openNowPlaying(context, null) ?: return
        manager.notify(
            RESUME,
            NotificationCompat.Builder(context, "now_playing")
                .setSmallIcon(R.drawable.ic_now_playing)
                .setContentTitle("Now Playing is paused")
                .setContentText("Tap to carry on recognising music around you")
                .setAutoCancel(true)
                .setSilent(true)
                .setContentIntent(open)
                .build(),
        )
    }

    companion object {
        const val RESUME = 7_302

        /** Clears the "tap to carry on" notification (it's running again). */
        fun dismiss(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(RESUME)
        }
    }
}
