package com.music.tuner.radio

import com.music.tuner.Region
import com.music.tuner.Tools
import java.io.File
import java.util.Locale

/** The command lines the tuner runs. */
object Commands {
    /** The MPX sample rate: what redsea works best at, and room for RDS at 57 kHz. */
    const val MPX_RATE = 171_000

    /** rtl_sdr's rate for nrsc5 (unsigned 8-bit I/Q). */
    const val HD_IQ_RATE = 1_488_375

    /** nrsc5's audio: 44.1 kHz, 16-bit stereo. */
    const val HD_AUDIO_RATE = 44_100

    private fun gainArgs(gain: Double?) = gain?.let { listOf("-g", String.format(Locale.ROOT, "%.1f", it)) }.orEmpty()

    /** FM demodulated to its multiplex signal (not to audio): stereo and RDS are decoded from it. */
    fun rtlFm(tools: Tools, device: String, khz: Int, gain: Double?, ppm: Int) =
        listOf(tools.rtlFm, "-d", device, "-M", "fm", "-l", "0", "-A", "std", "-p", ppm.toString(), "-s", "171k", "-F", "9") +
            gainArgs(gain) + listOf("-f", "${khz}k", "-")

    /** RDS from the MPX, as JSON lines; with block error rates, and RBDS call letters in the Americas. */
    fun redsea(tools: Tools, region: Region) =
        listOf(tools.redsea, "--input", "mpx", "-r", "171k", "--bler") + if (region.rbds) listOf("--rbds") else emptyList()

    /** The raw signal around an HD Radio station, for nrsc5 (several programs can share it). */
    fun rtlSdr(tools: Tools, device: String, khz: Int, gain: Double?, ppm: Int) =
        listOf(tools.rtlSdr, "-d", device, "-f", "${khz * 1000L}", "-s", HD_IQ_RATE.toString(), "-p", ppm.toString()) + gainArgs(gain) + listOf("-")

    /** One HD Radio program (0 = HD1) from rtl_sdr's I/Q on stdin, as raw audio on stdout. */
    fun nrsc5(tools: Tools, program: Int, filesDir: File?) =
        listOf(tools.nrsc5, "-r", "-", "-o", "-", "-t", "raw") +
            (filesDir?.let { listOf("--dump-aas-files", it.path) } ?: emptyList()) +
            listOf(program.toString())

    /** Raw 16-bit stereo at [rate] to AAC (ADTS), what listeners get. */
    fun encoder(tools: Tools, rate: Int, kbps: Int) = listOf(
        tools.ffmpeg, "-hide_banner", "-nostats", "-loglevel", "error",
        "-f", "s16le", "-ar", rate.toString(), "-ac", "2", "-i", "pipe:0",
        "-c:a", "aac", "-b:a", "${kbps}k", "-ar", "48000", "-f", "adts", "-flush_packets", "1", "pipe:1",
    )

    /** One sweep of the FM band, in 10 kHz bins. */
    fun rtlPower(tools: Tools, device: String, region: Region, gain: Double?, ppm: Int, seconds: Int) =
        listOf(
            tools.rtlPower, "-d", device,
            "-f", "${region.firstKhz - 200}k:${region.lastKhz + 200}k:10k",
            "-i", seconds.toString(), "-1", "-c", "20%", "-p", ppm.toString(),
        ) + gainArgs(gain) + listOf("-")

    fun rtlTest(tools: Tools) = listOf(tools.rtlTest, "-t")
}

/** What rtl-sdr's tools say when the dongle can't be used, in words someone can act on. */
object DongleTrouble {
    fun of(line: String, device: String): String? = when {
        "usb_claim_interface error" in line ->
            "Dongle $device is held by the kernel's TV tuner driver: blacklist dvb_usb_rtl28xxu on the host (see the tuner's README)"
        "No supported devices found" in line || "Failed to open rtlsdr device" in line || "No matching devices found" in line ->
            "Couldn't open dongle $device: is it plugged in, and passed to the container (--device /dev/bus/usb)?"
        "usb_open error" in line -> "Couldn't open dongle $device: no permission to use it (passed to the container with --device /dev/bus/usb?)"
        else -> null
    }
}
