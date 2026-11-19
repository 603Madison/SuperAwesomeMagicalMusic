package com.music.server.listen

import com.music.server.ApiError
import com.music.server.Services
import com.music.server.auth.requireUser
import com.music.server.music.ReportLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.listenRoutes(services: Services) {
    val listen = services.listen
    // A listen sends a few tries; each runs ffmpeg and a search, and AudD's cost money.
    val tries = ReportLimiter(perMinute = 30)
    val audd = ReportLimiter(perMinute = 6)

    route("/api/listen") {
        get("/status") {
            call.respond(listen.status(call.requireUser(services)))
        }

        // A recording (any common audio format, as the body); ?final=true on the last try of a listen.
        post {
            val user = call.requireUser(services)
            if (!tries.allow(user.id)) throw ApiError.tooMany("That's a lot of listening: try again in a minute")
            val audio = call.receive<ByteArray>()
            if (audio.isEmpty()) throw ApiError.badRequest("Nothing was recorded")
            val final = call.request.queryParameters["final"] == "true" && audd.allow(user.id)
            val type = call.request.contentType().toString().takeIf { it.startsWith("audio/") || it.startsWith("video/") }
            val candidate = call.request.queryParameters["candidate"]?.takeIf { it.length <= 20 }
            call.respond(listen.recognise(user, audio, type, final, candidate))
        }

        get("/heard") {
            call.respond(listen.heard(call.requireUser(services)))
        }

        delete("/heard/{id}") {
            val user = call.requireUser(services)
            if (!listen.forget(user, call.parameters["id"].orEmpty())) throw ApiError.notFound()
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}
