package com.music.player.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.music.player.data.PlaylistDao
import com.music.player.net.AppJson
import com.music.player.settings.Account
import com.music.player.settings.AuthentikConfig
import com.music.player.settings.SettingsRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Signs in to the app with authentik (OpenID Connect, authorization code + PKCE through the
 * browser). The account's `sub` claim becomes the owner of the user's playlists.
 *
 * In authentik: create an OAuth2/OpenID provider with client type "Public", redirect URI
 * `com.music.player:/oauth2redirect`, and an application using it. The issuer URL is
 * `https://<authentik>/application/o/<application slug>/`.
 */
class AuthentikAuth(
    private val context: Context,
    private val settings: SettingsRepository,
    private val playlists: PlaylistDao,
) {
    /** Discovers the provider and returns the browser intent that starts sign-in. */
    suspend fun signInIntent(config: AuthentikConfig): Intent {
        val issuer = config.issuerUrl.trim().let { if (it.endsWith("/")) it else "$it/" }.toUri()
        val serviceConfig = discover(issuer)
        val request = AuthorizationRequest.Builder(serviceConfig, config.clientId.trim(), ResponseTypeValues.CODE, REDIRECT_URI)
            .setScopes("openid", "profile", "email", "offline_access")
            .build()
        val service = AuthorizationService(context)
        return try {
            service.getAuthorizationRequestIntent(request)
        } finally {
            service.dispose()
        }
    }

    /** Finishes sign-in with the browser's result and stores the account. */
    suspend fun completeSignIn(result: Intent?): Account {
        if (result == null) throw AuthException("Sign-in was cancelled")
        AuthorizationException.fromIntent(result)?.let { throw AuthException(it.errorDescription ?: it.error ?: "Sign-in failed") }
        val response = AuthorizationResponse.fromIntent(result) ?: throw AuthException("Sign-in was cancelled")
        val state = AuthState(response, null)
        val service = AuthorizationService(context)
        val tokens = try {
            suspendCancellableCoroutine { cont ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, error ->
                    when {
                        tokenResponse != null -> cont.resume(tokenResponse)
                        else -> cont.resumeWithException(AuthException(error?.errorDescription ?: error?.error ?: "Token request failed"))
                    }
                }
            }
        } finally {
            service.dispose()
        }
        state.update(tokens, null)
        val idToken = tokens.idToken ?: throw AuthException("authentik did not return an ID token; is the openid scope allowed?")
        val account = accountFromIdToken(idToken)
        // Playlists made before signing in become this user's.
        playlists.adoptUnowned(account.subject)
        settings.setAccount(account, state.jsonSerializeString())
        return account
    }

    suspend fun signOut() {
        settings.setAccount(null, null)
    }

    private suspend fun discover(issuer: Uri): AuthorizationServiceConfiguration = suspendCancellableCoroutine { cont ->
        AuthorizationServiceConfiguration.fetchFromIssuer(issuer) { config, error ->
            if (config != null) {
                cont.resume(config)
            } else {
                cont.resumeWithException(
                    AuthException("Couldn't reach authentik at $issuer: ${error?.errorDescription ?: error?.message ?: "unknown error"}"),
                )
            }
        }
    }

    companion object {
        val REDIRECT_URI: Uri = "com.music.player:/oauth2redirect".toUri()

        /** Reads the identity claims from an ID token (already validated by AppAuth). */
        fun accountFromIdToken(idToken: String): Account {
            val payload = idToken.split('.').getOrNull(1) ?: throw AuthException("Malformed ID token")
            val claims = AppJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload))).jsonObject
            val subject = claims.string("sub") ?: throw AuthException("ID token has no subject")
            return Account(
                subject = subject,
                username = claims.string("preferred_username") ?: claims.string("nickname"),
                email = claims.string("email"),
                name = claims.string("name"),
            )
        }

        private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

class AuthException(message: String) : Exception(message)
