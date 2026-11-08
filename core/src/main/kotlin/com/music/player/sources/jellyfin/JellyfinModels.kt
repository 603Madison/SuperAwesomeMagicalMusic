package com.music.player.sources.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JfAuthRequest(@SerialName("Username") val username: String, @SerialName("Pw") val password: String)

@Serializable
data class JfAuthResult(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: JfUser,
)

@Serializable
data class JfUser(@SerialName("Id") val id: String, @SerialName("Name") val name: String? = null)

@Serializable
data class JfItemsResult(@SerialName("Items") val items: List<JfItem> = emptyList())

@Serializable
data class JfItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("MediaSources") val mediaSources: List<JfMediaSource> = emptyList(),
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    /** Jellyfin 10.9+: the gain (dB) that brings the track to -18 LUFS. */
    @SerialName("NormalizationGain") val normalizationGain: Float? = null,
)

@Serializable
data class JfMediaSource(
    @SerialName("Id") val id: String,
    @SerialName("Container") val container: String? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JfMediaStream> = emptyList(),
)

@Serializable
data class JfMediaStream(
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("BitRate") val bitRate: Long? = null,
)

@Serializable
data class JfLyrics(@SerialName("Lyrics") val lyrics: List<JfLyricLine> = emptyList())

@Serializable
data class JfLyricLine(
    @SerialName("Text") val text: String = "",
    /** In ticks (100 ns); absent for unsynced lyrics. */
    @SerialName("Start") val start: Long? = null,
)
