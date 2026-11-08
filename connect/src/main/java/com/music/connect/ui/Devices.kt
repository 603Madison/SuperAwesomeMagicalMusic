package com.music.connect.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.connect.net.ConnectDevice
import com.music.connect.ui.theme.OnSitePurple
import com.music.connect.ui.theme.SitePurple
import kotlinx.coroutines.delay

/*
 * The person's devices, like Spotify Connect: what plays where, controlling another device from
 * this phone, and moving playback between them. Another device being in control shows in the
 * web player's purple, on the phone too (not the phone's own colours).
 */

/** The phone's icon for a kind of device. */
internal fun kindIcon(kind: String): ImageVector = when (kind) {
    "phone" -> Icons.Rounded.Smartphone
    "speaker" -> Icons.Rounded.Speaker
    else -> Icons.Rounded.Computer
}

/** A clock that ticks every second while [running] (positions of other devices move on their own). */
@Composable
private fun ticking(running: Boolean): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(running) {
        while (running) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    return now
}

/** Opens the devices sheet; in the app's colour while another device is playing. */
@Composable
fun DevicesButton() {
    val c = appContainer()
    val devices by c.devices.devices.collectAsState()
    var open by remember { mutableStateOf(false) }
    val elsewhere = c.devices.remote(devices)?.state?.playing == true
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("devices")) {
        Icon(Icons.Rounded.Devices, "Devices", tint = if (elsewhere) SitePurple else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) DevicesSheet { open = false }
}

/** The mini player while another device plays: what's on there, and its play/pause. */
@Composable
fun RemoteMiniPlayer(device: ConnectDevice) {
    val c = appContainer()
    val state = device.state ?: return
    val track = state.track ?: return
    var open by remember { mutableStateOf(false) }
    val now = ticking(state.playing)
    val position = remember(now, device) { c.devices.positionOf(device) }
    Surface(tonalElevation = 3.dp) {
        Column {
            // A band of purple says this controls another device; it fills as the song plays.
            LinearProgressIndicator(
                progress = { if (state.live || state.durationMs <= 0) 1f else (position.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = SitePurple,
                trackColor = SitePurple.copy(alpha = 0.3f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Row(
                Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp).testTag("remote-player"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(track.artworkUrl, 44.dp, corner = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(kindIcon(device.kind), null, Modifier.size(14.dp), tint = SitePurple)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Playing on ${device.name}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = SitePurple,
                        )
                    }
                }
                IconButton(onClick = { c.devices.command(device.id, if (state.playing) "pause" else "play") }) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play")
                }
                if (!state.live) IconButton(onClick = { c.devices.command(device.id, "next") }) { Icon(Icons.Rounded.SkipNext, "Next") }
            }
        }
    }
    if (open) DevicesSheet { open = false }
}

/**
 * The person's devices: the one playing elsewhere with its controls (seek, skip, volume), then
 * every device, this phone first. Choosing one moves playback there, from the same moment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(onDismiss: () -> Unit) {
    val c = appContainer()
    val devices by c.devices.devices.collectAsState()
    val active = devices.firstOrNull { it.active && it.state?.track != null }
    val remote = c.devices.remote(devices)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            remote?.let { RemoteControls(it) }
            Text(
                "Play on",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (devices.isEmpty()) {
                Text(
                    "Can't reach the server's device list right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            devices.sortedByDescending { it.id == c.devices.me }.forEach { device ->
                val here = device.id == c.devices.me
                val isActive = device === active
                DeviceRow(
                    device = device,
                    name = if (here) "This phone" else device.name,
                    playingHere = isActive,
                    // Something has to have played somewhere for there to be anything to move.
                    enabled = active != null && !isActive,
                ) {
                    c.devices.transfer(device.id)
                    onDismiss()
                }
            }
            if (devices.size == 1) {
                Text(
                    "Open the web player, or this app on another phone, signed in to your account, and it shows up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ConnectDevice, name: String, playingHere: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val accent = if (playingHere) SitePurple else MaterialTheme.colorScheme.onSurface
    val state = device.state
    val detail = when {
        playingHere && state?.track != null -> "${if (state.playing) "Playing" else "Paused"} · ${state.track.title}"
        device.kind == "phone" -> "Phone"
        device.kind == "speaker" -> "Speaker"
        else -> "Web player"
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp).testTag("device-${device.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kindIcon(device.kind), null, tint = accent)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = accent, fontWeight = if (playingHere) FontWeight.SemiBold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playingHere && state?.playing == true) Icon(Icons.Rounded.GraphicEq, "Playing", tint = SitePurple)
    }
}

/** Controls for the device playing elsewhere. */
@Composable
private fun RemoteControls(device: ConnectDevice) {
    val c = appContainer()
    val state = device.state ?: return
    val track = state.track ?: return
    val now = ticking(state.playing)
    // After a seek, the bar stays where it was let go until the device says where it is.
    var seeked by remember(device.state) { mutableStateOf<Pair<Long, Long>?>(null) }
    val reported = remember(now, device) { c.devices.positionOf(device) }
    val position = seeked?.takeIf { (_, at) -> now - at < 3_000 }?.let { (to, at) -> to + if (state.playing) now - at else 0 } ?: reported
    var volume by remember(device.id, state.volume) { mutableStateOf(state.volume.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.artworkUrl, 56.dp, corner = 10.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(kindIcon(device.kind), null, Modifier.size(14.dp), tint = SitePurple)
                    Spacer(Modifier.width(4.dp))
                    Text("Playing on ${device.name}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = SitePurple, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (!state.live && state.durationMs > 0) {
        Progress(position, state.durationMs, accent = SitePurple) { to ->
            seeked = to to SystemClock.elapsedRealtime()
            c.devices.command(device.id, "seek", positionMs = to)
        }
    } else {
        Spacer(Modifier.height(12.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (!state.live) IconButton(onClick = { c.devices.command(device.id, "previous") }) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
        Spacer(Modifier.width(12.dp))
        FilledIconButton(
            onClick = { c.devices.command(device.id, if (state.playing) "pause" else "play") },
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = SitePurple, contentColor = OnSitePurple),
        ) {
            Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play")
        }
        Spacer(Modifier.width(12.dp))
        if (!state.live) IconButton(onClick = { c.devices.command(device.id, "next") }) { Icon(Icons.Rounded.SkipNext, "Next") }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = volume,
            onValueChange = { volume = it },
            onValueChangeFinished = { c.devices.command(device.id, "volume", volume = volume.toDouble()) },
            colors = SliderDefaults.colors(thumbColor = SitePurple, activeTrackColor = SitePurple, inactiveTrackColor = SitePurple.copy(alpha = 0.24f)),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("remote-volume"),
        )
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
}
