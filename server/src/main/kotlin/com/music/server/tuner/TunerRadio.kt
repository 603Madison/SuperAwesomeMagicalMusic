package com.music.server.tuner

import com.music.player.CoreLog
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.sources.radio.RadioStation
import com.music.server.ApiError
import com.music.server.Services
import com.music.server.auth.User
import com.music.server.music.ApiTrack
import com.music.server.music.LiveProxy
import com.music.server.music.MediaTypes
import com.music.server.music.NowView
import com.music.server.sources.SourceKind
import com.music.server.sources.StoredSource
import com.music.server.util.NetworkGuard
import com.music.server.util.NetworkGuard.reach
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Which copy of a station is playing, and how well the tuner hears it. */
@Serializable
data class SignalView(
    /** "tuner" or "internet". */
    val via: String,
    val score: Double? = null,
    val detail: String? = null,
    val hd: Boolean = false,
)

/**
 * Local FM and HD Radio from the person's tuners (see tuner/), alongside internet radio: tuner
 * stations are radio stations like any other (every player plays them, favourites keep them),
 * and one that's also on the internet is listed once and plays from whichever copy sounds
 * better (see [Blend]).
 *
 * A tuner station's id is "tuner:<station>" (its id on the tuner, e.g. 97.1-hd2), or
 * "tuner:<station>~<internet station>" when it's also online.
 */
class TunerRadio(private val services: Services) {
    /** Where internet copies come from: Radio Browser (tests swap in their own). */
    val counterparts = Counterparts(services.scope, { query, country -> services.registry.radio.search(query, country, limit = 20) }, services.clock)

    @Volatile
    var internetStation: suspend (String) -> RadioStation = { services.registry.radio.station(it) }

    /** How far internet streams may be fetched from: the internet only (tests reach their own). */
    @Volatile
    var internetReach: NetworkGuard.Reach = NetworkGuard.Reach.PUBLIC

    private val blends = ConcurrentHashMap<String, Blend>()
    private val scores = ConcurrentHashMap<String, Double?>()
    private val titles = ConcurrentHashMap<String, Pair<String, Long>>()
    private val regions = ConcurrentHashMap<String, Pair<Long, String>>()
    private val heard = ConcurrentHashMap<String, Pair<String, Long>>()

    data class Id(val station: String, val internet: String?)

    /** The person's tuners and their stations; a tuner that can't be reached is left out. */
    suspend fun stations(user: User): List<Pair<StoredSource, List<TunerStation>>> =
        services.sources.activeFor(user).filter { it.kind == SourceKind.TUNER }.mapNotNull { source ->
            val client = services.registry.tuner(source) ?: return@mapNotNull null
            try {
                source to client.stations()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CoreLog.i(TAG, "${source.name} didn't answer: ${e.message}")
                null
            }
        }

    /** The tuner's band plan: "US" (call letters from RDS) or "OTHER". */
    suspend fun region(source: StoredSource): String {
        regions[source.id]?.let { (at, region) -> if (services.clock() - at < 600_000) return region }
        val region = runCatching { services.registry.tuner(source)?.status()?.region }.getOrNull() ?: "US"
        regions[source.id] = services.clock() to region
        return region
    }

    /** A tuner station as a radio station for the players, merged with its internet copy if it has one. */
    fun track(source: StoredSource, station: TunerStation, internet: RadioStation?): ApiTrack {
        val band = if (station.hd) "${station.frequency} HD${station.hdProgram ?: 1}" else "${station.frequency} FM"
        return ApiTrack(
            source = SourceType.RADIO,
            sourceId = idOf(station.id, internet?.id),
            title = station.name,
            artist = if (internet != null) "$band, on your tuner or online" else "$band, on your tuner",
            album = station.genre?.replaceFirstChar { it.uppercase() },
            artworkUrl = station.logoUrl?.let { imagePath(source.id, station.id, "logo", it) } ?: internet?.favicon?.ifBlank { null },
            via = source.id,
        )
    }

    /** One tuner's stations, for its own listing. */
    suspend fun own(source: StoredSource, query: String? = null): List<ApiTrack> {
        val client = services.registry.tuner(source) ?: return emptyList()
        val stations = try {
            client.stations()
        } catch (e: IOException) {
            throw ApiError.upstream("Couldn't reach the tuner: ${e.message ?: e.javaClass.simpleName}")
        }
        val region = region(source)
        return stations.filter { matches(it, query) }.map { track(source, it, counterparts.known(it, region)) }
    }

