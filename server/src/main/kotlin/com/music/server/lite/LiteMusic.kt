package com.music.server.lite

import com.music.player.model.SourceType
import com.music.player.sources.TrackMatcher
import com.music.server.ApiError
import com.music.server.music.AlbumSummary
import com.music.server.music.ApiTrack
import com.music.server.music.ArtistSummary
import com.music.server.music.BrowseKey
import com.music.server.music.ResolveRequest
import com.music.server.music.resolveStream
import com.music.server.playlists.PlaylistSummary
import com.music.server.playlists.keepableTrack
import com.music.server.radio.StationKind
import com.music.server.radio.Stations
import com.music.server.sources.SourceKind
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

// Pieces of pages that need the request (its form token, its artwork) take the page being made, [h].

// ---- The start, search, artists, albums and songs ----

internal suspend fun Lite.homePage() {
    val playlists = services.playlists.list(user.id).sortedByDescending { maxOf(it.playedAt ?: 0, it.updatedAt) }.take(6)
    val favorites = services.liveFavorites.list(user.id).take(8).map(art::outgoing)
    page("") {
        h1("Hello, ${user.displayName.ifBlank { user.username }.substringBefore(' ')}")
        searchForm("")
        el("ul", "class" to "menu") {
            for ((href, label) in HOME_LINKS) el("li") { a(href, label) }
        }
        if (playlists.isNotEmpty()) {
            h2("Your playlists")
            playlistRows(this, playlists)
        }
        if (favorites.isNotEmpty()) {
            h2("Favourite stations")
            liveRows(this, favorites)
        }
    }
}

private val HOME_LINKS = listOf(
    "/lite/playlists" to "Playlists",
    "/lite/radio" to "Radio",
    "/lite/now" to "Now playing",
    "/lite/history" to "History",
    "/lite/heard" to "Recently heard",
    "/lite/wrapped" to "Wrapped",
    "/lite/devices" to "Your devices",
    "/lite/settings" to "Settings",
)

internal fun Html.searchForm(query: String) {
    el("form", "action" to "/lite/search", "method" to "get", "class" to "search", "role" to "search") {
        empty("input", "type" to "search", "name" to "q", "value" to query, "placeholder" to "Songs, artists, albums, stations", "aria-label" to "Search")
        el("button", "class" to "btn", "type" to "submit") { text("Search") }
    }
}

internal fun Lite.playlistRows(h: Html, playlists: List<PlaylistSummary>) = with(h) {
    el("ul", "class" to "rows") {
        for (p in playlists) el("li") {
            el("a", "href" to "/lite/playlist/${p.id.encodeURLParameter()}") {
                cover(art.wrap(p.artwork.firstOrNull()))
                el("span", "class" to "tx") {
                    el("b") { text(p.name) }
                    el("small") { text(if (p.count == 1) "1 song" else "${p.count} songs") }
                }
            }
        }
    }
}

/** Live channels and stations; a Pandora station opens its page, as it's started rather than tuned in. */
internal fun Lite.liveRows(h: Html, list: List<ApiTrack>) = with(h) {
    val (stations, live) = list.partition(LiteTracks::isPandoraStation)
    if (live.isNotEmpty()) tracks(live, single = true, sub = { it.artist })
    if (stations.isNotEmpty()) {
        el("ul", "class" to "rows") {
            for (s in stations) el("li") {
                el("a", "href" to url("/lite/radio/pandora/station", "src" to s.via, "id" to s.sourceId.removePrefix(LiteTracks.PANDORA_STATION), "name" to s.title)) {
                    cover(s.artworkUrl)
                    el("span", "class" to "tx") {
                        el("b") { text(s.title) }
                        el("small") { text("Pandora station") }
                    }
                }
            }
        }
    }
}

