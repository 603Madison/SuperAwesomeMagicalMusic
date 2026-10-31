package com.music.player.sources.radio

import com.music.player.model.SourceType
import com.music.player.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A radio station from the Radio Browser directory (radio-browser.info). */
@Serializable
data class RadioStation(
    @SerialName("stationuuid") val id: String,
    val name: String,
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val favicon: String = "",
    val country: String = "",
    @SerialName("countrycode") val countryCode: String = "",
    val state: String = "",
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
    @SerialName("geo_lat") val latitude: Double? = null,
    @SerialName("geo_long") val longitude: Double? = null,
) {
    /** The address to play: the directory's resolved stream when it has one. */
    val streamUrl: String get() = urlResolved.ifBlank { url }

    val isHls: Boolean get() = hls == 1 || streamUrl.substringBefore('?').endsWith(".m3u8")

    /** Where it broadcasts from, e.g. "New Hampshire · United States". */
    val location: String get() = listOf(state, country).filter { it.isNotBlank() }.joinToString(" · ")

    val genre: String? get() = tags.split(',').map { it.trim() }.firstOrNull { it.isNotBlank() }

    /** e.g. "MP3 128 kbps". */
    val format: String get() = listOfNotNull(codec.ifBlank { null }, bitrate.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" ")

    fun toTrack(): Track = Track(
        source = SourceType.RADIO,
        sourceId = if (isHls) "$HLS_PREFIX$id" else id,
        title = name.trim(),
        artist = location.ifBlank { "Radio" },
        album = genre?.replaceFirstChar { it.uppercase() },
        artworkUrl = favicon.ifBlank { null },
    )

    companion object {
        private const val HLS_PREFIX = "hls:"

        /** (station id, whether it streams HLS) of a radio track. */
        fun idOf(track: Track): Pair<String, Boolean>? {
            if (track.source != SourceType.RADIO) return null
            val hls = track.sourceId.startsWith(HLS_PREFIX)
            return track.sourceId.removePrefix(HLS_PREFIX) to hls
        }
    }
}
