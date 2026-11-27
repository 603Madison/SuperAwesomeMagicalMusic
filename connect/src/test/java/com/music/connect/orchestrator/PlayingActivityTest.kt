package com.music.connect.orchestrator

import com.music.connect.LiveNow
import com.music.connect.net.ApiTrack
import com.music.connect.orchestrator.PlayingActivity.Now
import com.music.connect.orchestrator.PlayingActivity.Step
import com.music.player.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What SuperAwesomeMagicalOrchestrator is told about what's playing, and when. */
class PlayingActivityTest {
    private val server = "https://music.example.com"
    private val song = ApiTrack(SourceType.JELLYFIN, "s1", "Silver Lining", "The Auroras", album = "Night & Day", via = "src1")
    private val other = ApiTrack(SourceType.JELLYFIN, "s2", "Slow Dawn", "The Auroras", album = "Night & Day", via = "src1")
    private val station = ApiTrack(SourceType.RADIO, "kexp", "KEXP", "Seattle", via = "radio")
    private val minute = 60_000L

    /** What the orchestrator was told, in order (null: stopped). */
    private val said = mutableListOf<Triple<String?, String, String>>()
    private var share = true
    private var time = 1_000_000L
    private val activity = PlayingActivity(
        share = { share },
        say = { title, detail, url -> said += Triple(title, detail, url) },
        scope = CoroutineScope(Dispatchers.Unconfined),
        dispatcher = Dispatchers.Unconfined,
        clock = { time },
    )

    private fun now(track: ApiTrack, live: LiveNow? = null) = PlayingActivity.now(server, track, live)

    @Test
    fun `the decision - once per song, again after ten minutes, never with the setting off`() {
        val a = now(song)
        assertEquals(Step.Tell, PlayingActivity.next(share = true, told = null, toldAt = 0, now = a, time = time))
        assertEquals(Step.Nothing, PlayingActivity.next(true, a, time, a, time + 9 * minute))
        assertEquals(Step.Tell, PlayingActivity.next(true, a, time, a, time + 10 * minute))
        assertEquals(Step.Tell, PlayingActivity.next(true, a, time, now(other), time + 1))
        assertEquals(Step.Nothing, PlayingActivity.next(false, null, 0, a, time))
        assertEquals(Step.Stop, PlayingActivity.next(false, a, time, a, time + 1))
    }

    @Test
    fun `a song is told once, however often the player says it's playing`() {
        repeat(5) { activity.playing(now(song)) }
        assertEquals(listOf(Triple<String?, String, String>("Silver Lining", "The Auroras", "$server/album?title=Night%20%26%20Day&artist=The%20Auroras")), said)
        activity.playing(now(other))
        assertEquals(2, said.size)
        assertEquals("Slow Dawn", said.last().first)
    }

    @Test
    fun `what keeps playing is said again every ten minutes, so it isn't forgotten`() {
        activity.playing(now(station))
        time += 9 * minute
        activity.playing(now(station))
        assertEquals(1, said.size)
        time += minute
        activity.playing(now(station))
        assertEquals(2, said.size)
        assertEquals(said[0], said[1])
        // The ten minutes count from the last telling.
        time += 9 * minute
        activity.playing(now(station))
        assertEquals(2, said.size)
    }

    @Test
    fun `stopping is said once, and only when something was told`() {
        activity.stopped()
        assertEquals(0, said.size)
        activity.playing(now(song))
        activity.stopped()
        activity.stopped()
        assertEquals(listOf("Silver Lining", null), said.map { it.first })
        assertEquals(Triple<String?, String, String>(null, "", ""), said.last())
        // Played again: told again.
        activity.playing(now(song))
        assertEquals(3, said.size)
    }

    @Test
    fun `with the setting off nothing is told, and turning it off says stopped`() {
        share = false
        activity.playing(now(song))
        assertEquals(0, said.size)
        share = true
        activity.playing(now(song))
        share = false
        activity.playing(now(song))
        assertEquals(listOf("Silver Lining", null), said.map { it.first })
        activity.playing(now(song))
        assertEquals(2, said.size)
    }

    @Test
    fun `a station that says what's on is told as the song, with the station`() {
        val live = LiveNow("Hyperballad", "Björk", null, null)
        assertEquals(Now("Hyperballad", "Björk · KEXP", "$server/radio"), now(station, live))
        assertEquals(Now("KEXP", "Seattle", "$server/radio"), now(station))
        // The next song on the station is a change worth telling.
        assertNotEquals(now(station, live), now(station, live.copy(title = "Jóga")))
        // A song isn't dressed up as live.
        assertEquals("Silver Lining", now(song, live).title)
    }

    @Test
    fun `the address opens something real in the web player`() {
        assertEquals("$server/radio", PlayingActivity.url("$server/", station))
        assertEquals("$server/radio", PlayingActivity.url(server, ApiTrack(SourceType.SIRIUSXM, "channel:hits1", "Hits 1", "SiriusXM")))
        assertEquals("$server/radio", PlayingActivity.url(server, ApiTrack(SourceType.SIRIUSXM, "stationtrack:st1:t9", "A song", "Someone", album = "An album")))
        assertEquals("$server/album?title=Night%20%26%20Day&artist=The%20Auroras", PlayingActivity.url(server, song))
        // No album to open: the player itself.
        assertEquals("$server/", PlayingActivity.url(server, song.copy(album = null)))
        assertEquals("$server/", PlayingActivity.url(server, song.copy(album = " ")))
    }
}