    /**
     * Internet stations (a search, or those near the person) with the person's tuner stations
     * added at the top: those matching [query] (all of them without one), each merged with its
     * internet copy, which then isn't listed again.
     */
    suspend fun merge(user: User, internet: List<RadioStation>, radioSourceId: String, query: String?): List<ApiTrack> {
        val tuners = stations(user)
        val internetTracks = { list: List<RadioStation> -> list.map { ApiTrack.of(it.toTrack(), radioSourceId) } }
        if (tuners.isEmpty()) return internetTracks(internet)
        val merged = mutableListOf<ApiTrack>()
        val used = HashSet<String>()
        for ((source, stations) in tuners) {
            val region = region(source)
            for (station in stations) {
                val inResults = StationMatch.best(station, internet, region)?.also { counterparts.learn(station, it) }
                if (inResults == null && !matches(station, query)) continue
                val counterpart = inResults ?: counterparts.known(station, region)
                counterpart?.let { used += it.id }
                merged += track(source, station, counterpart)
            }
        }
        return merged + internetTracks(internet.filter { it.id !in used })
    }

    private fun matches(station: TunerStation, query: String?): Boolean {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
        return listOfNotNull(station.name, station.callLetters, station.frequency, station.genre).any { it.lowercase().contains(q) }
    }

    // ---- Listening ----

    /** Plays a tuner station: from the tuner, or (when it's also online) the copy that sounds better. */
    suspend fun respondStream(call: ApplicationCall, user: User, source: StoredSource, rawId: String) {
        val id = parse(rawId) ?: throw ApiError.notFound("There's no such station on the tuner")
        val client = services.registry.tuner(source) ?: throw ApiError.notFound("That tuner isn't available")
        val internet = id.internet?.let { runCatching { internetStation(it) }.getOrNull() }?.takeIf { !it.isHls }
        if (internet != null && ffmpegWorks()) return respondBlend(call, source, client, id.station, internet)
        // Without ffmpeg to blend them: one or the other, chosen now.
        val score = runCatching { client.station(id.station)?.signal?.score }.getOrNull()
        if (internet != null && score != null && score < STATIC_BELOW) {
            return services.live.respondRadio(call, internet.streamUrl, titleKey(source, internet))
        }
        val response = try {
            client.openStream(id.station)
        } catch (e: IOException) {
            if (internet != null) return services.live.respondRadio(call, internet.streamUrl, titleKey(source, internet))
            if (e is TunerBusy) throw ApiError(HttpStatusCode.ServiceUnavailable, e.message ?: "All the tuner's tuners are busy")
            throw ApiError.upstream("Couldn't reach the tuner: ${e.message ?: e.javaClass.simpleName}")
        }
        relay(call, response)
    }

