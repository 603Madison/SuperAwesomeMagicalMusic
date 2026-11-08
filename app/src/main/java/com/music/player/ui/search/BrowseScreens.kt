package com.music.player.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.browse.AlbumPage
import com.music.player.browse.ArtistPage
import com.music.player.model.Track
import com.music.player.ui.LocalAppContainer
import com.music.player.ui.LocalTrackActions
import com.music.player.ui.components.Artwork
import com.music.player.ui.components.TrackRow
import kotlinx.coroutines.CancellationException

/** An artist: their best-known songs, then albums and singles, from all your sources. */
@Composable
fun ArtistScreen(name: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val actions = LocalTrackActions.current
    val downloaded by container.downloads.completedKeys.collectAsStateWithLifecycle(emptySet())
    val (page, error) = rememberPage(name) {
        container.browsing.artist(container.browseSources(), name, container.remembered.artist(name)?.copies.orEmpty())
            ?: throw IllegalStateException("None of your sources has $name")
    }
    BrowseScaffold(page?.name ?: name, onBack) {
        val p: ArtistPage = page ?: return@BrowseScaffold Loading(error)
        val songs = p.topSongs.map { it.item }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(p.artworkUrl, 168.dp, circle = true)
                    Text(p.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    PlayButtons(songs)
                }
            }
            if (songs.isNotEmpty()) {
                item { Title("Popular") }
                itemsIndexed(songs, key = { _, t -> "top-${t.key}" }) { i, track ->
                    TrackRow(track = track, onClick = { actions.play(songs, i) }, downloaded = track.key in downloaded)
                }
            }
            if (p.albums.isNotEmpty()) item { Title("Albums"); AlbumRow(p.albums, showArtist = false) }
            if (p.singles.isNotEmpty()) item { Title("Singles and EPs"); AlbumRow(p.singles, showArtist = false) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** An album's songs in order, from your library when it has them. */
@Composable
fun AlbumScreen(title: String, artist: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val actions = LocalTrackActions.current
    val downloaded by container.downloads.completedKeys.collectAsStateWithLifecycle(emptySet())
    val (page, error) = rememberPage(title, artist) {
        container.browsing.album(container.browseSources(), title, artist, container.remembered.album(title, artist)?.copies.orEmpty())
            ?: throw IllegalStateException("None of your sources has $title")
    }
    BrowseScaffold(page?.album?.title ?: title, onBack) {
        val p: AlbumPage = page ?: return@BrowseScaffold Loading(error)
        val songs = p.tracks.map { it.item }
        val total = songs.sumOf { it.durationMs ?: 0 }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Artwork(p.album.artworkUrl, 200.dp)
                    Text(p.album.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(
                        p.album.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { actions.openArtist(p.album.artist) }.padding(4.dp),
                    )
                    Text(
                        listOfNotNull(p.album.year?.toString(), "${songs.size} songs", total.takeIf { it > 0 }?.let(::minutes)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PlayButtons(songs)
                }
            }
            itemsIndexed(songs, key = { i, t -> "$i-${t.key}" }) { i, track ->
                TrackRow(track = track, onClick = { actions.play(songs, i) }, downloaded = track.key in downloaded)
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private fun minutes(ms: Long): String {
    val total = ms / 60_000
    return if (total >= 60) "${total / 60} h ${total % 60} min" else "$total min"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding -> Box(Modifier.padding(padding).fillMaxSize()) { content() } }
}

/** Loads a page once; artists and albums need a connection. */
@Composable
private fun <T> rememberPage(vararg keys: Any, load: suspend () -> T): Pair<T?, String?> {
    val container = LocalAppContainer.current
    var page by remember(*keys) { mutableStateOf<T?>(null) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    LaunchedEffect(*keys) {
        if (!container.networkMonitor.state.value.isOnline) {
            error = "Artists and albums need a connection"
            return@LaunchedEffect
        }
        try {
            page = load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't open this"
        }
    }
    return page to error
}

@Composable
private fun Loading(error: String?) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
}

/** Play, shuffle, download and add to a playlist, for a list of songs. */
@Composable
private fun PlayButtons(songs: List<Track>) {
    val actions = LocalTrackActions.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { actions.play(songs) }, enabled = songs.isNotEmpty()) { Icon(Icons.Default.PlayArrow, null); Text("Play") }
        FilledTonalButton(onClick = { actions.shuffle(songs) }, enabled = songs.size > 1) { Icon(Icons.Default.Shuffle, null); Text("Shuffle") }
        FilledTonalIconButton(onClick = { actions.download(songs) }, enabled = songs.isNotEmpty()) { Icon(Icons.Default.Download, "Download all") }
        FilledTonalIconButton(onClick = { actions.addToPlaylist(songs) }, enabled = songs.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add all to a playlist")
        }
    }
}
