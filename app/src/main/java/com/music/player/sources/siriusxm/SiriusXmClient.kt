package com.music.player.sources.siriusxm

import com.music.player.net.AppJson
import com.music.player.net.HttpException
import com.music.player.net.execute
import com.music.player.settings.SiriusXmConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SiriusXmAuthException(message: String) : Exception(message)

/**
 * SiriusXM live radio through the private "edge-gateway" API used by SiriusXM's web player,
 * following the flow of the open-source sxm-player / omarchy-siriusxm clients:
 * device grant → anonymous session → password identity grant → authenticated session.
 *
 * The API is undocumented and SiriusXM can change or block it at any time.
 */
class SiriusXmClient(
    private val http: OkHttpClient,
    private val credentials: suspend () -> SiriusXmConfig,
    private val loadSession: suspend () -> String?,
    private val saveSession: suspend (String?) -> Unit,
    private val baseUrl: String = API_BASE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var session: SxmSession? = null
    private val requestCounter = AtomicInteger()

    private var channelCache: List<SxmChannel>? = null
    private var channelsFetchedAt = 0L
    private val tunes = ConcurrentHashMap<String, Pair<String, Long>>()
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val schedules = ConcurrentHashMap<String, Pair<List<SxmCut>, Long>>()

    private val _nowPlaying = MutableStateFlow<SxmNowPlaying?>(null)
    /** Updated by the playback service while a channel is playing. */
    val nowPlaying: StateFlow<SxmNowPlaying?> = _nowPlaying.asStateFlow()

    fun publishNowPlaying(value: SxmNowPlaying?) {
        _nowPlaying.value = value
    }

    suspend fun isAvailable(): Boolean = credentials().isConfigured

    /** Signs in with new credentials (without saving them) and returns the channel count. */
    suspend fun verify(config: SiriusXmConfig): Int = mutex.withLock {
        // Keep the device registration but drop any session belonging to previous credentials.
        session = currentSession().let { SxmSession(deviceId = it.deviceId, deviceGrant = it.deviceGrant) }
        login(config)
        parseLineup(requestLocked("GET", "relationship/v1/container/all-channels", query = lineupQuery())).size
    }

    suspend fun signOut() = mutex.withLock {
        session = null
        channelCache = null
        tunes.clear()
        saveSession(null)
    }

    suspend fun channels(refresh: Boolean = false): List<SxmChannel> {
        val cached = channelCache
        if (!refresh && cached != null && clock() - channelsFetchedAt < LINEUP_MAX_AGE_MS) return cached
        val channels = parseLineup(call("GET", "relationship/v1/container/all-channels", query = lineupQuery()))
        if (channels.isEmpty()) throw IllegalStateException("SiriusXM returned an empty channel list")
        channelCache = channels
        channelsFetchedAt = clock()
        return channels
    }

    /** The channel's HLS master playlist URL; tuning is reused for a while. */
    suspend fun streamUrl(type: String, channelId: String, forceRetune: Boolean = false): String {
        tunes[channelId]?.takeIf { !forceRetune && clock() - it.second < STREAM_MAX_AGE_MS }?.let { return it.first }
        val tuned = call(
            "POST",
            "playback/play/v1/tuneSource",
            buildJsonObject {
                put("id", channelId)
                put("type", type)
                put("hlsVersion", "V3")
                put("manifestVariant", if (type == "channel-linear") "WEB" else "FULL")
                put("mtcVersion", "V2")
            },
        )
        val stream = (tuned["streams"] as? JsonArray)?.firstOrNull() as? JsonObject
        val urls = (stream?.get("urls") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val choice = urls.firstOrNull { (it["isPrimary"] as? JsonPrimitive)?.contentOrNull == "true" } ?: urls.firstOrNull()
        val url = (choice?.get("url") as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("SiriusXM returned no stream for this channel")
        SiriusXmParser.dig(stream, "metadata", "live", "items")?.let { items ->
            schedules[channelId] = SiriusXmParser.parseCuts(items as? JsonArray) to clock()
        }
        tunes[channelId] = url to clock()
        return url
    }

    /** The 16-byte AES-128 key for an encrypted segment. */
    suspend fun key(guid: String): ByteArray {
        keys[guid]?.let { return it }
        val data = call("GET", "playback/key/v1/$guid")
        val key = Base64.getDecoder().decode((data["key"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        if (key.size != 16) throw IllegalStateException("Unexpected SiriusXM key length ${key.size}")
        keys[guid] = key
        return key
    }

    /** The song (or show) on [channelId] at wall-clock time [heardAtMs]. */
    suspend fun cutAt(channelId: String, heardAtMs: Long): SxmCut? {
        val cached = schedules[channelId]
        var cut = cached?.let { SiriusXmParser.currentCut(it.first, heardAtMs) }
        val stale = cached == null || clock() - cached.second > SCHEDULE_MAX_AGE_MS ||
            (cut != null && heardAtMs > cut.endMs + 5_000)
        if (stale) {
            val now = clock()
            val data = call(
                "POST",
                "playback/play/v1/liveUpdate",
                buildJsonObject {
                    put("channelId", channelId)
                    put("startTimestamp", SiriusXmParser.isoUtc(now - 3 * 3600_000L))
                    put("endTimestamp", SiriusXmParser.isoUtc(now + 60_000L))
                },
            )
            val cuts = SiriusXmParser.parseCuts(data["items"] as? JsonArray)
            schedules[channelId] = cuts to now
            cut = SiriusXmParser.currentCut(cuts, heardAtMs)
        }
        return cut
    }

    private fun parseLineup(raw: JsonObject) = SiriusXmParser.parseChannels(raw)

    private fun lineupQuery() = mapOf(
        "containerId" to "3JoBfOCIwo6FmTpzM1S2H7",
        "useCuratedContext" to "false",
        "entityType" to "curated-grouping",
        "entityId" to "403ab6a5-d3c9-4c2a-a722-a94a6a5fd056",
        "offset" to "0",
        "size" to "1000",
        "setStyle" to "small_list",
        "key" to Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray()),
    )

    /** Authenticated request, signing in again once if the session was rejected. */
    private suspend fun call(
        method: String,
        path: String,
        payload: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
    ): JsonObject = mutex.withLock {
        val config = credentials()
        if (!config.isConfigured) throw SiriusXmAuthException("SiriusXM isn't set up")
        login(config)
        try {
            requestLocked(method, path, payload, query)
        } catch (e: HttpException) {
            if (e.code != 401 && e.code != 403) throw e
            session = session?.let { SxmSession(deviceId = it.deviceId, deviceGrant = it.deviceGrant) }
            login(config)
            requestLocked(method, path, payload, query)
        }
    }

    private suspend fun currentSession(): SxmSession {
        session?.let { return it }
        val restored = loadSession()?.let { runCatching { AppJson.decodeFromString(SxmSession.serializer(), it) }.getOrNull() }
        return (restored ?: SxmSession()).also { session = it }
    }

    private suspend fun update(transform: (SxmSession) -> SxmSession) {
        val updated = transform(currentSession())
        session = updated
        saveSession(AppJson.encodeToString(SxmSession.serializer(), updated))
    }

    private fun valid(token: String?, expiresMs: Long) = token != null && expiresMs - clock() > TOKEN_MARGIN_MS

    /** Brings the session up to an authenticated access token. Call with [mutex] held. */
    private suspend fun login(config: SiriusXmConfig) {
        var state = currentSession()
        if (valid(state.accessToken, state.accessExpiresMs)) return

        if (state.deviceGrant == null) {
            val device = requestLocked(
                "POST",
                "device/v1/devices",
                buildJsonObject {
                    put("devicePlatform", "web-desktop")
                    put("deviceAttributes", buildJsonObject {
                        put("browser", buildJsonObject {
                            put("browserVersion", BROWSER_VERSION)
                            put("browser", "Chrome")
                            put("userAgent", USER_AGENT)
                            put("sdk", "web")
                            put("app", "web")
                            put("sdkVersion", BROWSER_VERSION)
                            put("appVersion", BROWSER_VERSION)
                        })
                    })
                },
                bearer = false,
            )
            val grant = device.string("grant") ?: throw SiriusXmAuthException("SiriusXM did not issue a device grant")
            update { SxmSession(deviceId = device.string("deviceId"), deviceGrant = grant) }
            state = currentSession()
        }

        if (state.identityGrant == null || !valid(state.anonToken, state.anonExpiresMs)) {
            update { SxmSession(deviceId = it.deviceId, deviceGrant = it.deviceGrant) }
            val anonymous = requestLocked("POST", "session/v1/sessions/anonymous", JsonPrimitive(""))
            update {
                it.copy(
                    anonToken = anonymous.string("accessToken"),
                    anonExpiresMs = expiry(anonymous),
                )
            }
            val identity = try {
                requestLocked(
                    "POST",
                    "identity/v1/identities/authenticate/password",
                    buildJsonObject {
                        put("handle", config.username)
                        put("password", config.password)
                    },
                )
            } catch (e: HttpException) {
                if (e.code in listOf(400, 401, 403, 404, 422)) throw SiriusXmAuthException("SiriusXM rejected that email or password")
                throw e
            }
            val grant = identity.string("grant") ?: throw SiriusXmAuthException("SiriusXM did not accept the sign-in")
            update { it.copy(identityGrant = grant) }
        }

        val authenticated = requestLocked("POST", "session/v1/sessions/authenticated", JsonPrimitive(""))
        val token = authenticated.string("accessToken") ?: throw SiriusXmAuthException("SiriusXM did not open a session")
        update { it.copy(accessToken = token, accessExpiresMs = expiry(authenticated)) }
    }

    private fun expiry(response: JsonObject): Long =
        SiriusXmParser.parseTimeMs(response["accessTokenExpiresAt"]) ?: (clock() + 3600_000L)

    private fun bearer(state: SxmSession?): String? = when {
        state == null -> null
        valid(state.accessToken, state.accessExpiresMs) -> state.accessToken
        state.identityGrant != null && valid(state.anonToken, state.anonExpiresMs) -> state.identityGrant
        valid(state.anonToken, state.anonExpiresMs) -> state.anonToken
        else -> state.deviceGrant
    }

    private suspend fun requestLocked(
        method: String,
        path: String,
        payload: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
        bearer: Boolean = true,
    ): JsonObject {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Origin", WEB_ORIGIN)
            .header("Referer", "$WEB_ORIGIN/")
            .header("x-sxm-clock", "[0,${requestCounter.incrementAndGet()}]")
            .header("x-sxm-platform", "browser")
            .header("x-sxm-tenant", "sxm")
            .apply {
                if (bearer) bearer(session)?.let { header("Authorization", "Bearer $it") }
            }
            .method(method, payload?.toString()?.toRequestBody(JSON))
            .build()
        val body = http.execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpException(response.code, "SiriusXM $path failed: HTTP ${response.code}")
            text
        }
        if (body.isBlank()) return JsonObject(emptyMap())
        return AppJson.parseToJsonElement(body).jsonObject
    }

    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    companion object {
        const val API_BASE = "https://api.edge-gateway.siriusxm.com/"
        const val WEB_ORIGIN = "https://www.siriusxm.com"
        private const val BROWSER_VERSION = "130.0.0.0"
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private const val TOKEN_MARGIN_MS = 10 * 60_000L
        private const val LINEUP_MAX_AGE_MS = 24 * 3600_000L
        private const val STREAM_MAX_AGE_MS = 20 * 60_000L
        private const val SCHEDULE_MAX_AGE_MS = 3 * 60_000L
    }
}
