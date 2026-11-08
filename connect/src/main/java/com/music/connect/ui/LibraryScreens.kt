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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.music.connect.net.SearchGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SearchScreen() {
    val c = appContainer()
    val player = LocalPlayer.current
    val online by c.online.collectAsState()
    val downloaded by c.downloads.doneKeys.collectAsState(initial = emptySet())
    val playing by player.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<SearchGroup>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, online) {
        val q = query.trim()
        if (q.isEmpty()) { groups = null; return@LaunchedEffect }
        delay(450)
        busy = true
        error = null
        groups = if (online) {
            try {
                c.api.search(q).groups.sortedByDescending { it.kind == "JELLYFIN" }
            } catch (e: Exception) {
                error = e.message
                null
            }
        } else {
            // Offline: what's downloaded.
            val words = q.lowercase().split(' ').filter { it.isNotBlank() }
            val matches = c.downloads.all.first().filter { it.done }.map { it.track }.filter { t -> words.all { "${t.title} ${t.artist} ${t.album.orEmpty()}".lowercase().contains(it) } }
            listOf(SearchGroup("downloads", "Downloaded", "DOWNLOADS", matches))
        }
        busy = false
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (busy) CircularProgressIndicator(Modifier.padding(12.dp)) },
            placeholder = { Text(if (online) "Songs, artists, albums" else "Search your downloads") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(Modifier.fillMaxSize()) {
            if (groups == null && query.isBlank()) {
                item { Empty("Search everything at once", "Your library, and every service on your server.", Icons.Rounded.Search) }
            }
            groups?.forEach { group ->
                item(key = "h-${group.sourceId}") { SectionTitle(group.sourceName) }
                group.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) } }
                if (group.tracks.isEmpty() && group.error == null) item { Text("Nothing found here", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
                itemsIndexed(group.tracks, key = { i, t -> "${group.sourceId}-$i-${t.key}" }) { i, track ->
                    TrackRow(track, onClick = { player.play(group.tracks, i) }, current = playing.track?.key == track.key, downloaded = track.key in downloaded)
                }
            }
        }
    }
}

@Composable
fun PlaylistsScreen(onOpen: (String) -> Unit) {
    val c = appContainer()
    val playlists by c.library.all.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New playlist") })
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (playlists.isEmpty()) item { Empty("No playlists yet", "Make one here, or on the web player; they're kept on your phone too.", Icons.AutoMirrored.Rounded.QueueMusic) }
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
