package com.music.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.bookmarks.Bookmark
import com.music.player.model.Track
import com.music.player.ui.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * A song bookmarked while driving: asks which playlist it goes in. Songs heard on SiriusXM are
 * saved as usual (the Jellyfin copy if the library has it, otherwise requested from Lidarr).
 */
@Composable
fun BookmarkDialog(
    bookmark: Bookmark,
    /** How many bookmarks are waiting, this one included. */
    waiting: Int,
    onLater: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember(bookmark.id) { mutableStateOf(false) }
    var name by remember(bookmark.id) { mutableStateOf("") }
    var saving by remember(bookmark.id) { mutableStateOf(false) }

    fun save(into: suspend (Track) -> String) {
        saving = true
        scope.launch {
            try {
                val track = if (bookmark.heard) container.heardSongs.capture(bookmark.track).track else bookmark.track
                val message = into(track)
                container.bookmarks.remove(bookmark.id)
                onMessage(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage("Couldn't save \"${bookmark.track.title}\": ${e.message ?: e.javaClass.simpleName}")
            } finally {
                saving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(if (waiting > 1) "Bookmarked while driving (1 of $waiting)" else "Bookmarked while driving") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(bookmark.track.artworkUrl, size = 56.dp)
                    Column(Modifier.weight(1f)) {
                        Text(bookmark.track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(bookmark.track.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                bookmark.heardOn?.let { "On $it" },
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(bookmark.savedAtMs)),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                when {
                    saving -> Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                    creating -> OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("New playlist") },
                        singleLine = true,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
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
                                    save { track ->
                                        container.playlists.addTracks(playlist.id, listOf(track))
                                        "Added \"${track.title}\" to ${playlist.name}"
                                    }
                                },
                                leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                                headlineContent = { Text(playlist.name) },
                                supportingContent = { Text("${playlist.trackCount} songs") },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    enabled = name.isNotBlank() && !saving,
                    onClick = {
                        save { track ->
                            container.playlists.create(name, listOf(track))
                            "Created ${name.trim()} with \"${track.title}\""
                        }
                    },
                ) { Text("Create") }
            } else {
                TextButton(enabled = !saving, onClick = onLater) { Text("Later") }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (creating) {
                        creating = false
                    } else {
                        scope.launch { container.bookmarks.remove(bookmark.id) }
                        onMessage("Removed the bookmark")
                    }
                },
            ) { Text(if (creating) "Back" else "Discard") }
        },
    )
}
