package com.music.connect.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.connect.net.Wrapped
import com.music.connect.net.WrappedShare
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** The family's violet to rose, the same in light and dark (white text sits on it). */
private val WrappedViolet = Color(0xFF5B3FD1)
private val WrappedRose = Color(0xFF8E3F5C)
private val WrappedGradient = Brush.linearGradient(listOf(WrappedViolet, WrappedRose))

private fun num(n: Number): String = NumberFormat.getIntegerInstance().format(n)

/** 0–23 as people say it. */
fun hourName(hour: Int): String = when (hour) {
    0 -> "midnight"
    12 -> "noon"
    in 1..11 -> "$hour am"
    else -> "${hour - 12} pm"
}

private fun peak(values: List<Long>): Int = values.indices.maxByOrNull { values[it] } ?: 0
private fun dayName(date: String): String = runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) }.getOrDefault(date)
private fun sourceName(name: String) = when (name) {
    "JELLYFIN" -> "Jellyfin"; "QOBUZ" -> "Qobuz"; "YOUTUBE_MUSIC" -> "YouTube Music"; "SIRIUSXM" -> "SiriusXM"; "RADIO" -> "Radio"; else -> name
}
private fun deviceName(name: String) = when (name) { "web" -> "Web player"; "android" -> "Phone app"; else -> name }
private val MONTHS = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
private val MONTH_NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** A banner that opens Wrapped (on Search's start). */
@Composable
fun WrappedBanner(onOpen: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(WrappedGradient)
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text("Your ${LocalDate.now().year} Wrapped", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Top songs, artists and stations, and your year's story", color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Color.White)
    }
}

/** Wrapped: the year in music, as a recap to look through and a story to tap through. */
@Composable
fun WrappedScreen() {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var data by remember { mutableStateOf<Wrapped?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var story by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(year) {
        error = null
        runCatching { c.api.wrapped(year, ZoneId.systemDefault().id) }
            .onSuccess { data = it }
            .onFailure { error = it.message ?: "Couldn't load your Wrapped" }
    }
    val save: (Wrapped) -> Unit = { w ->
        scope.launch {
            val name = "Your Top Songs ${w.year}"
            c.library.create(name, w.topSongs.map { it.track })
            c.tell("Saved “$name” with ${w.topSongs.size} songs")
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(LocalBack.current, "Wrapped")
        val shown = data
        when {
            shown == null && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shown == null -> Empty("Couldn't load your Wrapped", error, Icons.Rounded.CloudOff)
            else -> Recap(shown, onYear = { year = it }, onStory = { story = true }, onSave = { save(shown) })
        }
    }
    val shown = data
    if (story && shown != null) Story(shown, onClose = { story = false }, onSave = { save(shown) })
}

@Composable
private fun Recap(data: Wrapped, onYear: (Int) -> Unit, onStory: () -> Unit, onSave: () -> Unit) {
    val c = appContainer()
    val player = LocalPlayer.current
    val browse = LocalBrowse.current
    val playing by player.state.collectAsState()
    val downloaded by c.downloads.doneKeys.collectAsState(initial = emptySet())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (data.years.size > 1) item(key = "years") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(data.years) { y -> FilterChip(selected = y == data.year, onClick = { onYear(y) }, label = { Text("$y") }) }
            }
        }
        if (data.listens == 0) {
            item { Empty("Nothing to wrap for ${data.year} yet", "Songs you listen to for a while, and songs heard on live radio, build up your year here.", Icons.Rounded.AutoAwesome) }
            return@LazyColumn
        }
        item(key = "hero") { Hero(data, onStory, onSave) }
        val songs = data.topSongs.take(10)
        item(key = "songs") { SectionTitle("Top songs") }
        itemsIndexed(songs, key = { i, s -> "s$i-${s.track.key}" }) { i, s ->
            CardItem(i, songs.size) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Rank(i + 1)
                    Box(Modifier.weight(1f)) {
                        TrackRow(s.track, onClick = { player.play(songs.map { it.track }, i) }, current = playing.track?.key == s.track.key, downloaded = s.track.key in downloaded)
                    }
                }
            }
        }
        item(key = "artists") { SectionTitle("Top artists") }
        itemsIndexed(data.topArtists, key = { _, a -> "a-${a.name}" }) { i, a ->
            CardItem(i, data.topArtists.size) {
                Row(
                    Modifier.fillMaxWidth().clickable { browse?.artist(null, a.name) }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Rank(i + 1)
                    Artwork(a.artworkUrl, 48.dp, corner = 24.dp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${num(a.minutes)} min · ${num(a.plays)} plays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (data.topAlbums.isNotEmpty()) item(key = "albums") {
            SectionTitle("Top albums")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data.topAlbums, key = { "${it.title}-${it.artist}" }) { a ->
                    Column(Modifier.width(140.dp).clip(RoundedCornerShape(16.dp)).clickable { browse?.album(null, a.title, a.artist) }) {
                        Artwork(a.artworkUrl, 140.dp, corner = 16.dp)
                        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text("${a.artist} · ${num(a.minutes)} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item(key = "when") {
            SectionCard("When you listen", Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Mostly around ${hourName(peak(data.hours))}.", style = MaterialTheme.typography.bodyMedium)
                BarChart("By hour of the day", data.hours, label = { hourName(it) }, tick = { if (it % 6 == 0) listOf("12a", "6a", "12p", "6p")[it / 6] else "" })
                BarChart("By month", data.months, label = { MONTH_NAMES[it] }, tick = { MONTHS[it] })
            }
        }
        item(key = "where") {
            SectionCard("Where it came from", Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Shares(data.sources, ::sourceName)
                Text("On", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Shares(data.devices, ::deviceName)
                if (data.topStations.isNotEmpty()) {
                    Text("Live radio", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Shares(data.topStations) { it }
                }
            }
        }
        item(key = "highlights") {
            SectionTitle("Highlights")
            val tiles = buildList {
                data.biggestDay?.let { add(Triple("Biggest day", "${num(it.minutes)} min", "${dayName(it.date)}, ${it.listens} listens")) }
                add(Triple("Longest streak", "${data.longestStreak} ${if (data.longestStreak == 1) "day" else "days"}", "in a row with music"))
                data.onRepeat?.let { add(Triple("On repeat", "${it.plays}×", "${it.track.title} on ${dayName(it.date)}")) }
                data.firstSong?.let { add(Triple("First song of the year", it.track.title, it.track.artist)) }
                if (data.newArtists > 0) add(Triple("Discoveries", "${num(data.newArtists)} new artists", "Favourite find: ${data.discoveries.firstOrNull()?.name.orEmpty()}"))
                add(Triple("Your listening personality", data.personality.name, data.personality.description))
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(2).forEach { pair ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (label, value, note) ->
                            AppCard(Modifier.weight(1f).fillMaxHeight(), padding = 14.dp) {
                                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, letterSpacing = 0.8.sp)
                                Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 2.dp))
                                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Rank(n: Int) {
    Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(28.dp).padding(end = 2.dp))
}

@Composable
private fun Hero(data: Wrapped, onStory: () -> Unit, onSave: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(WrappedGradient)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Text("${data.year}${if (data.soFar) " so far" else ""}".uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = 0.85f))
            Text("${num(data.minutes)} minutes of music", style = MaterialTheme.typography.headlineMedium)
            Text("${num(data.songs)} songs · ${num(data.artists)} artists · ${num(data.days)} days with music", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
            if (data.listens > 0) {
                Surface(color = Color.White.copy(alpha = 0.18f), contentColor = Color.White, shape = RoundedCornerShape(50), modifier = Modifier.padding(top = 4.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(data.personality.name, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onStory, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF3B1F9E))) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play your story")
                }
                if (data.topSongs.isNotEmpty()) {
                    Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White)) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save top songs as a playlist")
                    }
                }
            }
        }
    }
}

/** One series of minutes as thin bars on a baseline; tapping a bar says its value. */
@Composable
private fun BarChart(title: String, values: List<Long>, label: (Int) -> String, tick: (Int) -> String) {
    if (values.isEmpty()) return
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val shownAt = selected ?: peak(values)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${label(shownAt)}: ${num(values[shownAt])} min", style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                val fraction = if (v <= 0) 0f else (v.toFloat() / max).coerceIn(0.04f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { selected = i }
                        .semantics { contentDescription = "${label(i)}: ${num(v)} minutes" },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (selected == null || selected == i) 1f else 0.5f)),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            values.indices.forEach { i ->
                Text(tick(i), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Shares of the year's minutes, largest first: name and value, with a bar below. */
@Composable
private fun Shares(items: List<WrappedShare>, name: (String) -> String) {
    val total = items.sumOf { it.minutes }.coerceAtLeast(1)
    val max = (items.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.take(6).forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text(name(s.name), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${num(s.minutes)} min · ${(s.minutes * 100 / total)}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))) {
                    Box(Modifier.fillMaxWidth(s.minutes.toFloat() / max).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

// ---- The story ----

private const val SLIDE_MS = 7000
private val BACKDROPS = listOf(
    Color(0xFF5B3FD1) to Color(0xFF8E3F5C),
    Color(0xFF1D1B6B) to Color(0xFF0F766E),
    Color(0xFF8E3F5C) to Color(0xFFD97706),
    Color(0xFF0B1220) to Color(0xFF5B3FD1),
    Color(0xFF0F766E) to Color(0xFF5B3FD1),
    Color(0xFF3B0764) to Color(0xFFBE185D),
)

/** The slides there's something to show for, in order. */
private fun slidesOf(data: Wrapped): List<String> = buildList {
    add("intro")
    add("minutes")
    if (data.topSongs.isNotEmpty()) add("song")
    if (data.topSongs.size > 1) add("songs")
    if (data.topArtists.isNotEmpty()) add("artist")
    if (data.topArtists.size > 1) add("artists")
    if (data.topStations.isNotEmpty()) add("station")
    if (data.newArtists > 0) add("discoveries")
    add("clock")
    add("streak")
    add("personality")
    add("summary")
}

@Composable
private fun Story(data: Wrapped, onClose: () -> Unit, onSave: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val slides = remember(data) { slidesOf(data) }
        var index by remember { mutableIntStateOf(0) }
        var paused by remember { mutableStateOf(false) }
        var held by remember { mutableStateOf(false) }
        val last = index == slides.lastIndex
        val progress = remember(index) { Animatable(0f) }
        // On to the next slide after a while, unless paused (the button, or holding a finger down).
        LaunchedEffect(index, paused, held) {
            if (last) {
                progress.snapTo(1f)
                return@LaunchedEffect
            }
            if (paused || held) return@LaunchedEffect
            progress.animateTo(1f, tween(((1f - progress.value) * SLIDE_MS).roundToInt().coerceAtLeast(1), easing = LinearEasing))
            index = (index + 1).coerceAtMost(slides.lastIndex)
        }
        val (from, to) = BACKDROPS[index % BACKDROPS.size]
        val top by animateColorAsState(from, tween(600), label = "top")
        val bottom by animateColorAsState(to, tween(600), label = "bottom")
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(top, bottom)))
                    .pointerInput(slides.size) {
                        detectTapGestures(
                            onPress = {
                                held = true
                                tryAwaitRelease()
                                held = false
                            },
                            onTap = { at ->
                                index = if (at.x < size.width / 3f) (index - 1).coerceAtLeast(0) else (index + 1).coerceAtMost(slides.lastIndex)
                            },
                        )
                    },
            ) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        slides.indices.forEach { i ->
                            val fill = when {
                                i < index -> 1f
                                i == index -> progress.value
                                else -> 0f
                            }
                            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
                                Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(Color.White))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                        if (!last) IconButton(onClick = { paused = !paused }) { Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Play" else "Pause") }
                        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") }
                    }
                    AnimatedContent(
                        targetState = index,
                        transitionSpec = { (fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 12 }) togetherWith fadeOut(tween(200)) },
                        label = "slide",
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { shown ->
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        ) { Slide(slides[shown], data) }
                    }
                    if (last) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (data.topSongs.isNotEmpty()) {
                                Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF3B1F9E))) {
                                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Save top songs as a playlist")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { index = 0 }) {
                                    Icon(Icons.Rounded.Replay, null, tint = Color.White)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Watch again", color = Color.White)
                                }
                                TextButton(onClick = onClose) { Text("Done", color = Color.White) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
}

