package com.music.player.ui.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.data.LidarrRequestEntity
import com.music.player.lidarr.LidarrReachability
import com.music.player.nextcloud.SignInState
import com.music.player.quality.StreamQuality
import com.music.player.settings.AppSettings
import com.music.player.settings.TranscodeCodec
import com.music.player.ui.appViewModel
import com.music.player.ui.components.NetworkStatusRow
import com.music.player.ui.components.formatBufferLength
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val viewModel = appViewModel { SettingsViewModel(it) }
    val settings by viewModel.current.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppearanceSection(settings, viewModel)
            QualitySection(settings, viewModel)
            BufferSection(settings, viewModel)
            PlaybackSection(settings, viewModel)
            CacheSection(settings, viewModel)
            ScrobblingSection(settings, viewModel)
            JellyfinSection(settings, viewModel)
            StreamingServicesSection(settings, viewModel)
            SiriusXmSection(settings, viewModel)
            LidarrSection(settings, viewModel)
            NextcloudSection(settings, viewModel)
            AboutSection(viewModel)
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SiriusXmSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val status by viewModel.siriusXmStatus.collectAsStateWithLifecycle()
    val config = settings.siriusXm
    Section("SiriusXM") {
        Hint(
            "Listen to SiriusXM channels in the Radio tab and save songs you hear to a playlist. " +
                "This uses SiriusXM's unofficial web API, which can stop working without notice.",
        )
        if (config.isConfigured) {
            Text("Signed in as ${config.username}")
            val clipboard = LocalClipboard.current
            val scope = rememberCoroutineScope()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::signOutSiriusXm) { Text("Sign out of SiriusXM") }
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("SiriusXM diagnostics", viewModel.siriusXmDiagnostics())))
                    }
                }) { Text("Copy diagnostics") }
            }
            Hint("If a channel misbehaves, play it, then copy the diagnostics and send them to whoever maintains the app.")
        } else {
            var username by rememberSaveable { mutableStateOf("") }
            var password by rememberSaveable { mutableStateOf("") }
            Field("E-mail or username", username, { username = it }, keyboardType = KeyboardType.Email)
            Field("Password", password, { password = it }, secret = true)
            ActionButton("Sign in to SiriusXM", status, enabled = username.isNotBlank() && password.isNotBlank()) {
                viewModel.signInSiriusXm(username, password)
            }
        }
        StatusText(status)
    }
}

@Composable
private fun AppearanceSection(settings: AppSettings, viewModel: SettingsViewModel) {
    Section("Appearance") {
        ToggleRow(
            "Bold text",
            "All text in Lexend Bold, the way your phone's Bold text setting draws it",
            settings.boldText,
            viewModel::setBoldText,
        )
    }
}

@Composable
private fun QualitySection(settings: AppSettings, viewModel: SettingsViewModel) {
    val quality = settings.quality
    Section("Streaming quality") {
        NetworkStatusRow()
        Hint(
            "Jellyfin transcodes to exactly the bitrate you pick. YouTube Music only offers fixed " +
                "levels, so it rounds down to the nearest one.",
        )
        QualitySlider("Wi-Fi", Icons.Default.Wifi, quality.wifi) { viewModel.setQuality(quality.copy(wifi = it)) }
        QualitySlider("Cellular", Icons.Default.SignalCellularAlt, quality.cellular) {
            viewModel.setQuality(quality.copy(cellular = it))
        }
        QualitySlider("Satellite", Icons.Default.SatelliteAlt, quality.satellite) {
            viewModel.setQuality(quality.copy(satellite = it))
        }
        QualitySlider("Data Saver", Icons.Default.DataSaverOn, quality.dataSaver) {
            viewModel.setQuality(quality.copy(dataSaver = it))
        }
        Hint(
            "While Android's Data Saver is on, music streams at the Data Saver bitrate or the network's own, " +
                "whichever is lower, even if this app is allowed unrestricted data. Like Data Saver itself, " +
                "this applies on metered connections (cellular, satellite, metered hotspots), not ordinary Wi-Fi.",
        )
        HorizontalDivider()
        QualitySlider("Downloads", Icons.Default.Download, quality.download) {
            viewModel.setQuality(quality.copy(download = it))
        }
        ToggleRow(
            "Download over cellular",
            "Otherwise downloads wait for Wi-Fi",
            settings.downloads.allowCellular,
        ) { viewModel.setDownloads(settings.downloads.copy(allowCellular = it)) }
        ToggleRow(
            "Download over satellite",
            "Uses your satellite data allowance",
            settings.downloads.allowSatellite,
        ) { viewModel.setDownloads(settings.downloads.copy(allowSatellite = it)) }
        ToggleRow(
            "Load artwork on satellite",
            "Album art costs data on slow satellite links",
            settings.loadArtworkOnSatellite,
            viewModel::setLoadArtworkOnSatellite,
        )
        Text("Jellyfin transcoding format", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TranscodeCodec.entries.forEach { codec ->
                FilterChip(
                    selected = settings.jellyfinTranscodeCodec == codec,
                    onClick = { viewModel.setTranscodeCodec(codec) },
                    label = { Text(codec.label) },
                )
            }
        }
    }
}

