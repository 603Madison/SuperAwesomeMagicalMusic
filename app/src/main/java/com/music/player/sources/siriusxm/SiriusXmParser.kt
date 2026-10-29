package com.music.player.sources.siriusxm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Pure transforms of SiriusXM's edge-gateway responses. */
object SiriusXmParser {
    const val IMAGE_BASE = "https://imgsrv-sxm-prod-device.streaming.siriusxm.com/"
    private val GUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val KEY_URI = Regex("URI=\"[^\"]*\"")

    /** The URI scheme the app gives HLS encryption keys; [SiriusXmDataSource] serves them. */
    const val KEY_SCHEME = "sxmkey"

    fun dig(value: JsonElement?, vararg path: String): JsonElement? =
        path.fold(value) { current, key -> (current as? JsonObject)?.get(key) }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** SiriusXM text fields are either strings or `{"default": "..."}` maps. */
    private fun text(texts: JsonObject?, name: String): String {
        return when (val value = texts?.get(name)) {
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            is JsonObject -> value["default"].string() ?: value.values.firstNotNullOfOrNull { it.string() }.orEmpty()
            else -> ""
        }
    }

    fun parseChannels(lineup: JsonObject): List<SxmChannel> {
        val sets = dig(lineup, "container", "sets") as? JsonArray ?: return emptyList()
        val items = sets.flatMap { (dig(it, "items") as? JsonArray).orEmpty() }
        return items.mapNotNull { item ->
            val entity = dig(item, "entity") as? JsonObject ?: return@mapNotNull null
            val decorations = dig(item, "decorations") as? JsonObject
            val id = entity["id"].string()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val texts = entity["texts"] as? JsonObject
            val number = (decorations?.get("channelNumber") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val category = decorations?.get("genre").string()
                ?: (decorations?.get("contentTags") as? JsonArray)?.firstOrNull().string()
                ?: ""
            SxmChannel(
                id = id,
                type = entity["type"].string() ?: "channel-linear",
                name = text(texts, "title").ifBlank { text(texts, "name") }.ifBlank { id },
                number = number,
                category = category,
                logoUrl = pickImage(entity["images"] as? JsonObject, 300),
                locked = (decorations?.get("unentitled") as? JsonPrimitive)?.contentOrNull == "true",
            )
        }
            .distinctBy { it.id }
            .sortedWith(compareBy<SxmChannel>({ it.number == null }, { it.number ?: 0 }, { it.name.lowercase() }))
    }

    fun parseCuts(items: JsonArray?): List<SxmCut> = items.orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val start = parseTimeMs(item["timestamp"] ?: item["startTime"]) ?: return@mapNotNull null
        val duration = (item["duration"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
        val title = item["name"].string().orEmpty()
        SxmCut(
            id = item["id"].string() ?: "${start}-${title.hashCode()}",
            title = title,
            artist = item["artistName"].string().orEmpty(),
            album = item["albumName"].string()?.takeIf { it.isNotBlank() },
            startMs = start,
            durationMs = duration,
            artworkUrl = pickImage(item["images"] as? JsonObject, 300) ?: pickImage(item["artistImages"] as? JsonObject, 300),
            interstitial = (item["isInterstitial"] as? JsonPrimitive)?.contentOrNull == "true",
        )
    }.sortedBy { it.startMs }

    /** The cut playing at wall-clock time [positionMs] (the moment of audio being heard). */
    fun currentCut(cuts: List<SxmCut>, positionMs: Long): SxmCut? {
        if (cuts.isEmpty()) return null
        return cuts.lastOrNull { it.startMs <= positionMs && positionMs <= it.endMs + 1000 }
            ?: cuts.lastOrNull { it.startMs <= positionMs }
            ?: cuts.first()
    }

    /** Points every encryption key in a media playlist at [KEY_SCHEME], keyed by its GUID. */
    fun rewriteKeys(playlist: String): String = playlist.lineSequence().joinToString("\n") { line ->
        if (line.startsWith("#EXT-X-KEY") && line.contains("URI=\"")) {
            val guid = GUID.findAll(line).lastOrNull()?.value
            if (guid != null) line.replace(KEY_URI, "URI=\"$KEY_SCHEME://$guid\"") else line
        } else {
            line
        }
    }

    fun pickImage(images: JsonObject?, size: Int): String? {
        for (kind in listOf("tile", "logo")) {
            for (aspect in listOf("aspect_1x1", "aspect_16x9")) {
                for (variant in listOf("preferred", "preferredImage", "default", "defaultImage")) {
                    dig(images, kind, aspect, variant, "url").string()?.takeIf { it.isNotBlank() }?.let { return imageUrl(it, size) }
                }
            }
        }
        return null
    }

    /** Turns an image key into a fetchable URL from SiriusXM's resizing image server. */
    fun imageUrl(key: String, size: Int): String {
        if (key.startsWith("http://") || key.startsWith("https://")) return key
        val params = buildJsonObject {
            put("key", key)
            put("edits", buildJsonArray {
                add(buildJsonObject { put("format", buildJsonObject { put("type", "jpeg") }) })
                add(buildJsonObject {
                    put("resize", buildJsonObject {
                        put("width", size)
                        put("height", size)
                    })
                })
            })
        }
        return IMAGE_BASE + Base64.getEncoder().encodeToString(params.toString().toByteArray())
    }

    /** SiriusXM timestamps: ISO strings (including a "+00:00Z" form) or epoch numbers. */
    fun parseTimeMs(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (!primitive.isString) {
            val number = primitive.longOrNull ?: primitive.contentOrNull?.toDoubleOrNull()?.toLong() ?: return null
            return if (number > 100_000_000_000L) number else number * 1000
        }
        val text = primitive.content.trim().replace("+00:00Z", "Z").ifEmpty { return null }
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(text).toEpochMilli() }
            .recoverCatching { java.time.LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrNull()
    }

    fun isoUtc(epochMs: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(epochMs))
}
