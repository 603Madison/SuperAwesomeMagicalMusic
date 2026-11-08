package com.music.connect.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.music.connect.net.AlbumPage
import com.music.connect.net.AlbumSummary
import com.music.connect.net.ApiTrack
import com.music.connect.net.ArtistPage
import com.music.connect.net.ArtistSummary
import com.music.connect.net.SearchResponse
import com.music.connect.net.TopResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Opens artists and albums from anywhere: search, a song's menu. */
class Browse(private val nav: NavHostController) {
    fun artist(id: String?, name: String? = null) = nav.navigate("artist?" + params("id" to id, "name" to name))

    fun album(id: String?, title: String? = null, artist: String? = null) = nav.navigate("album?" + params("id" to id, "title" to title, "artist" to artist))

    private fun params(vararg pairs: Pair<String, String?>) = pairs.filter { it.second != null }.joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" }
}

val LocalBrowse = staticCompositionLocalOf<Browse?> { null }

private const val SONGS_AT_FIRST = 5

/**
 * Search the way streaming apps do it: the likeliest match on top (an artist, an album or a
 * song), then songs, artists and albums, each once whichever source has it. Offline, it searches
 * the downloads.
 */
@Composable
fun SearchScreen() {
    val c = appContainer()
    val player = LocalPlayer.current
    val online by c.online.collectAsState()
    val downloaded by c.downloads.doneKeys.collectAsState(initial = emptySet())
    val playing by player.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResponse?>(null) }
    var offline by remember { mutableStateOf<List<ApiTrack>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var allSongs by remember { mutableStateOf(false) }

    LaunchedEffect(query, online) {
        val q = query.trim()
        allSongs = false
        if (q.isEmpty()) { results = null; offline = null; return@LaunchedEffect }
        delay(450)
        busy = true
        error = null
        if (online) {
            results = try {
                c.api.search(q)
            } catch (e: Exception) {
                error = e.message
                null
            }
            offline = null
        } else {
            val words = q.lowercase().split(' ').filter { it.isNotBlank() }
            offline = c.downloads.all.first().filter { it.done }.map { it.track }
                .filter { t -> words.all { "${t.title} ${t.artist} ${t.album.orEmpty()}".lowercase().contains(it) } }
            results = null
        }
        busy = false
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (busy) CircularProgressIndicator(Modifier.padding(12.dp)) },
            placeholder = { Text(if (online) "Artists, albums, songs" else "Search your downloads") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isBlank()) {
                item { Empty("Search all your music", "Artists, albums and songs from your library and every service on your server.", Icons.Rounded.Search) }
            }
            offline?.let { songs ->
                item { SectionTitle("Downloaded") }
                if (songs.isEmpty()) item { Empty("No downloads match", "Offline, search looks through your downloads.", Icons.Rounded.CloudOff) }
                itemsIndexed(songs, key = { i, t -> "o-$i-${t.key}" }) { i, track ->
                    TrackRow(track, onClick = { player.play(songs, i) }, current = playing.track?.key == track.key, downloaded = true)
                }
            }
            results?.let { r ->
                val songs = r.allSongs
                if (r.top == null && songs.isEmpty() && r.artists.isEmpty() && r.albums.isEmpty()) {
                    item { Empty("Nothing found for “${r.query}”", "Try other words, or check your sources on the server.") }
                }
                r.top?.let { top ->
                    item(key = "top") {
                        SectionTitle("Top result")
                        TopResultCard(top, songs)
                    }
                }
                if (songs.isNotEmpty()) {
                    item(key = "songs") { SectionTitle("Songs") }
                    val shown = if (allSongs) songs else songs.take(SONGS_AT_FIRST)
                    itemsIndexed(shown, key = { i, t -> "s-$i-${t.key}" }) { i, track ->
                        TrackRow(track, onClick = { player.play(songs, i) }, current = playing.track?.key == track.key, downloaded = track.key in downloaded)
                    }
                    if (songs.size > SONGS_AT_FIRST) item(key = "more") {
                        TextButton(onClick = { allSongs = !allSongs }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(if (allSongs) "Fewer songs" else "All ${songs.size} songs")
                        }
                    }
                }
                if (r.artists.isNotEmpty()) item(key = "artists") {
                    SectionTitle("Artists")
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(r.artists, key = { it.id }) { ArtistTile(it) }
                    }
                }
                if (r.albums.isNotEmpty()) item(key = "albums") {
                    SectionTitle("Albums")
                    AlbumRow(r.albums)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/** The likeliest match, large: opens the artist or album, or plays the song. */
@Composable
private fun TopResultCard(top: TopResult, songs: List<ApiTrack>) {
    val c = appContainer()
    val player = LocalPlayer.current
    val browse = LocalBrowse.current
    val scope = rememberCoroutineScope()
    var starting by remember { mutableStateOf(false) }
    val title = top.artist?.name ?: top.album?.title ?: top.track?.title.orEmpty()
    val art = top.artist?.artworkUrl ?: top.album?.artworkUrl ?: top.track?.artworkUrl
    val label = when (top.kind) {
        "artist" -> "Artist"
        "album" -> "Album · ${top.album?.artist.orEmpty()}"
        else -> "Song · ${top.track?.artist.orEmpty()}"
    }
    fun play() {
        top.track?.let { song ->
            val index = songs.indexOfFirst { it.key == song.key }
            if (index >= 0) player.play(songs, index) else player.play(listOf(song))
            return
        }
        scope.launch {
            starting = true
            try {
                val tracks = top.artist?.let { c.api.artist(it.id).topSongs } ?: top.album?.let { c.api.album(it.id).tracks }.orEmpty()
                if (tracks.isNotEmpty()) player.play(tracks)
            } catch (e: Exception) {
                c.tell(e.message ?: "Couldn't play that")
            } finally {
                starting = false
            }
        }
    }
    Card(
        onClick = {
            when {
                top.artist != null -> browse?.artist(top.artist.id)
                top.album != null -> browse?.album(top.album.id)
                else -> play()
            }
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(art, 88.dp, corner = if (top.kind == "artist") 44.dp else 10.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledIconButton(onClick = ::play, enabled = !starting) {
                if (starting) CircularProgressIndicator(Modifier.padding(10.dp)) else Icon(Icons.Rounded.PlayArrow, "Play $title")
            }
        }
    }
}

@Composable
private fun ArtistTile(artist: ArtistSummary, size: Dp = 112.dp) {
    val browse = LocalBrowse.current
    Column(
        Modifier.width(size + 16.dp).clickable { browse?.artist(artist.id) }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(artist.artworkUrl, size, corner = size / 2)
        Text(artist.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AlbumRow(albums: List<AlbumSummary>, showArtist: Boolean = true) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(albums, key = { it.id }) { AlbumTile(it, showArtist) }
    }
}

@Composable
private fun AlbumTile(album: AlbumSummary, showArtist: Boolean, size: Dp = 136.dp) {
    val browse = LocalBrowse.current
    Column(Modifier.width(size + 16.dp).clickable { browse?.album(album.id) }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Artwork(album.artworkUrl, size)
        Text(album.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(album.year?.toString(), album.artist.takeIf { showArtist }).joinToString(" · ").ifEmpty { "Album" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Loads a page from the server, which artists and albums need. */
@Composable
private fun <T> rememberPage(vararg keys: Any?, load: suspend () -> T): Pair<T?, String?> {
    val c = appContainer()
    val online by c.online.collectAsState()
    var page by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(*keys, online) {
        if (page != null) return@LaunchedEffect
        if (!online) { error = "Artists and albums need a connection"; return@LaunchedEffect }
        error = null
        try {
            page = load()
        } catch (e: Exception) {
            error = e.message ?: "Couldn't open this"
        }
    }
    return page to error
}

@Composable
fun ArtistScreen(id: String?, name: String?) {
    val c = appContainer()
    val player = LocalPlayer.current
    val downloaded by c.downloads.doneKeys.collectAsState(initial = emptySet())
    val playing by player.state.collectAsState()
    val (page, error) = rememberPage(id, name) { c.api.artist(id, name) }
    if (page == null) {
        Loading(error, "Couldn't open this artist")
        return
    }
    val p: ArtistPage = page
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Artwork(p.artist.artworkUrl, 168.dp, corner = 84.dp)
                Text(p.artist.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                PlayRow(p.topSongs)
            }
        }
        if (p.topSongs.isNotEmpty()) {
            item { SectionTitle("Popular") }
            itemsIndexed(p.topSongs, key = { i, t -> "t-$i-${t.key}" }) { i, track ->
                TrackRow(track, onClick = { player.play(p.topSongs, i) }, current = playing.track?.key == track.key, downloaded = track.key in downloaded)
            }
        }
        if (p.albums.isNotEmpty()) item { SectionTitle("Albums"); AlbumRow(p.albums, showArtist = false) }
        if (p.singles.isNotEmpty()) item { SectionTitle("Singles and EPs"); AlbumRow(p.singles, showArtist = false) }
        if (p.topSongs.isEmpty() && p.albums.isEmpty() && p.singles.isEmpty()) item { Empty("Nothing by them here yet") }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun AlbumScreen(id: String?, title: String?, artist: String?) {
    val c = appContainer()
    val player = LocalPlayer.current
    val browse = LocalBrowse.current
    val downloaded by c.downloads.doneKeys.collectAsState(initial = emptySet())
    val playing by player.state.collectAsState()
    val (page, error) = rememberPage(id, title, artist) { c.api.album(id, title, artist) }
    if (page == null) {
        Loading(error, "Couldn't open this album")
        return
    }
    val p: AlbumPage = page
    val total = p.tracks.sumOf { it.durationMs ?: 0 }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Artwork(p.album.artworkUrl, 200.dp, corner = 14.dp)
                Spacer(Modifier.height(4.dp))
                Text(p.album.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    p.album.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { browse?.artist(p.album.artistId) }.padding(4.dp),
                )
                Text(
                    listOfNotNull(p.album.year?.toString(), "${p.tracks.size} songs", total.takeIf { it > 0 }?.let(::duration)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlayRow(p.tracks)
            }
        }
        itemsIndexed(p.tracks, key = { i, t -> "a-$i-${t.key}" }) { i, track ->
            TrackRow(track, onClick = { player.play(p.tracks, i) }, current = playing.track?.key == track.key, downloaded = track.key in downloaded)
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** Play, shuffle, download and add to a playlist, for a list of songs. */
@Composable
private fun PlayRow(tracks: List<ApiTrack>) {
    val c = appContainer()
    val player = LocalPlayer.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    val songs = tracks.filter { it.isSong }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { player.play(tracks) }, enabled = tracks.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Text("Play") }
        FilledTonalButton(onClick = { player.playShuffled(tracks) }, enabled = tracks.size > 1) { Icon(Icons.Rounded.Shuffle, null); Text("Shuffle") }
        FilledTonalIconButton(onClick = { scope.launch { c.downloads.queue(songs); c.tell("Downloading ${songs.size} songs") } }, enabled = songs.isNotEmpty()) {
            Icon(Icons.Rounded.Download, "Download all")
        }
        FilledTonalIconButton(onClick = { adding = true }, enabled = songs.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add all to a playlist") }
    }
    if (adding) AddToPlaylistDialog(songs) { adding = false }
}

@Composable
private fun Loading(error: String?, title: String) {
    if (error != null) {
        Empty(title, error, Icons.Rounded.CloudOff)
    } else {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
    }
}
