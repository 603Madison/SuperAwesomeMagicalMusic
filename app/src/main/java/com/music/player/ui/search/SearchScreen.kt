package com.music.player.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.browse.TopResult
import com.music.player.model.Track
import com.music.player.ui.LocalTrackActions
import com.music.player.ui.appViewModel
import com.music.player.ui.components.Artwork
import com.music.player.ui.components.NetworkStatusRow
import com.music.player.ui.components.TrackRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun SearchScreen() {
    val viewModel = appViewModel { SearchViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloadedKeys.collectAsStateWithLifecycle()
    val actions = LocalTrackActions.current
    val focus = LocalFocusManager.current
    var allSongs by remember(state.results?.songs?.size, state.query) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "Search",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        )
        // A filled, pill-shaped field rather than an outlined box.
        TextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
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
        NetworkStatusRow(Modifier.padding(horizontal = 24.dp))

        if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            if (state.query.isBlank()) {
                item { RecentlyPlayed() }
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.LibraryMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                        }
                        Text("Find something to play", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Text(
                            "Artists, albums and songs from your Jellyfin library and your streaming services, " +
                                "all at once. Songs play from your library whenever it has them.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            state.offlineResults?.let { offline ->
                header("You're offline — downloaded songs")
                if (offline.isEmpty()) message("No downloaded songs match")
                tracks(offline, downloaded) { index -> actions.play(offline, index) }
            }
            if (state.noSources) message("Set up Jellyfin, Qobuz or YouTube Music in Settings to search")
            state.results?.let { results ->
                val songs = results.songs.map { it.item }
                if (!state.searching && results.top == null && songs.isEmpty() && results.artists.isEmpty() && results.albums.isEmpty()) {
                    message("Nothing found for “${state.query.trim()}”")
                }
                results.top?.let { top ->
                    header("Top result")
                    item(key = "top") { TopResultCard(top, songs) }
                }
                if (songs.isNotEmpty()) {
                    header("Songs")
                    val shown = if (allSongs) songs else songs.take(SONGS_AT_FIRST)
                    itemsIndexed(shown, key = { _, track -> "song-${track.key}" }) { index, track ->
                        TrackRow(track = track, onClick = { actions.play(songs, index) }, downloaded = track.key in downloaded)
                    }
                    if (songs.size > SONGS_AT_FIRST) {
                        item(key = "more-songs") {
                            TextButton(onClick = { allSongs = !allSongs }, modifier = Modifier.padding(horizontal = 12.dp)) {
                                Text(if (allSongs) "Fewer songs" else "All ${songs.size} songs")
                            }
                        }
                    }
                }
                if (results.artists.isNotEmpty()) {
                    header("Artists")
                    item(key = "artists") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                            items(results.artists, key = { it.name }) { ArtistTile(it) }
                        }
                    }
                }
                if (results.albums.isNotEmpty()) {
                    header("Albums")
                    item(key = "albums") { AlbumRow(results.albums) }
                }
                if (state.problems.isNotEmpty() && !state.searching) state.problems.forEach { message("Couldn't search $it") }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

private const val SONGS_AT_FIRST = 5

/** The likeliest match, large: opens the artist or album, or plays the song. */
@Composable
private fun TopResultCard(top: TopResult, songs: List<Track>) {
    val actions = LocalTrackActions.current
    val container = com.music.player.ui.LocalAppContainer.current
    val (title, art, label) = when (top) {
        is TopResult.OfArtist -> Triple(top.artist.name, top.artist.artworkUrl, "Artist")
        is TopResult.OfAlbum -> Triple(top.album.title, top.album.artworkUrl, "Album · ${top.album.artist}")
        is TopResult.OfSong -> Triple(top.song.item.title, top.song.item.artworkUrl, "Song · ${top.song.item.artist}")
    }
    val open = {
        when (top) {
            is TopResult.OfArtist -> { container.remembered.keep(top.artist); actions.openArtist(top.artist.name) }
            is TopResult.OfAlbum -> { container.remembered.keep(top.album); actions.openAlbum(top.album.title, top.album.artist) }
            is TopResult.OfSong -> {
                val index = songs.indexOfFirst { it.key == top.song.item.key }
                if (index >= 0) actions.play(songs, index) else actions.play(listOf(top.song.item))
            }
        }
    }
    Card(
        onClick = open,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(art, 88.dp, circle = top is TopResult.OfArtist)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun ArtistTile(artist: com.music.player.browse.Artist) {
    val actions = LocalTrackActions.current
    val container = com.music.player.ui.LocalAppContainer.current
    Column(
        Modifier.width(128.dp).clickable { container.remembered.keep(artist); actions.openArtist(artist.name) }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(artist.artworkUrl, 112.dp, circle = true)
        Text(artist.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun AlbumRow(albums: List<com.music.player.browse.Album>, showArtist: Boolean = true) {
    val actions = LocalTrackActions.current
    val container = com.music.player.ui.LocalAppContainer.current
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
        items(albums, key = { "${it.title}|${it.artist}" }) { album ->
            Column(
                Modifier.width(152.dp).clickable { container.remembered.keep(album); actions.openAlbum(album.title, album.artist) }.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Artwork(album.artworkUrl, 136.dp)
                Text(album.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(album.year?.toString(), album.artist.takeIf { showArtist }).joinToString(" · ").ifEmpty { "Album" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun LazyListScope.header(title: String) {
    item(key = "header-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        )
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

private fun LazyListScope.tracks(tracks: List<Track>, downloaded: Set<String>, onPlay: (Int) -> Unit) {
    itemsIndexed(tracks, key = { _, track -> "track-${track.key}" }) { index, track ->
        TrackRow(track = track, onClick = { onPlay(index) }, downloaded = track.key in downloaded)
    }
}

/** The last songs played (or heard), to play again with a tap. */
@Composable
private fun RecentlyPlayed() {
    val container = com.music.player.ui.LocalAppContainer.current
    val actions = com.music.player.ui.LocalTrackActions.current
    val recent by androidx.compose.runtime.remember { container.database.history().observeRecent(40) }
        .collectAsStateWithLifecycle(emptyList())
    val songs = recent.map { it.track }.distinctBy { it.key }.take(12)
    if (songs.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            "Recently played",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(songs.size) { index ->
                val song = songs[index]
                Column(
                    Modifier.width(112.dp).clickable { actions.play(listOf(song)) },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    com.music.player.ui.components.Artwork(song.artworkUrl, 112.dp)
                    Text(song.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
