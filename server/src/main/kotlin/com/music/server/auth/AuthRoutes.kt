package com.music.server.auth

import com.music.server.ApiError
import com.music.server.Services
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class OidcButton(val label: String)

@Serializable
data class AuthConfig(
    val serverName: String,
    val version: String,
    /** Whether the password form is offered (the break-glass admin can always use it). */
    val localLogin: Boolean,
    val oidc: OidcButton? = null,
    /** Whether apps may sign in with a ticket from SuperAwesomeMagicalOrchestrator (`POST /api/auth/orchestrator`). */
    val orchestrator: Boolean = false,
)

@Serializable
data class LoginRequest(val username: String, val password: String, val app: Boolean = false, val deviceName: String = "")

@Serializable
data class SignedIn(val user: User, val token: String? = null)

@Serializable
data class AppCodeRequest(val challenge: String)

@Serializable
data class AppCodeResponse(val code: String)

@Serializable
data class AppTokenRequest(val code: String, val verifier: String, val deviceName: String = "")

/** A sign-in ticket from SuperAwesomeMagicalOrchestrator on the same device, handed to this server. */
@Serializable
data class OrchestratorSignIn(val ticket: String, val deviceName: String = "")

@Serializable
data class Me(val user: User, val session: SessionInfo)

@Serializable
data class ProfileUpdate(val displayName: String)

@Serializable
data class PasswordChange(val current: String, val new: String)

private val log = LoggerFactory.getLogger("samm.auth")

private const val OIDC_STATE_COOKIE = "samm_oidc"

