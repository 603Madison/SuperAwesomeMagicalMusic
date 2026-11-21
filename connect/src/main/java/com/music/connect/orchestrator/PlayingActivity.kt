package com.music.connect.orchestrator

import com.music.connect.LiveNow
import com.music.connect.net.ApiTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Carry on here": while music plays, SuperAwesomeMagicalOrchestrator on this phone is told what
 * it is (the song, who it's by, and a page on the server that opens it), so the person's other
 * devices can offer it; and told when it stops. Only while Settings › "Show what I'm playing on
 * my other devices" is on, once for each song (and again every ten minutes while the same thing
 * keeps playing: the orchestrator forgets after fifteen, and a long mix or a radio station
 * shouldn't drop off), and never on the player's thread: the orchestrator is another app, which
 * may ask its server.
 */
class PlayingActivity(
    /** Whether the person lets this be told (the setting). */
    private val share: () -> Boolean,
    /** Tells the orchestrator ([OrchestratorLink.publishActivity]); a null title: stopped. */
    private val say: (title: String?, detail: String, url: String) -> Unit,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What's playing, as the orchestrator is told it. */
    data class Now(val title: String, val detail: String, val url: String)

    /** What the orchestrator was last told is playing, if anything, and when. */
    private var told: Now? = null
    private var toldAt = 0L

    /** [now] is playing: called when a song starts, and now and then while it plays on. */
    @Synchronized
    fun playing(now: Now) {
        val time = clock()
        when (next(share(), told, toldAt, now, time)) {
            Step.Nothing -> Unit
            Step.Stop -> stopped()
            Step.Tell -> {
                told = now
                toldAt = time
                send(now.title, now.detail, now.url)
            }
        }
    }

    /** Playback stopped or the queue was cleared (or the setting was turned off). Said once. */
    @Synchronized
    fun stopped() {
        if (told == null) return
        told = null
        send(null, "", "")
    }

    private fun send(title: String?, detail: String, url: String) {
        scope.launch(dispatcher) { attempt { say(title, detail, url) } }
    }

    enum class Step { Nothing, Tell, Stop }

    companion object {
        /** Activities are forgotten after fifteen minutes: said again well before. */
        const val REFRESH_MS = 10L * 60 * 1000

        /**
         * What to do while [now] is playing at [time]: tell the orchestrator once per song; nothing
         * while it's what was already told, until [REFRESH_MS] has passed; and with the setting
         * off, nothing, or "stopped" if something was told before it was turned off.
         */
        fun next(share: Boolean, told: Now?, toldAt: Long, now: Now, time: Long): Step = when {
            !share -> if (told != null) Step.Stop else Step.Nothing
            told == now && time - toldAt < REFRESH_MS -> Step.Nothing
            else -> Step.Tell
        }

        /**
         * [track] as the orchestrator is told it. On a live channel or station that says what's on
         * ([live]), the song with the station after its artist.
         */
        fun now(server: String, track: ApiTrack, live: LiveNow? = null): Now {
            if (live != null && track.isLive && live.title.isNotBlank()) {
                val detail = listOf(live.artist, track.title).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")
                return Now(live.title, detail, url(server, track))
            }
            return Now(track.title, track.artist, url(server, track))
        }

        /**
         * A page of the web player that opens on another device: Radio for live channels and
         * stations (and songs from one), the album where the song names one (`/album?title=…&artist=…`,
         * as the web's `albumNamed`), else the player itself.
         */
        fun url(server: String, track: ApiTrack): String {
            val base = server.trimEnd('/')
            val album = track.album?.trim().orEmpty()
            return when {
                track.isLive || track.isStationTrack || track.isPandoraStation -> "$base/radio"
                album.isNotEmpty() && track.artist.isNotBlank() -> "$base/album?title=${enc(album)}&artist=${enc(track.artist.trim())}"
                else -> "$base/"
            }
        }

        private fun enc(text: String): String = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    }
}
