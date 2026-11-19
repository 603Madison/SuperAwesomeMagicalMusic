package com.music.tuner

import com.music.tuner.hd.HdDecoder
import com.music.tuner.hd.HdQuality
import com.music.tuner.hd.Nrsc5Event
import com.music.tuner.hd.Nrsc5Log
import com.music.tuner.radio.Devices
import com.music.tuner.rds.CallLetters
import com.music.tuner.rds.RdsDecoder
import com.music.tuner.rds.Redsea
import com.music.tuner.scan.RtlPower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CallLettersTest {
    @Test
    fun `PI codes spell call letters, K from 0x1000 and W from 0x54A8`() {
        assertEquals("KAAA", CallLetters.of(0x1000))
        assertEquals("KZZZ", CallLetters.of(0x54A7))
        assertEquals("WAAA", CallLetters.of(0x54A8))
        assertEquals("WZZZ", CallLetters.of(0x994F))
        // W, then B=1, U=20, R=17: 0x54A8 + 676 + 520 + 17.
        assertEquals("WBUR", CallLetters.of(0x54A8 + 1213))
        assertEquals("WBUR", CallLetters.of("0x5965"))
        for (letters in listOf("KQED", "WXYZ", "KEXP", "WFMU", "KCRW")) {
            assertEquals(letters, CallLetters.of(CallLetters.piOf(letters)!!))
        }
    }

    @Test
    fun `three-letter stations, codes with zeros, and networks`() {
        assertEquals("KGO", CallLetters.of(0x9954))
        assertEquals("WGN", CallLetters.of(0x9972))
        // A code with zero digits is sent with A and F in front: 0x1000 (KAAA) as AFA1.
        assertEquals(CallLetters.of(0x1000), CallLetters.of(0xAFA1))
        // P1 P2 0 0, sent as A F P1 P2.
        assertEquals(CallLetters.of(0x2300), CallLetters.of(0xAF23))
        // P1 0 P3 P4, sent as A P1 P3 P4.
        assertEquals(CallLetters.of(0x2045), CallLetters.of(0xA245))
        // Nationally linked (NPR, CBC) and Canadian/other codes don't spell anything here.
        assertNull(CallLetters.of(0xB001))
        assertNull(CallLetters.of(0xC123))
        assertNull(CallLetters.of(0x0123))
    }

    @Test
    fun `call letters from HD Radio station names`() {
        assertEquals("WXYZ", CallLetters.fromName("WXYZ-FM"))
        assertEquals("WXYZ", CallLetters.fromName("WXYZ-HD2"))
        assertEquals("KEX", CallLetters.fromName("KEX"))
        assertNull(CallLetters.fromName("97.1 The River"))
        assertNull(CallLetters.fromName(null))
    }
}

class RedseaTest {
    @Test
    fun `redsea's JSON lines`() {
        val group = Redsea.parse("""{"pi":"0xD3C2","group":"0A","ps":"MDR JUMP","di":{"dynamic_pty":true},"is_music":true,"prog_type":"Pop music","ta":false,"tp":false,"bler":12}""")!!
        assertEquals(0xD3C2, group.pi)
        assertEquals("MDR JUMP", group.ps)
        assertEquals("Pop music", group.progType)
        assertEquals(12, group.bler)
        val plus = Redsea.parse(
            """{"pi":"0xD3C2","group":"12A","prog_type":"Pop music","radiotext_plus":{"item_running":true,"item_toggle":1,"tags":[{"content-type":"item.title","data":"Das Leichteste der Welt"},{"content-type":"item.artist","data":"Silbermond"}]},"tp":false}""",
        )!!
        assertEquals("Das Leichteste der Welt", plus.rtPlus["item.title"])
        assertEquals("Silbermond", plus.rtPlus["item.artist"])
        assertEquals(1, plus.itemToggle)
        assertNull(Redsea.parse("redsea: warning: raw MPX sample rate not defined"))
        assertNull(Redsea.parse("{not json"))
    }

