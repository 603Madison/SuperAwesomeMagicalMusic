package com.music.connect.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.nowplaying.NowPlaying

/**
 * Now Playing: on, and every minute or so the phone listens for a few seconds; music it hears
 * from your library shows in a quiet notification (and in Recently heard). Like Pixel phones'.
 */
@Composable
fun NowPlayingSettings() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(NowPlaying.isOn(context)) }
    var every by remember { mutableIntStateOf(NowPlaying.everySeconds(context)) }
    val status by NowPlaying.status.collectAsState()
    val current by NowPlaying.current.collectAsState()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true || NowPlaying.hasMicrophone(context)) on = NowPlaying.turnOn(context)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Now Playing", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Recognises music playing around you, by itself, and shows it in a notification and in Recently heard. " +
                        "Every so often the phone listens for a few seconds; only fingerprints of the sound go to your server, never the sound.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(on, onCheckedChange = { want ->
                if (!want) {
                    NowPlaying.turnOff(context)
                    on = false
                } else if (NowPlaying.hasMicrophone(context)) {
                    on = NowPlaying.turnOn(context)
                } else {
                    val wanted = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    ask.launch(wanted.toTypedArray())
                }
            })
        }
        if (on) {
            Text("Listens every", style = MaterialTheme.typography.bodyMedium)
            val choices = listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { i, (seconds, label) ->
                    SegmentedButton(
                        selected = every == seconds,
                        onClick = {
                            NowPlaying.setEverySeconds(context, seconds)
                            every = seconds
                            // Takes effect from the next listen: started again with the new pace.
                            NowPlaying.turnOff(context)
                            on = NowPlaying.turnOn(context)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = choices.size),
                        icon = {},
                    ) { Text(label) }
                }
            }
            val now = current
            Text(
                when {
                    now != null -> "Playing near you: ${now.title}, by ${now.artist}"
                    status == NowPlaying.Status.LISTENING -> "Listening…"
                    status == NowPlaying.Status.RECOGNISING -> "Heard music; looking it up…"
                    status == NowPlaying.Status.OFF -> "Paused: it carries on when the app is open"
                    else -> "Nothing recognised just now"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "It doesn't listen while the phone plays music itself, during calls, in battery saver, or when the battery is low. " +
                    "After the phone restarts, tap its notification (or open the app) to carry on: Android asks that.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
