package com.music.server.lite

import com.music.player.sources.SourceUnavailableException
import com.music.server.ApiError
import com.music.server.Services
import com.music.server.auth.Principal
import com.music.server.auth.SESSION_COOKIE
import com.music.server.auth.User
import com.music.server.auth.isHttps
import com.music.server.connect.sameOrigin
import com.music.server.music.ApiTrack
import com.music.server.music.Artwork
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZoneId
import java.util.zip.GZIPOutputStream

/**
 * One request to the lite player: who's asking, and how its pages are put together. Pages come
 * whole for a browser's own navigation, or as just the part that changes when the page's script
 * asks (`X-Lite: 1`), so the player at the bottom of the page keeps playing.
 */
internal class Lite(val call: ApplicationCall, val services: Services, val principal: Principal?) {
    val user: User get() = principal?.user ?: throw ApiError.unauthorized()
    val art: Artwork get() = services.artwork

    /** Asked for by the page's script: only the page's own content is sent. */
    private val partial = call.request.header("X-Lite") == "1"

    /** The form token for this sign-in: other sites' pages can't know it, so can't post forms as the person. */
    val csrf: String get() = csrfFor(services, principal ?: throw ApiError.unauthorized())

    /** The person's time zone, which the page's script keeps in a cookie (the server's otherwise). */
    val timeZone: String? = call.request.cookies[TZ_COOKIE]?.takeIf { it.length < 60 && runCatching { ZoneId.of(it) }.isSuccess }
    val zone: ZoneId = timeZone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()

    /** The bitrate cap chosen in Settings, kept in a cookie (0: original quality). */
    val kbps: Int = call.request.cookies[KBPS_COOKIE]?.toIntOrNull()?.takeIf { it in QUALITY_STEPS } ?: 0

