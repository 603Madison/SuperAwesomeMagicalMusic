package com.music.player.sources

import com.music.player.net.AppJson
import com.music.player.sources.siriusxm.SiriusXmParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/**
 * Asks image servers for artwork at the size it's shown, so the big player artwork is sharp
 * and list thumbnails stay small. Sizes round up to a few steps so cached copies get reused.
 * Saved songs keep whatever URL they were saved with; it's resized here when shown.
 */
object ArtworkUrls {
    private val STEPS = listOf(128, 256, 512, 1024)
    private val JELLYFIN_SIZE = Regex("([?&])(maxHeight|maxWidth|fillHeight|fillWidth)=\\d+")
    private val GOOGLE_SIZE = Regex("=w\\d+-h\\d+")

    /** The size step that covers [px]. */
    fun step(px: Int): Int = STEPS.firstOrNull { it >= px } ?: STEPS.last()

    /** [url] asking for about [px] pixels, where its server can resize; otherwise unchanged. */
    fun sized(url: String, px: Int): String {
        val size = step(px)
        return when {
            url.contains("/Images/Primary") -> {
                // Jellyfin: drop any size given, then ask for this one.
                val stripped = url.replace(JELLYFIN_SIZE) { if (it.groupValues[1] == "?") "?" else "" }.replace("?&", "?")
                val separator = if (stripped.contains('?')) (if (stripped.endsWith("?")) "" else "&") else "?"
                "$stripped${separator}maxHeight=$size&maxWidth=$size"
            }
            url.startsWith(SiriusXmParser.IMAGE_BASE) -> resizedSiriusXm(url, size) ?: url
            url.contains("googleusercontent.com") && GOOGLE_SIZE.containsMatchIn(url) -> url.replace(GOOGLE_SIZE, "=w$size-h$size")
            else -> url
        }
    }

    /** SiriusXM's image server takes its edits as Base64 JSON in the path; change the resize. */
    private fun resizedSiriusXm(url: String, size: Int): String? = runCatching {
        val encoded = url.removePrefix(SiriusXmParser.IMAGE_BASE).substringBefore('?')
        val params = AppJson.parseToJsonElement(String(Base64.getDecoder().decode(encoded))).jsonObject
        val edits = (params["edits"] as? JsonArray)?.map { edit ->
            val obj = edit as? JsonObject ?: return@map edit
            if ("resize" in obj) {
                JsonObject(mapOf("resize" to JsonObject(mapOf("width" to JsonPrimitive(size), "height" to JsonPrimitive(size)))))
            } else {
                obj
            }
        } ?: return null
        val updated = JsonObject(params + ("edits" to JsonArray(edits)))
        SiriusXmParser.IMAGE_BASE + Base64.getEncoder().encodeToString(updated.toString().toByteArray())
    }.getOrNull()
}
