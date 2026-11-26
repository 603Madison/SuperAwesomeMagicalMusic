package com.music.player.ui

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.test.hasSetTextAction
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.music.player.MainActivity
import com.music.player.MusicApp
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.playback.PlaybackOrigin
import com.music.player.playback.PlaybackService
import com.music.player.playback.PlayerController
import com.music.player.playback.PlayerUiState
import com.music.player.playback.StreamResolver
import com.music.player.settings.JellyfinConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The main screens at a phone's size, drawn for real into build/screens (the F-Droid listing's
 * screenshots, in fastlane/metadata/android/en-US/images/phoneScreenshots, are these): searching
 * a stand-in Jellyfin, the playlists, and the player. And that each of them comes up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DelicateCoilApi::class)
class ScreenshotsTest {
    private val jellyfin = MockWebServer()
    private val imagesServed = AtomicInteger()
    private val base get() = jellyfin.url("/").toString().trimEnd('/')
    private val out = File("build/screens").apply { mkdirs() }

    private val setUp = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            // Robolectric can't bind a MediaSessionService; the player's state is set by hand below.
            shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
            jellyfin.dispatcher = library()
            // Reading a bitmap's pixels into a buffer here, on the main thread, sets up the native
            // graphics' class references before Coil and Palette use bitmaps on their own threads
            // (left to them, setting them up there crashed the test JVM now and then).
            cover(ALBUMS[0].colors).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                .copyPixelsToBuffer(java.nio.ByteBuffer.allocate(512 * 512 * 4))
            jellyfin.start()
            val container = (app as MusicApp).container
            // Artwork drawn as soon as it's loaded: the app's crossfade would be caught half done.
            SingletonImageLoader.setUnsafe(
                ImageLoader.Builder(app)
                    .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.httpClient })) }
                    .crossfade(false)
                    .build(),
            )
            runBlocking {
                container.settings.setJellyfin(JellyfinConfig(base, "maddie", "u1", "token"))
                container.settings.setYouTubeMusicEnabled(false)
            }
        }

        override fun after() {
            val container = (ApplicationProvider.getApplicationContext<Application>() as MusicApp).container
            runBlocking {
                container.settings.setJellyfin(JellyfinConfig())
                container.settings.setYouTubeMusicEnabled(true)
            }
            jellyfin.shutdown()
            SingletonImageLoader.reset()
        }
    }

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(setUp).around(compose)

    private val container get() = ApplicationProvider.getApplicationContext<MusicApp>().container

    @Test
    fun searchPlaylistsAndThePlayer() {
        val songs = SONGS.associate { it.id to it.track(base) }
        runBlocking {
            val playlists = container.playlists
            playlists.create("Road trip", listOf("s1", "s6", "s4", "s9", "s2", "s7").map(songs::getValue))
            playlists.create("Rainy Sunday", listOf("s5", "s8", "s3").map(songs::getValue))
            playlists.create("Kitchen disco", listOf("s9", "s7", "s1", "s6").map(songs::getValue))
            playlists.create("Focus", listOf("s4", "s5").map(songs::getValue))
        }
        // Something playing, as if the player had started it: the mini player shows on every screen.
        val queue = listOf("s1", "s2", "s3", "s4", "s5").map(songs::getValue)
        playerState().value = PlayerUiState(
            current = queue[0],
            isPlaying = true,
            positionMs = 83_000,
            durationMs = queue[0].durationMs!!,
            bufferedPositionMs = 140_000,
            queue = queue,
            currentIndex = 0,
        )
        origins().value = mapOf(queue[0].key to PlaybackOrigin(SourceType.JELLYFIN, "Jellyfin · FLAC (original file)", kbps = 1411))

        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("velvet satellites")
        waitForText("Top result")
        waitForText("Songs")
        compose.onNodeWithText("Paper Lanterns").assertExists()
        waitForArtwork()
        save("1-search")

        compose.onAllNodesWithText("Playlists").onFirst().performClick()
        waitForText("Road trip")
        compose.onNodeWithText("Kitchen disco").assertExists()
        compose.onNodeWithText("6 songs").assertExists()
        waitForArtwork()
        save("2-playlists")

        // The mini player opens the player.
        compose.onAllNodesWithText("Neon Tidepool").onFirst().performClick()
        waitForText("Up next")
        compose.onNodeWithText("Jellyfin · FLAC (original file)").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        waitForArtwork()
        saveWithTopWindow("3-player")
    }

    @Suppress("UNCHECKED_CAST")
    private fun playerState() = PlayerController::class.java.getDeclaredField("_state")
        .apply { isAccessible = true }.get(container.player) as MutableStateFlow<PlayerUiState>

    @Suppress("UNCHECKED_CAST")
    private fun origins() = StreamResolver::class.java.getDeclaredField("_origins")
        .apply { isAccessible = true }.get(container.resolver) as MutableStateFlow<Map<String, PlaybackOrigin>>

    private fun waitForText(text: String) =
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** Lets the artwork asked for arrive and fade in. */
    private fun waitForArtwork() {
        var served = -1
        var quiet = 0
        // Done once no more artwork has been asked for in a while.
        for (i in 0 until 60) {
            compose.waitForIdle()
            Thread.sleep(150)
            compose.mainClock.advanceTimeBy(300)
            val now = imagesServed.get()
            quiet = if (now == served && now > 0) quiet + 1 else 0
            served = now
            if (quiet >= 4) break
        }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Draws the window with the one above it (the player's sheet) on top. */
    private fun saveWithTopWindow(name: String) {
        compose.waitForIdle()
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>
        val base = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            val canvas = Canvas(bitmap)
            base.draw(canvas)
            views.lastOrNull()?.takeIf { it !== base }?.draw(canvas)
        }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class Album(val id: String, val title: String, val artist: String, val year: Int, val colors: IntArray)

    private class Song(val id: String, val title: String, val album: Album, val seconds: Int) {
        fun json() = """{"Id":"$id","Name":"$title","Artists":["${album.artist}"],"AlbumArtist":"${album.artist}","Album":"${album.title}",""" +
            """"AlbumId":"${album.id}","AlbumPrimaryImageTag":"t","RunTimeTicks":${seconds * 10_000_000L}}"""

        fun track(base: String) = Track(
            SourceType.JELLYFIN, id, title, album.artist, album.title, seconds * 1000L,
            "$base/Items/${album.id}/Images/Primary?maxHeight=512&tag=t",
        )
    }

    private companion object {
        const val VELVET = "Velvet Satellites"
        val LOW_ORBIT = Album("al1", "Low Orbit", VELVET, 2024, intArrayOf(0xFF2B1B5A.toInt(), 0xFFE0567A.toInt(), 0xFFFFC46B.toInt()))
        val COASTLINES = Album("al2", "Coastlines", VELVET, 2021, intArrayOf(0xFF0B3C5D.toInt(), 0xFF3AAFA9.toInt(), 0xFFDEF2F1.toInt()))
        val GLASSHOUSE = Album("al3", "Glasshouse", "The Marigold Static", 2023, intArrayOf(0xFF1E3D2F.toInt(), 0xFF8DB580.toInt(), 0xFFF2E394.toInt()))
        val ARCADE = Album("al4", "Midnight Arcade", "Juniper & the Echoes", 2022, intArrayOf(0xFF120E2E.toInt(), 0xFF6A3FD1.toInt(), 0xFF35E0F0.toInt()))
        val ALBUMS = listOf(LOW_ORBIT, COASTLINES, GLASSHOUSE, ARCADE)
        val SONGS = listOf(
            Song("s1", "Neon Tidepool", LOW_ORBIT, 200),
            Song("s2", "Paper Lanterns", LOW_ORBIT, 214),
            Song("s3", "Satellite Hearts", LOW_ORBIT, 199),
            Song("s4", "Glass Harbour", COASTLINES, 187),
            Song("s5", "Tidewater", COASTLINES, 242),
            Song("s6", "Glasshouse Summer", GLASSHOUSE, 187),
            Song("s7", "Wildflower Radio", GLASSHOUSE, 221),
            Song("s8", "Slow Motion City", ARCADE, 236),
            Song("s9", "Pixel Heart", ARCADE, 203),
        )
        const val ARTIST = """{"Id":"ar1","Name":"$VELVET","ImageTags":{"Primary":"t"}}"""
    }

    private fun Album.json() = """{"Id":"$id","Name":"$title","AlbumArtist":"$artist","Artists":["$artist"],"ProductionYear":$year,""" +
        """"ChildCount":${SONGS.count { it.album == this }},"ImageTags":{"Primary":"t"}}"""

    /** Cover art: a gradient and a sun, in each album's colours. */
    private fun cover(colors: IntArray): ByteArray {
        val size = 512f
        val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, size, size, Paint().apply { shader = LinearGradient(0f, 0f, size, size, colors[0], colors[1], Shader.TileMode.CLAMP) })
        val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(size * 0.62f, size * 0.42f, size * 0.24f, colors[2], colors[1], Shader.TileMode.CLAMP) }
        canvas.drawCircle(size * 0.62f, size * 0.42f, size * 0.22f, sun)
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[0]; alpha = 150 }
        for (i in 0 until 5) canvas.drawRect(0f, size * (0.62f + i * 0.07f), size, size * (0.62f + i * 0.07f) + size * 0.025f, band)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A small library: the artist Velvet Satellites with two albums, and two more artists' albums. */
    private fun library(): Dispatcher {
        val covers = ALBUMS.associate { it.id to cover(it.colors) } +
            ("ar1" to cover(intArrayOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFFFFAF7B.toInt())))
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val path = url.encodedPath
                if (path.startsWith("/Items/") && path.endsWith("/Images/Primary")) {
                    val image = covers[path.removePrefix("/Items/").substringBefore('/')] ?: return MockResponse().setResponseCode(404)
                    imagesServed.incrementAndGet()
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image))
                }
                val term = url.queryParameter("searchTerm")?.lowercase()
                fun named(name: String) = term != null && term.length > 2 && name.lowercase().contains(term)
                val ids = url.queryParameter("ids")?.split(',').orEmpty()
                val byArtist = listOfNotNull(url.queryParameter("artistIds"), url.queryParameter("albumArtistIds")).any { "ar1" in it }
                val items = when {
                    path == "/Artists" -> listOf(ARTIST).filter { named(VELVET) }
                    path != "/Items" -> return MockResponse().setResponseCode(404)
                    url.queryParameter("includeItemTypes") == "MusicAlbum" -> ALBUMS
                        .filter { it.id in ids || named(it.title) || (byArtist && it.artist == VELVET) }
                        .map { it.json() }
                    "ar1" in ids -> listOf(ARTIST)
                    url.queryParameter("parentId") != null -> SONGS.filter { it.album.id == url.queryParameter("parentId") }.map { it.json() }
                    byArtist -> SONGS.filter { it.album.artist == VELVET }.map { it.json() }
                    else -> SONGS.filter { it.id in ids || named(it.title) }.map { it.json() }
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"Items":[${items.joinToString(",")}]}""")
            }
        }
    }
}
