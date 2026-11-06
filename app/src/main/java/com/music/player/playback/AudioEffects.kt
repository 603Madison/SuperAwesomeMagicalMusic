package com.music.player.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.music.player.settings.PlaybackSettings

/**
 * The phone's equalizer (its presets) and bass boost on the player's audio, and the session
 * announced to equalizer apps (Wavelet and the like) so they can work on it too.
 */
@OptIn(UnstableApi::class)
class AudioEffects(private val context: Context, private val onPresets: (List<String>) -> Unit) {
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var settings = PlaybackSettings()

    fun onSessionId(id: Int) {
        if (id == sessionId) return
        release()
        sessionId = id
        if (id == C.AUDIO_SESSION_ID_UNSET) return
        announce(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        equalizer = runCatching { Equalizer(0, id) }.onFailure { Log.i(TAG, "No equalizer", it) }.getOrNull()
        equalizer?.let { eq -> onPresets((0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }) }
        apply(settings)
    }

    fun apply(playback: PlaybackSettings) {
        settings = playback
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            equalizer?.let { eq ->
                val preset = playback.equalizerPreset
                if (preset in 0 until eq.numberOfPresets) {
                    eq.usePreset(preset.toShort())
                    eq.enabled = true
                } else {
                    eq.enabled = false
                }
            }
            if (playback.bassBoost > 0) {
                val boost = bassBoost ?: BassBoost(0, sessionId).also { bassBoost = it }
                if (boost.strengthSupported) boost.setStrength(playback.bassBoost.coerceIn(0, 1000).toShort())
                boost.enabled = true
            } else {
                bassBoost?.enabled = false
            }
        }.onFailure { Log.w(TAG, "Couldn't apply audio effects", it) }
    }

    fun release() {
        if (sessionId != C.AUDIO_SESSION_ID_UNSET) announce(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        sessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun announce(action: String) {
        runCatching {
            context.sendBroadcast(
                Intent(action)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
            )
        }
    }

    private companion object {
        const val TAG = "AudioEffects"
    }
}
