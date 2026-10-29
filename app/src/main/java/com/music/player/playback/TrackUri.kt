package com.music.player.playback

import android.net.Uri
import android.util.Base64
import com.music.player.model.Track
import com.music.player.net.AppJson

/**
 * Media items carry a `music://track?d=…` URI holding the whole track. The real stream URL is
 * only chosen when ExoPlayer opens the item, so it reflects the network in use at that moment.
 */
object TrackUri {
    const val SCHEME = "music"
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun encode(track: Track): Uri {
        val payload = Base64.encodeToString(AppJson.encodeToString(Track.serializer(), track).toByteArray(), FLAGS)
        return Uri.Builder().scheme(SCHEME).authority("track").appendQueryParameter("d", payload).build()
    }

    fun decode(uri: Uri): Track? {
        if (uri.scheme != SCHEME) return null
        val payload = uri.getQueryParameter("d") ?: return null
        return runCatching {
            AppJson.decodeFromString(Track.serializer(), String(Base64.decode(payload, FLAGS)))
        }.getOrNull()
    }
}
