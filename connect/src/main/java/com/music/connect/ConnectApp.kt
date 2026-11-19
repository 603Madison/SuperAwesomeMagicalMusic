package com.music.connect

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Configuration
import com.music.nowplaying.NowPlayingHost
import com.music.nowplaying.Recognition

class ConnectApp : Application(), Configuration.Provider, NowPlayingHost {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        container.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    /** Now Playing's fingerprints go to the server, which looks for them in the person's library. */
    override suspend fun recognise(hashes: LongArray, heardMs: Long, hint: String?): Recognition? {
        if (!container.settings.value.signedIn) return null
        val result = container.api.listenHashes(hashes, heardMs, hint)
        if (!result.found || result.title == null) return null
        return Recognition(
            title = result.title,
            artist = result.artist.orEmpty(),
            album = result.album,
            artworkUrl = result.artworkUrl,
            positionMs = result.positionMs,
            durationMs = result.song?.durationMs,
            id = result.candidate,
        )
    }

    /** Tapping Now Playing opens Listen, where what it heard is (and can be played). */
    override fun openNowPlaying(context: Context, song: Recognition?): PendingIntent? = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java).setAction(MainActivity.OPEN_LISTEN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

val android.content.Context.container: Container get() = (applicationContext as ConnectApp).container
