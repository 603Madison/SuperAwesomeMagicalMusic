package com.music.player.sources

import com.music.player.model.SourceType
import com.music.player.settings.SettingsRepository
import com.music.player.sources.jellyfin.JellyfinSource
import com.music.player.sources.qobuz.QobuzSource
import com.music.player.sources.youtube.YouTubeMusicSource

class Catalog(
    val jellyfin: JellyfinSource,
    val qobuz: QobuzSource,
    val youtube: YouTubeMusicSource,
    private val settings: SettingsRepository,
) {
    fun source(type: SourceType): MusicSource = when (type) {
        SourceType.JELLYFIN -> jellyfin
        SourceType.QOBUZ -> qobuz
        SourceType.YOUTUBE_MUSIC -> youtube
    }

    /** Streaming services to use when Jellyfin lacks a song, in the user's preferred order. */
    suspend fun fallbackOrder(): List<SourceType> = settings.current().fallbackOrder
}