fun Route.authRoutes(services: Services) {
    route("/api/auth") {
        get("/config") {
            val settings = services.settings.get()
            val oidc = settings.oidc
            call.respond(
                AuthConfig(
                    serverName = settings.serverName,
                    version = Services.version,
                    localLogin = settings.localLogin || !oidc.enabled,
                    oidc = if (oidc.enabled && oidc.issuer.isNotBlank() && oidc.clientId.isNotBlank()) OidcButton(oidc.buttonLabel) else null,
                    orchestrator = settings.orchestratorUrl.isNotBlank(),
                ),
            )
        }

        post("/login") {
            val request = call.receive<LoginRequest>()
            if (request.username.length > 200 || request.password.length > 1_000) throw ApiError.unauthorized("Wrong username or password")
            val attempt = LoginThrottle.Attempt(clientAddress(call.request.local.remoteAddress, call.request.headers, services.config), request.username)
            services.throttle.attempt(attempt)
            val user = services.users.checkPassword(request.username, request.password)
                ?: throw ApiError.unauthorized("Wrong username or password")
            services.throttle.succeeded(attempt)
            if (user.disabled) throw ApiError.forbidden("This account is turned off")
            val settings = services.settings.get()
            if (!settings.localLogin && settings.oidc.enabled && !user.breakGlass) {
                throw ApiError.forbidden("Sign in with ${settings.oidc.buttonLabel.removePrefix("Sign in with ")} instead")
            }
            call.respond(signIn(services, call, user, request.app, request.deviceName))
        }

        post("/logout") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
                ?: call.request.cookies[SESSION_COOKIE]
            call.principal(services) // checks the CSRF header for cookie sign-ins
            token?.let { services.sessions.revokeToken(it) }
            call.response.cookies.append(call.clearedSessionCookie())
            call.respond(mapOf("ok" to true))
        }

        // The browser goes to the identity provider…
        get("/oidc/start") {
            val settings = services.settings.get()
            if (!settings.oidc.enabled) throw ApiError.notFound("Single sign-on isn't set up")
            val returnTo = safeReturn(call.request.queryParameters["return"])
            val discovery = services.oidc.discover(settings.oidc.issuer)
            val (state, pending) = services.pending.startOidc(returnTo)
            call.response.cookies.append(
                Cookie(
                    OIDC_STATE_COOKIE, state, CookieEncoding.RAW, maxAge = 600, path = "/api/auth/oidc",
                    secure = call.isHttps, httpOnly = true, extensions = mapOf("SameSite" to "Lax"),
                ),
            )
            call.respondRedirect(
                services.oidc.authorizationUrl(discovery, settings.oidc, redirectUri(services, call), state, pending.nonce, pending.verifier),
            )
        }

        // …and comes back here.
        get("/oidc/callback") {
            val params = call.request.queryParameters
            val state = params["state"].orEmpty()
            call.response.cookies.append(
                Cookie(OIDC_STATE_COOKIE, "", CookieEncoding.RAW, maxAge = 0, path = "/api/auth/oidc", secure = call.isHttps, httpOnly = true),
            )
            fun fail(message: String) = "/login?error=${message.encodeURLParameter()}"
            // The state must match the cookie set when this browser started, so nobody can sign
            // someone else's browser in to their own account.
            val pending = if (state.isNotEmpty() && call.request.cookies[OIDC_STATE_COOKIE] == state) services.pending.finishOidc(state) else null
            if (pending == null) return@get call.respondRedirect(fail("That sign-in expired or came from another browser. Try again."))
            params["error"]?.let { error ->
                return@get call.respondRedirect(fail(params["error_description"] ?: "The identity provider said: $error"))
            }
            val code = params["code"] ?: return@get call.respondRedirect(fail("The identity provider sent no sign-in code"))
            val target = try {
                val user = oidcUser(services, call, code, pending)
                services.users.touchLogin(user.id)
                val token = services.sessions.create(user.id, SessionKind.WEB, deviceName(call))
                call.response.cookies.append(call.sessionCookie(token, SessionStore.WEB_LIFETIME_MS / 1000))
                pending.returnTo
            } catch (e: ApiError) {
                log.warn("Single sign-on failed: {}", e.message)
                fail(e.message ?: "Sign-in failed")
            }
            call.respondRedirect(target)
        }

        // The app's sign-in: the person signs in to the web page in a browser tab the app opened,
        // agrees, and the page hands the app a one-time code for the app's own token.
        post("/app/code") {
            val principal = call.principal(services) ?: throw ApiError.unauthorized()
            if (!principal.viaCookie) throw ApiError.forbidden("Approve the app from a browser")
            val challenge = call.receive<AppCodeRequest>().challenge
            if (!challenge.matches(Regex("[A-Za-z0-9_-]{43}"))) throw ApiError.badRequest("That isn't a PKCE challenge")
            call.respond(AppCodeResponse(services.pending.issueAppCode(principal.user.id, challenge)))
        }

        post("/app/token") {
            val request = call.receive<AppTokenRequest>()
            val userId = services.pending.redeemAppCode(request.code, request.verifier)
                ?: throw ApiError.unauthorized("That sign-in code expired; sign in again")
            val user = services.users.byId(userId)?.takeIf { !it.disabled } ?: throw ApiError.unauthorized("This account is turned off")
            call.respond(signIn(services, call, user, app = true, request.deviceName))
        }

        // An app on a device signed in to SuperAwesomeMagicalOrchestrator: the agent there gave it a
        // short-lived signed ticket, and it's signed in here as the same person (server/README.md).
        post("/orchestrator") {
            val settings = services.settings.get()
            if (settings.orchestratorUrl.isBlank()) throw ApiError.notFound("No orchestrator is set up")
            val request = call.receive<OrchestratorSignIn>()
            // Refused tickets count against the address like wrong passwords (one that works is taken
            // back). The "account" is the address too: nobody's named until the ticket is checked, and
            // one place's bad tickets must never shut everyone else out.
            val address = clientAddress(call.request.local.remoteAddress, call.request.headers, services.config)
            val attempt = LoginThrottle.Attempt(address, "orchestrator:$address")
            services.throttle.attempt(attempt)
            val (user, claims) = try {
                val claims = services.orchestrator.verify(
                    request.ticket, settings.orchestratorUrl, listOfNotNull(settings.publicUrl.takeIf { it.isNotBlank() }, requestBase(call)), ORCHESTRATOR_APP,
                )
                orchestratorUser(services, claims) to claims
            } catch (e: OrchestratorTickets.Refused) {
                // The reason says what was wrong with the ticket or the account, never the ticket itself.
                log.warn("A sign-in through the orchestrator was refused: {}", e.message)
                throw ApiError.unauthorized("That sign-in didn't work here. Sign in the usual way.")
            }
            services.throttle.succeeded(attempt)
            call.respond(signIn(services, call, user, app = true, claims.deviceName.ifBlank { request.deviceName.trim().take(64) }))
        }
    }

    // Signing in with a code shown on another device (a flip phone, say).
    deviceRoutes(services)

    route("/api/me") {
        get {
            val principal = call.principal(services) ?: throw ApiError.unauthorized()
            call.respond(Me(principal.user, principal.session))
        }
        patch {
            val user = call.requireUser(services)
            call.respond(services.users.update(user.id, displayName = call.receive<ProfileUpdate>().displayName))
        }
        post("/password") {
            val principal = call.principal(services) ?: throw ApiError.unauthorized()
            val change = call.receive<PasswordChange>()
            if (!principal.user.hasPassword) throw ApiError.badRequest("This account signs in through single sign-on")
            val attempt = LoginThrottle.Attempt(clientAddress(call.request.local.remoteAddress, call.request.headers, services.config), principal.user.username)
            services.throttle.attempt(attempt)
            if (change.current.length > 1_000 || services.users.checkPassword(principal.user.username, change.current) == null) {
                throw ApiError.badRequest("The current password is wrong")
            }
            services.throttle.succeeded(attempt)
            services.users.setPassword(principal.user.id, change.new)
            // Signs out everywhere else.
            services.sessions.list(principal.user.id).filter { it.id != principal.session.id }.forEach { services.sessions.revoke(principal.user.id, it.id) }
            call.respond(mapOf("ok" to true))
        }
        get("/sessions") {
            val principal = call.principal(services) ?: throw ApiError.unauthorized()
            call.respond(services.sessions.list(principal.user.id, principal.session.id))
        }
        // A link for Claude's connector (see McpRoutes): shown once; it's listed, and revoked,
        // with the account's devices.
        post("/mcp-links") {
            val user = call.requireUser(services)
            val request = runCatching { call.receive<McpLinkRequest>() }.getOrDefault(McpLinkRequest())
            if (request.allAccounts && !user.isAdmin) throw ApiError.forbidden("Only admins can make a link that sees every account")
            val token = if (request.allAccounts) {
                services.sessions.create(user.id, SessionKind.MCP_ALL, "Claude connector (every account)")
            } else {
                services.sessions.create(user.id, SessionKind.MCP, "Claude connector")
            }
            call.respond(McpLink("${publicBase(services, call)}/mcp/$token"))
        }
        delete("/sessions/{id}") {
            val user = call.requireUser(services)
            if (!services.sessions.revoke(user.id, call.parameters["id"].orEmpty())) throw ApiError.notFound()
            call.respond(mapOf("ok" to true))
        }
    }
}

