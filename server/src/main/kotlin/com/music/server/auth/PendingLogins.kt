package com.music.server.auth

import com.music.server.util.Random
import com.music.server.util.secureEquals
import java.util.concurrent.ConcurrentHashMap

/**
 * Sign-ins in progress, kept briefly in memory: browsers on their way to the identity provider
 * and back, and codes handed to the app at the end of its browser sign-in.
 */
class PendingLogins(private val clock: () -> Long = System::currentTimeMillis) {
    data class OidcState(val nonce: String, val verifier: String, val returnTo: String, val createdAt: Long)

    data class AppCode(val userId: String, val challenge: String, val createdAt: Long)

    private val states = ConcurrentHashMap<String, OidcState>()
    private val codes = ConcurrentHashMap<String, AppCode>()

    fun startOidc(returnTo: String): Pair<String, OidcState> {
        purge()
        val state = Random.token()
        val pending = OidcState(nonce = Random.token(), verifier = Random.token(48), returnTo = returnTo, createdAt = clock())
        states[state] = pending
        return state to pending
    }

    /** The sign-in [state] belongs to, once: it can't be replayed. */
    fun finishOidc(state: String): OidcState? = states.remove(state)?.takeIf { clock() - it.createdAt < STATE_TTL_MS }

    fun issueAppCode(userId: String, challenge: String): String {
        purge()
        val code = Random.token()
        codes[code] = AppCode(userId, challenge, clock())
        return code
    }

    /** The user for [code], if [verifier] is the one its challenge was made from (PKCE). */
    fun redeemAppCode(code: String, verifier: String): String? {
        val found = codes.remove(code) ?: return null
        if (clock() - found.createdAt > CODE_TTL_MS) return null
        return found.userId.takeIf { secureEquals(OidcClient.challengeOf(verifier), found.challenge) }
    }

    private fun purge() {
        val now = clock()
        states.entries.removeIf { now - it.value.createdAt > STATE_TTL_MS }
        codes.entries.removeIf { now - it.value.createdAt > CODE_TTL_MS }
        // Someone opening thousands of sign-ins shouldn't fill the memory, nor (by emptying it)
        // cut off people signing in just then: the oldest go first.
        trim(states) { it.createdAt }
        trim(codes) { it.createdAt }
    }

    private fun <T> trim(map: ConcurrentHashMap<String, T>, createdAt: (T) -> Long) {
        val over = map.size - MAX_PENDING
        if (over <= 0) return
        map.entries.sortedBy { createdAt(it.value) }.take(over + MAX_PENDING / 10).forEach { map.remove(it.key, it.value) }
    }

    private companion object {
        const val STATE_TTL_MS = 10L * 60 * 1000
        const val CODE_TTL_MS = 2L * 60 * 1000
        const val MAX_PENDING = 10_000
    }
}
