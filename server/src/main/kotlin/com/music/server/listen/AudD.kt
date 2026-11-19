package com.music.server.listen

import com.music.player.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * AudD (audd.io), a song recognition service: given a recording, the song in it. Only used when
 * someone has added it as a source (with their own API token), and only for what the library
 * didn't recognise.
 */
class AudD(private val http: OkHttpClient, private val endpoint: String = ENDPOINT) {
    /** What AudD recognised. */
    data class Song(
        val title: String,
        val artist: String,
        val album: String?,
        val artworkUrl: String?,
        /** Where in the song the recording was, when AudD says. */
        val positionMs: Long?,
        val durationMs: Long?,
        /** AudD's page for the song, with links to it on the streaming services. */
        val link: String?,
    )

    /** Throws if AudD doesn't accept [token] (anything else, like asking without a recording, means it does). */
    suspend fun check(token: String) {
        val answer = post(MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("api_token", token).build())
        val code = errorCode(answer)
        if (code in BAD_TOKEN) throw IOException("AudD didn't accept the API token")
    }

    /** The song in [audio] (any common format, [contentType]), or null if AudD didn't recognise one. */
    suspend fun recognise(token: String, audio: ByteArray, contentType: String?): Song? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_token", token)
            .addFormDataPart("return", "apple_music,spotify")
            .addFormDataPart("file", "heard", audio.toRequestBody((contentType ?: "application/octet-stream").toMediaTypeOrNull()))
            .build()
        val answer = post(body)
        errorCode(answer)?.let { code ->
            val message = (answer["error"] as? JsonObject)?.get("error_message")?.jsonPrimitive?.contentOrNull
            throw IOException(if (code in BAD_TOKEN) "AudD didn't accept the API token" else "AudD: ${message ?: "error $code"}")
        }
        val result = answer["result"] as? JsonObject ?: return null
        fun text(o: JsonObject?, key: String) = o?.get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.takeIf { it.isNotBlank() }
        val title = text(result, "title") ?: return null
        val apple = result["apple_music"] as? JsonObject
        val spotify = result["spotify"] as? JsonObject
        val artwork = text(apple?.get("artwork") as? JsonObject, "url")?.replace("{w}", "600")?.replace("{h}", "600")
            ?: runCatching { ((spotify?.get("album") as? JsonObject)?.get("images"))?.jsonArray?.firstOrNull()?.jsonObject?.let { text(it, "url") } }.getOrNull()
        val duration = apple?.get("durationInMillis")?.jsonPrimitive?.longOrNull ?: spotify?.get("duration_ms")?.jsonPrimitive?.longOrNull
        return Song(
            title = title,
            artist = text(result, "artist").orEmpty(),
            album = text(result, "album"),
            artworkUrl = artwork?.takeIf { it.startsWith("https://") },
            positionMs = text(result, "timecode")?.let(::clockMs),
            durationMs = duration,
            link = text(result, "song_link")?.takeIf { it.startsWith("https://") },
        )
    }

    private suspend fun post(body: MultipartBody): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint).header("User-Agent", Http.USER_AGENT).post(body).build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException("AudD answered ${response.code}")
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException("AudD's answer wasn't readable") }
        }
    }

    private fun errorCode(answer: JsonObject): Int? =
        if (answer["status"]?.jsonPrimitive?.contentOrNull == "error") (answer["error"] as? JsonObject)?.get("error_code")?.jsonPrimitive?.intOrNull ?: 0 else null

    companion object {
        const val ENDPOINT = "https://api.audd.io/"
        private val BAD_TOKEN = setOf(900, 901, 902)

        /** "01:23" (or "1:02:03") as milliseconds. */
        fun clockMs(clock: String): Long? {
            val parts = clock.trim().split(':').map { it.toLongOrNull() ?: return null }
            if (parts.isEmpty()) return null
            return parts.fold(0L) { total, part -> total * 60 + part } * 1000
        }
    }
}
