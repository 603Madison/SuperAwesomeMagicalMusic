package com.music.server.radio

import com.music.server.ApiError
import com.music.server.Services
import com.music.server.auth.User
import com.music.server.auth.requireUser
import com.music.server.music.ApiTrack
import com.music.server.music.NowView
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.ZoneId

/** One of the person's own stations, as the Radio page lists it. */
@Serializable
data class PersonalStationView(
    val track: ApiTrack,
    val kind: String,
    val name: String,
    val tagline: String,
    /** There's something to play (some listening, or songs in playlists). */
    val ready: Boolean,
    /** What's on, while it's on the air. */
    val onAir: NowView? = null,
)

@Serializable
data class DjView(val id: String, val text: String, val atMs: Long)

/** What's on a personal station: the song, what's next and what was, and what the DJ says. */
@Serializable
data class PersonalNow(
    val station: ApiTrack,
    val now: NowView? = null,
    /** The song on now, for thumbs. */
    val songKey: String? = null,
    val thumb: String? = null,
    val next: List<NowView> = emptyList(),
    val recent: List<NowView> = emptyList(),
    val dj: DjView? = null,
    /** A line to say on tuning in. */
    val welcome: String? = null,
    /** The server's clock, and how far behind it listeners hear (times here are on-air times). */
    val serverTime: Long,
    val heardDelayMs: Long,
)

@Serializable
data class StationThumb(val key: String, val thumb: String)

fun Route.radioRoutes(services: Services) {
    val radio = services.personalRadio
    val art = services.artwork

    fun kindOf(call: ApplicationCall): StationKind =
        StationKind.of(call.parameters["id"].orEmpty()) ?: throw ApiError.notFound("There's no such station")

    fun zoneOf(call: ApplicationCall): ZoneId? = call.request.queryParameters["tz"]?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    fun songView(song: Candidate, startMs: Long, durationMs: Long) = NowView(
        title = song.track.title,
        artist = song.track.artist,
        album = song.track.album,
        artworkUrl = art.wrap(song.track.artworkUrl),
        startMs = startMs,
        durationMs = durationMs,
        song = art.outgoing(song.track),
    )

    fun onAirView(t: Transmitter.OnAir) = songView(t.song, t.startMs, t.durationMs)

    suspend fun nowOf(user: User, kind: StationKind, transmitter: Transmitter?): PersonalNow {
        val station = art.outgoing(Stations.track(kind, user.displayName, user.username))
        val serverTime = services.clock()
        if (transmitter == null) return PersonalNow(station, serverTime = serverTime, heardDelayMs = HEARD_DELAY_MS)
        val current = transmitter.current
        // What's lined up, with when each should start (each song's length, less the crossfade).
        var at = current?.let { it.startMs + it.durationMs } ?: serverTime
        val next = transmitter.lined.map { song ->
            val length = song.track.durationMs ?: 0
            songView(song, at, length).also { at += length }
        }
        val thumbs = radio.thumbs(user)
        val dj = transmitter.dj?.takeIf { line -> line.songKey == current?.song?.key }
        return PersonalNow(
            station = station,
            now = current?.let(::onAirView),
            songKey = current?.song?.key,
            thumb = current?.song?.key?.let { thumbs[it] }?.let { if (it > 0) "UP" else "DOWN" },
            next = next,
            recent = transmitter.recent.map(::onAirView),
            dj = dj?.let { DjView(it.id, it.text, it.atMs) },
            welcome = current?.let { "You're tuned to ${transmitter.name}. ${it.song.track.title}, by ${it.song.track.artist}." }
                ?: "You're tuned to ${transmitter.name}.",
            serverTime = serverTime,
            heardDelayMs = HEARD_DELAY_MS,
        )
    }

    route("/api/radio") {
        // A station's logo: the same for everyone (the name beside it is theirs), so no sign-in needed.
        get("/art") {
            val kind = StationKind.of(call.request.queryParameters["station"].orEmpty()) ?: throw ApiError.notFound()
            val bytes = Stations::class.java.getResourceAsStream("/radio/${kind.id}.png")?.use { it.readBytes() } ?: throw ApiError.notFound()
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(bytes, ContentType.Image.PNG)
        }

        get("/personal") {
            val user = call.requireUser(services)
            val ready = radio.ready(user)
            call.respond(
                StationKind.entries.map { kind ->
                    PersonalStationView(
                        track = art.outgoing(Stations.track(kind, user.displayName, user.username)),
                        kind = kind.id,
                        name = Stations.nameOf(kind, user.displayName, user.username),
                        tagline = kind.tagline,
                        ready = ready,
                        onAir = radio.onAir(user, kind)?.current?.let(::onAirView),
                    )
                },
            )
        }

        get("/personal/{id}/now") {
            val user = call.requireUser(services)
            val kind = kindOf(call)
            val transmitter = radio.onAir(user, kind)
            zoneOf(call)?.let { transmitter?.zone = it }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(nowOf(user, kind, transmitter))
        }

        post("/personal/{id}/skip") {
            val user = call.requireUser(services)
            radio.skip(user, kindOf(call))
            call.respond(mapOf("ok" to true))
        }

        post("/personal/{id}/thumb") {
            val user = call.requireUser(services)
            val request = call.receive<StationThumb>()
            val thumb = when (request.thumb.uppercase()) {
                "UP" -> 1
                "DOWN" -> -1
                "NONE" -> 0
                else -> throw ApiError.badRequest("UP, DOWN or NONE")
            }
            radio.thumb(user, kindOf(call), request.key.take(500), thumb)
            call.respond(mapOf("ok" to true))
        }
    }
}

/**
 * Plays a personal station to [call] (from the radio station route, so every player plays it as
 * the radio station it is): the last moments at once, then the broadcast as it goes.
 */
suspend fun PersonalRadio.respondStream(call: ApplicationCall, user: User, id: String) {
    val kind = StationKind.of(id) ?: throw ApiError.notFound("There's no such station")
    val zone = call.request.queryParameters["tz"]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    val transmitter = tuneIn(user, kind, zone)
    val (burst, listener) = transmitter.subscribe()
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header("icy-name", transmitter.name.replace('’', '\'').filter { it.code in 0x20..0x7e })
    try {
        call.respondOutputStream(ContentType("audio", "aac")) {
            try {
                burst.forEach { write(it) }
                flush()
                for (frame in listener.frames) {
                    write(frame)
                    // Everything waiting goes in one write, then out.
                    while (true) write(listener.frames.tryReceive().getOrNull() ?: break)
                    flush()
                }
            } catch (e: IOException) {
                // The listener tuned out.
            } finally {
                transmitter.unsubscribe(listener)
            }
        }
    } finally {
        transmitter.unsubscribe(listener)
    }
}

/** What a personal station's "now" says for players that only know radio stations ("Artist - Title"). */
fun PersonalRadio.radioTitle(user: User, id: String): String? {
    val kind = StationKind.of(id) ?: return null
    val song = onAir(user, kind)?.current?.song ?: return null
    return "${song.track.artist} - ${song.track.title}"
}

/** How far behind the station's clock listeners hear it: the burst on tuning in, and a player's buffer. */
private const val HEARD_DELAY_MS = 3_000L
