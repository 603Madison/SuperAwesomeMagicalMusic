package com.music.nowplaying

import android.app.PendingIntent
import android.content.Context
import kotlinx.serialization.Serializable

/**
 * The app hosting Now Playing (its Application implements this): it recognises a recording's
 * fingerprints (a music server, an on-device library…) and says what tapping a song opens.
 * Only fingerprints leave the phone, never the sound itself: they can't be turned back into
 * audio, and say nothing about speech.
 */
interface NowPlayingHost {
    /**
     * The song [hashes] (fingerprints of [heardMs] of sound, see `Fingerprint.hashes` with
     * `Fingerprint.CLIP`) are from, or null. [hint] is the last song heard, which it likely
     * still is. Throws when it can't be asked (no connection, signed out).
     */
    suspend fun recognise(hashes: LongArray, heardMs: Long, hint: String?): Recognition?

    /** What tapping Now Playing's notification opens (for [song], the song showing, if any). */
    fun openNowPlaying(context: Context, song: Recognition?): PendingIntent?
}

/** A song Now Playing recognised. */
@Serializable
data class Recognition(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    /** Where the song was when the recording ended, and how long it is (when known). */
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    /** When it was heard (this phone's clock). */
    val at: Long = System.currentTimeMillis(),
    /** The host's own id for it (to open it, or to pass back as a hint). */
    val id: String? = null,
) {
    /** How long until the song is over, as far as is known. */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long? {
        val length = durationMs ?: return null
        val position = positionMs ?: return null
        return (length - position - (now - at)).coerceAtLeast(0)
    }

    fun sameSong(other: Recognition?): Boolean =
        other != null && title.equals(other.title, ignoreCase = true) && artist.equals(other.artist, ignoreCase = true)
}
