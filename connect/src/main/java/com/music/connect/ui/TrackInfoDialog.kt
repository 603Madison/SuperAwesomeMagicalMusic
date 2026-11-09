package com.music.connect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.connect.net.ApiTrack
import com.music.connect.playback.AudioDetails
import com.music.player.model.SourceType
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Where a song comes from and, while it plays, exactly what's playing: the copy the server picked,
 * the codec, sample rate and bitrate, the quality cap, volume levelling and how much is loaded.
 */
@Composable
fun TrackInfoDialog(track: ApiTrack, onDismiss: () -> Unit) {
    val c = appContainer()
    val player = LocalPlayer.current
    val playing by player.state.collectAsState()
    val facts by c.streamFacts.collectAsState()
    val audio by c.audioDetails.collectAsState()
    val live by c.liveNow.collectAsState()
    val settings by c.settings.collectAsState()
    val network by c.network.state.collectAsState()
    val isCurrent = playing.track?.key == track.key
    // How much is loaded ahead moves on its own: look again every second.
    var buffered by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isCurrent) {
        while (isCurrent) {
            buffered = player.bufferedMs()
            delay(1_000)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.title) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!track.isLive) Info("Artist", track.artist)
                    track.album?.takeIf { it.isNotBlank() }?.let { Info("Album", it) }
                    track.durationMs?.takeIf { it > 0 }?.let { Info("Length", duration(it)) }
                    Info("From", sourceOf(track))
                    Info("${track.source.label} id", track.sourceId)

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    if (!isCurrent) {
                        Hint("Not playing. When it plays, your server picks the copy: your library first, then the other services, for the best one within your ${network.networkClass.label.lowercase()} quality.")
                    } else {
                        Text("Playing now", style = MaterialTheme.typography.titleSmall)
                        if (track.isLive) {
                            live?.let { now -> Info("On now", listOf(now.title, now.artist).filter { it.isNotBlank() }.joinToString(" · ")) }
                            Info("Stream", if (track.isChannel) "SiriusXM's live stream, through your server" else "The station's stream, through your server")
                        }
                        if (facts[track.key] == null && !track.isLive) Info("Copy", "Being picked by your server…")
                        facts[track.key]?.let { fact ->
                            Info("Copy", fact.description)
                            if (!fact.downloaded && !track.isLive) Info("Fetched by", if (fact.byPhone) "This phone, straight from YouTube (your server couldn't play it)" else "Your server")
                            fact.kbps?.let { Info("Bitrate", "$it kbps" + if (fact.transcoded) " (converted on your server)" else "") }
                            fact.capKbps?.let { cap ->
                                val why = when {
                                    fact.dataSaver -> "Android restricts this app's mobile data, so “With Data Saver on” applies (Settings › Quality)"
                                    else -> "picked on ${fact.network ?: network.networkClass.label}"
                                }
                                Info("Quality cap", "${if (cap == 0) "Original" else "$cap kbps"} ($why)")
                            }
                        }
                        audio?.takeIf { it.key == null || it.key == track.key }?.let { decoded ->
                            codecOf(decoded)?.let { Info("Codec", it) }
                            describe(decoded)?.let { Info("Audio", it) }
                            decoded.bitrateKbps?.takeIf { facts[track.key]?.kbps == null }?.let { Info("Bitrate", "$it kbps") }
                        }
                        Info(
                            "Volume levelling",
                            when {
                                !settings.levelling -> "Off"
                                c.loudness.knownGainDb != null -> String.format(Locale.ROOT, "%+.1f dB (from your library's loudness value)", c.loudness.currentGainDb)
                                else -> String.format(Locale.ROOT, "%+.1f dB (measured while playing)", c.loudness.currentGainDb)
                            },
                        )
                        if (!track.isLive) {
                            val remaining = (playing.durationMs - playing.positionMs).coerceAtLeast(0)
                            Info("Loaded ahead", duration(buffered) + if (playing.durationMs > 0 && buffered > remaining + 1_000) " (into the next song)" else "")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(112.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun sourceOf(track: ApiTrack): String = when {
    track.isChannel -> "SiriusXM live channel"
    track.isStationTrack -> "Pandora station, through SiriusXM"
    track.source == SourceType.RADIO -> "Radio station"
    else -> track.source.label
}

private fun codecOf(audio: AudioDetails): String? {
    val name = when (audio.mime?.lowercase()) {
        null -> null
        "audio/flac" -> "FLAC"
        "audio/mpeg" -> "MP3"
        "audio/mp4a-latm" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/alac" -> "ALAC"
        "audio/raw" -> "PCM (uncompressed)"
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3" -> "Dolby Digital Plus"
        else -> audio.mime.removePrefix("audio/").uppercase()
    } ?: return null
    return listOfNotNull(name, audio.codecs?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }).joinToString(" · ")
}

/** "44.1 kHz · stereo". */
private fun describe(audio: AudioDetails): String? = listOfNotNull(
    audio.sampleRateHz?.let { if (it % 1000 == 0) "${it / 1000} kHz" else String.format(Locale.ROOT, "%.1f kHz", it / 1000.0) },
    audio.channels?.let {
        when (it) {
            1 -> "mono"
            2 -> "stereo"
            else -> "$it channels"
        }
    },
).joinToString(" · ").ifEmpty { null }
