package com.music.connect.net

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.music.connect.data.Prefs
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Signing in to the server. With single sign-on (Authentik…) the server's own page does it, in a
 * browser tab: the person signs in there (or already is), agrees to sign the app in, and the
 * page hands back a one-time code at `sammusic://signed-in`, which only works together with the
 * secret this app made for that sign-in (PKCE), so another app catching the link gets nothing.
 */
class SignIn(private val context: Context, private val api: ServerApi, private val prefs: Prefs) {
    private val pending = context.getSharedPreferences("sign-in", Context.MODE_PRIVATE)

    suspend fun signInWithPassword(server: String, username: String, password: String) {
        val config = api.authConfig(server)
        val signedIn = api.login(server, username, password, deviceName())
        prefs.signIn(server, signedIn.token ?: throw ServerException(0, "The server sent no sign-in"), signedIn.user, config.serverName)
    }

    /** Opens the server's sign-in page in a browser tab. */
    fun startBrowserSignIn(server: String) {
        val verifier = token(48)
        val state = token(24)
        pending.edit {
            putString("server", server)
            putString("verifier", verifier)
            putString("state", state)
        }
        val url = "$server/connect-app".toUri().buildUpon()
            .appendQueryParameter("challenge", challenge(verifier))
            .appendQueryParameter("state", state)
            .appendQueryParameter("device", deviceName())
            .build()
        CustomTabsIntent.Builder().setShowTitle(true).build().apply {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }.launchUrl(context, url)
    }

    /** Finishes a browser sign-in from the link the server's page opened. */
    suspend fun finish(link: Uri) {
        val server = pending.getString("server", null) ?: throw ServerException(0, "That sign-in wasn't started here; try again")
        val verifier = pending.getString("verifier", null) ?: throw ServerException(0, "That sign-in expired; try again")
        val expected = pending.getString("state", null)
        val code = link.getQueryParameter("code") ?: throw ServerException(0, "The server sent no sign-in code")
        if (expected == null || link.getQueryParameter("state") != expected) throw ServerException(0, "That sign-in link wasn't for this sign-in")
        pending.edit { clear() }
        val config = api.authConfig(server)
        val signedIn = api.exchangeCode(server, code, verifier, deviceName())
        prefs.signIn(server, signedIn.token ?: throw ServerException(0, "The server sent no sign-in"), signedIn.user, config.serverName)
    }

    /**
     * Signing in with a code: [showCode] gets the code to type on a phone or computer that's
     * signed in (at the server's /link page), and this asks the server every few seconds until
     * it's allowed there (then it's signed in), turned down, or the code runs out.
     */
    suspend fun signInWithCode(server: String, showCode: (DeviceStart) -> Unit) {
        val config = api.authConfig(server)
        val started = api.startCodeSignIn(server, deviceName())
        showCode(started)
        var intervalMs = started.interval.coerceIn(1, 60) * 1000L
        val until = System.currentTimeMillis() + started.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(intervalMs)
            val poll = try {
                api.pollCodeSignIn(server, started.deviceCode)
            } catch (e: ServerException) {
                if (e.code in 400..499 && e.code != 429) throw e
                continue
            } catch (_: java.io.IOException) {
                // A dropped connection: asked again next time round.
                continue
            }
            when (poll.status) {
                "pending" -> Unit
                "slow_down" -> intervalMs = (poll.interval?.times(1000L) ?: (intervalMs + 5_000)).coerceAtMost(60_000)
                "approved" -> {
                    val token = poll.token ?: throw ServerException(0, "The server sent no sign-in")
                    prefs.signIn(server, token, poll.user ?: throw ServerException(0, "The server sent no sign-in"), config.serverName)
                    return
                }
                else -> throw ServerException(0, poll.message ?: "The sign-in was turned down")
            }
        }
        throw ServerException(0, "The code expired. Get a new one.")
    }

    companion object {
        /** How the server lists this phone among the person's sign-ins. */
        fun deviceName(): String = listOf(Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }, Build.MODEL.orEmpty())
            .distinct().joinToString(" ").trim().take(60)

        private val random = SecureRandom()

        fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    }
}
