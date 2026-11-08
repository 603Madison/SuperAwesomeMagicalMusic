package com.music.server.playlists

import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.playlists.SpotifyPlaylists
import com.music.player.sources.TrackMatcher
import com.music.player.sources.youtube.YouTubeMusicSource
import com.music.server.ApiError
import com.music.server.Services
import com.music.server.auth.requireUser
import com.music.server.music.ApiTrack
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

@Serializable
data class ImportRequest(val url: String)

@Serializable
data class ImportResult(val playlist: Playlist, val found: Int, val missing: List<String>)

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
            val url = call.receive<ImportRequest>().url.trim()
            val (name, songs) = when {
                SpotifyPlaylists.isSpotify(url) -> SpotifyPlaylists(services.http).read(url).let { (name, listed) ->
                    name to listed.map { Track(SourceType.YOUTUBE_MUSIC, "", it.title, it.artist, durationMs = it.durationMs) }
                }
                "youtube.com/" in url || "youtu.be/" in url -> YouTubeMusicSource(services.http).playlist(url)
                else -> throw ApiError.badRequest("Paste a Spotify or YouTube playlist link")
            }
            // Each song is matched in the person's own sources (their library first).
            val searches = Semaphore(4)
            val matched = coroutineScope {
                songs.map { song ->
                    async {
                        searches.withPermit {
                            if (song.sourceId.isNotEmpty()) return@withPermit song to ApiTrack.of(song, null)
                            val query = TrackMatcher.searchQuery(song)
                            val found = runCatching { services.catalog.search(user, query, 10) }.getOrDefault(emptyList())
                            val candidates = found.sortedByDescending { it.kind.isLibrary }.flatMap { it.tracks }
                            song to candidates.firstOrNull { TrackMatcher.matches(it.toTrack(), song) }
                        }
                    }
                }.awaitAll()
            }
            val tracks = matched.mapNotNull { it.second }
            val playlist = services.playlists.create(user.id, name, "Imported from $url", tracks.take(PlaylistStore.MAX_ENTRIES))
            call.respond(ImportResult(playlist.out(), tracks.size, matched.filter { it.second == null }.map { "${it.first.artist} – ${it.first.title}" }))
        }
        route("/{id}") {
            get {
                val user = call.requireUser(services)
                call.respond(services.playlists.get(user.id, call.parameters["id"].orEmpty()).out())
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
