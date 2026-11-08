package com.music.connect

import android.content.Context
import androidx.room.Room
import com.music.connect.data.ConnectDb
import com.music.connect.data.Downloads
import com.music.connect.data.Library
import com.music.connect.data.Prefs
import com.music.connect.net.ApiTrack
import com.music.connect.net.ServerApi
import com.music.connect.net.SignIn
import com.music.player.model.SourceType
import com.music.server.ServerConfig
import com.music.server.Services
import com.music.server.db.Database
import com.music.server.db.Schema
import com.music.server.module
import com.music.server.sources.SourceKind
import com.music.server.util.Passwords
import com.music.server.util.Random
import com.music.server.util.SecretBox
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The app's own code against the real server (in-process, with an in-memory database and a
 * stand-in Jellyfin): signing in both ways, searching, streaming, playlist changes made offline
 * reaching the server, and downloads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EndToEndTest {
    private val audio = ByteArray(5000) { (it % 251).toByte() }
    private lateinit var jellyfin: MockWebServer
    private lateinit var services: Services
    private lateinit var server: io.ktor.server.engine.EmbeddedServer<*, *>
    private lateinit var base: String
    private var token: String? = null
    private val http = OkHttpClient()
    private lateinit var api: ServerApi
    private lateinit var db: ConnectDb
    private lateinit var context: Context

    @Before
    fun start() {
        jellyfin = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/Items") || request.path!!.startsWith("/Artists") -> MockResponse().setBody(
                        """{"Items":[{"Id":"song1","Name":"Neon Tidepool","Artists":["Velvet Satellites"],"Album":"Low Orbit","RunTimeTicks":2000000000,
                           "MediaSources":[{"Id":"ms","MediaStreams":[{"Type":"Audio","Codec":"flac","BitRate":900000}]}]}]}""",
                    )
                    request.path!!.startsWith("/Audio/song1/stream") -> MockResponse().setHeader("Content-Type", "audio/flac").setBody(Buffer().write(audio))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        val serverDb = Database.inMemory().also(Schema::migrate)
        val config = ServerConfig(dataDir = Files.createTempDirectory("e2e").toFile(), adminPassword = "admin-password")
        services = Services(config, serverDb, SecretBox(Random.bytes(32)), passwords = Passwords(1_000))
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
            // Only the library for these tests (no internet services).
            services.sources.allGlobal().filter { it.kind != SourceKind.JELLYFIN }.forEach { services.sources.delete(it.id) }
        }
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(services) }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        base = "http://127.0.0.1:$port"
        api = ServerApi(http, { base }, { token })
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, ConnectDb::class.java).allowMainThreadQueries().build()
    }

    @After
    fun stop() {
        server.stop(100, 1_000)
        jellyfin.shutdown()
        db.close()
    }

    private fun signInWithPassword() = runBlocking {
        token = api.login(base, "maddie", "correct horse", "Test phone").token
        assertNotNull(token)
    }

    @Test
    fun `password sign-in, search and streaming`() = runBlocking {
        signInWithPassword()
        assertEquals("maddie", api.me().user.username)
        val found = api.search("neon").groups.flatMap { it.tracks }
        assertEquals("Neon Tidepool", found.single().title)
        val resolved = api.resolve(found.single(), 0, thorough = true)
        val bytes = http.newCall(Request.Builder().url(api.absolute(resolved.streamUrl)).header("Authorization", "Bearer $token").build())
            .execute().use { it.body!!.bytes() }
        assertArrayEquals(audio, bytes)
        // The interceptor adds the sign-in for the server only.
        val interceptor = ServerApi.authInterceptor({ base }, { token })
        val withAuth = OkHttpClient.Builder().addInterceptor(interceptor).build()
        assertEquals(200, withAuth.newCall(Request.Builder().url(api.absolute(resolved.streamUrl)).build()).execute().use { it.code })
    }

    @Test
    fun `the browser sign-in's code works only with this app's secret`() = runBlocking {
        // What the web page does once signed in: ask for a code for this app's challenge.
        val cookies = mutableMapOf<String, String>()
        val browser = OkHttpClient.Builder().cookieJar(object : okhttp3.CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies2: List<okhttp3.Cookie>) = cookies2.forEach { cookies[it.name] = it.value }
            override fun loadForRequest(url: okhttp3.HttpUrl) = cookies.map { (k, v) -> okhttp3.Cookie.Builder().name(k).value(v).domain(url.host).build() }
        }).build()
        val json = "application/json".toMediaType()
        browser.newCall(Request.Builder().url("$base/api/auth/login").post("""{"username":"maddie","password":"correct horse"}""".toRequestBody(json)).build()).execute().close()
        val verifier = SignIn.token(48)
        val code = browser.newCall(
            Request.Builder().url("$base/api/auth/app/code").header("X-SAMM", "1")
                .post("""{"challenge":"${SignIn.challenge(verifier)}"}""".toRequestBody(json)).build(),
        ).execute().use { Regex("\"code\":\"([^\"]+)\"").find(it.body!!.string())!!.groupValues[1] }
        val signedIn = api.exchangeCode(base, code, verifier, "Test phone")
        token = signedIn.token
        assertEquals("maddie", api.me().user.username)
    }

    @Test
    fun `playlist changes made offline reach the server in order`() = runBlocking {
        signInWithPassword()
        val library = Library(db, api)
        val song = api.search("neon").groups.flatMap { it.tracks }.single()
        val other = ApiTrack(SourceType.YOUTUBE_MUSIC, "abc", "Paper Lanterns", "Velvet Satellites")

        // All of this happens with no server calls ("offline").
        val id = library.create("Road trip", listOf(song))
        library.add(id, listOf(other))
        val added = library.entriesNow(id).last()
        library.remove(id, listOf(added.entryId)) // never reaches the server
        library.rename(id, "Road trip 2026")
        assertEquals(listOf("Neon Tidepool"), library.entriesNow(id).map { it.track.title })

        library.sync()
        val maddie = services.users.byUsername("maddie")!!
        val onServer = services.playlists.list(maddie.id).single()
        assertEquals("Road trip 2026", onServer.name)
        assertEquals(1, onServer.count)
        // And the phone's copy now has the server's entry ids.
        assertTrue(library.entriesNow(id).none { it.entryId.startsWith("local-") })

        // A playlist deleted elsewhere: a change to it is dropped, not retried forever.
        services.playlists.delete(maddie.id, id)
        library.add(id, listOf(other))
        library.sync()
        assertTrue(library.entriesNow(id).isEmpty())
        assertEquals(0, db.pending().all().size)
    }

    @Test
    fun `downloads are fetched whole and play offline`() = runBlocking {
        signInWithPassword()
        val prefs = Prefs(context)
        val downloads = Downloads(context, db, api, http.newBuilder().addInterceptor(ServerApi.authInterceptor({ base }, { token })).build(), prefs)
        val song = api.search("neon").groups.flatMap { it.tracks }.single()
        downloads.queue(listOf(song))
        assertTrue(downloads.runQueue())
        val file: File = downloads.fileFor(song)!!
        assertArrayEquals(audio, file.readBytes())
        downloads.remove(song)
        assertEquals(null, downloads.fileFor(song))
    }
}