internal fun Lite.artistRows(h: Html, artists: List<ArtistSummary>) = with(h) {
    el("ul", "class" to "rows") {
        for (a in artists) el("li") {
            el("a", "href" to url("/lite/artist", "id" to a.id)) {
                cover(a.artworkUrl, cls = "cv round")
                el("span", "class" to "tx") {
                    el("b") { text(a.name) }
                    el("small") { text("Artist") }
                }
            }
        }
    }
}

internal fun Lite.albumRows(h: Html, albums: List<AlbumSummary>) = with(h) {
    el("ul", "class" to "rows") {
        for (a in albums) el("li") {
            el("a", "href" to url("/lite/album", "id" to a.id)) {
                cover(a.artworkUrl)
                el("span", "class" to "tx") {
                    el("b") { text(a.title) }
                    el("small") { text(listOfNotNull(a.artist, a.year?.toString()).joinToString(" · ")) }
                }
            }
        }
    }
}

private class Found(
    val music: com.music.player.browse.SearchResults,
    val playlists: List<PlaylistSummary>,
    val live: List<ApiTrack>,
)

/** How long search waits for the radio directory and SiriusXM before showing what it has. */
private const val SLOW_MS = 6_000L

internal suspend fun Lite.searchPage() {
    val query = call.request.queryParameters["q"]?.trim().orEmpty().take(200)
    if (query.isEmpty()) {
        return page("Search") {
            h1("Search")
            searchForm("")
        }
    }
    val sources = services.sources.activeFor(user)
    val found = coroutineScope {
        val music = async { services.browser.search(user, query, 20).results }
        val playlists = async { services.playlists.list(user.id).filter { it.name.contains(query, ignoreCase = true) }.take(10) }
        val favorites = async { services.liveFavorites.list(user.id).filter { it.title.contains(query, ignoreCase = true) }.map(art::outgoing) }
        val channels = async {
            sources.filter { it.kind == SourceKind.SIRIUSXM }.flatMap { source ->
                val client = services.registry.siriusXm(source) ?: return@flatMap emptyList()
                withTimeoutOrNull(SLOW_MS) { runCatching { client.channels() }.getOrNull() }.orEmpty()
                    .filter { !it.locked && it.name.contains(query, ignoreCase = true) }
                    .take(10).map { art.outgoing(ApiTrack.of(it.toTrack(), source.id)) }
            }
        }
        val stations = async {
            val radio = sources.firstOrNull { it.kind == SourceKind.RADIO } ?: return@async emptyList()
            withTimeoutOrNull(SLOW_MS) { runCatching { services.registry.radio.search(query, limit = 10) }.getOrNull() }.orEmpty()
                .take(10).map { art.outgoing(ApiTrack.of(it.toTrack(), radio.id)) }
        }
        Found(music.await(), playlists.await(), (favorites.await() + channels.await() + stations.await()).distinctBy { "${it.via}|${it.sourceId}" })
    }
    val views = services.views
    val top = views.top(found.music.top)
    val songs = found.music.songs.map(views::track)
    val artists = found.music.artists.map(views::artist)
    val albums = found.music.albums.map(views::album)
    page("“$query”") {
        h1("Search")
        searchForm(query)
        if (top != null) {
            h2("Top result")
            when {
                top.artist != null -> artistRows(this, listOf(top.artist))
                top.album != null -> albumRows(this, listOf(top.album))
                top.track != null -> tracks(listOf(top.track), single = true)
            }
        }
        if (songs.isNotEmpty()) {
            h2("Songs")
            tracks(songs)
        }
        if (artists.isNotEmpty()) {
            h2("Artists")
            artistRows(this, artists)
        }
        if (albums.isNotEmpty()) {
            h2("Albums")
            albumRows(this, albums)
        }
        if (found.playlists.isNotEmpty()) {
            h2("Your playlists")
            playlistRows(this, found.playlists)
        }
        if (found.live.isNotEmpty()) {
            h2("Radio")
            liveRows(this, found.live)
        }
        if (top == null && songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && found.playlists.isEmpty() && found.live.isEmpty()) {
            notice("Nothing found. Try fewer words, or check the spelling.")
        }
    }
}

