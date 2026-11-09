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
import com.music.connect.net.ConnectClient
import com.music.connect.net.Listen
import com.music.connect.net.NetworkMonitor
import com.music.connect.net.ServerApi
import com.music.connect.speakers.Speakers
import com.music.player.AppInfo
import com.music.player.CoreLog
import com.music.player.model.SourceType
import com.music.player.net.Http
import com.music.player.sources.youtube.YouTubeMusicSource
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
import kotlinx.coroutines.flow.drop
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

    /** YouTube Music, asked from the phone itself (see Settings.youtubeOnPhone). */
    val youtube = YouTubeMusicSource(http)
    val db = ConnectDb.build(context)
    val library = Library(db, api) { SyncWorker.enqueue(context) }
    val downloads = Downloads(context, db, api, http, prefs) { DownloadWorker.enqueue(context, settings.value.downloadOnWifiOnly) }
    val signIn = com.music.connect.net.SignIn(context, api, prefs)

    val liveNow = MutableStateFlow<LiveNow?>(null)

    /** This phone among the person's devices (see ConnectClient). */
    val devices = ConnectClient(context, http, { settings.value.server }, { settings.value.token }, scope)

    /** MusicCast speakers on the Wi-Fi, as the person's devices (see Speakers). */
    val speakers = Speakers(context, http, api, settings, network.state, scope) { tell(it) }

    /** The person's favourite channels and stations, shared with the web player (see [refreshFavorites]). */
    val favorites = MutableStateFlow<List<ApiTrack>>(emptyList())

    /** Pandora stations started on this phone, by id: their names, for the heart while they play. */
    private val stations = java.util.concurrent.ConcurrentHashMap<String, ApiTrack>()

    fun rememberStation(station: ApiTrack) {
        if (station.isPandoraStation) station.pandoraStationId?.let { stations[it] = station }
    }

    /**
     * What the heart beside [track] keeps: the channel or radio station itself, or the Pandora
     * station a song came from; null for anything else.
     */
    fun favoriteOf(track: ApiTrack?): ApiTrack? {
        if (track?.via == null) return null
        if (track.isStationTrack) {
            val id = track.pandoraStationId ?: return null
            val sourceId = ApiTrack.PANDORA_STATION + id
            return stations[id]
                ?: favorites.value.firstOrNull { it.sourceId == sourceId && it.via == track.via }
                ?: ApiTrack(SourceType.SIRIUSXM, sourceId, "Pandora station", "Pandora station · SiriusXM", "Pandora artist stations", via = track.via)
        }
        return track.takeIf { it.isLive }
    }

    /** What playing [favorite] plays: the channel or station, or a Pandora station's first songs. */
    suspend fun tracksOf(favorite: ApiTrack): List<ApiTrack> {
        if (!favorite.isPandoraStation) return listOf(favorite)
        val started = api.station(favorite.via ?: return emptyList(), null, favorite.pandoraStationId, favorite.title, false)
        rememberStation(started.station)
        return started.tracks
    }

    /** Fetches the favourites again (when online and signed in). */
    suspend fun refreshFavorites() {
        if (!settings.value.signedIn || !online.value) return
        runCatching { api.liveFavorites() }.onSuccess { favorites.value = it }.onFailure { Log.i(TAG, "Couldn't fetch favourites: ${it.message}") }
    }

    /** Makes [track] a favourite or not: shown at once, and kept on the server (put back if that fails). */
    fun setFavorite(track: ApiTrack, favorite: Boolean) {
        val before = favorites.value
        val others = before.filter { it.key != track.key }
        favorites.value = if (favorite) listOf(track) + others else others
        scope.launch {
            runCatching { api.setLiveFavorite(track, favorite) }
                .onSuccess { favorites.value = it }
                .onFailure {
                    favorites.value = before
                    tell("Couldn't change your favourites: ${it.message}")
                }
        }
    }

    /** Evens out loudness between songs (in the playback service's audio path; the details show it). */
    val loudness = com.music.connect.playback.LoudnessProcessor()

    /** Each song played's stream details (filled in by the playback service), for its details. */
    val streamFacts = MutableStateFlow<Map<String, com.music.connect.playback.StreamFacts>>(emptyMap())

    /** The audio being decoded now (codec, sample rate…), as the player says. */
    val audioDetails = MutableStateFlow<com.music.connect.playback.AudioDetails?>(null)

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
        speakers.start()
        SyncWorker.schedulePeriodic(context)
        // Signed in or out, or another server: this phone's place among the devices follows.
        scope.launch {
            settings.map { it.server to it.token }.distinctUntilChanged().drop(1).collect {
                devices.restart()
                favorites.value = emptyList()
                refreshFavorites()
            }
        }
        // Back online: send what waited (playlist changes, listens) and fetch queued downloads.
        scope.launch {
            online.collect { isOnline ->
                if (isOnline) devices.retry()
                if (isOnline && settings.value.signedIn) {
                    refreshFavorites()
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
