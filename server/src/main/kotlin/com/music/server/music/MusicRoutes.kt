package com.music.server.music

import com.music.player.lyrics.LyricsRepository
import com.music.player.net.Http
import com.music.player.quality.StreamQuality
import com.music.player.settings.JellyfinConfig
import com.music.player.sources.jellyfin.JellyfinSource
import com.music.server.sources.SourceKind
import com.music.server.ApiError
import com.music.server.Services
import com.music.server.auth.requireUser
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress

@Serializable
data class LyricLineView(val timeMs: Long? = null, val text: String)

@Serializable
data class LyricsView(val lines: List<LyricLineView>, val source: String = "", val synced: Boolean = false)

@Serializable
data class LyricsRequest(val track: ApiTrack)

@Serializable
data class SearchResponse(val query: String, val groups: List<SearchGroup>)

@Serializable
data class ResolveRequest(
    val track: ApiTrack,
    /** Bitrate cap in kbps; 0 for the original quality. */
    val kbps: Int = 0,
    /** The codecs the player can play (flac, mp3, aac, opus…); empty means the usual ones. */
    val codecs: List<String> = emptyList(),
    /** Compare every service for the best copy (slower; for downloads and fast connections). */
    val thorough: Boolean = false,
)

@Serializable
data class ResolvedStream(
    /** The copy that will play, which may be another service's copy of the song asked for. */
    val track: ApiTrack,
    val streamUrl: String,
    val description: String,
    val kbps: Int? = null,
    val withinCap: Boolean = true,
    val gainDb: Float? = null,
    /** Whether the player can seek in it by itself; if not, it asks again with `start`. */
    val seekable: Boolean,
    val transcoded: Boolean,
    /** The file extension to save it under. */
    val extension: String,
)