    @Test
    fun `RDS builds up the name, RadioText and the song, and a new item clears the old song`() {
        val rds = RdsDecoder(rbds = true)
        val wxyz = "0x%04X".format(CallLetters.piOf("WXYZ"))
        rds.accept("""{"pi":"$wxyz","group":"0A","ps":"WXYZ FM ","prog_type":"Rock","bler":0}""")
        rds.accept("""{"pi":"$wxyz","group":"0A","ps":"NOW: ","prog_type":"Rock"}""")
        rds.accept("""{"pi":"$wxyz","group":"0A","ps":"WXYZ FM ","prog_type":"Rock"}""")
        rds.accept("""{"pi":"$wxyz","group":"2A","radiotext":"Now playing Heroes by David Bowie","bler":8}""")
        rds.accept("""{"pi":"$wxyz","group":"12A","radiotext_plus":{"item_running":true,"item_toggle":0,"tags":[{"content-type":"item.title","data":"Heroes"},{"content-type":"item.artist","data":"David Bowie"}]}}""")
        var info = rds.info
        assertEquals("WXYZ", info.callLetters)
        assertEquals("WXYZ FM", rds.steadyName())
        assertEquals("Now playing Heroes by David Bowie", info.radiotext)
        assertEquals("Heroes", info.title)
        assertEquals("David Bowie", info.artist)
        assertEquals("Rock", info.genre)
        assertEquals(8, info.bler)
        rds.accept("""{"pi":"$wxyz","group":"12A","radiotext_plus":{"item_running":true,"item_toggle":1,"tags":[]}}""")
        info = rds.info
        assertNull(info.title)
        assertNull(info.artist)
    }

    @Test
    fun `outside the Americas the PI code isn't call letters`() {
        val rds = RdsDecoder(rbds = false)
        rds.accept("""{"pi":"0xC201","group":"0A","ps":"BBC R2  "}""")
        assertNull(rds.info.callLetters)
        assertEquals(0xC201, rds.info.pi)
    }
}

class Nrsc5Test {
    private val log = """
        12:00:01 Synchronized
        12:00:01 Frequency offset: -312 Hz
        12:00:02 MER: 11.5 dB (lower), 12.3 dB (upper)
        12:00:02 BER: 0.000050, avg: 0.000100, min: 0.000000, max: 0.000200
        12:00:03 Station name: WXYZ-FM
        12:00:03 Slogan: The Rock of the Valley
        12:00:03 Audio program 0: public, type: Rock, sound experience 0
        12:00:03 Audio program 1: public, type: Jazz, sound experience 0
        12:00:03 Audio program 2: restricted, type: Talk, sound experience 0
        12:00:04 SIG Service: type=audio number=1 name=WXYZ
        12:00:04   Audio component: id=0 port=0000 type=0 mime=4DC66C5A
        12:00:04   Data component: id=1 port=1000 service_data_type=0 type=3 mime=D9C72536
        12:00:04   Data component: id=2 port=1001 service_data_type=0 type=3 mime=BE4B7536
        12:00:04 SIG Service: type=audio number=2 name=WXYZ HD2 Jazz
        12:00:04   Data component: id=1 port=2000 service_data_type=0 type=3 mime=D9C72536
        12:00:05 Title: Heroes
        12:00:05 Artist: David Bowie
        12:00:05 Album: "Heroes"
        12:00:05 XHDR: 0 BE4B7536 42
        12:00:06 LOT file: port=1000 lot=7 name=logo.png size=4 mime=4F328CA0 expiry=2026-12-01T00:00:00Z
        12:00:07 LOT file: port=1001 lot=42 name=cover.jpg size=4 mime=1E653E9C expiry=2026-12-01T00:00:00Z
        12:00:08 Audio packet CRC mismatches: 2
        12:00:08 Audio bit rate: 96.3 kbps
    """.trimIndent()