    /** Passes the tuner's AAC straight on (no blending needed: there's only the one copy). */
    private suspend fun relay(call: ApplicationCall, response: okhttp3.Response) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        MediaTypes.sandbox(call)
        call.respondOutputStream(ContentType("audio", "aac"), HttpStatusCode.OK) {
            try {
                response.body.byteStream().use { it.copyTo(this, 16 * 1024) }
            } catch (e: IOException) {
                // The listener (or the tuner) went.
            } finally {
                response.close()
            }
        }
    }

    private suspend fun respondBlend(call: ApplicationCall, source: StoredSource, client: TunerClient, stationId: String, internet: RadioStation) {
        val key = blendKey(source.id, stationId, internet.id)
        val blend = withContext(Dispatchers.IO) { blendFor(key, source, client, stationId, internet) }
            ?: throw ApiError(HttpStatusCode.ServiceUnavailable, "Neither the tuner nor the internet stream would play")
        val listener = blend.subscribe()
        try {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            MediaTypes.sandbox(call)
            call.respondOutputStream(ContentType("audio", "aac"), HttpStatusCode.OK) {
                try {
                    for (chunk in listener.chunks) {
                        write(chunk)
                        while (true) write(listener.chunks.tryReceive().getOrNull() ?: break)
                        flush()
                    }
                } catch (e: IOException) {
                    // The listener tuned away.
                }
            }
        } finally {
            blend.unsubscribe(listener)
        }
    }

    private fun blendFor(key: String, source: StoredSource, client: TunerClient, stationId: String, internet: RadioStation): Blend? {
        synchronized(blends) {
            blends[key]?.takeIf { it.isRunning }?.let { return it }
            val ffmpeg = services.config.ffmpeg
            scores[key] = runCatching { runBlocking { client.station(stationId)?.signal?.score } }.getOrNull()
            var target: Blend? = null
            val blend = Blend(
                name = "${source.name} $stationId",
                openTuner = {
                    FfmpegDecoder("tuner $stationId", ffmpeg, open = {
                        val response = runBlocking { client.openStream(stationId) }
                        Opened(response.body.byteStream()) { response.close() }
                    }).start()
                },
                openInternet = {
                    FfmpegDecoder("internet ${internet.name}", ffmpeg, open = { openInternet(internet) }, onTitle = { titles[key] = it to services.clock() }).start()
                },
                tunerScore = { scores[key] },
                sink = FfmpegEncoder(ffmpeg, BLEND_KBPS) { target?.publish(it) },
                clock = services.clock,
            )
            target = blend
            blend.begin()
            if (!blend.prebuffer()) {
                blend.close()
                return null
            }
            blend.run()
            blends[key] = blend
            // While it plays: the tuner's signal every few seconds, and it stops a while after its last listener.
            services.scope.launch {
                while (blend.isRunning) {
                    scores[key] = runCatching { client.station(stationId)?.signal?.score }.getOrNull()
                    val idle = blend.idleSince
                    if (idle != null && services.clock() - idle > BLEND_GRACE_MS) break
                    delay(3_000)
                }
                blend.close()
                blends.remove(key, blend)
                scores.remove(key)
            }
            return blend
        }
    }

    /** An internet station's stream, without its song titles (kept for what's on). */
    private fun openInternet(station: RadioStation): Opened {
        val request = Request.Builder().url(station.streamUrl).header("Icy-MetaData", "1").reach(internetReach).build()
        val response = services.http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build().newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("The station answered HTTP ${response.code}")
        }
        val interval = response.header("icy-metaint")?.toIntOrNull()?.takeIf { it in 1..1_000_000 }
        return Opened(response.body.byteStream(), interval) { response.close() }
    }

    // ---- What's on ----

    /** What's on a tuner station now: the song (RDS RadioText+ or HD Radio's tags), its cover, and the signal. */
    suspend fun now(user: User, source: StoredSource, rawId: String): RadioNowParts {
        val id = parse(rawId) ?: return RadioNowParts()
        val client = services.registry.tuner(source) ?: return RadioNowParts()
        val station = runCatching { client.station(id.station) }.getOrNull()
        val blend = id.internet?.let { blends[blendKey(source.id, id.station, it)] }?.takeIf { it.isRunning }
        val via = blend?.via ?: Via.TUNER
        val internetTitle = id.internet?.let { internetId ->
            titles[blendKey(source.id, id.station, internetId)]?.takeIf { services.clock() - it.second < 3_600_000 }?.first
                ?: services.live.radioTitle("${source.id}|$internetId")
        }
        val tunerNow = station?.now
        // The tuner's tags while it plays (or while it's clear enough to trust); else the internet copy's title.
        val trustTuner = via == Via.TUNER || (station?.signal?.score ?: 0.0) >= STATIC_BELOW
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        if (trustTuner && tunerNow?.title != null) {
            title = tunerNow.title
            artist = tunerNow.artist
            album = tunerNow.album
        } else if (internetTitle != null) {
            val at = internetTitle.indexOf(" - ")
            if (at > 0) {
                artist = internetTitle.substring(0, at).trim()
                title = internetTitle.substring(at + 3).trim()
            } else {
                title = internetTitle.trim()
            }
        }
        val text = when {
            title != null && !artist.isNullOrBlank() -> "$artist - $title"
            title != null -> title
            else -> tunerNow?.radiotext ?: internetTitle
        }
        val signal = SignalView(
            via = via.name.lowercase(),
            score = station?.signal?.score,
            detail = station?.signal?.detail,
            hd = station?.hd == true,
        )
        if (title == null || artist.isNullOrBlank()) return RadioNowParts(text, null, signal)
        // The broadcast's own cover (HD Radio), else the song's from the person's sources.
        val cover = station?.artUrl?.takeIf { trustTuner }?.let { imagePath(source.id, id.station, "art", it) }
        val song = Track(SourceType.SIRIUSXM, "song:radio", title, artist, album)
        val artwork = cover ?: services.songArt.of(user, song, 2_500)
        val key = "${source.id}|${id.station}"
        val songKey = "$artist|$title"
        val startedAt = heard[key]?.takeIf { it.first == songKey }?.second ?: services.clock().also { heard[key] = songKey to it }
        val now = NowView(
            title = title,
            artist = artist,
            album = album,
            artworkUrl = services.artwork.wrap(artwork),
            startMs = startedAt,
            durationMs = 0,
            song = services.artwork.outgoing(ApiTrack.of(song.copy(artworkUrl = artwork?.takeIf { !it.startsWith("/") }), null)),
        )
        return RadioNowParts(text, now, signal)
    }

    /** A logo or cover from the tuner, through the server (the tuner is on the home network). */
    suspend fun respondImage(call: ApplicationCall, source: StoredSource, stationId: String, kind: String) {
        if (kind != "logo" && kind != "art") throw ApiError.notFound()
        val client = services.registry.tuner(source) ?: throw ApiError.notFound()
        val image = try {
            client.image("/api/stations/${stationId.encodeURLPathPart()}/$kind")
        } catch (e: IOException) {
            null
        } ?: throw ApiError.notFound("No image")
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
        call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")
        call.respondBytes(image.first, ContentType.parse(image.second))
    }

    private var ffmpegChecked: Boolean? = null

    private fun ffmpegWorks(): Boolean = ffmpegChecked ?: runCatching {
        ProcessBuilder(services.config.ffmpeg, "-version").redirectErrorStream(true).start().let { p ->
            p.inputStream.readAllBytes()
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        }
    }.getOrDefault(false).also {
        ffmpegChecked = it
        if (!it) CoreLog.w(TAG, "No ffmpeg: stations on both the tuner and the internet play one copy, chosen when they start")
    }

    /** Stops every blend (the server is stopping). */
    fun close() {
        blends.values.forEach { runCatching { it.close() } }
        blends.clear()
    }

    data class RadioNowParts(val title: String? = null, val now: NowView? = null, val signal: SignalView? = null)

    companion object {
        private const val TAG = "Tuner"
        const val PREFIX = "tuner:"
        const val STATIC_BELOW = 0.35
        const val BLEND_KBPS = 160
        const val BLEND_GRACE_MS = 15_000L

        /** Where the server serves a tuner's images: wrapped artwork lets these through as they are. */
        const val IMAGE_PATH = "/api/live/tuner/"

        fun idOf(stationId: String, internetId: String?) = PREFIX + stationId + (internetId?.let { "~$it" } ?: "")

        fun parse(raw: String): Id? {
            if (!raw.startsWith(PREFIX)) return null
            val rest = raw.removePrefix(PREFIX)
            val station = rest.substringBefore('~').takeIf { it.matches(Regex("[0-9A-Za-z.\\-]{1,24}")) } ?: return null
            val internet = rest.substringAfter('~', "").takeIf { it.matches(Regex("[0-9A-Za-z\\-]{1,64}")) }
            return Id(station, internet)
        }

        fun blendKey(sourceId: String, stationId: String, internetId: String) = "$sourceId|$stationId|$internetId"

        fun titleKey(source: StoredSource, internet: RadioStation) = "${source.id}|${internet.id}"

        /** The server's address for a tuner image (the tuner's `?v=` kept, so a new one is fetched when it changes). */
        fun imagePath(sourceId: String, stationId: String, kind: String, tunerUrl: String): String {
            val version = tunerUrl.substringAfter("?v=", "").takeIf { it.matches(Regex("[0-9a-f]{1,32}")) }
            return "$IMAGE_PATH${sourceId.encodeURLPathPart()}/image/${stationId.encodeURLPathPart()}/$kind" + (version?.let { "?v=${it.encodeURLParameter()}" } ?: "")
        }
    }
}
