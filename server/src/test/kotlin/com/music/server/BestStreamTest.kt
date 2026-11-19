package com.music.server

import com.music.player.sources.radio.RadioStation
import com.music.server.tuner.Blend
import com.music.server.tuner.Chooser
import com.music.server.tuner.PcmBuffer
import com.music.server.tuner.PcmInput
import com.music.server.tuner.PcmSink
import com.music.server.tuner.StationMatch
import com.music.server.tuner.TunerRadio
import com.music.server.tuner.TunerStation
import com.music.server.tuner.Via
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationMatchTest {
    private fun tuner(name: String, callLetters: String? = null, frequency: String = "97.1", hdProgram: Int? = null) =
        TunerStation(id = frequency, frequency = frequency, name = name, callLetters = callLetters, hdProgram = hdProgram, hd = hdProgram != null)

    private fun internet(name: String, tags: String = "", country: String = "US", hls: Boolean = false) =
        RadioStation(id = name.lowercase().filter { it.isLetterOrDigit() }, name = name, url = if (hls) "https://x.example/live.m3u8" else "https://x.example/live.mp3", countryCode = country, tags = tags)

    @Test
    fun `call letters make a station the same, in its name or its tags`() {
        val wxyz = tuner("WXYZ FM", "WXYZ")
        assertEquals(100, StationMatch.score(wxyz, internet("WXYZ-FM 97.1 The River"), "US"))
        assertEquals(100, StationMatch.score(wxyz, internet("The River", tags = "rock,wxyz,boston"), "US"))
        assertEquals(100, StationMatch.score(wxyz, internet("WXYZFM"), "US"))
        // Another station's call letters: not this one, even on the same frequency.
        assertEquals(0, StationMatch.score(wxyz, internet("WABC 97.1 FM"), "US"))
        // A station known by a name that looks like call letters: its name and frequency.
        assertTrue(StationMatch.score(tuner("KISS 108", "WXKS", frequency = "107.9"), internet("Kiss 108 FM 107.9 Boston"), "US") >= StationMatch.SAME)
        assertEquals(0, StationMatch.score(wxyz, internet("KQED"), "US"))
        // HLS streams aren't blended; and in the Americas, a station abroad isn't it.
        assertEquals(0, StationMatch.score(wxyz, internet("WXYZ-FM", hls = true), "US"))
        assertEquals(0, StationMatch.score(wxyz, internet("WXYZ-FM", country = "DE"), "US"))
    }

    @Test
    fun `HD2 is matched only by an HD2 stream, and the main station not by one`() {
        val main = tuner("WXYZ FM", "WXYZ")
        val hd1 = tuner("WXYZ HD1", "WXYZ", hdProgram = 1)
        val hd2 = tuner("WXYZ HD2", "WXYZ", hdProgram = 2)
        val stream = internet("WXYZ-FM")
        val stream2 = internet("WXYZ HD2 Jazz")
        assertEquals(100, StationMatch.score(main, stream, "US"))
        assertEquals(100, StationMatch.score(hd1, stream, "US"))
        assertEquals(0, StationMatch.score(hd2, stream, "US"))
        assertEquals(100, StationMatch.score(hd2, stream2, "US"))
        assertEquals(0, StationMatch.score(main, stream2, "US"))
        assertEquals(stream2, StationMatch.best(hd2, listOf(stream, stream2), "US"))
    }

    @Test
    fun `elsewhere, the name the station sends and its frequency`() {
        val r2 = tuner("BBC R2", frequency = "88.8")
        assertTrue(StationMatch.score(r2, internet("BBC R2 88.8", country = "GB"), "OTHER") >= StationMatch.SAME)
        assertTrue(StationMatch.score(tuner("JAM FM", frequency = "93.6"), internet("Jam FM 93,6 Berlin", country = "DE"), "OTHER") >= StationMatch.SAME)
        assertEquals(0, StationMatch.score(r2, internet("Radio Paradise", country = "GB"), "OTHER"))
        // A shared word alone isn't enough without the frequency.
        assertEquals(0, StationMatch.score(tuner("KISS FM", frequency = "98.0"), internet("Kiss Kiss Italia", country = "IT"), "OTHER"))
        assertEquals("WXYZ", StationMatch.query(tuner("WXYZ FM", "WXYZ")))
        assertEquals("BBC R2", StationMatch.query(r2))
    }

    @Test
    fun `tuner station ids`() {
        assertEquals("tuner:97.1-hd2~abc-123", TunerRadio.idOf("97.1-hd2", "abc-123"))
        assertEquals(TunerRadio.Id("97.1-hd2", "abc-123"), TunerRadio.parse("tuner:97.1-hd2~abc-123"))
        assertEquals(TunerRadio.Id("97.1", null), TunerRadio.parse("tuner:97.1"))
        assertNull(TunerRadio.parse("97.1"))
        assertNull(TunerRadio.parse("tuner:../../etc"))
        assertEquals("/api/live/tuner/src/image/97.1-hd2/art?v=ab12", TunerRadio.imagePath("src", "97.1-hd2", "art", "/api/stations/97.1-hd2/art?v=ab12"))
    }
}