internal suspend fun Lite.artistPage() {
    val params = call.request.queryParameters
    val key = params["id"]?.let(BrowseKey::decode)
        ?: params["name"]?.trim()?.takeIf { it.isNotEmpty() && it.length < 300 }?.let { BrowseKey.artist(TrackMatcher.primaryArtist(it)) }
        ?: throw ApiError.badRequest("Which artist?")
    val view = services.views.artistPage(services.browser.artist(user, key))
    page(view.artist.name) {
        el("div", "class" to "head") {
            cover(view.artist.artworkUrl, 96, "cv round big")
            h1(view.artist.name)
        }
        if (view.topSongs.isNotEmpty()) {
            h2("Popular")
            playAll(view.topSongs.firstOrNull())
            tracks(view.topSongs)
        }
        if (view.albums.isNotEmpty()) {
            h2("Albums")
            albumRows(this, view.albums)
        }
        if (view.singles.isNotEmpty()) {
            h2("Singles and EPs")
            albumRows(this, view.singles)
        }
        if (view.topSongs.isEmpty() && view.albums.isEmpty() && view.singles.isEmpty()) notice("Nothing by this artist was found.")
    }
}

internal suspend fun Lite.albumPage() {
    val params = call.request.queryParameters
    val key = params["id"]?.let(BrowseKey::decode)
        ?: params["title"]?.trim()?.takeIf { it.isNotEmpty() && it.length < 300 }?.let { BrowseKey.album(it, params["artist"]?.trim().orEmpty()) }
        ?: throw ApiError.badRequest("Which album?")
    val view = services.views.albumPage(services.browser.album(user, key))
    val album = view.album
    page(album.title) {
        el("div", "class" to "head") {
            cover(album.artworkUrl, 96, "cv big")
            el("div") {
                h1(album.title)
                el("p") {
                    a(url("/lite/artist", "id" to album.artistId), album.artist)
                    album.year?.let { text(" · $it") }
                }
            }
        }
        playAll(view.tracks.firstOrNull())
        tracks(view.tracks, numbered = true, sub = { t -> t.durationMs?.takeIf { it > 0 }?.let(Lite::duration) ?: t.artist })
    }
}

/** A song (or channel, or station) on its own: play it, and what else can be done with it. */
internal suspend fun Lite.songPage() {
    val track = LiteTracks.decode(call.request.queryParameters["t"]) ?: throw ApiError.notFound("That link is broken")
    val token = LiteTracks.token(track)
    val live = track.toTrack().isLive
    val playlistId = call.request.queryParameters["pl"]
    val entryId = call.request.queryParameters["e"]
    val favorite = favoriteState(track)
    page(track.title) {
        el("div", "class" to "head") {
            cover(track.artworkUrl, 96, "cv big")
            el("div") {
                h1(track.title)
                el("p") {
                    if (live || track.artist.isBlank()) text(track.artist)
                    else a(url("/lite/artist", "name" to track.artist), track.artist)
                }
                if (!live && !track.album.isNullOrBlank()) {
                    el("p") { a(url("/lite/album", "title" to track.album, "artist" to track.artist), track.album) }
                }
                track.durationMs?.takeIf { it > 0 }?.let { el("p", "class" to "muted") { text(Lite.duration(it)) } }
            }
        }
        el("div", "class" to "acts", "data-list" to "single") {
            el("a", "class" to "btn t", "href" to "/lite/play?t=$token") { text("Play") }
            if (!live) {
                el("button", "class" to "btn js", "type" to "button", "data-queue" to "next", "data-t" to token) { text("Play next") }
                el("button", "class" to "btn js", "type" to "button", "data-queue" to "end", "data-t" to token) { text("Add to queue") }
            }
        }
        if (!live) el("p") { a("/lite/add?t=$token", "Add to a playlist", "btn") }
        if (playlistId != null && entryId != null) {
            post("/lite/playlist/${playlistId.encodeURLParameter()}/remove") {
                hidden("e", entryId)
                el("button", "class" to "btn", "type" to "submit") { text("Remove from this playlist") }
            }
        }
        favoriteForm(this, track, favorite, "/lite/song?t=$token")
    }
}

