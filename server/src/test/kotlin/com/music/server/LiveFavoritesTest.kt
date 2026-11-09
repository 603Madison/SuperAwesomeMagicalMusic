package com.music.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Favourite channels and stations: each person's own, and only live ones. */
class LiveFavoritesTest {
    @Test
    fun `a station can be made a favourite and taken off again`() = serverTest { services ->
        services.users.create("maddie", password = "correct horse")
        services.users.create("sam", password = "correct horse")
        val radio = services.sources.allGlobal().single { it.kind == com.music.server.sources.SourceKind.RADIO }.id
        val maddie = browser()
        maddie.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }
        val station = """{"source":"RADIO","sourceId":"st1","title":"Lake FM","artist":"Burlington","artworkUrl":"https://example.com/logo.png","via":"$radio"}"""

        val added = maddie.put("/api/live/favorites") { csrf(); json("""{"track":$station,"favorite":true}""") }.json().jsonArray
        assertEquals("Lake FM", added.single().jsonObject.str("title"))
        // Its logo comes back through the server, like all artwork.
        assertTrue(added.single().jsonObject.str("artworkUrl").startsWith("/api/art?u="))
        // Sending it back as it came (with the server's artwork address) is the same favourite.
        val again = maddie.put("/api/live/favorites") { csrf(); json("""{"track":${added.single()},"favorite":true}""") }.json().jsonArray
        assertEquals(1, again.size)

        // Someone else's are their own.
        val sam = browser()
        sam.post("/api/auth/login") { json("""{"username":"sam","password":"correct horse"}""") }
        assertTrue(sam.get("/api/live/favorites").json().jsonArray.isEmpty())

        // Songs aren't stations.
        val song = maddie.put("/api/live/favorites") {
            csrf(); json("""{"track":{"source":"JELLYFIN","sourceId":"s1","title":"A song","artist":"Someone","via":"$radio"},"favorite":true}""")
        }
        assertEquals(HttpStatusCode.BadRequest, song.status)

        // A Pandora station can be one (it's played by starting the station); one of its songs can't.
        val pandora = """{"source":"SIRIUSXM","sourceId":"channel:artist-station:abc123","title":"Phoebe Bridgers","artist":"Pandora station · SiriusXM","via":"$radio"}"""
        val withStation = maddie.put("/api/live/favorites") { csrf(); json("""{"track":$pandora,"favorite":true}""") }.json().jsonArray
        assertEquals(listOf("Phoebe Bridgers", "Lake FM"), withStation.map { it.jsonObject.str("title") })
        val stationSong = maddie.put("/api/live/favorites") {
            csrf(); json("""{"track":{"source":"SIRIUSXM","sourceId":"stationtrack:abc123:t9","title":"Motion Sickness","artist":"Phoebe Bridgers","via":"$radio"},"favorite":true}""")
        }
        assertEquals(HttpStatusCode.BadRequest, stationSong.status)
        maddie.put("/api/live/favorites") { csrf(); json("""{"track":$pandora,"favorite":false}""") }

        // The top ones, for the sidebar: favourites played lately first, then others played lately.
        val other = """{"source":"RADIO","sourceId":"st2","title":"WFMU","artist":"Jersey City","via":"$radio"}"""
        maddie.post("/api/live/played") { csrf(); json("""{"track":$other}""") }
        maddie.post("/api/live/played") { csrf(); json("""{"track":$station}""") }
        assertEquals(listOf("Lake FM", "WFMU"), maddie.get("/api/live/top?limit=4").json().jsonArray.map { it.jsonObject.str("title") })
        assertEquals(listOf("Lake FM"), maddie.get("/api/live/top?limit=1").json().jsonArray.map { it.jsonObject.str("title") })
        assertEquals(HttpStatusCode.BadRequest, maddie.post("/api/live/played") { csrf(); json("""{"track":{"source":"JELLYFIN","sourceId":"s1","title":"A song","artist":"Someone","via":"$radio"}}""") }.status)

        val removed = maddie.put("/api/live/favorites") { csrf(); json("""{"track":$station,"favorite":false}""") }.json().jsonArray
        assertTrue(removed.isEmpty())
        assertTrue(maddie.get("/api/live/favorites").json().jsonArray.isEmpty())
    }
}
