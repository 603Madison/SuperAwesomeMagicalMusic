package com.music.server.lite

import com.music.server.ApiError
import com.music.server.Services
import com.music.server.auth.SESSION_COOKIE
import com.music.server.auth.clearedSessionCookie
import com.music.server.auth.deviceName
import com.music.server.auth.isHttps
import com.music.server.auth.passwordUser
import com.music.server.auth.safeReturn
import com.music.server.auth.signIn
import com.music.server.lite.Lite.Companion.liteCookie
import com.music.server.util.Random
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("samm.lite")

/**
 * The lite web player, at /lite: plain pages made on the server for old and slow browsers (old
 * iPads, e-readers, smart TVs), with a small script that plays music and keeps it playing while
 * pages change. Everything works through links and forms; the script adds playing a list, the
 * player at the bottom and Now playing. Not linked from the main web player.
 */
fun Route.liteRoutes(services: Services) {
    route("/lite") {
        get("/lite.css") { LiteAssets.respond(call, LiteAssets.css) }
        get("/lite.js") { LiteAssets.respond(call, LiteAssets.js) }

        page(services, "") { homePage() }
        page(services, "/") { homePage() }
        page(services, "/login", signedIn = false) { loginPage() }
        post("/login") { handle(services, call, signedIn = false) { login() } }
        page(services, "/welcome", signedIn = false) { welcomePage() }
        action(services, "/signout") { signOut() }

        page(services, "/search") { searchPage() }
        page(services, "/artist") { artistPage() }
        page(services, "/album") { albumPage() }
        page(services, "/song") { songPage() }
        page(services, "/play") { playPage() }
        page(services, "/now") { nowPage() }

        page(services, "/playlists") { playlistsPage() }
        action(services, "/playlists") { createPlaylist() }
        page(services, "/playlist/{id}") { playlistPage() }
        action(services, "/playlist/{id}/remove") { removeFromPlaylist() }
        page(services, "/add") { addPage() }
        action(services, "/add") { addToPlaylist() }

        page(services, "/radio") { radioPage() }
        page(services, "/radio/sxm") { siriusXmPage() }
        page(services, "/radio/pandora") { pandoraPage() }
        action(services, "/radio/pandora/start") { startPandora() }
        page(services, "/radio/pandora/station") { pandoraStationPage() }
        page(services, "/radio/stations") { stationsPage() }
        action(services, "/favorite") { setFavorite() }
        action(services, "/station/skip") { stationSkip() }
        action(services, "/station/thumb") { stationThumb() }

        page(services, "/history") { historyPage() }
        page(services, "/heard") { heardPage() }
        page(services, "/wrapped") { wrappedPage() }
        page(services, "/devices") { devicesPage() }
        action(services, "/devices/command") { deviceCommand() }
        action(services, "/devices/transfer") { deviceTransfer() }
        page(services, "/settings") { settingsPage() }
        action(services, "/settings") { saveSettings() }
    }
}

private fun Route.page(services: Services, path: String, signedIn: Boolean = true, block: suspend Lite.() -> Unit) {
    get(path) { handle(services, call, signedIn, block) }
}

private fun Route.action(services: Services, path: String, block: suspend Lite.() -> Unit) {
    post(path) { handle(services, call, signedIn = true, block) }
}

/** Runs a page: signed out, the sign-in page instead; anything that goes wrong, as a page saying so. */
private suspend fun handle(services: Services, call: ApplicationCall, signedIn: Boolean, block: suspend Lite.() -> Unit) {
    val lite = Lite(call, services, Lite.principalOf(call, services))
    try {
        if (signedIn && lite.principal == null) {
            val next = if (call.request.local.method.value == "GET") call.request.uri else "/lite"
            return lite.loginPage(HttpStatusCode.Unauthorized, next)
        }
        lite.block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val (status, message) = Lite.messageOf(e)
        if (status == HttpStatusCode.InternalServerError) log.error("Lite page failed: ${call.request.uri.substringBefore('?')}", e)
        with(lite) {
            page("Something went wrong", status) {
                h1("Something went wrong")
                notice(message, "note err")
                el("p") { a("/lite", "Back to the start", "btn") }
            }
        }
    }
}

/** Off to [path] (one of the lite pages) after a form: the browser asks for it with GET. */
internal suspend fun Lite.seeOther(path: String) {
    call.response.header(HttpHeaders.Location, liteReturn(path))
    call.respondText("", status = HttpStatusCode.SeeOther)
}

/** [path] if it's one of the lite pages, else the start. */
internal fun liteReturn(path: String?): String = safeReturn(path).takeIf { it == "/lite" || it.startsWith("/lite/") || it.startsWith("/lite?") } ?: "/lite"

// ---- Signing in and out ----

/**
 * The sign-in page: the server's single sign-on button and the password form. Before there's a
 * sign-in, the form's token comes from a cookie made for this page.
 */
