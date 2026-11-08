package com.music.server.music

import com.music.player.sources.radio.RadioStation
import com.music.player.sources.siriusxm.SiriusXmClient
import com.music.player.sources.siriusxm.SxmCut
import com.music.player.sources.siriusxm.SiriusXmParser
import com.music.player.sources.siriusxm.SxmArtist
import com.music.player.sources.siriusxm.SxmThumb
import com.music.server.ApiError
import com.music.server.Services
import com.music.server.auth.User
import com.music.server.auth.requireUser
import com.music.server.sources.SourceKind
import com.music.server.sources.SourceSetup
import com.music.server.sources.StoredSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ChannelView(val track: ApiTrack, val number: Int? = null, val category: String, val locked: Boolean)

@Serializable
data class NowView(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val startMs: Long,
    val durationMs: Long,
    /** The song, to save to a playlist: it plays from your library or a streaming service. */
    val song: ApiTrack? = null,
    val artistId: String? = null,
)

@Serializable
data class ArtistView(val id: String, val name: String, val artworkUrl: String? = null, val stationId: String? = null, val entityType: String = "talent")

@Serializable
data class StationRequest(val artist: ArtistView? = null, val stationId: String? = null, val name: String? = null, val more: Boolean = false)

@Serializable
data class StationTracks(val station: ApiTrack, val tracks: List<ApiTrack>)

@Serializable
data class ProgressReport(val elapsedMs: Long, val start: Boolean = false)

@Serializable
data class ThumbRequest(val thumb: String, val elapsedMs: Long = 0)

@Serializable
data class RadioNow(val title: String? = null)

/** Making a live channel or station a favourite, or not. */
@Serializable
data class FavoriteChange(val track: ApiTrack, val favorite: Boolean)

