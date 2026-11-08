package com.music.server.connect

import com.music.server.music.ApiTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** What a device is playing, as it last said. */
@Serializable
data class PlayState(
    val track: ApiTrack? = null,
    /** The whole queue: sent when playback moves to another device, not in the device list. */
    val queue: List<ApiTrack> = emptyList(),
    val index: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    /** 0 to 1. */
    val volume: Double = 1.0,
    val live: Boolean = false,
    /** The device's repeat setting ("off", "all", "one"). */
    val repeat: String = "off",
)

/** A device signed in to the person's account, and what it's playing. */
@Serializable
data class DeviceView(
    val id: String,
    val name: String,
    /** "web", "phone" or "speaker". */
    val kind: String,
    val state: PlayState? = null,
    /** The device playing, or the one that played last: what the others show and control. */
    val active: Boolean = false,
)

/**
 * Something for a device to do: "play", "pause", "next", "previous", "seek" ([positionMs]),
 * "volume" ([volume]), or "take" (start playing [state], handed over from another device).
 */
@Serializable
data class Command(
    val action: String,
    val positionMs: Long? = null,
    val volume: Double? = null,
    val state: PlayState? = null,
    /** The device that asked. */
    val from: String? = null,
)

/** What a device sends: its state, a command for another device, or to move playback. */
@Serializable
data class Inbound(
    val type: String,
    val state: PlayState? = null,
    val target: String? = null,
    val command: Command? = null,
    val to: String? = null,
)

/** What the server sends: the person's devices (with which one is this), or a command. */
@Serializable
data class Outbound(
    val type: String,
    val devices: List<DeviceView>? = null,
    val you: String? = null,
    val command: Command? = null,
)

/**
 * The devices each person has open (web players, phones), the way Spotify Connect works: each
 * says what it's playing; any can see the others, control the one playing, or move playback to
 * another (which carries on from the same moment in the same queue). One device plays at a time:
 * when one starts, the others pause.
 */
class ConnectHub(private val clock: () -> Long = System::currentTimeMillis) {
    class Device internal constructor(
        val id: String,
        val userId: String,
        val name: String,
        val kind: String,
        internal val send: suspend (String) -> Unit,
    ) {
        @Volatile internal var state: PlayState? = null
        /** When [state] was reported, on the server's clock. */
        @Volatile internal var reportedAt: Long = 0
        @Volatile internal var playedAt: Long = 0
    }

    private val people = ConcurrentHashMap<String, MutableMap<String, Device>>()

    suspend fun join(userId: String, id: String, name: String, kind: String, send: suspend (String) -> Unit): Device {
        val device = Device(id, userId, name.take(80).ifBlank { "Device" }, kind.takeIf { it in KINDS } ?: "web", send)
        val devices = people.computeIfAbsent(userId) { ConcurrentHashMap() }
        // The same device connecting again (a reload, a new network) takes over its old place.
        devices.put(id, device)?.let { old -> device.state = old.state; device.reportedAt = old.reportedAt; device.playedAt = old.playedAt }
        announce(userId)
        return device
    }

    suspend fun leave(device: Device) {
        val devices = people[device.userId] ?: return
        if (devices.remove(device.id, device)) announce(device.userId)
    }

    /** A message from [device]: anything that can't be read is ignored. */
    suspend fun receive(device: Device, text: String) {
        val message = runCatching { HubJson.decodeFromString(Inbound.serializer(), text) }.getOrNull() ?: return
        when (message.type) {
            "state" -> message.state?.let { report(device, it) }
            "command" -> {
                val target = message.target ?: return
                val command = message.command ?: return
                if (command.action !in REMOTE_ACTIONS) return
                command(device.userId, target, command.copy(from = device.id, state = null))
            }
            "transfer" -> message.to?.let { transfer(device.userId, it) }
        }
    }

    suspend fun report(device: Device, state: PlayState) {
        val now = clock()
        val wasPlaying = device.state?.playing == true
        device.state = state.copy(queue = state.queue.take(MAX_QUEUE))
        device.reportedAt = now
        if (state.playing) {
            device.playedAt = now
            // Starting here pauses the others.
            if (!wasPlaying) {
                devicesOf(device.userId).filter { it !== device && it.state?.playing == true }.forEach { other ->
                    other.state = other.state?.copy(playing = false)
                    send(other, Outbound("command", command = Command("pause", from = device.id)))
                }
            }
        }
        announce(device.userId)
    }

    suspend fun command(userId: String, targetId: String, command: Command) {
        val target = people[userId]?.get(targetId) ?: return
        send(target, Outbound("command", command = command))
    }

    /** Moves playback to [toId]: the device playing (or last played) stops, and [toId] carries on. */
    suspend fun transfer(userId: String, toId: String) {
        val devices = devicesOf(userId)
        val to = devices.firstOrNull { it.id == toId } ?: return
        val from = active(devices) ?: return
        if (from === to) return
        val state = from.state?.takeIf { it.track != null } ?: return
        val handed = state.copy(positionMs = positionNow(from, state), playing = true)
        if (state.playing) send(from, Outbound("command", command = Command("pause", from = to.id)))
        from.state = state.copy(playing = false, positionMs = handed.positionMs)
        send(to, Outbound("command", command = Command("take", state = handed, from = from.id)))
        announce(userId)
    }

    /** The person's devices, as the others show them. */
    fun devices(userId: String): List<DeviceView> {
        val devices = devicesOf(userId)
        val active = active(devices)
        return devices.sortedBy { it.name.lowercase() }.map { device ->
            DeviceView(
                id = device.id,
                name = device.name,
                kind = device.kind,
                state = device.state?.let { it.copy(queue = emptyList(), positionMs = positionNow(device, it)) },
                active = device === active,
            )
        }
    }

    private fun devicesOf(userId: String): List<Device> = people[userId]?.values?.toList().orEmpty()

    /** The device playing, else the one that played most recently. */
    private fun active(devices: List<Device>): Device? =
        devices.filter { it.state?.track != null }.maxWithOrNull(compareBy<Device> { it.state?.playing == true }.thenBy { it.playedAt })

    /** Where [device]'s song is now: as reported, plus the time since if it's playing. */
    private fun positionNow(device: Device, state: PlayState): Long {
        if (!state.playing || state.live) return state.positionMs
        val moved = state.positionMs + (clock() - device.reportedAt).coerceAtLeast(0)
        return if (state.durationMs > 0) moved.coerceAtMost(state.durationMs) else moved
    }

    private suspend fun announce(userId: String) {
        val list = devices(userId)
        devicesOf(userId).forEach { device -> send(device, Outbound("devices", devices = list, you = device.id)) }
    }

    private suspend fun send(device: Device, message: Outbound) {
        try {
            device.send(HubJson.encodeToString(Outbound.serializer(), message))
        } catch (e: Exception) {
            // A device that went away is dropped when its connection closes.
            log.debug("Couldn't reach device {}: {}", device.id, e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConnectHub::class.java)
        val HubJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val KINDS = setOf("web", "phone", "speaker")
        private val REMOTE_ACTIONS = setOf("play", "pause", "next", "previous", "seek", "volume")
        const val MAX_QUEUE = 500
    }
}