@Composable
private fun PlaybackSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val playback = settings.playback
    Section("Playback") {
        ToggleRow(
            "Even out volume",
            "Songs from Jellyfin, YouTube and SiriusXM play at about the same loudness",
            playback.levelVolume,
        ) { viewModel.setPlayback(playback.copy(levelVolume = it)) }
        ToggleRow(
            "Start SiriusXM songs from the beginning",
            "Tuning to a channel starts the song that's on from its start, not midway",
            playback.siriusXmFromSongStart,
        ) { viewModel.setPlayback(playback.copy(siriusXmFromSongStart = it)) }
        ToggleRow(
            "Save Pandora thumbs-ups",
            "A thumbs up on a station song also adds it to a \"Liked on Pandora\" playlist",
            playback.likedOnPandoraPlaylist,
        ) { viewModel.setPlayback(playback.copy(likedOnPandoraPlaylist = it)) }
        ToggleRow(
            "Resume in the car",
            "Start playing where you left off when Android Auto connects",
            playback.resumeInCar,
        ) { viewModel.setPlayback(playback.copy(resumeInCar = it)) }
        Text("Equalizer", style = MaterialTheme.typography.labelLarge)
        if (playback.equalizerPresets.isEmpty()) {
            Hint("Play something once to load your phone's equalizer presets.")
        } else {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = playback.equalizerPreset < 0,
                    onClick = { viewModel.setPlayback(playback.copy(equalizerPreset = -1)) },
                    label = { Text("Off") },
                )
                playback.equalizerPresets.forEachIndexed { index, name ->
                    FilterChip(
                        selected = playback.equalizerPreset == index,
                        onClick = { viewModel.setPlayback(playback.copy(equalizerPreset = index)) },
                        label = { Text(name) },
                    )
                }
            }
        }
        Text(
            "Bass boost: " + when {
                playback.bassBoost == 0 -> "off"
                playback.bassBoost < 400 -> "light"
                playback.bassBoost < 750 -> "medium"
                else -> "strong"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        var boost by remember(playback.bassBoost) { mutableStateOf(playback.bassBoost.toFloat()) }
        Slider(
            value = boost,
            onValueChange = { boost = it },
            onValueChangeFinished = { viewModel.setPlayback(playback.copy(bassBoost = boost.roundToInt())) },
            valueRange = 0f..1000f,
            steps = 9,
        )
        Hint("Equalizer apps on your phone (such as Wavelet) can also work on this app's sound.")
    }
}

