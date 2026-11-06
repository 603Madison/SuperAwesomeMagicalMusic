package com.music.player

import android.content.Context
import android.util.Log
import com.music.player.data.AppDatabase
import com.music.player.diagnostics.AppLog
import com.music.player.data.MissingDetails
import com.music.player.playback.PlaybackDetails
import com.music.player.sources.radio.RadioBrowserClient
import com.music.player.downloads.DownloadRepository
import com.music.player.downloads.OfflinePlaylistWorker
import com.music.player.downloads.OfflinePlaylists
import com.music.player.lidarr.LidarrClient
import com.music.player.lidarr.LidarrQueueWorker
import com.music.player.lidarr.LidarrService
import com.music.player.quality.NetworkClass
import com.music.player.sources.siriusxm.HeardSongs
import com.music.player.sources.siriusxm.SiriusXmClient
import com.music.player.net.Http
import com.music.player.net.NetworkMonitor
import com.music.player.nextcloud.NextcloudClient
import com.music.player.nextcloud.NextcloudSignIn
import com.music.player.nextcloud.SyncStatus
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency graph; one instance lives in [MusicApp]. */
class AppContainer(val context: Context) {
    /** Background work that outlives screens; failures are logged rather than crashing the app. */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("AppContainer", "Background task failed", e) },
    )

    val settings = SettingsRepository(context)
    val appLog = AppLog(File(context.filesDir, "app-log.txt"))

    /** The installed version, e.g. "1.4.0 (10)". */
    val versionDescription: String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("unknown")
    val settingsState: StateFlow<AppSettings> = settings.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    val networkMonitor = NetworkMonitor(context)
    val httpClient = Http.createClient()
    val database = AppDatabase.build(context)

    val jellyfin = JellyfinSource(httpClient, settings)
    val qobuz = QobuzSource(httpClient, settings)
    val youtube = YouTubeMusicSource(httpClient, settings)
    val catalog = Catalog(jellyfin, qobuz, youtube, settings)

    // Lidarr is usually LAN-only: give up quickly when it can't be reached, and queue instead.
    val lidarrClient = LidarrClient(
        httpClient.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    )
    val lidarr = LidarrService(lidarrClient, { settings.current().lidarr }, database.lidarrRequests(), appScope, added = database.lidarrAdded())

    val siriusXm = SiriusXmClient(
        http = httpClient,
        credentials = { settings.current().siriusXm },
        loadSession = { settings.siriusXmSession() },
        saveSession = { settings.setSiriusXmSession(it) },
    )

    val downloads = DownloadRepository(context, database.downloads(), settings)
    val missingDetails = MissingDetails(database.playlists(), database.downloads())
    val radio = RadioBrowserClient(httpClient, userAgent = "SuperAwesomeMagicalMusic/${versionDescription.substringBefore(' ')}")
    /** Songs kept for dead zones (see PlaybackCache). */
    val playbackCache = com.music.player.playback.PlaybackCache(
        context,
        database.cachedCopies(),
        maxBytes = { settingsState.value.cache.sizeMb * 1024L * 1024L },
        enabled = { settingsState.value.cache.enabled },
    )

    val resolver = StreamResolver(
        catalog,
        database.downloads(),
        networkMonitor,
        settings,
        lidarr,
        siriusXm,
        missingDetails,
        appScope,
        radio,
        playbackCache,
    )

    val nextcloud = NextcloudClient(httpClient)
    val playlistSync = PlaylistSyncService(database.playlists(), nextcloud, settings)
    val playlists = PlaylistRepository(
        dao = database.playlists(),
        owner = settings.settings.map { it.nextcloud.owner },
        onChanged = ::onPlaylistsChanged,
    )
    val scrobbler = com.music.player.scrobble.Scrobbler(
        database.scrobbles(),
        { settings.current().scrobbling },
        com.music.player.scrobble.ListenBrainzClient(httpClient),
        com.music.player.scrobble.LastFmClient(httpClient),
    ) { networkMonitor.state.value.isOnline }
    val listening = com.music.player.history.ListeningTracker(database.history(), scrobbler, appScope)
    val playlistImporter by lazy {
        com.music.player.playlists.PlaylistImporter(youtube, jellyfin, com.music.player.playlists.SpotifyPlaylists(httpClient), playlists)
    }
    val heardSongs = HeardSongs(jellyfin, lidarr, playlists) { catalog.findCopyWithArtwork(it, includeJellyfin = false) }
    val offlinePlaylists = OfflinePlaylists(
        database.playlists(),
        database.downloads(),
        downloads,
        catalog.jellyfin,
        missingDetails,
        { catalog.findCopyWithArtwork(it) },
    )
    val nextcloudSignIn = NextcloudSignIn(nextcloud, settings, database.playlists(), playlistSync, appScope)

    val player = PlayerController(context, MainScope())

    /** What's actually being decoded and downloaded for each queue item (filled in by the playback service). */
    val playbackDetails = PlaybackDetails()
    /** The player's volume leveller (see PlaybackService). */
    val loudness = com.music.player.playback.LoudnessProcessor()
    val sleepTimer = com.music.player.playback.SleepTimer()
    val queueStore = com.music.player.playback.QueueStore(File(context.filesDir, "queue.json"))
    val lyrics = com.music.player.lyrics.LyricsRepository(
        httpClient,
        jellyfin,
        userAgent = "SuperAwesomeMagicalMusic/${com.music.player.BuildConfig.VERSION_NAME} (Android)",
    )
    val bookmarks = com.music.player.bookmarks.Bookmarks(File(context.filesDir, "bookmarks.json"))

    private val _playbackErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val playbackErrors: SharedFlow<String> = _playbackErrors

    fun start() {
        networkMonitor.start()
        appScope.launch { settings.ensureDeviceId() }
        appScope.launch { migrateFromAuthentik() }
        OfflinePlaylistWorker.schedulePeriodic(context)
        // Songs that arrive through Nextcloud sync get downloaded too.
        appScope.launch {
            playlistSync.status.collect { if (it is SyncStatus.Done) OfflinePlaylistWorker.enqueue(context) }
        }
        appScope.launch {
            settings.settings.map { it.nextcloud.isConfigured && it.nextcloud.autoSync }.distinctUntilChanged().collect { enabled ->
                PlaylistSyncWorker.schedulePeriodic(context, enabled)
                if (enabled) PlaylistSyncWorker.enqueue(context)
            }
        }
        // Signing in or out switches whose playlists are shown and synced.
        appScope.launch {
            settings.settings.map { it.nextcloud.owner }.distinctUntilChanged().drop(1).collect { onPlaylistsChanged() }
        }
        appScope.launch {
            settings.settings.map { it.lidarr.isConfigured }.distinctUntilChanged().collect { configured ->
                LidarrQueueWorker.schedule(context, configured)
            }
        }
        // Queued Lidarr requests go out when the network changes, e.g. on arriving home.
        appScope.launch {
            networkMonitor.state.map { it.networkClass }.distinctUntilChanged().collect { networkClass ->
                if (networkClass != NetworkClass.NONE) {
                    delay(3_000)
                    lidarr.processInBackground()
                    // Listens made offline go out once back online.
                    runCatching { scrobbler.flush() }.onFailure { Log.w("AppContainer", "Scrobbling failed", it) }
                }
            }
        }
    }

    /** 1.1.0 kept playlists under an authentik user id; hand them to the Nextcloud account. */
    private suspend fun migrateFromAuthentik() {
        val subject = settings.legacyAuthentikSubject() ?: return
        database.playlists().reassign(subject, settings.current().nextcloud.owner)
        settings.clearLegacyAuthentik()
    }

    fun reportPlaybackError(message: String) {
        _playbackErrors.tryEmit(readableError(message))
    }

    private fun onPlaylistsChanged() {
        OfflinePlaylistWorker.enqueue(context)
        val nextcloud = settingsState.value.nextcloud
        if (nextcloud.isConfigured && nextcloud.autoSync) PlaylistSyncWorker.enqueue(context)
    }
}

/**
 * An error message fit to show: printable text only, kept short. Errors can quote whatever a
 * server sent, which may be binary (an audio file read as a playlist filled the screen once).
 */
fun readableError(message: String): String {
    val printable = message.filter { it == ' ' || (!it.isISOControl() && it != '\uFFFD' && Character.isDefined(it)) }
    val text = if (printable.length < message.length * 0.9) printable.take(80).trimEnd() + "… (unreadable data)" else printable
    return if (text.length > MAX_ERROR_LENGTH) text.take(MAX_ERROR_LENGTH).trimEnd() + "…" else text
}

private const val MAX_ERROR_LENGTH = 240
