package com.music.player.ui.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.sources.siriusxm.SxmChannel
import com.music.player.ui.LocalAppContainer
import com.music.player.ui.LocalTrackActions
import com.music.player.ui.appViewModel
import com.music.player.ui.components.Artwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen() {
    val viewModel = appViewModel { RadioViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalTrackActions.current

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("SiriusXM") },
                actions = {
                    if (state.configured == true) {
                        IconButton(onClick = { viewModel.load(refresh = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh channels")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { NowPlayingCard() }
            when {
                state.configured == false -> item {
                    Text(
                        "Sign in to SiriusXM in Settings to listen to live channels and save the songs you hear.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.loading -> item {
                    Row(Modifier.padding(16.dp)) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                }
                state.error != null -> item {
                    Text(state.error.orEmpty(), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                else -> {
                    item {
                        OutlinedTextField(
                            value = state.filter,
                            onValueChange = viewModel::setFilter,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = { Text("Filter channels") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                        )
                    }
                    items(state.visible, key = { it.id }) { channel ->
                        ChannelRow(channel) { actions.play(listOf(channel.toTrack())) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: SxmChannel, onPlay: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = !channel.locked, onClick = onPlay),
        leadingContent = { Artwork(channel.logoUrl, 48.dp) },
        headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(channel.number?.let { "Ch $it" }, channel.category.ifBlank { null }).joinToString(" · "),
                maxLines = 1,
            )
        },
        trailingContent = {
            if (channel.locked) Icon(Icons.Default.Lock, contentDescription = "Not in your subscription")
        },
    )
}

/** The song on the SiriusXM channel playing now, with a button to save it to a playlist. */
@Composable
fun NowPlayingCard(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val actions = LocalTrackActions.current
    val nowPlaying by container.siriusXm.nowPlaying.collectAsStateWithLifecycle()
    val current = nowPlaying ?: return
    val cut = current.cut
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(cut?.artworkUrl ?: current.channel.artworkUrl, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("On ${current.channel.title}", style = MaterialTheme.typography.labelMedium)
                if (cut?.isSong == true) {
                    Text(cut.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cut.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(
                        if (cut == null) "Finding out what's playing…" else "No song playing right now",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (cut?.isSong == true) {
                FilledTonalButton(onClick = { actions.saveHeardSong(cut) }) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                    Text(" Save")
                }
            }
        }
    }
}
