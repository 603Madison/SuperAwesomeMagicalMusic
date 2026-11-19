package com.music.mini.ui

import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.music.mini.BuildConfig
import com.music.mini.Prefs
import com.music.mini.R
import com.music.mini.net.AlbumSummary
import com.music.mini.net.ApiTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// The app's screens, apart from signing in (SignInScreens.kt) and Now playing (NowPlayingScreen.kt).

/** Plays [tracks] from [index] and opens Now playing. */
fun Screen.play(tracks: List<ApiTrack>, index: Int) {
    val chosen = tracks.getOrNull(index) ?: return
    if (chosen.isMissing) {
        mini.tell("That song isn't in any of your sources")
        return
    }
    val playable = tracks.filterNot { it.isMissing }
    mini.player.play(playable, playable.indexOf(chosen).coerceAtLeast(0))
    host.push(NowPlayingScreen())
}

/** Plays a favourite: a channel or station as it is, or a Pandora station from its first songs. */
fun Screen.playStation(station: ApiTrack) {
    if (!station.isPandoraStation) return play(listOf(station), 0)
    val via = station.via ?: return
    mini.tell("Starting ${station.title}…")
    scope.launch {
        try {
            val started = mini.api.station(via, station.sourceId.removePrefix(ApiTrack.PANDORA_STATION), station.title, more = false)
            if (started.tracks.isEmpty()) mini.tell("${station.title} has nothing to play right now") else play(started.tracks, 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mini.tell(problem(e))
        }
    }
}

/** A song in a list: OK plays the list from it; its options add it to a playlist and the like. */
fun Screen.songRow(tracks: List<ApiTrack>, index: Int, detail: String? = null): Row.Item {
    val track = tracks[index]
    val time = track.durationMs?.takeIf { it > 0 }?.let { " · ${duration(it)}" }.orEmpty()
    return Row.Item(
        title = track.title,
        detail = if (track.isMissing) "Not found in your sources" else detail ?: (track.artist + time),
        options = { songOptions(track) },
    ) { play(tracks, index) }
}

/** What can be done with a song, besides playing it. */
fun Screen.songOptions(track: ApiTrack): List<Option> = buildList {
    if (track.isSong && !track.isMissing) add(Option("Add to playlist") { host.push(AddToPlaylistScreen(track)) })
    if (!track.album.isNullOrBlank() && track.isSong) {
        add(Option("Go to the album") { host.push(TrackListScreen(track.album) { mini.api.album(null, track.album, track.artist).tracks }) })
    }
    if (track.artist.isNotBlank() && track.isSong) add(Option("Go to ${track.artist}") { host.push(ArtistScreen(null, track.artist)) })
}

fun Screen.albumRow(album: AlbumSummary): Row.Item = Row.Item(
    album.title,
    listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "),
) { host.push(TrackListScreen(album.title) { mini.api.album(album.id).tracks }) }

/** The first screen: a numbered menu of everything. */
class HomeScreen : ListScreen() {
    override val title: String get() = mini.prefs.serverName
    override val numbered = true

    private fun nowPlaying(): Row.Item {
        val track = mini.player.state.value.track
        val detail = track?.let { if (it.isLive) it.title else "${it.title} · ${it.artist}" } ?: "Nothing playing"
        return Row.Item("Now playing", detail) { host.push(NowPlayingScreen()) }
    }

    override fun onShown(first: Boolean) {
        if (first) {
            setRows(
                listOf(
                    nowPlaying(),
                    Row.Item("Search", "Songs, artists and albums") { host.push(SearchScreen()) },
                    Row.Item("Playlists") { host.push(PlaylistsScreen()) },
                    Row.Item("Radio", "Your stations and favourites") { host.push(RadioScreen()) },
                    Row.Item("Recently played") { host.push(RecentScreen()) },
                    Row.Item("Settings") { host.push(SettingsScreen()) },
                ),
            )
            scope.launch {
                mini.player.state.map { it.track?.key }.distinctUntilChanged().collect { updateRow(0, nowPlaying()) }
            }
        }
        super.onShown(first)
    }
}

/** A search box over the results; OK in the box searches, and down goes into the results. */
class SearchScreen : ListScreen() {
    override val title = "Search"
    override val hasOptions = true
    private lateinit var field: EditText
    private var fieldHadFocus = true

    override fun wrap(list: RecyclerView): View {
        val c = list.context
        field = EditText(c).apply {
            hint = "Artist, album or song"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(c.color(R.color.text))
            setHintTextColor(c.color(R.color.muted))
            setBackgroundResource(R.drawable.field_background)
            setPadding(c.dp(10), c.dp(8), c.dp(10), c.dp(8))
            setOnEditorActionListener { _, _, _ -> search(); true }
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(4), c.dp(4), c.dp(4), 0)
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun defaultFocus(): View = field

    override fun onShown(first: Boolean) {
        if (first || fieldHadFocus || rows.none { it is Row.Item }) field.requestFocus() else super.onShown(false)
    }

    override fun onHidden() {
        fieldHadFocus = field.hasFocus()
        super.onHidden()
    }

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (field.hasFocus()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    search()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (firstFocusable() >= 0) focusAt(firstFocusable())
                    return true
                }
            }
            return false
        }
        // Up from the first result goes back to the box.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && focusedIndex() == firstFocusable()) {
            field.requestFocus()
            return true
        }
        return super.onKey(keyCode, event)
    }

    override fun wrapsUp() = false

    fun search() {
        val query = field.text.toString().trim()
        if (query.isEmpty()) return
        (host as? MainActivity)?.hideKeyboard()
        load("Searching for \"$query\"…") {
            val results = mini.api.search(query)
            buildList {
                results.top?.let { top ->
                    when {
                        top.artist != null -> add(Row.Item(top.artist.name, "Artist") { host.push(ArtistScreen(top.artist.id, top.artist.name)) })
                        top.album != null -> add(albumRow(top.album).let { Row.Item(it.title, "Album · ${top.album.artist}", onClick = it.onClick) })
                        top.track != null -> add(songRow(listOf(top.track), 0, "Song · ${top.track.artist}"))
                    }
                }
                if (results.songs.isNotEmpty()) {
                    add(Row.Header("Songs"))
                    results.songs.indices.forEach { add(songRow(results.songs, it)) }
                }
                if (results.artists.isNotEmpty()) {
                    add(Row.Header("Artists"))
                    results.artists.forEach { artist -> add(Row.Item(artist.name) { host.push(ArtistScreen(artist.id, artist.name)) }) }
                }
                if (results.albums.isNotEmpty()) {
                    add(Row.Header("Albums"))
                    results.albums.forEach { add(albumRow(it)) }
                }
                if (isEmpty()) add(Row.Note("Nothing found for \"$query\""))
            }
        }
    }
}

