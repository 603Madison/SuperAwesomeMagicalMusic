package com.music.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What is actually being played, per queue item: the codec and audio format the decoder is
 * given, and the bitrate measured from the media downloaded for it. Streams such as SiriusXM's
 * don't declare their bitrate in a way the player passes on, so it's measured instead.
 */
data class MediaDetails(
    /** e.g. "HE-AAC" or "Opus". */
    val codec: String? = null,
    /** The codec string the stream gave, e.g. "mp4a.40.5". */
    val codecString: String? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    /** The bitrate the stream declares (e.g. an HLS variant's), when it does. */
    val declaredKbps: Int? = null,
    /** Bytes downloaded per second of audio, over the most recent segments. */
    val measuredKbps: Int? = null,
)

class PlaybackDetails {
    private class Window(val bytes: ArrayDeque<Long> = ArrayDeque(), val durations: ArrayDeque<Long> = ArrayDeque())

    private val windows = HashMap<String, Window>()
    private val _state = MutableStateFlow<Map<String, MediaDetails>>(emptyMap())
    val state: StateFlow<Map<String, MediaDetails>> = _state.asStateFlow()

    /** The format the audio decoder was given for [mediaId]. */
    @OptIn(UnstableApi::class)
    fun onFormat(mediaId: String, format: Format) {
        val codec = codecName(format.codecs, format.sampleMimeType)
        val sbr = codec == "HE-AAC" || codec == "HE-AAC v2"
        update(mediaId) {
            it.copy(
                codec = codec,
                codecString = format.codecs,
                // HE-AAC carries half the sample rate; spectral band replication restores the rest.
                sampleRateHz = format.sampleRate.takeIf { rate -> rate != Format.NO_VALUE }?.let { rate -> if (sbr && rate <= 24_000) rate * 2 else rate },
                channels = format.channelCount.takeIf { count -> count != Format.NO_VALUE },
                declaredKbps = (format.averageBitrate.takeIf { rate -> rate != Format.NO_VALUE } ?: format.bitrate.takeIf { rate -> rate != Format.NO_VALUE })
                    ?.let { rate -> (rate + 500) / 1000 }
                    ?: it.declaredKbps,
            )
        }
    }

    /** A segment of [mediaId]'s audio was downloaded: [bytes] for [durationMs] of audio. */
    @Synchronized
    fun onSegment(mediaId: String, bytes: Long, durationMs: Long) {
        if (bytes <= 0 || durationMs <= 0) return
        val window = windows.getOrPut(mediaId) { Window() }
        window.bytes.addLast(bytes)
        window.durations.addLast(durationMs)
        while (window.bytes.size > WINDOW_SEGMENTS) {
            window.bytes.removeFirst()
            window.durations.removeFirst()
        }
        val kbps = (window.bytes.sum() * 8 / window.durations.sum()).toInt()
        update(mediaId) { it.copy(measuredKbps = kbps) }
        if (windows.size > MAX_ITEMS) windows.keys.firstOrNull { it != mediaId }?.let(windows::remove)
    }

    private fun update(mediaId: String, change: (MediaDetails) -> MediaDetails) {
        _state.update { all ->
            val updated = all + (mediaId to change(all[mediaId] ?: MediaDetails()))
            if (updated.size > MAX_ITEMS) updated - updated.keys.first { it != mediaId } else updated
        }
    }

    companion object {
        private const val WINDOW_SEGMENTS = 12
        private const val MAX_ITEMS = 50

        /** A readable codec name, with the AAC profile where the stream names one. */
        fun codecName(codecs: String?, mimeType: String?): String {
            val codec = codecs?.split(',')?.firstOrNull()?.trim()?.lowercase()
            return when {
                codec == "mp4a.40.2" -> "AAC-LC"
                codec == "mp4a.40.5" -> "HE-AAC"
                codec == "mp4a.40.29" -> "HE-AAC v2"
                codec == "mp4a.40.42" -> "xHE-AAC"
                codec == "mp4a.40.34" || codec == "mp4a.6b" || codec == "mp4a.69" -> "MP3"
                codec == "opus" -> "Opus"
                codec == "flac" -> "FLAC"
                codec == "ac-3" -> "AC-3"
                codec == "ec-3" -> "E-AC-3"
                mimeType == MimeTypes.AUDIO_AAC -> "AAC"
                else -> AudioFormatInfo.codecName(mimeType)
            }
        }
    }
}
