package com.music.player.sources.radio

import android.util.Log
import com.music.player.net.AppJson
import com.music.player.net.fetchString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Radio stations from Radio Browser (radio-browser.info), a free, open directory of tens of
 * thousands of stations, searchable by name and by location.
 *
 * As the API asks, servers are looked up from DNS rather than hard-coded (with known servers as
 * a fallback), the app identifies itself in the User-Agent, and each station played is counted.
 */
class RadioBrowserClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val lookUpServers: suspend () -> List<String> = ::serversFromDns,
    private val scheme: String = "https",
) {
    private var servers: List<String>? = null
    private val known = ConcurrentHashMap<String, RadioStation>()

    /** Stations within [radiusKm] of a location, most listened to first. */
    suspend fun nearby(latitude: Double, longitude: Double, radiusKm: Int = 80, limit: Int = 100): List<RadioStation> =
        stations("json/stations/search") {
            addQueryParameter("geo_lat", latitude.toString())
            addQueryParameter("geo_long", longitude.toString())
            addQueryParameter("geo_distance", (radiusKm * 1000).toString())
            popularFirst(limit)
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
        runCatching { request("json/url/$id") {} }.onFailure { Log.i(TAG, "Couldn't count a play of $id", it) }
    }

    private fun HttpUrl.Builder.popularFirst(limit: Int) {
        addQueryParameter("hidebroken", "true")
        addQueryParameter("order", "clickcount")
        addQueryParameter("reverse", "true")
        addQueryParameter("limit", limit.toString())
    }

    private suspend fun stations(path: String, query: HttpUrl.Builder.() -> Unit): List<RadioStation> {
        val body = request(path, query)
        val stations = AppJson.decodeFromString(ListSerializer(RadioStation.serializer()), body)
            .filter { it.streamUrl.startsWith("http") }
            .distinctBy { it.id }
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
            } catch (e: Exception) {
                lastError = e
                Log.i(TAG, "Radio Browser server $server failed", e)
            }
        }
        // Look the servers up afresh next time; the ones found may have gone away.
        servers = null
        throw lastError ?: IllegalStateException("No Radio Browser server answered")
    }

    /**
     * The servers DNS lists, in random order, then the long-standing ones: DNS can return a
     * single server that is down, which on its own would make every search fail.
     */
    private suspend fun servers(): List<String> {
        servers?.let { return it }
        val fromDns = runCatching { lookUpServers() }.getOrDefault(emptyList()).shuffled()
        val all = (fromDns + FALLBACK_SERVERS.shuffled() + ROUND_ROBIN).distinct()
        servers = all
        return all
    }

    companion object {
        private const val TAG = "RadioBrowser"
        private val FALLBACK_SERVERS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info")

        /** Any server, chosen by DNS; its certificate covers *.api.radio-browser.info. */
        private const val ROUND_ROBIN = "all.api.radio-browser.info"

        /** The servers behind all.api.radio-browser.info, by name (needed for HTTPS). */
        suspend fun serversFromDns(): List<String> = withContext(Dispatchers.IO) {
            InetAddress.getAllByName("all.api.radio-browser.info")
                .map { it.canonicalHostName }
                .filter { it.endsWith(".radio-browser.info") }
                .distinct()
        }
    }
}
