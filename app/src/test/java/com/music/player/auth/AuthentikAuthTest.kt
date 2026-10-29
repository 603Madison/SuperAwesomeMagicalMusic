package com.music.player.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class AuthentikAuthTest {
    private fun jwt(claims: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"RS256"}""", claims, "signature").joinToString(".") { encoder.encodeToString(it.toByteArray()) }
    }

    @Test
    fun `account comes from the ID token claims`() {
        val account = AuthentikAuth.accountFromIdToken(
            jwt("""{"sub":"9f3c0a7e5b","preferred_username":"madison","email":"m@example.com","name":"Madison"}"""),
        )
        assertEquals("9f3c0a7e5b", account.subject)
        assertEquals("madison", account.username)
        assertEquals("Madison", account.displayName)
    }

    @Test
    fun `display name falls back to username then email`() {
        assertEquals("madison", AuthentikAuth.accountFromIdToken(jwt("""{"sub":"x","preferred_username":"madison"}""")).displayName)
        assertEquals("x", AuthentikAuth.accountFromIdToken(jwt("""{"sub":"x"}""")).displayName)
    }

    @Test(expected = AuthException::class)
    fun `a token without a subject is rejected`() {
        AuthentikAuth.accountFromIdToken(jwt("""{"email":"m@example.com"}"""))
    }
}
