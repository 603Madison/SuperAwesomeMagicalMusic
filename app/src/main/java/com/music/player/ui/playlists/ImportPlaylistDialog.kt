package com.music.player.ui.playlists

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // A CSV or text file from the phone (Downloads, Drive…): its songs fill the list, its name the name.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val label = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (out.size() <= MAX_FILE) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    } ?: error("Couldn't open the file")
                    if (bytes.size > MAX_FILE) error("That file is too big (1 MB at most)")
                    label to String(bytes, Charsets.UTF_8)
                }
            }.onSuccess { (label, content) ->
                text = content
                fileName = label ?: "the file"
                if (name.isBlank()) label?.let { name = it.substringBeforeLast('.').replace('_', ' ').trim() }
            }.onFailure { error = it.message ?: "Couldn't read the file" }
        }
    }

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
                                "Couldn't find ${done.notFound.size} (they're in the playlist greyed out, and are looked for again now and then): " +
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
                            OutlinedButton(onClick = { pickFile.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }) {
                                Icon(Icons.Default.FileOpen, contentDescription = null)
                                Text(fileName?.let { " From $it" } ?: " Choose a file (CSV or text)")
                            }
                            Text(
                                "One a line, like “Artist - Title”, or a CSV export with title and artist columns: choose the " +
                                    "file, or paste it (for Qobuz and other services, export one with TuneMyMusic or Soundiiz).",
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

private const val MAX_FILE = 1_000_000
