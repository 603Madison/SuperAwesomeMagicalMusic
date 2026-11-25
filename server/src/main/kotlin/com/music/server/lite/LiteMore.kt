package com.music.server.lite

import com.music.server.ApiError
import com.music.server.connect.Command
import com.music.server.playlists.WrappedShare
import com.music.server.playlists.wrappedFor
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---- History, Recently heard, Wrapped and devices ----

/** When, as a person would say it: "5 min ago", "Yesterday 21:40", "3 Mar". */
internal fun Lite.whenText(at: Long): String {
    val now = services.clock()
    val minutes = (now - at) / 60_000
    if (minutes < 1) return "Just now"
    if (minutes < 60) return "$minutes min ago"
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (time.toLocalDate()) {
        today -> "Today $clock"
        today.minusDays(1) -> "Yesterday $clock"
        else -> time.format(DateTimeFormatter.ofPattern(if (time.year == today.year) "d MMM" else "d MMM yyyy", Locale.ENGLISH))
    }
}

internal suspend fun Lite.historyPage() {
    val before = call.request.queryParameters["before"]?.toLongOrNull()
    val listens = services.history.list(user.id, PAGE, before).map { it.copy(track = art.outgoing(it.track)) }
    page("History") {
        h1("History")
        if (listens.isEmpty()) notice(if (before == null) "Nothing yet: what you play shows up here." else "That's everything.")
        tracks(listens.map { it.track }, sub = { t ->
            val listen = listens.first { it.track === t }
            listOfNotNull(t.artist, listen.station?.let { "on $it" }, whenText(listen.playedAt)).joinToString(" · ")
        })
        if (listens.size == PAGE) el("p") { a(url("/lite/history", "before" to listens.last().playedAt), "Older", "btn") }
    }
}

private const val PAGE = 50

/** What Listen recognised lately (on any of the person's devices). */
internal suspend fun Lite.heardPage() {
    val heard = services.listen.heard(user)
    page("Recently heard") {
        h1("Recently heard")
        el("p", "class" to "muted") { text("Songs Listen recognised for you, on any of your devices. Listening from here is in the full web player and the app.") }
        if (heard.isEmpty()) notice("Nothing yet.")
        val songs = heard.filter { it.song != null }
        tracks(songs.map { art.outgoing(it.song!!).copy(artworkUrl = art.wrap(it.artworkUrl) ?: art.outgoing(it.song).artworkUrl) }, single = true)
        val others = heard.filter { it.song == null }
        if (others.isNotEmpty()) {
            h2("Not in your sources")
            el("ul", "class" to "rows plain") {
                for (h in others) el("li") {
                    cover(art.wrap(h.artworkUrl))
                    el("span", "class" to "tx") {
                        el("b") { text(h.title) }
                        el("small") { text("${h.artist} · ${whenText(h.at)}") }
                    }
                }
            }
        }
    }
}

/** Wrapped, as plain text: the year in numbers and lists. */
internal suspend fun Lite.wrappedPage() {
    val wrapped = wrappedFor(services, user, zone, call.request.queryParameters["year"]?.toIntOrNull())
    page("Wrapped ${wrapped.year}") {
        h1("Your ${wrapped.year}${if (wrapped.soFar) " so far" else ""}")
        if (wrapped.years.size > 1) {
            el("p") {
                text("Other years: ")
                wrapped.years.filter { it != wrapped.year }.forEach { year ->
                    a(url("/lite/wrapped", "year" to year), year.toString())
                    text(" ")
                }
            }
        }
        if (wrapped.listens == 0) {
            notice("Nothing played this year yet.")
            return@page
        }
        el("ul", "class" to "facts") {
            fact("${count(wrapped.minutes, "minute")} of music")
            fact("${count(wrapped.listens, "play")} of ${count(wrapped.songs, "song")} by ${count(wrapped.artists, "artist")}")
            fact("Music on ${count(wrapped.days, "day")}; ${wrapped.longestStreak} in a row at most")
            wrapped.biggestDay?.let { fact("The biggest day: ${dayText(it.date)}, with ${count(it.minutes, "minute")}") }
            wrapped.onRepeat?.let { fact("On repeat: ${it.track.title} by ${it.track.artist}, ${count(it.plays, "time")} on ${dayText(it.date)}") }
            if (wrapped.newArtists > 0) fact("${count(wrapped.newArtists, "artist")} new to you")
        }
        h2(wrapped.personality.name)
        p(wrapped.personality.description)
        if (wrapped.topSongs.isNotEmpty()) {
            h2("Top songs")
            val top = wrapped.topSongs.take(10)
            tracks(top.map { it.track }, numbered = true, sub = { t -> top.first { it.track === t }.let { "${t.artist} · ${count(it.plays, "play")}" } })
        }
        if (wrapped.topArtists.isNotEmpty()) {
            h2("Top artists")
            el("ol", "class" to "rows num") {
                for (a in wrapped.topArtists) el("li") {
                    el("a", "href" to url("/lite/artist", "name" to a.name)) {
                        cover(a.artworkUrl, cls = "cv round")
                        el("span", "class" to "tx") {
                            el("b") { text(a.name) }
                            el("small") { text(count(a.minutes, "minute")) }
                        }
                    }
                }
            }
        }
        if (wrapped.topAlbums.isNotEmpty()) {
            h2("Top albums")
            el("ol", "class" to "rows num") {
                for (a in wrapped.topAlbums) el("li") {
                    el("a", "href" to url("/lite/album", "title" to a.title, "artist" to a.artist)) {
                        cover(a.artworkUrl)
                        el("span", "class" to "tx") {
                            el("b") { text(a.title) }
                            el("small") { text("${a.artist} · ${count(a.minutes, "minute")}") }
                        }
                    }
                }
            }
        }
        shares(this, "Top stations", wrapped.topStations)
        shares(this, "Where it came from", wrapped.sources)
        if (wrapped.discoveries.isNotEmpty()) {
            h2("New to you")
            el("p") { text(wrapped.discoveries.joinToString(", ") { it.name }) }
        }
        val peak = wrapped.hours.indices.maxByOrNull { wrapped.hours[it] }
        if (peak != null && wrapped.hours[peak] > 0) p("Your favourite hour: ${peak}:00 to ${(peak + 1) % 24}:00.")
    }
}

