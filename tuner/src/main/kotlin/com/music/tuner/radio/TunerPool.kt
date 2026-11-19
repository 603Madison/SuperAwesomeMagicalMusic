package com.music.tuner.radio

import com.music.tuner.LineupStore
import com.music.tuner.Station
import com.music.tuner.audio.Listener
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/** Every tuner is playing something someone's listening to (or scanning). */
class AllTunersBusy(message: String) : Exception(message)

class NoSuchStation(id: String) : Exception("There's no station $id: scan for stations, or add it")

/** A dongle, and what it's doing. */
class Tuner(val index: Int, val device: String) {
    internal var receiver: Receiver? = null
    internal var scanning = false
    internal val sessions = LinkedHashMap<String, Session>()

    val busy: Boolean get() = scanning || sessions.values.any { it.listeners > 0 }
}

/** A station playing on a tuner, with however many listeners. */
class Session internal constructor(val station: Station, val tuner: Tuner, val audio: StationAudio, val startedAt: Long) {
    var listeners = 0
        internal set
    internal var idleSince: Long? = null
}

/** One listener's place: hand it back with [TunerPool.leave]. */
class Ticket internal constructor(val session: Session, val listener: Listener)

@Serializable
data class TunerStatus(
    val index: Int,
    val device: String,
    /** idle, playing, waiting (nobody listening, about to stop) or scanning. */
    val state: String,
    val tuning: String? = null,
    val stations: List<String> = emptyList(),
    val listeners: Int = 0,
    val error: String? = null,
)

/** Station logos (from HD Radio), kept so they show while a station isn't playing. */
class ImageStore(private val dir: File) {
    fun logo(stationId: String): File? = dir.listFiles { f -> f.name.startsWith("${safe(stationId)}.logo.") }?.firstOrNull()

    fun saveLogo(stationId: String, file: File) {
        val existing = logo(stationId)
        if (existing != null && existing.length() == file.length() && existing.readBytes().contentEquals(file.readBytes())) return
        dir.mkdirs()
        existing?.delete()
        val ext = file.extension.lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "gif") } ?: "img"
        runCatching { file.copyTo(File(dir, "${safe(stationId)}.logo.$ext"), overwrite = true) }
    }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '.' || it == '-' }

    companion object {
        /** A short fingerprint of an image, so its address changes when it does. */
        fun version(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).take(6).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The dongles and who's listening to what. Listeners of the same station share it; a new station
 * takes a tuner nobody's listening to (one already on the same HD Radio frequency first, since
 * its other programs come free), or there's no room. A station plays on for [graceMs] after its
 * last listener leaves, so tuning straight back is instant, then the tuner goes quiet.
 */
