package com.music.player.scrobble

import android.util.Log
import com.music.player.BuildConfig
import com.music.player.data.PendingScrobbleEntity
import com.music.player.data.ScrobbleDao
import com.music.player.model.Track
import com.music.player.net.AppJson
import com.music.player.net.Http
import com.music.player.net.fetchString
import com.music.player.settings.ScrobbleSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

/** A song listened to, as scrobbling services want it. */
data class Listen(val track: Track, val startedAtMs: Long)

/** ListenBrainz, or any server with its API (Maloja, Koito…). */
class ListenBrainzClient(private val http: OkHttpClient) {
    /** The user name the token belongs to; throws if it's not valid. */
    suspend fun validate(baseUrl: String, token: String): String {
        val request = Request.Builder().url("${Http.normalizeBaseUrl(baseUrl)}/1/validate-token")
            .header("Authorization", "Token $token").build()
        val body = AppJson.parseToJsonElement(http.fetchString(request)).jsonObject
        if ((body["valid"] as? JsonPrimitive)?.contentOrNull != "true") throw IOException("ListenBrainz didn't accept that token")
        return (body["user_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    suspend fun nowPlaying(settings: ScrobbleSettings, track: Track) =
        submit(settings, "playing_now", listOf(track to null))

    suspend fun submit(settings: ScrobbleSettings, listens: List<Listen>) =
        submit(settings, if (listens.size == 1) "single" else "import", listens.map { it.track to it.startedAtMs / 1000 })

    private suspend fun submit(settings: ScrobbleSettings, type: String, listens: List<Pair<Track, Long?>>) {
        val body = buildJsonObject {
            put("listen_type", type)
            put("payload", buildJsonArray {
                listens.forEach { (track, listenedAt) ->
                    add(buildJsonObject {
                        listenedAt?.let { put("listened_at", it) }
                        put("track_metadata", buildJsonObject {
                            put("artist_name", track.artist)
                            put("track_name", track.title)
                            track.album?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
                            put("additional_info", buildJsonObject {
                                track.durationMs?.let { put("duration_ms", it) }
                                track.isrc?.let { put("isrc", it) }
                                put("media_player", APP)
                                put("submission_client", APP)
                                put("submission_client_version", BuildConfig.VERSION_NAME)
                            })
                        })
                    })
                }
            })
        }
        val request = Request.Builder()
            .url("${Http.normalizeBaseUrl(settings.listenBrainzUrl)}/1/submit-listens")
            .header("Authorization", "Token ${settings.listenBrainzToken}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.fetchString(request)
    }
}

/** Last.fm's scrobbling API, with the API key and secret you create at last.fm/api. */
class LastFmClient(private val http: OkHttpClient, private val endpoint: String = "https://ws.audioscrobbler.com/2.0/") {
    /** Signs in and returns the session key; the password is sent once (over HTTPS) and not kept. */
    suspend fun signIn(apiKey: String, secret: String, username: String, password: String): String {
        val response = call(secret, mapOf("method" to "auth.getMobileSession", "username" to username, "password" to password, "api_key" to apiKey))
        return (response["session"] as? JsonObject)?.get("key")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw IOException("Last.fm didn't open a session")
    }

    suspend fun nowPlaying(settings: ScrobbleSettings, track: Track) {
        call(
            settings.lastFmSecret,
            buildMap {
                put("method", "track.updateNowPlaying")
                put("artist", track.artist)
                put("track", track.title)
                track.album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
                track.durationMs?.let { put("duration", (it / 1000).toString()) }
                put("api_key", settings.lastFmApiKey)
                put("sk", settings.lastFmSession)
            },
        )
    }

    /** Up to 50 listens at once, as Last.fm allows. */
    suspend fun scrobble(settings: ScrobbleSettings, listens: List<Listen>) {
        require(listens.size <= 50)
        call(
            settings.lastFmSecret,
            buildMap {
                put("method", "track.scrobble")
                listens.forEachIndexed { i, listen ->
                    put("artist[$i]", listen.track.artist)
                    put("track[$i]", listen.track.title)
                    put("timestamp[$i]", (listen.startedAtMs / 1000).toString())
                    listen.track.album?.takeIf { it.isNotBlank() }?.let { put("album[$i]", it) }
                    listen.track.durationMs?.let { put("duration[$i]", (it / 1000).toString()) }
                }
                put("api_key", settings.lastFmApiKey)
                put("sk", settings.lastFmSession)
            },
        )
    }

    private suspend fun call(secret: String, params: Map<String, String>): JsonObject {
        val form = FormBody.Builder().apply {
            params.forEach { (name, value) -> add(name, value) }
            add("api_sig", signature(params, secret))
            add("format", "json")
        }.build()
        val body = http.fetchString(Request.Builder().url(endpoint).post(form).build())
        val json = AppJson.parseToJsonElement(body).jsonObject
        (json["error"] as? JsonPrimitive)?.contentOrNull?.let { code ->
            val message = (json["message"] as? JsonPrimitive)?.contentOrNull ?: "error $code"
            throw LastFmException(code.toIntOrNull() ?: 0, "Last.fm: $message")
        }
        return json
    }

    companion object {
        /** Last.fm's request signature: md5 of the sorted name+value pairs, then the secret. */
        fun signature(params: Map<String, String>, secret: String): String {
            val text = params.toSortedMap().entries.joinToString("") { (name, value) -> name + value } + secret
            return MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/** Last.fm turned a request down; [code] 9 means the session is no longer valid. */
class LastFmException(val code: Int, message: String) : IOException(message)

/**
 * Sends listens to the scrobbling services set up. Listens are kept in a queue until each
 * service has them, so listening offline (or on satellite) loses nothing.
 */
class Scrobbler(
    private val dao: ScrobbleDao,
    private val settings: suspend () -> ScrobbleSettings,
    private val listenBrainz: ListenBrainzClient,
    private val lastFm: LastFmClient,
    private val isOnline: () -> Boolean,
) {
    private val mutex = Mutex()

    /** Whether [track] should be scrobbled at all, given where it came from. */
    suspend fun wants(heard: Boolean): Boolean {
        val config = settings()
        return config.enabled && (!heard || config.includeRadio)
    }

    suspend fun nowPlaying(track: Track) {
        val config = settings()
        if (!config.enabled || !isOnline()) return
        if (config.listenBrainzEnabled) send("now playing (ListenBrainz)") { listenBrainz.nowPlaying(config, track) }
        if (config.lastFmEnabled) send("now playing (Last.fm)") { lastFm.nowPlaying(config, track) }
    }

    suspend fun scrobble(listen: Listen) {
        if (!settings().enabled) return
        dao.insert(PendingScrobbleEntity(track = listen.track, playedAt = listen.startedAtMs))
        flush()
    }

    /** Sends whatever is waiting, oldest first, if online. */
    suspend fun flush() = mutex.withLock {
        val config = settings()
        if (!config.enabled || !isOnline()) return@withLock
        while (true) {
            val batch = dao.oldest(BATCH)
            if (batch.isEmpty()) return@withLock
            var progressed = false
            if (config.listenBrainzEnabled) {
                val todo = batch.filter { !it.listenBrainzDone }
                if (todo.isNotEmpty() && send("ListenBrainz") { listenBrainz.submit(config, todo.map { Listen(it.track, it.playedAt) }) }) {
                    dao.markListenBrainzDone(todo.map { it.id })
                    progressed = true
                }
            }
            if (config.lastFmEnabled) {
                val todo = batch.filter { !it.lastFmDone }
                if (todo.isNotEmpty() && send("Last.fm") { lastFm.scrobble(config, todo.map { Listen(it.track, it.playedAt) }) }) {
                    dao.markLastFmDone(todo.map { it.id })
                    progressed = true
                }
            }
            // Done with a listen once every service set up has it.
            val refreshed = dao.oldest(BATCH)
            val finished = refreshed.filter {
                (it.listenBrainzDone || !config.listenBrainzEnabled) && (it.lastFmDone || !config.lastFmEnabled)
            }
            if (finished.isNotEmpty()) dao.delete(finished.map { it.id })
            if (!progressed || finished.isEmpty()) {
                dao.recordAttempt(batch.map { it.id })
                // Give up on listens a service keeps refusing, rather than blocking the rest.
                dao.delete(batch.filter { it.attempts + 1 >= MAX_ATTEMPTS }.map { it.id })
                return@withLock
            }
        }
    }

    private suspend fun send(service: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: IOException) {
        log("scrobble ($service)", e)
        false
    }

    private fun log(what: String, e: Throwable) = Log.i(TAG, "Couldn't send $what", e)

    private companion object {
        const val TAG = "Scrobbler"
        const val BATCH = 50
        const val MAX_ATTEMPTS = 20
    }
}

private const val APP = "SuperAwesomeMagicalMusic"
