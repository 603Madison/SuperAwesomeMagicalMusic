package com.music.connect.ui

import androidx.compose.foundation.layout.Arrangement
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
                "Now on ${network.networkClass.label}" + if (network.savingData) " with Data Saver" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QualityChoice("On Wi-Fi", settings.kbpsWifi, network.networkClass == NetworkClass.WIFI) { k -> update { it.copy(kbpsWifi = k) } }
            QualityChoice("On mobile data", settings.kbpsCellular, network.networkClass == NetworkClass.CELLULAR) { k -> update { it.copy(kbpsCellular = k) } }
            QualityChoice("On satellite", settings.kbpsSatellite, network.networkClass == NetworkClass.SATELLITE) { k -> update { it.copy(kbpsSatellite = k) } }
            Text(
                "Songs above the cap are converted down on your server, or a smaller copy is picked. On satellite the player also loads ten minutes ahead.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow("Even out volume", "Songs from different services play at about the same loudness.", settings.levelling) { v -> update { it.copy(levelling = v) } }
        }

        Panel("Downloads") {
            QualityChoice("Download quality", settings.kbpsDownload, false) { k -> update { it.copy(kbpsDownload = k) } }
            ToggleRow("Only on Wi-Fi", "Downloads wait for Wi-Fi or another unmetered network.", settings.downloadOnWifiOnly) { v -> update { it.copy(downloadOnWifiOnly = v) } }
        }

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