@Composable
private fun Big(text: String) {
    Text(text, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
}

@Composable
private fun Huge(text: String) {
    Text(text, style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 88.sp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.92f), textAlign = TextAlign.Center)
}

@Composable
private fun ColumnScope.Slide(key: String, data: Wrapped) {
    val soFar = if (data.soFar) " so far" else ""
    when (key) {
        "intro" -> {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(48.dp))
            Eyebrow("${data.year}$soFar")
            Big("Your year in music")
            Body("Let's look back at what you played.")
        }
        "minutes" -> {
            Eyebrow("You listened for")
            Huge(num(data.minutes))
            Text("minutes", style = MaterialTheme.typography.headlineSmall)
            val days = data.minutes / 1440.0
            Body("That's ${if (days < 10) "%.1f".format(days).removeSuffix(".0") else num(days.roundToInt())} days of music: ${num(data.songs)} songs by ${num(data.artists)} artists, on ${num(data.days)} different days.")
        }
        "song" -> data.topSongs.first().let { s ->
            Eyebrow("Your song of the year")
            Artwork(s.track.artworkUrl, 220.dp, corner = 20.dp)
            Big(s.track.title)
            Body(s.track.artist)
            Text("Played ${num(s.plays)} ${if (s.plays == 1) "time" else "times"}", style = MaterialTheme.typography.titleMedium)
        }
        "songs" -> {
            Eyebrow("Your top songs")
            data.topSongs.take(5).forEachIndexed { i, s -> StoryRow(i + 1, s.track.artworkUrl, s.track.title, s.track.artist, round = false) }
        }
        "artist" -> data.topArtists.first().let { a ->
            Eyebrow("Your top artist")
            Artwork(a.artworkUrl, 220.dp, corner = 110.dp)
            Big(a.name)
            Body("${num(a.minutes)} minutes together${a.topSong?.let { ", most of all “$it”" }.orEmpty()}.")
        }
        "artists" -> {
            Eyebrow("Your top artists")
            data.topArtists.take(5).forEachIndexed { i, a -> StoryRow(i + 1, a.artworkUrl, a.name, "${num(a.minutes)} min", round = true) }
        }
        "station" -> data.topStations.first().let { s ->
            Eyebrow("Your station of the year")
            Big(s.name)
            val others = data.topStations.drop(1).take(2).joinToString(" and ") { it.name }
            Body("${num((s.minutes / 60.0).roundToInt())} hours tuned in${if (others.isNotEmpty()) ", then $others" else ""}.")
        }
        "discoveries" -> {
            Eyebrow("New to you")
            Huge(num(data.newArtists))
            Text(if (data.newArtists == 1) "artist discovered" else "artists discovered", style = MaterialTheme.typography.headlineSmall)
            data.discoveries.firstOrNull()?.let { Body("Your favourite find: ${it.name}.") }
        }
        "clock" -> {
            Eyebrow("Your music clock")
            Big("Peak time: ${hourName(peak(data.hours))}")
            val max = (data.hours.maxOrNull() ?: 0L).coerceAtLeast(1L)
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                data.hours.forEach { m ->
                    Box(Modifier.weight(1f).fillMaxHeight(if (m <= 0) 0f else (m.toFloat() / max).coerceIn(0.04f, 1f)).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(Color.White.copy(alpha = 0.9f)))
                }
            }
            data.biggestDay?.let { Body("Your biggest day was ${dayName(it.date)}, with ${num(it.minutes)} minutes.") }
        }
        "streak" -> {
            Eyebrow("Keeping it going")
            Huge("${data.longestStreak}")
            Text(if (data.longestStreak == 1) "day" else "days in a row", style = MaterialTheme.typography.headlineSmall)
            Body(data.onRepeat?.let { "And one day you played “${it.track.title}” ${it.plays} times. No judgement." } ?: "Your longest run of days with music.")
        }
        "personality" -> {
            Eyebrow("Your listening personality")
            Big(data.personality.name)
            Body(data.personality.description)
        }
        else -> Summary(data)
    }
}

@Composable
private fun StoryRow(rank: Int, art: String?, title: String, subtitle: String, round: Boolean) {
    Row(
        Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.width(8.dp))
        Artwork(art, 48.dp, corner = if (round) 24.dp else 10.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The last card: the year on one screen. */
@Composable
private fun Summary(data: Wrapped) {
    Column(
        Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.22f)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Eyebrow("${data.year}${if (data.soFar) " so far" else ""} Wrapped")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryList("Top artists", data.topArtists.take(5).map { it.name }, Modifier.weight(1f))
            SummaryList("Top songs", data.topSongs.take(5).map { it.track.title }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryFact("Minutes", num(data.minutes), Modifier.weight(1f))
            SummaryFact("Personality", data.personality.name, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryList(title: String, lines: List<String>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = Color.White.copy(alpha = 0.8f))
        lines.forEachIndexed { i, line -> Text("${i + 1}. $line", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun SummaryFact(title: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = Color.White.copy(alpha = 0.8f))
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}
