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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

/**
 * The app's own code against the real server (in-process, with an in-memory database and a
 * stand-in Jellyfin): signing in both ways, searching, streaming, playlist changes made offline
 * reaching the server, and downloads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EndToEndTest {
    private lateinit var test: TestServer
    private val audio get() = test.audio
    private val services get() = test.services
    private val base get() = test.base
    private var token: String? = null
    private val http = OkHttpClient()
    private lateinit var api: ServerApi
    private lateinit var db: ConnectDb
    private lateinit var context: Context

    @Before
    fun start() {
        test = TestServer()
        api = ServerApi(http, { base }, { token })
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, ConnectDb::class.java).allowMainThreadQueries().build()
    }

    @After
    fun stop() {
        test.close()
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
        val found = api.search("neon").allSongs
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
    fun `an artist's name finds them, and their page and album open`() = runBlocking {
        signInWithPassword()
        val found = api.search("velvet satellites")
        assertEquals("artist", found.top?.kind)
        val artist = api.artist(found.top!!.artist!!.id)
        assertEquals("Velvet Satellites", artist.artist.name)
        assertEquals(listOf("Low Orbit"), artist.albums.map { it.title })
        val album = api.album(artist.albums.single().id)
        assertEquals(listOf("Neon Tidepool", "Paper Lanterns"), album.tracks.map { it.title })
        // From a song's menu: by name.
        assertEquals("Velvet Satellites", api.artist(null, name = "Velvet Satellites").artist.name)
        assertEquals(2, api.album(null, title = "Low Orbit", artist = "Velvet Satellites").tracks.size)
    }

    @Test
    fun `a typed list is imported as a playlist`() = runBlocking {
        signInWithPassword()
        val result = api.importPlaylist(null, "Velvet Satellites - Neon Tidepool\nNobody - Nothing", "Typed")
        assertEquals(1, result.found)
        assertEquals(listOf("Nobody – Nothing"), result.missing)
        val library = Library(db, api)
        library.sync()
        assertEquals("Typed", library.all.first().single().name)
    }

    @Test
    fun `a playlist made in the web player arrives on the phone`() = runBlocking {
        signInWithPassword()
        val library = Library(db, api)
        library.sync()
        assertTrue(library.all.first().isEmpty())
        // Made on the web: straight on the server.
        val maddie = services.users.byUsername("maddie")!!
        val song = api.search("neon").allSongs.single()
        val made = services.playlists.create(maddie.id, "From the web", "", listOf(com.music.server.music.ApiTrack(song.source, song.sourceId, song.title, song.artist, via = song.via)))
        library.sync()
        assertEquals(listOf("From the web"), library.all.first().map { it.name })
        assertEquals(listOf("Neon Tidepool"), library.entriesNow(made.summary.id).map { it.track.title })
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
        val song = api.search("neon").allSongs.single()
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
        val song = api.search("neon").allSongs.single()
        downloads.queue(listOf(song))
        assertTrue(downloads.runQueue())
        val file: File = downloads.fileFor(song)!!
        assertArrayEquals(audio, file.readBytes())
        downloads.remove(song)
        assertEquals(null, downloads.fileFor(song))
    }
}
