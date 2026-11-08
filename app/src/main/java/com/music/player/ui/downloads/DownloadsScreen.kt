package com.music.player.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.data.DownloadEntity
import com.music.player.data.DownloadStatus
import com.music.player.ui.LocalAppContainer
import com.music.player.ui.LocalTrackActions
import com.music.player.ui.components.Artwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen() {
    val container = LocalAppContainer.current
    val actions = LocalTrackActions.current
    val context = LocalContext.current
    val downloads by container.downloads.downloads.collectAsStateWithLifecycle(emptyList())
    val completed = downloads.filter { it.status == DownloadStatus.COMPLETED }
    val completedTracks = completed.map { it.track }
    var tripPrep by remember { mutableStateOf(false) }
    if (tripPrep) TripPrepDialog(onDismiss = { tripPrep = false })

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    TextButton(onClick = { tripPrep = true }) {
                        Icon(Icons.Default.Luggage, contentDescription = null)
                        Text(" Trip")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "${completed.size} songs · ${Formatter.formatShortFileSize(context, completed.sumOf { it.sizeBytes })} " +
                            "· available offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { actions.play(completedTracks) }, enabled = completed.isNotEmpty()) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(" Play")
                        }
                        FilledTonalButton(onClick = { actions.shuffle(completedTracks) }, enabled = completed.isNotEmpty()) {
                            Icon(Icons.Default.Shuffle, contentDescription = null)
                            Text(" Shuffle")
                        }
                    }
                }
            }
            if (downloads.isEmpty()) {
                item {
                    Text(
                        "Nothing downloaded yet. Use “Download” on any song or playlist — from Jellyfin " +
                            "or YouTube Music — to listen without a connection.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(downloads, key = { it.trackKey }) { download ->
                DownloadRow(
                    download = download,
                    onPlay = {
                        val index = completedTracks.indexOfFirst { it.key == download.trackKey }
                        if (index >= 0) actions.play(completedTracks, index)
                    },
                    onRetry = { actions.download(listOf(download.track)) },
                    onDelete = { actions.removeDownload(download.track) },
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(download: DownloadEntity, onPlay: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.clickable(enabled = download.status == DownloadStatus.COMPLETED, onClick = onPlay),
        leadingContent = { Artwork(download.track.artworkUrl, 48.dp) },
        headlineContent = { Text(download.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(download.track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (download.status) {
                    DownloadStatus.QUEUED -> Text("Waiting for a network that allows downloads…", style = MaterialTheme.typography.bodySmall)
                    DownloadStatus.DOWNLOADING -> LinearProgressIndicator(
                        progress = { download.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    DownloadStatus.COMPLETED -> Text(
                        "${download.qualityDescription.orEmpty()} · ${Formatter.formatShortFileSize(context, download.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DownloadStatus.FAILED -> Text(
                        download.error ?: "Download failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = {
            Row {
                if (download.status == DownloadStatus.FAILED) {
                    IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, contentDescription = "Retry") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, contentDescription = "Remove download") }
            }
        },
    )
}