@Composable
private fun CacheSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val cache = settings.cache
    val used by viewModel.cacheUsedBytes.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshCacheUsage() }
    Section("Dead-zone cache") {
        Hint(
            "Keeps the songs you play, and fetches the next few ahead of time while the connection is good, " +
                "so a dead zone or a stretch on satellite doesn't stop the music. Songs cached whole play with " +
                "no connection at all. Downloads are separate and never removed.",
        )
        ToggleRow("Cache songs", null, cache.enabled) { viewModel.setCache(cache.copy(enabled = it)) }
        if (cache.enabled) {
            Text("Space to use", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.music.player.settings.CacheSettings.SIZES_MB.forEach { mb ->
                    FilterChip(
                        selected = cache.sizeMb == mb,
                        onClick = { viewModel.setCache(cache.copy(sizeMb = mb)) },
                        label = { Text(if (mb < 1024) "$mb MB" else "${mb / 1024} GB") },
                    )
                }
            }
            Text("Songs to fetch ahead", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.music.player.settings.CacheSettings.SONGS_AHEAD.forEach { count ->
                    FilterChip(
                        selected = cache.songsAhead == count,
                        onClick = { viewModel.setCache(cache.copy(songsAhead = count)) },
                        label = { Text("$count") },
                    )
                }
            }
            ToggleRow(
                "Fetch ahead on cellular",
                "Otherwise only on Wi-Fi. Never on satellite, or while Data Saver is on",
                cache.onCellular,
            ) { viewModel.setCache(cache.copy(onCellular = it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${android.text.format.Formatter.formatShortFileSize(context, used)} used", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = viewModel::clearCache) { Text("Clear cache") }
        }
    }
}

@Composable
private fun ScrobblingSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val scrobbling = settings.scrobbling
    val status by viewModel.scrobblingStatus.collectAsStateWithLifecycle()
    val pending by viewModel.pendingScrobbles.collectAsStateWithLifecycle()
    Section("Scrobbling") {
        Hint(
            "Record what you listen to on ListenBrainz (or a server with its API, such as Maloja) and Last.fm. " +
                "Listens made offline are sent once you're back online.",
        )
        if (scrobbling.listenBrainzEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ListenBrainz: ${scrobbling.listenBrainzUrl.substringAfter("://")}", modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::disconnectListenBrainz) { Text("Disconnect") }
            }
        } else {
            var url by rememberSaveable { mutableStateOf(com.music.player.settings.ScrobbleSettings.DEFAULT_LISTENBRAINZ_URL) }
            var token by rememberSaveable { mutableStateOf("") }
            Field("ListenBrainz server", url, { url = it }, keyboardType = KeyboardType.Uri)
            Field("User token (from your ListenBrainz settings)", token, { token = it }, secret = true)
            ActionButton("Connect ListenBrainz", status, enabled = token.isNotBlank()) { viewModel.connectListenBrainz(url, token) }
        }
        HorizontalDivider()
        if (scrobbling.lastFmEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last.fm: ${scrobbling.lastFmUser}", modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::disconnectLastFm) { Text("Disconnect") }
            }
        } else {
            var key by rememberSaveable { mutableStateOf("") }
            var secret by rememberSaveable { mutableStateOf("") }
            var user by rememberSaveable { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            Hint("Last.fm needs an API key and secret of your own: create them at last.fm/api/account/create.")
            Field("API key", key, { key = it })
            Field("Shared secret", secret, { secret = it }, secret = true)
            Field("Username", user, { user = it })
            Field("Password (used once to sign in, not kept)", password, { password = it }, secret = true)
            ActionButton(
                "Sign in to Last.fm",
                status,
                enabled = key.isNotBlank() && secret.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
            ) { viewModel.signInLastFm(key, secret, user, password) }
        }
        StatusText(status)
        if (scrobbling.enabled) {
            ToggleRow(
                "Include SiriusXM and Pandora",
                "Also scrobble songs heard on channels and stations",
                scrobbling.includeRadio,
                viewModel::setScrobbleRadio,
            )
            if (pending > 0) Hint("$pending listens waiting to be sent")
        }
    }
}

@Composable
private fun AboutSection(viewModel: SettingsViewModel) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Section("About") {
        Text("Version ${viewModel.version}")
        Hint(
            "The app log records what Android Auto asks the app for and how it answers, and playback " +
                "errors. If something misbehaves (e.g. in the car), copy it and send it along.",
        )
        TextButton(onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Music app log", viewModel.appDiagnostics()))) }
        }) { Text("Copy app log") }
    }
}

@Composable
private fun BufferSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val steps = AppSettings.BUFFER_AHEAD_STEPS
    fun positionOf(seconds: Int) = steps.indexOfFirst { it >= seconds }.let { if (it < 0) steps.lastIndex else it }.toFloat()
    var position by remember(settings.bufferAheadSeconds) { mutableFloatStateOf(positionOf(settings.bufferAheadSeconds)) }
    val seconds = steps[position.roundToInt().coerceIn(steps.indices)]
    Section("Buffering") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.HourglassBottom, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Buffer ahead", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(formatBufferLength(seconds), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            valueRange = 0f..steps.lastIndex.toFloat(),
            steps = steps.size - 2,
            onValueChangeFinished = { viewModel.setBufferAheadSeconds(seconds) },
        )
        Hint(
            "How much music is loaded ahead of what you're hearing. When a song has fully loaded, the " +
                "next songs in the queue load too, so with a long buffer songs change seamlessly even " +
                "when a satellite or cellular link drops out. The buffer uses up to 64 MB of memory: " +
                "about 25 minutes at 320 kbps, 2 hours at 64 kbps, or 8 minutes of lossless audio.",
        )
    }
}

