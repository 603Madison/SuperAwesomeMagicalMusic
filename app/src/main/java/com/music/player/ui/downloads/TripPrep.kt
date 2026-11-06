package com.music.player.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.model.Track
import com.music.player.ui.LocalAppContainer
import com.music.player.ui.LocalTrackActions
import kotlinx.coroutines.launch

/**
 * Downloads what you'll want on a trip in one go: your most played songs, what you've played
 * lately, and any playlists you pick.
 */
@Composable
fun TripPrepDialog(onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val actions = LocalTrackActions.current
    val scope = rememberCoroutineScope()
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle(emptyList())
    var mostPlayed by remember { mutableStateOf(true) }
    var recent by remember { mutableStateOf(true) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    val settings by container.settingsState.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Get ready for a trip") },
        text = {
            Column {
                Text(
                    if (settings.downloads.allowCellular) "Downloads what you pick, so it plays without a signal."
                    else "Downloads what you pick, so it plays without a signal. Downloads wait for Wi-Fi " +
                        "(Settings › Streaming quality allows cellular).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Choice("Your most played", "Up to $MOST_PLAYED songs from the last three months", mostPlayed) { mostPlayed = it }
                Choice("Recently played", "The last $RECENT songs you played or heard", recent) { recent = it }
                if (playlists.isNotEmpty()) HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        Choice(playlist.name, "${playlist.trackCount} songs", playlist.id in chosen) { on ->
                            chosen = if (on) chosen + playlist.id else chosen - playlist.id
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = mostPlayed || recent || chosen.isNotEmpty(),
                onClick = {
                    scope.launch {
                        val history = container.database.history()
                        val tracks = buildList<Track> {
                            if (mostPlayed) {
                                val since = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
                                addAll(history.mostPlayed(since, MOST_PLAYED).map { it.track })
                            }
                            if (recent) addAll(history.recent(RECENT * 2).map { it.track }.distinctBy { it.key }.take(RECENT))
                            chosen.forEach { addAll(container.playlists.entries(it)) }
                        }.filter { !it.isLive }.distinctBy { it.key }
                        actions.download(tracks)
                        onDismiss()
                    }
                },
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = onChange) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
    )
}

private const val MOST_PLAYED = 100
private const val RECENT = 50
