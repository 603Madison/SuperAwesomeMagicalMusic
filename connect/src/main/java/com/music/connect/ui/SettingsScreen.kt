package com.music.connect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.connect.BuildConfig
import com.music.connect.data.Settings
import com.music.connect.data.SyncWorker
import com.music.player.quality.NetworkClass
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val c = appContainer()
    val settings by c.settings.collectAsState()
    val network by c.network.state.collectAsState()
    val pending by c.library.pendingCount.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    fun update(transform: (Settings) -> Settings) = scope.launch { c.prefs.update(transform) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Panel("Quality") {
            Text(
                "Now on ${network.networkClass.label}" + if (network.savingData) " (Android reports this app's data as restricted)" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QualityChoice("On Wi-Fi", settings.kbpsWifi, network.networkClass == NetworkClass.WIFI) { k -> update { it.copy(kbpsWifi = k) } }
            QualityChoice("On mobile data", settings.kbpsCellular, network.networkClass == NetworkClass.CELLULAR) { k -> update { it.copy(kbpsCellular = k) } }
            QualityChoice("On satellite", settings.kbpsSatellite, network.networkClass == NetworkClass.SATELLITE) { k -> update { it.copy(kbpsSatellite = k) } }
            QualityChoice("With Data Saver on", settings.kbpsDataSaver, network.savingData) { k -> update { it.copy(kbpsDataSaver = k) } }
            if (network.savingData && settings.kbpsDataSaver != 0) {
                Text(
                    "Android says this app's mobile data is restricted right now (Data Saver, or the app's own Background data setting), so songs are kept to ${settings.kbpsDataSaver} kbps at most. Choose Original for “With Data Saver on” to use your choices above anyway.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "Songs above the cap are converted down on your server, or a smaller copy is picked. On satellite the player also loads ten minutes ahead.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow("Even out volume", "Songs from different services play at about the same loudness.", settings.levelling) { v -> update { it.copy(levelling = v) } }
            ToggleRow(
                "Get YouTube Music on this phone",
                "YouTube often turns servers away, so the phone asks YouTube for the song itself, over its own connection. Your server's copy is used if that doesn't work.",
                settings.youtubeOnPhone,
            ) { v -> update { it.copy(youtubeOnPhone = v) } }
        }

        Panel("Downloads") {
            QualityChoice("Download quality", settings.kbpsDownload, false) { k -> update { it.copy(kbpsDownload = k) } }
            ToggleRow("Only on Wi-Fi", "Downloads wait for Wi-Fi or another unmetered network.", settings.downloadOnWifiOnly) { v -> update { it.copy(downloadOnWifiOnly = v) } }
        }

        Panel("Speakers") { SpeakerSettings(settings, ::update) }

        Panel("Account") {
            Text(settings.user?.displayName ?: "", style = MaterialTheme.typography.titleMedium)
            Text("${settings.user?.username} on ${settings.serverName.ifBlank { settings.server }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(settings.server, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pending > 0) {
                Text("$pending playlist changes waiting to be sent", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { SyncWorker.enqueue(c.context) }) { Text("Send now") }
            }
            Text(
                "Sources (your SiriusXM, Jellyfin…), devices and your password are managed on the server's web page.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
        }

        Text("SuperAwesomeMagicalMusic ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Downloads stay on the phone. Playlist changes not sent yet are lost.") },
            confirmButton = { TextButton(onClick = { scope.launch { c.signOut(); confirmSignOut = false } }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun QualityChoice(label: String, kbps: Int, inUse: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label + if (inUse) " (now)" else "", style = MaterialTheme.typography.bodyLarge)
        }
        Box {
            TextButton(onClick = { open = true }) { Text(qualityLabel(kbps)) }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                qualitySteps.forEach { k -> DropdownMenuItem(text = { Text(qualityLabel(k)) }, onClick = { open = false; onPick(k) }) }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = onChange)
    }
}

/** Yamaha MusicCast speakers: on or off, the ones found, and ones added by address. */
@Composable
private fun SpeakerSettings(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    val c = appContainer()
    val found by c.speakers.found.collectAsState()
    val searching by c.speakers.searching.collectAsState()
    val network by c.network.state.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    ToggleRow(
        "MusicCast speakers",
        "At home, Yamaha MusicCast speakers show up among your devices: anything you play, on the web or here, can play on them. The phone hands them each song, so it stays on the Wi-Fi meanwhile.",
        settings.speakers,
    ) { v -> update { it.copy(speakers = v) } }
    if (!settings.speakers) return
    when {
        network.networkClass != NetworkClass.WIFI -> Text("They're found when the phone is on Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        found.isEmpty() && !searching -> Text("None found on this Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    found.forEach { speaker ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("speaker-${speaker.deviceId}")) {
            Icon(Icons.Rounded.Speaker, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(speaker.name, style = MaterialTheme.typography.bodyLarge)
                Text(listOf(speaker.model, speaker.host).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val problem = c.speakers.problemOf(speaker.deviceId)?.collectAsState()?.value
                problem?.let { Text("Last problem: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    settings.speakerAddresses.forEach { address ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Added: $address", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { update { it.copy(speakerAddresses = it.speakerAddresses - address) } }) { Text("Remove") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { scope.launch { c.speakers.refresh() } }, enabled = !searching && network.networkClass == NetworkClass.WIFI) {
            Text(if (searching) "Searching…" else "Search again")
        }
        TextButton(onClick = { adding = true }) { Text("Add by address") }
    }
    if (adding) {
        var address by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a speaker") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Its IP address, from the MusicCast app (the speaker's Settings › Network) or your router.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(address, { address = it.trim() }, singleLine = true, placeholder = { Text("192.168.1.40") })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val added = address
                        update { it.copy(speakerAddresses = (it.speakerAddresses + added).distinct()) }
                        adding = false
                        scope.launch {
                            kotlinx.coroutines.delay(300)
                            c.speakers.refresh()
                        }
                    },
                    enabled = address.matches(Regex("[0-9A-Za-z.:\\[\\]-]{3,64}")),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
