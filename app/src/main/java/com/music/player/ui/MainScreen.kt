package com.music.player.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.music.player.lidarr.LidarrEvent
import com.music.player.model.Track
import com.music.player.quality.NetworkClass
import com.music.player.ui.components.AddToPlaylistDialog
import com.music.player.ui.components.BookmarkDialog
import com.music.player.ui.components.TrackInfoDialog
import com.music.player.ui.downloads.DownloadsScreen
import com.music.player.ui.player.MiniPlayer
import com.music.player.ui.player.NowPlayingSheet
import com.music.player.ui.playlists.PlaylistDetailScreen
import com.music.player.ui.playlists.PlaylistsScreen
import com.music.player.ui.radio.RadioScreen
import com.music.player.ui.search.AlbumScreen
import com.music.player.ui.search.ArtistScreen
import com.music.player.ui.search.SearchScreen
import com.music.player.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

private fun text(name: String) = navArgument(name) {
    type = NavType.StringType
    defaultValue = ""
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    SEARCH("search", "Search", Icons.Default.Search),
    PLAYLISTS("playlists", "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    RADIO("radio", "Radio", Icons.Default.Radio),
    DOWNLOADS("downloads", "Downloads", Icons.Default.Download),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
}

@Composable
fun MusicAppUi() {
    val container = LocalAppContainer.current
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var addToPlaylist by remember { mutableStateOf<List<Track>?>(null) }
    var infoFor by remember { mutableStateOf<Track?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    val network by container.networkMonitor.state.collectAsStateWithLifecycle()
    val settings by container.settingsState.collectAsStateWithLifecycle()
    val bookmarks by container.bookmarks.items.collectAsStateWithLifecycle()
    // Bookmarks put off with "Later" until the app is next opened.
    var bookmarksLater by remember { mutableStateOf(emptySet<String>()) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { bookmarksLater = emptySet() }

    val actions = remember {
        TrackActions(
            container = container,
            scope = scope,
            showAddToPlaylist = { addToPlaylist = it },
            showMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
            showInfo = { infoFor = it },
            showArtist = { name -> navController.navigate("artist?name=${Uri.encode(name)}") },
            showAlbum = { title, artist -> navController.navigate("album?title=${Uri.encode(title)}&artist=${Uri.encode(artist)}") },
        )
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) {
        merge(
            container.playbackErrors,
            container.lidarr.events.map { event ->
                when (event) {
                    is LidarrEvent.ArtistAdded -> "Added ${event.artist} to Lidarr"
                    is LidarrEvent.ArtistNotFound -> "Lidarr couldn't find ${event.artist}"
                    is LidarrEvent.Failed -> "Lidarr: ${event.message}"
                    is LidarrEvent.ArtistDownloaded -> "${event.artist}'s music is now in your library"
                }
            },
        ).collect { snackbar.showSnackbar(it) }
    }

    CompositionLocalProvider(
        LocalTrackActions provides actions,
        LocalArtworkAllowed provides (settings.loadArtworkOnSatellite || network.networkClass != NetworkClass.SATELLITE),
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    MiniPlayer(onExpand = { showPlayer = true })
                    BottomBar(navController)
                }
            },
        ) { padding ->
            NavHost(navController, startDestination = Tab.SEARCH.route, modifier = Modifier.padding(padding)) {
                composable(Tab.SEARCH.route) { SearchScreen() }
                composable(Tab.PLAYLISTS.route) {
                    PlaylistsScreen(
                        onOpen = { id -> navController.navigate("playlist/$id") },
                        onOpenHistory = { navController.navigate("history") },
                    )
                }
                composable("playlist/{id}") { entry ->
                    PlaylistDetailScreen(
                        playlistId = entry.arguments?.getString("id").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("history") { com.music.player.ui.history.HistoryScreen(onBack = { navController.popBackStack() }) }
                composable("artist?name={name}", arguments = listOf(text("name"))) { entry ->
                    ArtistScreen(entry.arguments?.getString("name").orEmpty(), onBack = { navController.popBackStack() })
                }
                composable("album?title={title}&artist={artist}", arguments = listOf(text("title"), text("artist"))) { entry ->
                    AlbumScreen(
                        entry.arguments?.getString("title").orEmpty(),
                        entry.arguments?.getString("artist").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Tab.RADIO.route) { RadioScreen() }
                composable(Tab.DOWNLOADS.route) { DownloadsScreen() }
                composable(Tab.SETTINGS.route) { SettingsScreen() }
            }
        }

        if (showPlayer) NowPlayingSheet(onDismiss = { showPlayer = false })
        infoFor?.let { track -> TrackInfoDialog(track, onDismiss = { infoFor = null }) }
        if (addToPlaylist == null && infoFor == null) {
            val waiting = bookmarks.filterNot { it.id in bookmarksLater }
            waiting.firstOrNull()?.let { bookmark ->
                BookmarkDialog(
                    bookmark = bookmark,
                    waiting = waiting.size,
                    onLater = { bookmarksLater = bookmarksLater + waiting.map { it.id } },
                    onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                )
            }
        }
        addToPlaylist?.let { tracks ->
            AddToPlaylistDialog(
                tracks = tracks,
                onDismiss = { addToPlaylist = null },
                onDone = { message ->
                    addToPlaylist = null
                    scope.launch { snackbar.showSnackbar(message) }
                },
            )
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController) {
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    NavigationBar {
        Tab.entries.forEach { tab ->
            val selected = route == tab.route ||
                (tab == Tab.PLAYLISTS && (route?.startsWith("playlist/") == true || route == "history")) ||
                (tab == Tab.SEARCH && (route?.startsWith("artist?") == true || route?.startsWith("album?") == true))
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}
