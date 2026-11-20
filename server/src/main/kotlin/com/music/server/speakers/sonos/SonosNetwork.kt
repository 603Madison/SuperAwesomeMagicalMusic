package com.music.server.speakers.sonos

import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory

/**
 * The Sonos speakers one Sonos source reaches: those added by address, and (unless it's turned
 * off) those found on the network. Any one speaker knows the whole household's groups, so the
 * groups are asked of the first that answers.
 */
class SonosNetwork(
    private val http: OkHttpClient,
    addresses: List<String>,
    private val discover: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val discovery: suspend () -> Set<String> = { SonosDiscovery.search() },
) {
    private val added: List<String> = addresses.mapNotNull(SonosClient::baseOf).distinct()
    private var found: Set<String> = emptySet()
    private var discoveredAt = 0L
    @Volatile private var groups: List<SonosGroup> = emptyList()

    /** What went wrong last time, for the source's test. */
    @Volatile var problem: String? = null
        private set

    /** The household's groups now. */
    suspend fun refresh(): List<SonosGroup> {
        val now = clock()
        val due = discoveredAt == 0L || now - discoveredAt > if (found.isEmpty()) RETRY_DISCOVERY_MS else REDISCOVER_MS
        if (discover && due) {
            discoveredAt = now
            found = runCatching { discovery() }.onFailure { log.info("Looking for Sonos speakers failed: {}", it.message) }.getOrDefault(found)
        }
        val known = groups.flatMap { group -> group.members.filter { !it.bridge }.map { it.baseUrl } }
        val seeds = (added + found + known).distinct()
        if (seeds.isEmpty()) {
            problem = if (discover) "No Sonos speakers answered on the network" else "No Sonos speakers added"
            groups = emptyList()
            return groups
        }
        var failure: String? = null
        for (seed in seeds) {
            try {
                val answer = SonosClient(http, seed).topology()
                groups = answer
                problem = if (answer.isEmpty()) "The Sonos at ${seed.substringAfter("://")} listed no speakers" else null
                return answer
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message
            }
        }
        problem = failure ?: "No Sonos speakers answered"
        groups = emptyList()
        return groups
    }

    private companion object {
        private val log = LoggerFactory.getLogger(SonosNetwork::class.java)
        /** Speakers come and go rarely; one that's found stays known through its group. */
        const val REDISCOVER_MS = 10 * 60_000L
        const val RETRY_DISCOVERY_MS = 60_000L
    }
}
