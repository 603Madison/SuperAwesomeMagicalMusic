package com.music.player

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class MusicApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The shared services (the core module) log through Android's log and say who they are.
        AppInfo.name = "SuperAwesomeMagicalMusic-Lite"
        AppInfo.version = BuildConfig.VERSION_NAME
        AppInfo.platform = "Android"
        AppInfo.deviceName = android.os.Build.MODEL
        CoreLog.sink = { level, tag, message, error ->
            when (level) {
                CoreLog.Level.DEBUG -> Log.d(tag, message, error)
                CoreLog.Level.INFO -> Log.i(tag, message, error)
                CoreLog.Level.WARN -> Log.w(tag, message, error)
                CoreLog.Level.ERROR -> Log.e(tag, message, error)
            }
        }
        container = AppContainer(this)
        container.start()
    }

    /** WorkManager initializes on first use (the startup initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.INFO).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.httpClient })) }
            .crossfade(true)
            .build()
}
