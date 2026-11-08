package com.music.player.sources.radio

import com.music.player.CoreLog
import com.music.player.net.AppJson
import com.music.player.net.fetchString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Radio stations from Radio Browser (radio-browser.info), a free, open directory of tens of
 * thousands of stations, searchable by name and by location.
 *
 * As the API asks, servers are looked up from DNS rather than hard-coded (with known servers as
 * a fallback), the app identifies itself in the User-Agent, and each station played is counted.
 */
class RadioBrowserClient(
    http: OkHttpClient,
    private val userAgent: String,
    private val lookUpServers: suspend () -> List<String> = ::serversFromDns,
    private val scheme: String = "https",
    /** Long-standing servers, tried before the ones DNS lists. */
    private val knownServers: List<String> = FALLBACK_SERVERS,
) {
    // A server that's down should cost seconds, not the patient timeouts satellite links need.
    private val http = http.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private var servers: List<String>? = null

    /** The server that answered last, tried first next time. */
    @Volatile
    private var lastGood: String? = null
    private val known = ConcurrentHashMap<String, RadioStation>()

    /**
     * Stations near a location, most listened to first: those listed within [radiusKm] (only
     * some stations give a location), together with those listed in your [state], if known.
     */
    suspend fun nearby(
        latitude: Double,
        longitude: Double,
        state: String? = null,
        countryCode: String? = null,
        radiusKm: Int = 80,
        limit: Int = 100,
    ): List<RadioStation> = coroutineScope {
        val byLocation = async {
            stations("json/stations/search") {
                addQueryParameter("geo_lat", latitude.toString())
                addQueryParameter("geo_long", longitude.toString())
                addQueryParameter("geo_distance", (radiusKm * 1000).toString())
                popularFirst(limit)
            }
        }
        val byState = async {
            if (state.isNullOrBlank()) emptyList()
            else runCatching {
                stations("json/stations/search") {
                    addQueryParameter("state", state)
                    countryCode?.takeIf { it.isNotBlank() }?.let { addQueryParameter("countrycode", it) }
                    popularFirst(limit)
                }
            }.getOrDefault(emptyList())
        }
        withoutDuplicates(byLocation.await() + byState.await()).sortedByDescending { it.clickCount }.take(limit)
    }

    /** Stations whose name matches [query], most listened to first; [countryCode] narrows it. */
    suspend fun search(query: String, countryCode: String? = null, limit: Int = 60): List<RadioStation> =
        stations("json/stations/search") {
            addQueryParameter("name", query)
            countryCode?.takeIf { it.isNotBlank() }?.let { addQueryParameter("countrycode", it) }
            popularFirst(limit)
        }

    /** The stream address of a station, from a listing seen earlier or fetched by its id. */
    suspend fun station(id: String): RadioStation {
        known[id]?.let { return it }
        return stations("json/stations/byuuid/$id") {}.firstOrNull()
            ?: throw IllegalStateException("That radio station is no longer listed")
    }

    /** Tells the directory a station was played, which ranks popular stations higher. */
    suspend fun countPlay(id: String) {
        runCatching { request("json/url/$id") {} }.onFailure { CoreLog.i(TAG, "Couldn't count a play of $id", it) }
    }

    private fun HttpUrl.Builder.popularFirst(limit: Int) {
        addQueryParameter("hidebroken", "true")
        addQueryParameter("order", "clickcount")
        addQueryParameter("reverse", "true")
        addQueryParameter("limit", limit.toString())
    }

    private suspend fun stations(path: String, query: HttpUrl.Builder.() -> Unit): List<RadioStation> {
        val body = request(path, query)
        val stations = withoutDuplicates(
            AppJson.decodeFromString(ListSerializer(RadioStation.serializer()), body).filter { it.streamUrl.startsWith("http") },
        )
        stations.forEach { known[it.id] = it }
        return stations
    }

    /** Tries each server in turn, as the API asks, until one answers. */
    private suspend fun request(path: String, query: HttpUrl.Builder.() -> Unit): String {
        var lastError: Exception? = null
        for (server in servers()) {
            val url = "$scheme://$server/$path".toHttpUrl().newBuilder().apply(query).build()
            try {
                return http.fetchString(Request.Builder().url(url).header("User-Agent", userAgent).build())
                    .also { lastGood = server }
            } catch (e: Exception) {
                lastError = e
                CoreLog.i(TAG, "Radio Browser server $server failed", e)
            }
        }
        // Look the servers up afresh next time; the ones found may have gone away.
        servers = null
        throw lastError ?: IllegalStateException("No Radio Browser server answered")
    }

    /**
     * The server that answered last, then the long-standing ones, then any others DNS lists.
     * DNS can list a server that's down (it once listed only that one), so it doesn't go first.
     */
    private suspend fun servers(): List<String> {
        val list = servers ?: (knownServers.shuffled() + runCatching { lookUpServers() }.getOrDefault(emptyList()).shuffled())
            .distinct()
            .also { servers = it }
        val good = lastGood ?: return list
        return listOf(good) + (list - good)
    }

    companion object {
        private const val TAG = "RadioBrowser"
        private val FALLBACK_SERVERS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info")

        /**
         * The directory often lists a station several times (e.g. "KISS 108 - WXKS-FM" and
         * "KISS 108 WXKS FM"): keep the first (most listened to) of each name or stream.
         */
        fun withoutDuplicates(stations: List<RadioStation>): List<RadioStation> {
            val names = HashSet<String>()
            val streams = HashSet<String>()
            return stations.filter { station ->
                val name = station.name.lowercase().filter { it.isLetterOrDigit() }
                val stream = station.streamUrl.substringAfter("://").substringBefore('?').trimEnd('/').lowercase()
                val newName = name.isEmpty() || names.add(name)
                val newStream = streams.add(stream)
                newName && newStream
            }
        }

        /** The servers behind all.api.radio-browser.info, by name (needed for HTTPS). */
        suspend fun serversFromDns(): List<String> = withContext(Dispatchers.IO) {
            InetAddress.getAllByName("all.api.radio-browser.info")
                .map { it.canonicalHostName }
                .filter { it.endsWith(".radio-browser.info") }
                .distinct()
        }
    }
}
