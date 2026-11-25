package com.music.server.lite

import com.music.server.util.hex
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes

/**
 * The lite player's stylesheet and script, read once from the jar. Their addresses carry a hash
 * of both ([version]), so browsers keep them until the server changes them.
 */
internal object LiteAssets {
    class Asset(val bytes: ByteArray, val type: ContentType) {
        val gzipped: ByteArray = Lite.gzip(bytes)
    }

    private fun load(name: String, type: ContentType) =
        Asset(LiteAssets::class.java.getResourceAsStream("/lite/$name")!!.use { it.readBytes() }, type)

    val css = load("lite.css", ContentType.Text.CSS.withParameter("charset", "utf-8"))
    val js = load("lite.js", ContentType.Application.JavaScript.withParameter("charset", "utf-8"))

    val version: String = java.security.MessageDigest.getInstance("SHA-256").digest(css.bytes + js.bytes).hex().take(12)

    suspend fun respond(call: ApplicationCall, asset: Asset) {
        val current = call.request.queryParameters["v"] == version
        call.response.header(HttpHeaders.CacheControl, if (current) "public, max-age=31536000, immutable" else "no-cache")
        call.response.header(HttpHeaders.Vary, "Accept-Encoding")
        if (call.request.header(HttpHeaders.AcceptEncoding)?.contains("gzip") == true) {
            call.response.header(HttpHeaders.ContentEncoding, "gzip")
            call.respondBytes(asset.gzipped, asset.type)
        } else {
            call.respondBytes(asset.bytes, asset.type)
        }
    }
}