class BestStreamTest {
    @Test
    fun `the chooser waits out a passing dip, and is more patient going back`() {
        val chooser = Chooser(clearAbove = 0.6, staticBelow = 0.35, toInternetMs = 6_000, toTunerMs = 20_000)
        assertEquals(Via.TUNER, chooser.start(0.9, tunerAvailable = true, internetAvailable = true))
        // Three seconds of static, then clear: stays.
        for (t in 0L..3_000L step 500) assertEquals(Via.TUNER, chooser.update(t, 0.1, true, true))
        assertEquals(Via.TUNER, chooser.update(3_500, 0.8, true, true))
        // Static for six seconds: the internet.
        for (t in 4_000L..9_500L step 500) assertEquals(Via.TUNER, chooser.update(t, 0.2, true, true))
        assertEquals(Via.INTERNET, chooser.update(10_000, 0.2, true, true))
        // Clear for a while, but not twenty seconds: stays on the internet.
        for (t in 10_500L..29_000L step 500) assertEquals(Via.INTERNET, chooser.update(t, 0.9, true, true))
        assertEquals(Via.INTERNET, chooser.update(29_500, 0.5, true, true))
        for (t in 30_000L..49_500L step 500) assertEquals(Via.INTERNET, chooser.update(t, 0.9, true, true))
        assertEquals(Via.TUNER, chooser.update(50_000, 0.9, true, true))
        // A tuner that stops: the internet at once.
        assertEquals(Via.INTERNET, chooser.update(50_500, 0.9, tunerAlive = false, internetAvailable = true))
        // Known to be static, it starts on the internet; with no internet copy, on the tuner regardless.
        assertEquals(Via.INTERNET, chooser.start(0.1, true, true))
        assertEquals(Via.TUNER, chooser.start(0.1, true, internetAvailable = false))
        assertEquals(Via.INTERNET, chooser.start(null, tunerAvailable = false, internetAvailable = true))
    }

    @Test
    fun `the ring buffer keeps whole frames and drops the oldest`() {
        val buffer = PcmBuffer(capacity = 16)
        buffer.write(ByteArray(12) { it.toByte() })
        buffer.write(ByteArray(8) { (100 + it).toByte() })
        assertEquals(16, buffer.size)
        val out = ByteArray(16)
        assertEquals(16, buffer.read(out))
        // The oldest frame (0..3) went to make room.
        assertEquals(4, out[0].toInt())
        assertEquals(107, out[15].toInt())
        buffer.write(ByteArray(7))
        assertEquals(4, buffer.read(ByteArray(16)))
    }

