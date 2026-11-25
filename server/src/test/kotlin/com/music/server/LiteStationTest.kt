package com.music.server

import com.music.player.model.SourceType
import com.music.server.lite.LiteTracks
import com.music.server.music.ApiTrack
import com.music.server.playlists.Listen
import com.music.server.radio.StationKind
import com.music.server.radio.Stations
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One of the person's own stations on the air (ffmpeg makes it, reading songs through a real
 * server), skipped and thumbed from the lite player's page, with no script.
 */
class LiteStationTest {
    private fun ffmpegAvailable() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)

    @Test
    fun `a personal station plays, and is skipped and thumbed with the page's forms`() {
        assumeTrue("ffmpeg isn't installed", ffmpegAvailable())
        val mp3 = File.createTempFile("tone", ".mp3").apply { deleteOnExit() }
        val made = ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=330:duration=40", "-c:a", "libmp3lame", "-b:a", "128k", mp3.path)
            .start().waitFor()
        assertEquals(0, made)
        val jellyfin = LiteTest.liteJellyfin(audio = mp3.readBytes())
        var port = 0
        val services = testServices(selfUrl = { "http://127.0.0.1:$port" })
        val server: EmbeddedServer<NettyApplicationEngine, *> = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(services) }
        val http = OkHttpClient.Builder().followRedirects(false).readTimeout(30, TimeUnit.SECONDS).build()
        var listening: Thread? = null
        try {
            server.start(wait = false)
            port = runBlocking { server.engine.resolvedConnectors().first().port }
            val base = "http://127.0.0.1:$port"
            val user = runBlocking {
                services.bootstrap()
                LiteTest.libraryOnly(services, jellyfin)
                val via = LiteTest.jellyfinSource(services)
                val user = services.users.create("maddie", "Madison", password = "correct horse")
                val now = System.currentTimeMillis()
                val songs = listOf("s1" to "Paranoid Android", "s2" to "Airbag", "s3" to "Let Down").map { (id, title) ->
                    ApiTrack(SourceType.JELLYFIN, id, title, "Radiohead", "OK Computer", 40_000, via = via)
                }
                services.history.add(user.id, songs.flatMap { s -> (1..4).map { Listen(s, now - it * 3_600_000L, 38_000, "web") } })
                user
            }
            val cookie = http.newCall(
                Request.Builder().url("$base/api/auth/login").post("""{"username":"maddie","password":"correct horse"}""".toRequestBody())
                    .header("Content-Type", "application/json").build(),
            ).execute().use { response -> response.headers("Set-Cookie").first { it.startsWith("samm_session=") }.substringBefore(';') }

            // Tuned in, as the lite player's audio element does.
            listening = thread(isDaemon = true) {
                runCatching {
                    http.newCall(Request.Builder().url("$base/api/live/radio/personal/station/mine/stream?tz=UTC&listener=lite1").header("Cookie", cookie).build())
                        .execute().use { response -> response.body.byteStream().use { input -> val buffer = ByteArray(8192); while (input.read(buffer) >= 0) Unit } }
                }
            }
            val first = waitFor { services.personalRadio.onAir(user, StationKind.MINE)?.current?.song?.key }
            assertNotNull("the station went on the air", first)

            fun page(path: String) = http.newCall(Request.Builder().url("$base$path").header("Cookie", cookie).build()).execute().use { it.code to it.body.string() }
            fun post(path: String, fields: Map<String, String>) = http.newCall(
                Request.Builder().url("$base$path").header("Cookie", cookie)
                    .post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()).build(),
            ).execute().use { it.code to it.header("Location") }

            val station = LiteTracks.token(Stations.track(StationKind.MINE, "Madison", "maddie"))
            val (status, html) = page("/lite/play?t=$station")
            assertEquals(200, status)
            assertTrue(html, html.contains("On now: </b>"))
            assertTrue(html.contains("Thumbs up"))
            val csrf = LiteTest.csrfIn(html)
            val key = Regex("""name="key" value="([^"]+)"""").find(html)!!.groupValues[1]

            // Thumbs up: kept for the station's taste.
            val (thumbed, thumbedTo) = post("/lite/station/thumb", mapOf("_csrf" to csrf, "id" to "mine", "key" to key, "thumb" to "UP", "back" to "/lite/play?t=$station"))
            assertEquals(303, thumbed)
            assertTrue(thumbedTo!!, thumbedTo.endsWith("done=thumbed"))
            assertEquals(1, runBlocking { services.personalRadio.thumbs(user) }[key])

            // Skip: the station moves on to another song, and the page says so.
            val (skipped, skippedTo) = post("/lite/station/skip", mapOf("_csrf" to csrf, "id" to "mine", "back" to "/lite/play?t=$station"))
            assertEquals(303, skipped)
            assertTrue(page(skippedTo!!).second.contains("Skipped."))
            val next = waitFor { services.personalRadio.onAir(user, StationKind.MINE)?.current?.song?.key?.takeIf { it != first } }
            assertNotEquals(first, next)
            assertNotNull("the station moved on", next)
        } finally {
            listening?.interrupt()
            http.dispatcher.cancelAll()
            server.stop(100, 1_000)
            services.close()
            jellyfin.shutdown()
        }
    }

    private fun <T> waitFor(timeoutMs: Long = 30_000, value: () -> T?): T? {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            value()?.let { return it }
            Thread.sleep(200)
        }
        return null
    }
}
