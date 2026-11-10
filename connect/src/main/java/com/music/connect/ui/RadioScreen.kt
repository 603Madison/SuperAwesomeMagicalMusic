package com.music.connect.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.connect.net.ApiTrack
import com.music.connect.net.ArtistView
import com.music.connect.net.ChannelView
import com.music.connect.net.SourceView
import com.music.connect.ui.theme.SiteRose
import java.util.Locale
import kotlinx.coroutines.launch

/** SiriusXM channels, Pandora artist stations and radio stations, through the server. */
@Composable
fun RadioScreen() {
    val c = appContainer()
    val online by c.online.collectAsState()
    var sources by remember { mutableStateOf<List<SourceView>?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(online) { if (online) sources = runCatching { c.api.sources() }.getOrNull() }
    if (!online) {
        Column {
            ScreenTitle("Radio")
            Empty("Radio needs a connection", "Live radio streams from your server. Your downloads still play.", Icons.Rounded.CloudOff)
        }
        return
    }
    val usable = sources?.filter { it.enabled && !it.hidden } ?: run {
        Column {
            ScreenTitle("Radio")
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        }
        return
    }
    val sxm = usable.firstOrNull { it.kind == "SIRIUSXM" }
    val radio = usable.firstOrNull { it.kind == "RADIO" }
    val favorites by c.favorites.collectAsState()
    // Favourites made on the web player show up here when the tab opens.
    LaunchedEffect(Unit) { c.refreshFavorites() }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Radio")
        if (favorites.isNotEmpty()) Favorites(favorites)
        val kinds = listOf("SiriusXM", "Pandora", "Local radio")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            kinds.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i,
                    onClick = { tab = i },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = kinds.size),
                    icon = {},
                ) { Text(label, maxLines = 1) }
            }
        }
        when (tab) {
            0 -> sxm?.let { Channels(it) } ?: Empty("No SiriusXM account", "Add yours on the server, in Settings › Sources.", Icons.Rounded.Radio)
            1 -> sxm?.let { Stations(it) } ?: Empty("No SiriusXM account", "Pandora artist stations come through your SiriusXM account.", Icons.Rounded.Radio)
            else -> radio?.let { LocalRadio(it) } ?: Empty("Radio is off", "An admin can add it on the server.")
        }
    }
}

