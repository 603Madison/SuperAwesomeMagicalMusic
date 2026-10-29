package com.music.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.ui.LocalTrackActions

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloaded: Boolean = false,
    highlighted: Boolean = false,
    showSource: Boolean = false,
    extraMenuItems: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        colors = if (highlighted) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = { Artwork(track.artworkUrl, 48.dp) },
        headlineContent = {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (highlighted) FontWeight.Bold else null,
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showSource) {
                    SourceBadge(track.source)
                    Text(" ", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    listOfNotNull(track.artist, track.album).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(
                        Icons.Default.DownloadDone,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val dismiss = { menuOpen = false }
                        StandardTrackMenuItems(track, downloaded, dismiss)
                        extraMenuItems(dismiss)
                    }
                }
            }
        },
    )
}

@Composable
fun ColumnScope.StandardTrackMenuItems(track: Track, downloaded: Boolean, dismiss: () -> Unit) {
    val actions = LocalTrackActions.current
    MenuItem("Play next", Icons.Default.SkipNext) { dismiss(); actions.playNext(track) }
    MenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { dismiss(); actions.addToQueue(listOf(track)) }
    MenuItem("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) { dismiss(); actions.addToPlaylist(listOf(track)) }
    if (downloaded) {
        MenuItem("Remove download", Icons.Default.DeleteOutline) { dismiss(); actions.removeDownload(track) }
    } else {
        MenuItem("Download", Icons.Default.Download) { dismiss(); actions.download(listOf(track)) }
    }
}

@Composable
fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
fun SourceBadge(source: SourceType) {
    val (label, color) = when (source) {
        SourceType.JELLYFIN -> "Jellyfin" to MaterialTheme.colorScheme.primaryContainer
        SourceType.QOBUZ -> "Qobuz" to MaterialTheme.colorScheme.tertiaryContainer
        SourceType.YOUTUBE_MUSIC -> "YouTube" to MaterialTheme.colorScheme.errorContainer
    }
    Surface(color = color, shape = RoundedCornerShape(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
