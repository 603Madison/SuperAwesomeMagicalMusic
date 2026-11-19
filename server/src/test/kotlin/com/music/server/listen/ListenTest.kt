package com.music.server.listen

import com.music.player.listen.Fingerprint
import com.music.server.module
import com.music.server.sources.SourceKind
import com.music.server.testServices
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Listen: recognising songs from what a microphone hears, from the library first. */
class ListenTest {
    companion object {
        /**
         * A song of [seconds] at [rate], made up from [seed]: a melody over chords, a bass line
         * and drums at its own tempo, in its own key. Different seeds sound nothing alike.
         */
        fun song(seed: Int, seconds: Int, rate: Int = Fingerprint.RATE): FloatArray {
            val r = Random(seed)
            val out = FloatArray(seconds * rate)
            val beat = 60.0 / (90 + r.nextInt(60))
            val root = 45 + r.nextInt(12)
            val scale = intArrayOf(0, 2, 4, 5, 7, 9, 11)
            fun hz(note: Int) = 440.0 * 2.0.pow((note - 69) / 12.0)
            fun tone(at: Double, length: Double, freq: Double, level: Double, harmonics: Int) {
                val from = (at * rate).toInt()
                val n = (length * rate).toInt()
                for (i in 0 until n) {
                    val k = from + i
                    if (k >= out.size) return
                    val t = i.toDouble() / rate
                    val env = minOf(1.0, t * 80) * exp(-3.0 * t / length)
                    var v = 0.0
                    for (h in 1..harmonics) v += sin(2 * PI * freq * h * t) / h
                    out[k] += (v * level * env).toFloat()
                }
            }
            fun hit(at: Double, length: Double, level: Double, bright: Boolean) {
                val from = (at * rate).toInt()
                var last = 0.0
                for (i in 0 until (length * rate).toInt()) {
                    val k = from + i
                    if (k >= out.size) return
                    val noise = r.nextDouble(-1.0, 1.0)
                    val v = if (bright) noise - last else noise
                    last = noise
                    out[k] += (v * level * exp(-i / (length * rate / 5))).toFloat()
                }
            }
            var t = 0.0
            while (t < seconds) {
                // Each bar its own chord (songs that only loop would match themselves all over).
                val degree = r.nextInt(7)
                for (third in 0..2) tone(t, beat * 4, hz(root + 12 + scale[(degree + third * 2) % 7]), 0.06, 3)
                for (b in 0 until 4) {
                    tone(t + b * beat, beat * 0.9, hz(root + scale[degree]), 0.12, 2)
                    if (b % 2 == 0) tone(t + b * beat, 0.15, 60.0, 0.4, 1) else hit(t + b * beat, 0.12, 0.15, false)
                    hit(t + b * beat + beat / 2, 0.04, 0.05, true)
                }
                var m = 0.0
                while (m < beat * 4) {
                    val length = beat * listOf(0.5, 1.0, 1.0, 1.5)[r.nextInt(4)]
                    tone(t + m, length * 0.9, hz(root + 24 + scale[r.nextInt(7)] + 12 * r.nextInt(2)), 0.1, 4)
                    m += length
                }
                t += beat * 4
            }
            val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-6f)
            for (i in out.indices) out[i] = out[i] / peak * 0.8f
            return out
        }

        /** [samples] as heard through a phone across a room: quieter, duller, echoing, with noise. */
        fun heard(samples: FloatArray, start: Int, length: Int, noise: Double, seed: Int = 1): FloatArray {
            val r = Random(seed)
            val clip = FloatArray(length)
            var low = 0.0
            val rms = sqrt(samples.sliceArray(start until start + length).map { it.toDouble() * it }.average())
            for (i in 0 until length) {
                val dry = samples[start + i].toDouble()
                val echo = if (start + i - 400 >= 0) samples[start + i - 400] * 0.35 else 0.0
                low += 0.45 * ((dry + echo) - low)
                clip[i] = (0.5 * low + r.nextDouble(-1.0, 1.0) * rms * noise * sqrt(3.0)).toFloat()
            }
            return clip
        }

