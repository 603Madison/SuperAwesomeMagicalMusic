package com.music.player.sources

import com.music.player.CoreLog
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.quality.StreamQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A stream picked for a requested track: possibly another source's copy of the same song. */
data class SelectedStream(val track: Track, val stream: SourceStream)

/**
 * The source rules for playing or downloading a song:
 *
 *  1. Jellyfin is preferred: tracks from any service are matched against the library first.
 *  2. If Jellyfin doesn't have the song, [onMissingFromJellyfin] is told (to involve Lidarr).
 *  3. Otherwise the song comes from a streaming service. With [thorough] (off satellite, and
 *     for downloads) every service is asked in parallel and the best copy that fits the bitrate
 *     cap wins, e.g. a Qobuz FLAC over a YouTube 160 kbps Opus. Without it (on satellite) the
 *     first service that fits the cap is used, to save data and round trips: the track's own
 *     service, then the others in [fallbackOrder].
 *  4. A copy over the cap (Qobuz can't go below 320 kbps) is only used when nothing fits.
 */
object SourceSelector {
    private const val TAG = "SourceSelector"

    suspend fun select(
        track: Track,
        quality: StreamQuality,
        jellyfin: MusicSource,
        fallbackOrder: List<MusicSource>,
        thorough: Boolean = false,
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Jellyfin couldn't be asked, so the song isn't known to be missing: leave Lidarr alone.
                missingFromJellyfin = false
                errors += "${jellyfin.type.label}: ${e.message ?: e.javaClass.simpleName}"
                CoreLog.w(TAG, "Jellyfin failed for ${track.key}", e)
            }
        }
        if (missingFromJellyfin) onMissingFromJellyfin(track)

        val ordered = buildList {
            fallbackOrder.firstOrNull { it.type == track.source }?.let(::add)
            addAll(fallbackOrder)
        }.distinctBy { it.type }.filter { it.type != SourceType.JELLYFIN && it.isAvailable() }

        val candidates: List<SelectedStream> = if (thorough) {
            coroutineScope {
                ordered.map { source -> async { attempt(source, track, quality, errors) } }.awaitAll().filterNotNull()
            }
        } else {
            val found = mutableListOf<SelectedStream>()
            for (source in ordered) {
                val selected = attempt(source, track, quality, errors) ?: continue
                found += selected
                if (selected.stream.withinCap) break
            }
            found
        }

        // Candidates are in preference order; maxBy keeps the first of equal bitrates.
        candidates.filter { it.stream.withinCap }.maxByOrNull { it.stream.kbps ?: 0 }?.let { return it }
        candidates.firstOrNull()?.let { return it }
        throw SourceUnavailableException(
            if (errors.isEmpty()) "\"${track.title}\" isn't available from any configured service"
            else "Couldn't play \"${track.title}\": ${errors.joinToString("; ")}",
        )
    }

    private suspend fun attempt(
        source: MusicSource,
        track: Track,
        quality: StreamQuality,
        errors: MutableList<String>,
    ): SelectedStream? = try {
        source.findMatch(track)?.let { candidate -> SelectedStream(candidate, source.stream(candidate.sourceId, quality)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        synchronized(errors) { errors += "${source.type.label}: ${e.message ?: e.javaClass.simpleName}" }
        CoreLog.w(TAG, "${source.type.label} failed for ${track.key}", e)
        null
    }
}
