package com.music.tuner

import com.music.tuner.audio.AdtsSplitter
import com.music.tuner.rds.CallLetters
import java.io.File
import java.nio.file.Files

/**
 * Stand-ins for rtl_fm, redsea, rtl_sdr, nrsc5, rtl_power and ffmpeg: small shell scripts that
 * print what the real ones would (canned MPX, RDS, logs, sweeps and AAC frames), so the tuner's
 * processes, plumbing and API can be tested without a dongle. 97.1 is a clear station with RDS
 * and HD Radio (HD1 and HD2); 101.5 has RDS but no HD Radio.
 */
class FakeRadio {
    val dir: File = Files.createTempDirectory("fake-radio").toFile()
    val dataDir: File = File(dir, "data").apply { mkdirs() }

    init {
        File(dir, "mpx.raw").writeBytes(Mpx.bytes(Mpx.make(0.5, Mpx.tone(1_000.0), Mpx.tone(440.0), noise = 5.0)))
        File(dir, "frames.aac").writeBytes((0 until 20).map { AdtsSplitter.frame(200) }.reduce { a, b -> a + b })
        val wxyz = "0x%04X".format(CallLetters.piOf("WXYZ"))
        File(dir, "rds-97.jsonl").writeText(
            listOf(
                """{"pi":"$wxyz","group":"0A","ps":"WXYZ FM ","prog_type":"Rock","bler":0}""",
                """{"pi":"$wxyz","group":"0A","ps":"WXYZ FM ","prog_type":"Rock","bler":0}""",
                """{"pi":"$wxyz","group":"2A","radiotext":"Heroes - David Bowie on WXYZ","bler":0}""",
                """{"pi":"$wxyz","group":"12A","radiotext_plus":{"item_running":true,"item_toggle":0,"tags":[{"content-type":"item.title","data":"Heroes"},{"content-type":"item.artist","data":"David Bowie"}]},"bler":0}""",
                """{"pi":"$wxyz","group":"0A","ps":"WXYZ FM ","prog_type":"Rock","bler":0}""",
            ).joinToString("\n", postfix = "\n"),
        )
        val kroc = "0x%04X".format(CallLetters.piOf("KROC"))
        File(dir, "rds-101.jsonl").writeText(
            """{"pi":"$kroc","group":"0A","ps":"ROCK 101","bler":2}""" + "\n" + """{"pi":"$kroc","group":"0A","ps":"ROCK 101","bler":2}""" + "\n",
        )
        File(dir, "nrsc5.log").writeText(
            """
            12:00:01 Synchronized
            12:00:02 MER: 12.5 dB (lower), 13.0 dB (upper)
            12:00:02 BER: 0.000010, avg: 0.000010, min: 0.000000, max: 0.000020
            12:00:03 Station name: WXYZ-FM
            12:00:03 Slogan: The Rock of the Valley
            12:00:03 Audio program 0: public, type: Rock, sound experience 0
            12:00:03 Audio program 1: public, type: Jazz, sound experience 0
            12:00:04 SIG Service: type=audio number=1 name=WXYZ
            12:00:04   Data component: id=1 port=1000 service_data_type=0 type=3 mime=D9C72536
            12:00:04   Data component: id=2 port=1001 service_data_type=0 type=3 mime=BE4B7536
            12:00:04 SIG Service: type=audio number=2 name=WXYZ HD2 Jazz
            12:00:04   Data component: id=1 port=2000 service_data_type=0 type=3 mime=D9C72536
            12:00:05 Title: So What
            12:00:05 Artist: Miles Davis
            12:00:05 Album: Kind of Blue
            12:00:06 LOT file: port=1000 lot=7 name=logo1.png size=8 mime=4F328CA0 expiry=2026-12-01T00:00:00Z
            12:00:06 LOT file: port=2000 lot=8 name=logo2.png size=8 mime=4F328CA0 expiry=2026-12-01T00:00:00Z
            """.trimIndent() + "\n",
        )
        val power = StringBuilder()
        var low = 87_700_000L
        while (low < 108_100_000L) {
            val values = (0 until 100).map { i ->
                val hz = low + i * 10_000L
                -40.0 + (if (kotlin.math.abs(hz - 97_100_000) <= 80_000) 20.0 else 0.0) + (if (kotlin.math.abs(hz - 101_500_000) <= 80_000) 15.0 else 0.0)
            }
            power.append("2026-11-19, 12:00:00, $low, ${low + 1_000_000}, 10000.00, 100, ").append(values.joinToString(", ")).append('\n')
            low += 1_000_000
        }
        File(dir, "power.csv").writeText(power.toString())

        script("rtl_fm", """echo "$@" > "${'$'}D/tuned"; while :; do cat "${'$'}D/mpx.raw" || exit 1; sleep 0.2; done""")
        script(
            "redsea",
            """sleep 0.3; if grep -q 97100k "${'$'}D/tuned"; then cat "${'$'}D/rds-97.jsonl"; else cat "${'$'}D/rds-101.jsonl"; fi; exec cat > /dev/null""",
        )
        script("rtl_sdr", """echo "$@" > "${'$'}D/tuned-hd"; while :; do head -c 65536 /dev/zero || exit 1; sleep 0.05; done""")
        script(
            "nrsc5",
            """sleep 0.3
            if grep -q 97100000 "${'$'}D/tuned-hd"; then
              printf '\211PNG1234' > 7_logo1.png
              printf '\211PNG5678' > 8_logo2.png
              cat "${'$'}D/nrsc5.log" >&2
              head -c 176400 /dev/zero
            fi
            exec cat > /dev/null""",
        )
        script("rtl_power", """cat "${'$'}D/power.csv"""")
        // The encoder: AAC frames for as long as it's fed.
        script("ffmpeg", """(while :; do cat "${'$'}D/frames.aac" || exit 0; sleep 0.1; done) & exec cat > /dev/null""")
    }

    private fun script(name: String, body: String) {
        File(dir, name).apply {
            writeText("#!/bin/sh\nD='${dir.absolutePath}'\n$body\n")
            setExecutable(true)
        }
    }

    fun tools() = Tools(
        rtlFm = File(dir, "rtl_fm").path,
        rtlSdr = File(dir, "rtl_sdr").path,
        rtlPower = File(dir, "rtl_power").path,
        rtlTest = File(dir, "rtl_test").path,
        redsea = File(dir, "redsea").path,
        nrsc5 = File(dir, "nrsc5").path,
        ffmpeg = File(dir, "ffmpeg").path,
    )

    fun config(token: String? = null, grace: Int = 1) = TunerConfig(
        dataDir = dataDir,
        port = 0,
        token = token,
        region = Region.US,
        graceSeconds = grace,
        workDir = File(dir, "work"),
        tools = tools(),
    )
}