/** Whether a live channel or station is a favourite; null when it can't be one (songs, Pandora tracks). */
internal suspend fun Lite.favoriteState(track: ApiTrack): Boolean? {
    val can = track.via != null && (track.source == SourceType.RADIO || (track.source == SourceType.SIRIUSXM && track.sourceId.startsWith("channel:")))
    if (!can) return null
    return services.liveFavorites.list(user.id).any { it.source == track.source && it.sourceId == track.sourceId && it.via == track.via }
}

internal fun Lite.favoriteForm(h: Html, track: ApiTrack, favorite: Boolean?, back: String) = with(h) {
    if (favorite == null) return@with
    post("/lite/favorite") {
        hidden("t", LiteTracks.token(track))
        hidden("on", if (favorite) "0" else "1")
        hidden("back", back)
        el("button", "class" to "btn", "type" to "submit", "aria-pressed" to favorite.toString()) {
            text(if (favorite) "♥ Favourite (take it out)" else "♡ Add to favourites")
        }
    }
}

internal suspend fun Lite.setFavorite() {
    val params = form()
    val track = LiteTracks.decode(params["t"])?.let(art::incoming) ?: throw ApiError.badRequest("Which station?")
    val on = params["on"] == "1"
    if (!track.toTrack().isLive || track.toTrack().isSiriusXmStationTrack) throw ApiError.badRequest("Only channels and stations can be favourites")
    if (!Stations.isPersonal(track) && (track.via.isNullOrBlank() || services.sources.accessible(user, track.via) == null)) {
        throw ApiError.notFound("That source isn't available")
    }
    services.liveFavorites.set(user.id, track, on)
    seeOther(withDone(params["back"], if (on) "favorite" else "unfavorite"))
}

/**
 * Plays one song with the browser's own player: how songs play with the script off (with it,
 * the player at the bottom of the page takes over). Live channels and stations play here too.
 */
internal suspend fun Lite.playPage() {
    val track = LiteTracks.decode(call.request.queryParameters["t"]) ?: throw ApiError.notFound("That link is broken")
    val token = LiteTracks.token(track)
    val live = LiteTracks.live(track, timeZone)
    val (src, playing) = if (live != null) {
        live.url to track
    } else {
        // Without the script the browser can't say what it plays: MP3 and AAC play everywhere.
        val resolved = resolveStream(services, user, ResolveRequest(track, kbps, codecs = listOf("mp3", "aac")))
        resolved.streamUrl to resolved.track
    }
    val now = if (live != null) whatsOn(track, live) else null
    val favorite = if (live != null) favoriteState(track) else null
    page(playing.title) {
        el("div", "class" to "head") {
            cover(playing.artworkUrl, 96, "cv big")
            el("div") {
                h1(playing.title)
                el("p") { text(playing.artist) }
            }
        }
        doneNotice(doneMessage())
        el("div", "id" to "play-here", "data-t" to token) {
            el("audio", "controls" to true, "autoplay" to true, "preload" to "auto", "src" to src) {
                text("This browser can't play music.")
            }
        }
        if (live?.hls == true) {
            el("p", "class" to "muted") { text("SiriusXM's channels are HLS streams, which only some browsers (Safari on iPhones, iPads and Macs) play by themselves.") }
        }
        if (now != null) el("p") { el("b") { text("On now: ") }; text(now.text) }
        if (live?.kind == "personal") personalControls(this, track, token, now)
        if (live != null) {
            favoriteForm(this, track, favorite, "/lite/play?t=$token")
            el("p") { a("/lite/play?t=$token", "What's on? (reload)") }
        } else {
            el("p") { a("/lite/add?t=${LiteTracks.token(playing)}", "Add to a playlist", "btn") }
        }
    }
}

