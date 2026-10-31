package com.music.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory

/**
 * Songs go through Media3's default factory; SiriusXM's HLS streams get a playlist tracker that
 * tolerates a live playlist not changing for a long time. An Xtra channel's playlist only grows
 * when the next track arrives, minutes apart, which the default (3.5 segment lengths) would
 * report as a stuck stream.
 */
@OptIn(UnstableApi::class)
class AppMediaSourceFactory(dataSourceFactory: DataSource.Factory, extractors: ExtractorsFactory) : MediaSource.Factory {
    private val default = DefaultMediaSourceFactory(dataSourceFactory, extractors)
    private val hls = HlsMediaSource.Factory(dataSourceFactory)
        .setPlaylistTrackerFactory { source, errorPolicy, parser, cmcd, executor ->
            DefaultHlsPlaylistTracker(source, errorPolicy, parser, cmcd, PLAYLIST_STUCK_COEFFICIENT, executor)
        }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) {
            hls.createMediaSource(mediaItem)
        } else {
            default.createMediaSource(mediaItem)
        }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        default.setDrmSessionManagerProvider(drmSessionManagerProvider)
        hls.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        default.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = default.supportedTypes

    private companion object {
        /** With 10-second segments, a playlist counts as stuck after 15 minutes without change. */
        const val PLAYLIST_STUCK_COEFFICIENT = 90.0
    }
}
