package com.music.tuner

import com.music.tuner.radio.AllTunersBusy
import com.music.tuner.radio.ImageStore
import com.music.tuner.radio.NoSuchStation
import com.music.tuner.radio.TunerStatus
import com.music.tuner.scan.ScanProgress
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@OptIn(ExperimentalSerializationApi::class)
val TunerJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class ErrorBody(val error: String)

/** What's on, as the station sends it. */
@Serializable
data class NowView(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** RDS RadioText: whatever the station writes (often the song, sometimes a phone number). */
    val radiotext: String? = null,
    val slogan: String? = null,
)

@Serializable
data class StationView(
    val id: String,
    /** e.g. "97.1". */
    val frequency: String,
    val frequencyKhz: Int,
    /** The HD Radio program number (1 for HD1…), or null for analog FM. */
    val hdProgram: Int? = null,
    val hd: Boolean,
    val name: String,
    val callLetters: String? = null,
    val pi: String? = null,
    val genre: String? = null,
    val now: NowView? = null,
    /** How well it comes in: measured now while it plays, else when it last played or was scanned. */
    val signal: Signal? = null,
    /** Playing on a tuner right now. */
    val playing: Boolean = false,
    val listeners: Int = 0,
    val logoUrl: String? = null,
    val artUrl: String? = null,
    val streamUrl: String,
    val manual: Boolean = false,
)

@Serializable
data class StatusView(
    val name: String,
    val version: String,
    val region: Region,
    val tuners: List<TunerStatus>,
    val scan: ScanProgress,
    val stations: Int,
    val scannedAt: Long? = null,
)

/** HDHomeRun's discovery answer, so HDHomeRun-aware apps find the tuner. */
@Serializable
data class Discover(
    @SerialName("FriendlyName") val friendlyName: String,
    @SerialName("Manufacturer") val manufacturer: String = "SuperAwesomeMagicalMusic",
    @SerialName("ModelNumber") val modelNumber: String = "SAMM-FM",
    @SerialName("FirmwareName") val firmwareName: String = "samm-tuner",
    @SerialName("FirmwareVersion") val firmwareVersion: String,
    @SerialName("DeviceID") val deviceId: String,
    @SerialName("DeviceAuth") val deviceAuth: String = "",
    @SerialName("BaseURL") val baseUrl: String,
    @SerialName("LineupURL") val lineupUrl: String,
    @SerialName("TunerCount") val tunerCount: Int,
)

@Serializable
data class LineupEntry(
    @SerialName("GuideNumber") val guideNumber: String,
    @SerialName("GuideName") val guideName: String,
    @SerialName("URL") val url: String,
    @SerialName("HD") val hd: Int? = null,
    @SerialName("AudioCodec") val audioCodec: String = "AAC",
)

@Serializable
data class LineupStatus(
    @SerialName("ScanInProgress") val scanInProgress: Int,
    @SerialName("ScanPossible") val scanPossible: Int,
    @SerialName("Source") val source: String = "Antenna",
    @SerialName("SourceList") val sourceList: List<String> = listOf("Antenna"),
    @SerialName("Progress") val progress: Int? = null,
    @SerialName("Found") val found: Int? = null,
)

@Serializable
data class AddStation(val frequency: String, val name: String? = null, val hdProgram: Int? = null)

private val log = LoggerFactory.getLogger("Tuner")

