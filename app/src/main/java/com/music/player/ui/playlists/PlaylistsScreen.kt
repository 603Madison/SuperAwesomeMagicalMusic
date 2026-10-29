package com.music.player.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(onOpen: (String) -> Unit) {
    val container = LocalAppContainer.current
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle(emptyList())
    val settings by container.settingsState.collectAsStateWithLifecycle()
    val syncStatus by container.playlistSync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val nextcloudReady = settings.nextcloud.isConfigured

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Playlists") },
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
                    !nextcloudReady -> "Set up Nextcloud in Settings to back up and share playlists across devices."
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
                ListItem(
                    modifier = Modifier.clickable { onOpen(playlist.id) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                    headlineContent = { Text(playlist.name) },
                    supportingContent = { Text("${playlist.trackCount} ${if (playlist.trackCount == 1) "song" else "songs"}") },
                    trailingContent = {
                        if (nextcloudReady && playlist.needsUpload) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = "Not yet synced",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
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
                scope.launch { onOpen(container.playlists.create(name)) }
            },
        )
    }
}