    /** This browser's id among the person's devices (the script makes one and keeps it in a cookie). */
    val deviceId: String? = call.request.cookies[DEVICE_COOKIE]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,64}")) }

    private var form: Parameters? = null

    /** A posted form's fields; refused unless it came from this server's own page. */
    suspend fun form(): Parameters {
        form?.let { return it }
        val params = call.receiveParameters()
        val origin = call.request.header(HttpHeaders.Origin)
        if (origin != null && origin != "null" && !sameOrigin(origin, call.request.origin.serverHost, services.settings.get().publicUrl)) {
            throw ApiError.forbidden("That form came from another site")
        }
        if (principal != null) {
            if (!services.box.verifySignature("lite|${principal.session.id}", params["_csrf"].orEmpty())) {
                throw ApiError.forbidden("That form is out of date: go back, reload the page and try again")
            }
        }
        form = params
        return params
    }

    /** Sends the page: whole, or only its content (with its title and address in headers). */
    suspend fun page(title: String, status: HttpStatusCode = HttpStatusCode.OK, body: Html.() -> Unit) {
        val content = Html().apply(body).toString()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(HttpHeaders.Vary, "X-Lite, Accept-Encoding")
        val text = if (partial) {
            call.response.header("X-Lite-Title", title.encodeURLParameter())
            call.response.header("X-Lite-Url", call.request.uri)
            content
        } else {
            layout(title, content)
        }
        respondText(text, status)
    }

    private suspend fun respondText(text: String, status: HttpStatusCode) {
        val bytes = text.toByteArray()
        val type = ContentType.Text.Html.withParameter("charset", "utf-8")
        if (bytes.size > 1_400 && call.request.header(HttpHeaders.AcceptEncoding)?.contains("gzip") == true) {
            call.response.header(HttpHeaders.ContentEncoding, "gzip")
            call.respondBytes(gzip(bytes), type, status)
        } else {
            call.respondBytes(bytes, type, status)
        }
    }

    private fun layout(title: String, content: String): String {
        val page = Html()
        page.raw("<!doctype html>")
        page.el("html", "lang" to "en") {
            el("head") {
                empty("meta", "charset" to "utf-8")
                empty("meta", "name" to "viewport", "content" to "width=device-width, initial-scale=1")
                empty("meta", "name" to "color-scheme", "content" to "light dark")
                el("title") { text(if (title.isBlank()) "Music" else "$title · Music") }
                empty("link", "rel" to "stylesheet", "href" to "/lite/lite.css?v=${LiteAssets.version}")
            }
            el("body", "data-signed-in" to (principal != null)) {
                a("#main", "Skip to the page", "skip")
                if (principal != null) {
                    el("header", "class" to "bar") {
                        el("nav", "aria-label" to "Main") {
                            for ((href, label) in NAV) a(href, label)
                        }
                    }
                }
                el("main", "id" to "main", "tabindex" to "-1") {
                    raw(MARK_START)
                    raw(content)
                    raw(MARK_END)
                }
                if (principal != null) {
                    el("div", "id" to "player", "class" to "player", "hidden" to true) {}
                    el("script", "src" to "/lite/lite.js?v=${LiteAssets.version}") {}
                }
            }
        }
        return page.toString()
    }

    // ---- Pieces the pages share ----

    /** A form posted to [action], with its token. [full]: the browser loads the answer itself (signing in or out). */
    fun Html.post(action: String, cls: String? = null, full: Boolean = false, body: Html.() -> Unit) =
        el("form", "method" to "post", "action" to action, "class" to cls, "data-full" to full) {
            if (principal != null) hidden("_csrf", csrf)
            body()
        }

    /** A small cover, sized by the server; empty space when there's none. */
    fun Html.cover(url: String?, size: Int = 48, cls: String = "cv") {
        val src = sized(url, size * 2)
        if (src == null) el("span", "class" to "$cls none", "aria-hidden" to "true") {}
        else empty("img", "class" to cls, "src" to src, "alt" to "", "width" to size, "height" to size, "loading" to "lazy")
    }

    /**
     * Songs, channels or stations, each a link to its own page: with the script, a tap plays it
     * (and the rest of a [single] = false list after it). [extra] adds to each one's link.
     */
    fun Html.tracks(
        list: List<ApiTrack>,
        single: Boolean = false,
        numbered: Boolean = false,
        extra: (Int, ApiTrack) -> String = { _, _ -> "" },
        sub: (ApiTrack) -> String = ::subtitle,
    ) {
        el("ol", "class" to if (numbered) "tracks num" else "tracks", "data-list" to if (single) "single" else "queue") {
            list.forEachIndexed { i, track ->
                if (track.toTrack().isMissing) {
                    el("li", "class" to "missing") {
                        el("span", "class" to "t") {
                            el("span", "class" to "cv none", "aria-hidden" to "true") {}
                            el("span", "class" to "tx") {
                                el("b") { text(track.title) }
                                el("small") { text("${track.artist} · not found") }
                            }
                        }
                    }
                    return@forEachIndexed
                }
                el("li") {
                    el("a", "class" to "t", "href" to "/lite/song?t=${LiteTracks.token(track)}${extra(i, track)}") {
                        cover(track.artworkUrl)
                        el("span", "class" to "tx") {
                            el("b") { text(track.title) }
                            el("small") { text(sub(track)) }
                        }
                    }
                    el("button", "class" to "opt js", "type" to "button", "aria-label" to "More for ${track.title}") { text("⋯") }
                }
            }
        }
    }

    /** "Play" and "Shuffle" for the list below them. */
    fun Html.playAll(first: ApiTrack?) {
        if (first == null) return
        el("p", "class" to "acts") {
            el("a", "class" to "btn", "href" to "/lite/play?t=${LiteTracks.token(first)}", "data-play" to "all") { text("Play") }
            el("button", "class" to "btn js", "type" to "button", "data-play" to "shuffle") { text("Shuffle") }
        }
    }

    fun Html.notice(text: String, cls: String = "note") = el("p", "class" to cls, "role" to "status") { text(text) }

    companion object {
        const val MARK_START = "<!--lite-->"
        const val MARK_END = "<!--/lite-->"
        const val TZ_COOKIE = "samm_lite_tz"
        const val KBPS_COOKIE = "samm_lite_kbps"
        const val DEVICE_COOKIE = "samm_lite_device"
        const val PRE_COOKIE = "samm_lite_pre"
        val QUALITY_STEPS = listOf(0, 320, 256, 192, 160, 128, 96, 64)
        val NAV = listOf("/lite" to "Home", "/lite/search" to "Search", "/lite/playlists" to "Playlists", "/lite/radio" to "Radio", "/lite/now" to "Now playing")

        fun csrfFor(services: Services, principal: Principal) = services.box.sign("lite|${principal.session.id}")

        /** A sized copy of server artwork (only the server's own image addresses are used). */
        fun sized(url: String?, px: Int): String? = when {
            url.isNullOrBlank() || !url.startsWith("/") -> null
            url.startsWith("${Artwork.PATH}?") -> "$url&px=$px"
            else -> url
        }

        fun subtitle(track: ApiTrack): String = listOfNotNull(
            track.artist.takeIf { it.isNotBlank() },
            track.durationMs?.takeIf { it > 0 }?.let(::duration),
        ).joinToString(" · ")

        fun duration(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

        fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream(bytes.size / 3 + 64).also { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
        }.toByteArray()

        /** The person signed in with this browser's cookie (the lite pages don't take app tokens). */
        suspend fun principalOf(call: ApplicationCall, services: Services): Principal? {
            val token = call.request.cookies[SESSION_COOKIE] ?: return null
            val (user, session) = services.sessions.resolve(token)?.takeIf { !it.second.kind.isConnector } ?: return null
            return Principal(user, session, viaCookie = true)
        }

        /** A cookie for the lite pages' own settings (not secret; the script reads the time zone and device ones). */
        fun ApplicationCall.liteCookie(name: String, value: String, maxAge: Int = 365 * 24 * 3600) = Cookie(
            name = name, value = value, encoding = CookieEncoding.URI_ENCODING, maxAge = maxAge, path = "/",
            secure = isHttps, extensions = mapOf("SameSite" to "Lax"),
        )

        /** What went wrong, for the person: the server's own messages, or a plain one. */
        fun messageOf(e: Throwable): Pair<HttpStatusCode, String> = when (e) {
            is ApiError -> e.status to (e.message ?: "Something went wrong")
            is SourceUnavailableException -> HttpStatusCode.BadGateway to (e.message ?: "That service isn't available right now")
            is IOException -> HttpStatusCode.BadGateway to "Couldn't reach a music service: ${e.message ?: "try again"}"
            else -> HttpStatusCode.InternalServerError to "Something went wrong on the server"
        }
    }
}