private suspend fun signIn(services: Services, call: ApplicationCall, user: User, app: Boolean, deviceName: String): SignedIn {
    services.users.touchLogin(user.id)
    return if (app) {
        val token = services.sessions.create(user.id, SessionKind.APP, deviceName.ifBlank { "SuperAwesomeMagicalMusic app" })
        SignedIn(user, token)
    } else {
        val token = services.sessions.create(user.id, SessionKind.WEB, deviceName(call))
        call.response.cookies.append(call.sessionCookie(token, SessionStore.WEB_LIFETIME_MS / 1000))
        SignedIn(user)
    }
}

/** Finds or makes the account for the person the provider signed in. */
private suspend fun oidcUser(services: Services, call: ApplicationCall, code: String, pending: PendingLogins.OidcState): User {
    val settings = services.settings.get().oidc
    val discovery = services.oidc.discover(settings.issuer)
    val identity = services.oidc.signIn(
        discovery, settings, services.settings.oidcClientSecret(), code, redirectUri(services, call), pending.verifier, pending.nonce,
    )
    return ssoAccount(services, settings, identity, "single sign-on")
}

/**
 * Whose account [identity] is, by this server's single sign-on rules: only the allowed group, the
 * role from the admin group, the account already tied to that subject, or a new one where that's
 * on. The same whether the identity provider said so itself or the orchestrator's ticket did ([via]).
 */
private suspend fun ssoAccount(services: Services, settings: com.music.server.OidcSettings, identity: OidcIdentity, via: String): User {
    if (settings.userGroup.isNotBlank() && settings.userGroup !in identity.groups && settings.adminGroup !in identity.groups) {
        throw ApiError.forbidden("Your account isn't in the group allowed to use this server (${settings.userGroup})")
    }
    val role = when {
        settings.adminGroup.isBlank() -> null
        settings.adminGroup in identity.groups -> Role.ADMIN
        else -> Role.USER
    }
    val existing = services.users.byOidc(identity.issuer, identity.subject)
    if (existing != null) {
        if (existing.disabled) throw ApiError.forbidden("This account is turned off")
        return if (role != null && role != existing.role) services.users.update(existing.id, role = role) else existing
    }
    if (!settings.autoCreateUsers) throw ApiError.forbidden("There's no account for you on this server yet; ask an admin")
    val user = services.users.create(
        username = services.users.freeUsername(identity.username),
        displayName = identity.displayName,
        role = role ?: Role.USER,
        oidcIssuer = identity.issuer,
        oidcSubject = identity.subject,
    )
    log.info("Created account {} for {} from {}", user.username, identity.username, via)
    return user
}

