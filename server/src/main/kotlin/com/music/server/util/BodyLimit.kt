package com.music.server.util

import com.music.server.ApiError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.request.path
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer

/**
 * How much anyone may send in one request. Everything the server receives is read into memory,
 * so without a cap one endless upload (no sign-in needed to send one to the sign-in page) could
 * run it out of memory. Playlists and listening history get more room: a pasted list or a phone's
 * backlog of listens can be large.
 */
object BodyLimit {
    const val DEFAULT = 1L * 1024 * 1024
    const val LARGE = 16L * 1024 * 1024

    fun limitFor(path: String): Long =
        if (path.startsWith("/api/playlists") || path.startsWith("/api/history")) LARGE else DEFAULT

    private fun tooLarge() = ApiError(HttpStatusCode.PayloadTooLarge, "That's too much to send at once")

    val plugin = createApplicationPlugin("BodyLimit") {
        onCall { call ->
            // Most senders say how much is coming: turned away before anything is read.
            val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (length != null && length > limitFor(call.request.path())) throw tooLarge()
        }
        // Those that don't (a chunked upload) are cut off once they pass the limit.
        on(ReceiveRequestBytes) { call: ApplicationCall, body: ByteReadChannel ->
            // A WebSocket's frames aren't a body (and have their own size limit).
            if (call.request.headers[HttpHeaders.Upgrade] != null) return@on body
            val limit = limitFor(call.request.path())
            call.application.writer {
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = body.readAvailable(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw tooLarge()
                    channel.writeFully(buffer, 0, n)
                    channel.flush()
                }
            }.channel
        }
    }
}