private fun Html.fact(text: String) = el("li") { text(text) }

/** "1 song", "1,204 songs". */
internal fun count(n: Number, word: String) = "${"%,d".format(n.toLong())} $word${if (n.toLong() == 1L) "" else "s"}"

private fun Lite.shares(h: Html, title: String, list: List<WrappedShare>) = with(h) {
    if (list.isEmpty()) return@with
    h2(title)
    el("ul", "class" to "facts") {
        for (s in list.take(10)) fact("${s.name}: ${count(s.minutes, "minute")}")
    }
}

private fun dayText(date: String): String =
    runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)) }.getOrDefault(date)

/**
 * The person's devices, from the devices hub: what each plays, buttons to control the one
 * playing, and to move the music to another (or to this browser, which the script connects).
 */
internal suspend fun Lite.devicesPage() {
    val devices = services.connect.devices(user.id)
    val me = deviceId
    val active = devices.firstOrNull { it.active }
    page("Your devices") {
        h1("Your devices")
        doneNotice(doneMessage())
        if (devices.isEmpty()) notice("None of your devices are open right now.")
        el("ul", "class" to "devices") {
            for (d in devices) el("li", "class" to if (d.active) "on" else null) {
                el("b") { text(d.name + if (d.id == me) " (this browser)" else "") }
                val kind = d.detail ?: when (d.kind) { "phone" -> "Phone"; "speaker" -> "Speaker"; else -> "Browser" }
                el("small") { text(kind) }
                d.state?.track?.let { t ->
                    el("p") {
                        text(if (d.state.playing) "Playing " else "Paused on ")
                        el("b") { text(t.title) }
                        text(" · ${t.artist}")
                    }
                }
                el("div", "class" to "acts") {
                    if (d.id != me && d.state?.track != null) {
                        for ((action, label) in listOf("previous" to "Previous", if (d.state.playing) "pause" to "Pause" else "play" to "Play", "next" to "Next")) {
                            post("/lite/devices/command", "inline") {
                                hidden("target", d.id)
                                hidden("action", action)
                                el("button", "class" to "btn", "type" to "submit") { text(label) }
                            }
                        }
                    }
                    if (active != null && active.id != d.id && active.state?.track != null) {
                        post("/lite/devices/transfer", "inline") {
                            hidden("to", d.id)
                            el("button", "class" to "btn", "type" to "submit") { text(if (d.id == me) "Play here" else "Play on this") }
                        }
                    }
                }
            }
        }
        if (me == null || devices.none { it.id == me }) {
            el("p", "class" to "muted") { text("With JavaScript on, this browser is one of your devices too, so music can move here.") }
        }
        el("p") { a("/lite/devices", "Refresh") }
    }
}

internal suspend fun Lite.deviceCommand() {
    val params = form()
    val action = params["action"]?.takeIf { it in setOf("play", "pause", "next", "previous") } ?: throw ApiError.badRequest("Play, pause, next or previous?")
    val target = params["target"] ?: throw ApiError.badRequest("Which device?")
    services.connect.command(user.id, target, Command(action, from = deviceId))
    seeOther("/lite/devices?done=sent")
}

internal suspend fun Lite.deviceTransfer() {
    val to = form()["to"] ?: throw ApiError.badRequest("Which device?")
    services.connect.transfer(user.id, to)
    seeOther("/lite/devices?done=moved")
}