/** Positions 0-9 are the kbps steps; the last position is "Original". */
@Composable
private fun QualitySlider(label: String, icon: ImageVector, value: StreamQuality, onChange: (StreamQuality) -> Unit) {
    val steps = StreamQuality.STEPS
    fun positionOf(quality: StreamQuality) = when (quality) {
        StreamQuality.Original -> steps.size.toFloat()
        is StreamQuality.Capped -> steps.indexOf(quality.kbps).coerceAtLeast(0).toFloat()
    }
    fun qualityAt(position: Float): StreamQuality {
        val index = position.roundToInt()
        return if (index >= steps.size) StreamQuality.Original else StreamQuality.Capped(steps[index])
    }
    var position by remember(value) { mutableFloatStateOf(positionOf(value)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(qualityAt(position).label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            valueRange = 0f..steps.size.toFloat(),
            steps = steps.size - 1,
            onValueChangeFinished = { onChange(qualityAt(position)) },
        )
    }
}

@Composable
private fun JellyfinSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val status by viewModel.jellyfinStatus.collectAsStateWithLifecycle()
    val config = settings.jellyfin
    Section("Jellyfin (preferred source)") {
        if (config.isConfigured) {
            Text("Signed in to ${config.serverUrl} as ${config.username}")
            OutlinedButton(onClick = viewModel::signOutJellyfin) { Text("Sign out") }
        } else {
            var url by rememberSaveable { mutableStateOf(config.serverUrl) }
            var username by rememberSaveable { mutableStateOf(config.username) }
            var password by rememberSaveable { mutableStateOf("") }
            Field("Server URL", url, { url = it }, keyboardType = KeyboardType.Uri, placeholder = "https://jellyfin.example.com")
            Field("Username", username, { username = it })
            Field("Password", password, { password = it }, secret = true)
            ActionButton("Sign in", status, enabled = url.isNotBlank() && username.isNotBlank()) {
                viewModel.signInJellyfin(url, username, password)
            }
        }
        StatusText(status)
    }
}

@Composable
private fun StreamingServicesSection(settings: AppSettings, viewModel: SettingsViewModel) {
    Section("When Jellyfin doesn't have a song") {
        ToggleRow(
            "YouTube Music",
            "Stream songs from YouTube Music (no account needed)",
            settings.youTubeMusicEnabled,
            viewModel::setYouTubeMusicEnabled,
        )
    }
}

@Composable
private fun LidarrSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val status by viewModel.lidarrStatus.collectAsStateWithLifecycle()
    val options by viewModel.lidarrOptions.collectAsStateWithLifecycle()
    val queue by viewModel.lidarrQueue.collectAsStateWithLifecycle()
    val reachability by viewModel.lidarrReachability.collectAsStateWithLifecycle()
    val config = settings.lidarr
    Section("Lidarr") {
        Hint(
            "When you play or save a song that isn't in Jellyfin, its artist is looked up in Lidarr and " +
                "added (monitored) if Lidarr doesn't have them yet. Requests wait in a queue until " +
                "Lidarr can be reached, e.g. when you're back on your home network.",
        )
        var url by rememberSaveable(config.serverUrl) { mutableStateOf(config.serverUrl) }
        var apiKey by rememberSaveable(config.apiKey) { mutableStateOf(config.apiKey) }
        Field("Server URL", url, { url = it }, keyboardType = KeyboardType.Uri, placeholder = "http://lidarr.local:8686")
        Field("API key", apiKey, { apiKey = it }, secret = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(if (config.isConfigured) "Test & refresh" else "Connect", status, enabled = url.isNotBlank() && apiKey.isNotBlank()) {
                viewModel.connectLidarr(url, apiKey)
            }
            if (config.isConfigured) TextButton(onClick = viewModel::disconnectLidarr) { Text("Disconnect") }
        }
        StatusText(status)
        if (config.isConfigured) {
            ToggleRow("Add missing artists automatically", null, config.autoAddArtists) { enabled ->
                viewModel.updateLidarr { it.copy(autoAddArtists = enabled) }
            }
            ToggleRow("Search for albums when adding", null, config.searchForMissingAlbums) { enabled ->
                viewModel.updateLidarr { it.copy(searchForMissingAlbums = enabled) }
            }
            LidarrQueue(queue, reachability, viewModel)
            val waiting by viewModel.lidarrWaiting.collectAsStateWithLifecycle()
            if (waiting.isNotEmpty()) {
                Text("Waiting for Lidarr to download", style = MaterialTheme.typography.labelLarge)
                Hint(waiting.joinToString(", ") { it.artistName } + ". You'll get a note when their music arrives.")
            }
            options?.let { server ->
                Picker(
                    "Root folder",
                    config.rootFolderPath,
                    server.rootFolders.map { it.path to it.path },
                ) { value -> viewModel.updateLidarr { it.copy(rootFolderPath = value) } }
                Picker(
                    "Quality profile",
                    config.qualityProfileId,
                    server.qualityProfiles.map { it.id to it.name },
                ) { value -> viewModel.updateLidarr { it.copy(qualityProfileId = value) } }
                Picker(
                    "Metadata profile",
                    config.metadataProfileId,
                    server.metadataProfiles.map { it.id to it.name },
                ) { value -> viewModel.updateLidarr { it.copy(metadataProfileId = value) } }
            }
        }
    }
}

