package com.music.server.lite

import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.server.music.ApiTrack
import com.music.server.radio.Stations
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Songs travel through the lite player's links as a short token: the song's fields in a JSON
 * array, base64url. The page's script reads the same token to queue songs, so each song is in a
 * page once. A token is only what a client could send to the API anyway: artwork in it is checked
 * like any artwork a client sends ([com.music.server.music.Artwork.incoming]).
 */
object LiteTracks {
    private const val MAX_TOKEN = 6_000

    fun token(track: ApiTrack): String {
        val array = JsonArray(
            listOf(
                JsonPrimitive(track.source.name), JsonPrimitive(track.sourceId), JsonPrimitive(track.title), JsonPrimitive(track.artist),
                track.album?.let(::JsonPrimitive) ?: JsonNull, track.durationMs?.let(::JsonPrimitive) ?: JsonNull,
                track.artworkUrl?.let(::JsonPrimitive) ?: JsonNull, track.isrc?.let(::JsonPrimitive) ?: JsonNull,
                track.via?.let(::JsonPrimitive) ?: JsonNull,
            ),
        )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(array.toString().toByteArray())
    }

    fun decode(token: String?): ApiTrack? {
        if (token.isNullOrBlank() || token.length > MAX_TOKEN) return null
        return runCatching {
            val fields = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(token.trim()))).jsonArray
            fun text(i: Int) = fields.getOrNull(i)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ApiTrack(
                source = SourceType.valueOf(text(0)!!),
                sourceId = text(1)!!.take(500),
                title = text(2).orEmpty().take(500),
                artist = text(3).orEmpty().take(500),
                album = text(4)?.take(500),
                durationMs = fields.getOrNull(5)?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull,
                artworkUrl = text(6),
                isrc = text(7)?.take(40),
                via = text(8)?.take(100),
            )
        }.getOrNull()
    }

    /** How a live channel or station plays: the server's address for it, and whether it's HLS. */
    data class Live(val url: String, val hls: Boolean, val kind: String)

    /** The same as the web player's `liveStreamOf`: null for a song (which is resolved instead). */
    fun live(track: ApiTrack, timeZone: String?): Live? {
        val via = track.via ?: return null
        if (track.source == SourceType.SIRIUSXM && track.sourceId.startsWith(Track.CHANNEL_PREFIX)) {
            val (type, id) = two(track.sourceId.removePrefix(Track.CHANNEL_PREFIX)) ?: return null
            return Live("/api/live/sxm/${via.encodeURLPathPart()}/channel/${type.encodeURLPathPart()}/${id.encodeURLPathPart()}", hls = true, kind = "channel")
        }
        if (track.source == SourceType.SIRIUSXM && track.sourceId.startsWith(Track.STATION_TRACK_PREFIX)) {
            val (_, id) = two(track.sourceId.removePrefix(Track.STATION_TRACK_PREFIX)) ?: return null
            return Live("/api/live/sxm/${via.encodeURLPathPart()}/station-track/${id.encodeURLPathPart()}", hls = false, kind = "station-track")
        }
        if (Stations.isPersonal(track)) {
            val tz = timeZone?.let { "?tz=${it.encodeURLParameter()}" }.orEmpty()
            return Live("/api/live/radio/personal/station/${track.sourceId.encodeURLPathPart()}/stream$tz", hls = false, kind = "personal")
        }
        if (track.source == SourceType.RADIO) {
            val id = track.sourceId.removePrefix("hls:")
            return Live("/api/live/radio/${via.encodeURLPathPart()}/station/${id.encodeURLPathPart()}/stream", hls = track.sourceId.startsWith("hls:"), kind = "radio")
        }
        return null
    }

    /** A Pandora station kept as a favourite: started, not tuned in to. */
    fun isPandoraStation(track: ApiTrack) = track.source == SourceType.SIRIUSXM && track.sourceId.startsWith(PANDORA_STATION)

    const val PANDORA_STATION = "channel:artist-station:"

    private fun two(value: String): Pair<String, String>? {
        val at = value.indexOf(':')
        if (at <= 0 || at == value.length - 1) return null
        return value.substring(0, at) to value.substring(at + 1)
    }
}
