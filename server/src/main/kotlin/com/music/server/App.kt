package com.music.server

import com.music.player.sources.SourceUnavailableException
import com.music.server.admin.adminRoutes
import com.music.server.auth.authRoutes
import com.music.server.music.musicRoutes
import com.music.server.playlists.playlistRoutes
import com.music.server.sources.sourceRoutes
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

@OptIn(ExperimentalSerializationApi::class)
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class Health(val ok: Boolean, val version: String)

private val log = LoggerFactory.getLogger("samm")

/** The server's web app: the API under /api, and the web player everywhere else. */
fun Application.module(services: Services) {
    install(ContentNegotiation) { json(ApiJson) }
    if (services.config.trustProxy) install(XForwardedHeaders)
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "same-origin")
        header("X-Frame-Options", "DENY")
        header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; " +
                "script-src 'self'; worker-src 'self' blob:; connect-src 'self'; font-src 'self'; object-src 'none'; " +
                "frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
        )
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api/") && call.request.path() != "/api/health" }
    }
    install(StatusPages) {
        exception<ApiError> { call, error -> call.respond(error.status, ErrorBody(error.message ?: "Error")) }
        exception<SourceUnavailableException> { call, error -> call.respond(HttpStatusCode.BadGateway, ErrorBody(error.message ?: "Not available")) }
        exception<BadRequestException> { call, error ->
            val cause = generateSequence(error as Throwable) { it.cause }.firstOrNull { it is SerializationException }
            call.respond(HttpStatusCode.BadRequest, ErrorBody(cause?.message?.substringBefore('\n')?.let { "That request isn't right: $it" } ?: "That request isn't right"))
        }
        exception<Throwable> { call, error ->
            log.error("Failed: ${call.request.path()}", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("Something went wrong on the server"))
        }
    }

    routing {
        get("/api/health") { call.respond(Health(true, Services.version)) }
        authRoutes(services)
        adminRoutes(services)
        sourceRoutes(services)
        musicRoutes(services)
        playlistRoutes(services)
        route("/api/{...}") {
            handle { call.respond(HttpStatusCode.NotFound, ErrorBody("No such API")) }
        }

        // The web player. Its files carry hashes in their names, so they're cached for good;
        // the page itself is always checked, so an updated server is picked up at once.
        if (Services::class.java.getResource("/web/index.html") != null) {
            staticResources("/", "web") {
                default("index.html")
                cacheControl { url ->
                    if (url.path.contains("/assets/")) listOf(CacheControl.MaxAge(31_536_000, visibility = CacheControl.Visibility.Public))
                    else listOf(CacheControl.NoCache(null))
                }
            }
        } else {
            get("/") {
                call.respondText(
                    "The SuperAwesomeMagicalMusic server is running, but this build has no web player (build web/ first).",
                    ContentType.Text.Plain,
                )
            }
        }
    }
}