/** What's on a live channel or station now (and on one's own stations, the song's key, for thumbs). */
internal class OnNow(val text: String, val key: String? = null)

private suspend fun Lite.whatsOn(track: ApiTrack, live: LiteTracks.Live): OnNow? = withTimeoutOrNull(4_000) {
    runCatching {
        when (live.kind) {
            "personal" -> StationKind.of(track.sourceId)?.let { services.personalRadio.onAir(user, it)?.current?.song }
                ?.let { OnNow("${it.track.title} · ${it.track.artist}", it.key) }
            "radio" -> services.live.radioTitle("${track.via}|${track.sourceId.removePrefix("hls:")}")?.let(::OnNow)
            "channel" -> {
                val source = services.sources.accessible(user, track.via.orEmpty())?.takeIf { it.enabled }
                val parts = track.sourceId.removePrefix("channel:").split(':', limit = 2)
                if (source == null || parts.size < 2) null
                else services.registry.siriusXm(source)?.cutAt(parts[0], parts[1], services.clock())?.let { OnNow("${it.title} · ${it.artist}") }
            }
            else -> null
        }
    }.getOrNull()
}

/** Skip and thumbs on one of the person's own stations, as forms. */
private fun Lite.personalControls(h: Html, track: ApiTrack, token: String, now: OnNow?) = with(h) {
    el("div", "class" to "acts") {
        post("/lite/station/skip", "inline") {
            hidden("id", track.sourceId)
            hidden("back", "/lite/play?t=$token")
            el("button", "class" to "btn", "type" to "submit") { text("Skip") }
        }
        if (now?.key != null) {
            for ((thumb, label) in listOf("UP" to "Thumbs up", "DOWN" to "Thumbs down")) {
                post("/lite/station/thumb", "inline") {
                    hidden("id", track.sourceId)
                    hidden("key", now.key)
                    hidden("thumb", thumb)
                    hidden("back", "/lite/play?t=$token")
                    el("button", "class" to "btn", "type" to "submit") { text(label) }
                }
            }
        }
    }
}

internal suspend fun Lite.stationSkip() {
    val params = form()
    val kind = StationKind.of(params["id"].orEmpty()) ?: throw ApiError.notFound("There's no such station")
    services.personalRadio.skip(user, kind)
    seeOther(withDone(params["back"], "skipped"))
}

internal suspend fun Lite.stationThumb() {
    val params = form()
    val kind = StationKind.of(params["id"].orEmpty()) ?: throw ApiError.notFound("There's no such station")
    val thumb = when (params["thumb"]) {
        "UP" -> 1
        "DOWN" -> -1
        else -> throw ApiError.badRequest("Up or down?")
    }
    services.personalRadio.thumb(user, kind, params["key"].orEmpty().take(500), thumb)
    seeOther(withDone(params["back"], "thumbed"))
}

/** [back] (a lite page) saying what was done. */
internal fun withDone(back: String?, done: String): String {
    val path = liteReturn(back).replace(Regex("[?&]done=[a-z]+"), "")
    return path + (if ('?' in path) "&" else "?") + "done=$done"
}

/** Now playing: the script fills it in from what it's playing. */
internal suspend fun Lite.nowPage() {
    page("Now playing") {
        h1("Now playing")
        el("div", "id" to "now") {
            el("p", "class" to "muted nojs") { text("Now playing needs JavaScript. Without it, each song plays on its own page.") }
        }
    }
}

// ---- Playlists ----

internal suspend fun Lite.playlistsPage() {
    val playlists = services.playlists.list(user.id).sortedBy { it.name.lowercase() }
    page("Playlists") {
        h1("Playlists")
        doneNotice(doneMessage())
        if (playlists.isEmpty()) notice("No playlists yet.") else playlistRows(this, playlists)
        h2("New playlist")
        newPlaylistForm(this, null)
    }
}