fun Route.musicRoutes(services: Services) {
    get("/api/search") {
        val user = call.requireUser(services)
        val query = call.request.queryParameters["q"]?.trim().orEmpty()
        if (query.isEmpty()) return@get call.respond(SearchResponse(query, emptyList()))
        if (query.length > 200) throw ApiError.badRequest("That search is too long")
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 25
        val groups = services.catalog.search(user, query, limit).map { group ->
            group.copy(tracks = group.tracks.map(services.artwork::outgoing))
        }
        call.respond(SearchResponse(query, groups))
    }

    post("/api/play/resolve") {
        val user = call.requireUser(services)
        val request = call.receive<ResolveRequest>()
        val wanted = services.artwork.incoming(request.track)
        val quality = qualityOf(request.kbps)
        val codecs = codecsOf(request.codecs.joinToString(","))
        val resolution = services.catalog.resolve(user, wanted.toTrack(), wanted.via, quality, request.thorough)
        val plan = services.streamer.plan(resolution.stream, quality, codecs, start = 0.0)
        val url = buildString {
            append("/api/stream?via=").append(resolution.source.id.encodeURLParameter())
            append("&id=").append(resolution.track.sourceId.encodeURLParameter())
            append("&kbps=").append(quality.toStored())
            if (codecs != null) append("&codecs=").append(codecs.sorted().joinToString(",").encodeURLParameter())
        }
        call.respond(
            ResolvedStream(
                track = services.artwork.outgoing(ApiTrack.of(resolution.track, resolution.source.id)),
                streamUrl = url,
                description = plan.format?.let { format ->
                    "${resolution.stream.description} → ${format.name}${plan.kbps?.let { " $it kbps" } ?: ""}"
                } ?: resolution.stream.description,
                kbps = plan.kbps ?: resolution.stream.kbps,
                withinCap = resolution.stream.withinCap || plan.transcoded,
                gainDb = resolution.stream.gainDb,
                seekable = !plan.transcoded && resolution.stream.supportsRangeRequests,
                transcoded = plan.transcoded,
                extension = plan.format?.extension ?: resolution.stream.fileExtension,
            ),
        )
    }

    get("/api/stream") {
        val user = call.requireUser(services)
        val params = call.request.queryParameters
        val source = services.sources.accessible(user, params["via"].orEmpty())?.takeIf { it.enabled }
            ?: throw ApiError.notFound("That source isn't available")
        val id = params["id"]?.takeIf { it.isNotBlank() && it.length < 500 } ?: throw ApiError.badRequest("Which song?")
        val quality = qualityOf(params["kbps"]?.toIntOrNull() ?: 0)
        val codecs = codecsOf(params["codecs"])
        val start = params["start"]?.toDoubleOrNull()?.coerceIn(0.0, 24.0 * 3600) ?: 0.0
        val stream = services.catalog.streamOf(source, id, quality)
        val plan = services.streamer.plan(stream, quality, codecs, start)
        services.streamer.respond(call, stream, plan, start) { services.catalog.streamOf(source, id, quality, fresh = true) }
    }

    // ffmpeg's way in to a stream it's converting; only from the server itself.
    get("/internal/relay/{id}") {
        val address = call.request.local.remoteAddress
        val loopback = runCatching { InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false)
        if (!loopback) throw ApiError.notFound()
        services.streamer.relay(call, call.parameters["id"].orEmpty())
    }

    get(Artwork.PATH) {
        val params = call.request.queryParameters
        val upstream = params["u"].orEmpty()
        if (upstream.isEmpty() || !services.artwork.verify(upstream, params["s"].orEmpty())) throw ApiError.notFound()
        val px = params["px"]?.toIntOrNull()?.coerceIn(32, 2048)
        val (type, bytes) = withContext(Dispatchers.IO) {
            try {
                services.http.newCall(Request.Builder().url(Artwork.sized(upstream, px)).build()).execute().use { response ->
                    val type = response.header(HttpHeaders.ContentType).orEmpty()
                    val body = response.body
                    // Only images: anything else from our address could be used against the web player.
                    if (!response.isSuccessful || body == null || !type.startsWith("image/") || type.contains("svg")) {
                        throw ApiError.notFound()
                    }
                    if (body.contentLength() > MAX_ART_BYTES) throw ApiError.notFound()
                    val bytes = body.byteStream().use { it.readNBytes(MAX_ART_BYTES + 1) }
                    if (bytes.size > MAX_ART_BYTES) throw ApiError.notFound()
                    type to bytes
                }
            } catch (e: IOException) {
                throw ApiError.notFound()
            }
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=604800, immutable")
        call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")
        call.respondBytes(bytes, ContentType.parse(type))
    }

    // Lyrics for the song playing: from its Jellyfin library if it has them, else LRCLIB.
    val lyricsByLibrary = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, LyricsRepository>>()
    post("/api/lyrics") {
        val user = call.requireUser(services)
        val track = services.artwork.incoming(call.receive<LyricsRequest>().track)
        val library = track.via?.let { services.sources.accessible(user, it) }?.takeIf { it.kind == SourceKind.JELLYFIN }
        val key = library?.id ?: ""
        val repository = lyricsByLibrary[key]?.takeIf { it.first == (library?.updatedAt ?: 0) }?.second
            ?: LyricsRepository(
                services.http,
                library?.let { services.registry.jellyfin(it) } ?: JellyfinSource(services.http, config = { JellyfinConfig() }, deviceId = { "samm-server" }),
                Http.USER_AGENT,
            ).also { lyricsByLibrary[key] = (library?.updatedAt ?: 0L) to it }
        val found = try {
            repository.find(track.toTrack())
        } catch (e: IOException) {
            null
        }
        call.respond(
            found?.let { lyrics -> LyricsView(lyrics.lines.map { LyricLineView(it.timeMs, it.text) }, lyrics.source, lyrics.synced) }
                ?: LyricsView(emptyList()),
        )
    }

    get("/api/lidarr/requests") {
        val user = call.requireUser(services)
        call.respond(services.lidarr.list(user))
    }
}

private const val MAX_ART_BYTES = 10 * 1024 * 1024

fun qualityOf(kbps: Int): StreamQuality = StreamQuality.fromStored(kbps)

/** The codecs named in [raw] that the server knows, or null to assume the usual ones play. */
fun codecsOf(raw: String?): Set<String>? =
    raw?.split(',')?.map { it.trim().lowercase() }?.filter { it in Streamer.KNOWN_CODECS }?.toSet()?.takeIf { it.isNotEmpty() }
