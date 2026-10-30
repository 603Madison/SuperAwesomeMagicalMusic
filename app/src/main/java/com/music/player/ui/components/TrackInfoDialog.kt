package com.music.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.data.DownloadEntity
import com.music.player.data.DownloadStatus
import com.music.player.model.Track
import com.music.player.playback.AudioFormatInfo
import com.music.player.playback.PlaybackOrigin
import com.music.player.playback.PlayerUiState
import com.music.player.ui.LocalAppContainer
import java.text.DateFormat
import java.util.Date

/** Where a song comes from and, while it plays, what exactly is playing: source, format, bitrate, buffer. */
@Composable
fun TrackInfoDialog(track: Track, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val player by container.player.state.collectAsStateWithLifecycle()
    val origins by container.resolver.origins.collectAsStateWithLifecycle()
    val network by container.networkMonitor.state.collectAsStateWithLifecycle()
    val downloads by container.downloads.downloads.collectAsStateWithLifecycle(emptyList())
    val download = downloads.firstOrNull { it.trackKey == track.key }
    val isCurrent = player.current?.key == track.key

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.title) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Info("Artist", track.artist)
                    track.album?.let { Info("Album", it) }
                    track.durationMs?.takeIf { it > 0 }?.let { Info("Length", formatTime(it)) }
                    Info(
                        "Saved from",
                        if (track.isSiriusXmChannel) "SiriusXM live channel" else track.source.label,
                    )
                    Info("${track.source.label} id", track.sourceId)

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    if (isCurrent) {
                        NowPlayingInfo(origins[track.key], player, network.networkClass.label)
                    } else {
                        Hint(
                            if (download?.status == DownloadStatus.COMPLETED) "Plays from the downloaded copy."
                            else "Not playing. When it plays, Jellyfin is checked first, then the other services " +
                                "for the best copy within your ${network.networkClass.label.lowercase()} bitrate cap.",
                        )
                    }
                    download?.let { DownloadInfo(it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ColumnScope.NowPlayingInfo(origin: PlaybackOrigin?, player: PlayerUiState, networkLabel: String) {
    Text("Playing now", style = MaterialTheme.typography.titleSmall)
    Info("Source", origin?.description ?: "Finding the best source…")
    origin?.playing?.takeIf { it.key != player.current?.key }?.let { copy ->
        Info("Copy", "${copy.source.label}: ${copy.artist} – ${copy.title}")
    }
    player.format?.let { Info("Format", describe(it)) }
    bitrate(player.format, origin)?.let { Info("Bitrate", it) }
    origin?.quality?.let { Info("Bitrate cap", "${it.label} (picked on $networkLabel)") }
    val remainingMs = (player.durationMs - player.positionMs).coerceAtLeast(0)
    val buffered = formatTime(player.totalBufferedMs)
    Info(
        "Buffered ahead",
        if (player.durationMs > 0 && player.totalBufferedMs > remainingMs + 1000) "$buffered (into the next song)" else buffered,
    )
}

@Composable
private fun ColumnScope.DownloadInfo(download: DownloadEntity) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text("Download", style = MaterialTheme.typography.titleSmall)
    when (download.status) {
        DownloadStatus.COMPLETED -> {
            download.qualityDescription?.let { Info("Copy", it) }
            download.effectiveKbps?.let { Info("Bitrate", if (download.kbps == null) "about $it kbps" else "$it kbps") }
            if (download.sizeBytes > 0) Info("Size", formatBytes(download.sizeBytes))
            download.qualityCheckedAt?.let {
                Info("Checked for better copies", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)))
            }
            if (download.autoDownloaded) Hint("Downloaded for a playlist you keep downloaded.")
        }
        DownloadStatus.QUEUED -> Info("Status", "Waiting to download")
        DownloadStatus.DOWNLOADING -> Info("Status", "Downloading (${(download.progress * 100).toInt()}%)")
        DownloadStatus.FAILED -> Info("Status", "Failed: ${download.error ?: "unknown error"}")
    }
}

private fun describe(format: AudioFormatInfo): String = listOfNotNull(
    format.codec,
    format.sampleRateHz?.let { if (it % 1000 == 0) "${it / 1000} kHz" else "%.1f kHz".format(it / 1000.0) },
    format.channels?.let {
        when (it) {
            1 -> "mono"
            2 -> "stereo"
            else -> "$it channels"
        }
    },
).joinToString(" · ")

/** The decoder's bitrate when the stream declares one, otherwise the source's nominal figure. */
private fun bitrate(format: AudioFormatInfo?, origin: PlaybackOrigin?): String? {
    format?.kbps?.let { return "$it kbps" }
    val lossless = format?.codec in setOf("FLAC", "ALAC", "PCM")
    val nominal = origin?.kbps
    return when {
        lossless && nominal != null -> "Lossless (about $nominal kbps)"
        lossless -> "Lossless"
        nominal != null -> "$nominal kbps"
        else -> null
    }
}

@Composable
private fun Info(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
