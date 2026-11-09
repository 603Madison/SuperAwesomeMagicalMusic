package com.music.server.playlists

import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.playlists.PlaylistCsv
import com.music.server.ApiError
import com.music.server.Services
import com.music.server.auth.requireUser
import com.music.server.music.ApiTrack
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class NewPlaylist(val name: String, val description: String = "", val tracks: List<ApiTrack> = emptyList(), val id: String? = null)

@Serializable
data class PlaylistChange(val name: String? = null, val description: String? = null)

@Serializable
data class AddTracks(val tracks: List<ApiTrack>, val position: Int? = null)

@Serializable
data class RemoveEntries(val entryIds: List<String>)

@Serializable
data class Reorder(val revision: Long, val entryIds: List<String>)

/** A link to a playlist or album elsewhere, or a typed or pasted list of songs ([text], named [name]). */
@Serializable
data class ImportRequest(val url: String = "", val text: String = "", val name: String = "")

@Serializable
data class ImportResult(val playlist: Playlist, val found: Int, val missing: List<String>, val note: String? = null)

/** What looking for a playlist's missing songs again found. */
@Serializable
data class FoundAgain(val playlist: Playlist, val found: Int, val stillMissing: Int)

@Serializable
data class HistoryUpload(val listens: List<Listen>)

fun Route.playlistRoutes(services: Services) {
    val art = services.artwork
    // A Pandora station track's link expires, so it's kept as the song itself (found again when
    // played); live channels and stations aren't songs, and aren't kept.
    fun keepable(track: ApiTrack): ApiTrack? = when {
        track.source == SourceType.SIRIUSXM && track.sourceId.startsWith(Track.STATION_TRACK_PREFIX) ->
            art.incoming(track).copy(sourceId = "song:" + track.sourceId.substringAfterLast(':'), via = null)
        track.toTrack().isLive -> null
        else -> art.incoming(track)
    }
    fun Playlist.out() = copy(
        summary = summary.out(services),
        entries = entries.map { it.copy(track = art.outgoing(it.track)) },
    )

    route("/api/playlists") {
        get {
            val user = call.requireUser(services)
            call.respond(services.playlists.list(user.id).map { it.out(services) })
        }
        post {
            val user = call.requireUser(services)
            val request = call.receive<NewPlaylist>()
            call.respond(services.playlists.create(user.id, request.name, request.description, request.tracks.mapNotNull(::keepable), request.id).out())
        }
        post("/import") {
            val user = call.requireUser(services)
            val request = call.receive<ImportRequest>()
            val imported = when {
                request.url.isNotBlank() -> services.importer.fromLink(user, request.url)
                request.text.isNotBlank() -> {
                    if (request.text.length > 1_000_000) throw ApiError.badRequest("That list is too long")
                    services.importer.fromText(user, request.text, request.name)
                }
                else -> throw ApiError.badRequest("Paste a link, or a list of songs")
            }
            if (imported.tracks.isEmpty()) throw ApiError.badRequest("There are no songs in it")
            // Songs that weren't found go in too, greyed out, and are looked for again now and then.
            val tracks = imported.tracks.take(PlaylistStore.MAX_ENTRIES)
            val playlist = services.playlists.create(user.id, imported.name, imported.origin, tracks)
            call.respond(
                ImportResult(
                    playlist.out(),
                    tracks.count { !it.toTrack().isMissing },
                    imported.missing.map { if (it.artist.isBlank()) it.title else "${it.artist} – ${it.title}" },
                    imported.note,
                ),
            )
        }
        route("/{id}") {
            get {
                val user = call.requireUser(services)
                call.respond(services.playlists.get(user.id, call.parameters["id"].orEmpty()).out())
            }
            // Started playing (on any device): the sidebar lists playlists by when they were played.
            post("/played") {
                val user = call.requireUser(services)
                services.playlists.played(user.id, call.parameters["id"].orEmpty())
                call.respond(mapOf("ok" to true))
            }
            // Looks again for its songs that weren't found; those found now take their places.
            post("/find-missing") {
                val user = call.requireUser(services)
                val id = call.parameters["id"].orEmpty()
                val playlist = services.playlists.get(user.id, id)
                val missing = playlist.entries.filter { it.track.toTrack().isMissing }
                val found = if (missing.isEmpty()) emptyList() else services.importer.findAgain(user, missing.map { it.track })
                val replacements = missing.zip(found).mapNotNull { (entry, track) -> track?.let { entry.entryId to it } }.toMap()
                val updated = if (replacements.isEmpty()) playlist else services.playlists.replace(user.id, id, replacements)
                call.respond(FoundAgain(updated.out(), replacements.size, missing.size - replacements.size))
            }
            // The playlist as a CSV file (Exportify's columns, which the import reads back).
            get("/export.csv") {
                val user = call.requireUser(services)
                val playlist = services.playlists.get(user.id, call.parameters["id"].orEmpty())
                val csv = PlaylistCsv.write(playlist.entries.map { PlaylistCsv.Row(it.track.title, it.track.artist, it.track.album, it.track.durationMs, it.track.isrc) })
                call.response.header(HttpHeaders.ContentDisposition, attachment(PlaylistCsv.fileName(playlist.summary.name)))
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(csv, ContentType.parse("text/csv; charset=utf-8"))
            }
            patch {
                val user = call.requireUser(services)
                val change = call.receive<PlaylistChange>()
                call.respond(services.playlists.rename(user.id, call.parameters["id"].orEmpty(), change.name, change.description).out(services))
            }
            delete {
                val user = call.requireUser(services)
                services.playlists.delete(user.id, call.parameters["id"].orEmpty())
                call.respond(mapOf("ok" to true))
            }
            post("/tracks") {
                val user = call.requireUser(services)
                val request = call.receive<AddTracks>()
                val tracks = request.tracks.mapNotNull(::keepable)
                if (tracks.isEmpty()) throw ApiError.badRequest("Live radio can't go in a playlist")
                call.respond(services.playlists.add(user.id, call.parameters["id"].orEmpty(), tracks, request.position).out())
            }
            post("/remove") {
                val user = call.requireUser(services)
                call.respond(services.playlists.remove(user.id, call.parameters["id"].orEmpty(), call.receive<RemoveEntries>().entryIds.toSet()).out())
            }
            put("/order") {
                val user = call.requireUser(services)
                val request = call.receive<Reorder>()
                call.respond(services.playlists.reorder(user.id, call.parameters["id"].orEmpty(), request.revision, request.entryIds).out())
            }
        }
    }

    route("/api/history") {
        get {
            val user = call.requireUser(services)
            val params = call.request.queryParameters
            val listens = services.history.list(user.id, params["limit"]?.toIntOrNull() ?: 100, params["before"]?.toLongOrNull())
            call.respond(listens.map { it.copy(track = art.outgoing(it.track)) })
        }
        post {
            val user = call.requireUser(services)
            val upload = call.receive<HistoryUpload>().listens.take(500)
            services.history.add(user.id, upload.map { it.copy(track = art.incoming(it.track)) })
            call.respond(mapOf("ok" to true))
        }
        delete {
            val user = call.requireUser(services)
            services.history.clear(user.id)
            call.respond(mapOf("ok" to true))
        }
    }
}

private fun PlaylistSummary.out(services: Services) = copy(artwork = artwork.mapNotNull { services.artwork.wrap(it) })

/** A download's Content-Disposition: the name as it is, with a plain-ASCII one for old browsers. */
internal fun attachment(fileName: String): String {
    val ascii = fileName.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
    val encoded = java.net.URLEncoder.encode(fileName, Charsets.UTF_8).replace("+", "%20")
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}