class PlaylistsScreen : ListScreen() {
    override val title = "Playlists"

    override fun onShown(first: Boolean) {
        super.onShown(first)
        if (first) {
            load {
                mini.api.playlists().map { playlist ->
                    Row.Item(playlist.name, if (playlist.count == 1) "1 song" else "${playlist.count} songs") {
                        host.push(TrackListScreen(playlist.name) { mini.api.playlist(playlist.id).entries.map { it.track } })
                    }
                }.ifEmpty { listOf(Row.Note("No playlists yet. Make some in the web player or the phone app.")) }
            }
        }
    }
}

/** Songs (a playlist, an album): OK plays from the one chosen. */
class TrackListScreen(override val title: String, private val loader: suspend () -> List<ApiTrack>) : ListScreen() {
    override val hasOptions = true
    private var tracks: List<ApiTrack> = emptyList()

    override fun onShown(first: Boolean) {
        super.onShown(first)
        if (first) {
            load {
                tracks = loader()
                tracks.indices.map { songRow(tracks, it) }.ifEmpty { listOf(Row.Note("No songs here")) }
            }
        }
    }

    override fun screenOptions(): List<Option> = if (tracks.none { !it.isMissing }) emptyList() else listOf(
        Option("Play all") { play(tracks, tracks.indexOfFirst { !it.isMissing }) },
        Option("Shuffle") { tracks.filterNot { it.isMissing }.shuffled().let { play(it, 0) } },
    )
}

/** An artist: their best-known songs, then albums and singles. By a search's [id], or by [name]. */
class ArtistScreen(private val id: String?, private val name: String) : ListScreen() {
    override val title = name
    override val hasOptions = true

    override fun onShown(first: Boolean) {
        super.onShown(first)
        if (first) {
            load {
                val page = mini.api.artist(id, name)
                buildList {
                    if (page.topSongs.isNotEmpty()) {
                        add(Row.Header("Popular"))
                        page.topSongs.indices.forEach { add(songRow(page.topSongs, it)) }
                    }
                    if (page.albums.isNotEmpty()) {
                        add(Row.Header("Albums"))
                        page.albums.forEach { add(albumRow(it)) }
                    }
                    if (page.singles.isNotEmpty()) {
                        add(Row.Header("Singles"))
                        page.singles.forEach { add(albumRow(it)) }
                    }
                    if (isEmpty()) add(Row.Note("Nothing by ${page.artist.name} was found"))
                }
            }
        }
    }
}

/** The person's own stations (made by the server), then their favourite channels and stations. */
class RadioScreen : ListScreen() {
    override val title = "Radio"

    override fun onShown(first: Boolean) {
        super.onShown(first)
        if (first) {
            load {
                // Each part on its own: an older server without one still shows the other.
                val own = runCatching { mini.api.personalStations() }
                val favourites = runCatching { mini.api.liveFavorites() }
                if (own.isFailure && favourites.isFailure) throw own.exceptionOrNull()!!
                buildList {
                    val stations = own.getOrDefault(emptyList())
                    if (stations.isNotEmpty()) {
                        add(Row.Header("Your stations"))
                        stations.forEach { station ->
                            add(
                                Row.Item(station.name, station.tagline.ifBlank { null } ?: if (station.ready) null else "Getting ready") {
                                    play(listOf(station.track), 0)
                                },
                            )
                        }
                    }
                    add(Row.Header("Favourites"))
                    val liked = favourites.getOrDefault(emptyList())
                    if (liked.isEmpty()) add(Row.Note("No favourites yet. Star channels and stations in the web player or the phone app."))
                    liked.forEach { station ->
                        val kind = when {
                            station.isPandoraStation -> "Pandora station"
                            station.isChannel -> "SiriusXM"
                            else -> "Radio"
                        }
                        add(Row.Item(station.title, listOf(kind, station.artist).filter { it.isNotBlank() }.distinct().joinToString(" · ")) { playStation(station) })
                    }
                }
            }
        }
    }
}

