package com.music.player.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.music.player.playlists.ImportResult
import com.music.player.ui.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Imports a playlist or album from a link (Spotify, Apple Music, Tidal, Deezer, YouTube,
 * ListenBrainz), or from a typed or pasted list of songs.
 */
@Composable
fun ImportPlaylistDialog(onDismiss: () -> Unit, onImported: (String) -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var fromList by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ImportResult?>(null) }

    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        title = { Text("Import a playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val done = result
                when {
                    done != null -> {
                        Text("Added ${done.added} songs to \"${done.name}\".")
                        done.note?.let { Text("$it.", style = MaterialTheme.typography.bodySmall) }
                        if (done.notFound.isNotEmpty()) {
                            Text(
                                "Couldn't find ${done.notFound.size}: " +
                                    done.notFound.take(8).joinToString(", ") { if (it.artist.isBlank()) it.title else "${it.title} (${it.artist})" } +
                                    if (done.notFound.size > 8) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    job != null -> {
                        val (count, total) = progress ?: (0 to 0)
                        Text(if (total > 0) "Finding songs: $count of $total" else "Reading the playlist…")
                        if (total > 0) LinearProgressIndicator(progress = { count.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !fromList, onClick = { fromList = false }, label = { Text("From a link") })
                            FilterChip(selected = fromList, onClick = { fromList = true }, label = { Text("From a list") })
                        }
                        if (!fromList) {
                            Text(
                                "A playlist or album on Spotify, Apple Music, Tidal, Deezer, YouTube or ListenBrainz. Songs are " +
                                    "found in your Jellyfin library first, then on YouTube Music.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = link,
                                onValueChange = { link = it },
                                label = { Text("Playlist or album link") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                label = { Text("Songs") },
                                placeholder = { Text("Radiohead - Paranoid Android\nFleetwood Mac - Dreams") },
                                minLines = 4,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "One a line, like “Artist - Title”, or a pasted CSV export with title and artist columns " +
                                    "(for Qobuz and other services, export one with TuneMyMusic or Soundiiz).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {
            val done = result
            when {
                done != null -> TextButton(onClick = { onImported(done.playlistId) }) { Text("Open") }
                job == null -> TextButton(
                    enabled = if (fromList) text.isNotBlank() else link.isNotBlank(),
                    onClick = {
                        error = null
                        job = scope.launch {
                            try {
                                val onProgress = { count: Int, total: Int -> progress = count to total }
                                result = if (fromList) container.playlistImporter.importText(text, name, onProgress)
                                else container.playlistImporter.import(link, onProgress)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                            } finally {
                                job = null
                            }
                        }
                    },
                ) { Text("Import") }
            }
        },
        dismissButton = {
            TextButton(onClick = { job?.cancel(); onDismiss() }) { Text(if (result != null) "Close" else "Cancel") }
        },
    )
}