        /** [samples] (mono, at [rate]) as a 16-bit WAV file. */
        fun wav(samples: FloatArray, rate: Int): ByteArray {
            val data = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                data[2 * i] = v.toByte()
                data[2 * i + 1] = (v shr 8).toByte()
            }
            val out = ByteArrayOutputStream()
            fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.write("RIFF".toByteArray()); int(36 + data.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
            out.write("data".toByteArray()); int(data.size); out.write(data)
            return out.toByteArray()
        }
    }

    private fun lookup(index: Map<Int, List<Pair<Long, Int>>>, clip: LongArray) =
        clip.map(Fingerprint::hash).distinct().flatMap { h -> index[h].orEmpty().map { (item, frame) -> Triple(h, item, frame) } }

    @Test
    fun `a few seconds through a microphone are enough to tell which song, and where in it`() {
        val songs = (1..8).map { song(it, 45) }
        val index = HashMap<Int, MutableList<Pair<Long, Int>>>()
        songs.forEachIndexed { i, s -> for (h in Fingerprint.hashes(s, Fingerprint.SONG)) index.getOrPut(Fingerprint.hash(h)) { mutableListOf() } += i.toLong() to Fingerprint.frame(h) }
        val rate = Fingerprint.RATE
        songs.forEachIndexed { i, s ->
            // Not on a frame boundary, quieter, echoing, duller and noisy.
            val start = 12 * rate + 137 * (i + 1)
            val clip = heard(s, start, 7 * rate, noise = 0.5, seed = i)
            val hashes = Fingerprint.hashes(clip, Fingerprint.CLIP)
            val match = Fingerprint.match(hashes, lookup(index, hashes))
            assertNotNull(match)
            assertEquals("song $i", i.toLong(), match!!.item)
            assertTrue("song $i: $match", match.confident())
            assertTrue("song $i at ${match.offsetMs} ms", abs(match.offsetMs - start * 1000L / rate) < 200)
        }
        // A song it never learned, and plain noise: nothing.
        for (unknown in listOf(heard(song(99, 20), rate, 7 * rate, 0.5), FloatArray(7 * rate) { Random(5).nextFloat() - 0.5f })) {
            val hashes = Fingerprint.hashes(unknown, Fingerprint.CLIP)
            val match = Fingerprint.match(hashes, lookup(index, hashes))
            assertFalse("$match", match?.confident() == true)
        }
    }

    @Test
    fun `the server learns the library, and what's heard is found in it`() {
        assumeTrue("ffmpeg isn't installed", runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false))
        val wavRate = Fingerprint.RATE
        val titles = listOf("Neon Tidepool", "Paper Lanterns", "Glasshouse Summer", "Coastline Radio")
        val audio = titles.indices.associate { "s$it" to wav(song(10 + it, 40, wavRate), wavRate) }
        val jellyfin = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    fun item(i: Int) = """{"Id":"s$i","Name":"${titles[i]}","Artists":["Velvet Satellites"],"Album":"Low Orbit","RunTimeTicks":400000000,
                        "MediaSources":[{"Id":"ms","MediaStreams":[{"Type":"Audio","Codec":"pcm_s16le","BitRate":352800}]}]}"""
                    return when {
                        path.startsWith("/Items") && "ids=" in path -> {
                            val id = Regex("ids=([^&]+)").find(path)!!.groupValues[1]
                            MockResponse().setBody("""{"Items":[${item(id.removePrefix("s").toInt())}],"TotalRecordCount":1}""")
                        }
                        path.startsWith("/Items") -> {
                            val start = Regex("startIndex=(\\d+)").find(path)?.groupValues?.get(1)?.toInt() ?: 0
                            val page = titles.indices.drop(start).take(2)
                            MockResponse().setBody("""{"Items":[${page.joinToString(",") { item(it) }}],"TotalRecordCount":${titles.size}}""")
                        }
                        path.startsWith("/Audio/") -> {
                            val id = path.removePrefix("/Audio/").substringBefore('/')
                            audio[id]?.let { MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        var port = 0
        val services = testServices(selfUrl = { "http://127.0.0.1:$port" })
        val server: EmbeddedServer<NettyApplicationEngine, *> = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(services) }
        val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        try {
            server.start(wait = false)
            port = runBlocking { server.engine.resolvedConnectors().first().port }
            runBlocking {
                services.bootstrap()
                services.sources.create(
                    ownerId = null, kind = SourceKind.JELLYFIN, name = "Jellyfin",
                    settings = buildJsonObject {
                        put("serverUrl", jellyfin.url("/").toString().trimEnd('/'))
                        put("username", "u")
                        put("userId", "u1")
                    },
                    secrets = buildJsonObject { put("accessToken", "t") },
                )
                services.listen.learnAll()
            }
            val token = http.newCall(
                Request.Builder().url("http://127.0.0.1:$port/api/auth/login")
                    .post("""{"username":"admin","password":"break-glass-pw","app":true}""".toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { Regex("\"token\":\"([^\"]+)\"").find(it.body.string())!!.groupValues[1] }
            fun get(path: String) = http.newCall(Request.Builder().url("http://127.0.0.1:$port$path").header("Authorization", "Bearer $token").build())
                .execute().use { Json.parseToJsonElement(it.body.string()) }
            fun send(clip: FloatArray, final: Boolean, candidate: String?) = http.newCall(
                Request.Builder().url("http://127.0.0.1:$port/api/listen?final=$final" + (candidate?.let { "&candidate=$it" } ?: ""))
                    .header("Authorization", "Bearer $token")
                    .post(wav(clip, Fingerprint.RATE).toRequestBody("audio/wav".toMediaType())).build(),
            ).execute().use { Json.parseToJsonElement(it.body.string()).jsonObject }
            // As the apps listen: what's been recorded so far, every few seconds, until it's sure.
            fun listen(clip: FloatArray): kotlinx.serialization.json.JsonObject {
                var candidate: String? = null
                val tries = listOf(4, 8).map { it * Fingerprint.RATE }.filter { it <= clip.size }
                for ((i, length) in tries.withIndex()) {
                    val answer = send(clip.copyOf(length), final = i == tries.lastIndex, candidate = candidate)
                    println("try ${length / Fingerprint.RATE} s: $answer")
                    if (answer["found"]!!.jsonPrimitive.content == "true" || i == tries.lastIndex) return answer
                    candidate = answer["candidate"]?.jsonPrimitive?.content
                }
                error("no tries")
            }

            val status = get("/api/listen/status").jsonObject
            assertEquals(4, status["learned"]!!.jsonPrimitive.content.toInt())
            assertEquals(4, status["total"]!!.jsonPrimitive.content.toInt())

            // Glasshouse Summer, from 15 seconds in, heard across a room.
            val heard = listen(heard(song(12, 40), 15 * Fingerprint.RATE, 8 * Fingerprint.RATE, noise = 0.4))
            assertEquals("true", heard["found"]!!.jsonPrimitive.content)
            assertEquals("Glasshouse Summer", heard["title"]!!.jsonPrimitive.content)
            assertEquals("library", heard["via"]!!.jsonPrimitive.content)
            assertEquals("s2", heard["song"]!!.jsonObject["sourceId"]!!.jsonPrimitive.content)
            // Where the song was when the recording ended (exactly, in the test above: these made-up
            // songs repeat their drums every few beats, so a short clip could be any of them).
            assertTrue(heard.toString(), heard["positionMs"]!!.jsonPrimitive.long in 4_000..40_000)

            // Something not in the library (no AudD here): not found, and why.
            val unknown = listen(heard(song(77, 20), 2 * Fingerprint.RATE, 8 * Fingerprint.RATE, noise = 0.4))
            assertEquals("false", unknown["found"]!!.jsonPrimitive.content)
            assertTrue(unknown.toString(), unknown["message"]!!.jsonPrimitive.content.contains("AudD"))

            // Recently heard keeps it (once, though it was heard twice in a row).
            listen(heard(song(12, 40), 20 * Fingerprint.RATE, 8 * Fingerprint.RATE, noise = 0.4, seed = 3))
            val recent = get("/api/listen/heard").jsonArray
            assertEquals(1, recent.size)
            assertEquals("Glasshouse Summer", recent[0].jsonObject["title"]!!.jsonPrimitive.content)

            // Now Playing: a phone sends fingerprints it worked out itself, ten seconds' worth, not sound.
            fun ambient(clip: FloatArray): kotlinx.serialization.json.JsonObject {
                val hashes = Fingerprint.hashes(clip, Fingerprint.CLIP)
                val body = java.nio.ByteBuffer.allocate(hashes.size * 8).apply { hashes.forEach(::putLong) }.array()
                return http.newCall(
                    Request.Builder().url("http://127.0.0.1:$port/api/listen/hashes?ms=${clip.size * 1000L / Fingerprint.RATE}")
                        .header("Authorization", "Bearer $token")
                        .post(body.toRequestBody("application/octet-stream".toMediaType())).build(),
                ).execute().use { Json.parseToJsonElement(it.body.string()).jsonObject }
            }
            val nearby = ambient(heard(song(13, 40), 5 * Fingerprint.RATE, 10 * Fingerprint.RATE, noise = 0.4, seed = 5))
            assertEquals(nearby.toString(), "Coastline Radio", nearby["title"]?.jsonPrimitive?.content)
            assertEquals("false", ambient(heard(song(78, 20), 2 * Fingerprint.RATE, 10 * Fingerprint.RATE, noise = 0.4))["found"]!!.jsonPrimitive.content)
            val nonsense = http.newCall(
                Request.Builder().url("http://127.0.0.1:$port/api/listen/hashes?ms=5000").header("Authorization", "Bearer $token")
                    .post(ByteArray(7).toRequestBody("application/octet-stream".toMediaType())).build(),
            ).execute().use { it.code }
            assertEquals(400, nonsense)

            // A playlist pretending to be a recording isn't followed anywhere.
            val sneaky = http.newCall(
                Request.Builder().url("http://127.0.0.1:$port/api/listen").header("Authorization", "Bearer $token")
                    .post("#EXTM3U\n#EXTINF:5,\nfile:///etc/passwd\n".toRequestBody("audio/x-mpegurl".toMediaType())).build(),
            ).execute().use { Json.parseToJsonElement(it.body.string()).jsonObject }
            assertEquals("false", sneaky["found"]!!.jsonPrimitive.content)

            // A song gone from the library is forgotten.
            runBlocking {
                services.listen.store.forgetUnseen(services.sources.all().first { it.kind == SourceKind.JELLYFIN }.id, Long.MAX_VALUE)
                assertEquals(0 to 0, services.listen.store.counts(services.sources.all().map { it.id }))
            }
        } finally {
            server.stop(100, 1_000)
            jellyfin.shutdown()
        }
    }

    @Test
    fun `AudD's answer is read, and a token it refuses is said so`() = runBlocking {
        val audd = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    return when {
                        "bad-token" in body -> MockResponse().setBody("""{"status":"error","error":{"error_code":900,"error_message":"Wrong API token"}}""")
                        "name=\"file\"" !in body -> MockResponse().setBody("""{"status":"error","error":{"error_code":700,"error_message":"No file"}}""")
                        "silence" in body -> MockResponse().setBody("""{"status":"success","result":null}""")
                        else -> MockResponse().setBody(
                            """{"status":"success","result":{"artist":"Velvet Satellites","title":"Static Bloom","album":"Low Orbit","timecode":"01:05",
                               "song_link":"https://lis.tn/StaticBloom","apple_music":{"durationInMillis":232000,"artwork":{"url":"https://is1.example/{w}x{h}bb.jpg"}}}}""",
                        )
                    }
                }
            }
            start()
        }
        try {
            val client = AudD(OkHttpClient(), audd.url("/").toString())
            client.check("good-token")
            assertThrows(IOException::class.java) { runBlocking { client.check("bad-token") } }
            val song = client.recognise("good-token", "music".toByteArray(), "audio/webm")!!
            assertEquals("Static Bloom", song.title)
            assertEquals("Velvet Satellites", song.artist)
            assertEquals(65_000L, song.positionMs)
            assertEquals(232_000L, song.durationMs)
            assertEquals("https://is1.example/600x600bb.jpg", song.artworkUrl)
            assertEquals("https://lis.tn/StaticBloom", song.link)
            assertNull(client.recognise("good-token", "silence".toByteArray(), "audio/webm"))
        } finally {
            audd.shutdown()
        }
    }
}
