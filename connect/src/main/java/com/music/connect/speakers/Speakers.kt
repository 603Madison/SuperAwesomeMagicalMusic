package com.music.connect.speakers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.music.connect.data.Settings
import com.music.connect.net.ConnectClient
import com.music.connect.net.ConnectDevice
import com.music.connect.net.NetworkState
import com.music.connect.net.ServerApi
import com.music.player.quality.NetworkClass
import com.music.player.speakers.sonos.SonosDiscovery
import com.music.player.speakers.sonos.SonosNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * The MusicCast and Sonos speakers on the Wi-Fi the phone is on. Each becomes one of the person's
 * devices (through this phone), so the web player and the app can play on it and control it,
 * while the phone is at home: the server is somewhere else and can't reach them itself. Sonos
 * rooms playing together are one device ("Living Room + Kitchen").
 */
class Speakers(
    private val context: Context,
    /** For the server (signed in): the relay and the speakers' own Connect links. */
    private val http: OkHttpClient,
    /** For the speakers themselves: devices on the Wi-Fi never get the server's sign-in. */
    private val lan: OkHttpClient,
    private val api: ServerApi,
    private val settings: StateFlow<Settings>,
    private val network: StateFlow<NetworkState>,
    private val scope: CoroutineScope,
    /** The person's devices as the server lists them: a Sonos the server plays on itself isn't added again. */
    private val devices: () -> List<ConnectDevice> = { emptyList() },
    /** Says something to the person (a speaker couldn't play, say). */
    private val tell: (String) -> Unit = {},
) {
    private val relay = StreamRelay(http)
    private val sessions = ConcurrentHashMap<String, SpeakerSession>()
    private val lock = Mutex()
    @Volatile private var active = false

    /** The Sonos household, reached through any speaker found or added (see SonosNetwork). */
    private var sonos: SonosNetwork? = null
    private var sonosAddresses: List<String>? = null

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
                var searchedAt = 0L
                while (true) {
                    val now = System.currentTimeMillis()
                    if (searchedAt == 0L || now - searchedAt >= REFRESH_MS) {
                        searchedAt = now
                        refresh()
                    } else {
                        // Sonos rooms are grouped and ungrouped often: their groups are checked more.
                        refreshSonos()
                    }
                    delay(SONOS_CHECK_MS)
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
            val (musicCast, sonosGroups) = coroutineScope {
                val sonosGroups = async { sonosSpeakers(rediscover = true) }
                musicCastSpeakers() to sonosGroups.await()
            }
            if (active) use(musicCast, sonosGroups)
        } finally {
            _searching.value = false
        }
    }

    /** Checks the Sonos household's groups again (rooms grouped or ungrouped in the Sonos app). */
    private suspend fun refreshSonos() = lock.withLock {
        if (!active) return@withLock
        val sonosGroups = sonosSpeakers(rediscover = false)
        if (active) use(emptyList(), sonosGroups)
    }

    /** The MusicCast speakers on the network and at the addresses typed in. */
    private suspend fun musicCastSpeakers(): Collection<SpeakerInfo> {
        val seen = mutableMapOf<String, SpeakerInfo>()
        for (location in Ssdp.search(context)) {
            runCatching {
                val description = fetch(location)
                if (!MusicCastSpeaker.isMusicCast(description)) return@runCatching
                val host = location.toHttpUrlOrNull()?.host ?: return@runCatching
                val info = MusicCastSpeaker.identify(host, lan, location)
                seen[info.deviceId] = info
            }.onFailure { Log.d(TAG, "Not a speaker at $location: ${it.message}") }
        }
        for (address in settings.value.speakerAddresses) {
            runCatching { MusicCastSpeaker.identify(address.trim(), lan) }
                .onSuccess { seen.putIfAbsent(it.deviceId, it) }
                .onFailure { Log.d(TAG, "No MusicCast speaker at $address: ${it.message}") }
        }
        return seen.values
    }

    /**
     * The Sonos household's groups, through any speaker found on the network or typed in; null
     * if none answered (the ones known are kept until they stop answering themselves).
     */
    private suspend fun sonosSpeakers(rediscover: Boolean): List<SpeakerInfo>? {
        val addresses = settings.value.speakerAddresses
        val network = sonos?.takeIf { sonosAddresses == addresses } ?: SonosNetwork(
            lan, addresses, discover = true,
            discovery = { Ssdp.withMulticast(context) { SonosDiscovery.search() } },
            rediscoverMs = REFRESH_MS, retryDiscoveryMs = REFRESH_MS,
        ).also {
            sonos = it
            sonosAddresses = addresses
        }
        if (rediscover) network.rediscover()
        val groups = network.refresh()
        if (groups.isEmpty()) {
            network.problem?.let { Log.d(TAG, it) }
            return null
        }
        return groups.map(SonosSpeaker::infoOf)
    }

    /** Starts (or renames) a session for each speaker found, and lets go of the ones that went. */
    private fun use(musicCast: Collection<SpeakerInfo>, sonosGroups: List<SpeakerInfo>?) {
        // The server may play on the same Sonos itself (a Sonos source, with the server at home): it's shown once.
        val listed = (devices() + sessions.values.flatMap { it.devices }).map { it.id.lowercase() }.toSet()
        val sonosHere = sonosGroups?.filter { "speaker:sonos-${it.deviceId.lowercase()}" !in listed }
        for (info in musicCast + sonosHere.orEmpty()) {
            val existing = sessions[info.deviceId]
            if (existing != null && (existing.info == info || existing.update(info))) continue
            // A new address (the router gave it another), or a speaker that can't be renamed: start it afresh.
            existing?.stop()
            if (sessions.isEmpty()) relay.start()
            sessions[info.deviceId] = newSession(info).also {
                it.onProblem = tell
                it.start()
            }
        }
        // When Sonos answered, its groups are the whole story: a room that joined another group goes.
        if (sonosHere != null) {
            val groups = sonosHere.map { it.deviceId }.toSet()
            sessions.values.filter { it.info.kind == SpeakerKind.SONOS && it.id !in groups }.forEach { old ->
                old.stop()
                sessions.remove(old.id)
            }
        }
        sessions.values.filter { it.gone }.forEach { gone ->
            gone.stop()
            sessions.remove(gone.id)
        }
        _found.value = sessions.values.map { it.info }.sortedBy { it.name.lowercase() }
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
            fixedId = info.connectId,
            fixedName = info.name,
            detail = info.detail,
        )
        val speaker = when (info.kind) {
            SpeakerKind.MUSICCAST -> MusicCastSpeaker(info, lan)
            SpeakerKind.SONOS -> SonosSpeaker(info, lan)
        }
        return SpeakerSession(
            speaker, hub, api, relay,
            server = { settings.value.server },
            kbps = { settings.value.kbpsFor(NetworkClass.WIFI) },
            scope = scope,
        )
    }

    private suspend fun stopAll() = lock.withLock {
        active = false
        sessions.values.forEach { it.stop() }
        sessions.clear()
        relay.stop()
        sonos = null
        _found.value = emptyList()
        _now.value = emptyList()
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val quick = lan.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
        quick.newCall(Request.Builder().url(url).header("User-Agent", MusicCastSpeaker.USER_AGENT).build()).execute().use { it.body.string() }
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
        private const val SONOS_CHECK_MS = 60L * 1000
    }
}
