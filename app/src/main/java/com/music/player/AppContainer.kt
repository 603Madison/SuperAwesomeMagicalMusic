package com.music.player

import android.content.Context
import android.util.Log
import com.music.player.data.AppDatabase
import com.music.player.downloads.DownloadRepository
import com.music.player.lidarr.LidarrClient
import com.music.player.lidarr.LidarrService
import com.music.player.net.Http
import com.music.player.net.NetworkMonitor
import com.music.player.nextcloud.NextcloudClient
import com.music.player.nextcloud.PlaylistSyncService
import com.music.player.nextcloud.PlaylistSyncWorker
import com.music.player.playback.PlayerController
import com.music.player.playback.StreamResolver
import com.music.player.playlists.PlaylistRepository
import com.music.player.settings.AppSettings
import com.music.player.settings.SettingsRepository
import com.music.player.sources.Catalog
import com.music.player.sources.jellyfin.JellyfinSource
import com.music.player.sources.qobuz.QobuzSource
import com.music.player.sources.youtube.YouTubeMusicSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Hand-rolled dependency graph; one instance lives in [MusicApp]. */
class AppContainer(private val context: Context) {
    /** Background work that outlives screens; failures are logged rather than crashing the app. */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("AppContainer", "Background task failed", e) },
    )

    val settings = SettingsRepository(context)
    val settingsState: StateFlow<AppSettings> = settings.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    val networkMonitor = NetworkMonitor(context)
    val httpClient = Http.createClient()
    val database = AppDatabase.build(context)

    val jellyfin = JellyfinSource(httpClient, settings)
    val qobuz = QobuzSource(httpClient, settings)
    val youtube = YouTubeMusicSource(httpClient, settings)
    val catalog = Catalog(jellyfin, qobuz, youtube, settings)

    val lidarrClient = LidarrClient(httpClient)
    val lidarr = LidarrService(lidarrClient, settings, appScope)

    val downloads = DownloadRepository(context, database.downloads(), settings)
    val resolver = StreamResolver(catalog, database.downloads(), networkMonitor, settings, lidarr)

    val nextcloud = NextcloudClient(httpClient)
    val playlistSync = PlaylistSyncService(database.playlists(), nextcloud, settings)
    val playlists = PlaylistRepository(database.playlists(), onChanged = ::requestPlaylistSync)

    val player = PlayerController(context, MainScope())

    private val _playbackErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val playbackErrors: SharedFlow<String> = _playbackErrors

    fun start() {
        networkMonitor.start()
        appScope.launch { settings.ensureDeviceId() }
        appScope.launch {
            settings.settings.map { it.nextcloud.isConfigured && it.nextcloud.autoSync }.distinctUntilChanged().collect { enabled ->
                PlaylistSyncWorker.schedulePeriodic(context, enabled)
                if (enabled) PlaylistSyncWorker.enqueue(context)
            }
        }
    }

    fun reportPlaybackError(message: String) {
        _playbackErrors.tryEmit(message)
    }

    private fun requestPlaylistSync() {
        val nextcloud = settingsState.value.nextcloud
        if (nextcloud.isConfigured && nextcloud.autoSync) PlaylistSyncWorker.enqueue(context)
    }
}
