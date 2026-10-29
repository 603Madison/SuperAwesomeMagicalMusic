package com.music.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.model.Track
import com.music.player.ui.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun AddToPlaylistDialog(tracks: List<Track>, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val container = LocalAppContainer.current
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "New playlist" else "Add to playlist") },
        text = {
            if (creating) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable { creating = true },
                            leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                            headlineContent = { Text("New playlist") },
                        )
                    }
                    items(playlists, key = { it.id }) { playlist ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    container.playlists.addTracks(playlist.id, tracks)
                                    onDone("Added to ${playlist.name}")
                                }
                            },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                            headlineContent = { Text(playlist.name) },
                            supportingContent = { Text("${playlist.trackCount} songs") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            container.playlists.create(name, tracks)
                            onDone("Created ${name.trim()}")
                        }
                    },
                ) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text("Name") })
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
