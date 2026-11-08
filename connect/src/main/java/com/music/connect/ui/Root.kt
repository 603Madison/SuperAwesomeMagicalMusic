package com.music.connect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("search", "Search", Icons.Rounded.Search),
    Tab("radio", "Radio", Icons.Rounded.Radio),
    Tab("playlists", "Playlists", Icons.AutoMirrored.Rounded.QueueMusic),
    Tab("downloads", "Downloads", Icons.Rounded.DownloadForOffline),
    Tab("settings", "Settings", Icons.Rounded.Settings),
)

@Composable
fun MainScaffold() {
    val c = appContainer()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { c.messages.collect { snackbar.showSnackbar(it) } }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val browse = remember(nav) { Browse(nav) }
    CompositionLocalProvider(LocalBrowse provides browse) { Shell(nav, route, snackbar) }
}

@Composable
private fun Shell(nav: NavHostController, route: String?, snackbar: SnackbarHostState) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route != "player") {
                Column {
                    MiniPlayer { nav.navigate("player") }
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route?.startsWith(tab.route) == true,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo("search") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            NetworkBanner()
            Box(Modifier.weight(1f)) { Routes(nav) }
        }
    }
}

@Composable
private fun Routes(nav: NavHostController) {
    NavHost(nav, startDestination = "search") {
        composable("search") { SearchScreen() }
        composable("radio") { RadioScreen() }
        composable("playlists") { PlaylistsScreen(onOpen = { nav.navigate("playlist/$it") }) }
        composable("playlist/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { back ->
            PlaylistScreen(back.arguments?.getString("id").orEmpty(), onGone = { nav.popBackStack() })
        }
        composable(
            "artist?id={id}&name={name}",
            arguments = listOf(optional("id"), optional("name")),
        ) { back -> ArtistScreen(back.arguments?.getString("id"), back.arguments?.getString("name")) }
        composable(
            "album?id={id}&title={title}&artist={artist}",
            arguments = listOf(optional("id"), optional("title"), optional("artist")),
        ) { back -> AlbumScreen(back.arguments?.getString("id"), back.arguments?.getString("title"), back.arguments?.getString("artist")) }
        composable("downloads") { DownloadsScreen() }
        composable("settings") { SettingsScreen() }
        composable("player") { PlayerScreen(onClose = { nav.popBackStack() }) }
    }
}

private fun optional(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val c = appContainer()
    val live by c.liveNow.collectAsState()
    val track = state.track ?: return
    val title = if (state.live && live != null) live!!.title else track.title
    val subtitle = if (state.live && live != null) "${live!!.artist} · ${track.title}" else track.artist
    Surface(tonalElevation = 3.dp) {
        Column {
            if (!state.live && state.durationMs > 0) {
                LinearProgressIndicator(progress = { (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(if (state.live) live?.artworkUrl ?: track.artworkUrl else track.artworkUrl, 44.dp, corner = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { player.toggle() }) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play") }
                if (!state.live) IconButton(onClick = { player.next() }) { Icon(Icons.Rounded.SkipNext, "Next") }
            }
        }
    }
}