class TunerPool(
    devices: List<String>,
    private val lineup: LineupStore,
    private val factory: ReceiverFactory,
    private val images: ImageStore,
    private val graceMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger("Tuners")
    val tuners: List<Tuner> = devices.mapIndexed { i, device -> Tuner(i, device) }
    private var reaper: Thread? = null

    @Volatile
    private var closed = false

    /** Starts the housekeeping: idle stations stop, and what they were last heard as is kept. */
    fun start() {
        reaper = Thread({
            var tick = 0
            while (!closed) {
                try {
                    Thread.sleep(1_000)
                    reap()
                    if (++tick % 30 == 0) rememberAll()
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    log.warn("Housekeeping failed", e)
                }
            }
        }, "tuner-housekeeping").apply { isDaemon = true; start() }
    }

    /** Starts listening to [stationId]: on a tuner already playing it, else a free one. */
    @Synchronized
    fun listen(stationId: String): Ticket {
        check(!closed) { "The tuner is stopping" }
        val station = lineup.station(stationId) ?: throw NoSuchStation(stationId)
        val session = sessionFor(station)
        session.listeners++
        session.idleSince = null
        return Ticket(session, session.audio.broadcast.subscribe())
    }

    @Synchronized
    fun leave(ticket: Ticket) {
        val session = ticket.session
        session.audio.broadcast.unsubscribe(ticket.listener)
        if (session.listeners > 0) session.listeners--
        if (session.listeners == 0) session.idleSince = clock()
    }

    private fun sessionFor(station: Station): Session {
        tuners.firstNotNullOfOrNull { it.sessions[station.id] }?.let { return it }
        val tuning = Tuning.of(station)
        // An HD Radio frequency already tuned plays its other programs too.
        val tuner = tuners.firstOrNull { !it.scanning && it.receiver?.tuning == tuning && tuning.hd }
            ?: tuners.firstOrNull { !it.scanning && it.receiver == null }
            ?: tuners.filter { !it.scanning && !it.busy }.minByOrNull { t -> t.sessions.values.minOfOrNull { it.idleSince ?: Long.MAX_VALUE } ?: 0L }
            ?: throw AllTunersBusy(busyMessage())
        if (tuner.receiver?.tuning != tuning) {
            stop(tuner)
            log.info("Tuner {} (dongle {}): tuning to {}", tuner.index, tuner.device, tuning)
            tuner.receiver = factory.open(tuner.device, tuning, encode = true)
        }
        val audio = tuner.receiver!!.audio(station)
        return Session(station, tuner, audio, clock()).also { tuner.sessions[station.id] = it }
    }

    private fun busyMessage(): String {
        val playing = tuners.map { t ->
            if (t.scanning) "scanning" else t.sessions.values.joinToString(" and ") { it.station.name }
        }
        return if (tuners.isEmpty()) "There are no tuners" else
            "All ${tuners.size} tuner${if (tuners.size == 1) " is" else "s are"} busy (${playing.joinToString("; ")}). Try again when one is free."
    }

    /** Stops what nobody has listened to for the grace period. */
    @Synchronized
    fun reap() {
        val now = clock()
        for (tuner in tuners) {
            val idle = tuner.sessions.values.filter { s -> s.listeners == 0 && s.idleSince?.let { now - it >= graceMs } == true }
            for (session in idle) {
                remember(session)
                tuner.sessions.remove(session.station.id)
                log.info("Tuner {}: nobody's listening to {}; stopping it", tuner.index, session.station.name)
                if (tuner.sessions.isNotEmpty()) tuner.receiver?.release(session.station)
            }
            if (tuner.sessions.isEmpty() && tuner.receiver != null) stop(tuner)
        }
    }

    private fun stop(tuner: Tuner) {
        tuner.sessions.values.forEach { remember(it); it.audio.broadcast.closeAll() }
        tuner.sessions.clear()
        tuner.receiver?.let { receiver ->
            runCatching { receiver.close() }.onFailure { log.warn("Couldn't stop tuner {} cleanly", tuner.index, it) }
        }
        tuner.receiver = null
    }

    @Synchronized
    private fun rememberAll() = tuners.flatMap { it.sessions.values }.forEach(::remember)

    /** Keeps what a playing station was heard as: its signal, names learned, its logo. */
    private fun remember(session: Session) {
        val live = runCatching { session.audio.live() }.getOrNull() ?: return
        live.logo?.let { images.saveLogo(session.station.id, it) }
        lineup.update(session.station.id) { s ->
            val frequencyName = s.name == s.bandLabel || s.name == s.frequencyLabel
            val learned = when {
                s.isHd -> live.programName ?: live.stationName?.let { "$it HD${(s.program ?: 0) + 1}" }
                else -> live.stationName ?: live.callLetters
            }
            s.copy(
                name = if (frequencyName && learned != null) learned else s.name,
                callLetters = s.callLetters ?: live.callLetters,
                pi = s.pi ?: live.pi,
                genre = s.genre ?: live.genre,
                slogan = live.slogan ?: s.slogan,
                signal = live.signal ?: s.signal,
            )
        }
    }

    /** What's on [stationId] while it plays, or null. */
    @Synchronized
    fun playing(stationId: String): Session? = tuners.firstNotNullOfOrNull { it.sessions[stationId] }

    /** Takes a tuner nobody's listening to, for a scan. */
    @Synchronized
    fun reserve(): Tuner {
        val tuner = tuners.firstOrNull { !it.scanning && it.receiver == null }
            ?: tuners.firstOrNull { !it.busy }
            ?: throw AllTunersBusy(busyMessage())
        stop(tuner)
        tuner.scanning = true
        return tuner
    }

    @Synchronized
    fun release(tuner: Tuner) {
        tuner.scanning = false
    }

    @Synchronized
    fun status(): List<TunerStatus> = tuners.map { t ->
        val listeners = t.sessions.values.sumOf { it.listeners }
        TunerStatus(
            index = t.index,
            device = t.device,
            state = when {
                t.scanning -> "scanning"
                t.receiver == null -> "idle"
                listeners == 0 -> "waiting"
                else -> "playing"
            },
            tuning = t.receiver?.tuning?.toString(),
            stations = t.sessions.keys.toList(),
            listeners = listeners,
            error = t.receiver?.error,
        )
    }

    override fun close() {
        closed = true
        reaper?.interrupt()
        synchronized(this) { tuners.forEach(::stop) }
    }
}
