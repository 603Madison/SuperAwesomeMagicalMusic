package com.music.server.auth

import com.music.server.util.Random
import com.music.server.util.hex
import com.music.server.util.sha256
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Signing in with a code (OAuth's device authorization flow): a device that's awkward to type on
 * (a flip phone, a TV) shows a short code, the person types it on their phone or computer where
 * they're already signed in, and the device, which has been asking all along, gets its sign-in.
 *
 * The device holds a long secret (its device code) that only it knows; the short user code just
 * names the sign-in for the person approving it. Both are kept in memory, briefly, and work once.
 */
class DeviceLogins(private val clock: () -> Long = System::currentTimeMillis) {
    enum class Status { PENDING, APPROVED, DENIED }

    class Pending(
        val userCode: String,
        val deviceName: String,
        val createdAt: Long,
        @Volatile var intervalMs: Long = INTERVAL_MS,
        @Volatile var lastPollAt: Long = 0,
        @Volatile var status: Status = Status.PENDING,
        @Volatile var userId: String? = null,
    ) {
        val expiresAt: Long get() = createdAt + TTL_MS
    }

    /** What the device is told when it asks. */
    sealed interface Poll {
        data object Waiting : Poll
        data class SlowDown(val intervalMs: Long) : Poll
        data class Approved(val userId: String, val deviceName: String) : Poll
        data object Denied : Poll
        data object Expired : Poll
    }

    /** Keyed by the hash of the device code, so a memory dump doesn't hand out sign-ins. */
    private val byDevice = ConcurrentHashMap<String, Pending>()
    private val byUser = ConcurrentHashMap<String, String>()

    /** A new sign-in for [deviceName]: (the device's secret code, its entry). */
    fun start(deviceName: String): Pair<String, Pending> {
        purge()
        val deviceCode = Random.token(32)
        var userCode: String
        do {
            userCode = newUserCode()
        } while (byUser.putIfAbsent(userCode, idOf(deviceCode)) != null)
        val pending = Pending(userCode, deviceName, clock())
        byDevice[idOf(deviceCode)] = pending
        return deviceCode to pending
    }

    /** The device asking how its sign-in is going; too often, and it's asked to slow down. */
    fun poll(deviceCode: String): Poll {
        if (deviceCode.length !in 20..200) return Poll.Expired
        val id = idOf(deviceCode)
        val pending = byDevice[id] ?: return Poll.Expired
        val now = clock()
        if (now >= pending.expiresAt) {
            forget(id, pending)
            return Poll.Expired
        }
        synchronized(pending) {
            // A little slack: a poll that was held up on the way can arrive close behind the next.
            if (pending.lastPollAt > 0 && now - pending.lastPollAt < pending.intervalMs - SLACK_MS) {
                pending.lastPollAt = now
                pending.intervalMs = (pending.intervalMs + INTERVAL_MS).coerceAtMost(MAX_INTERVAL_MS)
                return Poll.SlowDown(pending.intervalMs)
            }
            pending.lastPollAt = now
            return when (pending.status) {
                Status.PENDING -> Poll.Waiting
                Status.DENIED -> {
                    forget(id, pending)
                    Poll.Denied
                }
                Status.APPROVED -> {
                    // Once: whoever polls with it next gets nothing.
                    forget(id, pending)
                    Poll.Approved(pending.userId!!, pending.deviceName)
                }
            }
        }
    }

    /** The sign-in waiting for approval under [userCode] (typed however), if there is one. */
    fun find(userCode: String): Pending? {
        val code = normalize(userCode) ?: return null
        val pending = byUser[code]?.let { byDevice[it] } ?: return null
        return pending.takeIf { it.status == Status.PENDING && clock() < it.expiresAt }
    }

    /** Approves (for [userId]) or turns down the sign-in under [userCode]; false if there's none waiting. */
    fun decide(userCode: String, userId: String, allow: Boolean): Boolean {
        val pending = find(userCode) ?: return false
        synchronized(pending) {
            if (pending.status != Status.PENDING) return false
            pending.userId = userId
            pending.status = if (allow) Status.APPROVED else Status.DENIED
        }
        // Approved or not, the code can't be used again.
        byUser.remove(pending.userCode)
        return true
    }

    private fun forget(id: String, pending: Pending) {
        byDevice.remove(id, pending)
        byUser.remove(pending.userCode, id)
    }

    private fun purge() {
        val now = clock()
        byDevice.entries.filter { now >= it.value.expiresAt }.forEach { forget(it.key, it.value) }
        // Someone starting thousands of sign-ins mustn't fill the memory: the oldest go first.
        val over = byDevice.size - MAX_PENDING
        if (over > 0) {
            byDevice.entries.sortedBy { it.value.createdAt }.take(over + MAX_PENDING / 10).forEach { forget(it.key, it.value) }
        }
    }

    companion object {
        /** Consonants only (as OAuth suggests): nothing to mix up (no 0/O, 1/I) and no words spelled by chance. */
        const val ALPHABET = "BCDFGHJKLMNPQRSTVWXZ"
        const val TTL_MS = 10L * 60 * 1000
        const val INTERVAL_MS = 5_000L
        private const val MAX_INTERVAL_MS = 60_000L
        private const val SLACK_MS = 1_000L
        private const val MAX_PENDING = 5_000
        private val random = SecureRandom()

        private fun newUserCode(): String = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

        /** "bcdf-ghjk", "BCDF GHJK", "bcdfghjk": all "BCDFGHJK". Null if it can't be a code. */
        fun normalize(typed: String): String? {
            if (typed.length > 40) return null
            val code = typed.uppercase().filter { it.isLetterOrDigit() }
            return code.takeIf { it.length == 8 && it.all { c -> c in ALPHABET } }
        }

        /** How a code is shown: XXXX-XXXX. */
        fun display(code: String): String = "${code.take(4)}-${code.drop(4)}"

        private fun idOf(deviceCode: String): String = sha256(deviceCode).hex()
    }
}
