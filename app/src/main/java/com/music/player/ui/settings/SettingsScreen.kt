package com.music.player.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.data.LidarrRequestEntity
import com.music.player.lidarr.LidarrReachability
import com.music.player.model.SourceType
import com.music.player.quality.StreamQuality
import com.music.player.settings.AppSettings
import com.music.player.settings.TranscodeCodec
import com.music.player.ui.appViewModel
import com.music.player.ui.components.NetworkStatusRow
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
            AccountSection(settings, viewModel)
            QualitySection(settings, viewModel)
            JellyfinSection(settings, viewModel)
            StreamingServicesSection(settings, viewModel)
            SiriusXmSection(settings, viewModel)
            LidarrSection(settings, viewModel)
            NextcloudSection(settings, viewModel)
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun AccountSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val status by viewModel.accountStatus.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.finishAuthentikSignIn(result.data)
    }
    LaunchedEffect(viewModel) { viewModel.signInIntents.collect { launcher.launch(it) } }
    val account = settings.account
    Section("Account (authentik)") {
        if (account != null) {
            Text("Signed in as ${account.displayName}")
            Hint("Your playlists are saved under your authentik user id (${account.subject}).")
            OutlinedButton(onClick = viewModel::signOutAuthentik) { Text("Sign out") }
        } else {
            Hint(
                "Sign in with authentik to keep playlists under your own account, including on Nextcloud. " +
                    "In authentik, create an OAuth2/OpenID provider (client type: Public) with the redirect URI " +
                    "com.music.player:/oauth2redirect.",
            )
            var issuer by rememberSaveable(settings.authentik.issuerUrl) { mutableStateOf(settings.authentik.issuerUrl) }
            var clientId by rememberSaveable(settings.authentik.clientId) { mutableStateOf(settings.authentik.clientId) }
            Field("Issuer URL", issuer, { issuer = it }, keyboardType = KeyboardType.Uri, placeholder = "https://auth.example.com/application/o/music/")
            Field("Client ID", clientId, { clientId = it })
            ActionButton("Sign in with authentik", status, enabled = issuer.isNotBlank() && clientId.isNotBlank()) {
                viewModel.startAuthentikSignIn(issuer, clientId)
            }
        }
        StatusText(status)
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
            OutlinedButton(onClick = viewModel::signOutSiriusXm) { Text("Sign out of SiriusXM") }
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
private fun QualitySection(settings: AppSettings, viewModel: SettingsViewModel) {
    val quality = settings.quality
    Section("Streaming quality") {
        NetworkStatusRow()
        Hint(
            "Jellyfin transcodes to exactly the bitrate you pick. Qobuz and YouTube Music only offer " +
                "fixed levels, so they round down to the nearest one (Qobuz can't go below 320 kbps, so " +
                "lower caps prefer another service when one is available).",
        )
        QualitySlider("Wi-Fi", Icons.Default.Wifi, quality.wifi) { viewModel.setQuality(quality.copy(wifi = it)) }
        QualitySlider("Cellular", Icons.Default.SignalCellularAlt, quality.cellular) {
            viewModel.setQuality(quality.copy(cellular = it))
        }
        QualitySlider("Satellite", Icons.Default.SatelliteAlt, quality.satellite) {
            viewModel.setQuality(quality.copy(satellite = it))
        }
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
    val status by viewModel.qobuzStatus.collectAsStateWithLifecycle()
    val qobuz = settings.qobuz
    Section("When Jellyfin doesn't have a song") {
        Text("Try first", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(SourceType.QOBUZ, SourceType.YOUTUBE_MUSIC).forEach { type ->
                FilterChip(
                    selected = settings.fallbackOrder.firstOrNull() == type,
                    onClick = { viewModel.preferFirst(type) },
                    label = { Text(type.label) },
                )
            }
        }
        HorizontalDivider()
        Text("Qobuz", style = MaterialTheme.typography.titleSmall)
        if (qobuz.isConfigured) {
            Text("Signed in${if (qobuz.username.isNotBlank()) " as ${qobuz.username}" else ""}")
            OutlinedButton(onClick = viewModel::signOutQobuz) { Text("Sign out of Qobuz") }
        } else {
            Hint(
                "Qobuz's API requires an app ID and secret issued to your application. Sign in with " +
                    "your e-mail and password, or paste a user auth token instead of the password.",
            )
            var appId by rememberSaveable { mutableStateOf(qobuz.appId) }
            var appSecret by rememberSaveable { mutableStateOf(qobuz.appSecret) }
            var username by rememberSaveable { mutableStateOf(qobuz.username) }
            var password by rememberSaveable { mutableStateOf("") }
            var token by rememberSaveable { mutableStateOf("") }
            Field("App ID", appId, { appId = it }, keyboardType = KeyboardType.Number)
            Field("App secret", appSecret, { appSecret = it }, secret = true)
            Field("E-mail or username", username, { username = it }, keyboardType = KeyboardType.Email)
            Field("Password", password, { password = it }, secret = true)
            Field("…or user auth token", token, { token = it }, secret = true)
            ActionButton(
                "Sign in to Qobuz",
                status,
                enabled = appId.isNotBlank() && appSecret.isNotBlank() &&
                    (token.isNotBlank() || (username.isNotBlank() && password.isNotBlank())),
            ) { viewModel.signInQobuz(appId, appSecret, username, password, token) }
        }
        StatusText(status)
        HorizontalDivider()
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
        Hint("Lidarr wasn't reachable when last tried: ${reachability.reason}")
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
    val config = settings.nextcloud
    Section("Nextcloud playlist sync") {
        Hint(
            "Playlists are saved as JSON files in a folder of your Nextcloud, and kept in sync across " +
                "devices. Create an app password in Nextcloud under Settings › Security.",
        )
        var url by rememberSaveable(config.serverUrl) { mutableStateOf(config.serverUrl) }
        var username by rememberSaveable(config.username) { mutableStateOf(config.username) }
        var password by rememberSaveable(config.appPassword) { mutableStateOf(config.appPassword) }
        var folder by rememberSaveable(config.folder) { mutableStateOf(config.folder) }
        Field("Server URL", url, { url = it }, keyboardType = KeyboardType.Uri, placeholder = "https://cloud.example.com")
        Field("Username", username, { username = it })
        Field("App password", password, { password = it }, secret = true)
        Field("Folder", folder, { folder = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(
                if (config.isConfigured) "Save & sync" else "Connect",
                status,
                enabled = url.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            ) { viewModel.connectNextcloud(url, username, password, folder) }
            if (config.isConfigured) {
                TextButton(onClick = viewModel::syncNow, enabled = !status.running) { Text("Sync now") }
                TextButton(onClick = viewModel::disconnectNextcloud) { Text("Disconnect") }
            }
        }
        StatusText(status)
        if (config.isConfigured) {
            ToggleRow("Sync automatically", "After every change, and every few hours", config.autoSync, viewModel::setNextcloudAutoSync)
        }
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
