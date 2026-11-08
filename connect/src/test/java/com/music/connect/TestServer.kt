package com.music.connect

import com.music.server.ServerConfig
import com.music.server.Services
import com.music.server.db.Database
import com.music.server.db.Schema
import com.music.server.module
import com.music.server.sources.SourceKind
import com.music.server.util.Passwords
import com.music.server.util.Random
import com.music.server.util.SecretBox
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.nio.file.Files

/**
 * The real server, in-process, with an in-memory database, a person (maddie / correct horse) and
 * a stand-in Jellyfin holding one artist's album, whose first song's file is [audio].
 */
class TestServer(val audio: ByteArray = ByteArray(5000) { (it % 251).toByte() }) : AutoCloseable {
    companion object {
        /** A real (silent) WAV file, which ExoPlayer can read: [seconds] of 16-bit mono at 8 kHz. */
        fun wav(seconds: Int = 2): ByteArray {
            val rate = 8_000
            val data = rate * 2 * seconds
            val buffer = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            buffer.put("data".toByteArray()).putInt(data)
            return buffer.array()
        }
    }

    /** A stand-in Jellyfin: the artist Velvet Satellites, their album Low Orbit, and its two songs. */
    val jellyfin = MockWebServer().apply {
        val media = """[{"Id":"ms","MediaStreams":[{"Type":"Audio","Codec":"flac","BitRate":900000}]}]"""
        val song = """{"Id":"song1","Name":"Neon Tidepool","Artists":["Velvet Satellites"],"Album":"Low Orbit","AlbumId":"al1","RunTimeTicks":2000000000,"MediaSources":$media}"""
        val song2 = """{"Id":"song2","Name":"Paper Lanterns","Artists":["Velvet Satellites"],"Album":"Low Orbit","AlbumId":"al1","RunTimeTicks":2140000000,"MediaSources":$media}"""
        val album = """{"Id":"al1","Name":"Low Orbit","AlbumArtist":"Velvet Satellites","ProductionYear":2024,"ChildCount":8}"""
        val artist = """{"Id":"ar1","Name":"Velvet Satellites"}"""
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath.startsWith("/Audio/song1/stream")) {
                    return MockResponse().setHeader("Content-Type", "audio/flac").setBody(Buffer().write(audio))
                }
                val term = url.queryParameter("searchTerm")?.lowercase()
                fun named(name: String) = term != null && term.length > 2 && name.lowercase().contains(term)
                val items = when {
                    url.encodedPath == "/Artists" -> listOf(artist).filter { named("Velvet Satellites") }
                    url.queryParameter("includeItemTypes") == "MusicAlbum" -> listOf(album).filter {
                        url.queryParameter("ids") == "al1" || url.queryParameter("albumArtistIds") == "ar1" || url.queryParameter("artistIds") == "ar1" || named("Low Orbit")
                    }
                    url.encodedPath != "/Items" -> return MockResponse().setResponseCode(404)
                    url.queryParameter("ids") == "ar1" -> listOf(artist)
                    url.queryParameter("ids") == "song1" -> listOf(song)
                    url.queryParameter("parentId") == "al1" || url.queryParameter("artistIds") == "ar1" -> listOf(song, song2)
                    else -> listOf(song).filter { named("Neon Tidepool") }
                }
                return MockResponse().setBody("""{"Items":[${items.joinToString(",")}]}""")
            }
        }
        start()
    }

    val services: Services
    private val server: EmbeddedServer<*, *>
    val base: String

    init {
        // Each Robolectric sandbox has its own class loader, and JDBC only lends a driver to the
        // loader that registered it: register SQLite's in this one.
        Class.forName("org.sqlite.JDBC")
        val config = ServerConfig(dataDir = Files.createTempDirectory("e2e").toFile(), adminPassword = "admin-password")
        services = Services(config, Database.inMemory().also(Schema::migrate), SecretBox(Random.bytes(32)), passwords = Passwords(1_000))
        runBlocking {
            services.bootstrap()
            services.users.create("maddie", password = "correct horse")
            services.sources.create(
                ownerId = null, kind = SourceKind.JELLYFIN, name = "Home library",
                settings = buildJsonObject {
                    put("serverUrl", jellyfin.url("/").toString().trimEnd('/'))
                    put("username", "u")
                    put("userId", "u1")
                },
                secrets = buildJsonObject { put("accessToken", "t") },
            )
            // Only the library (no internet services in tests).
            services.sources.allGlobal().filter { it.kind != SourceKind.JELLYFIN }.forEach { services.sources.delete(it.id) }
        }
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(services) }.start(wait = false)
        base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
    }

    override fun close() {
        server.stop(100, 1_000)
        jellyfin.shutdown()
    }
}