@Composable
private fun LidarrQueue(queue: List<LidarrRequestEntity>, reachability: LidarrReachability, viewModel: SettingsViewModel) {
    HorizontalDivider()
    Text(
        if (queue.isEmpty()) "No requests waiting" else "Waiting for Lidarr (${queue.size})",
        style = MaterialTheme.typography.labelLarge,
    )
    if (reachability is LidarrReachability.Unreachable) {
        Hint("Last try: ${reachability.reason}. They'll be sent when Lidarr can be reached.")
    }
    queue.forEach { request ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(request.artistName, style = MaterialTheme.typography.bodyMedium)
                Hint(listOfNotNull(request.trackTitle?.let { "from “$it”" }, request.lastError).joinToString(" · "))
            }
            IconButton(onClick = { viewModel.removeLidarrRequest(request.artistKey) }) {
                Icon(Icons.Default.Close, contentDescription = "Remove from queue")
            }
        }
    }
    if (queue.isNotEmpty()) TextButton(onClick = viewModel::retryLidarrQueue) { Text("Send now") }
}

@Composable
private fun NextcloudSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val status by viewModel.nextcloudStatus.collectAsStateWithLifecycle()
    val signIn by viewModel.nextcloudSignIn.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val config = settings.nextcloud
    // Open the login page as soon as Nextcloud provides it.
    LaunchedEffect(signIn) {
        (signIn as? SignInState.WaitingForBrowser)?.let { runCatching { uriHandler.openUri(it.loginUrl) } }
    }
    Section("Nextcloud") {
        Hint(
            "Your playlists belong to your Nextcloud account and are saved as JSON files in a folder of " +
                "your Nextcloud, kept in sync across devices. You sign in on Nextcloud's own page in the " +
                "browser, so single sign-on (e.g. authentik) works.",
        )
        var folder by rememberSaveable(config.folder) { mutableStateOf(config.folder) }
        if (config.isConfigured) {
            Text("Signed in as ${config.davUser} on ${config.serverUrl.substringAfter("://")}")
            Field("Folder", folder, { folder = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (folder.trim('/') != config.folder.trim('/')) {
                    ActionButton("Save folder", status, enabled = true) { viewModel.setNextcloudFolder(folder) }
                }
                TextButton(onClick = viewModel::syncNow, enabled = !status.running) { Text("Sync now") }
                TextButton(onClick = viewModel::disconnectNextcloud) { Text("Sign out") }
            }
            ToggleRow("Sync automatically", "After every change, and every few hours", config.autoSync, viewModel::setNextcloudAutoSync)
        } else {
            var url by rememberSaveable(config.serverUrl) { mutableStateOf(config.serverUrl) }
            Field("Server URL", url, { url = it }, keyboardType = KeyboardType.Uri, placeholder = "https://cloud.example.com")
            Field("Folder", folder, { folder = it })
            when (val state = signIn) {
                is SignInState.WaitingForBrowser -> {
                    Hint("Finish signing in in the browser, then come back here.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { uriHandler.openUri(state.loginUrl) }) { Text("Open sign-in page") }
                        TextButton(onClick = viewModel::cancelNextcloudSignIn) { Text("Cancel") }
                    }
                }
                SignInState.Starting -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else -> Button(onClick = { viewModel.signInToNextcloud(url, folder) }, enabled = url.isNotBlank()) {
                    Text("Sign in with browser")
                }
            }
            (signIn as? SignInState.Failed)?.let {
                Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        StatusText(status)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActionButton(label: String, status: ActionStatus, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !status.running) {
        if (status.running) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}

@Composable
private fun StatusText(status: ActionStatus) {
    val message = status.message ?: return
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (status.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Hint(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A labelled dropdown; `null` means "let the server decide". */
@Composable
private fun <T> Picker(label: String, selected: T?, options: List<Pair<T, String>>, onSelect: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: "Server default"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(selectedLabel) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Server default") }, onClick = { open = false; onSelect(null) })
                options.forEach { (value, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(value) })
                }
            }
        }
    }
}
