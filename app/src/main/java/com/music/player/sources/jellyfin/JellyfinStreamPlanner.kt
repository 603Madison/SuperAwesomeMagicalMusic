package com.music.player.sources.jellyfin

import com.music.player.quality.StreamQuality
import com.music.player.settings.TranscodeCodec

sealed interface JellyfinPlan {
    /** Serve the original file untouched. */
    data class Direct(val codec: String?) : JellyfinPlan

    /** Let the server transcode; [kbps] is null for lossless output. */
    data class Transcode(val container: String, val codec: String, val kbps: Int?) : JellyfinPlan
}

/**
 * Jellyfin can transcode to any bitrate, so the cap is honoured exactly: files already at or
 * under the cap are sent as-is, anything bigger is transcoded down to the cap.
 */
object JellyfinStreamPlanner {
    /** Codecs every Android device decodes out of the box (ALAC/APE/WMA/DSD need transcoding). */
    private val DIRECT_PLAY_CODECS = setOf(
        "mp3", "flac", "aac", "vorbis", "opus",
        "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_u8",
    )

    /** Allows for container overhead and embedded artwork inflating the reported bitrate. */
    private const val BITRATE_TOLERANCE = 1.05

    fun plan(source: JfMediaSource?, quality: StreamQuality, transcodeCodec: TranscodeCodec): JellyfinPlan {
        val audio = source?.mediaStreams?.firstOrNull { it.type.equals("Audio", ignoreCase = true) }
        val codec = audio?.codec?.lowercase()
        val directPlayable = codec != null && codec in DIRECT_PLAY_CODECS
        val bitrate = audio?.bitRate ?: source?.bitrate
        return when (quality) {
            StreamQuality.Original ->
                if (directPlayable) JellyfinPlan.Direct(codec) else JellyfinPlan.Transcode("flac", "flac", null)
            is StreamQuality.Capped ->
                if (directPlayable && bitrate != null && bitrate <= quality.kbps * 1000 * BITRATE_TOLERANCE) {
                    JellyfinPlan.Direct(codec)
                } else {
                    JellyfinPlan.Transcode(transcodeCodec.container, transcodeCodec.codec, quality.kbps)
                }
        }
    }

    /** The file's bitrate in kbps; lossless files without a reported bitrate count as CD quality. */
    fun kbpsOf(source: JfMediaSource?): Int? {
        val audio = source?.mediaStreams?.firstOrNull { it.type.equals("Audio", ignoreCase = true) }
        val bps = audio?.bitRate ?: source?.bitrate
        if (bps != null && bps > 0) return (bps / 1000).toInt()
        val codec = audio?.codec?.lowercase() ?: return null
        return if (codec == "flac" || codec == "alac" || codec.startsWith("pcm")) 1411 else null
    }

    fun extensionForCodec(codec: String?): String = when (codec) {
        "mp3" -> "mp3"
        "flac" -> "flac"
        "aac" -> "m4a"
        "vorbis" -> "ogg"
        "opus" -> "opus"
        null -> "audio"
        else -> if (codec.startsWith("pcm")) "wav" else "audio"
    }
}
