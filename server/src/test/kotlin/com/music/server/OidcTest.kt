package com.music.server

import com.music.server.auth.Role
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

/** Sign-in through an identity provider, played by a fake Authentik. */
class OidcTest {
    private val provider = MockWebServer()
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
    private lateinit var issuer: String

    /** What the fake provider puts in the next ID token. */
    @Volatile private var nonce = ""
    @Volatile private var groups = listOf("music")
    @Volatile private var subject = "user-123"
    @Volatile private var tokenIssuer: String? = null
    @Volatile private var signingKey: RSAKey = key
    @Volatile private var lastVerifier = ""

    @Before
    fun start() {
        provider.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/application/o/samm/.well-known/openid-configuration" -> MockResponse().setBody(
                    """{"issuer":"$issuer","authorization_endpoint":"${issuer}authorize/","token_endpoint":"${issuer}token/",
                       "jwks_uri":"${issuer}jwks/","userinfo_endpoint":"${issuer}userinfo/"}""",
                )
                "/application/o/samm/jwks/" -> MockResponse().setBody(JWKSet(key.toPublicJWK()).toString())
                "/application/o/samm/token/" -> {
                    val form = request.body.readUtf8()
                    lastVerifier = Regex("code_verifier=([^&]+)").find(form)?.groupValues?.get(1).orEmpty()
                    if ("code=good-code" !in form || request.getHeader("Authorization") == null) {
                        MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                    } else {
                        MockResponse().setBody("""{"access_token":"at","token_type":"Bearer","id_token":"${idToken()}"}""")
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        provider.start()
        issuer = provider.url("/application/o/samm/").toString()
    }

    @After
    fun stop() = provider.shutdown()

    private fun idToken(): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(tokenIssuer ?: issuer)
            .subject(subject)
            .audience("samm-client")
            .expirationTime(Date(System.currentTimeMillis() + 300_000))
            .issueTime(Date())
            .claim("nonce", nonce)
            .claim("preferred_username", "maddie")
            .claim("name", "Maddie")
            .claim("groups", groups)
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims).apply { sign(RSASSASigner(signingKey)) }.serialize()
    }

    private suspend fun Services.configure(userGroup: String = "music", adminGroup: String = "music-admins") {
        settings.update {
            it.copy(oidc = it.oidc.copy(enabled = true, issuer = issuer, clientId = "samm-client", userGroup = userGroup, adminGroup = adminGroup))
        }
        settings.setOidcClientSecret("secret")
    }

    /** Goes through the sign-in in a browser; returns where the server sent the browser at the end. */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.signIn(code: String = "good-code", tamperState: Boolean = false): Pair<String, io.ktor.client.HttpClient> {
        val browser = createClient {
            install(HttpCookies)
            followRedirects = false
        }
        val start = browser.get("/api/auth/oidc/start?return=/playlists")
        assertEquals(HttpStatusCode.Found, start.status)
        val authorize = Url(start.headers["Location"]!!)
        assertTrue(authorize.toString().startsWith("${issuer}authorize/"))
        assertEquals("S256", authorize.parameters["code_challenge_method"])
        nonce = authorize.parameters["nonce"]!!
        val state = authorize.parameters["state"]!!.let { if (tamperState) it + "x" else it }
        val back = browser.get("/api/auth/oidc/callback?code=$code&state=$state")
        assertEquals(HttpStatusCode.Found, back.status)
        return back.headers["Location"]!! to browser
    }

    @Test
    fun `a member of the group signs in and gets an account`() = serverTest { services ->
        services.configure()
        val (location, browser) = signIn()
        assertEquals("/playlists", location)
        val me = browser.get("/api/me").obj()["user"]!!.jsonObject
        assertEquals("maddie", me.str("username"))
        assertEquals("Maddie", me.str("displayName"))
        assertEquals("USER", me.str("role"))
        assertEquals("true", me.str("oidc"))
        assertTrue(lastVerifier.isNotEmpty())
    }

    @Test
    fun `the admin group makes admins, and roles follow the groups at each sign-in`() = serverTest { services ->
        services.configure()
        groups = listOf("music-admins")
        signIn()
        assertEquals(Role.ADMIN, services.users.byUsername("maddie")!!.role)
        groups = listOf("music")
        signIn()
        assertEquals(Role.USER, services.users.byUsername("maddie")!!.role)
        assertEquals(2L, services.users.count()) // the same account both times
    }

    @Test
    fun `people outside the group are turned away`() = serverTest { services ->
        services.configure()
        groups = listOf("someone-else")
        val (location, _) = signIn()
        assertTrue(location, location.startsWith("/login?error="))
        assertEquals(1L, services.users.count())
    }

    @Test
    fun `a local account with the same name isn't taken over`() = serverTest { services ->
        services.configure(userGroup = "")
        val local = services.users.create("maddie", password = "local password")
        signIn()
        assertEquals(local.id, services.users.byUsername("maddie")!!.id)
        assertTrue(services.users.byUsername("maddie-2")!!.oidc)
    }

    @Test
    fun `forged tokens and replayed sign-ins are refused`() = serverTest { services ->
        services.configure(userGroup = "")
        // Signed by someone else's key.
        signingKey = RSAKeyGenerator(2048).keyID("k1").generate()
        assertTrue(signIn().first.startsWith("/login?error="))
        signingKey = key
        // From another issuer.
        tokenIssuer = "https://evil.example.com/"
        assertTrue(signIn().first.startsWith("/login?error="))
        tokenIssuer = null
        // A state that isn't this browser's.
        assertTrue(signIn(tamperState = true).first.startsWith("/login?error="))
        // A bad code.
        assertTrue(signIn(code = "bad-code").first.startsWith("/login?error="))
        assertEquals(1L, services.users.count())
    }

    @Test
    fun `the return address can't lead off the server`() = serverTest { services ->
        services.configure(userGroup = "")
        val browser = createClient {
            install(HttpCookies)
            followRedirects = false
        }
        val start = browser.get("/api/auth/oidc/start?return=//evil.example.com/")
        val authorize = Url(start.headers["Location"]!!)
        nonce = authorize.parameters["nonce"]!!
        val back = browser.get("/api/auth/oidc/callback?code=good-code&state=${authorize.parameters["state"]}")
        assertEquals("/", back.headers["Location"])
    }
}
