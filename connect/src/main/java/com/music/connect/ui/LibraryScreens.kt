package com.music.connect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.connect.data.Library
import com.music.connect.net.ImportResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(onOpen: (String) -> Unit) {
    val c = appContainer()
    val playlists by c.library.all.collectAsState(initial = emptyList())
    val online by c.online.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    // Playlists made or changed elsewhere (the web player, another phone) arrive when this opens,
    // and on a pull down.
    fun refresh(byHand: Boolean) {
        scope.launch {
            if (byHand) refreshing = true
            try {
                c.library.sync()
            } catch (e: Exception) {
                if (byHand) c.tell(if (Library.isOffline(e)) "Offline: these are the playlists on your phone" else e.message ?: "Couldn't refresh")
            } finally {
                refreshing = false
            }
        }
    }
    LaunchedEffect(online) { if (online) refresh(byHand = false) }
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New playlist") })
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh(byHand = true) }, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = { importing = true }) { Icon(Icons.Rounded.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Import a playlist") }
                    }
                }
                if (playlists.isEmpty()) item { Empty("No playlists yet", "Make one here or on the web player, or import one from Spotify, Apple Music, Tidal, Deezer and more; they're kept on your phone too.", Icons.AutoMirrored.Rounded.QueueMusic) }
                items(playlists, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(p.artwork.firstOrNull(), 56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.count} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (p.keepOffline) Icon(Icons.Rounded.DownloadForOffline, "Kept offline", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    if (importing) ImportDialog(onClose = { importing = false }, onImported = onOpen)
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New playlist") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val id = c.library.create(name.ifBlank { "New playlist" })
                        creating = false
                        name = ""
                        onOpen(id)
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

/** Brings in a playlist or album from a link (Spotify, Apple Music, Tidal…), or a typed list of songs. */
@Composable
private fun ImportDialog(onClose: () -> Unit, onImported: (String) -> Unit) {
    val c = appContainer()
    val online by c.online.collectAsState()
    val scope = rememberCoroutineScope()
    var fromList by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ImportResult?>(null) }
    val ready = online && !busy && (if (fromList) text.isNotBlank() else url.isNotBlank())

    report?.let { result ->
        val open = { onClose(); onImported(result.playlist.summary.id) }
        AlertDialog(
            onDismissRequest = open,
            title = { Text("Imported ${result.found} songs") },
            text = {
                LazyColumn {
                    result.note?.let { item { Text("$it.", Modifier.padding(bottom = 8.dp)) } }
                    if (result.missing.isNotEmpty()) item { Text("These weren't found in your sources:", Modifier.padding(bottom = 4.dp)) }
                    items(result.missing) { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
            confirmButton = { TextButton(onClick = open) { Text("Open the playlist") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Import a playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !fromList, onClick = { fromList = false }, label = { Text("From a link") })
                    FilterChip(selected = fromList, onClick = { fromList = true }, label = { Text("From a list") })
                }
                if (!fromList) {
                    OutlinedTextField(url, { url = it }, label = { Text("Playlist or album link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Spotify, Apple Music, Tidal, Deezer, YouTube or ListenBrainz. Each song is found in your own sources, your library first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        text, { text = it },
                        label = { Text("Songs") },
                        placeholder = { Text("Radiohead - Paranoid Android\nFleetwood Mac - Dreams") },
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("One a line, like “Artist - Title”, or a pasted CSV export with title and artist columns (for Qobuz and other services, export one with TuneMyMusic or Soundiiz).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!online) Text("Importing needs a connection.", color = MaterialTheme.colorScheme.error)
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text("Finding the songs… this takes a moment for long playlists.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                busy = true
                scope.launch {
                    try {
                        val result = if (fromList) c.api.importPlaylist(null, text, name) else c.api.importPlaylist(url.trim(), null, null)
                        // Brings the new playlist (and any other changes) onto the phone.
                        runCatching { c.library.sync() }
                        if (result.missing.isEmpty() && result.note == null) {
                            c.tell("Imported ${result.found} songs")
                            onClose()
                            onImported(result.playlist.summary.id)
                        } else {
                            report = result
                        }
                    } catch (e: Exception) {
                        c.tell(e.message ?: "Couldn't import that")
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
fun PlaylistScreen(id: String, onGone: () -> Unit) {
    val c = appContainer()
    val player = LocalPlayer.current
    val info by c.library.playlist(id).collectAsState(initial = null)
    val entries by c.library.entries(id).collectAsState(initial = emptyList())
    val downloaded by c.downloads.doneKeys.collectAsState(initial = emptySet())
    val online by c.online.collectAsState()
    val playing by player.state.collectAsState()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val playlist = info ?: run {
        Empty("This playlist isn't here", "It may have been deleted on another device.")
        return
    }
    // Offline, only downloaded songs can play.
    val playable = entries.map { it.track }.filter { online || it.key in downloaded }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.headlineMedium)
                Text("${entries.size} songs" + if (!online) " · ${playable.size} downloaded" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { player.play(playable) }, enabled = playable.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Text("Play") }
                    FilledTonalButton(onClick = { player.playShuffled(playable) }, enabled = playable.size > 1) { Icon(Icons.Rounded.Shuffle, null); Text("Shuffle") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep offline", style = MaterialTheme.typography.bodyLarge)
                        Text("Downloads every song (on Wi-Fi, unless set otherwise), and new ones as they're added.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(playlist.keepOffline, onCheckedChange = { keep ->
                        scope.launch {
                            c.library.setKeepOffline(id, keep)
                            if (keep) c.downloads.queue(entries.map { it.track })
                        }
                    })
                }
                Row {
                    TextButton(onClick = { renaming = true }) { Text("Rename") }
                    TextButton(onClick = { deleting = true }) { Text("Delete") }
                }
            }
        }
        if (entries.isEmpty()) item { Empty("This playlist is empty", "Add songs from search with their ⋮ menu.") }
        itemsIndexed(entries, key = { _, e -> e.entryId }) { i, entry ->
            val available = online || entry.track.key in downloaded
            TrackRow(
                entry.track,
                onClick = {
                    if (available) player.play(playable, playable.indexOfFirst { it.key == entry.track.key }.coerceAtLeast(0))
                    else c.tell("Offline, and this song isn't downloaded")
                },
                current = playing.track?.key == entry.track.key,
                downloaded = entry.track.key in downloaded,
                available = available,
                extraActions = buildList {
                    add("Remove from playlist" to { scope.launch { c.library.remove(id, listOf(entry.entryId)) }; Unit })
                    if (i > 0) add("Move up" to { reorder(scope, c, id, entries.map { it.entryId }, i, i - 1) })
                    if (i < entries.size - 1) add("Move down" to { reorder(scope, c, id, entries.map { it.entryId }, i, i + 1) })
                },
            )
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { c.library.rename(id, name); renaming = false } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = { Text("It goes on every device. The songs stay in their sources (and downloads stay downloaded).") },
            confirmButton = { TextButton(onClick = { scope.launch { c.library.delete(id); deleting = false; onGone() } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep it") } },
        )
    }
}

private fun reorder(scope: kotlinx.coroutines.CoroutineScope, c: com.music.connect.Container, id: String, order: List<String>, from: Int, to: Int) {
    val next = order.toMutableList().apply { add(to, removeAt(from)) }
    scope.launch {
        try {
            c.library.reorder(id, next)
        } catch (e: Exception) {
            c.tell(if (Library.isOffline(e)) "Reordering needs a connection" else e.message ?: "Couldn't reorder")
        }
    }
}

@Composable
fun DownloadsScreen() {
    val c = appContainer()
    val player = LocalPlayer.current
    val downloads by c.downloads.all.collectAsState(initial = emptyList())
    val playing by player.state.collectAsState()
    val scope = rememberCoroutineScope()
    val done = downloads.filter { it.done }
    val waiting = downloads.filter { !it.done }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Downloads", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${done.size} songs · ${"%.1f".format(done.sumOf { it.bytes } / 1_048_576.0)} MB · they play with no connection at all",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { player.play(done.map { it.track }) }, enabled = done.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Text("Play all") }
                    FilledTonalButton(onClick = { player.playShuffled(done.map { it.track }) }, enabled = done.size > 1) { Icon(Icons.Rounded.Shuffle, null); Text("Shuffle") }
                }
            }
        }
        if (downloads.isEmpty()) item { Empty("Nothing downloaded yet", "Download songs from their ⋮ menu, or keep a whole playlist offline.", Icons.Rounded.CloudOff) }
        if (waiting.isNotEmpty()) {
            item { SectionTitle("Waiting (${waiting.size})") }
            items(waiting, key = { "w-${it.track.key}" }) { d ->
                TrackRow(d.track, onClick = {}, extraActions = listOf("Cancel" to { scope.launch { c.downloads.remove(d.track) }; Unit }))
                d.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 76.dp, bottom = 4.dp)) }
            }
        }
        if (done.isNotEmpty()) item { SectionTitle("On this phone") }
        itemsIndexed(done, key = { _, d -> "d-${d.track.key}" }) { i, d ->
            TrackRow(d.track, onClick = { player.play(done.map { it.track }, i) }, current = playing.track?.key == d.track.key, downloaded = true)
        }
    }
}