/** This app's name in the orchestrator's list of the family's apps, and in its tickets. */
private const val ORCHESTRATOR_APP = "music"

/**
 * Whose account a checked ticket is for, or [OrchestratorTickets.Refused] with why nobody's:
 * 1. the ticket says who they are at the identity provider, and it's this server's own provider:
 *    the same account, and the same rules, as signing in through it here ([ssoAccount]);
 * 2. otherwise the account with the ticket's username, if it's an ordinary one;
 * 3. otherwise nobody. Never the break-glass admin, and never an account that's turned off.
 */
private suspend fun orchestratorUser(services: Services, claims: OrchestratorTickets.Claims): User {
    val oidc = services.settings.get().oidc
    val subject = claims.oidcSubject
    val who = "${claims.username} (${claims.subject} on the orchestrator)"
    if (subject != null && oidc.enabled && oidc.clientId.isNotBlank() && OrchestratorTickets.sameProvider(claims.oidcIssuer, oidc.issuer)) {
        // Authentik gives each application its own issuer address and the person one subject in all
        // of them: the account is the one under this server's issuer.
        val identity = OidcIdentity(oidc.issuer.trim(), subject, claims.username, claims.displayName, claims.email, claims.groups)
        return try {
            ssoAccount(services, oidc, identity, "the orchestrator")
        } catch (e: ApiError) {
            throw OrchestratorTickets.Refused("$who: ${e.message}")
        }
    }
    val user = services.users.byUsername(claims.username)
    val why = when {
        user == null -> "there's no account for them on this server yet"
        user.breakGlass -> "the break-glass admin never signs in this way"
        user.disabled -> "their account is turned off"
        user.oidc -> "their account here belongs to an identity provider the ticket doesn't name"
        else -> return user
    }
    throw OrchestratorTickets.Refused("$who: $why")
}

/** The address the identity provider sends people back to; the admin page shows it to register. */
suspend fun redirectUri(services: Services, call: ApplicationCall): String = "${publicBase(services, call)}/api/auth/oidc/callback"

suspend fun publicBase(services: Services, call: ApplicationCall): String =
    services.settings.get().publicUrl.trimEnd('/').takeIf { it.isNotBlank() } ?: requestBase(call)

/** The address this request came to (the proxy's word for it, where one is trusted). */
fun requestBase(call: ApplicationCall): String {
    val origin = call.request.origin
    val defaultPort = (origin.scheme == "https" && origin.serverPort == 443) || (origin.scheme == "http" && origin.serverPort == 80)
    return "${origin.scheme}://${origin.serverHost}${if (defaultPort) "" else ":${origin.serverPort}"}"
}

/**
 * Only places on this server: never another site (an open redirect). Browsers drop tabs and line
 * breaks from addresses and read a backslash as a slash, so "/\t/evil.example" would become
 * "//evil.example": a path with any of those isn't taken.
 */
internal fun safeReturn(path: String?): String =
    path?.takeIf { p ->
        p.startsWith("/") && !p.startsWith("//") && p.length <= 2_000 && p.none { it.code < 0x20 || it.code == 0x7f || it == '\\' }
    } ?: "/"

/** "Firefox on Windows" and the like, to tell sign-ins apart. */
fun deviceName(call: ApplicationCall): String {
    val agent = call.request.header(HttpHeaders.UserAgent).orEmpty()
    val browser = when {
        "Edg/" in agent -> "Edge"
        "OPR/" in agent -> "Opera"
        "Firefox/" in agent -> "Firefox"
        "Chrome/" in agent -> "Chrome"
        "Safari/" in agent -> "Safari"
        else -> "Browser"
    }
    val system = when {
        "Windows" in agent -> "Windows"
        "Android" in agent -> "Android"
        "iPhone" in agent || "iPad" in agent -> "iOS"
        "Mac OS" in agent -> "macOS"
        "CrOS" in agent -> "ChromeOS"
        "Linux" in agent -> "Linux"
        else -> null
    }
    return if (system != null) "$browser on $system" else browser
}

@Serializable
data class McpLink(val url: String)

/** An admin's link can see every account, not just their own. */
@Serializable
data class McpLinkRequest(val allAccounts: Boolean = false)
