package com.music.connect.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.connect.data.Library
import com.music.connect.net.ImportResult
import com.music.player.playlists.PlaylistCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh(byHand = true) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ScreenTitle("Playlists", if (playlists.isEmpty()) null else "${playlists.size} on this phone") {
                    IconButton(onClick = { importing = true }) { Icon(Icons.Rounded.FileDownload, "Import a playlist") }
                    FilledTonalIconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "New playlist") }
                }
            }
            if (playlists.isEmpty()) {
                item {
                    Empty(
                        "No playlists yet",
                        "Make one here or on the web player, or import one from Spotify, Apple Music, Tidal, Deezer and more; they're kept on your phone too.",
                        Icons.AutoMirrored.Rounded.QueueMusic,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { creating = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New playlist") }
                            OutlinedButton(onClick = { importing = true }) { Text("Import") }
                        }
                    }
                }
            } else {
                item { Spacer(Modifier.height(8.dp)) }
            }
            itemsIndexed(playlists, key = { _, p -> p.id }) { i, p ->
                CardItem(i, playlists.size) {
                    Row(Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(p.artwork.firstOrNull(), 56.dp, corner = 14.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.count} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (p.keepOffline) Pill("Offline", MaterialTheme.colorScheme.primary, icon = Icons.Rounded.DownloadForOffline)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // A CSV or text file from the phone (Downloads, Drive…): its songs fill the list, its name the name.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val label = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes(MAX_IMPORT_FILE + 1) } ?: error("Couldn't open the file")
                    if (bytes.size > MAX_IMPORT_FILE) error("That file is too big (1 MB at most)")
                    label to String(bytes, Charsets.UTF_8)
                }
            }.onSuccess { (label, content) ->
                text = content
                fileName = label ?: "the file"
                if (name.isBlank()) label?.let { name = it.substringBeforeLast('.').replace('_', ' ').trim() }
            }.onFailure { c.tell(it.message ?: "Couldn't read the file") }
        }
    }
    val ready = online && !busy && (if (fromList) text.isNotBlank() else url.isNotBlank())

    report?.let { result ->
        val open = { onClose(); onImported(result.playlist.summary.id) }
        AlertDialog(
            onDismissRequest = open,
            title = { Text("Imported ${result.found} songs") },
            text = {
                LazyColumn {
                    result.note?.let { item { Text("$it.", Modifier.padding(bottom = 8.dp)) } }
                    if (result.missing.isNotEmpty()) item { Text("These weren't found in your sources. They're in the playlist anyway, greyed out, and are looked for again every few hours:", Modifier.padding(bottom = 4.dp)) }
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
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }) {
                        Icon(Icons.Rounded.FileOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(fileName?.let { "From $it" } ?: "Choose a file (CSV or text)")
                    }
                    Text("One a line, like “Artist - Title”, or a CSV export with title and artist columns: choose the file, or paste it (for Qobuz and other services, export one with TuneMyMusic or Soundiiz). Songs that aren't found go in greyed out, and are looked for again now and then.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val context = LocalContext.current
    // Saved wherever the person picks (Downloads, Drive…), from the playlist as kept on the phone.
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PlaylistCsv.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val rows = entries.map { PlaylistCsv.Row(it.track.title, it.track.artist, it.track.album, it.track.durationMs, it.track.isrc) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(PlaylistCsv.write(rows).toByteArray()) } ?: error("Couldn't open the file")
                }
            }.onSuccess { c.tell("Exported ${rows.size} songs") }.onFailure { c.tell("Couldn't export: ${it.message}") }
        }
    }
    val playlist = info ?: run {
        Empty("This playlist isn't here", "It may have been deleted on another device.")
        return
    }
    // Offline, only downloaded songs can play; songs that weren't found never do.
    val playable = entries.map { it.track }.filter { !it.isMissing && (online || it.key in downloaded) }
    val missing = entries.count { it.track.isMissing }
    var finding by remember { mutableStateOf(false) }
    fun played() {
        scope.launch { runCatching { c.api.playlistPlayed(id) } }
    }

    var menu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BackBar(LocalBack.current) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(
                            text = { Text("Export CSV") },
                            leadingIcon = { Icon(Icons.Rounded.FileUpload, null) },
                            enabled = entries.isNotEmpty(),
                            onClick = { menu = false; export.launch(PlaylistCsv.fileName(playlist.name)) },
                        )
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; deleting = true })
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(entries.firstOrNull { !it.track.isMissing }?.track?.artworkUrl, 96.dp, corner = 20.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${entries.size} songs" + if (!online) " · ${playable.size} downloaded" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { player.play(playable); played() }, enabled = playable.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Text("Play") }
                FilledTonalButton(onClick = { player.playShuffled(playable); played() }, enabled = playable.size > 1) { Icon(Icons.Rounded.Shuffle, null); Text("Shuffle") }
            }
        }
        item {
            AppCard(Modifier.padding(horizontal = 16.dp)) {
              Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                if (missing > 0) {
                    Text(
                        if (missing == 1) "1 song wasn't found in your sources: it's greyed out and skipped, and looked for again every few hours." else "$missing songs weren't found in your sources: they're greyed out and skipped, and looked for again every few hours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        enabled = online && !finding,
                        onClick = {
                            finding = true
                            scope.launch {
                                runCatching { c.api.findMissing(id) }
                                    .onSuccess { found ->
                                        c.library.sync()
                                        c.tell(if (found.found > 0) "Found ${found.found}" + (if (found.stillMissing > 0) "; ${found.stillMissing} still missing" else "") else "Still not found anywhere")
                                    }
                                    .onFailure { c.tell(it.message ?: "Couldn't look for them") }
                                finding = false
                            }
                        },
                    ) { Text(if (finding) "Looking…" else "Look for them again") }
                }
              }
            }
        }
        if (entries.isEmpty()) item { Empty("This playlist is empty", "Add songs from search with their ⋮ menu.") }
        else item { SectionTitle("Songs") }
        itemsIndexed(entries, key = { _, e -> e.entryId }) { i, entry ->
            val available = !entry.track.isMissing && (online || entry.track.key in downloaded)
            CardItem(i, entries.size) { TrackRow(
                entry.track,
                onClick = {
                    when {
                        entry.track.isMissing -> c.tell("Not found in your sources yet; it's looked for again every few hours")
                        available -> {
                            player.play(playable, playable.indexOfFirst { it.key == entry.track.key }.coerceAtLeast(0))
                            played()
                        }
                        else -> c.tell("Offline, and this song isn't downloaded")
                    }
                },
                current = playing.track?.key == entry.track.key,
                downloaded = entry.track.key in downloaded,
                available = available,
                extraActions = buildList {
                    add("Remove from playlist" to { scope.launch { c.library.remove(id, listOf(entry.entryId)) }; Unit })
                    if (i > 0) add("Move up" to { reorder(scope, c, id, entries.map { it.entryId }, i, i - 1) })
                    if (i < entries.size - 1) add("Move down" to { reorder(scope, c, id, entries.map { it.entryId }, i, i + 1) })
                },
            ) }
        }
        item { Spacer(Modifier.height(24.dp)) }
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
            ScreenTitle(
                "Downloads",
                if (done.isEmpty()) null else "${done.size} songs · ${"%.1f".format(done.sumOf { it.bytes } / 1_048_576.0)} MB · play with no connection",
            )
        }
        if (done.isNotEmpty()) item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { player.play(done.map { it.track }) }) { Icon(Icons.Rounded.PlayArrow, null); Text("Play all") }
                FilledTonalButton(onClick = { player.playShuffled(done.map { it.track }) }, enabled = done.size > 1) { Icon(Icons.Rounded.Shuffle, null); Text("Shuffle") }
            }
        }
        if (downloads.isEmpty()) item { Empty("Nothing downloaded yet", "Download songs from their ⋮ menu, or keep a whole playlist offline.", Icons.Rounded.CloudOff) }
        if (waiting.isNotEmpty()) {
            item { SectionTitle("Waiting (${waiting.size})") }
            itemsIndexed(waiting, key = { _, d -> "w-${d.track.key}" }) { i, d ->
                CardItem(i, waiting.size) {
                    Column {
                        TrackRow(d.track, onClick = {}, extraActions = listOf("Cancel" to { scope.launch { c.downloads.remove(d.track) }; Unit }))
                        d.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 72.dp, end = 12.dp, bottom = 6.dp)) }
                    }
                }
            }
        }
        if (done.isNotEmpty()) item { SectionTitle("On this phone") }
        itemsIndexed(done, key = { _, d -> "d-${d.track.key}" }) { i, d ->
            CardItem(i, done.size) {
                TrackRow(d.track, onClick = { player.play(done.map { it.track }, i) }, current = playing.track?.key == d.track.key, downloaded = true)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private const val MAX_IMPORT_FILE = 1_000_000

/** Reads up to [limit] bytes. */
private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
