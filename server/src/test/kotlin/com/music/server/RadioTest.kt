package com.music.server

import com.music.player.model.SourceType
import com.music.server.music.ApiTrack
import com.music.server.playlists.Listen
import com.music.server.radio.Candidate
import com.music.server.radio.Dj
import com.music.server.radio.Pcm
import com.music.server.radio.PcmSource
import com.music.server.radio.Played
import com.music.server.radio.Programme
import com.music.server.radio.Programmer
import com.music.server.radio.Rotation
import com.music.server.radio.StationKind
import com.music.server.radio.Stations
import com.music.server.radio.Taste
import com.music.server.radio.Transmitter
import com.music.server.radio.DjLine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class RadioTest {
    private val now = 1_800_000_000_000L
    private val day = Taste.DAY_MS

    private fun song(title: String, artist: String) = ApiTrack(SourceType.JELLYFIN, "id-$title", title, artist, durationMs = 200_000, via = "lib")

    private fun plays(track: ApiTrack, count: Int, daysAgo: Int) = (0 until count).map { Listen(track, now - (daysAgo + it) * day, 180_000, "web") }

    private val favourite = song("Neon Tidepool", "Velvet Satellites")
    private val old = song("Coastline Radio", "Juniper & The Hum")
    private val kept = song("Glasshouse Summer", "The Marigold Static")
    private val skipped = song("Paper Lanterns", "Velvet Satellites")

    private fun taste(thumbs: Map<String, Pair<Int, ApiTrack>> = emptyMap()) = Taste.build(
        plays(favourite, 6, 1) + plays(old, 8, 90) + Listen(skipped, now - day, 5_000, "web"),
        listOf("Summer" to listOf(kept)),
        thumbs,
        now,
    )

    @Test
    fun `a taste sorts songs into what's played now, what was loved, and what's kept`() {
        val taste = taste()
        assertEquals(listOf(favourite.title), taste.pool(Rotation.POWER, emptyList()).map { it.track.title })
        assertEquals(listOf(old.title), taste.pool(Rotation.RECURRENT, emptyList()).map { it.track.title })
        assertEquals(listOf(kept.title), taste.pool(Rotation.LIBRARY, emptyList()).map { it.track.title })
        // Five seconds of a song isn't a listen.
        assertNull(taste.known(Taste.songKeyOf(skipped)))
        // Artists: the one played lately first.
        assertEquals("Velvet Satellites", taste.artists.first().first)
        // Discovery: only songs not already known.
        val discovered = taste.pool(Rotation.DISCOVERY, listOf(favourite, song("Blue Hour", "Velvet Satellites")))
        assertEquals(listOf("Blue Hour"), discovered.map { it.track.title })
        assertFalse(taste.isEmpty)
        assertTrue(Taste.build(emptyList(), emptyList(), emptyMap(), now).isEmpty)
    }

    @Test
    fun `a thumbs down keeps a song off, and a thumbs up puts one in heavy rotation`() {
        val down = taste(mapOf(Taste.songKeyOf(favourite) to (-1 to favourite)))
        assertTrue(down.pool(Rotation.POWER, emptyList()).isEmpty())
        val up = taste(mapOf(Taste.songKeyOf(kept) to (1 to kept)))
        assertTrue(up.pool(Rotation.POWER, emptyList()).any { it.track.title == kept.title })
    }

    @Test
    fun `the programmer keeps songs and artists apart, and never repeats what's scheduled`() {
        val songs = (1..48).map { song("Song $it", "Artist ${it % 8}") }
        val taste = Taste.build(songs.flatMap { plays(it, 3, 2) }, emptyList(), emptyMap(), now)
        val programmer = Programmer(StationKind.MINE)
        val random = Random(7)
        val scheduled = mutableListOf<Candidate>()
        val played = mutableListOf<Played>()
        repeat(30) { position ->
            val pick = programmer.pick(taste, emptyList(), played, scheduled.takeLast(3), position, now + position * 240_000L, random)
            assertNotNull(pick)
            pick!!
            assertFalse("${pick.track.title} was just scheduled", scheduled.takeLast(3).any { it.key == pick.key })
            // Eight artists: the last three never come straight back.
            assertFalse("${pick.track.artist} again", scheduled.takeLast(3).any { it.artistKey == pick.artistKey })
            scheduled += pick
            played += Played("mine", pick.key, pick.artistKey, now + position * 240_000L)
        }
        // Nothing to play at all: nothing picked (the station says to listen first).
        assertNull(programmer.pick(Taste.build(emptyList(), emptyList(), emptyMap(), now), emptyList(), emptyList(), emptyList(), 0, now, random))
    }

    @Test
    fun `each station follows its own clock`() {
        assertTrue(Programmer(StationKind.REWIND).clock.count { it == Rotation.RECURRENT } > Programmer(StationKind.REWIND).clock.size / 2)
        assertTrue(Programmer(StationKind.DISCOVERY).clock.count { it == Rotation.DISCOVERY } > Programmer(StationKind.DISCOVERY).clock.size / 2)
        assertEquals("Madison’s Radio", Stations.nameOf(StationKind.MINE, "Madison Norton", "maddie"))
        assertEquals("maddie’s Rewind", Stations.nameOf(StationKind.REWIND, " ", "maddie"))
        assertEquals(StationKind.DISCOVERY, StationKind.of("hls:discovery"))
    }

    @Test
    fun `the DJ lets the first song play, talks every few songs, and gives the time on the hour`() {
        val dj = Dj(Random(3))
        val zone = ZoneId.of("America/New_York")
        val songs = (1..10).map { Candidate("k$it", "a$it", song("Song $it (feat. Someone)", "Artist $it"), 1.0, Rotation.POWER, null) }
        val ten = java.time.ZonedDateTime.of(2026, 11, 19, 21, 50, 0, 0, zone).toInstant().toEpochMilli()
        assertNull(dj.line("Madison’s Radio", StationKind.MINE, null, songs[0], songs.drop(1), ten, zone))
        val lines = (1..6).map { i -> dj.line("Madison’s Radio", StationKind.MINE, songs[i - 1], songs[i], songs.drop(i + 1), ten + i * 60_000L, zone) }
        val spoken = lines.filterNotNull()
        assertTrue("talked ${spoken.size} times", spoken.size in 1..3)
        assertTrue(spoken.none { "(feat." in it.text })
        val onTheHour = dj.line("Madison’s Radio", StationKind.MINE, songs[6], songs[7], songs.drop(8), ten + 11 * 60_000L, zone)
        assertNotNull(onTheHour)
        assertTrue(onTheHour!!.text, onTheHour.text.startsWith("It's 10:01 on Thursday night."))
        // A song skipped isn't announced as if it had played.
        repeat(20) { i ->
            val line = Dj(Random(i)).apply { repeat(3) { line("R", StationKind.MINE, songs[0], songs[1], emptyList(), ten, zone) } }
                .line("R", StationKind.MINE, songs[1], songs[2], emptyList(), ten + 60_000, zone, skipped = true)
            assertFalse(line?.text.orEmpty(), line?.text.orEmpty().contains("That was"))
        }
    }

    @Test
    fun `crossfades hold the level, and silence at a song's end is cut`() {
        val frames = 1000
        val loud = ByteArray(frames * 4) { if (it % 2 == 0) 0 else 0x40 }
        val quiet = ByteArray(frames * 4)
        val mixed = Pcm.crossfade(loud, quiet)
        fun sampleAt(bytes: ByteArray, frame: Int) = ((bytes[frame * 4].toInt() and 0xff) or (bytes[frame * 4 + 1].toInt() shl 8)).toShort().toInt()
        assertEquals(sampleAt(loud, 0), sampleAt(mixed, 0))
        assertEquals(0, sampleAt(mixed, frames - 1))
        val withSilence = loud.copyOf(loud.size + Pcm.bytesOf(2_000))
        val kept = Pcm.withoutTrailingSilence(withSilence)
        assertTrue(kept in loud.size..loud.size + Pcm.bytesOf(200))
        assertEquals(0, Pcm.withoutTrailingSilence(ByteArray(4_000)))
    }

    private fun ffmpegAvailable() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)

    /** A tone of [ms] at [hz], as a song would decode (made as it's read). */
    private class Tone(ms: Long, private val hz: Double, override val startMs: Long = 0) : PcmSource {
        private val frames = Pcm.framesOf(ms)
        private var frame = 0L
        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (frame >= frames) return -1
            val n = minOf((length / 4).toLong(), frames - frame).toInt()
            for (i in 0 until n) {
                val v = (sin(2 * PI * hz * (frame + i) / Pcm.RATE) * 8000).toInt()
                for (c in 0 until 2) {
                    into[offset + i * 4 + c * 2] = v.toByte()
                    into[offset + i * 4 + c * 2 + 1] = (v shr 8).toByte()
                }
            }
            frame += n
            return n * 4
        }
        override fun close() {}
    }

    @Test
    fun `a station goes on the air, sends every listener the same stream, and moves on when skipped`() = runBlocking {
        assumeTrue("ffmpeg isn't installed", ffmpegAvailable())
        val songs = (1..6).map { Candidate("k$it", "a$it", song("Song $it", "Artist $it").copy(durationMs = 90_000), 1.0, Rotation.POWER, null) }
        var picked = 0
        val aired = mutableListOf<String>()
        val programme = object : Programme {
            override suspend fun nextSong(scheduled: List<Candidate>) = songs[picked++ % songs.size]
            override suspend fun open(song: Candidate, fromMs: Long, exact: Boolean): PcmSource {
                if (song.key == "k3") throw java.io.IOException("not available")
                return Tone(90_000 - fromMs, 220.0 + song.key.last().digitToInt() * 110, fromMs)
            }
            override fun encoder(): Process = ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-f", "s16le", "-ar", "44100", "-ac", "2", "-i", "pipe:0",
                "-c:a", "aac", "-b:a", "128k", "-f", "adts", "pipe:1",
            ).start()
            override fun aired(now: Transmitter.OnAir, before: Transmitter.OnAir?, coming: List<Candidate>, atMs: Long, skipped: Boolean): DjLine? {
                synchronized(aired) { aired += now.song.key }
                return null
            }
        }
        val transmitter = Transmitter("u1", StationKind.MINE, "Test Radio", programme)
        transmitter.start()
        try {
            val (_, first) = transmitter.subscribe("one")
            val (_, second) = transmitter.subscribe()
            var bytes = 0
            withTimeout(15_000) {
                while (bytes < 40_000) {
                    val a = first.frames.receive()
                    val b = second.frames.receive()
                    assertTrue(a.contentEquals(b))
                    // Every frame is ADTS: its sync word first.
                    assertEquals(0xff, a[0].toInt() and 0xff)
                    bytes += a.size
                }
            }
            val before = transmitter.current!!.song.key
            // Joined mid-song: it started before it went on the air.
            assertTrue(transmitter.current!!.startMs < transmitter.startedAt)
            // A listener that says where it is is told what it hears; one it doesn't know isn't.
            val heard = transmitter.heardAt("one", 1_000)
            assertNotNull(heard)
            assertEquals(before, transmitter.songsFrom(heard!!).first().song.key)
            assertEquals(null, transmitter.heardAt("nobody", 1_000))
            assertEquals(3, transmitter.lined.size)
            transmitter.skip()
            withTimeout(15_000) {
                while (transmitter.current!!.song.key == before) {
                    first.frames.receive()
                    second.frames.receive()
                }
            }
            assertNotEquals(before, transmitter.current!!.song.key)
            // Song 3 wouldn't play: passed over.
            transmitter.skip()
            withTimeout(15_000) { while (synchronized(aired) { aired.size } < 3) first.frames.receive().also { second.frames.receive() } }
            assertFalse(synchronized(aired) { "k3" in aired })
            transmitter.unsubscribe(first)
            transmitter.unsubscribe(second)
            assertNotNull(transmitter.idleSince)
        } finally {
            transmitter.stop()
        }
    }

    @Test
    fun `a song the source stops sending part way through is picked up where it stopped`() = runBlocking {
        assumeTrue("ffmpeg isn't installed", ffmpegAvailable())
        val songs = (1..4).map { Candidate("k$it", "a$it", song("Song $it", "Artist $it").copy(durationMs = 60_000), 1.0, Rotation.POWER, null) }
        var picked = 0
        val opens = java.util.Collections.synchronizedList(mutableListOf<Triple<String, Long, Boolean>>())
        val programme = object : Programme {
            override suspend fun nextSong(scheduled: List<Candidate>) = songs[picked++ % songs.size]
            override suspend fun open(song: Candidate, fromMs: Long, exact: Boolean): PcmSource {
                opens += Triple(song.key, fromMs, exact)
                // The first time, the service drops it three seconds in.
                val length = if (opens.count { it.first == song.key } == 1) 3_000L else 60_000 - fromMs
                return Tone(length, 330.0, fromMs)
            }
            override fun encoder(): Process = ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-f", "s16le", "-ar", "44100", "-ac", "2", "-i", "pipe:0",
                "-c:a", "aac", "-b:a", "128k", "-f", "adts", "pipe:1",
            ).start()
            override fun aired(now: Transmitter.OnAir, before: Transmitter.OnAir?, coming: List<Candidate>, atMs: Long, skipped: Boolean): DjLine? = null
        }
        val transmitter = Transmitter("u1", StationKind.MINE, "Test Radio", programme)
        transmitter.start()
        try {
            val (_, listener) = transmitter.subscribe()
            withTimeout(15_000) { while (opens.size < 2) listener.frames.receive() }
            val first = opens[0]
            val again = opens[1]
            assertEquals(first.first, again.first)
            assertTrue("picked up at exactly where it stopped", again.third)
            assertTrue("picked up at ${again.second}, stopped at ${first.second + 3_000}", kotlin.math.abs(again.second - (first.second + 3_000)) < 200)
            assertEquals(first.first, transmitter.current!!.song.key)
        } finally {
            transmitter.stop()
        }
    }
}
