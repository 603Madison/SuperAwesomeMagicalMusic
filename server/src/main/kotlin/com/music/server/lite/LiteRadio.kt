package com.music.server.lite

import com.music.player.sources.siriusxm.SxmArtist
import com.music.player.sources.siriusxm.SxmStation
import com.music.server.ApiError
import com.music.server.music.ApiTrack
import com.music.server.radio.StationKind
import com.music.server.radio.Stations
import com.music.server.sources.SourceKind
import com.music.server.sources.SourceSetup
import com.music.server.sources.StoredSource
import io.ktor.http.HttpHeaders
import io.ktor.server.request.header

/** Radio: the person's own stations, favourites, SiriusXM, Pandora stations and radio stations. */
internal suspend fun Lite.radioPage() {
    val sources = services.sources.activeFor(user)
    val siriusXm = sources.filter { it.kind == SourceKind.SIRIUSXM }
    val radio = radioSource(sources)
    val ready = services.personalRadio.ready(user)
    val personal = StationKind.entries.map { kind ->
        val track = art.outgoing(Stations.track(kind, user.displayName, user.username))
        val onAir = services.personalRadio.onAir(user, kind)?.current?.song?.track
        if (onAir != null) track.copy(artist = "On now: ${onAir.title} · ${onAir.artist}") else track
    }
    val favorites = services.liveFavorites.list(user.id).map(art::outgoing)
    page("Radio") {
        h1("Radio")
        h2("Made for you")
        if (ready) tracks(personal, single = true, sub = { it.artist })
        else notice("Play some music, and your own stations fill up with what you love.")
        if (favorites.isNotEmpty()) {
            h2("Favourites")
            liveRows(this, favorites)
        }
        h2("SiriusXM")
        if (siriusXm.isEmpty()) {
            el("p", "class" to "muted") { text("Add your SiriusXM account in the full web player's Settings › Sources to hear its channels and Pandora stations here.") }
        }
        for (source in siriusXm) {
            el("ul", "class" to "menu") {
                el("li") { a(url("/lite/radio/sxm", "src" to source.id), if (siriusXm.size > 1) "Channels (${source.name})" else "Channels") }
            }
            pandoraForm(this, source, "")
        }
        h2("Radio stations")
        if (radio == null) {
            el("p", "class" to "muted") { text("Radio is off: an admin can add it in the full web player's Admin › Shared sources.") }
        } else {
            stationForm(this, "")
            el("ul", "class" to "menu") {
                el("li") { a("/lite/radio/stations", "Popular stations") }
                el("li", "class" to "js") { el("a", "href" to "/lite/radio/stations", "data-near" to true) { text("Stations near me") } }
            }
        }
    }
}

private fun radioSource(sources: List<StoredSource>) =
    sources.firstOrNull { it.kind == SourceKind.RADIO } ?: sources.firstOrNull { it.kind == SourceKind.TUNER }

private fun Lite.stationForm(h: Html, query: String) = with(h) {
    el("form", "action" to "/lite/radio/stations", "method" to "get", "class" to "search", "role" to "search") {
        empty("input", "type" to "search", "name" to "q", "value" to query, "placeholder" to "Station name, e.g. WFMU or jazz", "aria-label" to "Search stations")
        el("button", "class" to "btn", "type" to "submit") { text("Find") }
    }
}

private fun Lite.pandoraForm(h: Html, source: StoredSource, query: String) = with(h) {
    el("form", "action" to "/lite/radio/pandora", "method" to "get", "class" to "search", "role" to "search") {
        hidden("src", source.id)
        empty("input", "type" to "search", "name" to "q", "value" to query, "placeholder" to "An artist, for a Pandora station", "aria-label" to "Artist for a Pandora station")
        el("button", "class" to "btn", "type" to "submit") { text("Find") }
    }
}

private suspend fun Lite.siriusXmSource(): StoredSource =
    services.sources.accessible(user, call.request.queryParameters["src"].orEmpty())?.takeIf { it.enabled && it.kind == SourceKind.SIRIUSXM }
        ?: throw ApiError.notFound("That SiriusXM account isn't available")

