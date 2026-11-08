package com.music.server

import com.music.player.model.SourceType
import com.music.server.music.ApiTrack
import com.music.server.sources.SourceKind
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Converting with a real ffmpeg, through a real server (ffmpeg reads the song from the server).
 * Jellyfin converts to MP3 for a bitrate cap; a player without MP3 has the server convert again.
 */
class TranscodeTest {
    private fun ffmpegAvailable() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)

    @Test
    fun `a lossless song is converted down to the cap`() {
        assumeTrue("ffmpeg isn't installed", ffmpegAvailable())
        val flac = File.createTempFile("tone", ".flac").apply { deleteOnExit() }
        val made = ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=8", "-c:a", "flac", flac.path)
            .start().waitFor()
        assertEquals(0, made)

        val jellyfin = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/Items") -> MockResponse().setBody(
                        """{"Items":[{"Id":"tone","Name":"Tone","Artists":["Test"],
                           "MediaSources":[{"Id":"ms","MediaStreams":[{"Type":"Audio","Codec":"flac","BitRate":900000}]}]}]}""",
                    )
                    request.path!!.startsWith("/Audio/tone/stream") ->
                        MockResponse().setHeader("Content-Type", "audio/flac").setBody(Buffer().write(flac.readBytes()))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        var port = 0
        val services = testServices(selfUrl = { "http://127.0.0.1:$port" })
        val server: EmbeddedServer<NettyApplicationEngine, *> = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(services) }
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
            }
            val http = services.http
            val token = http.newCall(
                Request.Builder().url("http://127.0.0.1:$port/api/auth/login")
                    .post("""{"username":"admin","password":"break-glass-pw","app":true}""".toRequestBody()).header("Content-Type", "application/json").build(),
            ).execute().use { Regex("\"token\":\"([^\"]+)\"").find(it.body!!.string())!!.groupValues[1] }
            val track = ApiJson.encodeToString(ApiTrack.serializer(), ApiTrack(SourceType.JELLYFIN, "tone", "Tone", "Test"))
            val resolved = http.newCall(
                Request.Builder().url("http://127.0.0.1:$port/api/play/resolve").header("Authorization", "Bearer $token")
                    .post("""{"track":$track,"kbps":64,"codecs":["opus","aac"]}""".toRequestBody()).header("Content-Type", "application/json").build(),
            ).execute().use { it.body!!.string() }
            assertTrue(resolved, "\"transcoded\":true" in resolved)
            val streamUrl = Regex("\"streamUrl\":\"([^\"]+)\"").find(resolved)!!.groupValues[1].replace("\\u0026", "&")

            http.newCall(Request.Builder().url("http://127.0.0.1:$port$streamUrl").header("Authorization", "Bearer $token").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("audio/ogg", response.header("Content-Type"))
                val bytes = response.body!!.bytes()
                assertEquals("OggS", String(bytes.copyOfRange(0, 4)))
                // 8 seconds at 64 kbps is about 64 KB; the FLAC was several times that.
                assertTrue("${bytes.size} bytes", bytes.size in 20_000..120_000)
                assertTrue(bytes.size < flac.length())
            }
            // Starting partway gives a shorter song.
            http.newCall(Request.Builder().url("http://127.0.0.1:$port$streamUrl&start=6").header("Authorization", "Bearer $token").build()).execute().use { response ->
                val bytes = response.body!!.bytes()
                assertTrue("${bytes.size} bytes", bytes.size in 5_000..40_000)
            }
        } finally {
            server.stop(100, 1_000)
            jellyfin.shutdown()
        }
    }
}
