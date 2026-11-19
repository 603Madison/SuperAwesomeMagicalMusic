package com.music.server.auth

import com.music.server.ApiError
import com.music.server.Services
import com.music.server.music.ReportLimiter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class DeviceStartRequest(val deviceName: String = "")

/** A code sign-in, started: the device shows [userCode] and the address, and asks every [interval] seconds. */
@Serializable
data class DeviceStart(
    val deviceCode: String,
    val userCode: String,
    /** Where to type the code: the server's own /link page. */
    val verificationUri: String,
    /** The same page with the code filled in (for a QR code). */
    val verificationUriComplete: String,
    val interval: Int,
    val expiresIn: Int,
)

@Serializable
data class DevicePollRequest(val deviceCode: String)

/**
 * How a code sign-in is going: "pending", "slow_down" (ask less often: every [interval] seconds),
 * "approved" (with the sign-in's [token], like the app's own sign-in), "denied" or "expired".
 */
@Serializable
data class DevicePoll(val status: String, val interval: Int? = null, val user: User? = null, val token: String? = null, val message: String? = null)

/** The sign-in waiting under a code, as the person approving it sees it. */
@Serializable
data class DeviceRequest(val userCode: String, val deviceName: String, val expiresIn: Int)

@Serializable
data class DeviceDecision(val userCode: String, val allow: Boolean)

private val log = LoggerFactory.getLogger("samm.auth")

/**
 * Signing in with a code from another device (see [DeviceLogins]). The device starts and asks
 * without being signed in; the person approves on the web player's /link page (or in the app),
 * signed in. Wrong codes count against them (and where they are) like wrong passwords do.
 */
fun Route.deviceRoutes(services: Services) {
    val starts = ReportLimiter(perMinute = 10, clock = services.clock)

    route("/api/auth/device") {
        post("/start") {
            val address = clientAddress(call.request.local.remoteAddress, call.request.headers, services.config)
            if (!starts.allow(address)) throw ApiError.tooMany("Too many sign-ins started. Try again in a minute.")
            val name = cleanName(runCatching { call.receive<DeviceStartRequest>() }.getOrDefault(DeviceStartRequest()).deviceName)
            val (deviceCode, pending) = services.deviceLogins.start(name)
            val link = "${publicBase(services, call)}/link"
            val shown = DeviceLogins.display(pending.userCode)
            call.respond(
                DeviceStart(
                    deviceCode = deviceCode,
                    userCode = shown,
                    verificationUri = link,
                    verificationUriComplete = "$link?code=$shown",
                    interval = (DeviceLogins.INTERVAL_MS / 1000).toInt(),
                    expiresIn = (DeviceLogins.TTL_MS / 1000).toInt(),
                ),
            )
        }

        post("/poll") {
            val deviceCode = call.receive<DevicePollRequest>().deviceCode
            val answer = when (val poll = services.deviceLogins.poll(deviceCode)) {
                DeviceLogins.Poll.Waiting -> DevicePoll("pending")
                is DeviceLogins.Poll.SlowDown -> DevicePoll("slow_down", interval = (poll.intervalMs / 1000).toInt())
                DeviceLogins.Poll.Denied -> DevicePoll("denied", message = "The sign-in was turned down")
                DeviceLogins.Poll.Expired -> DevicePoll("expired", message = "That code expired. Start again for a new one.")
                is DeviceLogins.Poll.Approved -> {
                    val user = services.users.byId(poll.userId)?.takeIf { !it.disabled }
                    if (user == null) {
                        DevicePoll("denied", message = "This account is turned off")
                    } else {
                        services.users.touchLogin(user.id)
                        val token = services.sessions.create(user.id, SessionKind.APP, poll.deviceName)
                        log.info("{} signed in {} with a code", user.username, poll.deviceName)
                        DevicePoll("approved", user = user, token = token)
                    }
                }
            }
            call.respond(answer)
        }

        // The person approving: what's asking, then yes or no.
        get("/request") {
            val principal = call.principal(services) ?: throw ApiError.unauthorized()
            val pending = findCounted(services, call, principal.user, call.request.queryParameters["code"].orEmpty())
            call.respond(
                DeviceRequest(
                    userCode = DeviceLogins.display(pending.userCode),
                    deviceName = pending.deviceName,
                    expiresIn = ((pending.expiresAt - services.clock()) / 1000).toInt().coerceAtLeast(0),
                ),
            )
        }

        post("/decide") {
            val principal = call.principal(services) ?: throw ApiError.unauthorized()
            val decision = call.receive<DeviceDecision>()
            val pending = findCounted(services, call, principal.user, decision.userCode)
            if (!services.deviceLogins.decide(pending.userCode, principal.user.id, decision.allow)) {
                throw ApiError.notFound("That code has already been used")
            }
            call.respond(mapOf("ok" to true))
        }
    }
}

/** The sign-in waiting under [code]; a code that isn't one counts as a wrong guess. */
private fun findCounted(services: Services, call: ApplicationCall, user: User, code: String): DeviceLogins.Pending {
    val attempt = LoginThrottle.Attempt(clientAddress(call.request.local.remoteAddress, call.request.headers, services.config), user.id)
    services.codeThrottle.attempt(attempt)
    val pending = services.deviceLogins.find(code)
        ?: throw ApiError.notFound("No sign-in is waiting for that code. Check it, or start again on the device for a new one.")
    services.codeThrottle.succeeded(attempt)
    return pending
}

/** The name a device gives itself, fit to show: one line, not too long. */
private fun cleanName(raw: String): String =
    raw.filter { it.code >= 0x20 && it.code != 0x7f }.trim().take(60).ifBlank { "A device" }
