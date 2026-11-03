package com.music.player.ui.playlists

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.music.player.ui.components.Artwork
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.nextcloud.SyncStatus
import com.music.player.ui.LocalAppContainer
import com.music.player.ui.components.TextInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(onOpen: (String) -> Unit) {
    val container = LocalAppContainer.current
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle(emptyList())
    val covers by container.playlists.covers.collectAsStateWithLifecycle(emptyMap())
    val settings by container.settingsState.collectAsStateWithLifecycle()
    val syncStatus by container.playlistSync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val nextcloudReady = settings.nextcloud.isConfigured

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Playlists")
                        if (settings.nextcloud.isConfigured) {
                            Text(settings.nextcloud.davUser, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (nextcloudReady) {
                        if (syncStatus == SyncStatus.Running) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            IconButton(onClick = { scope.launch { runCatching { container.playlistSync.sync() } } }) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync with Nextcloud")
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New playlist") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                val status = when {
                    !nextcloudReady -> "Sign in to Nextcloud in Settings to back up your playlists and use them on other devices."
                    else -> when (val s = syncStatus) {
                        SyncStatus.Idle -> "Saved to Nextcloud: ${settings.nextcloud.folder}"
                        SyncStatus.Running -> "Syncing with Nextcloud…"
                        is SyncStatus.Done -> "Synced with Nextcloud (${s.result})"
                        is SyncStatus.Failed -> "Nextcloud sync failed: ${s.message}"
                    }
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncStatus is SyncStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (playlists.isEmpty()) {
                item {
                    Text(
                        "No playlists yet. Create one, or add songs from search with “Add to playlist”.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                ElevatedCard(
                    onClick = { onOpen(playlist.id) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlaylistCover(covers[playlist.id].orEmpty(), 72.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${playlist.trackCount} ${if (playlist.trackCount == 1) "song" else "songs"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (playlist.keepDownloaded) {
                            Icon(
                                Icons.Default.DownloadForOffline,
                                contentDescription = "Kept downloaded",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        } else if (nextcloudReady && playlist.needsUpload) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = "Not yet synced",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(88.dp)) } // Room above the "New playlist" button.
        }
    }

    if (creating) {
        TextInputDialog(
            title = "New playlist",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                scope.launch {
                    val id = container.playlists.create(name)
                    withContext(Dispatchers.Main.immediate) { onOpen(id) }
                }
            },
        )
    }
}

/** A playlist's cover: its first song's artwork, or a 2×2 mosaic once it has four different ones. */
@Composable
private fun PlaylistCover(artwork: List<String>, size: Dp) {
    if (artwork.size < 4) {
        Artwork(artwork.firstOrNull(), size, corner = 14.dp)
        return
    }
    Column(Modifier.size(size).clip(RoundedCornerShape(14.dp))) {
        artwork.chunked(2).forEach { row ->
            Row { row.forEach { Artwork(it, size / 2, corner = 0.dp) } }
        }
    }
}