internal suspend fun Lite.loginPage(status: HttpStatusCode = HttpStatusCode.OK, next: String? = null, error: String? = null) {
    if (principal != null && status == HttpStatusCode.OK) return call.respondRedirect(liteReturn(next ?: call.request.queryParameters["next"]))
    val settings = services.settings.get()
    val oidc = settings.oidc.takeIf { it.enabled && it.issuer.isNotBlank() && it.clientId.isNotBlank() }
    val then = liteReturn(next ?: call.request.queryParameters["next"])
    val pre = call.request.cookies[Lite.PRE_COOKIE]?.takeIf { it.length in 20..100 } ?: Random.token().also {
        call.response.cookies.append(
            Cookie(Lite.PRE_COOKIE, it, CookieEncoding.RAW, maxAge = 24 * 3600, path = "/lite", secure = call.isHttps, httpOnly = true, extensions = mapOf("SameSite" to "Lax")),
        )
    }
    page("Sign in", status) {
        h1(settings.serverName)
        error?.let { notice(it, "note err") }
        if (oidc != null) {
            el("p") {
                el("a", "class" to "btn big", "href" to url("/api/auth/oidc/start", "return" to "/lite/welcome")) { text(oidc.buttonLabel) }
            }
        }
        val passwords = settings.localLogin || oidc == null
        if (!passwords) el("p", "class" to "muted") { text("Admins can still sign in with a password:") }
        el("form", "method" to "post", "action" to "/lite/login", "class" to "stack", "data-full" to true) {
            hidden("_csrf", services.box.sign("lite-login|$pre"))
            hidden("next", then)
            el("label", "for" to "u") { text("Username") }
            empty("input", "id" to "u", "name" to "username", "autocomplete" to "username", "autocapitalize" to "none", "autocorrect" to "off", "required" to true)
            el("label", "for" to "pw") { text("Password") }
            empty("input", "id" to "pw", "name" to "password", "type" to "password", "autocomplete" to "current-password", "required" to true)
            el("button", "class" to "btn", "type" to "submit") { text("Sign in") }
        }
    }
}

private suspend fun Lite.login() {
    val params = form()
    val pre = call.request.cookies[Lite.PRE_COOKIE]
    if (pre == null || !services.box.verifySignature("lite-login|$pre", params["_csrf"].orEmpty())) {
        return loginPage(HttpStatusCode.Forbidden, params["next"], "That sign-in page was out of date (or cookies are off): try again.")
    }
    val user = try {
        passwordUser(services, call, params["username"].orEmpty().trim(), params["password"].orEmpty())
    } catch (e: ApiError) {
        return loginPage(e.status, params["next"], e.message)
    }
    signIn(services, call, user, app = false, deviceName = deviceName(call))
    seeOther(liteReturn(params["next"]))
}

/**
 * Where single sign-on comes back to. The sign-in cookie is only sent with the browser's own
 * navigation from this site, and the way back from the identity provider isn't that, so this
 * page takes the browser on to the start itself.
 */
private suspend fun Lite.welcomePage() {
    if (principal != null) return call.respondRedirect("/lite")
    page("Signing in") {
        raw("<meta http-equiv=\"refresh\" content=\"0;url=/lite\">")
        h1("Signing you in…")
        el("p") { a("/lite", "Carry on", "btn") }
    }
}

private suspend fun Lite.signOut() {
    form()
    call.request.cookies[SESSION_COOKIE]?.let { services.sessions.revokeToken(it) }
    call.response.cookies.append(call.clearedSessionCookie())
    seeOther("/lite/login")
}

// ---- Settings ----

private suspend fun Lite.settingsPage() {
    page("Settings") {
        h1("Settings")
        doneNotice(doneMessage())
        post("/lite/settings", "stack") {
            el("label", "for" to "kbps") { text("Stream quality") }
            el("select", "id" to "kbps", "name" to "kbps") {
                for (step in Lite.QUALITY_STEPS) {
                    el("option", "value" to step, "selected" to (step == kbps)) { text(if (step == 0) "Original quality" else "Up to $step kbps") }
                }
            }
            el("p", "class" to "muted") { text("Lower uses less data. Songs over it are converted by the server.") }
            el("button", "class" to "btn", "type" to "submit") { text("Save") }
        }
        h2("Your account")
        el("p") { text("Signed in as ${user.displayName.ifBlank { user.username }} (${user.username}).") }
        post("/lite/signout", full = true) {
            el("button", "class" to "btn", "type" to "submit") { text("Sign out") }
        }
        el("p", "class" to "muted") {
            text("Sources, accounts and the rest are in the ")
            a("/", "full web player")
            text(".")
        }
    }
}

private suspend fun Lite.saveSettings() {
    val kbps = form()["kbps"]?.toIntOrNull()?.takeIf { it in Lite.QUALITY_STEPS } ?: throw ApiError.badRequest("Pick a quality")
    call.response.cookies.append(call.liteCookie(Lite.KBPS_COOKIE, kbps.toString()))
    seeOther("/lite/settings?done=saved")
}

private val DONE = mapOf(
    "created" to "Made the playlist.",
    "added" to "Added to the playlist.",
    "removed" to "Taken out of the playlist.",
    "saved" to "Saved.",
    "sent" to "Sent.",
    "moved" to "Moving the music there.",
    "favorite" to "In your favourites, on the Radio page.",
    "unfavorite" to "Taken out of your favourites.",
    "skipped" to "Skipped.",
    "thumbed" to "Thanks: the station will take that into account.",
)

/** What a form just did, when the page after it says (by a fixed word, so no one can put words here). */
internal fun Lite.doneMessage(): String? = DONE[call.request.queryParameters["done"]]

internal fun Html.doneNotice(message: String?) {
    if (message != null) el("p", "class" to "note ok", "role" to "status") { text(message) }
}
