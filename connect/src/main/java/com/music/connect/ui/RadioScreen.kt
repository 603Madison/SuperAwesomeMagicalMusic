package com.music.connect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.connect.net.ApiTrack
import com.music.connect.net.ArtistView
import com.music.connect.net.ChannelView
import com.music.connect.net.SourceView
import kotlinx.coroutines.launch
import java.util.Locale

/** SiriusXM channels, Pandora artist stations and radio stations, through the server. */
@Composable
fun RadioScreen() {
    val c = appContainer()
    val online by c.online.collectAsState()
    var sources by remember { mutableStateOf<List<SourceView>?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(online) { if (online) sources = runCatching { c.api.sources() }.getOrNull() }
    if (!online) {
        Empty("Radio needs a connection", "Live radio streams from your server. Your downloads still play.", Icons.Rounded.CloudOff)
        return
    }
    val usable = sources?.filter { it.enabled && !it.hidden } ?: run {
        Row(Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator() }
        return
    }
    val sxm = usable.firstOrNull { it.kind == "SIRIUSXM" }
    val radio = usable.firstOrNull { it.kind == "RADIO" }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("SiriusXM", "Pandora", "Local radio").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
        }
        when (tab) {
            0 -> sxm?.let { Channels(it) } ?: Empty("No SiriusXM account", "Add yours on the server, in Settings › Sources.", Icons.Rounded.Radio)
            1 -> sxm?.let { Stations(it) } ?: Empty("No SiriusXM account", "Pandora artist stations come through your SiriusXM account.", Icons.Rounded.Radio)
            else -> radio?.let { LocalRadio(it) } ?: Empty("Radio is off", "An admin can add it on the server.")
        }
    }
}

@Composable
private fun Channels(source: SourceView) {
    val c = appContainer()
    val player = LocalPlayer.current
    val playing by player.state.collectAsState()
    var channels by remember(source.id) { mutableStateOf<List<ChannelView>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(source.id) {
        channels = try { c.api.channels(source.id) } catch (e: Exception) { error = e.message; emptyList() }
    }
    val words = filter.lowercase().split(' ').filter { it.isNotBlank() }
    val shown = channels.orEmpty().filter { !it.locked && words.all { w -> "${it.track.title} ${it.number ?: ""} ${it.category}".lowercase().contains(w) } }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(filter, { filter = it }, placeholder = { Text("Filter: name, number or genre") }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        if (channels == null) item { Row(Modifier.padding(32.dp)) { CircularProgressIndicator() } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        shown.groupBy { it.category.ifBlank { "Other" } }.forEach { (category, list) ->
            item(key = "c-$category") { SectionTitle(category) }
            items(list, key = { it.track.sourceId }) { channel ->
                Row(
                    Modifier.fillMaxWidth().clickable { player.play(listOf(channel.track)) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(channel.track.artworkUrl, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(channel.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing.track?.key == channel.track.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(channel.number?.let { "Ch $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stations(source: SourceView) {
    val c = appContainer()
    val player = LocalPlayer.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var artists by remember { mutableStateOf<List<ArtistView>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun search() {
        if (query.isBlank()) return
        busy = true
        scope.launch {
            artists = runCatching { c.api.artists(source.id, query.trim()) }.onFailure { c.tell(it.message ?: "Search failed") }.getOrNull()
            busy = false
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("An artist to start a station from") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (busy) CircularProgressIndicator(Modifier.padding(12.dp)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        artists?.let { if (it.isEmpty()) item { Empty("No stations found", "Try the artist's full name.") } }
        items(artists.orEmpty(), key = { it.id }) { artist ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        runCatching { c.api.station(source.id, artist, null, null, false) }
                            .onSuccess { if (it.tracks.isNotEmpty()) player.play(it.tracks) else c.tell("SiriusXM sent nothing for this station") }
                            .onFailure { c.tell(it.message ?: "Couldn't start the station") }
                    }
                }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(artist.artworkUrl, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(artist.name, style = MaterialTheme.typography.bodyLarge)
                    Text(if (artist.stationId != null) "Pandora station" else "Artist station", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LocalRadio(source: SourceView) {
    val c = appContainer()
    val player = LocalPlayer.current
    val playing by player.state.collectAsState()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var stations by remember { mutableStateOf<List<ApiTrack>?>(null) }
    var title by remember { mutableStateOf("Popular in ${Locale.getDefault().displayCountry}") }
    LaunchedEffect(source.id) {
        stations = runCatching { c.api.radioSearch(source.id, "", Locale.getDefault().country.ifBlank { null }) }.getOrDefault(emptyList())
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Station name, e.g. WFMU or jazz") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    scope.launch {
                        stations = runCatching { c.api.radioSearch(source.id, query.trim(), null) }.onFailure { c.tell(it.message ?: "Search failed") }.getOrNull()
                        title = "“${query.trim()}”"
                    }
                }),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        item { SectionTitle(title) }
        if (stations == null) item { Row(Modifier.padding(32.dp)) { CircularProgressIndicator() } }
        items(stations.orEmpty(), key = { it.sourceId }) { station ->
            TrackRow(station, onClick = { player.play(listOf(station)) }, current = playing.track?.key == station.key)
        }
    }
}