/** The tuner's web API and page. With a token configured, everything but the page needs it. */
fun Application.tunerModule(app: TunerApp) {
    install(ContentNegotiation) { json(TunerJson) }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().let { it.startsWith("/api/") && it != "/api/health" && it != "/api/status" && it != "/api/stations" } }
    }
    install(StatusPages) {
        exception<AllTunersBusy> { call, e -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody(e.message ?: "All tuners are busy")) }
        exception<NoSuchStation> { call, e -> call.respond(HttpStatusCode.NotFound, ErrorBody(e.message ?: "No such station")) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorBody("That request isn't right")) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody(e.message ?: "That request isn't right")) }
        exception<Throwable> { call, e ->
            if (e is kotlinx.coroutines.CancellationException) return@exception
            log.error("Failed: ${call.request.path()}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("Something went wrong in the tuner"))
        }
    }
    val token = app.config.token
    intercept(ApplicationCallPipeline.Plugins) {
        val path = context.request.path()
        if (token != null && path != "/" && path != "/api/health" && !context.hasToken(token)) {
            context.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            context.respond(HttpStatusCode.Unauthorized, ErrorBody("This tuner needs its token"))
            finish()
        }
    }

    fun ApplicationCall.base(): String {
        val origin = request.origin
        val host = request.headers[HttpHeaders.Host] ?: "${origin.serverHost}:${origin.serverPort}"
        return "${origin.scheme}://$host"
    }

    /** `?token=` for addresses handed to players that can't send a header. */
    fun tokenQuery(prefix: Char = '?') = token?.let { "${prefix}token=${it.encodeURLParameter()}" }.orEmpty()

    fun view(station: Station): StationView {
        val session = app.pool.playing(station.id)
        val live = session?.let { runCatching { it.audio.live() }.getOrNull() }
        val path = "/api/stations/${station.id.encodeURLPathPart()}"
        val logo = live?.logo ?: app.images.logo(station.id)
        val art = live?.art
        val stationName = if (station.isHd) live?.programName else live?.stationName
        return StationView(
            id = station.id,
            frequency = station.frequencyLabel,
            frequencyKhz = station.frequencyKhz,
            hdProgram = station.program?.plus(1),
            hd = station.isHd,
            name = station.name.takeUnless { it == station.bandLabel && stationName != null } ?: stationName!!,
            callLetters = station.callLetters ?: live?.callLetters,
            pi = station.pi ?: live?.pi,
            genre = station.genre ?: live?.genre,
            now = (live?.let { NowView(it.title, it.artist, it.album, it.radiotext, it.slogan ?: station.slogan) } ?: NowView(slogan = station.slogan))
                .takeIf { it != NowView() },
            signal = live?.signal ?: station.signal,
            playing = session != null,
            listeners = session?.listeners ?: 0,
            logoUrl = logo?.let { "$path/logo?v=${ImageStore.version(it)}" },
            artUrl = art?.let { "$path/art?v=${ImageStore.version(it)}" },
            streamUrl = "$path/stream",
            manual = station.manual,
        )
    }

    fun stationOf(call: ApplicationCall): Station {
        val id = call.parameters["id"].orEmpty()
        return app.lineup.station(id) ?: throw NoSuchStation(id)
    }

    routing {
        get("/") {
            val page = TunerApp::class.java.getResourceAsStream("/web/index.html")?.use { it.readBytes() }
                ?: return@get call.respondText("The tuner is running.", ContentType.Text.Plain)
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; media-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
            call.respondBytes(page, ContentType.Text.Html)
        }

        get("/api/health") { call.respond(mapOf("ok" to "true", "version" to TunerApp.version)) }

        // ---- HDHomeRun-style discovery ----

        get("/discover.json") {
            val base = call.base()
            call.respond(
                Discover(
                    friendlyName = app.config.name,
                    firmwareVersion = TunerApp.version,
                    deviceId = app.deviceId,
                    baseUrl = base,
                    lineupUrl = "$base/lineup.json${tokenQuery()}",
                    tunerCount = app.pool.tuners.size,
                ),
            )
        }

        get("/lineup.json") {
            val base = call.base()
            call.respond(
                app.lineup.get().stations.map { s ->
                    LineupEntry(
                        guideNumber = if (s.isHd) "${s.frequencyLabel}-HD${(s.program ?: 0) + 1}" else s.frequencyLabel,
                        guideName = s.name,
                        url = "$base/api/stations/${s.id.encodeURLPathPart()}/stream${tokenQuery()}",
                        hd = if (s.isHd) 1 else null,
                    )
                },
            )
        }

        get("/lineup_status.json") {
            val scan = app.scanner.progress
            call.respond(
                LineupStatus(
                    scanInProgress = if (scan.running) 1 else 0,
                    scanPossible = 1,
                    progress = if (scan.running && scan.total > 0) scan.checked * 100 / scan.total else null,
                    found = if (scan.running) scan.found else null,
                ),
            )
        }

        // ---- The tuner's own API ----

        get("/api/status") {
            val lineup = app.lineup.get()
            call.respond(
                StatusView(
                    name = app.config.name,
                    version = TunerApp.version,
                    region = app.config.region,
                    tuners = app.pool.status(),
                    scan = app.scanner.progress,
                    stations = lineup.stations.size,
                    scannedAt = lineup.scannedAt,
                ),
            )
        }

        get("/api/stations") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(app.lineup.get().stations.map(::view))
        }

        post("/api/stations") {
            val request = call.receive<AddStation>()
            val khz = Station.parseId(request.frequency.trim())?.first ?: throw IllegalArgumentException("Which frequency? e.g. 97.1")
            require(khz in 87_500..108_000) { "FM stations are between 87.5 and 108 MHz" }
            val program = request.hdProgram?.also { require(it in 1..8) { "HD Radio programs are HD1 to HD8" } }?.minus(1)
            val label = Station.frequencyLabel(khz)
            val station = Station(
                id = Station.idOf(khz, program),
                frequencyKhz = khz,
                program = program,
                name = request.name?.trim()?.take(64)?.takeIf { it.isNotEmpty() } ?: if (program == null) "$label FM" else "$label HD${program + 1}",
                manual = true,
            )
            app.lineup.add(station)
            call.respond(HttpStatusCode.Created, view(station))
        }

        get("/api/stations/{id}") { call.respond(view(stationOf(call))) }

        delete("/api/stations/{id}") {
            if (!app.lineup.remove(call.parameters["id"].orEmpty())) throw NoSuchStation(call.parameters["id"].orEmpty())
            call.respond(HttpStatusCode.NoContent, "")
        }

        // The station's sound, as AAC in ADTS frames (what Icecast-style players and the music server take).
        get("/api/stations/{id}/stream") {
            val station = stationOf(call)
            val ticket = app.pool.listen(station.id)
            try {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("icy-name", station.name.filter { it.code in 0x20..0x7e })
                call.respondOutputStream(ContentType("audio", "aac")) {
                    try {
                        for (frame in ticket.listener.frames) {
                            write(frame)
                            // Everything waiting goes in one write, then out.
                            while (true) write(ticket.listener.frames.tryReceive().getOrNull() ?: break)
                            flush()
                        }
                    } catch (e: IOException) {
                        // The listener tuned away.
                    }
                }
            } finally {
                app.pool.leave(ticket)
            }
        }

        get("/api/stations/{id}/logo") {
            val station = stationOf(call)
            val file = app.pool.playing(station.id)?.let { runCatching { it.audio.live().logo }.getOrNull() } ?: app.images.logo(station.id)
            call.respondImage(file)
        }

        get("/api/stations/{id}/art") {
            val station = stationOf(call)
            val file = app.pool.playing(station.id)?.let { runCatching { it.audio.live().art }.getOrNull() }
            call.respondImage(file)
        }

        get("/api/scan") { call.respond(app.scanner.progress) }

        post("/api/scan") {
            val progress = app.scanner.start()
            call.respond(HttpStatusCode.Accepted, progress)
        }

        delete("/api/scan") {
            app.scanner.cancel()
            call.respond(app.scanner.progress)
        }
    }
}

private fun ApplicationCall.hasToken(token: String): Boolean {
    val given = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        ?: request.queryParameters["token"]
        ?: return false
    return MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
}

/** Only images (by their first bytes): what nrsc5 saved came from the air. */
private suspend fun ApplicationCall.respondImage(file: File?) {
    val bytes = file?.takeIf { it.isFile && it.length() in 1..5_000_000 }?.readBytes()
        ?: return respond(HttpStatusCode.NotFound, ErrorBody("No image"))
    val type = when {
        bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> ContentType.Image.PNG
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> ContentType.Image.JPEG
        bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() -> ContentType.Image.GIF
        else -> return respond(HttpStatusCode.NotFound, ErrorBody("No image"))
    }
    response.header(HttpHeaders.CacheControl, "public, max-age=86400")
    response.header("Content-Security-Policy", "default-src 'none'; sandbox")
    respondBytes(bytes, type)
}
