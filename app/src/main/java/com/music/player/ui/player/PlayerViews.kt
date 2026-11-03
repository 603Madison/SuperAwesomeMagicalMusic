package com.music.player.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import com.music.player.ui.components.contentColorOn
import com.music.player.ui.components.rememberArtworkColor
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
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
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
import com.music.player.sources.siriusxm.SxmCut
import com.music.player.sources.siriusxm.SxmStationTrack
import com.music.player.sources.siriusxm.SxmThumb
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
    val accent by animateColorAsState(rememberArtworkColor(track.artworkUrl) ?: MaterialTheme.colorScheme.primary, label = "mini-accent")
    // A floating card, tinted toward the artwork.
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, accent, 0.18f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.clickable(onClick = onExpand)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(track.artworkUrl, 48.dp, corner = 12.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        track.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(
                    onClick = container.player::togglePlayPause,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = contentColorOn(accent)),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = container.player::next) { Icon(Icons.Default.SkipNext, contentDescription = "Next") }
            }
            val duration = state.durationMs
            BufferedProgress(
                played = if (duration > 0) state.positionMs.toFloat() / duration else 0f,
                buffered = if (duration > 0) state.bufferedPositionMs.toFloat() / duration else 0f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 8.dp).height(3.dp),
                color = accent,
            )
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
    val thumbs by container.siriusXm.thumbs.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val track = state.current

    val accent by animateColorAsState(rememberArtworkColor(track?.artworkUrl) ?: MaterialTheme.colorScheme.primary, label = "sheet-accent")

    // The artwork's colour fills the top of the sheet (handle included) and fades into the page below.
    val surface = MaterialTheme.colorScheme.surface
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = lerp(surface, accent, 0.3f), contentColor = MaterialTheme.colorScheme.onSurface) {
        if (track == null) {
            Text("Nothing playing", modifier = Modifier.padding(24.dp))
            return@ModalBottomSheet
        }
        LazyColumn(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.3f to surface.copy(alpha = 0.35f), 0.75f to surface),
            ),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                        val side = minOf(maxWidth, 340.dp)
                        Artwork(
                            track.artworkUrl,
                            side,
                            corner = 24.dp,
                            modifier = Modifier.shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = accent, spotColor = accent),
                        )
                    }
                    Text(
                        track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = lerp(MaterialTheme.colorScheme.onSurface, accent, 0.45f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    track.album?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val origin = origins[track.key]
                            origin?.source?.let { SourceBadge(it) }
                            Text(
                                origin?.description ?: "Finding the best source…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    NetworkStatusRow()
                    if (!track.isLive) {
                        SeekBar(state.positionMs, state.bufferedPositionMs, state.durationMs, player::seekTo, accent)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(onClick = player::toggleShuffle) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (state.shuffle) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                        }
                        FilledIconButton(
                            onClick = player::togglePlayPause,
                            modifier = Modifier.size(76.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = contentColorOn(accent)),
                        ) {
                            Icon(
                                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                        }
                        IconButton(onClick = player::cycleRepeat) {
                            Icon(
                                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Repeat",
                                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) accent
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (track.isSiriusXmChannel) NowPlayingCard()
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        SxmStationTrack.idOf(track)?.let { (_, trackId) ->
                            val thumb = thumbs[trackId]
                            IconButton(onClick = { actions.rate(track, SxmThumb.DOWN) }) {
                                Icon(
                                    if (thumb == SxmThumb.DOWN) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                                    contentDescription = if (thumb == SxmThumb.DOWN) "Remove thumbs down" else "Thumbs down",
                                    tint = if (thumb == SxmThumb.DOWN) accent else LocalContentColor.current,
                                )
                            }
                            IconButton(onClick = { actions.rate(track, SxmThumb.UP) }) {
                                Icon(
                                    if (thumb == SxmThumb.UP) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                                    contentDescription = if (thumb == SxmThumb.UP) "Remove thumbs up" else "Thumbs up",
                                    tint = if (thumb == SxmThumb.UP) accent else LocalContentColor.current,
                                )
                            }
                        }
                        SxmStationTrack.idOf(track)?.let { (_, trackId) ->
                            // Station tracks' links expire, so save the song itself (Jellyfin first, as usual).
                            IconButton(onClick = {
                                actions.saveHeardSong(
                                    SxmCut(trackId, track.title, track.artist, track.album, 0, track.durationMs ?: 0, track.artworkUrl, interstitial = false),
                                )
                            }) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Save song")
                            }
                        }
                        IconButton(onClick = { actions.info(track) }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Info")
                        }
                    }
                    }
                    Text(
                        "Up next",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                    )
                }
            }
            itemsIndexed(state.queue, key = { index, item -> "$index-${item.key}" }) { index, item ->
                ListItem(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { player.skipTo(index) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (index == state.currentIndex) accent.copy(alpha = 0.16f) else Color.Transparent,
                    ),
                    leadingContent = { Artwork(item.artworkUrl, 44.dp, corner = 10.dp) },
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
private fun SeekBar(positionMs: Long, bufferedMs: Long, durationMs: Long, onSeek: (Long) -> Unit, color: Color) {
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
            colors = SliderDefaults.colors(thumbColor = color),
            track = { sliderState ->
                BufferedProgress(
                    played = sliderState.value,
                    buffered = bufferedMs.toFloat() / duration,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = color,
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
private fun BufferedProgress(
    played: Float,
    buffered: Float,
    modifier: Modifier = Modifier,
    rounded: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val playedColor = color
    val bufferedColor = color.copy(alpha = 0.35f)
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
