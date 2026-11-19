package com.music.mini

import com.music.mini.net.Api
import com.music.mini.net.CodeSignIn
import com.music.mini.net.DeviceStart
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Asking the server, every few seconds, whether the code has been allowed yet. */
class CodeSignInTest {
    private val server = MockWebServer()
    private var now = 0L
    private val waits = mutableListOf<Long>()
    private lateinit var signIn: CodeSignIn
    private val base get() = server.url("/").toString().trimEnd('/')
    private val started = DeviceStart("d".repeat(43), "BCDF-GHJK", "https://music.example.com/link", interval = 5, expiresIn = 600)

    @Before
    fun start() {
        server.start()
        val api = Api(OkHttpClient(), { base }, { null })
        // Waiting just moves the clock on.
        signIn = CodeSignIn(api, wait = { waits += it; now += it }, clock = { now })
    }

    @After
    fun stop() = server.shutdown()

    private fun answer(json: String) = server.enqueue(MockResponse().setBody(json).setHeader("Content-Type", "application/json"))

    @Test
    fun `asks until it's allowed, slowing down when told to`() = runBlocking {
        answer("""{"status":"pending"}""")
        answer("""{"status":"slow_down","interval":10}""")
        answer("""{"status":"pending"}""")
        answer("""{"status":"approved","token":"secret-token","user":{"id":"u1","username":"maddie","displayName":"Maddie"}}""")
        val outcome = signIn.await(base, started)
        assertEquals(CodeSignIn.Outcome.SignedIn("secret-token", com.music.mini.net.User("u1", "maddie", "Maddie")), outcome)
        assertEquals(listOf(5_000L, 5_000L, 10_000L, 10_000L), waits)
        val polls = (1..4).map { server.takeRequest() }
        assertTrue(polls.all { it.path == "/api/auth/device/poll" && it.body.readUtf8() == """{"deviceCode":"${started.deviceCode}"}""" })
    }

    @Test
    fun `a dropped connection is tried again`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"Bad gateway"}"""))
        answer("""{"status":"approved","token":"t","user":{"id":"u1","username":"maddie","displayName":"Maddie"}}""")
        assertTrue(signIn.await(base, started) is CodeSignIn.Outcome.SignedIn)
    }

    @Test
    fun `turned down or expired ends it`() = runBlocking {
        answer("""{"status":"denied","message":"The sign-in was turned down"}""")
        assertEquals(CodeSignIn.Outcome.Failed("The sign-in was turned down", expired = false), signIn.await(base, started))
        answer("""{"status":"expired","message":"That code expired. Start again for a new one."}""")
        assertEquals(CodeSignIn.Outcome.Failed("That code expired. Start again for a new one.", expired = true), signIn.await(base, started))
    }

    @Test
    fun `stops asking once the code has run out`() = runBlocking {
        repeat(200) { answer("""{"status":"pending"}""") }
        val outcome = signIn.await(base, started)
        assertEquals(true, (outcome as CodeSignIn.Outcome.Failed).expired)
        // Every 5 seconds for 10 minutes: 120 times, no more.
        assertEquals(120, server.requestCount)
    }

    @Test
    fun `starting sends the phone's name`() = runBlocking {
        answer("""{"deviceCode":"${started.deviceCode}","userCode":"BCDF-GHJK","verificationUri":"https://m.example.com/link","verificationUriComplete":"https://m.example.com/link?code=BCDF-GHJK","interval":5,"expiresIn":600}""")
        val got = signIn.start(base, "Mini on TCL Flip Pro")
        assertEquals("BCDF-GHJK", got.userCode)
        val request = server.takeRequest()
        assertEquals("/api/auth/device/start", request.path)
        assertEquals("""{"deviceName":"Mini on TCL Flip Pro"}""", request.body.readUtf8())
    }

    @Test
    fun `server addresses are filled out`() {
        assertEquals("https://music.example.com", Api.normalize(" music.example.com/ "))
        assertEquals("http://192.168.1.20:8080", Api.normalize("192.168.1.20:8080"))
        assertEquals("http://nas.local:8080", Api.normalize("nas.local:8080"))
        assertEquals("http://music.example.com", Api.normalize("http://music.example.com"))
        assertEquals(null, Api.normalize(""))
        assertEquals(null, Api.normalize("not an address"))
    }
}
