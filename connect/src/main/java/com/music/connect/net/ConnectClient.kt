package com.music.connect.net

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** What a device is playing, as it last said (the server moves the position along). */
@Serializable
data class PlayState(
    val track: ApiTrack? = null,
    val queue: List<ApiTrack> = emptyList(),
    val index: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val volume: Double = 1.0,
    val live: Boolean = false,
    val repeat: String = "off",
)

/** One of the person's devices: a web player, a phone, a speaker. */
@Serializable
data class ConnectDevice(
    val id: String,
    val name: String,
    val kind: String = "web",
    val state: PlayState? = null,
    val active: Boolean = false,
)

/** Something another device asks this one to do ("take" carries the queue it hands over). */
@Serializable
data class ConnectCommand(
    val action: String,
    val positionMs: Long? = null,
    val volume: Double? = null,
    val state: PlayState? = null,
    val from: String? = null,
)

@Serializable
private data class Inbound(val type: String, val devices: List<ConnectDevice>? = null, val you: String? = null, val command: ConnectCommand? = null)

@Serializable
private data class Outbound(val type: String, val state: PlayState? = null, val target: String? = null, val command: ConnectCommand? = null, val to: String? = null)

/**
 * This phone as one of the person's devices, like Spotify Connect: while the app is open or
 * playing, it says what it's playing, hears what the others are doing, and does what they ask.
 */
class ConnectClient(
    private val context: Context,
    http: OkHttpClient,
    private val server: () -> String,
    private val token: () -> String?,
    private val scope: CoroutineScope,
    /** "phone", or "speaker" for a speaker this phone stands in for. */
    private val kind: String = "phone",
    /** The device's id and name, when it isn't this phone. */
    private val fixedId: String? = null,
    private val fixedName: String? = null,
) {
    private val http = http.newBuilder().pingInterval(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val _devices = MutableStateFlow<List<ConnectDevice>>(emptyList())
    val devices: StateFlow<List<ConnectDevice>> = _devices

    /** When [devices] arrived (elapsed real time), for moving positions along. */
    @Volatile var receivedAt: Long = 0
        private set

    private val _commands = MutableSharedFlow<ConnectCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<ConnectCommand> = _commands

    val me: String by lazy { fixedId ?: deviceId() }

    private var socket: WebSocket? = null
    private var users = 0
    private var retries = 0
    private var reconnect: Job? = null
    private var lastState: PlayState? = null

    /** Connects while anything needs it (the playback service, the screens); [stop] for each. */
    @Synchronized
    fun start() {
        users++
        if (users == 1) open()
    }

    @Synchronized
    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            reconnect?.cancel()
            socket?.close(1000, null)
            socket = null
        }
    }

    /** Tries again now (back online, signed in again). */
    @Synchronized
    fun retry() {
        if (users > 0 && socket == null) {
            reconnect?.cancel()
            retries = 0
            open()
        }
    }

    /** Signed in again, to another account or server: the old connection goes, a new one opens. */
    @Synchronized
    fun restart() {
        reconnect?.cancel()
        socket?.let { old ->
            socket = null
            old.close(1000, null)
        }
        _devices.value = emptyList()
        lastState = null
        retries = 0
        if (users > 0) open()
    }

    fun report(state: PlayState) {
        lastState = state
        send(Outbound("state", state = state))
    }

    fun transfer(to: String) = send(Outbound("transfer", to = to))

    fun command(target: String, action: String, positionMs: Long? = null, volume: Double? = null) =
        send(Outbound("command", target = target, command = ConnectCommand(action, positionMs, volume)))

    /** The device the others show (the active one), if it isn't this phone. */
    fun remote(devices: List<ConnectDevice>): ConnectDevice? =
        devices.firstOrNull { it.active && it.id != me && it.state?.track != null }

    /** Where [device]'s song is now. */
    fun positionOf(device: ConnectDevice): Long {
        val state = device.state ?: return 0
        val moved = state.positionMs + if (state.playing && !state.live) android.os.SystemClock.elapsedRealtime() - receivedAt else 0
        return if (state.durationMs > 0) moved.coerceAtMost(state.durationMs) else moved
    }

    private fun send(message: Outbound) {
        socket?.send(ApiJson.encodeToString(Outbound.serializer(), message))
    }

    @Synchronized
    private fun open() {
        val token = token() ?: return
        val base = server().toHttpUrlOrNull() ?: return
        val url = base.newBuilder()
            .addPathSegments("api/connect/ws")
            .addQueryParameter("id", me)
            .addQueryParameter("name", fixedName ?: deviceName())
            .addQueryParameter("kind", kind)
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retries = 0
                lastState?.let { report(it) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { ApiJson.decodeFromString(Inbound.serializer(), text) }.getOrNull() ?: return
                when (message.type) {
                    "devices" -> {
                        receivedAt = android.os.SystemClock.elapsedRealtime()
                        _devices.value = message.devices.orEmpty()
                    }
                    "command" -> message.command?.let { _commands.tryEmit(it) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.i(TAG, "Connection to the server's device list lost: ${t.message}")
                lost(webSocket)
            }
        })
    }

    @Synchronized
    private fun lost(webSocket: WebSocket) {
        if (socket !== webSocket) return
        socket = null
        _devices.value = emptyList()
        if (users == 0) return
        val wait = (1_000L shl retries.coerceAtMost(5)).coerceAtMost(30_000)
        retries++
        reconnect = scope.launch {
            delay(wait)
            synchronized(this@ConnectClient) { if (users > 0 && socket == null) open() }
        }
    }

    private fun deviceId(): String {
        val prefs = context.getSharedPreferences("connect", Context.MODE_PRIVATE)
        prefs.getString("device", null)?.let { return it }
        val id = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("device", id).apply()
        return id
    }

    /** The name the phone goes by ("Maddie's Pixel"), else its model. */
    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.orEmpty().ifBlank { "Phone" }

    private companion object {
        const val TAG = "ConnectClient"
    }
}
