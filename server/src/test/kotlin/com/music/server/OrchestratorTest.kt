package com.music.server

import com.music.server.auth.Role
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.UUID

/** Signing in with SuperAwesomeMagicalOrchestrator's tickets, minted here as the orchestrator would. */
class OrchestratorTest {
    private val address = "https://orchestrator.example.com"
    private val provider = "https://auth.example.com/application/o/"
    private val key: ECKey = ECKeyGenerator(Curve.P_256).keyID("ticket-1").generate()

    /** How often the server asked for the orchestrator's keys, and what it's given. */
    @Volatile private var fetches = 0
    @Volatile private var published: String? = JWKSet(key.toPublicJWK()).toString()

    private fun services() = testServices(orchestratorFetch = { url ->
        fetches++
        assertEquals("$address/api/orchestrator/keys", url)
        published ?: throw java.io.IOException("unreachable")
    })

    /** The tests' server answers at http://localhost. */
    private fun ticket(
        username: String = "maddie",
        oidcSub: String? = "sub-123",
        oidcIss: String? = "${provider}orchestrator/",
        groups: List<String> = listOf("music"),
        audience: String = "http://localhost",
        app: String = "music",
        issuer: String = address,
        signedBy: ECKey = key,
        id: String = UUID.randomUUID().toString(),
        deviceName: String = "Maddie's Pixel",
        issuedAt: Long = System.currentTimeMillis(),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer).audience(audience).subject("orch-user-1").jwtID(id)
            .issueTime(Date(issuedAt)).notBeforeTime(Date(issuedAt)).expirationTime(Date(issuedAt + 120_000))
            .claim("app", app).claim("preferred_username", username).claim("name", "Maddie").claim("email", "maddie@example.com")
            .claim("groups", groups).claim("device_id", "device-1").claim("device_name", deviceName)
            .apply {
                oidcIss?.let { claim("oidc_iss", it) }
                oidcSub?.let { claim("oidc_sub", it) }
            }.build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signedBy.keyID).type(JOSEObjectType("samo-ticket+jwt")).build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(signedBy)) }.serialize()
    }

    private suspend fun Services.configure(
        sso: Boolean = true, userGroup: String = "music", adminGroup: String = "music-admins", autoCreate: Boolean = true, publicUrl: String = "",
    ) {
        settings.update {
            it.copy(
                orchestratorUrl = address, publicUrl = publicUrl,
                oidc = it.oidc.copy(enabled = sso, issuer = if (sso) "${provider}music/" else "", clientId = if (sso) "samm-client" else "",
                    userGroup = userGroup, adminGroup = adminGroup, autoCreateUsers = autoCreate),
            )
        }
    }

    private suspend fun ApplicationTestBuilder.send(ticket: String, deviceName: String = "A phone"): HttpResponse =
        createClient { }.post("/api/auth/orchestrator") { json("""{"ticket":"$ticket","deviceName":"$deviceName"}""") }

    private suspend fun HttpResponse.refused() {
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals("That sign-in didn't work here. Sign in the usual way.", obj().str("error"))
    }

    @Test
    fun `nothing changes where no orchestrator is set`() = serverTest(services()) {
        val client = createClient { }
        assertFalse(client.get("/api/auth/config").obj()["orchestrator"]!!.jsonPrimitive.boolean)
        val answer = send(ticket())
        assertEquals(HttpStatusCode.NotFound, answer.status)
        assertEquals("No orchestrator is set up", answer.obj().str("error"))
        assertEquals(0, fetches)
    }

    @Test
    fun `the sign-in settings say when tickets are taken`() = serverTest(services()) { services ->
        services.configure()
        assertTrue(createClient { }.get("/api/auth/config").obj()["orchestrator"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a ticket signs in the account with the same subject, as an app named after the device`() = serverTest(services()) { services ->
        services.configure()
        // The account was made by signing in through Authentik here: under this server's issuer, as its tokens spell it.
        val existing = services.users.create("maddie", "Maddie", oidcIssuer = "${provider}music/", oidcSubject = "sub-123")
        val answer = send(ticket(username = "another-name"))
        assertEquals(HttpStatusCode.OK, answer.status)
        val signedIn = answer.obj()
        assertEquals(existing.id, signedIn["user"]!!.jsonObject.str("id"))
        val client = createClient { }
        val me = client.get("/api/me") { bearerAuth(signedIn.str("token")) }.obj()
        assertEquals("APP", me["session"]!!.jsonObject.str("kind"))
        assertEquals("Maddie's Pixel", me["session"]!!.jsonObject.str("name"))
        assertEquals(1L + 1, services.users.count()) // nobody new
        assertNotNull(services.users.byId(existing.id)!!.lastLoginAt)
    }

    @Test
    fun `the issuer's last slash doesn't make a second account`() = serverTest(services()) { services ->
        services.configure()
        val existing = services.users.create("maddie", oidcIssuer = "${provider}music", oidcSubject = "sub-123")
        assertEquals(existing.id, send(ticket()).obj()["user"]!!.jsonObject.str("id"))
    }

    @Test
    fun `the request's device name is used when the ticket has none`() = serverTest(services()) { services ->
        services.configure()
        val token = send(ticket(deviceName = ""), deviceName = "Pixel 9").obj().str("token")
        assertEquals("Pixel 9", createClient { }.get("/api/me") { bearerAuth(token) }.obj()["session"]!!.jsonObject.str("name"))
    }

    @Test
    fun `someone new gets an account by the single sign-on rules`() = serverTest(services()) { services ->
        services.configure()
        // A password account that happens to have the name isn't taken over.
        val local = services.users.create("maddie", password = "local-password")
        val user = send(ticket(groups = listOf("music", "music-admins"))).obj()["user"]!!.jsonObject
        assertEquals("maddie-2", user.str("username"))
        assertEquals("Maddie", user.str("displayName"))
        assertEquals("ADMIN", user.str("role"))
        val made = services.users.byOidc("${provider}music/", "sub-123")!!
        assertTrue(made.oidc && made.id != local.id)

        // The role follows the groups at each sign-in, as with single sign-on.
        assertEquals("USER", send(ticket()).obj()["user"]!!.jsonObject.str("role"))
    }

    @Test
    fun `nobody new when accounts aren't made on first sign-in`() = serverTest(services()) { services ->
        services.configure(autoCreate = false)
        send(ticket()).refused()
        assertEquals(1L, services.users.count())
    }

    @Test
    fun `people outside the allowed group are turned away`() = serverTest(services()) { services ->
        services.configure()
        services.users.create("maddie", oidcIssuer = "${provider}music/", oidcSubject = "sub-123")
        send(ticket(groups = listOf("video"))).refused()
        // The admin group is allowed in too.
        assertEquals(HttpStatusCode.OK, send(ticket(groups = listOf("music-admins"))).status)
    }

    @Test
    fun `without single sign-on here, the username finds an ordinary account`() = serverTest(services()) { services ->
        services.configure(sso = false)
        val maddie = services.users.create("maddie", password = "local-password")
        // Whether or not the orchestrator knows the person through an identity provider.
        assertEquals(maddie.id, send(ticket()).obj()["user"]!!.jsonObject.str("id"))
        assertEquals(maddie.id, send(ticket(oidcSub = null, oidcIss = null)).obj()["user"]!!.jsonObject.str("id"))
        // Nobody is made this way.
        send(ticket(username = "someone-else", oidcSub = null, oidcIss = null)).refused()
        assertNull(services.users.byUsername("someone-else"))
    }

    @Test
    fun `another identity provider's subject is not this server's`() = serverTest(services()) { services ->
        services.configure()
        services.users.create("maddie", oidcIssuer = "${provider}music/", oidcSubject = "sub-123")
        // Same subject, said by a provider on another host: the single sign-on account stays shut.
        send(ticket(oidcIss = "https://auth.elsewhere.example/application/o/orchestrator/")).refused()
        // A password account by that name is still found by its username.
        val local = services.users.create("robin", password = "local-password")
        assertEquals(local.id, send(ticket(username = "robin", oidcIss = "https://auth.elsewhere.example/o/")).obj()["user"]!!.jsonObject.str("id"))
    }

    @Test
    fun `never the break-glass admin`() = serverTest(services()) { services ->
        services.configure(sso = false)
        send(ticket(username = "admin", oidcSub = null, oidcIss = null)).refused()
        send(ticket(username = "admin")).refused()
        services.configure(sso = true, userGroup = "", adminGroup = "")
        // With single sign-on, "admin" at the provider becomes a separate, ordinary account.
        val user = send(ticket(username = "admin")).obj()["user"]!!.jsonObject
        assertEquals("admin-2", user.str("username"))
        assertEquals("USER", user.str("role"))
        assertFalse(user["breakGlass"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an account that's turned off stays refused`() = serverTest(services()) { services ->
        services.configure()
        val sso = services.users.create("maddie", oidcIssuer = "${provider}music/", oidcSubject = "sub-123")
        services.users.update(sso.id, disabled = true)
        send(ticket()).refused()

        services.configure(sso = false)
        val local = services.users.create("robin", password = "local-password")
        services.users.update(local.id, disabled = true)
        send(ticket(username = "robin")).refused()
    }

    @Test
    fun `a ticket made for another server or another app is refused`() = serverTest(services()) { services ->
        services.configure()
        send(ticket(audience = "https://video.example.com")).refused()
        send(ticket(app = "video")).refused()
        assertEquals(1L, services.users.count())
    }

    @Test
    fun `the public address counts as this server, and so does the address the request came to`() = serverTest(services()) { services ->
        services.configure(publicUrl = "https://music.example.com")
        assertEquals(HttpStatusCode.OK, send(ticket(audience = "https://music.example.com/")).status)
        assertEquals(HttpStatusCode.OK, send(ticket(audience = "http://localhost")).status)
    }

    @Test
    fun `a ticket works once`() = serverTest(services()) { services ->
        services.configure()
        val once = ticket()
        assertEquals(HttpStatusCode.OK, send(once).status)
        send(once).refused()
    }

    @Test
    fun `forged, stale and foreign tickets are refused`() = serverTest(services()) { services ->
        services.configure()
        val other = ECKeyGenerator(Curve.P_256).keyID("ticket-1").generate()
        send(ticket(signedBy = other)).refused()
        send(ticket(issuer = "https://orchestrator.elsewhere.example")).refused()
        send(ticket(issuedAt = System.currentTimeMillis() - 10 * 60_000)).refused()
        send("not-a-ticket").refused()
        assertEquals(1L, services.users.count())
    }

    @Test
    fun `refused tickets count against the address like wrong passwords`() = serverTest(services()) { services ->
        services.configure()
        repeat(10) { send(ticket(app = "video")).refused() }
        // Even a good ticket waits now.
        assertEquals(HttpStatusCode.TooManyRequests, send(ticket()).status)
    }

    @Test
    fun `tickets that work don't use up the limit`() = serverTest(services()) { services ->
        services.configure()
        repeat(12) { assertEquals(HttpStatusCode.OK, send(ticket()).status) }
    }

    @Test
    fun `an orchestrator that can't be reached refuses, without an error page`() = serverTest(services()) { services ->
        services.configure()
        published = null
        send(ticket()).refused()
    }

    @Test
    fun `the admin sets the address, and can test it`() = serverTest(services()) { services ->
        val admin = browser()
        admin.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }
        val current = admin.get("/api/admin/settings").obj()["settings"]!!.jsonObject
        fun with(url: String) = kotlinx.serialization.json.JsonObject(current + ("orchestratorUrl" to kotlinx.serialization.json.JsonPrimitive(url))).toString()
        assertEquals(HttpStatusCode.BadRequest, admin.put("/api/admin/settings") { csrf(); json("""{"settings":${with("orchestrator.example.com")}}""") }.status)
        val saved = admin.put("/api/admin/settings") { csrf(); json("""{"settings":${with("  $address/ ")}}""") }.obj()
        assertEquals(address, saved["settings"]!!.jsonObject.str("orchestratorUrl"))
        assertEquals(address, services.settings.get().orchestratorUrl)

        val found = admin.post("/api/admin/orchestrator/test") { csrf(); json("""{"url":"$address/"}""") }.obj()
        assertTrue(found["ok"]!!.jsonPrimitive.boolean)
        assertEquals("Found the orchestrator at $address (1 signing key)", found.str("message"))
        published = null
        assertFalse(admin.post("/api/admin/orchestrator/test") { csrf(); json("""{"url":"$address"}""") }.obj()["ok"]!!.jsonPrimitive.boolean)
        assertFalse(admin.post("/api/admin/orchestrator/test") { csrf(); json("""{"url":""}""") }.obj()["ok"]!!.jsonPrimitive.boolean)
        // Only admins.
        assertEquals(HttpStatusCode.Unauthorized, createClient { }.post("/api/admin/orchestrator/test") { json("""{"url":"$address"}""") }.status)
    }

    @Test
    fun `refused tickets from one place don't keep the others out`() = serverTest(testServices(
        ServerConfig(dataDir = java.nio.file.Files.createTempDirectory("samm-test").toFile(), adminPassword = "break-glass-pw", trustProxy = true),
        orchestratorFetch = { published ?: throw java.io.IOException("unreachable") },
    )) { services ->
        services.configure()
        suspend fun from(address: String, ticket: String) =
            createClient { }.post("/api/auth/orchestrator") { header("X-Forwarded-For", address); json("""{"ticket":"$ticket"}""") }
        repeat(10) { assertEquals(HttpStatusCode.Unauthorized, from("203.0.113.9", ticket(app = "video")).status) }
        assertEquals(HttpStatusCode.TooManyRequests, from("203.0.113.9", ticket()).status)
        assertEquals(HttpStatusCode.OK, from("198.51.100.4", ticket()).status)
    }

    @Test
    fun `the keys are fetched from the address the admin set - no redirects, and only a small answer`() {
        val orchestrator = okhttp3.mockwebserver.MockWebServer()
        val elsewhere = okhttp3.mockwebserver.MockWebServer()
        val keys = JWKSet(key.toPublicJWK()).toString()
        elsewhere.enqueue(okhttp3.mockwebserver.MockResponse().setBody(keys))
        try {
            val base = orchestrator.url("/").toString().trimEnd('/')
            val services = testServices() // the real fetch
            fun check() = kotlinx.coroutines.runBlocking {
                try {
                    services.orchestrator.check(base)
                } catch (e: com.music.server.auth.OrchestratorTickets.Refused) {
                    null
                }
            }
            orchestrator.enqueue(okhttp3.mockwebserver.MockResponse().setBody(keys))
            assertEquals("Found the orchestrator at $base (1 signing key)", check())
            assertEquals("/api/orchestrator/keys", orchestrator.takeRequest().path)
            // Sent elsewhere: not followed.
            orchestrator.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/keys").toString()))
            assertNull(check())
            assertEquals(0, elsewhere.requestCount)
            // Too long to be keys.
            orchestrator.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"keys\":[],\"pad\":\"" + "x".repeat(300 * 1024) + "\"}"))
            assertNull(check())
            orchestrator.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(500))
            assertNull(check())
        } finally {
            orchestrator.shutdown()
            elsewhere.shutdown()
        }
    }

    @Test
    fun `the address can come from the environment`() {
        val config = ServerConfig.fromEnvironment(mapOf("SAMM_DATA_DIR" to "/tmp/x", "SAMM_ORCHESTRATOR_URL" to "$address/"))
        val services = testServices(config.copy(dataDir = java.nio.file.Files.createTempDirectory("samm-test").toFile(), adminPassword = "break-glass-pw"))
        kotlinx.coroutines.runBlocking { services.bootstrap() }
        assertEquals(address, kotlinx.coroutines.runBlocking { services.settings.get() }.orchestratorUrl)
        assertEquals(Role.ADMIN, kotlinx.coroutines.runBlocking { services.users.byUsername("admin") }!!.role)
    }
}