/** The person's favourite channels and stations (from here or the web player): one tap to play. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Favorites(favorites: List<ApiTrack>) {
    val c = appContainer()
    val player = LocalPlayer.current
    val playing by player.state.collectAsState()
    val scope = rememberCoroutineScope()
    val playingKey = c.favoriteOf(playing.track)?.key
    var menuFor by remember { mutableStateOf<String?>(null) }
    SectionTitle("Favourites")
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 12.dp).testTag("favorites"),
    ) {
        items(favorites, key = { it.key }) { track ->
            val current = playingKey == track.key
            Box {
                Column(
                    Modifier.width(88.dp).combinedClickable(
                        onClick = {
                            scope.launch {
                                runCatching { c.tracksOf(track) }
                                    .onSuccess { if (it.isNotEmpty()) player.play(it) else c.tell("SiriusXM sent nothing for this station") }
                                    .onFailure { c.tell(it.message ?: "Couldn't start ${track.title}") }
                            }
                        },
                        onLongClick = { menuFor = track.key },
                        onLongClickLabel = "More",
                    ),
                ) {
                    Artwork(track.artworkUrl, 88.dp, corner = 18.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        track.title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(expanded = menuFor == track.key, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text("Remove from favourites") },
                        leadingIcon = { Icon(Icons.Rounded.HeartBroken, null) },
                        onClick = {
                            menuFor = null
                            c.setFavorite(track, false)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Channels(source: SourceView) {
    val c = appContainer()
    val player = LocalPlayer.current
    val playing by player.state.collectAsState()
    val favorites by c.favorites.collectAsState()
    val favoriteKeys = remember(favorites) { favorites.map { it.key }.toSet() }
    var channels by remember(source.id) { mutableStateOf<List<ChannelView>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(source.id) {
        channels = try { c.api.channels(source.id) } catch (e: Exception) { error = e.message; emptyList() }
    }
    val words = filter.lowercase().split(' ').filter { it.isNotBlank() }
    val shown = channels.orEmpty().filter { !it.locked && words.all { w -> "${it.track.title} ${it.number ?: ""} ${it.category}".lowercase().contains(w) } }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SearchField(filter, { filter = it }, "Filter: name, number or genre") }
        if (channels == null) item { Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
        shown.groupBy { it.category.ifBlank { "Other" } }.forEach { (category, list) ->
            item(key = "c-$category") { SectionTitle(category) }
            itemsIndexed(list, key = { _, it -> it.track.sourceId }) { i, channel ->
                CardItem(i, list.size) { Row(
                    Modifier.fillMaxWidth().clickable { player.play(listOf(channel.track)) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(channel.track.artworkUrl, 48.dp, corner = 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(channel.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing.track?.key == channel.track.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(channel.number?.let { "Ch $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (channel.track.key in favoriteKeys) Icon(Icons.Rounded.Favorite, "Favourite", tint = SiteRose, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                } }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
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
        item { SearchField(query, { query = it }, "An artist to start a station from", busy = busy, onSearch = { search() }) }
        if (artists == null) item { Empty("Pandora artist stations", "Search for an artist to start their station; the heart keeps it in your favourites.", Icons.Rounded.Radio) }
        artists?.let { if (it.isEmpty()) item { Empty("No stations found", "Try the artist's full name.") } }
        val found = artists.orEmpty()
        if (found.isNotEmpty()) item { Spacer(Modifier.height(8.dp)) }
        itemsIndexed(found, key = { _, it -> it.id }) { i, artist ->
            CardItem(i, found.size) { Row(
                Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        runCatching { c.api.station(source.id, artist, null, null, false) }
                            .onSuccess {
                                c.rememberStation(it.station)
                                if (it.tracks.isNotEmpty()) player.play(it.tracks) else c.tell("SiriusXM sent nothing for this station")
                            }
                            .onFailure { c.tell(it.message ?: "Couldn't start the station") }
                    }
                }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(artist.artworkUrl, 52.dp, corner = 26.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(artist.name, style = MaterialTheme.typography.bodyLarge)
                    Text(if (artist.stationId != null) "Pandora station" else "Artist station", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StationHeart(source, artist)
            } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** A heart on an artist's Pandora station: the station is made (or found) when it's favourited. */
@Composable
private fun StationHeart(source: SourceView, artist: ArtistView) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val favorites by c.favorites.collectAsState()
    var busy by remember { mutableStateOf(false) }
    val saved = favorites.firstOrNull { f ->
        f.isPandoraStation && f.via == source.id && (f.pandoraStationId == artist.stationId || f.title == artist.name)
    }
    IconButton(
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                if (saved != null) {
                    c.setFavorite(saved, false)
                } else {
                    runCatching { c.api.station(source.id, artist, null, null, false) }
                        .onSuccess {
                            c.rememberStation(it.station)
                            c.setFavorite(it.station, true)
                            c.tell("${it.station.title} is in your favourites, at the top of the Radio tab")
                        }
                        .onFailure { c.tell(it.message ?: "Couldn't make the station") }
                }
                busy = false
            }
        },
    ) {
        Icon(
            if (saved != null) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (saved != null) "Remove ${artist.name}'s station from favourites" else "Add ${artist.name}'s station to favourites",
            tint = if (saved != null) SiteRose else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            SearchField(query, { query = it }, "Station name, e.g. WFMU or jazz", onSearch = {
                scope.launch {
                    stations = runCatching { c.api.radioSearch(source.id, query.trim(), null) }.onFailure { c.tell(it.message ?: "Search failed") }.getOrNull()
                    title = "“${query.trim()}”"
                }
            })
        }
        item { SectionTitle(title) }
        if (stations == null) item { Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
        val list = stations.orEmpty()
        itemsIndexed(list, key = { _, it -> it.sourceId }) { i, station ->
            CardItem(i, list.size) { TrackRow(station, onClick = { player.play(listOf(station)) }, current = playing.track?.key == station.key) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
