package com.music.server.listen

import com.music.player.listen.Fingerprint
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Listen against real recordings, when there are some to try (SAMM_LISTEN_MUSIC: a folder of
 * songs). Each is learned; clips of them go through a phone-like microphone (a room's echo, a
 * tinny response, noise, low-bitrate Opus) and should be recognised; songs left out shouldn't be.
 */
class RealMusicTest {
    private fun decode(args: List<String>): FloatArray {
        val p = ProcessBuilder(listOf("ffmpeg", "-v", "error", "-nostdin") + args + listOf("-ac", "1", "-ar", Fingerprint.RATE.toString(), "-f", "s16le", "pipe:1"))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor(60, TimeUnit.SECONDS)
        return FloatArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
    }

    private fun heard(file: File, start: Double, seconds: Int, noise: Double): FloatArray {
        val out = File.createTempFile("clip", ".ogg")
        val graph = "[0:a]highpass=f=250,lowpass=f=4500,aecho=0.8:0.6:35|80:0.35|0.25,volume=0.6,aresample=11025,aformat=channel_layouts=mono[a];" +
            "anoisesrc=color=pink:amplitude=$noise:sample_rate=11025:duration=$seconds[n];[a][n]amix=inputs=2:duration=first:normalize=0[o]"
        ProcessBuilder("ffmpeg", "-v", "error", "-y", "-ss", "%.2f".format(java.util.Locale.ROOT, start), "-t", "$seconds", "-i", file.path, "-filter_complex", graph, "-map", "[o]", "-c:a", "libopus", "-b:a", "24k", out.path)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor(60, TimeUnit.SECONDS)
        return decode(listOf("-i", out.path)).also { out.delete() }
    }

    @Test
    fun `real songs heard through a microphone are recognised`() {
        val dir = System.getenv("SAMM_LISTEN_MUSIC")?.let(::File)
        assumeTrue("SAMM_LISTEN_MUSIC isn't set", dir?.isDirectory == true)
        val files = dir!!.listFiles { f -> f.extension in setOf("mp3", "flac", "ogg", "m4a") }!!.sorted()
        val known = files.dropLast(3)
        val unknown = files.takeLast(3)
        val index = HashMap<Int, MutableList<Pair<Long, Int>>>()
        val lengths = HashMap<Long, Double>()
        var hashCount = 0L
        var seconds = 0.0
        val started = System.nanoTime()
        known.forEachIndexed { i, f ->
            val samples = decode(listOf("-i", f.path))
            lengths[i.toLong()] = samples.size.toDouble() / Fingerprint.RATE
            seconds += lengths[i.toLong()]!!
            val hashes = Fingerprint.hashes(samples, Fingerprint.SONG)
            hashCount += hashes.size
            for (h in hashes) index.getOrPut(Fingerprint.hash(h)) { mutableListOf() } += i.toLong() to Fingerprint.frame(h)
        }
        println("learned ${known.size} songs (${seconds.toInt()} s) in ${(System.nanoTime() - started) / 1_000_000} ms, ${"%.1f".format(hashCount / seconds)} hashes/s")
        val random = Random(4)
        fun find(clip: FloatArray, candidate: Long?): Fingerprint.Match? {
            val hashes = Fingerprint.hashes(clip, Fingerprint.CLIP)
            val found = hashes.map(Fingerprint::hash).distinct().flatMap { h -> index[h].orEmpty().map { (item, frame) -> Triple(h, item, frame) } }
            return Fingerprint.match(hashes, found)
        }
        // As the apps listen: 4, 7, then 10 seconds, each try passing on what the last leant towards.
        fun listen(clip: FloatArray): Pair<Fingerprint.Match?, Int> {
            var candidate: Long? = null
            for (seconds in listOf(4, 7, 10)) {
                val match = find(clip.copyOf(seconds * Fingerprint.RATE), candidate)
                if (match != null && match.confident(candidate)) return match to seconds
                candidate = match?.takeIf { it.promising }?.item
            }
            return null to 10
        }
        for (noise in listOf(0.05, 0.15, 0.3)) {
            var right = 0
            var wrong = 0
            val when_ = mutableListOf<Int>()
            known.forEachIndexed { i, f ->
                val start = random.nextDouble(5.0, lengths[i.toLong()]!! - 15)
                val (match, seconds) = listen(heard(f, start, 10, noise))
                if (match != null) {
                    if (match.item == i.toLong()) { right++; when_ += seconds } else wrong++
                }
            }
            val falseHits = unknown.count { f -> listen(heard(f, 30.0, 10, noise)).first != null }
            println("noise $noise: $right/${known.size} recognised (after ${when_.groupingBy { it }.eachCount().toSortedMap()} s), $wrong wrong, $falseHits of ${unknown.size} unknown taken for something")
        }
    }
}