    /** A copy of a station that's always ready, with every sample [value]. */
    private class Steady(private val value: Short) : PcmInput {
        override val buffer = PcmBuffer()
        var open = true
        override val alive: Boolean get() = open

        fun top() {
            val block = ByteArray(Blend.bytesOf(Blend.BLOCK_MS))
            for (i in block.indices step 2) {
                block[i] = value.toInt().toByte()
                block[i + 1] = (value.toInt() shr 8).toByte()
            }
            while (buffer.size < Blend.bytesOf(1_000)) buffer.write(block)
        }

        override fun close() {
            open = false
        }
    }

    private class Captured : PcmSink {
        val blocks = mutableListOf<ByteArray>()
        override fun write(pcm: ByteArray) {
            blocks += pcm
        }
        override fun close() = Unit
    }

    private fun ByteArray.firstSample() = ((this[0].toInt() and 0xFF) or (this[1].toInt() shl 8)).toShort().toInt()
    private fun ByteArray.lastSample() = ((this[size - 4].toInt() and 0xFF) or (this[size - 3].toInt() shl 8)).toShort().toInt()

    @Test
    fun `a blend plays the tuner while it's clear, crossfades to the internet in static, and back when it clears`() {
        var now = 0L
        var score: Double? = 0.9
        val tuner = Steady(1_000)
        var internetOpened = 0
        var internet: Steady? = null
        val sink = Captured()
        val blend = Blend(
            name = "test",
            openTuner = { tuner },
            openInternet = {
                internetOpened++
                Steady(-1_000).also { internet = it }
            },
            tunerScore = { score },
            sink = sink,
            chooser = Chooser(toInternetMs = 6_000, toTunerMs = 20_000),
            clock = { now },
            fadeMs = 1_000,
        )
        fun run(ms: Long): ByteArray {
            var last = ByteArray(0)
            repeat((ms / Blend.BLOCK_MS).toInt()) {
                tuner.top()
                internet?.top()
                last = blend.step()
                now += Blend.BLOCK_MS
            }
            return last
        }
        assertEquals(Via.TUNER, blend.begin())
        assertEquals(1_000, run(2_000).firstSample())
        // Only the tuner is fetched while it's clear.
        assertEquals(0, internetOpened)

        score = 0.1
        assertEquals(1_000, run(5_000).firstSample())
        assertEquals(Via.TUNER, blend.via)
        // After six seconds of static, the switch: a crossfade through the middle.
        val during = run(1_500)
        val fade = sink.blocks.takeLast(60).map { it.firstSample() }
        assertTrue("crossfading: $fade", fade.any { it in -900..900 })
        assertEquals(1, internetOpened)
        assertEquals(-1_000, run(2_000).lastSample())
        assertEquals(Via.INTERNET, blend.via)
        assertTrue(during.isNotEmpty())
        // The tuner stays connected (to hear when it's clear).
        assertTrue(tuner.open)

        score = 0.9
        assertEquals(-1_000, run(15_000).lastSample())
        assertEquals(Via.INTERNET, blend.via)
        run(8_000)
        assertEquals(Via.TUNER, blend.via)
        assertEquals(1_000, run(1_000).lastSample())
        // Back on the tuner, the internet copy is let go.
        assertTrue(!internet!!.open)
    }

    @Test
    fun `a tuner that stops hands over to the internet straight away`() {
        var now = 0L
        val tuner = Steady(500)
        val internet = Steady(-500)
        val sink = Captured()
        val blend = Blend("test", { tuner }, { internet }, { 0.9 }, sink, clock = { now }, fadeMs = 200)
        blend.begin()
        repeat(50) {
            tuner.top(); internet.top()
            blend.step()
            now += Blend.BLOCK_MS
        }
        tuner.open = false
        repeat(50) {
            internet.top()
            blend.step()
            now += Blend.BLOCK_MS
        }
        assertEquals(Via.INTERNET, blend.via)
        assertEquals(-500, sink.blocks.last().lastSample())
    }
}
