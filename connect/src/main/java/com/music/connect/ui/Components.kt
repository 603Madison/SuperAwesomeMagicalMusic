package com.music.connect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import com.music.connect.ui.theme.LocalBackdrop
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
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artworkUrl, 48.dp, corner = 12.dp)
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

/** A friendly, centred placeholder for empty lists and problems, as in the other apps. */
@Composable
fun Empty(title: String, body: String? = null, icon: ImageVector? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon ?: Icons.Rounded.MusicNote, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        body?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        action?.let {
            Spacer(Modifier.height(16.dp))
            it()
        }
    }
}

/** A small heading above a card, as in the other apps' settings and lists. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** Everything behind the screens: the theme's gentle gradient. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalBackdrop.current
    Box(
        modifier.fillMaxSize().drawBehind {
            drawRect(colors.base)
            // Two soft glows of the theme at the top, like an aurora.
            drawRect(Brush.radialGradient(listOf(colors.glowA, Color.Transparent), center = Offset(size.width * 0.1f, -size.height * 0.04f), radius = size.width * 1.05f))
            drawRect(Brush.radialGradient(listOf(colors.glowB, Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.02f), radius = size.width * 0.85f))
        },
    ) { content() }
}

/**
 * A tab's title, large and left-aligned over the backdrop, with its actions on the right: as the
 * other apps head their screens.
 */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        actions()
    }
}

/** A page opened from a tab (an artist, an album, a playlist): back on the left, actions on the right. */
@Composable
fun BackBar(onBack: (() -> Unit)?, title: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(title.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
        actions()
    }
}

/** The app's one card style, as in the other apps: 20dp corners on surfaceContainer. */
@Composable
fun AppCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val shape = RoundedCornerShape(CARD_CORNER)
    val body: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick != null) Card(onClick = onClick, shape = shape, colors = colors, modifier = modifier.fillMaxWidth(), content = body)
    else Card(shape = shape, colors = colors, modifier = modifier.fillMaxWidth(), content = body)
}

/** A heading above a card of rows, spaced like the other apps' settings. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        AppCard { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
    }
}

/**
 * One row of a card in a list: rows of a group sit together in one rounded card (the first
 * rounds its top, the last its bottom), so long lists stay lazy.
 */
@Composable
fun CardItem(index: Int, count: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val top = if (index == 0) CARD_CORNER else 0.dp
    val bottom = if (index == count - 1) CARD_CORNER else 0.dp
    Box(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(top = if (index == 0) 6.dp else 0.dp, bottom = if (index == count - 1) 6.dp else 0.dp),
    ) { content() }
}

/** A small rounded label, e.g. "Live" or "Downloaded". */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(color = color.copy(alpha = 0.14f), contentColor = color, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A rounded search field on a card, for searches and filters. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onSearch: (() -> Unit)? = null,
) {
    TextField(
        value, onValueChange,
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            when {
                busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                value.isNotEmpty() -> IconButton(onClick = { onValueChange("") }) { Icon(Icons.Rounded.Close, "Clear") }
            }
        },
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Corners of the app's cards, as in the other apps. */
val CARD_CORNER = 20.dp

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
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
