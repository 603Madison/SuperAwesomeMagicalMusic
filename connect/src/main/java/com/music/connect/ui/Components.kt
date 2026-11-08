package com.music.connect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.music.connect.Container
import com.music.connect.container
import com.music.connect.data.Library
import com.music.connect.net.ApiTrack
import com.music.connect.playback.MediaItems
import com.music.connect.playback.PlayerConnection
import com.music.player.quality.NetworkClass
import kotlinx.coroutines.launch

val LocalPlayer = compositionLocalOf<PlayerConnection> { error("No player") }

@Composable
fun appContainer(): Container = LocalContext.current.container

/** Artwork through the server, smaller on satellite. */
@Composable
fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    val c = appContainer()
    val network by c.network.state.collectAsState()
    val px = with(LocalDensity.current) { size.roundToPx() }.let { if (network.networkClass == NetworkClass.SATELLITE) minOf(it, 128) else it }
    val server = c.settings.collectAsState().value.server
    val uri = MediaItems.artworkUri(server, url, px)
    val shape = RoundedCornerShape(corner)
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (uri == null) {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.4f))
        } else {
            SubcomposeAsyncImage(
                model = uri.toString(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                error = { Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.4f)) },
            )
        }
    }
}

@Composable
fun TrackRow(
    track: ApiTrack,
    onClick: () -> Unit,
    current: Boolean = false,
    downloaded: Boolean = false,
    available: Boolean = true,
    extraActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artworkUrl, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    current -> MaterialTheme.colorScheme.primary
                    !available -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                listOfNotNull(track.artist, track.album).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (available) 1f else 0.5f),
            )
        }
        if (downloaded) Icon(Icons.Rounded.DownloadDone, "Downloaded", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        track.durationMs?.takeIf { it > 0 }?.let {
            Text(duration(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
        }
        TrackMenu(track, downloaded, extraActions)
    }
}

@Composable
fun TrackMenu(track: ApiTrack, downloaded: Boolean, extraActions: List<Pair<String, () -> Unit>> = emptyList()) {
    val c = appContainer()
    val player = LocalPlayer.current
    val scope = rememberCoroutineScope()
    val browse = LocalBrowse.current
    val online by c.online.collectAsState()
    var open by remember { mutableStateOf(false) }
    var addTo by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuRow("Play next", Icons.Rounded.PlaylistPlay) { open = false; player.playNext(listOf(track)) }
            MenuRow("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { open = false; player.addToQueue(listOf(track)) }
            if (track.isSong || track.isStationTrack) {
                MenuRow("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { open = false; addTo = true }
            }
            if (track.isSong) {
                if (downloaded) {
                    MenuRow("Remove download", Icons.Rounded.DeleteOutline) { open = false; scope.launch { c.downloads.remove(track) } }
                } else {
                    MenuRow("Download", Icons.Rounded.Download) {
                        open = false
                        scope.launch { c.downloads.queue(listOf(track)); c.tell("Downloading \"${track.title}\"") }
                    }
                }
            }
            if (browse != null && online && (track.isSong || track.isStationTrack) && track.artist.isNotBlank()) {
                MenuRow("Go to artist", Icons.Rounded.Person) { open = false; browse.artist(null, name = track.artist) }
                track.album?.takeIf { it.isNotBlank() }?.let { album ->
                    MenuRow("Go to album", Icons.Rounded.Album) { open = false; browse.album(null, title = album, artist = track.artist) }
                }
            }
            extraActions.forEach { (label, action) -> MenuRow(label, null) { open = false; action() } }
        }
    }
    if (addTo) AddToPlaylistDialog(listOf(track)) { addTo = false }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector?, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = icon?.let { { Icon(it, null) } }, onClick = onClick)
}

@Composable
fun AddToPlaylistDialog(tracks: List<ApiTrack>, onDone: () -> Unit) {
    val c = appContainer()
    val playlists by c.library.all.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (creating) "New playlist" else "Add to playlist") },
        text = {
            if (creating) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            } else {
                LazyColumn {
                    item {
                        Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(Modifier.width(12.dp))
                            Text("New playlist…")
                        }
                    }
                    items(playlists, key = { it.id }) { p ->
                        Text(
                            p.name,
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    c.library.add(p.id, tracks)
                                    c.tell(if (tracks.size == 1) "Added to ${p.name}" else "Added ${tracks.size} songs to ${p.name}")
                                    onDone()
                                }
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (creating) TextButton(onClick = {
                scope.launch {
                    c.library.create(name.ifBlank { "New playlist" }, tracks.filter { it.isSong || it.isStationTrack }.map(Library::asSong))
                    c.tell("Made \"${name.ifBlank { "New playlist" }}\"")
                    onDone()
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
fun Empty(title: String, body: String? = null, icon: ImageVector? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { Icon(it, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) }
        Text(title, style = MaterialTheme.typography.titleMedium)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp))
}

/** Says when the app is offline or on satellite, and what that means. */
@Composable
fun NetworkBanner() {
    val c = appContainer()
    val network by c.network.state.collectAsState()
    val pending by c.library.pendingCount.collectAsState(initial = 0)
    val (icon, text) = when {
        !network.isOnline -> Icons.Rounded.CloudOff to ("Offline: your downloads and playlists still work" + if (pending > 0) " · $pending changes to send" else "")
        network.networkClass == NetworkClass.SATELLITE -> Icons.Rounded.SatelliteAlt to "On satellite: lower quality, loading well ahead"
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun duration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

val qualitySteps = listOf(0, 320, 256, 192, 160, 128, 96, 64)

fun qualityLabel(kbps: Int) = if (kbps == 0) "Original quality" else "Up to $kbps kbps"