/** What the person played lately, anywhere: songs play from the one chosen; stations play on their own. */
class RecentScreen : ListScreen() {
    override val title = "Recently played"

    override fun onShown(first: Boolean) {
        super.onShown(first)
        if (first) {
            load {
                val listens = mini.api.history().distinctBy { it.track.key }.take(60)
                val songs = listens.map { it.track }.filter { !it.isLive }
                val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                listens.map { listen ->
                    val track = listen.track
                    val detail = "${track.artist} · ${format.format(Date(listen.playedAt))}"
                    if (track.isLive) {
                        Row.Item(track.title, detail) { play(listOf(track), 0) }
                    } else {
                        songRow(songs, songs.indexOf(track), detail)
                    }
                }.ifEmpty { listOf(Row.Note("Nothing played yet")) }
            }
        }
    }
}

class SettingsScreen : ListScreen() {
    override val title = "Settings"
    override val numbered = true

    private fun rows(): List<Row> {
        val prefs = mini.prefs
        val sleep = mini.sleepAt.value
        return listOf(
            Row.Item("Stream quality", Prefs.qualityLabel(prefs.kbps)) { host.push(QualityScreen { refresh() }) },
            Row.Item("Sleep timer", sleepLabel(sleep)) { host.push(SleepScreen { refresh() }) },
            Row.Item("Sign out", "${prefs.userName} on ${prefs.server.removePrefix("https://")}") {
                host.push(
                    OptionsScreen(
                        "Sign out?",
                        listOf(
                            Option("Sign out") {
                                mini.player.pause()
                                scope.launch {
                                    mini.api.logout()
                                    mini.prefs.signOut()
                                    host.restart(ServerScreen())
                                }
                            },
                            Option("Stay signed in") {},
                        ),
                    ),
                )
            },
            Row.Note("SuperAwesomeMagicalMusic Mini ${BuildConfig.VERSION_NAME}"),
        )
    }

    override fun onShown(first: Boolean) {
        if (first) setRows(rows())
        super.onShown(first)
    }

    private fun refresh() = setRows(rows(), focusedIndex().takeIf { it >= 0 }, moveFocus = false)
}

fun sleepLabel(at: Long): String =
    if (at <= 0) "Off" else "Stops at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))}"

/** The stream quality: low by default, to save data. Takes effect from the next song. */
class QualityScreen(private val changed: () -> Unit) : ListScreen() {
    override val title = "Stream quality"
    override val numbered = true

    override fun onShown(first: Boolean) {
        if (first) {
            val current = mini.prefs.kbps
            setRows(
                Prefs.QUALITIES.map { (kbps, label) ->
                    Row.Item(label, if (kbps == current) "Chosen" else null) {
                        mini.prefs.kbps = kbps
                        mini.tell("From the next song: ${Prefs.qualityLabel(kbps)}")
                        changed()
                        host.pop()
                    }
                },
                moveFocus = false,
            )
        }
        super.onShown(first)
    }

    override fun initialFocus(): Int = Prefs.QUALITIES.indexOfFirst { it.first == mini.prefs.kbps }.coerceAtLeast(0)
}

/** Stops the music after a while. */
class SleepScreen(private val changed: () -> Unit = {}) : ListScreen() {
    override val title = "Sleep timer"
    override val numbered = true

    override fun onShown(first: Boolean) {
        if (first) {
            val choices = listOf(0 to "Off", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", 90 to "1½ hours")
            setRows(
                choices.map { (minutes, label) ->
                    Row.Item(label) {
                        mini.sleepIn(minutes)
                        mini.tell(if (minutes == 0) "Sleep timer off" else "The music stops in $label")
                        changed()
                        host.pop()
                    }
                },
            )
        }
        super.onShown(first)
    }
}

/** Adds [track] to one of the person's playlists. */
class AddToPlaylistScreen(private val track: ApiTrack) : ListScreen() {
    override val title = "Add to playlist"

    override fun onShown(first: Boolean) {
        super.onShown(first)
        if (first) {
            load {
                mini.api.playlists().map { playlist ->
                    Row.Item(playlist.name, "${playlist.count} songs") {
                        scope.launch {
                            try {
                                mini.api.addToPlaylist(playlist.id, listOf(track))
                                mini.tell("Added to ${playlist.name}")
                                host.pop()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                mini.tell(problem(e))
                            }
                        }
                    }
                }.ifEmpty { listOf(Row.Note("No playlists yet. Make one in the web player or the phone app.")) }
            }
        }
    }
}