/** SiriusXM's channels: by category (hundreds at once would be a lot for a small device), or found by name. */
internal suspend fun Lite.siriusXmPage() {
    val source = siriusXmSource()
    val client = services.registry.siriusXm(source) ?: throw ApiError.notFound("That SiriusXM account isn't available")
    val channels = SourceSetup.service("SiriusXM") { client.channels() }
    val category = call.request.queryParameters["cat"]
    val query = call.request.queryParameters["q"]?.trim().orEmpty()
    val shown = when {
        query.isNotEmpty() -> channels.filter { c -> query.split(' ').filter { it.isNotBlank() }.all { w -> "${c.name} ${c.number ?: ""} ${c.category}".contains(w, ignoreCase = true) } }
        category != null -> channels.filter { (it.category.ifBlank { "Other" }) == category }
        else -> null
    }
    page(category ?: "SiriusXM") {
        h1(category ?: "SiriusXM")
        el("p", "class" to "note hls-note", "hidden" to true) {
            text("This browser can't play SiriusXM's live channels (they're HLS streams, which Safari plays by itself). Pandora stations, radio and your own stations still play.")
        }
        el("form", "action" to "/lite/radio/sxm", "method" to "get", "class" to "search", "role" to "search") {
            hidden("src", source.id)
            empty("input", "type" to "search", "name" to "q", "value" to query, "placeholder" to "Channel name, number or genre", "aria-label" to "Find a channel")
            el("button", "class" to "btn", "type" to "submit") { text("Find") }
        }
        if (shown == null) {
            el("ul", "class" to "menu") {
                channels.filter { !it.locked }.groupBy { it.category.ifBlank { "Other" } }.forEach { (name, list) ->
                    el("li") { a(url("/lite/radio/sxm", "src" to source.id, "cat" to name), "$name (${list.size})") }
                }
            }
        } else {
            val playable = shown.filter { !it.locked }
            if (playable.isEmpty()) notice("No channels match.")
            tracks(
                playable.map { art.outgoing(ApiTrack.of(it.toTrack(), source.id)) }, single = true,
                sub = { t -> playable.firstOrNull { "channel:${it.type}:${it.id}" == t.sourceId }?.number?.let { "Ch $it" } ?: "SiriusXM" },
            )
            val locked = shown.count { it.locked }
            if (locked > 0) el("p", "class" to "muted") { text(if (locked == 1) "1 more isn't in your SiriusXM plan." else "$locked more aren't in your SiriusXM plan.") }
        }
    }
}

