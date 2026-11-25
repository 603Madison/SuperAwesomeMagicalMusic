package com.music.server.speakers

import com.music.player.speakers.sonos.SonosClient
import com.music.player.speakers.sonos.SonosDiscovery
import com.music.player.speakers.sonos.SonosNetwork
import com.music.server.Services
import com.music.server.music.ApiTrack
import com.music.server.sources.SourceKind
import com.music.server.sources.StoredSource
import com.music.server.speakers.musicassistant.MusicAssistantClient
import com.music.server.speakers.musicassistant.MusicAssistantRenderer
import com.music.server.speakers.sonos.SonosRenderer
import com.music.server.util.NetworkGuard
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory

/**
 * Sonos speakers and Music Assistant players as places to play. Each Sonos or Music Assistant
 * source (shared by the admins, or someone's own) brings its speakers into the devices of the
 * people who use it, next to their web players and phones, where any of their devices can move
 * the music to one and control it. The server plays on them itself (see [SpeakerPlayer]).
 */
class Speakers(
    private val services: Services,
    private val streams: SpeakerStreams = services.speakerStreams,
    /** How Sonos speakers are looked for on the network (a stand-in in tests). */
    private val discovery: suspend () -> Set<String> = { SonosDiscovery.search() },
    private val timing: Timing = Timing(),
) : AutoCloseable {
    /** How often players look at their speakers (shorter in tests). */
    data class Timing(
        val settleMs: Long = SpeakerPlayer.SETTLE_MS,
        val busyPollMs: Long = SpeakerPlayer.BUSY_POLL_MS,
        val idlePollMs: Long = SpeakerPlayer.IDLE_POLL_MS,
    )

    private val players = ConcurrentHashMap<String, SpeakerPlayer>()
    /** Syncs in a row a player's speaker wasn't found: it goes after a few, not at the first blip. */
    private val missing = ConcurrentHashMap<String, Int>()
    /** The source each speaker was last found through. */
    private val origins = ConcurrentHashMap<String, String>()
    private val sonos = ConcurrentHashMap<String, Pair<Long, SonosNetwork>>()
    private val assistants = ConcurrentHashMap<String, Pair<Long, MusicAssistantClient>>()
    private val problems = ConcurrentHashMap<String, String>()
    private val syncing = Mutex()
    private var job: Job? = null

    /** Keeps the speakers up to date, every half a minute. */
    fun start() {
        if (job != null) return
        job = services.scope.launch {
            while (isActive) {
                try {
                    sync()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Updating the speakers failed", e)
                }
                delay(SYNC_MS)
            }
        }
    }

    /** The speakers each person may use, now: shown in their devices, and the rest taken away. */
    suspend fun sync() = syncing.withLock {
        val users = services.users.list().filter { !it.disabled }
        val admins = users.filter { it.isAdmin }.map { it.id }.toSet()
        val audience = mutableMapOf<String, MutableSet<String>>()
        val used = mutableMapOf<String, StoredSource>()
        for (user in users) {
            for (source in services.sources.activeFor(user)) {
                if (source.kind != SourceKind.SONOS && source.kind != SourceKind.MUSIC_ASSISTANT) continue
                used[source.id] = source
                audience.getOrPut(source.id) { mutableSetOf() } += user.id
            }
        }
        // Sources gone (or changed) let go of what they had open.
        sonos.keys.filter { it !in used }.forEach { sonos.remove(it) }
        assistants.keys.filter { it !in used }.forEach { id -> assistants.remove(id)?.second?.close() }

        val found = mutableMapOf<String, Pair<SpeakerTarget, MutableSet<String>>>()
        for ((id, source) in used) {
            val trusted = source.ownerId == null || source.ownerId in admins || services.config.localSources
            val targets = try {
                targetsOf(source, if (trusted) services.http else guarded).also { problems.remove(id) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problems[id] = e.message ?: e.javaClass.simpleName
                log.info("{}: {}", source.name, e.message)
                emptyList()
            }
            for (target in targets) {
                found.getOrPut(target.id) { target to mutableSetOf() }.second += audience[id].orEmpty()
                origins[target.id] = id
            }
        }

        for ((id, entry) in found) {
            val (target, people) = entry
            missing.remove(id)
            val player = players[id]?.also { it.update(target) } ?: SpeakerPlayer(
                target, services.connect, ::media, services.scope, services.clock,
                settleMs = timing.settleMs, busyPollMs = timing.busyPollMs, idlePollMs = timing.idlePollMs,
            ).also {
                players[id] = it
                it.start()
            }
            player.show(people)
        }
        for ((id, player) in players.entries.toList()) {
            if (id in found) continue
            val misses = (missing[id] ?: 0) + 1
            // Still listed as a source's but not answering: kept a little while, in case it's a blip.
            if (misses < KEEP_MISSING && origins[id] in used) {
                missing[id] = misses
                continue
            }
            missing.remove(id)
            origins.remove(id)
            players.remove(id)
            player.close()
        }
    }

    /** What went wrong reaching a source's speakers last time, if anything. */
    fun problem(sourceId: String): String? = problems[sourceId] ?: sonos[sourceId]?.second?.problem

    /** The speakers now, by id (for tests and diagnostics). */
    fun players(): Map<String, SpeakerPlayer> = players.toMap()

    private suspend fun targetsOf(source: StoredSource, http: OkHttpClient): List<SpeakerTarget> = when (source.kind) {
        SourceKind.SONOS -> {
            val network = sonos[source.id]?.takeIf { it.first == source.updatedAt }?.second ?: SonosNetwork(
                http,
                addresses = SpeakerSetup.addresses(source.setting("addresses")),
                discover = source.settingBool("discover", true),
                clock = services.clock,
                discovery = discovery,
            ).also { sonos[source.id] = source.updatedAt to it }
            network.refresh().map { group ->
                val grouped = group.rooms > 1
                SpeakerTarget(
                    id = "sonos-${group.coordinator.uuid}",
                    name = group.name,
                    detail = if (grouped) "Sonos · ${group.rooms} rooms" else "Sonos",
                    host = group.coordinator.host,
                    profile = SpeakerTarget.Profile.SONOS,
                    rendererKey = "${group.coordinator.baseUrl}|$grouped",
                    speakerUrl = source.setting("speakerUrl"),
                ) { SonosRenderer(SonosClient(http, group.coordinator.baseUrl), grouped) }
            }
        }
        SourceKind.MUSIC_ASSISTANT -> {
            val serverUrl = source.setting("serverUrl").orEmpty()
            val client = assistants[source.id]?.takeIf { it.first == source.updatedAt }?.second ?: MusicAssistantClient(
                http, serverUrl, source.secret("token").ifEmpty { null },
            ).also { client ->
                assistants.put(source.id, source.updatedAt to client)?.second?.close()
            }
            val host = serverUrl.toHttpUrlOrNull()?.host ?: serverUrl
            client.players().filter { it.available && !it.hidden && it.syncedTo == null }.map { player ->
                SpeakerTarget(
                    id = "ma-${player.id}",
                    name = player.name,
                    detail = "Music Assistant · ${player.kindLabel}",
                    host = host,
                    profile = SpeakerTarget.Profile.MUSIC_ASSISTANT,
                    rendererKey = "${source.id}|${source.updatedAt}",
                    speakerUrl = source.setting("speakerUrl"),
                ) { MusicAssistantRenderer(client, player.id) }
            }
        }
        else -> emptyList()
    }

    /** What a speaker plays for [track]: the address on this server it can reach. */
    private suspend fun media(target: SpeakerTarget, userId: String, track: ApiTrack, startMs: Long): SpeakerMedia {
        val base = target.speakerUrl?.trimEnd('/')
            ?: services.config.speakerUrl?.trimEnd('/')
            ?: SpeakerStreams.addressToward(target.host, services.config.port)
            ?: throw SpeakerException("Set this server's address for speakers in the source's settings")
        return streams.media(target, base, userId, track, startMs)
    }

    /** For sources people added for themselves: never the server's own machine (see [NetworkGuard]). */
    private val guarded: OkHttpClient by lazy {
        services.http.newBuilder().addNetworkInterceptor(NetworkGuard.interceptor(NetworkGuard.Reach.NO_LOOPBACK)).build()
    }

    override fun close() {
        job?.cancel()
        assistants.values.forEach { it.second.close() }
        assistants.clear()
    }

    companion object {
        private val log = LoggerFactory.getLogger(Speakers::class.java)
        private const val SYNC_MS = 30_000L
        private const val KEEP_MISSING = 3
    }
}

/** Where speakers fetch songs: no sign-in, only a signed address (see [SpeakerStreams]). */
fun Route.speakerRoutes(services: Services) {
    get(SpeakerStreams.PATH + "{payload}/{file}") {
        services.speakerStreams.respond(call, call.parameters["payload"].orEmpty(), call.parameters["file"].orEmpty())
    }
    head(SpeakerStreams.PATH + "{payload}/{file}") {
        services.speakerStreams.respond(call, call.parameters["payload"].orEmpty(), call.parameters["file"].orEmpty(), headOnly = true)
    }
}
