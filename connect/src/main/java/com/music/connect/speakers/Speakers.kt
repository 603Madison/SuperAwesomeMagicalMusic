package com.music.connect.speakers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.music.connect.data.Settings
import com.music.connect.net.ConnectClient
import com.music.connect.net.NetworkState
import com.music.connect.net.ServerApi
import com.music.player.quality.NetworkClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The MusicCast speakers on the Wi-Fi the phone is on. Each becomes one of the person's devices
 * (through this phone), so the web player and the app can play on it and control it, while the
 * phone is at home: the server is somewhere else and can't reach them itself.
 */
class Speakers(
    private val context: Context,
    private val http: OkHttpClient,
    private val api: ServerApi,
    private val settings: StateFlow<Settings>,
    private val network: StateFlow<NetworkState>,
    private val scope: CoroutineScope,
    /** Says something to the person (a speaker couldn't play, say). */
    private val tell: (String) -> Unit = {},
) {
    private val relay = StreamRelay(http)
    private val sessions = ConcurrentHashMap<String, SpeakerSession>()
    private val lock = Mutex()
    @Volatile private var active = false

    private val _found = MutableStateFlow<List<SpeakerInfo>>(emptyList())
    /** The speakers in use, by name. */
    val found: StateFlow<List<SpeakerInfo>> = _found

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private val _now = MutableStateFlow<List<SpeakerNow>>(emptyList())
    /** What's playing on them from this app (the notification shows it). */
    val now: StateFlow<List<SpeakerNow>> = _now

    fun start() {
        scope.launch {
            val wanted = settings.map { Triple(it.signedIn && it.speakers, it.server, it.token) }.distinctUntilChanged()
            val onWifi = network.map { it.networkClass == NetworkClass.WIFI }.distinctUntilChanged()
            combine(wanted, onWifi) { (use, _, _), wifi -> use && wifi }.collectLatest { use ->
                stopAll()
                if (!use) return@collectLatest
                active = true
                while (true) {
                    refresh()
                    delay(REFRESH_MS)
                }
            }
        }
        // What's playing on them, for the notification (and to keep the app going while it plays).
        scope.launch {
            while (true) {
                val playing = sessions.values.mapNotNull { it.now.value }
                if (playing != _now.value) _now.value = playing
                if (playing.any { it.playing }) keepAlive()
                delay(1_000)
            }
        }
    }

    /** Looks for speakers again (on the network, and at the addresses typed in). */
    suspend fun refresh() = lock.withLock {
        if (!active) return@withLock
        _searching.value = true
        try {
            val seen = mutableMapOf<String, SpeakerInfo>()
            for (location in Ssdp.search(context)) {
                runCatching {
                    val description = fetch(location)
                    if (!MusicCastSpeaker.isMusicCast(description)) return@runCatching
                    val host = location.toHttpUrlOrNull()?.host ?: return@runCatching
                    val info = MusicCastSpeaker.identify(host, http, location)
                    seen[info.deviceId] = info
                }.onFailure { Log.d(TAG, "Not a speaker at $location: ${it.message}") }
            }
            for (address in settings.value.speakerAddresses) {
                runCatching { MusicCastSpeaker.identify(address.trim(), http) }
                    .onSuccess { seen.putIfAbsent(it.deviceId, it) }
                    .onFailure { Log.i(TAG, "No speaker at $address: ${it.message}") }
            }
            if (!active) return@withLock
            for (info in seen.values) {
                val existing = sessions[info.deviceId]
                // A new address (the router gave it another) or a new name: start it afresh.
                if (existing != null && existing.info == info) continue
                existing?.stop()
                if (sessions.isEmpty()) relay.start()
                sessions[info.deviceId] = newSession(info).also {
                    it.onProblem = tell
                    it.start()
                }
            }
            sessions.values.filter { it.gone }.forEach { gone ->
                gone.stop()
                sessions.remove(gone.id)
            }
            _found.value = sessions.values.map { it.info }.sortedBy { it.name.lowercase() }
        } finally {
            _searching.value = false
        }
    }

    /** What last went wrong with the speaker [deviceId], if anything. */
    fun problemOf(deviceId: String): StateFlow<String?>? = sessions[deviceId]?.problem

    /** Pauses whatever they're playing from this app (the notification's button). */
    suspend fun pauseAll() {
        sessions.values.forEach { it.pause() }
    }

    private fun newSession(info: SpeakerInfo): SpeakerSession {
        val hub = ConnectClient(
            context, http, { settings.value.server }, { settings.value.token }, scope,
            kind = "speaker",
            fixedId = "musiccast-" + info.deviceId.lowercase().filter { it.isLetterOrDigit() }.take(40),
            fixedName = info.name,
        )
        return SpeakerSession(
            MusicCastSpeaker(info, http), hub, api, relay,
            server = { settings.value.server },
            kbps = { settings.value.kbpsFor(NetworkClass.WIFI, savingData = false) },
            scope = scope,
        )
    }

    private suspend fun stopAll() = lock.withLock {
        active = false
        sessions.values.forEach { it.stop() }
        sessions.clear()
        relay.stop()
        _found.value = emptyList()
        _now.value = emptyList()
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val quick = http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
        quick.newCall(Request.Builder().url(url).header("User-Agent", MusicCastSpeaker.USER_AGENT).build()).execute().use { it.body?.string().orEmpty() }
    }

    /** Keeps the app running while a speaker plays (the phone hands it each song). */
    private var triedAt = 0L

    private fun keepAlive() {
        if (SpeakerService.running) return
        val now = System.currentTimeMillis()
        if (now - triedAt < 60_000) return
        triedAt = now
        runCatching { ContextCompat.startForegroundService(context, Intent(context, SpeakerService::class.java)) }
            // Android doesn't let an app in the background start one; it's tried again when the app opens.
            .onFailure { Log.i(TAG, "Couldn't keep the app running for the speakers: ${it.message}") }
    }

    companion object {
        private const val TAG = "Speakers"
        private const val REFRESH_MS = 10L * 60 * 1000
    }
}
