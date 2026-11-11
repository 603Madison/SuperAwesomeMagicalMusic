package com.music.server

import com.music.server.playlists.WrappedReport
import io.ktor.client.request.get
import io.ktor.client.request.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Wrapped: a year of listening, from the history the apps send (live radio included). */
class WrappedTest {
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun song(title: String, artist: String, album: String, source: String = "JELLYFIN", id: String = title) =
        """{"source":"$source","sourceId":"$id","title":"$title","artist":"$artist","album":"$album","durationMs":200000,"artworkUrl":"https://img.example.com/$id.jpg"}"""

    private fun listen(track: String, iso: String, ms: Long = 200_000, client: String = "web", station: String? = null) =
        """{"track":$track,"playedAt":${at(iso)},"listenedMs":$ms,"client":"$client"${station?.let { ""","station":"$it"""" } ?: ""}}"""

    @Test
    fun `a year of listening, wrapped`() = serverTest(testServices(clock = { at("2026-06-15T12:00:00Z") })) { services ->
        services.users.create("maddie", password = "correct horse")
        val user = browser()
        user.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }

        val neon = song("Neon Tidepool", "Velvet Satellites", "Low Orbit")
        val channel = """{"source":"SIRIUSXM","sourceId":"channel:xtra:altap","title":"Alt Nation Advanced Placement","artist":"SiriusXM"}"""
        val listens = listOf(
            // Last year: an artist that's no discovery this year.
            listen(song("Old Favourite", "The Regulars", "Then"), "2025-05-01T18:00:00Z"),
            // Three days in a row, then more.
            listen(neon, "2026-01-01T15:00:00Z"),
            listen(neon, "2026-01-02T15:00:00Z", client = "android"),
            listen(neon, "2026-01-03T15:00:00Z"),
            listen(neon, "2026-03-10T23:00:00Z"),
            // The same song from another service, featuring someone: still the same song.
            listen(song("Neon Tidepool (feat. Lumen)", "Velvet Satellites, Lumen", "Low Orbit", "YOUTUBE_MUSIC", "yt1"), "2026-03-10T23:05:00Z"),
            listen(song("Glasshouse Summer", "The Marigold Static", "Glasshouse"), "2026-04-02T09:00:00Z"),
            listen(song("Glasshouse Summer", "The Marigold Static", "Glasshouse"), "2026-04-02T09:05:00Z"),
            listen(song("Old Favourite", "The Regulars", "Then"), "2026-04-03T09:00:00Z"),
            // Live radio: a song heard on a channel, and time on it without a known song.
            listen(song("Fool's Gold", "Buffalo Traffic Jam", "Gold", "YOUTUBE_MUSIC", "yt2"), "2026-05-01T20:00:00Z", ms = 240_000, station = "Alt Nation Advanced Placement"),
            listen(channel, "2026-05-01T20:04:00Z", ms = 600_000, station = "Alt Nation Advanced Placement"),
        )
        user.post("/api/history") { csrf(); json("""{"listens":[${listens.joinToString(",")}]}""") }

        // The station is kept with the listen.
        val history = user.get("/api/history").json().jsonArray.map { it.jsonObject }
        assertEquals("Alt Nation Advanced Placement", history.first { it["track"]!!.jsonObject.str("title") == "Fool's Gold" }.str("station"))

        val wrapped = user.get("/api/wrapped?tz=UTC").obj()
        assertEquals(2026, wrapped.int("year"))
        assertEquals(listOf(2026, 2025), wrapped["years"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals("true", wrapped.str("soFar"))
        assertEquals(10, wrapped.int("listens"))
        // 8 songs of 200 s, 240 s heard on the radio and 600 s of the channel: 2,440 s.
        assertEquals(40L, wrapped["minutes"]!!.jsonPrimitive.long)
        assertEquals(4, wrapped.int("songs"))
        assertEquals(4, wrapped.int("artists"))

        val top = wrapped["topSongs"]!!.jsonArray[0].jsonObject
        assertEquals(5, top.int("plays"))
        assertTrue(top["track"]!!.jsonObject.str("title").startsWith("Neon Tidepool"))
        // Artwork comes through the server.
        assertTrue(top["track"]!!.jsonObject.str("artworkUrl").startsWith("/api/art"))
        val artist = wrapped["topArtists"]!!.jsonArray[0].jsonObject
        assertEquals("Velvet Satellites", artist.str("name"))
        assertEquals("Neon Tidepool", artist.str("topSong").substringBefore(" ("))
        assertEquals("Low Orbit", wrapped["topAlbums"]!!.jsonArray[0].jsonObject.str("title"))

        val station = wrapped["topStations"]!!.jsonArray.single().jsonObject
        assertEquals("Alt Nation Advanced Placement", station.str("name"))
        assertEquals(14L, station["minutes"]!!.jsonPrimitive.long)
        assertEquals("JELLYFIN", wrapped["sources"]!!.jsonArray[0].jsonObject.str("name"))
        assertEquals(setOf("web", "android"), wrapped["devices"]!!.jsonArray.map { it.jsonObject.str("name") }.toSet())

        assertEquals(3, wrapped.int("longestStreak"))
        assertEquals("2026-05-01", wrapped["biggestDay"]!!.jsonObject.str("date"))
        assertEquals("Neon Tidepool", wrapped["firstSong"]!!.jsonObject["track"]!!.jsonObject.str("title"))
        // Discoveries: new this year, not The Regulars from last year.
        val found = wrapped["discoveries"]!!.jsonArray.map { it.jsonObject.str("name") }
        assertEquals(3, wrapped.int("newArtists"))
        assertTrue(found.toString(), "Velvet Satellites" in found && "The Regulars" !in found)
        assertTrue(wrapped["personality"]!!.jsonObject.str("name").isNotBlank())

        // Hours in the person's time zone: 23:00 UTC is 19:00 in New York.
        val newYork = user.get("/api/wrapped?tz=America/New_York").obj()
        assertTrue(newYork["hours"]!!.jsonArray[19].jsonPrimitive.long > 0)
        assertEquals(0L, newYork["hours"]!!.jsonArray[23].jsonPrimitive.long)

        // Last year: just the one song, and nothing to discover against.
        val last = user.get("/api/wrapped?year=2025&tz=UTC").obj()
        assertEquals(1, last.int("listens"))
        assertEquals("false", last.str("soFar"))
        assertEquals(0, last.int("newArtists"))

        // Someone with no listening yet gets an empty year, not an error.
        services.users.create("sam", password = "correct horse")
        val sam = browser()
        sam.post("/api/auth/login") { json("""{"username":"sam","password":"correct horse"}""") }
        val empty = sam.get("/api/wrapped").obj()
        assertEquals(0, empty.int("listens"))
        assertFalse(empty["topSongs"]!!.jsonArray.any())
    }

    @Test
    fun `the main artist of a song`() {
        assertEquals("Velvet Satellites", WrappedReport.mainArtist("Velvet Satellites, Lumen"))
        assertEquals("Velvet Satellites", WrappedReport.mainArtist("Velvet Satellites feat. Lumen"))
        assertEquals("Simon & Garfunkel", WrappedReport.mainArtist("Simon & Garfunkel"))
    }

    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int
}
