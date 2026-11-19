package com.music.mini.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.music.mini.R
import com.music.mini.net.ApiTrack
import com.music.mini.net.PersonalNow
import com.music.mini.playback.MediaItems
import com.music.mini.playback.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * What's playing. OK plays and pauses, left and right go to the previous and next song (on the
 * person's own stations, right skips the song), up and down change the volume. The left soft key
 * has thumbs, adding to a playlist and the sleep timer.
 */
class NowPlayingScreen : Screen() {
    override val title = "Now playing"
    override val optionsLabel = "Options"
    override val centreLabel = "Play/Pause"
    private lateinit var root: View
    private lateinit var art: ImageView
    private lateinit var name: TextView
    private lateinit var artist: TextView
    private lateinit var progress: ProgressBar
    private lateinit var time: TextView
    private lateinit var status: TextView
    private lateinit var note: TextView
    private var artFor: String? = null
    private var watching: Job? = null
    private var watchingKey: String? = null
    private var clearNote: Job? = null

    /** What's on one of the person's own stations (it has its own songs, thumbs and skips). */
    private var personal: PersonalNow? = null
    /** What's on a radio station, when it says. */
    private var radioTitle: String? = null
    /** Thumbs given to Pandora station songs here, by song. */
    private val stationThumbs = mutableMapOf<String, String>()

    override fun create(context: Context): View {
        val c = context
        art = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            contentDescription = "Cover"
        }
        name = c.label("", 20f, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        artist = c.label("", 16f, muted = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        progress = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(c.color(R.color.accent))
        }
        time = c.label("", 15f).apply { gravity = Gravity.CENTER }
        status = c.label("", 15f, muted = true).apply { gravity = Gravity.CENTER }
        note = c.label("", 15f).apply {
            gravity = Gravity.CENTER
            setTextColor(c.color(R.color.focus))
        }
        // For touch screens; the keys do all of this without them.
        fun control(text: String, description: String, action: () -> Unit) = c.button(text, action).apply {
            isFocusable = false
            contentDescription = description
        }
        val controls = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val weight = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(control("⏮", "Previous") { mini.player.previous() }, weight)
            addView(control("⏯", "Play or pause") { mini.player.toggle() }, LinearLayout.LayoutParams(weight))
            addView(control("⏭", "Next") { mini.player.next() }, LinearLayout.LayoutParams(weight))
        }
        val column = c.column(art, name, artist, progress, time, status, note, controls)
        column.setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
        // The cover: as wide as the screen allows, but leaving room for the rest on a small screen.
        val metrics = c.resources.displayMetrics
        val side = minOf(metrics.widthPixels - c.dp(16), metrics.heightPixels / 3)
        (art.layoutParams as LinearLayout.LayoutParams).apply {
            width = side
            height = side
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root = ScrollView(c).apply {
            isFocusable = true
            addView(column)
        }
        return root
    }

    override fun defaultFocus(): View = root

    override fun onShown(first: Boolean) {
        super.onShown(first)
        if (first) scope.launch { mini.player.state.collect(::show) }
    }

    private fun show(state: PlayerState) {
        val track = state.track
        if (track == null) {
            name.text = "Nothing playing"
            artist.text = "Choose something in Search, Playlists or Radio."
            time.text = ""
            status.text = ""
            progress.visibility = View.GONE
            art.visibility = View.GONE
            return
        }
        watch(track)
        val on = personal?.now?.takeIf { track.isPersonal }
        name.text = on?.title ?: radioTitle?.takeIf { track.isRadio } ?: track.title
        artist.text = when {
            on != null -> "${on.artist} · ${track.title}"
            track.isRadio && radioTitle != null -> track.title
            track.isLive -> track.artist.ifBlank { "Live" }
            else -> listOfNotNull(track.artist, track.album).joinToString(" · ")
        }
        if (track.isLive) {
            progress.visibility = View.GONE
            time.text = "Live"
        } else {
            progress.visibility = View.VISIBLE
            progress.progress = if (state.durationMs > 0) (state.positionMs * 1000 / state.durationMs).toInt() else 0
            time.text = if (state.durationMs > 0) "${duration(state.positionMs)} / ${duration(state.durationMs)}" else duration(state.positionMs)
        }
        status.text = when {
            state.error != null -> state.error
            state.buffering -> "Loading…"
            state.playing -> "Playing"
            else -> "Paused"
        }
        loadArt(on?.artworkUrl ?: track.artworkUrl)
    }

