package com.music.connect

import android.content.Context
import android.os.Build
import android.util.Log
import com.music.connect.data.ConnectDb
import com.music.connect.data.DownloadWorker
import com.music.connect.data.Downloads
import com.music.connect.data.Library
import com.music.connect.data.ListenRow
import com.music.connect.data.Prefs
import com.music.connect.data.Settings
import com.music.connect.data.SyncWorker
import com.music.connect.net.ApiJson
import com.music.connect.net.ApiTrack
import com.music.connect.net.Listen
import com.music.connect.net.NetworkMonitor
import com.music.connect.net.ServerApi
import com.music.player.AppInfo
import com.music.player.CoreLog
import com.music.player.net.Http
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** What's on the live channel or station playing, when it says. */
data class LiveNow(val title: String, val artist: String, val artworkUrl: String?, val song: ApiTrack?)

/** Everything the app is made of; one lives in [ConnectApp]. */
class Container(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Background task failed", e) })
    val prefs = Prefs(context)

    /** The settings, kept current (read once at start so the first requests know the server). */
    val settings: StateFlow<Settings> = prefs.settings.stateIn(scope, SharingStarted.Eagerly, runBlocking { prefs.current() })

    val network = NetworkMonitor(context)

    val http = Http.createClient().newBuilder()
        .addInterceptor(ServerApi.authInterceptor({ settings.value.server }, { settings.value.token }))
        .build()

    val api = ServerApi(http, { settings.value.server }, { settings.value.token })
    val db = ConnectDb.build(context)
    val library = Library(db, api) { SyncWorker.enqueue(context) }
    val downloads = Downloads(context, db, api, http, prefs) { DownloadWorker.enqueue(context, settings.value.downloadOnWifiOnly) }
    val signIn = com.music.connect.net.SignIn(context, api, prefs)

    val liveNow = MutableStateFlow<LiveNow?>(null)

    /** What's playing came from where, at what quality (filled in by the playback service). */
    val playingDescription = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val online: StateFlow<Boolean> = network.state.map { it.isOnline }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    fun start() {
        AppInfo.name = "SuperAwesomeMagicalMusic"
        AppInfo.version = BuildConfig.VERSION_NAME
        AppInfo.platform = "Android"
        AppInfo.deviceName = Build.MODEL
        CoreLog.sink = { level, tag, message, error ->
            when (level) {
                CoreLog.Level.DEBUG -> Log.d(tag, message, error)
                CoreLog.Level.INFO -> Log.i(tag, message, error)
                CoreLog.Level.WARN -> Log.w(tag, message, error)
                CoreLog.Level.ERROR -> Log.e(tag, message, error)
            }
        }
        network.start()
        SyncWorker.schedulePeriodic(context)
        // Back online: send what waited (playlist changes, listens) and fetch queued downloads.
        scope.launch {
            online.collect { isOnline ->
                if (isOnline && settings.value.signedIn) {
                    SyncWorker.enqueue(context)
                    DownloadWorker.enqueue(context, settings.value.downloadOnWifiOnly)
                }
            }
        }
    }

    fun tell(message: String) {
        _messages.tryEmit(message)
    }

    /** Keeps a listen to send to the server (now, or when back online). */
    fun recordListen(track: ApiTrack, listenedMs: Long, playedAt: Long) {
        if (!track.isSong && !track.isStationTrack) return
        scope.launch {
            val listen = Listen(Library.asSong(track), playedAt, listenedMs)
            db.listens().add(ListenRow(listen = ApiJson.encodeToString(Listen.serializer(), listen)))
            if (online.value) runCatching { flushListens() }
        }
    }

    suspend fun flushListens() {
        while (true) {
            val batch = db.listens().batch()
            if (batch.isEmpty()) return
            val listens = batch.mapNotNull { runCatching { ApiJson.decodeFromString(Listen.serializer(), it.listen) }.getOrNull() }
            api.addListens(listens)
            db.listens().delete(batch.map { it.id })
        }
    }

    /** Queues downloads for every song in the playlists kept offline. */
    suspend fun keepOfflineDownloaded() {
        val tracks = library.offlinePlaylists().flatMap { library.entriesNow(it) }.map { it.track }
        downloads.queue(tracks)
    }

    /** The server no longer accepts this phone's sign-in (signed out from the server, say). */
    fun signedOutByServer() {
        scope.launch {
            prefs.signOut()
            tell("Signed out by the server; sign in again")
        }
    }

    suspend fun signOut() {
        runCatching { api.logout() }
        prefs.signOut()
        db.pending().clear()
    }

    private companion object {
        const val TAG = "Container"
    }
}
