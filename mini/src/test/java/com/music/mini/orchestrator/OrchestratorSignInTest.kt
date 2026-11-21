package com.music.mini.orchestrator

import android.content.Intent
import org.robolectric.RuntimeEnvironment
import com.music.mini.Prefs
import com.music.mini.net.Api
import com.music.mini.net.User
import com.music.mini.orchestrator.OrchestratorSignIn.Outcome
import com.music.mini.orchestrator.OrchestratorSignIn.Row
import com.music.mini.orchestrator.OrchestratorSignIn.Why
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mini signing in through SuperAwesomeMagicalOrchestrator: what it decides, and what it sends its server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrchestratorSignInTest {
    private val server = MockWebServer()
    private val prefs = Prefs(RuntimeEnvironment.getApplication())
    private val maddie = OrchestratorLink.Status(signedIn = true, server = "https://orchestrator.example.com", username = "maddie", displayName = "Maddie")
    private val approvalPage = Intent(OrchestratorLink.ACTION_APPROVE)

    private var answer = 200
    private val sent = mutableListOf<RecordedRequest>()

    /** The agent on the phone, played here. */
    private var status: OrchestratorLink.Status? = maddie
    private val answers = ArrayDeque<OrchestratorLink.SignIn>()
    private var asked = 0
    private val confirmed = mutableListOf<OrchestratorLink.SignIn.Ticket>()
    private val agent = object : OrchestratorAgent {
        override fun status() = status
        override fun signIn(): OrchestratorLink.SignIn {
            asked++
            return answers.removeFirstOrNull() ?: OrchestratorLink.SignIn.Unavailable("Nobody is signed in")
        }
        override fun confirmed(ticket: OrchestratorLink.SignIn.Ticket) { confirmed += ticket }
    }

    private lateinit var base: String
    private lateinit var signIn: OrchestratorSignIn

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/orchestrator" -> {
                    sent += request
                    if (answer == 200) json("""{"user":{"id":"u1","username":"maddie","displayName":"Maddie","role":"USER"},"token":"t0k3n"}""")
                    else json("""{"error":"That sign-in didn't work here. Sign in the usual way."}""", answer)
                }
                "/api/auth/config" -> json("""{"serverName":"Music at home","version":"1.32.0","localLogin":true,"orchestrator":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
        signIn = OrchestratorSignIn(agent, Api(OkHttpClient(), { "" }, { null }), prefs, deviceName = { "Mini on TCL Flip" })
    }

    @After
    fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun ticket(confirm: Boolean = false, url: String = base, jwt: String = "the.ticket.jwt") = OrchestratorLink.SignIn.Ticket(url, jwt, confirm, "c3rt")

    private val host get() = "at ${server.hostName}:${server.port}"

    @Test
    fun `a ticket to confirm is never used by itself - only on a press, for the address that was shown`() {
        val music = "https://music.example.com"
        val known = ticket(url = music)
        Why.entries.forEach { assertTrue(OrchestratorSignIn.uses(known, it, null)) }
        val first = ticket(confirm = true, url = music)
        assertFalse(OrchestratorSignIn.uses(first, Why.START, null))
        assertFalse(OrchestratorSignIn.uses(first, Why.START, music))
        assertFalse(OrchestratorSignIn.uses(first, Why.APPROVED, music))
        assertFalse(OrchestratorSignIn.uses(first, Why.TAPPED, null))
        assertTrue(OrchestratorSignIn.uses(first, Why.TAPPED, music))
        assertFalse(OrchestratorSignIn.uses(ticket(confirm = true, url = "https://music.elsewhere.example"), Why.TAPPED, music))
    }

    @Test
    fun `the row's words`() {
        assertNull(OrchestratorSignIn.label(false, null))
        assertNull(OrchestratorSignIn.label(false, maddie.copy(signedIn = false)))
        assertEquals("Continue as Maddie", OrchestratorSignIn.label(false, maddie))
        assertEquals("Continue as maddie", OrchestratorSignIn.label(false, maddie.copy(displayName = " ")))
        assertEquals("Continue with SuperAwesomeMagicalOrchestrator", OrchestratorSignIn.label(true, maddie))
        assertEquals("Continue with SuperAwesomeMagicalOrchestrator", OrchestratorSignIn.label(false, null, confirming = true))
        assertEquals("at music.example.com", OrchestratorSignIn.place("https://music.example.com:443/"))
        assertEquals("at 192.168.1.20:8090", OrchestratorSignIn.place("http://192.168.1.20:8090"))
    }

    @Test
    fun `at start, a ticket signs Mini in as signing in with a code does`() = runBlocking {
        answers += ticket()
        assertEquals(Outcome.SignedIn("Maddie"), signIn.opened())
        val request = sent.single()
        assertNull(request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"ticket\":\"the.ticket.jwt\"") && body.contains("\"deviceName\":\"Mini on TCL Flip\""))
        assertTrue(prefs.signedIn)
        assertEquals(base, prefs.server)
        assertEquals("t0k3n", prefs.token)
        assertEquals("Maddie", prefs.userName)
        assertEquals("Music at home", prefs.serverName)
        assertTrue(confirmed.isEmpty())
    }

    @Test
    fun `a server not agreed to yet is shown and waits for the press, which uses a fresh ticket and remembers`() = runBlocking {
        answers += ticket(confirm = true, jwt = "first.ticket")
        assertEquals(Outcome.Shown, signIn.opened())
        assertTrue(sent.isEmpty())
        assertFalse(prefs.signedIn)
        assertEquals(Row("Continue as Maddie", host), signIn.row)

        val fresh = ticket(confirm = true, jwt = "fresh.ticket")
        answers += fresh
        assertEquals(Outcome.SignedIn("Maddie"), signIn.tapped())
        assertTrue(sent.single().body.readUtf8().contains("fresh.ticket"))
        assertEquals(listOf(fresh), confirmed)
        assertEquals(base, prefs.server)
    }

    @Test
    fun `a fresh ticket for another server isn't used - its address is shown instead`() = runBlocking {
        answers += ticket(confirm = true)
        signIn.opened()
        answers += ticket(confirm = true, url = "https://music.elsewhere.example")
        assertEquals(Outcome.Shown, signIn.tapped())
        assertTrue(sent.isEmpty())
        assertTrue(confirmed.isEmpty())
        assertFalse(prefs.signedIn)
        assertEquals(Row("Continue as Maddie", "at music.elsewhere.example"), signIn.row)
    }

    @Test
    fun `a server that says no leaves the usual sign-in, with no row`() = runBlocking {
        answer = 401
        answers += ticket()
        assertEquals(Outcome.Shown, signIn.opened())
        assertEquals(1, sent.size)
        assertFalse(prefs.signedIn)
        assertNull(signIn.row)
        // The sign-in screen coming up again doesn't try by itself, nor bring the row back.
        answers += ticket()
        signIn.opened()
        assertEquals(1, sent.size)
        assertNull(signIn.row)
    }

    @Test
    fun `no orchestrator - the usual screen, unchanged`() = runBlocking {
        status = null
        assertEquals(Outcome.Shown, signIn.opened())
        assertTrue(sent.isEmpty())
        assertNull(signIn.row)
    }

    @Test
    fun `after signing out on purpose, nothing happens by itself, and the row still works`() = runBlocking {
        prefs.signIn("https://old.example.com", "old-token", User("u0", "old", "Old"), "Old")
        prefs.signOut()
        assertTrue(prefs.signedOutOnPurpose)
        answers += ticket()
        assertEquals(Outcome.Shown, signIn.opened())
        assertEquals(0, asked)
        assertEquals(Row("Continue as Maddie"), signIn.row)

        assertEquals(Outcome.SignedIn("Maddie"), signIn.tapped())
        // Signed in again, by any means: the choice to stay signed out is over.
        assertFalse(prefs.signedOutOnPurpose)
    }

    @Test
    fun `the first time, the orchestrator's page is only opened on a press`() = runBlocking {
        status = OrchestratorLink.Status(true, "", "", "")
        answers += OrchestratorLink.SignIn.NeedsApproval(approvalPage)
        assertEquals(Outcome.Shown, signIn.opened())
        assertEquals(Row("Continue with SuperAwesomeMagicalOrchestrator"), signIn.row)
        answers += OrchestratorLink.SignIn.NeedsApproval(approvalPage)
        assertEquals(Outcome.Approve(approvalPage), signIn.tapped())
        // Said yes there: who it is and where to, and still a press away.
        status = maddie
        answers += ticket(confirm = true)
        assertEquals(Outcome.Shown, signIn.approved())
        assertEquals(Row("Continue as Maddie", host), signIn.row)
        assertTrue(sent.isEmpty())
        answers += ticket(confirm = true)
        assertEquals(Outcome.SignedIn("Maddie"), signIn.tapped())
    }

    @Test
    fun `a press that comes to nothing takes the row away`() = runBlocking {
        prefs.signIn("https://old.example.com", "old-token", User("u0", "old", "Old"), "Old")
        prefs.signOut()
        signIn.opened()
        assertEquals(Row("Continue as Maddie"), signIn.row)
        assertEquals(Outcome.Shown, signIn.tapped())
        assertNull(signIn.row)
    }
}