    /** Keeps up with what's on, for the person's own stations and radio stations. */
    private fun watch(track: ApiTrack) {
        if (watchingKey == track.key) return
        watchingKey = track.key
        watching?.cancel()
        personal = null
        radioTitle = null
        if (!track.isPersonal && !track.isRadio) return
        val zone = java.util.TimeZone.getDefault().id
        watching = scope.launch {
            while (isActive) {
                try {
                    if (track.isPersonal) {
                        personal = mini.api.personalNow(track.sourceId, zone)
                    } else {
                        radioTitle = track.via?.let { mini.api.radioNow(it, track.sourceId).title }?.takeIf { it.isNotBlank() }
                    }
                    show(mini.player.state.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Not this time; it's asked again shortly.
                }
                delay(if (track.isPersonal) 10_000 else 30_000)
            }
        }
    }

    private fun loadArt(url: String?) {
        val address = MediaItems.artworkUrl(mini.prefs.server, url, 300)
        if (address == artFor) return
        artFor = address
        if (address == null) {
            art.visibility = View.GONE
            return
        }
        val side = (art.layoutParams?.width ?: 0).coerceAtLeast(64)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    mini.http.newCall(Request.Builder().url(address).build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        decode(response.body.bytes(), side)
                    }
                }.getOrNull()
            }
            if (artFor != address) return@launch
            if (bitmap != null) {
                art.setImageBitmap(bitmap)
                art.visibility = View.VISIBLE
            } else {
                art.visibility = View.GONE
            }
        }
    }

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        val state = mini.player.state.value
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> mini.player.toggle()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (state.live) say("Live radio can't go back") else mini.player.previous()
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                state.track?.isPersonal == true -> {
                    mini.player.next()
                    say("Skipping this song…")
                }
                state.hasNext -> mini.player.next()
                else -> say("That's the last one")
            }
            KeyEvent.KEYCODE_DPAD_UP -> volume(AudioManager.ADJUST_RAISE)
            KeyEvent.KEYCODE_DPAD_DOWN -> volume(AudioManager.ADJUST_LOWER)
            else -> return false
        }
        return true
    }

    private fun volume(direction: Int) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        say("Volume $now of $max")
    }

    /** A short note under the song, gone after a few seconds. */
    private fun say(text: String) {
        note.text = text
        clearNote?.cancel()
        clearNote = scope.launch {
            delay(2_500)
            note.text = ""
        }
    }

    override fun onOptions() {
        val state = mini.player.state.value
        val track = state.track
        val options = buildList {
            val station = personal?.takeIf { track?.isPersonal == true }
            if (track != null && station?.songKey != null) {
                add(Option(if (station.thumb == "UP") "Thumbs up (take it back)" else "Thumbs up: more like this") { personalThumb(track, station, "UP") })
                add(Option(if (station.thumb == "DOWN") "Thumbs down (take it back)" else "Thumbs down: never again") { personalThumb(track, station, "DOWN") })
            }
            if (track != null && track.isStationTrack && track.via != null) {
                add(Option("Thumbs up") { stationThumb(track, "UP") })
                add(Option("Thumbs down (and skip)") { stationThumb(track, "DOWN") })
            }
            // A song can go in a playlist: the one playing, or the one on one of the person's stations.
            val song = track?.takeIf { it.isSong } ?: station?.now?.song?.takeIf { it.isSong }
            if (song != null) addAll(songOptions(song))
            add(Option("Sleep timer: ${sleepLabel(mini.sleepAt.value)}") { host.push(SleepScreen()) })
        }
        host.push(OptionsScreen(track?.title ?: "Options", options))
    }

    private fun personalThumb(track: ApiTrack, station: PersonalNow, direction: String) {
        val key = station.songKey ?: return
        val next = if (station.thumb == direction) "NONE" else direction
        scope.launch {
            try {
                mini.api.personalThumb(track.sourceId, key, next)
                personal = station.copy(thumb = next.takeIf { it != "NONE" })
                mini.tell(
                    when (next) {
                        "UP" -> "Thumbs up: more like this"
                        "DOWN" -> "Thumbs down: it won't play again"
                        else -> "Thumb taken back"
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mini.tell(problem(e))
            }
        }
    }

    private fun stationThumb(track: ApiTrack, direction: String) {
        val via = track.via ?: return
        val (stationId, trackId) = MediaItems.split(track.sourceId.removePrefix("stationtrack:"))
        val next = if (stationThumbs[trackId] == direction) "NONE" else direction
        scope.launch {
            try {
                mini.api.stationThumb(via, stationId, trackId, next, mini.player.state.value.positionMs)
                stationThumbs[trackId] = next
                if (next == "DOWN") mini.player.next()
                mini.tell(if (next == "NONE") "Thumb taken back" else if (next == "UP") "Thumbs up" else "Thumbs down")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mini.tell(problem(e))
            }
        }
    }

    companion object {
        /** A picture made no bigger than needed: a 2000-pixel cover would use 16 MB as it is. */
        fun decode(bytes: ByteArray, side: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= side && bounds.outHeight / (sample * 2) >= side) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }
}
