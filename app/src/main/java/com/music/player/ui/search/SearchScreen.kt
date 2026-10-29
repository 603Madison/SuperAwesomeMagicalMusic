package com.music.player.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.ui.LocalTrackActions
import com.music.player.ui.appViewModel
import com.music.player.ui.components.NetworkStatusRow
import com.music.player.ui.components.SourceBadge
import com.music.player.ui.components.TrackRow

@Composable
fun SearchScreen() {
    val viewModel = appViewModel { SearchViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloadedKeys.collectAsStateWithLifecycle()
    val actions = LocalTrackActions.current
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Songs, artists, albums") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                viewModel.submit()
                focus.clearFocus()
            }),
        )
        NetworkStatusRow(Modifier.padding(horizontal = 16.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            if (state.query.isBlank()) {
                item {
                    Text(
                        "Search your Jellyfin library first; songs it doesn't have can stream from " +
                            "Qobuz or YouTube Music, and their artists are sent to Lidarr.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            state.offlineResults?.let { offline ->
                header("You're offline — downloaded songs", null)
                if (offline.isEmpty()) message("No downloaded songs match")
                tracks(offline, downloaded) { index -> actions.play(offline, index) }
            }
            for (type in state.order) {
                val section = state.sections[type] ?: continue
                header(
                    if (type == SourceType.JELLYFIN) "In your library" else "Stream from ${type.label}",
                    type,
                )
                when (section) {
                    SectionState.Loading -> loading()
                    SectionState.NotConfigured -> message(
                        if (type == SourceType.YOUTUBE_MUSIC) "YouTube Music is turned off in Settings"
                        else "${type.label} isn't set up — add it in Settings",
                    )
                    is SectionState.Error -> message("Couldn't search ${type.label}: ${section.message}")
                    is SectionState.Results -> {
                        val tracks = state.visible[type].orEmpty()
                        if (tracks.isEmpty()) {
                            message(
                                if (section.tracks.isEmpty()) "No results"
                                else "Everything here is already in your library",
                            )
                        }
                        tracks(tracks, downloaded) { index -> actions.play(tracks, index) }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.header(title: String, source: SourceType?) {
    item(key = "header-$title") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            source?.let { SourceBadge(it) }
        }
    }
}

private fun LazyListScope.message(text: String) {
    item {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun LazyListScope.loading() {
    item {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

private fun LazyListScope.tracks(tracks: List<Track>, downloaded: Set<String>, onPlay: (Int) -> Unit) {
    itemsIndexed(tracks, key = { _, track -> "track-${track.key}" }) { index, track ->
        TrackRow(track = track, onClick = { onPlay(index) }, downloaded = track.key in downloaded)
    }
}
