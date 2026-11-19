package com.music.server.tuner

import com.music.player.net.fetchString
import com.music.server.ApiError
import com.music.server.ApiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What's on a tuner station, as the station sends it. */
@Serializable
data class TunerNow(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val radiotext: String? = null,
    val slogan: String? = null,
)

/** How well a tuner station comes in: 0 (static) to 1 (clear). */
@Serializable
data class TunerSignal(val score: Double, val detail: String, val measuredAt: Long = 0, val stereo: Boolean? = null)

/** A station the tuner has (see tuner/: `GET /api/stations`). */
@Serializable
data class TunerStation(
    val id: String,
    val frequency: String,
    val frequencyKhz: Int = 0,
    val hdProgram: Int? = null,
    val hd: Boolean = false,
    val name: String,
    val callLetters: String? = null,
    val pi: String? = null,
    val genre: String? = null,
    val now: TunerNow? = null,
    val signal: TunerSignal? = null,
    val playing: Boolean = false,
    val listeners: Int = 0,
    val logoUrl: String? = null,
    val artUrl: String? = null,
)

@Serializable
data class TunerStatus(
    val name: String,
    val version: String = "",
    /** US (the Americas' band plan, with RBDS call letters) or OTHER. */
    val region: String = "US",
    val tuners: List<JsonObject> = emptyList(),
    val stations: Int = 0,
)

/** All of the tuner's dongles are playing other stations. */
class TunerBusy(message: String) : IOException(message)

/**
 * Talks to a SuperAwesomeMagicalMusic tuner (tuner/ in this repository) on the local network,
 * with its token when it has one. The station list is kept for a few seconds: every player
 * asking what's on shouldn't each ask the tuner.
 */
class TunerClient(
    http: OkHttpClient,
    val baseUrl: String,
    private val token: String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val http = http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    // Live audio never ends; a tuner that stops sending for this long has stopped.
    private val streaming = http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()

    @Volatile
    private var cached: Pair<Long, List<TunerStation>>? = null

    private fun request(path: String): Request.Builder {
        val url = (baseUrl.trimEnd('/') + path).toHttpUrlOrNull() ?: throw IOException("The tuner's address isn't usable")
        return Request.Builder().url(url).apply { token?.let { header("Authorization", "Bearer $it") } }
    }

    suspend fun status(): TunerStatus = ApiJson.decodeFromString(TunerStatus.serializer(), http.fetchString(request("/api/status").build()))

    /** The tuner's stations (with what's on), kept for [maxAgeMs]. */
    suspend fun stations(maxAgeMs: Long = 8_000): List<TunerStation> {
        cached?.let { (at, list) -> if (clock() - at < maxAgeMs) return list }
        val list = ApiJson.decodeFromString(ListSerializer(TunerStation.serializer()), http.fetchString(request("/api/stations").build()))
        cached = clock() to list
        return list
    }

    /** One station, fresh (what's on now, and its signal). */
    suspend fun station(id: String): TunerStation? {
        val path = "/api/stations/${id.encodePath()}"
        return try {
            ApiJson.decodeFromString(TunerStation.serializer(), http.fetchString(request(path).build()))
        } catch (e: com.music.player.net.HttpException) {
            if (e.code == 404) null else throw e
        }
    }

    /**
     * Opens a station's audio (AAC in ADTS frames). The caller closes it. Throws [TunerBusy]
     * when every tuner is playing something else.
     */
    suspend fun openStream(id: String): Response = withContext(Dispatchers.IO) {
        val response = streaming.newCall(request("/api/stations/${id.encodePath()}/stream").build()).execute()
        if (response.code == 503) {
            val message = runCatching { response.body.string() }.getOrDefault("")
            response.close()
            throw TunerBusy(Regex("\"error\"\\s*:\\s*\"([^\"]*)\"").find(message)?.groupValues?.get(1) ?: "All the tuner's tuners are busy")
        }
        if (!response.isSuccessful) {
            response.close()
            throw IOException("The tuner answered HTTP ${response.code}")
        }
        response
    }

    /** An image the tuner named (a station logo or album art): its bytes and type. */
    suspend fun image(path: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        if (!path.startsWith("/api/stations/")) return@withContext null
        http.newCall(request(path).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val type = response.header("Content-Type")?.takeIf { it.startsWith("image/") } ?: return@use null
            val bytes = response.body.byteStream().use { it.readNBytes(MAX_IMAGE_BYTES + 1) }
            if (bytes.size > MAX_IMAGE_BYTES) null else bytes to type
        }
    }

    fun forget() {
        cached = null
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

        private fun String.encodePath(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

        /** Throws the right error for the person when the tuner can't be reached. */
        fun unreachable(e: Exception): Nothing = throw ApiError.upstream("Couldn't reach the tuner: ${e.message ?: e.javaClass.simpleName}")
    }
}
