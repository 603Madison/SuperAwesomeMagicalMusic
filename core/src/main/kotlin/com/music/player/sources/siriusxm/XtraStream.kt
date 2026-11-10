package com.music.player.sources.siriusxm

import java.net.URI
import java.util.Locale

/**
 * An Xtra channel as one continuous HLS media playlist.
 *
 * SiriusXM hands out Xtra channels a track at a time: tuning the channel returns a playlist for
 * the track playing now, and tuning again once it is nearly over returns the next one (the
 * sxm-player client keeps them going the same way). Played as delivered, each track's playlist
 * ends and the player stops. Instead, every track's segments are appended here to a single
 * growing playlist, with a discontinuity between tracks, so the player runs on from one track to
 * the next.
 *
 * Segments are renumbered, so each carries its own decryption IV (HLS otherwise derives it from
 * the sequence number) and a program date-time on a continuous clock starting at [startTimeMs],
 * which is how [cutAt] finds the song being heard.
 */
class XtraStream(
    /** The stream clock's start: the first segment's program date-time. */
    val startTimeMs: Long,
    private val maxSegments: Int = MAX_SEGMENTS,
    /** When the stream was started, on the wall clock. */
    val createdAtMs: Long = startTimeMs,
) {
    private class Segment(
        val url: String,
        val durationMs: Long,
        val keyLine: String?,
        val discontinuity: Boolean,
        val startMs: Long,
    )

    /** One track as appended: where it starts on the stream's clock and how long it is so far. */
    class Track(val key: String, val cut: SxmCut?, val startMs: Long, val addedAtMs: Long) {
        var durationMs: Long = 0
            internal set

        /** Whether SiriusXM's playlist for it was finished (ENDLIST), so nothing more will come. */
        var complete: Boolean = false
            internal set
    }

    private val segments = ArrayDeque<Segment>()
    private val tracks = ArrayList<Track>()
    private val seenInTrack = HashSet<String>()
    private var firstSequence = 0L
    private var discontinuitySequence = 0L
    private var endMs = startTimeMs
    private var targetDurationSec = 1

    val lastTrack: Track? @Synchronized get() = tracks.lastOrNull()

    val isEmpty: Boolean @Synchronized get() = segments.isEmpty()

    /** Where the queued music ends, on the stream's clock. */
    val endTimeMs: Long @Synchronized get() = endMs

    /**
     * Adds the segments of [mediaPlaylist] (fetched from [playlistUrl]) that aren't already here.
     * A [trackKey] different from the last track's starts a new track; the same key adds only
     * segments not seen yet (a track's playlist may grow). Returns how many segments were added.
     */
    @Synchronized
    fun append(trackKey: String, playlistUrl: String, mediaPlaylist: String, cut: SxmCut?, nowMs: Long): Int {
        val base = URI(playlistUrl)
        var sequence = 0L
        var keyLine: String? = null
        var durationMs: Long? = null
        var discontinuity = false
        var complete = false
        var added = 0
        var current = tracks.lastOrNull()?.takeIf { it.key == trackKey }
        for (raw in mediaPlaylist.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    line.substringAfter(':').trim().toIntOrNull()?.let { targetDurationSec = maxOf(targetDurationSec, it) }
                line.startsWith("#EXT-X-KEY:") -> keyLine = line
                line.startsWith("#EXTINF:") ->
                    durationMs = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()?.let { (it * 1000).toLong() }
                line == "#EXT-X-DISCONTINUITY" -> discontinuity = true
                line == "#EXT-X-ENDLIST" -> complete = true
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val url = base.resolve(line).toString()
                    var startsTrack = false
                    if (current == null) {
                        current = Track(trackKey, cut, endMs, nowMs).also { tracks += it }
                        seenInTrack.clear()
                        startsTrack = segments.isNotEmpty()
                    }
                    if (seenInTrack.add(url.substringBefore('?').substringAfterLast('/'))) {
                        val length = durationMs ?: 0
                        segments.addLast(Segment(url, length, withIv(keyLine, sequence), startsTrack || discontinuity, endMs))
                        endMs += length
                        current.durationMs += length
                        added++
                    }
                    sequence++
                    durationMs = null
                    discontinuity = false
                }
            }
        }
        if (current != null && current === tracks.lastOrNull()) current.complete = complete
        while (segments.size > maxSegments) {
            if (segments.removeFirst().discontinuity) discontinuitySequence++
            firstSequence++
        }
        return added
    }

    /**
     * The playlist to give the player. It has no end marker, so the player keeps reloading it.
     * It says to start ([EXT-X-START]) at [startAtMs] on the stream's clock (the track being
     * heard, or the first a tune brought), or else at the newest track. Players that ignore that
     * start at the end, a few segments from the last: hls.js and ExoPlayer honour it.
     */
    @Synchronized
    fun render(startAtMs: Long? = null): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:$targetDurationSec")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$firstSequence")
        appendLine("#EXT-X-DISCONTINUITY-SEQUENCE:$discontinuitySequence")
        val first = segments.firstOrNull() ?: return@buildString
        val start = startAtMs?.takeIf { it >= first.startMs && it < endMs } ?: tracks.lastOrNull()?.startMs ?: first.startMs
        appendLine("#EXT-X-START:TIME-OFFSET=${seconds(maxOf(0, start - first.startMs))},PRECISE=YES")
        var lastKey: String? = null
        for (segment in segments) {
            if (segment.discontinuity) appendLine("#EXT-X-DISCONTINUITY")
            if (segment.keyLine != lastKey) {
                appendLine(segment.keyLine ?: "#EXT-X-KEY:METHOD=NONE")
                lastKey = segment.keyLine
            }
            appendLine("#EXT-X-PROGRAM-DATE-TIME:${SiriusXmParser.isoUtc(segment.startMs)}")
            appendLine("#EXTINF:${seconds(segment.durationMs)},")
            appendLine(segment.url)
        }
    }

    /** Where the track playing at [timeMs] starts, on the stream's clock. */
    @Synchronized
    fun trackStartAt(timeMs: Long): Long? = tracks.lastOrNull { it.startMs <= timeMs }?.startMs

    /** Where the track [key] starts, on the stream's clock. */
    @Synchronized
    fun startOf(key: String): Long? = tracks.firstOrNull { it.key == key }?.startMs

    /** The song at [timeMs] on this stream's clock (the player's position as a date-time). */
    @Synchronized
    fun cutAt(timeMs: Long): SxmCut? {
        val track = tracks.lastOrNull { it.startMs <= timeMs } ?: return null
        if (timeMs > track.startMs + track.durationMs + 2_000) return null
        return track.cut?.copy(startMs = track.startMs, durationMs = track.durationMs)
    }

    /** Every song on the stream so far, where each starts on the stream's clock. */
    @Synchronized
    fun played(): List<SxmCut> = tracks.mapNotNull { track -> track.cut?.copy(startMs = track.startMs, durationMs = track.durationMs) }

    companion object {
        /** About four hours of 10-second segments. */
        const val MAX_SEGMENTS = 1500

        private fun seconds(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)

        /** HLS takes a missing IV to be the segment's sequence number; renumbering would break that. */
        fun withIv(keyLine: String?, sequence: Long): String? = when {
            keyLine == null -> null
            keyLine.contains("METHOD=NONE") || keyLine.contains("IV=") -> keyLine
            else -> "$keyLine,IV=0x${String.format(Locale.US, "%032x", sequence)}"
        }
    }
}
