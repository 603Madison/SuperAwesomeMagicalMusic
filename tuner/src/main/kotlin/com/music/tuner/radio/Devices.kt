package com.music.tuner.radio

import com.music.tuner.Tools
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** One RTL-SDR dongle rtl_test found. */
data class Dongle(val index: Int, val name: String, val serial: String?)

object Devices {
    private val log = LoggerFactory.getLogger("Devices")
    private val LINE = Regex("""^\s*(\d+):\s+(.*?)(?:,\s*SN:\s*(\S+))?\s*$""")

    /** rtl_test's list: "Found 2 device(s):" then "  0:  Realtek, RTL2838UHIDIR, SN: 00000001". */
    fun parse(output: String): List<Dongle> {
        val lines = output.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("Found ") && it.contains("device") }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { it.isNotBlank() }.mapNotNull { line ->
            LINE.matchEntire(line)?.let { Dongle(it.groupValues[1].toInt(), it.groupValues[2].trim(), it.groupValues[3].ifBlank { null }) }
        }
    }

    /** The dongles plugged in, by asking rtl_test (which stops by itself after listing them). */
    fun detect(tools: Tools): List<Dongle> = try {
        val process = ProcessBuilder(Commands.rtlTest(tools)).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
        reader.join(2_000)
        parse(output.toString()).also { found ->
            if (found.isEmpty()) log.warn("rtl_test found no dongles:\n{}", output.toString().trim())
        }
    } catch (e: Exception) {
        log.warn("Couldn't run rtl_test to look for dongles: {}", e.message)
        emptyList()
    }

    /**
     * Which dongles to use: [configured] (indexes or serial numbers), else every one found (by
     * index: most dongles share the serial 00000001, and rtl-sdr reads a number as an index
     * first), else the first.
     */
    fun choose(configured: List<String>, found: () -> List<Dongle>): List<String> {
        if (configured.isNotEmpty()) return configured
        return found().map { it.index.toString() }.ifEmpty { listOf("0") }
    }
}