    @Test
    fun `nrsc5's log lines`() {
        assertEquals(Nrsc5Event.Synchronized, Nrsc5Log.parse("12:00:01 Synchronized"))
        assertEquals(Nrsc5Event.Mer(11.5, 12.3), Nrsc5Log.parse("12:00:02 MER: 11.5 dB (lower), 12.3 dB (upper)"))
        assertEquals(Nrsc5Event.Ber(0.00005, 0.0001), Nrsc5Log.parse("12:00:02 BER: 0.000050, avg: 0.000100, min: 0.000000, max: 0.000200"))
        assertEquals(Nrsc5Event.Xhdr(0, 0xBE4B7536L, 42), Nrsc5Log.parse("12:00:05 XHDR: 0 BE4B7536 42"))
        assertEquals(Nrsc5Event.LotFile(0x1000, 7, "logo.png", 4, 0x4F328CA0L), Nrsc5Log.parse("12:00:06 LOT file: port=1000 lot=7 name=logo.png size=4 mime=4F328CA0 expiry=2026-12-01T00:00:00Z"))
        assertEquals(Nrsc5Event.SigData(0x1001, 0xBE4B7536L), Nrsc5Log.parse("12:00:04   Data component: id=2 port=1001 service_data_type=0 type=3 mime=BE4B7536"))
        assertEquals(Nrsc5Event.AudioProgram(1, true, "Jazz"), Nrsc5Log.parse("12:00:03 Audio program 1: public, type: Jazz, sound experience 0"))
        assertTrue(Nrsc5Log.parse("12:00:00 Open device failed.") is Nrsc5Event.Fatal)
        assertNull(Nrsc5Log.parse("12:00:01 Frequency offset: -312 Hz"))
    }

    @Test
    fun `an HD1 program's names, song, logo and album art from the log`() {
        val dir = Files.createTempDirectory("hd").toFile()
        dir.resolve("7_logo.png").writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
        dir.resolve("42_cover.jpg").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0))
        val hd = HdDecoder(program = 0, filesDir = dir)
        log.lines().forEach { hd.accept(it) }
        val info = hd.info
        assertTrue(info.synced)
        assertEquals("WXYZ-FM", info.stationName)
        assertEquals("WXYZ", info.programName)
        assertEquals("The Rock of the Valley", info.slogan)
        assertEquals("Heroes", info.title)
        assertEquals("David Bowie", info.artist)
        assertEquals("\"Heroes\"", info.album)
        assertEquals(mapOf(0 to "Rock", 1 to "Jazz"), info.programs)
        assertEquals(dir.resolve("7_logo.png"), info.logo)
        assertEquals(dir.resolve("42_cover.jpg"), info.art)
        assertEquals(11.5, info.mer!!, 0.01)
        assertEquals(2, info.audioErrors)
        val score = HdQuality.score(info)!!
        assertTrue("good but with lost packets: $score", score in 0.7..0.9)

        // HD2 gets its own logo port, and not HD1's cover.
        val hd2 = HdDecoder(program = 1, filesDir = dir)
        log.lines().forEach { hd2.accept(it) }
        assertEquals("WXYZ HD2 Jazz", hd2.info.programName)
        assertNull(hd2.info.art)
        assertNull(hd2.info.logo)

        // Losing the signal: nothing to hear.
        hd.accept("12:01:00 Lost synchronization")
        assertEquals(0.0, HdQuality.score(hd.info)!!, 0.0)
        // A new song clears the last one's artist, and XHDR 1 goes back to the logo.
        hd.accept("12:01:05 Title: Station ID")
        hd.accept("12:01:05 XHDR: 1 BE4B7536 -1")
        assertEquals("Station ID", hd.info.title)
        assertNull(hd.info.artist)
        assertNull(hd.info.art)
    }

    @Test
    fun `what nrsc5 really logs, from its own sample recording (KUT, Austin)`() {
        val hd = HdDecoder(program = 0, filesDir = null)
        javaClass.getResourceAsStream("/nrsc5-sample.log")!!.bufferedReader().forEachLine { hd.accept(it) }
        val info = hd.info
        assertTrue(info.synced)
        assertEquals("KUT", info.stationName)
        assertEquals("KUT", CallLetters.fromName(info.stationName))
        assertEquals("The University of Texas at Austin", info.slogan)
        assertEquals("You're Listening to Q with Jian Ghomeshi", info.title)
        // It sends an empty artist: that's no artist.
        assertNull(info.artist)
        assertEquals(setOf(0, 1), info.programs.keys)
        assertEquals("Public", info.programs[1])
        assertTrue(info.mer!! > 10)
        assertTrue(HdQuality.score(info)!! > 0.85)
    }

    @Test
    fun `files named to escape the folder are ignored`() {
        val dir = Files.createTempDirectory("hd").toFile()
        val hd = HdDecoder(program = 0, filesDir = dir)
        hd.accept("SIG Service: type=audio number=1 name=X")
        hd.accept("  Data component: id=1 port=1000 service_data_type=0 type=3 mime=D9C72536")
        hd.accept("LOT file: port=1000 lot=7 name=../../etc/passwd size=4 mime=4F328CA0 expiry=x")
        assertNull(hd.info.logo)
    }
}

