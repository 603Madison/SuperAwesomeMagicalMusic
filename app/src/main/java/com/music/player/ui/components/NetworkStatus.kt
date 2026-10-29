package com.music.player.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.player.quality.NetworkClass
import com.music.player.ui.LocalAppContainer

fun NetworkClass.icon(): ImageVector = when (this) {
    NetworkClass.WIFI -> Icons.Default.Wifi
    NetworkClass.CELLULAR -> Icons.Default.SignalCellularAlt
    NetworkClass.SATELLITE -> Icons.Default.SatelliteAlt
    NetworkClass.NONE -> Icons.Default.CloudOff
}

/** "Satellite · streaming at 64 kbps" */
@Composable
fun NetworkStatusRow(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val network by container.networkMonitor.state.collectAsStateWithLifecycle()
    val settings by container.settingsState.collectAsStateWithLifecycle()
    val networkClass = network.networkClass
    val text = if (networkClass == NetworkClass.NONE) {
        "Offline · downloaded songs only"
    } else {
        "${networkClass.label} · streaming at ${settings.quality.forNetwork(networkClass).label.lowercase()}"
    }
    Row(modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            networkClass.icon(),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
