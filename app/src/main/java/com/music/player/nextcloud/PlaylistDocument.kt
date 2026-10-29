package com.music.player.nextcloud

import com.music.player.model.Track
import kotlinx.serialization.Serializable

/** The JSON file written to Nextcloud for each playlist. */
@Serializable
data class PlaylistDocument(
    val format: String = FORMAT,
    val version: Int = 1,
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val tracks: List<Track>,
) {
    companion object {
        const val FORMAT = "music-app-playlist"
    }
}
