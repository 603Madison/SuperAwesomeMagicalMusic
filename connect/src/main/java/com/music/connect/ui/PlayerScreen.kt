package com.music.connect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.music.connect.net.LyricsView
import com.music.connect.playback.MediaItems
import com.music.connect.ui.theme.SiteRose
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val c = appContainer()
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val live by c.liveNow.collectAsState()
    val descriptions by c.playingDescription.collectAsState()
    val downloaded by c.downloads.doneKeys.collectAsState(initial = emptySet())
    val favorites by c.favorites.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var addTo by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var thumbs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val track = state.track ?: run {
        Empty("Nothing playing")
        return
    }
    val nowSong = if (state.live) live?.song else track.takeIf { it.isSong || it.isStationTrack }
    val title = if (state.live && live != null) live!!.title else track.title
    val artist = if (state.live && live != null) live!!.artist else track.artist
    val artwork = if (state.live) live?.artworkUrl ?: track.artworkUrl else track.artworkUrl

    LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
                Spacer(Modifier.weight(1f))
                // The channel or station playing, or the Pandora station this song is from.
                c.favoriteOf(track)?.let { target ->
                    val favorite = favorites.any { it.key == target.key }
                    IconButton(onClick = {
                        c.setFavorite(target, !favorite)
                        c.tell(if (favorite) "Removed ${target.title} from your favourites" else "${target.title} is in your favourites, on the Radio tab")
                    }) {
                        Icon(
                            if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (favorite) "Remove ${target.title} from favourites" else "Add ${target.title} to favourites",
                            tint = if (favorite) SiteRose else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (nowSong != null) IconButton(onClick = { addTo = true }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") }
                DevicesButton()
                SleepTimerButton()
                IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, "Details") }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 12.dp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Artwork(artwork, 300.dp, Modifier.aspectRatio(1f), corner = 28.dp)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(artist, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                when {
                    state.live -> Pill("Live · ${track.title}", MaterialTheme.colorScheme.tertiary)
                    track.key in downloaded -> Pill("Downloaded", MaterialTheme.colorScheme.primary, icon = Icons.Rounded.DownloadDone)
                    else -> descriptions[track.key]?.let { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item {
            if (!state.live) Progress(state.positionMs, state.durationMs) { player.seek(it) }
            else Spacer(Modifier.height(24.dp))
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (track.isStationTrack && track.via != null) {
                    val (stationId, trackId) = MediaItems.split(track.sourceId.removePrefix("stationtrack:"))
                    fun thumb(direction: String) {
                        val next = if (thumbs[trackId] == direction) "NONE" else direction
                        thumbs = thumbs + (trackId to next)
                        scope.launch {
                            runCatching { c.api.stationThumb(track.via, stationId, trackId, next, state.positionMs) }
                                .onSuccess { if (next == "DOWN") player.next() }
                                .onFailure { c.tell(it.message ?: "SiriusXM didn't take the thumb") }
                        }
                    }
                    IconButton(onClick = { thumb("DOWN") }) { Icon(Icons.Rounded.ThumbDown, "Thumbs down", tint = if (thumbs[trackId] == "DOWN") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                } else if (!state.live) {
                    IconButton(onClick = { player.toggleShuffle() }) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton(onClick = { player.previous() }, enabled = !state.live) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(32.dp)) }
                FilledIconButton(onClick = { player.toggle() }, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors()) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(40.dp))
                }
                IconButton(onClick = { player.next() }, enabled = !state.live) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(32.dp)) }
                if (track.isStationTrack && track.via != null) {
                    val (stationId, trackId) = MediaItems.split(track.sourceId.removePrefix("stationtrack:"))
                    IconButton(onClick = {
                        val next = if (thumbs[trackId] == "UP") "NONE" else "UP"
                        thumbs = thumbs + (trackId to next)
                        scope.launch { runCatching { c.api.stationThumb(track.via, stationId, trackId, next, state.positionMs) }.onFailure { c.tell(it.message ?: "SiriusXM didn't take the thumb") } }
                    }) { Icon(Icons.Rounded.ThumbUp, "Thumbs up", tint = if (thumbs[trackId] == "UP") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                } else if (!state.live) {
                    IconButton(onClick = { player.cycleRepeat() }) {
                        Icon(
                            if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                            tint = if (state.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)) {
                listOf("Up next", "Lyrics").forEachIndexed { i, label ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(index = i, count = 2), icon = {}) { Text(label) }
                }
            }
        }
        if (tab == 0) {
            val upcoming = state.queue.size - state.index.coerceAtLeast(0)
            itemsIndexed(state.queue, key = { i, t -> "$i-${t.key}" }) { i, queued ->
                if (i < state.index) return@itemsIndexed
                CardItem(i - state.index.coerceAtLeast(0), upcoming) {
                    TrackRow(queued, onClick = { player.jumpTo(i) }, current = i == state.index, downloaded = queued.key in downloaded, extraActions = listOf("Remove from queue" to { player.remove(i); Unit }))
                }
            }
        } else {
            item { AppCard(Modifier.padding(horizontal = 16.dp), padding = 0.dp) { Lyrics(nowSong, state.positionMs) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (addTo && nowSong != null) AddToPlaylistDialog(listOf(nowSong)) { addTo = false }
    if (info) TrackInfoDialog(track) { info = false }
}

@Composable
internal fun Progress(positionMs: Long, durationMs: Long, accent: androidx.compose.ui.graphics.Color? = null, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek((it * durationMs).toLong()) }; dragging = null },
            enabled = durationMs > 0,
            colors = accent?.let { SliderDefaults.colors(thumbColor = it, activeTrackColor = it, inactiveTrackColor = it.copy(alpha = 0.24f)) } ?: SliderDefaults.colors(),
        )
        Row {
            Text(duration((fraction * durationMs).toLong()), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(duration(durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Lyrics(track: com.music.connect.net.ApiTrack?, positionMs: Long) {
    val c = appContainer()
    var lyrics by remember(track?.key) { mutableStateOf<LyricsView?>(null) }
    var loading by remember(track?.key) { mutableStateOf(true) }
    LaunchedEffect(track?.key) {
        loading = true
        lyrics = track?.let { runCatching { c.api.lyrics(it) }.getOrNull() }
        loading = false
    }
    val lines = lyrics?.lines.orEmpty()
    val online by c.online.collectAsState()
    when {
        track == null -> Empty("No lyrics for live radio", "When a channel says which song is on, its lyrics show here.")
        loading -> Text("Looking for lyrics…", Modifier.padding(24.dp))
        lines.isEmpty() -> Empty("No lyrics found", if (online) null else "Lyrics need a connection.")
        else -> {
            val current = if (lyrics?.synced == true) lines.indexOfLast { it.timeMs != null && it.timeMs <= positionMs } else -1
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lines.forEachIndexed { i, line ->
                    Text(
                        line.text.ifBlank { "♪" },
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            current < 0 -> MaterialTheme.colorScheme.onSurface
                            i == current -> MaterialTheme.colorScheme.primary
                            i < current -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                    )
                }
                Text("Lyrics from ${lyrics?.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Stops playback after a while (fading out over the last few seconds). */
@Composable
private fun SleepTimerButton() {
    val player = LocalPlayer.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var left by remember { mutableFloatStateOf(0f) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Rounded.Bedtime, "Sleep timer", tint = if (job?.isActive == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DropdownMenu(open, onDismissRequest = { open = false }) {
        listOf(15, 30, 45, 60, 90).forEach { minutes ->
            DropdownMenuItem(text = { Text("In $minutes minutes") }, onClick = {
                open = false
                job?.cancel()
                job = scope.launch {
                    left = minutes.toFloat()
                    delay(minutes * 60_000L)
                    if (player.state.value.playing) player.toggle()
                }
            })
        }
        if (job?.isActive == true) DropdownMenuItem(text = { Text("Turn off the sleep timer") }, onClick = { open = false; job?.cancel(); job = null })
    }
}
