package com.music.player.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.music.player.ui.LocalAppContainer
import com.music.player.ui.LocalTrackActions
import com.music.player.ui.components.Artwork
import com.music.player.ui.components.NetworkStatusRow
import com.music.player.ui.components.SourceBadge
import com.music.player.ui.components.formatTime
import com.music.player.ui.radio.NowPlayingCard

@Composable
fun MiniPlayer(onExpand: () -> Unit) {
    val container = LocalAppContainer.current
    val state by container.player.state.collectAsStateWithLifecycle()
    val track = state.current ?: return
    Surface(tonalElevation = 3.dp) {
        Column {
            val duration = state.durationMs
            BufferedProgress(
                played = if (duration > 0) state.positionMs.toFloat() / duration else 0f,
                buffered = if (duration > 0) state.bufferedPositionMs.toFloat() / duration else 0f,
                modifier = Modifier.fillMaxWidth().height(3.dp),
                rounded = false,
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(track.artworkUrl, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        track.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = container.player::togglePlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = container.player::next) { Icon(Icons.Default.SkipNext, contentDescription = "Next") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val actions = LocalTrackActions.current
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    val origins by container.resolver.origins.collectAsStateWithLifecycle()
    val downloaded by container.downloads.completedKeys.collectAsStateWithLifecycle(emptySet())
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val track = state.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (track == null) {
            Text("Nothing playing", modifier = Modifier.padding(24.dp))
            return@ModalBottomSheet
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Artwork(track.artworkUrl, 280.dp, modifier = Modifier.aspectRatio(1f), corner = 12.dp)
                    Text(track.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(
                        listOfNotNull(track.artist, track.album).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val origin = origins[track.key]
                        origin?.source?.let { SourceBadge(it) }
                        Text(
                            origin?.description ?: "Finding the best source…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NetworkStatusRow()
                    if (!track.isLive) {
                        SeekBar(state.positionMs, state.bufferedPositionMs, state.durationMs, player::seekTo)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = player::toggleShuffle) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = player::previous) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(32.dp))
                        }
                        FilledIconButton(
                            onClick = player::togglePlayPause,
                            modifier = Modifier.size(64.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(),
                        ) {
                            Icon(
                                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        IconButton(onClick = player::next) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = player::cycleRepeat) {
                            Icon(
                                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Repeat",
                                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (track.isSiriusXmChannel) NowPlayingCard()
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (!track.isLive) {
                            IconButton(onClick = { actions.addToPlaylist(listOf(track)) }) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to playlist")
                            }
                            if (track.key in downloaded) {
                                IconButton(onClick = { actions.removeDownload(track) }) {
                                    Icon(Icons.Default.DownloadDone, contentDescription = "Remove download", tint = MaterialTheme.colorScheme.primary)
                                }
                            } else {
                                IconButton(onClick = { actions.download(listOf(track)) }) {
                                    Icon(Icons.Default.Download, contentDescription = "Download")
                                }
                            }
                        }
                        IconButton(onClick = { actions.info(track) }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Info")
                        }
                    }
                    Text(
                        "Up next",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
            itemsIndexed(state.queue, key = { index, item -> "$index-${item.key}" }) { index, item ->
                ListItem(
                    modifier = Modifier.clickable { player.skipTo(index) },
                    colors = if (index == state.currentIndex) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        ListItemDefaults.colors()
                    },
                    leadingContent = { Artwork(item.artworkUrl, 40.dp) },
                    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        if (index != state.currentIndex) {
                            IconButton(onClick = { player.removeFromQueue(index) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove from queue")
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(positionMs: Long, bufferedMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    val fraction = dragging ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * duration).toLong()) }
                dragging = null
            },
            enabled = durationMs > 0,
            track = { sliderState ->
                BufferedProgress(
                    played = sliderState.value,
                    buffered = bufferedMs.toFloat() / duration,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime((fraction * duration).toLong()), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A progress bar that also shows, lighter, how far ahead the song has loaded. */
@Composable
private fun BufferedProgress(played: Float, buffered: Float, modifier: Modifier = Modifier, rounded: Boolean = true) {
    val playedColor = MaterialTheme.colorScheme.primary
    val bufferedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val emptyColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        val corner = if (rounded) CornerRadius(size.height / 2) else CornerRadius.Zero
        fun bar(fraction: Float, color: Color) {
            val width = size.width * fraction.coerceIn(0f, 1f)
            if (width > 0f) drawRoundRect(color, size = Size(width, size.height), cornerRadius = corner)
        }
        bar(1f, emptyColor)
        bar(maxOf(buffered, played), bufferedColor)
        bar(played, playedColor)
    }
}