/** Pandora artist stations through SiriusXM: find an artist, start their station. */
internal suspend fun Lite.pandoraPage() {
    val source = siriusXmSource()
    val query = call.request.queryParameters["q"]?.trim().orEmpty().take(200)
    val artists = if (query.isEmpty()) emptyList() else {
        val client = services.registry.siriusXm(source) ?: throw ApiError.notFound("That SiriusXM account isn't available")
        SourceSetup.service("SiriusXM") { client.searchArtists(query) }
    }
    page("Pandora stations") {
        h1("Pandora stations")
        el("p", "class" to "muted") { text("Music by and like one artist, through your SiriusXM account. Thumbs up and down shape what plays.") }
        pandoraForm(this, source, query)
        if (query.isNotEmpty() && artists.isEmpty()) notice("No stations found. Try the artist's full name.")
        el("ul", "class" to "rows") {
            for (artist in artists) el("li") {
                post("/lite/radio/pandora/start", "row") {
                    hidden("src", source.id)
                    hidden("id", artist.id)
                    hidden("name", artist.name)
                    artist.stationId?.let { hidden("station", it) }
                    hidden("type", artist.entityType)
                    art.wrap(artist.artworkUrl)?.let { hidden("art", it) }
                    el("button", "type" to "submit") {
                        cover(art.wrap(artist.artworkUrl), cls = "cv round")
                        el("span", "class" to "tx") {
                            el("b") { text(artist.name) }
                            el("small") { text("Start the station") }
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun Lite.startPandora() {
    val params = form()
    val source = services.sources.accessible(user, params["src"].orEmpty())?.takeIf { it.enabled && it.kind == SourceKind.SIRIUSXM }
        ?: throw ApiError.notFound("That SiriusXM account isn't available")
    val client = services.registry.siriusXm(source) ?: throw ApiError.notFound("That SiriusXM account isn't available")
    val artist = SxmArtist(
        id = params["id"]?.take(200) ?: throw ApiError.badRequest("Which artist?"),
        name = params["name"].orEmpty().take(200),
        artworkUrl = art.unwrap(params["art"]),
        stationId = params["station"]?.take(200),
        entityType = params["type"]?.take(40) ?: "talent",
    )
    val station = SourceSetup.service("SiriusXM") { client.artistStation(artist) }
    seeOther(url("/lite/radio/pandora/station", "src" to source.id, "id" to station.id, "name" to station.name))
}

/** A Pandora station's first songs, to play; the script asks SiriusXM for more as they play. */
internal suspend fun Lite.pandoraStationPage() {
    val source = siriusXmSource()
    val client = services.registry.siriusXm(source) ?: throw ApiError.notFound("That SiriusXM account isn't available")
    val id = call.request.queryParameters["id"]?.take(200) ?: throw ApiError.badRequest("Which station?")
    val name = call.request.queryParameters["name"]?.take(200)?.ifBlank { null } ?: "Pandora station"
    val station = art.outgoing(ApiTrack.of(SxmStation(id, name).toTrack(), source.id))
    val tracks = SourceSetup.service("SiriusXM") { client.stationTracks(id, false) }.map { art.outgoing(ApiTrack.of(it.toTrack(name), source.id)) }
    val favorite = favoriteState(station)
    page(name) {
        h1(name)
        doneNotice(doneMessage())
        el("p", "class" to "muted") { text("A Pandora station on SiriusXM. More songs come as these play.") }
        if (tracks.isEmpty()) notice("SiriusXM sent nothing for this station. Try again in a moment.")
        playAll(tracks.firstOrNull())
        tracks(tracks)
        favoriteForm(this, station, favorite, call.request.local.uri)
    }
}

/** Radio stations: popular where the browser is, found by name, or near (where the script asks for it). */
internal suspend fun Lite.stationsPage() {
    val source = radioSource(services.sources.activeFor(user)) ?: throw ApiError.notFound("Radio isn't available")
    val params = call.request.queryParameters
    val query = params["q"]?.trim().orEmpty().take(200)
    val lat = params["lat"]?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lon = params["lon"]?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val country = call.request.header(HttpHeaders.AcceptLanguage)?.split(',')?.firstOrNull()?.substringBefore(';')
        ?.split('-')?.getOrNull(1)?.uppercase()?.takeIf { it.length == 2 }
    val (title, stations) = if (source.kind == SourceKind.TUNER) {
        "Your tuner's stations" to services.tunerRadio.own(source, query.ifEmpty { null })
    } else {
        val radio = services.registry.radio
        val (title, found) = when {
            lat != null && lon != null -> "Near you" to SourceSetup.service("Radio Browser") { radio.nearby(lat, lon, null, null) }
            query.isNotEmpty() -> "“$query”" to SourceSetup.service("Radio Browser") { radio.search(query, null) }
            else -> (if (country != null) "Popular in $country" else "Popular") to SourceSetup.service("Radio Browser") { radio.search("", country) }
        }
        title to services.tunerRadio.merge(user, found, source.id, query.ifEmpty { null })
    }
    page("Radio stations") {
        h1("Radio stations")
        stationForm(this, query)
        h2(title)
        if (stations.isEmpty()) notice("No stations found.")
        tracks(stations.take(60).map(art::outgoing), single = true, sub = { listOfNotNull(it.artist.takeIf { a -> a.isNotBlank() }, it.album).joinToString(" · ") })
        if (source.kind == SourceKind.RADIO) el("p", "class" to "muted") { text("Stations from Radio Browser, a free, open directory.") }
    }
}