class RtlPowerTest {
    @Test
    fun `rtl_power's CSV, and the stations standing out of the noise`() {
        // A sweep of 87.7 to 108.1 MHz in 10 kHz bins: noise around -40 dB, with stations at
        // 88.5 (strong, with HD sidebands spilling into the channels beside it), 97.1 and 101.5.
        val lines = mutableListOf<String>()
        var low = 87_700_000L
        while (low < 108_100_000L) {
            val high = low + 1_000_000
            val values = (0 until 100).map { i ->
                val hz = low + i * 10_000L
                fun bump(center: Long, db: Double, width: Long = 80_000) = if (kotlin.math.abs(hz - center) <= width) db else 0.0
                -40.0 + ((hz / 10_000) % 7) * 0.3 + bump(88_500_000, 35.0) + bump(88_700_000, 12.0, 60_000) + bump(88_300_000, 12.0, 60_000) +
                    bump(97_100_000, 18.0) + bump(101_500_000, 11.0)
            }
            lines += "2026-11-19, 12:00:00, $low, $high, 10000.00, 100, " + values.joinToString(", ") { "%.2f".format(java.util.Locale.ROOT, it) }
            low = high
        }
        val bins = RtlPower.parse(lines.asSequence())
        assertTrue(bins.size > 1_500)
        val carriers = RtlPower.carriers(bins, Region.US)
        assertEquals(listOf(88_500, 97_100, 101_500), carriers.map { it.khz }.sorted())
        assertEquals(88_500, carriers.first().khz)
        assertTrue(carriers.all { it.aboveFloorDb >= 8 })
    }

    @Test
    fun `rtl_test's list of dongles`() {
        val output = """
            Found 2 device(s):
              0:  Realtek, RTL2838UHIDIR, SN: 00000001
              1:  Nooelec, NESDR SMArt v5, SN: fm-attic

            Using device 0: Generic RTL2832U OEM
            Found Rafael Micro R820T tuner
            No E4000 tuner found, aborting.
        """.trimIndent()
        val dongles = Devices.parse(output)
        assertEquals(2, dongles.size)
        assertEquals("fm-attic", dongles[1].serial)
        assertEquals(listOf("0", "1"), Devices.choose(emptyList()) { dongles })
        assertEquals(listOf("fm-attic"), Devices.choose(listOf("fm-attic")) { dongles })
        assertEquals(listOf("0"), Devices.choose(emptyList()) { emptyList() })
    }

    @Test
    fun `station ids and band plans`() {
        assertEquals("97.1", Station.idOf(97_100, null))
        assertEquals("97.1-hd2", Station.idOf(97_100, 1))
        assertEquals(97_100 to 1, Station.parseId("97.1-hd2"))
        assertEquals(87_500 to null, Station.parseId("87.5"))
        assertNull(Station.parseId("97.1; rm -rf"))
        assertTrue(Region.US.isChannel(97_100))
        assertTrue(!Region.US.isChannel(97_000))
        assertTrue(Region.OTHER.isChannel(97_000))
        assertEquals(101, Region.US.channelsKhz.size)
    }
}
