package com.music.connect.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.music.connect.listen.MicRecorder
import com.music.connect.net.Heard
import com.music.connect.net.ListenResult
import com.music.connect.net.ListenStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Phase { IDLE, LISTENING, FOUND, MISSED }

/** When each try is sent (seconds of recording); the last one may also ask AudD. */
private val TRIES = listOf(4, 7, 10)

/**
 * Listen: what's playing around you. The microphone records for up to ten seconds; every few
 * seconds what's been heard so far goes to the server, which looks for it in your library (and,
 * last, AudD if you've added it), stopping as soon as it's sure.
 */
@Composable
fun ListenScreen(onBack: (() -> Unit)?, startNow: Boolean = false) {
    val c = appContainer()
    val player = LocalPlayer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var result by remember { mutableStateOf<ListenResult?>(null) }
    var heardAt by remember { mutableLongStateOf(0L) }
    var seconds by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<ListenStatus?>(null) }
    var heard by remember { mutableStateOf<List<Heard>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val mic = remember { MicRecorder(context) }

    LaunchedEffect(Unit) { status = runCatching { c.api.listenStatus() }.getOrNull() }
    LaunchedEffect(refresh) { heard = runCatching { c.api.listenHeard() }.getOrDefault(heard) }
    DisposableEffect(Unit) { onDispose { job?.cancel(); mic.stop() } }

    fun listen() {
        job?.cancel()
        result = null
        job = scope.launch {
            if (!mic.start()) {
                result = ListenResult(found = false, message = "The microphone couldn't be opened. Another app may be using it.")
                phase = Phase.MISSED
                return@launch
            }
            phase = Phase.LISTENING
            seconds = 0
            val began = System.currentTimeMillis()
            val ticker = launch { while (true) { seconds = ((System.currentTimeMillis() - began) / 1000).toInt().coerceAtMost(TRIES.last()); delay(250) } }
            try {
                var candidate: String? = null
                for ((i, at) in TRIES.withIndex()) {
                    delay((began + at * 1000L - System.currentTimeMillis()).coerceAtLeast(0))
                    val last = i == TRIES.lastIndex
                    if (last) mic.stop()
                    val recorded = System.currentTimeMillis()
                    val answer = try {
                        c.api.listen(mic.wav(), last, candidate)
                    } catch (e: Exception) {
                        if (!last) continue
                        result = ListenResult(found = false, message = e.message ?: "The server couldn't be asked")
                        phase = Phase.MISSED
                        return@launch
                    }
                    if (answer.found) {
                        mic.stop()
                        result = answer
                        heardAt = recorded
                        phase = Phase.FOUND
                        refresh++
                        return@launch
                    }
                    candidate = answer.candidate ?: candidate
                    if (last) {
                        result = answer
                        phase = Phase.MISSED
                    }
                }
            } finally {
                ticker.cancel()
                mic.stop()
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            listen()
        } else {
            result = ListenResult(found = false, message = "Listen needs the microphone. Allow it for this app in your phone's settings.")
            phase = Phase.MISSED
        }
    }

    fun tap() {
        if (phase == Phase.LISTENING) {
            job?.cancel()
            mic.stop()
            phase = Phase.IDLE
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(startNow) { if (startNow) tap() }

    LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { BackBar(onBack, "Listen") }
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("What's playing around you?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ListenButton(listening = phase == Phase.LISTENING, onClick = ::tap)
                Text(
                    when (phase) {
                        Phase.LISTENING -> "Listening… ${seconds}s"
                        Phase.IDLE -> "Tap to listen"
                        Phase.FOUND -> "Got it"
                        Phase.MISSED -> "Tap to try again"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                when {
                    phase == Phase.FOUND && result != null -> Found(result!!, onPlay = { song -> player.play(listOf(song)) }, onPlayFromHere = { song, at ->
                        player.playFrom(song, at + (System.currentTimeMillis() - heardAt))
                    })
                    phase == Phase.MISSED && result != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't tell what that was", style = MaterialTheme.typography.titleMedium)
                        result!!.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
                    }
                }
                status?.let { s ->
                    val library = when {
                        !s.libraries -> "No library to learn yet: add your Jellyfin on the server."
                        !s.enabled -> "Learning your library is turned off on the server."
                        s.total > 0 && s.learned < s.total -> "Learning your library: ${s.learned} of ${s.total} songs so far"
                        else -> "Knows all ${s.learned} songs in your library"
                    }
                    Text(
                        library + " · " + if (s.audd) "AudD for everything else" else "Add AudD on the server to recognise any song",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (heard.isNotEmpty()) item { SectionTitle("Recently heard", Modifier.fillMaxWidth()) }
        itemsIndexed(heard, key = { _, h -> h.id }) { i, h ->
            CardItem(i, heard.size, Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(h.artworkUrl ?: h.song?.artworkUrl, 48.dp, corner = 10.dp)
                    Column(Modifier.weight(1f)) {
                        Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${h.artist} · ${ago(h.at)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    h.song?.let { song -> IconButton(onClick = { player.play(listOf(song)) }) { Icon(Icons.Rounded.PlayArrow, "Play ${h.title}") } }
                    IconButton(onClick = { scope.launch { runCatching { c.api.forgetHeard(h.id) }; refresh++ } }) { Icon(Icons.Rounded.Close, "Remove") }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The big round button, pulsing while it listens. */
@Composable
private fun ListenButton(listening: Boolean, onClick: () -> Unit) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    val pulse = rememberInfiniteTransition(label = "listen")
    val grow by pulse.animateFloat(1f, 1.6f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        if (listening) {
            Box(Modifier.size(160.dp).scale(grow).alpha((1.6f - grow) / 0.6f * 0.45f).clip(CircleShape).background(Brush.linearGradient(colors)))
        }
        Box(
            Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(colors))
                .clickable(onClick = onClick)
                .semantics { contentDescription = if (listening) "Stop listening" else "Listen" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(64.dp))
        }
    }
}

/** What was recognised: its cover and name, and ways to play it (from where it is in the room, too). */
@Composable
private fun Found(result: ListenResult, onPlay: (com.music.connect.net.ApiTrack) -> Unit, onPlayFromHere: (com.music.connect.net.ApiTrack, Long) -> Unit) {
    val context = LocalContext.current
    AppCard(Modifier.padding(horizontal = 8.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Artwork(result.artworkUrl ?: result.song?.artworkUrl, 200.dp, corner = 20.dp)
            Spacer(Modifier.height(6.dp))
            Text(result.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(listOfNotNull(result.artist, result.album).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Text(if (result.via == "library") "From your library" else "Recognised by AudD", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val song = result.song
            if (song != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPlay(song) }) { Icon(Icons.Rounded.PlayArrow, null); Text("Play") }
                    result.positionMs?.let { at -> FilledTonalButton(onClick = { onPlayFromHere(song, at) }) { Icon(Icons.Rounded.SkipNext, null); Text("Play from here") } }
                }
            } else {
                Text("It isn't on any of your sources.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            result.link?.let { link ->
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp)); Text(" More")
                }
            }
        }
    }
}

/** "Just now", "12 min ago", "3 h ago", or days. */
private fun ago(at: Long): String {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 48 * 60 -> "Yesterday"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
