package com.music.server.connect

import com.music.server.Services
import com.music.server.auth.principal
import io.ktor.http.HttpHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URI

/**
 * Each open web player and phone keeps this open: it says what it's playing and hears what the
 * person's other devices are doing, and what to do. Pages on other sites can't open it with
 * the person's cookie: a browser's connection must come from this server's own pages.
 */
fun Route.connectRoutes(services: Services) {
    webSocket("/api/connect/ws") {
        val principal = call.principal(services)
        if (principal == null || principal.user.disabled) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Sign in first"))
            return@webSocket
        }
        if (principal.viaCookie && !sameOrigin(call.request.header(HttpHeaders.Origin), call.request.origin.serverHost, services.settings.get().publicUrl)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not from this server's pages"))
            return@webSocket
        }
        val params = call.request.queryParameters
        val id = params["id"]?.takeIf { it.matches(DEVICE_ID) } ?: run {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Which device?"))
            return@webSocket
        }
        // A speaker a phone plays on says what it is ("Sonos", "MusicCast").
        val detail = params["detail"]?.takeIf { params["kind"] == "speaker" && it.isNotBlank() }
        val device = services.connect.join(principal.user.id, id, params["name"].orEmpty(), params["kind"].orEmpty(), detail) { text ->
            outgoing.send(Frame.Text(text))
        }
        // Signed out elsewhere (or the account turned off): the connection doesn't outlive it.
        val watch = launch {
            while (isActive) {
                delay(SESSION_CHECK_MS)
                if (!services.sessions.isLive(principal.session.id)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Signed out"))
                    break
                }
            }
        }
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) services.connect.receive(device, frame.readText())
            }
        } finally {
            watch.cancel()
            services.connect.leave(device)
        }
    }
}

private val DEVICE_ID = Regex("[A-Za-z0-9_-]{8,64}")

private const val SESSION_CHECK_MS = 60_000L

/**
 * Whether a browser's [origin] is this server: the name it was reached by, or its public address.
 * Ports are left out: behind a proxy the two can differ, and other sites have other names anyway.
 */
internal fun sameOrigin(origin: String?, host: String?, publicUrl: String): Boolean {
    if (origin == null) return true // Not a browser.
    val from = runCatching { URI(origin).host }.getOrNull()?.lowercase() ?: return false
    val reached = host?.substringBeforeLast(':')?.trim('[', ']')?.lowercase()
    val public = runCatching { URI(publicUrl).host }.getOrNull()?.lowercase()
    return from == reached || from == public
}
