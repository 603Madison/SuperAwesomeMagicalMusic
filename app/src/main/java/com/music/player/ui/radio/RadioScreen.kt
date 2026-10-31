package com.music.player.ui.radio

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import com.music.player.location.CoarseLocation
import com.music.player.sources.radio.RadioStation
import com.music.player.sources.siriusxm.SxmArtist
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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

private enum class RadioTab(val title: String) { SIRIUSXM("SiriusXM"), LOCAL("Radio stations") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen() {
    val viewModel = appViewModel { RadioViewModel(it) }
    val local = appViewModel { LocalRadioViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(RadioTab.SIRIUSXM) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Radio") },
                    actions = {
                        if (tab == RadioTab.SIRIUSXM && state.configured == true) {
                            IconButton(onClick = { viewModel.load(refresh = true) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh channels")
                            }
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    RadioTab.entries.forEach { entry ->
                        Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.title) })
                    }
                }
            }
        },
    ) { padding ->
        when (tab) {
            RadioTab.SIRIUSXM -> SiriusXmTab(viewModel, Modifier.padding(padding))
            RadioTab.LOCAL -> LocalRadioTab(local, Modifier.padding(padding))
        }
    }
}

@Composable
private fun SiriusXmTab(viewModel: RadioViewModel, modifier: Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalTrackActions.current
    var searching by rememberSaveable { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize()) {
        item { NowPlayingCard() }
        when {
            state.configured == false -> item {
                Text(
                    "Sign in to SiriusXM in Settings to listen to live channels and Pandora artist stations, and save the songs you hear.",
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
                item(key = "stations-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Header("Pandora artist stations", Modifier.weight(1f))
                        TextButton(onClick = { searching = true }, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text(" New station")
                        }
                    }
                }
                if (state.stations.isEmpty()) {
                    item(key = "stations-hint") {
                        Text(
                            "Music by and like one artist. Tap \"Artist station\" on a song playing on SiriusXM, or start one by name.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                items(state.stations, key = { "station-" + it.id }) { station ->
                    ListItem(
                        modifier = Modifier.clickable { actions.play(listOf(station.toTrack())) },
                        leadingContent = { Artwork(station.artworkUrl, 48.dp) },
                        headlineContent = { Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("Pandora station") },
                        trailingContent = {
                            IconButton(onClick = { viewModel.removeStation(station) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove station")
                            }
                        },
                    )
                }
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
                if (state.bookmarked.isNotEmpty()) {
                    item(key = "bookmarked-header") { Header("Bookmarked") }
                    items(state.bookmarked, key = { "b-" + it.id }) { channel ->
                        ChannelRow(channel, bookmarked = true, onBookmark = { viewModel.toggleBookmark(channel) }) {
                            actions.play(listOf(channel.toTrack()))
                        }
                    }
                    item(key = "all-header") { Header("All channels") }
                }
                items(state.others, key = { it.id }) { channel ->
                    ChannelRow(channel, bookmarked = false, onBookmark = { viewModel.toggleBookmark(channel) }) {
                        actions.play(listOf(channel.toTrack()))
                    }
                }
            }
        }
    }

    if (searching) {
        ArtistSearchDialog(
            search = state.artistSearch,
            onSearch = viewModel::searchArtists,
            onPick = { artist ->
                searching = false
                viewModel.clearArtistSearch()
                actions.startArtistStation(artist)
            },
            onDismiss = {
                searching = false
                viewModel.clearArtistSearch()
            },
        )
    }
}

@Composable
private fun ArtistSearchDialog(
    search: ArtistSearch,
    onSearch: (String) -> Unit,
    onPick: (SxmArtist) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New artist station") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Artist") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    trailingIcon = {
                        IconButton(onClick = { onSearch(query) }, enabled = query.isNotBlank() && !search.running) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (search.running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                search.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    search.results.orEmpty().forEach { artist ->
                        ListItem(
                            modifier = Modifier.clickable { onPick(artist) },
                            leadingContent = { Artwork(artist.artworkUrl, 40.dp) },
                            headlineContent = { Text(artist.name) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun LocalRadioTab(viewModel: LocalRadioViewModel, modifier: Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalTrackActions.current
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.findNearby() else viewModel.locationDenied()
    }

    @Composable
    fun Stations(stations: List<RadioStation>) {
        stations.forEachIndexed { index, station ->
            StationRow(
                station = station,
                favorite = station.id in state.favoriteIds,
                distanceKm = state.distanceKm(station),
                onFavorite = { viewModel.toggleFavorite(station) },
                // Queue the whole list, so next and previous move between stations.
                onPlay = { actions.play(stations.map(RadioStation::toTrack), index) },
            )
        }
    }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; if (it.isBlank()) viewModel.clearSearch() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search stations by name") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = {
                        if (CoarseLocation.isPermitted(context)) viewModel.findNearby()
                        else askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                    enabled = !state.loading,
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null)
                    Text(" Find stations near me")
                }
                if (state.loading) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            state.message?.let {
                Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val results = state.searchResults
        if (results != null) {
            item { Header("Results") }
            item { Stations(results) }
        } else {
            if (state.favorites.isNotEmpty()) {
                item { Header("Favourites") }
                item { Stations(state.favorites) }
            }
            val nearby = state.nearby.filter { it.id !in state.favoriteIds }
            if (nearby.isNotEmpty()) {
                item { Header("Near you") }
                item { Stations(nearby) }
            }
        }
        item {
            Text(
                "Stations from Radio Browser (radio-browser.info), a free directory anyone can add stations to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun StationRow(station: RadioStation, favorite: Boolean, distanceKm: Int?, onFavorite: () -> Unit, onPlay: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onPlay),
        leadingContent = { Artwork(station.favicon.ifBlank { null }, 48.dp) },
        headlineContent = { Text(station.name.trim(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(
                    station.location.ifBlank { null },
                    distanceKm?.let { "$it km" },
                    station.format.ifBlank { null },
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun Header(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun ChannelRow(channel: SxmChannel, bookmarked: Boolean, onBookmark: () -> Unit, onPlay: () -> Unit) {
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
            if (channel.locked) {
                Icon(Icons.Default.Lock, contentDescription = "Not in your subscription")
            } else {
                IconButton(onClick = onBookmark) {
                    Icon(
                        if (bookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark",
                        tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
                Column(horizontalAlignment = Alignment.End) {
                    FilledTonalButton(onClick = { actions.saveHeardSong(cut) }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                        Text(" Save")
                    }
                    if (cut.artistId != null) {
                        TextButton(onClick = { actions.startArtistStation(cut) }) { Text("Artist station") }
                    }
                }
            }
        }
    }
}
