package com.music.mini.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Signing in with a code: the server hands out a short code to show, the person types it on their
 * phone or computer (at the server's /link page), and meanwhile this asks the server every few
 * seconds whether they have. Asking too often gets "slow down", and the wait grows as asked.
 */
class CodeSignIn(
    private val api: Api,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** How it ended. */
    sealed interface Outcome {
        data class SignedIn(val token: String, val user: User) : Outcome
        data class Failed(val message: String, val expired: Boolean) : Outcome
    }

    suspend fun start(server: String, deviceName: String): DeviceStart = api.startCodeSignIn(server, deviceName)

    /**
     * Asks until the code is approved, turned down or expires. A dropped connection isn't the end:
     * it's tried again at the next turn, until the code runs out.
     */
    suspend fun await(server: String, started: DeviceStart): Outcome {
        var intervalMs = started.interval.coerceIn(1, 60) * 1000L
        val until = clock() + started.expiresIn * 1000L
        while (true) {
            wait(intervalMs)
            if (clock() > until) return Outcome.Failed("The code expired. Get a new one.", expired = true)
            val poll = try {
                api.pollCodeSignIn(server, started.deviceCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerException) {
                // The server turned the request down for good (it doesn't do codes, say).
                if (e.code in 400..499 && e.code != 429) return Outcome.Failed(e.message ?: "The server said no", expired = false)
                continue
            } catch (_: Exception) {
                continue
            }
            when (poll.status) {
                "pending" -> Unit
                "slow_down" -> intervalMs = (poll.interval?.times(1000L) ?: (intervalMs + 5_000)).coerceAtMost(60_000)
                "approved" -> {
                    val token = poll.token ?: return Outcome.Failed("The server sent no sign-in", expired = false)
                    val user = poll.user ?: return Outcome.Failed("The server sent no sign-in", expired = false)
                    return Outcome.SignedIn(token, user)
                }
                "denied" -> return Outcome.Failed(poll.message ?: "The sign-in was turned down", expired = false)
                else -> return Outcome.Failed(poll.message ?: "The code expired. Get a new one.", expired = true)
            }
        }
    }
}
