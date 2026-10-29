package com.music.player.sources

import android.util.Log
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.quality.StreamQuality

/** A stream picked for a requested track: possibly another source's copy of the same song. */
data class SelectedStream(val track: Track, val stream: SourceStream)

/**
 * The source rules for playing or downloading a song:
 *
 *  1. Jellyfin is preferred: tracks from any service are matched against the library first.
 *  2. If Jellyfin doesn't have the song, [onMissingFromJellyfin] is told (to involve Lidarr).
 *  3. The song then streams from the track's own service, then the others in [fallbackOrder].
 *     Services that can honour the bitrate cap win over ones that can't (Qobuz below 320 kbps).
 */
object SourceSelector {
    private const val TAG = "SourceSelector"

    suspend fun select(
        track: Track,
        quality: StreamQuality,
        jellyfin: MusicSource,
        fallbackOrder: List<MusicSource>,
        onMissingFromJellyfin: (Track) -> Unit,
    ): SelectedStream {
        val errors = mutableListOf<String>()

        var missingFromJellyfin = true
        if (jellyfin.isAvailable()) {
            try {
                val libraryTrack = jellyfin.findMatch(track)
                if (libraryTrack != null) {
                    missingFromJellyfin = false
                    return SelectedStream(libraryTrack, jellyfin.stream(libraryTrack.sourceId, quality))
                }
            } catch (e: Exception) {
                // Jellyfin couldn't be asked, so the song isn't known to be missing: leave Lidarr alone.
                missingFromJellyfin = false
                errors += "${jellyfin.type.label}: ${e.message ?: e.javaClass.simpleName}"
                Log.w(TAG, "Jellyfin failed for ${track.key}", e)
            }
        }
        if (missingFromJellyfin) onMissingFromJellyfin(track)

        val ordered = buildList {
            fallbackOrder.firstOrNull { it.type == track.source }?.let(::add)
            addAll(fallbackOrder)
        }.distinctBy { it.type }.filter { it.type != SourceType.JELLYFIN }

        var overCap: SelectedStream? = null
        for (source in ordered) {
            if (!source.isAvailable()) continue
            try {
                val candidate = source.findMatch(track) ?: continue
                val stream = source.stream(candidate.sourceId, quality)
                if (stream.withinCap) return SelectedStream(candidate, stream)
                // Keep it in reserve and look for a service that fits the cap.
                if (overCap == null) overCap = SelectedStream(candidate, stream)
            } catch (e: Exception) {
                errors += "${source.type.label}: ${e.message ?: e.javaClass.simpleName}"
                Log.w(TAG, "${source.type.label} failed for ${track.key}", e)
            }
        }
        overCap?.let { return it }
        throw SourceUnavailableException(
            if (errors.isEmpty()) "\"${track.title}\" isn't available from any configured service"
            else "Couldn't play \"${track.title}\": ${errors.joinToString("; ")}",
        )
    }
}
