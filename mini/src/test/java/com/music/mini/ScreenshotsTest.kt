package com.music.mini

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import com.music.mini.R
import com.music.mini.net.User
import com.music.mini.orchestrator.OrchestratorAgent
import com.music.mini.orchestrator.OrchestratorLink
import com.music.mini.orchestrator.OrchestratorSignIn
import com.music.mini.ui.CodeScreen
import com.music.mini.ui.MainActivity
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of the main screens at a flip phone's size (480 × 640, like the TCL Flip Pro), drawn
 * for real, into build/screens: to look at, and for the store listing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h427dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotsTest {
    private val server = MockWebServer()
    private val player = FakePlayer()
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val mini get() = RuntimeEnvironment.getApplication().mini
    private val base get() = server.url("/").toString().trimEnd('/')
    private val out = File("build/screens").apply { mkdirs() }

    private fun song(id: String, title: String, ms: Long) =
        """{"source":"JELLYFIN","sourceId":"$id","title":"$title","artist":"Velvet Satellites","album":"Low Orbit","durationMs":$ms,"via":"lib"}"""

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.path) {
                    "/api/auth/device/start" -> """{"deviceCode":"${"d".repeat(43)}","userCode":"KXRT-MWPD","verificationUri":"https://music.example.com/link","verificationUriComplete":"https://music.example.com/link?code=KXRT-MWPD","interval":5,"expiresIn":600}"""
                    "/api/auth/device/poll" -> """{"status":"pending"}"""
                    "/api/playlists" -> """[{"id":"p1","name":"Road trip","count":24},{"id":"p2","name":"Rainy day","count":12},{"id":"p3","name":"Kitchen disco","count":31}]"""
                    "/api/playlists/p1" -> """{"summary":{"id":"p1","name":"Road trip","count":5},"entries":[
                        {"entryId":"e1","track":${song("s1", "Neon Tidepool", 200_000)}},
                        {"entryId":"e2","track":${song("s2", "Paper Lanterns", 214_000)}},
                        {"entryId":"e3","track":${song("s3", "Glass Harbour", 187_000)}},
                        {"entryId":"e4","track":${song("s4", "Low Orbit", 242_000)}},
                        {"entryId":"e5","track":${song("s5", "Satellite Hearts", 199_000)}}]}"""
                    else -> return MockResponse().setResponseCode(404).setBody("""{"error":"No such API"}""")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        mini.player = player
    }

    @After
    fun stop() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        server.shutdown()
    }

    private fun save(name: String) {
        settle()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun screens() {
        mini.prefs.signIn(base, "t0ken", User("u1", "maddie", "Maddie"), "Home Music")
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        // This screen size starts in touch mode: the first key brings the highlight.
        activity.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Now playing", activity.focusedTitle)
        activity.press(KeyEvent.KEYCODE_DPAD_DOWN)
        activity.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Playlists", activity.focusedTitle)
        // The highlighted row's text is dark on the highlight.
        val row = activity.focusedView as android.view.ViewGroup
        assertEquals(activity.getColor(R.color.on_focus), (row.getChildAt(0) as android.widget.TextView).currentTextColor)
        save("1-home")
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("playlists") { activity.focusedTitle == "Road trip" }
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("songs") { activity.focusedTitle == "Neon Tidepool" }
        activity.press(KeyEvent.KEYCODE_DPAD_DOWN)
        save("2-playlist")
        activity.press(KeyEvent.KEYCODE_DPAD_CENTER)
        player.state.value = player.state.value.copy(positionMs = 83_000)
        save("3-now-playing")
        activity.press(KeyEvent.KEYCODE_SOFT_LEFT)
        save("4-options")
    }

    @Test
    fun `sign-in`() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        activity.focusedView.let { (it as android.widget.EditText).setText("music.example.com") }
        save("5-address")
        mini.prefs.rememberServer(base)
        activity.restart(CodeScreen(base, "Home Music"))
        waitFor("the code") { (activity.top as? CodeScreen)?.started != null }
        save("6-code")
    }

    @Test
    fun `sign-in, with someone signed in to SuperAwesomeMagicalOrchestrator on the phone`() {
        // The orchestrator can't yet be told from an impostor: the row says where it would sign in, and waits.
        mini.orchestrator = OrchestratorSignIn(
            object : OrchestratorAgent {
                override fun status() = OrchestratorLink.Status(true, "https://orchestrator.example.com", "maddie", "Maddie")
                override fun signIn() = OrchestratorLink.SignIn.Ticket("https://music.example.com", "a.ticket", confirm = true, certificate = "c3rt")
                override fun confirmed(ticket: OrchestratorLink.SignIn.Ticket) = Unit
            },
            mini.api, mini.prefs,
        )
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fun row(): android.widget.TextView? {
            val found = ArrayList<android.view.View>()
            activity.window.decorView.findViewsWithText(found, "Continue as Maddie", android.view.View.FIND_VIEWS_WITH_TEXT)
            return found.filterIsInstance<android.widget.TextView>().firstOrNull { it.isShown }
        }
        waitFor("the row") { mini.orchestrator.row?.place != null && row() != null }
        assertEquals("Continue as Maddie\nat music.example.com", row()!!.text.toString())
        // Nothing was handed in: the ticket waits for a press.
        assertEquals(false, mini.prefs.signedIn)
        // Up from the address: the highlight is on the row.
        activity.press(KeyEvent.KEYCODE_DPAD_UP)
        save("7-continue-as")
    }
}
