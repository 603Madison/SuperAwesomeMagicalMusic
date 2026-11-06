package com.music.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.session.MediaButtonReceiver
import com.music.player.MainActivity
import com.music.player.R

/** What the widget shows. */
data class WidgetState(
    val title: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val artwork: Bitmap? = null,
)

/**
 * The home-screen widget: what's playing, with previous / play-pause / next. Its buttons are
 * media-button presses, so they work even when the app isn't running (play then picks up where
 * you left off).
 */
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, manager, appWidgetIds, last)
    }

    companion object {
        @Volatile
        private var last = WidgetState()

        /** Shows [state] on every widget placed (does nothing when there are none). */
        fun update(context: Context, state: WidgetState) {
            last = state
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            render(context, manager, ids, state)
        }

        /** Playback stopped (the player was closed): shows play rather than pause. */
        fun showStopped(context: Context) = update(context, last.copy(isPlaying = false))

        fun hasWidgets(context: Context): Boolean = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java)).isNotEmpty()
        }.getOrDefault(false)

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, state: WidgetState) {
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
                setTextViewText(R.id.widget_title, state.title ?: context.getString(R.string.app_name))
                setTextViewText(R.id.widget_artist, state.artist ?: context.getString(R.string.widget_idle))
                if (state.artwork != null) setImageViewBitmap(R.id.widget_artwork, state.artwork)
                else setImageViewResource(R.id.widget_artwork, R.drawable.ic_notification)
                setImageViewResource(R.id.widget_play_pause, if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
                setContentDescription(R.id.widget_play_pause, context.getString(if (state.isPlaying) R.string.widget_pause else R.string.widget_play))
                setOnClickPendingIntent(R.id.widget_previous, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                setOnClickPendingIntent(R.id.widget_play_pause, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                setOnClickPendingIntent(R.id.widget_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT))
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        /** A media-button press for [keyCode], delivered to the app's media button receiver. */
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