fun Route.liveRoutes(services: Services) {
    val proxy = services.live
    val art = services.artwork
    val counted = ConcurrentHashMap.newKeySet<String>()

    /** The SiriusXM source [call] names, if the person may use it, and its client. */
    suspend fun ApplicationCall.siriusXm(user: User): Pair<StoredSource, SiriusXmClient> {
        val source = services.sources.accessible(user, parameters["src"].orEmpty())?.takeIf { it.enabled && it.kind == SourceKind.SIRIUSXM }
            ?: throw ApiError.notFound("That SiriusXM account isn't available")
        return source to services.registry.siriusXm(source)!!
    }

    suspend fun ApplicationCall.radioSource(user: User): StoredSource =
        services.sources.accessible(user, parameters["src"].orEmpty())?.takeIf { it.enabled && it.kind == SourceKind.RADIO }
            ?: throw ApiError.notFound("Radio isn't available")

    fun keyPath(source: StoredSource) = { guid: String -> "/api/live/sxm/${source.id.encodeURLPathPart()}/key/${guid.encodeURLPathPart()}" }

    fun stationsOut(source: StoredSource, stations: List<RadioStation>) = stations.map { art.outgoing(ApiTrack.of(it.toTrack(), source.id)) }

    /** What's (or what was) on, with the song's own cover when the channel has none. */
    suspend fun nowView(user: User, cut: SxmCut, waitMs: Long): NowView {
        val song = if (cut.isSong) cut.toTrack() else null
        val cover = cut.artworkUrl ?: song?.let { services.songArt.of(user, it, waitMs) }
        return NowView(
            title = cut.title,
            artist = cut.artist,
            album = cut.album,
            artworkUrl = art.wrap(cover),
            startMs = cut.startMs,
            durationMs = cut.durationMs,
            song = song?.let { art.outgoing(ApiTrack.of(it.copy(artworkUrl = cover), null)) },
            artistId = cut.artistId,
        )
    }

    route("/api/live") {
        // ---- Favourites: channels and stations, on any of the person's sources ----

        get("/favorites") {
            val user = call.requireUser(services)
            call.respond(services.liveFavorites.list(user.id).map(art::outgoing))
        }

        put("/favorites") {
            val user = call.requireUser(services)
            val change = call.receive<FavoriteChange>()
            val track = art.incoming(change.track)
            if (!track.toTrack().isLive || track.toTrack().isSiriusXmStationTrack) throw ApiError.badRequest("Only channels and stations can be favourites")
            if (track.via.isNullOrBlank() || services.sources.accessible(user, track.via) == null) throw ApiError.notFound("That source isn't available")
            services.liveFavorites.set(user.id, track, change.favorite)
            call.respond(services.liveFavorites.list(user.id).map(art::outgoing))
        }

        // ---- SiriusXM ----

        get("/sxm/{src}/channels") {
            val user = call.requireUser(services)
            val (source, client) = call.siriusXm(user)
            val channels = SourceSetup.service("SiriusXM") { client.channels() }
            call.respond(channels.map { ChannelView(art.outgoing(ApiTrack.of(it.toTrack(), source.id)), it.number, it.category, it.locked) })
        }

        // A channel's playlist: the live stream, or an Xtra channel stitched from its tracks.
        get("/sxm/{src}/channel/{type}/{id}") {
            val user = call.requireUser(services)
            val (source, client) = call.siriusXm(user)
            val type = call.parameters["type"].orEmpty()
            val id = call.parameters["id"].orEmpty().removeSuffix(".m3u8")
            if (type.isBlank() || id.isBlank()) throw ApiError.badRequest("Which channel?")
            val playlist = if (type == SiriusXmClient.LINEAR) {
                val url = SourceSetup.service("SiriusXM") { client.streamUrl(type, id) }
                val (text, finalUrl) = proxy.fetchPlaylist(url, LiveProxy.SXM_HEADERS)
                proxy.rewrite(SiriusXmParser.rewriteKeys(text, client.fallbackKeyId), finalUrl, source.id, keyPath(source))
            } else {
                val text = SourceSetup.service("SiriusXM") { client.xtraPlaylist(type, id) }
                proxy.rewrite(text, "", source.id, keyPath(source))
            }
            proxy.respondPlaylist(call, playlist)
        }

        get("/sxm/{src}/key/{guid}") {
            val user = call.requireUser(services)
            val (_, client) = call.siriusXm(user)
            val key = SourceSetup.service("SiriusXM") { client.key(call.parameters["guid"].orEmpty()) }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respondBytes(key, ContentType.Application.OctetStream)
        }

        // What's on a channel at a moment of the stream (and, on Xtra channels, telling SiriusXM what's heard).
        get("/sxm/{src}/now") {
            val user = call.requireUser(services)
            val (_, client) = call.siriusXm(user)
            val params = call.request.queryParameters
            val type = params["type"].orEmpty()
            val id = params["id"].orEmpty()
            val at = params["at"]?.toLongOrNull() ?: services.clock()
            if (type != SiriusXmClient.LINEAR) runCatching { client.onXtraHeard(type, id, at) }
            val cut = SourceSetup.service("SiriusXM") { client.cutAt(type, id, at) }
            if (cut == null) return@get call.respond(HttpStatusCode.NoContent, "")
            call.respond(nowView(user, cut, waitMs = 2_500))
        }

        // The songs the channel played before this one (its last three hours), most recent first.
        get("/sxm/{src}/recent") {
            val user = call.requireUser(services)
            val (_, client) = call.siriusXm(user)
            val params = call.request.queryParameters
            val at = params["at"]?.toLongOrNull() ?: services.clock()
            val cuts = SourceSetup.service("SiriusXM") { client.recentCuts(params["type"].orEmpty(), params["id"].orEmpty(), at, 30) }
            // Covers already found show now; the rest are looked for, for the next look.
            call.respond(cuts.map { nowView(user, it, waitMs = 0) })
        }

        get("/sxm/{src}/artists") {
            val user = call.requireUser(services)
            val (_, client) = call.siriusXm(user)
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            if (query.isEmpty()) return@get call.respond(emptyList<ArtistView>())
            val artists = SourceSetup.service("SiriusXM") { client.searchArtists(query) }
            call.respond(artists.map { ArtistView(it.id, it.name, art.wrap(it.artworkUrl), it.stationId, it.entityType) })
        }

        // A Pandora artist station's tracks: the first ones, or the next ones with `more`.
        post("/sxm/{src}/station") {
            val user = call.requireUser(services)
            val (source, client) = call.siriusXm(user)
            val request = call.receive<StationRequest>()
            val station = when {
                request.artist != null -> SourceSetup.service("SiriusXM") {
                    val a = request.artist
                    client.artistStation(SxmArtist(a.id, a.name, art.unwrap(a.artworkUrl), a.stationId, a.entityType))
                }
                request.stationId != null -> com.music.player.sources.siriusxm.SxmStation(request.stationId, request.name ?: "Pandora station")
                else -> throw ApiError.badRequest("Which station?")
            }
            val tracks = SourceSetup.service("SiriusXM") { client.stationTracks(station.id, request.more) }
            call.respond(
                StationTracks(
                    station = art.outgoing(ApiTrack.of(station.toTrack(), source.id)),
                    tracks = tracks.map { art.outgoing(ApiTrack.of(it.toTrack(station.name), source.id)) },
                ),
            )
        }

        get("/sxm/{src}/station-track/{trackId}") {
            val user = call.requireUser(services)
            val (_, client) = call.siriusXm(user)
            val url = client.stationTrackUrl(call.parameters["trackId"].orEmpty())
                ?: throw ApiError(HttpStatusCode.Gone, "This station track has expired; start the station again")
            proxy.respondFile(call, url, LiveProxy.SXM_HEADERS)
        }

        post("/sxm/{src}/station-track/{stationId}/{trackId}/progress") {
            val user = call.requireUser(services)
            val (_, client) = call.siriusXm(user)
            val report = call.receive<ProgressReport>()
            runCatching { client.onStationTrack(call.parameters["stationId"].orEmpty(), call.parameters["trackId"].orEmpty(), report.elapsedMs, report.start) }
            call.respond(mapOf("ok" to true))
        }

        post("/sxm/{src}/station-track/{stationId}/{trackId}/thumb") {
            val user = call.requireUser(services)
            val (_, client) = call.siriusXm(user)
            val request = call.receive<ThumbRequest>()
            val thumb = SxmThumb.entries.firstOrNull { it.name == request.thumb.uppercase() } ?: throw ApiError.badRequest("UP, DOWN or NONE")
            val sent = SourceSetup.service("SiriusXM") {
                client.rateStationTrack(call.parameters["stationId"].orEmpty(), call.parameters["trackId"].orEmpty(), thumb, request.elapsedMs)
            }
            if (!sent) throw ApiError.upstream("SiriusXM didn't take the thumb")
            call.respond(mapOf("ok" to true))
        }

        // ---- Anything a rewritten playlist names ----

        get("/hls/{scope}/{kind}") {
            val user = call.requireUser(services)
            val scope = call.parameters["scope"].orEmpty()
            val kind = call.parameters["kind"]?.singleOrNull()?.takeIf { it == 'p' || it == 's' } ?: throw ApiError.notFound()
            val url = call.request.queryParameters["u"].orEmpty()
            if (!proxy.verify(scope, kind, url, call.request.queryParameters["s"].orEmpty())) throw ApiError.notFound()
            val source = services.sources.accessible(user, scope)?.takeIf { it.enabled } ?: throw ApiError.notFound()
            val headers = if (source.kind == SourceKind.SIRIUSXM) LiveProxy.SXM_HEADERS else emptyMap()
            if (kind == 's') return@get proxy.respondFile(call, url, headers)
            val (text, finalUrl) = proxy.fetchPlaylist(url, headers)
            val client = services.registry.siriusXm(source)
            val playlist = if (client != null) {
                proxy.rewrite(SiriusXmParser.rewriteKeys(text, client.fallbackKeyId), finalUrl, scope, keyPath(source))
            } else {
                proxy.rewrite(text, finalUrl, scope)
            }
            proxy.respondPlaylist(call, playlist)
        }

        // ---- Radio ----

        get("/radio/{src}/search") {
            val user = call.requireUser(services)
            val source = call.radioSource(user)
            val params = call.request.queryParameters
            val query = params["q"]?.trim().orEmpty()
            val stations = SourceSetup.service("Radio Browser") { services.registry.radio.search(query, params["country"]) }
            call.respond(stationsOut(source, stations))
        }

        get("/radio/{src}/nearby") {
            val user = call.requireUser(services)
            val source = call.radioSource(user)
            val params = call.request.queryParameters
            val lat = params["lat"]?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: throw ApiError.badRequest("Where? (lat and lon)")
            val lon = params["lon"]?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: throw ApiError.badRequest("Where? (lat and lon)")
            val stations = SourceSetup.service("Radio Browser") {
                services.registry.radio.nearby(lat, lon, params["state"], params["country"])
            }
            call.respond(stationsOut(source, stations))
        }

        get("/radio/{src}/station/{id}/stream") {
            val user = call.requireUser(services)
            val source = call.radioSource(user)
            val id = call.parameters["id"].orEmpty().removePrefix("hls:")
            val station = SourceSetup.service("Radio Browser") { services.registry.radio.station(id) }
            // The directory ranks stations by plays: count each person's once a day.
            if (counted.add("${user.id}|$id|${LocalDate.now()}")) {
                services.scope.launch { services.registry.radio.countPlay(id) }
                if (counted.size > 50_000) counted.clear()
            }
            if (station.isHls) {
                val (text, finalUrl) = proxy.fetchPlaylist(station.streamUrl)
                proxy.respondPlaylist(call, proxy.rewrite(text, finalUrl, source.id))
            } else {
                proxy.respondRadio(call, station.streamUrl, "${source.id}|$id")
            }
        }

        get("/radio/{src}/station/{id}/now") {
            val user = call.requireUser(services)
            val source = call.radioSource(user)
            val id = call.parameters["id"].orEmpty().removePrefix("hls:")
            call.respond(RadioNow(proxy.radioTitle("${source.id}|$id")))
        }
    }
}
