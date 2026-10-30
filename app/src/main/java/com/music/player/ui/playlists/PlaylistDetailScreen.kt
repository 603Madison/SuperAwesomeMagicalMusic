package com.music.player.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.downloads.RefreshResult
import com.music.player.ui.LocalAppContainer
import com.music.player.ui.LocalTrackActions
import com.music.player.ui.components.MenuItem
import com.music.player.ui.components.TextInputDialog
import com.music.player.ui.components.TrackRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(playlistId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val actions = LocalTrackActions.current
    val playlist by remember(playlistId) { container.playlists.playlist(playlistId) }.collectAsStateWithLifecycle(null)
    val tracks by remember(playlistId) { container.playlists.tracks(playlistId) }.collectAsStateWithLifecycle(emptyList())
    val downloaded by container.downloads.completedKeys.collectAsStateWithLifecycle(emptySet())
    val current by container.player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    // Leave the screen if the playlist disappears (deleted here or by a sync).
    LaunchedEffect(playlist) {
        if (playlist != null) loaded = true
        if (loaded && (playlist == null || playlist?.deleted == true)) onBack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(
                        enabled = !refreshing && tracks.isNotEmpty(),
                        onClick = {
                            refreshing = true
                            scope.launch {
                                try {
                                    actions.message(describe(container.offlinePlaylists.refresh(playlistId)))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    actions.message("Couldn't refresh: ${e.message ?: e.javaClass.simpleName}")
                                } finally {
                                    refreshing = false
                                }
                            }
                        },
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh downloads")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            val keep = playlist?.keepDownloaded == true
                            DropdownMenuItem(
                                text = { Text("Keep downloaded") },
                                leadingIcon = { Icon(Icons.Default.DownloadForOffline, contentDescription = null) },
                                trailingIcon = { Checkbox(checked = keep, onCheckedChange = null) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { container.playlists.setKeepDownloaded(playlistId, !keep) }
                                },
                            )
                            MenuItem("Rename", Icons.Default.Edit) { menuOpen = false; renaming = true }
                            MenuItem("Download all now", Icons.Default.Download) { menuOpen = false; actions.download(tracks) }
                            MenuItem("Delete playlist", Icons.Default.Delete) { menuOpen = false; confirmDelete = true }
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = { actions.play(tracks) }, enabled = tracks.isNotEmpty()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(" Play")
                    }
                    FilledTonalButton(onClick = { actions.shuffle(tracks) }, enabled = tracks.isNotEmpty()) {
                        Icon(Icons.Default.Shuffle, contentDescription = null)
                        Text(" Shuffle")
                    }
                }
                val downloadedCount = tracks.count { it.key in downloaded }
                Text(
                    "${tracks.size} songs · $downloadedCount downloaded" +
                        if (playlist?.keepDownloaded == true) " · kept downloaded and upgraded to better copies" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            itemsIndexed(tracks, key = { index, track -> "$index-${track.key}" }) { index, track ->
                TrackRow(
                    track = track,
                    onClick = { actions.play(tracks, index) },
                    downloaded = track.key in downloaded,
                    highlighted = current.current?.key == track.key,
                ) { dismiss ->
                    if (index > 0) {
                        MenuItem("Move up", Icons.Default.ArrowUpward) {
                            dismiss(); scope.launch { container.playlists.move(playlistId, index, index - 1) }
                        }
                    }
                    if (index < tracks.lastIndex) {
                        MenuItem("Move down", Icons.Default.ArrowDownward) {
                            dismiss(); scope.launch { container.playlists.move(playlistId, index, index + 1) }
                        }
                    }
                    MenuItem("Remove from playlist", Icons.Default.DeleteOutline) {
                        dismiss(); scope.launch { container.playlists.removeAt(playlistId, index) }
                    }
                }
            }
        }
    }

    if (renaming) {
        TextInputDialog(
            title = "Rename playlist",
            initial = playlist?.name.orEmpty(),
            confirmLabel = "Rename",
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                scope.launch { container.playlists.rename(playlistId, name) }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${playlist?.name}”?") },
            text = { Text("It will also be removed from Nextcloud on the next sync. Downloaded songs are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { container.playlists.delete(playlistId) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun describe(result: RefreshResult): String = buildList {
    when (result.inJellyfin) {
        null -> add("Jellyfin couldn't be reached")
        0 -> add("No new songs in Jellyfin")
        else -> add("${result.inJellyfin} ${if (result.inJellyfin == 1) "song" else "songs"} from other services found in Jellyfin")
    }
    if (result.rechecking > 0) add("checking ${result.rechecking} downloads for Jellyfin or better copies")
    if (result.queued > 0) add("downloading ${result.queued} missing")
}.joinToString("; ")
