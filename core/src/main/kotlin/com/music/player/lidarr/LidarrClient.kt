package com.music.player.lidarr

import com.music.player.net.AppJson
import com.music.player.net.HttpException
import com.music.player.net.Http
import com.music.player.net.fetchString
import com.music.player.settings.LidarrConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class LidarrArtist(val id: Int = 0, val artistName: String, val foreignArtistId: String? = null)

@Serializable
data class LidarrProfile(val id: Int, val name: String)

@Serializable
data class LidarrRootFolder(
    val id: Int = 0,
    val path: String,
    val name: String? = null,
    val defaultQualityProfileId: Int? = null,
    val defaultMetadataProfileId: Int? = null,
)

data class LidarrServerOptions(
    val qualityProfiles: List<LidarrProfile>,
    val metadataProfiles: List<LidarrProfile>,
    val rootFolders: List<LidarrRootFolder>,
)

/** Minimal Lidarr v1 API client: list/lookup/add artists and read the add-artist options. */
class LidarrClient(private val http: OkHttpClient) {

    suspend fun artists(config: LidarrConfig): List<LidarrArtist> =
        AppJson.decodeFromString(get(config, "artist"))

    /** Searches MusicBrainz through Lidarr. Raw objects are kept so they can be posted back as-is. */
    suspend fun lookup(config: LidarrConfig, term: String): List<JsonObject> =
        AppJson.decodeFromString(get(config, "artist/lookup", "term" to term))

    suspend fun serverOptions(config: LidarrConfig) = LidarrServerOptions(
        qualityProfiles = AppJson.decodeFromString(get(config, "qualityprofile")),
        metadataProfiles = AppJson.decodeFromString(get(config, "metadataprofile")),
        rootFolders = AppJson.decodeFromString(get(config, "rootfolder")),
    )

    suspend fun addArtist(
        config: LidarrConfig,
        lookupResult: JsonObject,
        qualityProfileId: Int,
        metadataProfileId: Int,
        rootFolderPath: String,
    ): LidarrArtist {
        val body = JsonObject(
            lookupResult + mapOf(
                "qualityProfileId" to JsonPrimitive(qualityProfileId),
                "metadataProfileId" to JsonPrimitive(metadataProfileId),
                "rootFolderPath" to JsonPrimitive(rootFolderPath),
                "monitored" to JsonPrimitive(true),
                "monitorNewItems" to JsonPrimitive("all"),
                "addOptions" to buildJsonObject {
                    put("monitor", "all")
                    put("searchForMissingAlbums", config.searchForMissingAlbums)
                },
            ),
        )
        val request = Request.Builder()
            .url(url(config, "artist"))
            .header("X-Api-Key", config.apiKey)
            .post(AppJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .build()
        return AppJson.decodeFromString(http.fetchString(request))
    }

    /** How many track files Lidarr has for an artist it has; null if it no longer has the artist. */
    suspend fun trackFileCount(config: LidarrConfig, artistId: Int): Int? = try {
        val artist = AppJson.decodeFromString<JsonObject>(get(config, "artist/$artistId"))
        (artist["statistics"] as? JsonObject)?.get("trackFileCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    } catch (e: HttpException) {
        if (e.code == 404) null else throw e
    }

    suspend fun testConnection(config: LidarrConfig): String {
        val status = AppJson.decodeFromString<JsonObject>(get(config, "system/status"))
        return status["version"]?.jsonPrimitive?.content ?: "unknown"
    }

    private suspend fun get(config: LidarrConfig, path: String, vararg query: Pair<String, String>): String {
        val url = url(config, path).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return http.fetchString(Request.Builder().url(url).header("X-Api-Key", config.apiKey).build())
    }

    private fun url(config: LidarrConfig, path: String) = "${Http.normalizeBaseUrl(config.serverUrl)}/api/v1/$path"

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