private fun Lite.newPlaylistForm(h: Html, token: String?) = with(h) {
    post("/lite/playlists", "search") {
        if (token != null) hidden("t", token)
        empty("input", "name" to "name", "placeholder" to "Name", "aria-label" to "The new playlist's name", "required" to true, "maxlength" to 200)
        el("button", "class" to "btn", "type" to "submit") { text(if (token != null) "Make it, with this song" else "Make it") }
    }
}

internal suspend fun Lite.createPlaylist() {
    val params = form()
    val tracks = listOfNotNull(LiteTracks.decode(params["t"])?.let { keepableTrack(art, it) })
    val playlist = services.playlists.create(user.id, params["name"].orEmpty().trim(), "", tracks)
    seeOther("/lite/playlist/${playlist.summary.id.encodeURLParameter()}?done=${if (tracks.isEmpty()) "created" else "added"}")
}

internal suspend fun Lite.playlistPage() {
    val id = call.parameters["id"].orEmpty()
    val playlist = services.playlists.get(user.id, id)
    val entries = playlist.entries.map { it.copy(track = art.outgoing(it.track)) }
    val summary = playlist.summary
    page(summary.name) {
        h1(summary.name)
        doneNotice(doneMessage())
        el("p", "class" to "muted") {
            text(
                listOfNotNull(
                    if (summary.count == 1) "1 song" else "${summary.count} songs",
                    summary.durationMs.takeIf { it > 0 }?.let { "${it / 60_000} min" },
                    summary.description.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
            )
        }
        if (entries.isEmpty()) notice("Nothing in it yet: each song's page has “Add to a playlist”.")
        el("div", "data-playlist" to summary.id) {
            playAll(entries.firstOrNull { !it.track.toTrack().isMissing }?.track)
            tracks(entries.map { it.track }, extra = { i, _ -> "&pl=${id.encodeURLParameter()}&e=${entries[i].entryId.encodeURLParameter()}" })
        }
    }
}

internal suspend fun Lite.removeFromPlaylist() {
    val id = call.parameters["id"].orEmpty()
    val entry = form()["e"] ?: throw ApiError.badRequest("Which song?")
    services.playlists.remove(user.id, id, setOf(entry))
    seeOther("/lite/playlist/${id.encodeURLParameter()}?done=removed")
}

internal suspend fun Lite.addPage() {
    val track = LiteTracks.decode(call.request.queryParameters["t"]) ?: throw ApiError.notFound("That link is broken")
    val token = LiteTracks.token(track)
    val playlists = services.playlists.list(user.id).sortedByDescending { it.updatedAt }
    page("Add to a playlist") {
        h1("Add to a playlist")
        el("p") {
            el("b") { text(track.title) }
            text(" · ${track.artist}")
        }
        if (track.toTrack().isLive && !track.toTrack().isSiriusXmStationTrack) {
            notice("Live radio can't go in a playlist.")
            return@page
        }
        el("ul", "class" to "rows") {
            for (p in playlists) el("li") {
                post("/lite/add", "row") {
                    hidden("t", token)
                    hidden("playlist", p.id)
                    el("button", "type" to "submit") {
                        cover(art.wrap(p.artwork.firstOrNull()))
                        el("span", "class" to "tx") {
                            el("b") { text(p.name) }
                            el("small") { text(if (p.count == 1) "1 song" else "${p.count} songs") }
                        }
                    }
                }
            }
        }
        h2("A new playlist")
        newPlaylistForm(this, token)
    }
}

internal suspend fun Lite.addToPlaylist() {
    val params = form()
    val track = LiteTracks.decode(params["t"])?.let { keepableTrack(art, it) } ?: throw ApiError.badRequest("Live radio can't go in a playlist")
    val id = params["playlist"] ?: throw ApiError.badRequest("Which playlist?")
    services.playlists.add(user.id, id, listOf(track))
    seeOther("/lite/playlist/${id.encodeURLParameter()}?done=added")
}
