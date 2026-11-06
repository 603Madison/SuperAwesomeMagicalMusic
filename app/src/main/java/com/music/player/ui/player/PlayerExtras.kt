package com.music.player.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.lyrics.Lyrics
import com.music.player.model.Track
import com.music.player.playback.SessionButtons
import com.music.player.playback.SleepTimer
import com.music.player.playback.SleepTimerState
import com.music.player.sources.siriusxm.SiriusXmClient
import com.music.player.sources.siriusxm.SxmChannel
import com.music.player.ui.LocalAppContainer
import kotlinx.coroutines.delay

/** On a live SiriusXM channel: back to the start of the song, back 15 seconds, or back to live. */
@Composable
fun LiveControls(track: Track) {
    if (SxmChannel.idOf(track)?.first != SiriusXmClient.LINEAR) return
    val player = LocalAppContainer.current.player
    val state by player.state.collectAsStateWithLifecycle()
    val behind = (state.liveOffsetMs ?: 0) > 60_000
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = { player.send(SessionButtons.RESTART_SONG) },
            label = { Text("Restart song") },
            leadingIcon = { Icon(Icons.Default.SkipPrevious, contentDescription = null) },
        )
        AssistChip(
            onClick = { player.send(SessionButtons.BACK_15) },
            label = { Text("15 s") },
            leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null) },
        )
        if (behind) {
            AssistChip(
                onClick = { player.send(SessionButtons.GO_LIVE) },
                label = { Text("Live") },
                leadingIcon = { Icon(Icons.Default.Sensors, contentDescription = null) },
            )
        }
    }
}

/**
 * Lyrics for what's playing, following along when they're synced. On SiriusXM channels it's
 * the song on the channel; its position comes from the stream's clock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val state by container.player.state.collectAsStateWithLifecycle()
    val nowPlaying by container.siriusXm.nowPlaying.collectAsStateWithLifecycle()
    val origins by container.resolver.origins.collectAsStateWithLifecycle()
    val current = state.current ?: return
    val cut = nowPlaying?.takeIf { it.channel.key == current.key }?.cut?.takeIf { it.isSong }
    val song = when {
        current.isSiriusXmChannel -> cut?.toTrack()
        current.source == com.music.player.model.SourceType.RADIO -> null
        else -> current
    }
    val songPositionMs = when {
        cut != null && state.windowStartMs != null -> state.windowStartMs!! + state.positionMs - cut.startMs
        else -> state.positionMs
    }
    val lyrics by produceState<LyricsResult>(LyricsResult.Loading, song?.key) {
        value = if (song == null) {
            LyricsResult.Missing
        } else {
            runCatching { container.lyrics.find(song, origins[song.key]?.playing) }
                .fold({ it?.let(LyricsResult::Found) ?: LyricsResult.Missing }, { LyricsResult.Failed(it.message ?: "Couldn't load lyrics") })
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(song?.title ?: current.title, style = MaterialTheme.typography.titleLarge)
            Text(song?.artist ?: current.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val result = lyrics) {
                LyricsResult.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                LyricsResult.Missing -> Hint(if (song == null) "No song to show lyrics for." else "No lyrics found for this song.")
                is LyricsResult.Failed -> Hint(result.message)
                is LyricsResult.Found -> LyricLines(result.lyrics, songPositionMs)
            }
        }
    }
}

@Composable
private fun LyricLines(lyrics: Lyrics, positionMs: Long) {
    val listState = rememberLazyListState()
    val currentLine = lyrics.lineAt(positionMs)
    LaunchedEffect(currentLine) {
        if (currentLine > 2) listState.animateScrollToItem(currentLine - 2)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            Text(
                line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (index == currentLine) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !lyrics.synced -> MaterialTheme.colorScheme.onSurface
                    index == currentLine -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (index < currentLine) 0.5f else 0.85f)
                },
            )
        }
        item {
            Text(
                "Lyrics from ${lyrics.source}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

private sealed interface LyricsResult {
    data object Loading : LyricsResult
    data object Missing : LyricsResult
    data class Failed(val message: String) : LyricsResult
    data class Found(val lyrics: Lyrics) : LyricsResult
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    )
}

/** Pick when music stops; shows the time left while a timer runs. */
@Composable
fun SleepTimerDialog(isLive: Boolean, onDismiss: () -> Unit) {
    val timer = LocalAppContainer.current.sleepTimer
    val state by timer.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                when (val current = state) {
                    is SleepTimerState.At -> {
                        var now by remember { mutableStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(current) {
                            while (true) {
                                now = System.currentTimeMillis()
                                delay(1_000)
                            }
                        }
                        val left = ((current.endsAtMs - now) / 1000).coerceAtLeast(0)
                        Text("Stops in ${left / 60}:${"%02d".format(left % 60)}", style = MaterialTheme.typography.titleMedium)
                    }
                    SleepTimerState.EndOfSong -> Text("Stops when this song ends", style = MaterialTheme.typography.titleMedium)
                    null -> Unit
                }
                SleepTimer.CHOICES_MINUTES.forEach { minutes ->
                    ListItem(
                        modifier = Modifier.clickable { timer.stopIn(minutes); onDismiss() },
                        headlineContent = { Text(if (minutes < 60 || minutes % 60 != 0) "$minutes minutes" else "${minutes / 60} hour") },
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (!isLive) {
                    ListItem(
                        modifier = Modifier.clickable { timer.stopAtEndOfSong(); onDismiss() },
                        headlineContent = { Text("End of this song") },
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        },
        confirmButton = {
            if (state != null) TextButton(onClick = { timer.cancel(); onDismiss() }) { Text("Turn off timer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
