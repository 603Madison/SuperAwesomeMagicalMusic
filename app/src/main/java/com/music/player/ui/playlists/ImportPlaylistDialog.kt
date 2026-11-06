package com.music.player.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.music.player.playlists.ImportResult
import com.music.player.ui.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Imports a YouTube, YouTube Music or Spotify playlist from a link. */
@Composable
fun ImportPlaylistDialog(onDismiss: () -> Unit, onImported: (String) -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
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
                        if (done.notFound.isNotEmpty()) {
                            Text(
                                "Couldn't find ${done.notFound.size}: " +
                                    done.notFound.take(8).joinToString(", ") { "${it.title} (${it.artist})" } +
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
                        Text(
                            "Paste a link to a YouTube, YouTube Music or Spotify playlist. Spotify songs are matched " +
                                "to your Jellyfin library first, then YouTube Music.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            label = { Text("Playlist link") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
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
                    enabled = link.isNotBlank(),
                    onClick = {
                        error = null
                        job = scope.launch {
                            try {
                                result = container.playlistImporter.import(link) { count, total -> progress = count to total }
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
