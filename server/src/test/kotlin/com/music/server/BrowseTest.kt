package com.music.server

import com.music.server.sources.SourceKind
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Searching for artists and albums as well as songs, and opening them, across a person's sources. */
class BrowseTest {
    private val song = """{"Id":"s1","Name":"Paranoid Android","Artists":["Radiohead"],"Album":"OK Computer","AlbumId":"al1","RunTimeTicks":3870000000}"""
    private val song2 = """{"Id":"s2","Name":"Airbag","Artists":["Radiohead"],"Album":"OK Computer","AlbumId":"al1","RunTimeTicks":2870000000}"""
    private val album = """{"Id":"al1","Name":"OK Computer","AlbumArtist":"Radiohead","ProductionYear":1997,"ChildCount":12,"ImageTags":{"Primary":"t"}}"""

    /** A song that turns up in the library later (Lidarr got it, say). */
    @Volatile private var arrived = false
    private val late = """{"Id":"s9","Name":"Nothing At All","Artists":["Nobody"],"Album":"Later","AlbumId":"al9","RunTimeTicks":2000000000}"""

    /** A pretend Jellyfin holding one album by one artist. */
    private val jellyfin = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val term = url.queryParameter("searchTerm")?.lowercase()
                val items = when {
                    url.encodedPath == "/Artists" -> if (term != null && "radiohead".startsWith(term.take(5))) listOf("""{"Id":"ar1","Name":"Radiohead"}""") else emptyList()
                    url.queryParameter("includeItemTypes") == "MusicAlbum" -> when {
                        url.queryParameter("ids") == "al1" || url.queryParameter("albumArtistIds") == "ar1" -> listOf(album)
                        term != null && ("ok computer".contains(term) || term.contains("ok computer")) -> listOf(album)
                        else -> emptyList()
                    }
                    url.queryParameter("parentId") == "al1" -> listOf(song2, song)
                    url.queryParameter("artistIds") == "ar1" -> listOf(song, song2)
                    term != null && term.length > 3 && "paranoid android".contains(term) -> listOf(song)
                    arrived && term != null && term.length > 3 && "nothing at all".contains(term) -> listOf(late)
                    else -> emptyList()
                }
                return MockResponse().setBody("""{"Items":[${items.joinToString(",")}]}""")
            }
        }
        start()
    }

    @After
    fun stop() = jellyfin.shutdown()

    private suspend fun libraryOnly(services: Services) {
        services.sources.create(
            ownerId = null, kind = SourceKind.JELLYFIN, name = "Home",
            settings = buildJsonObject {
                put("serverUrl", jellyfin.url("/").toString().trimEnd('/'))
                put("username", "u")
                put("userId", "u1")
            },
            secrets = buildJsonObject { put("accessToken", "t") },
        )
        services.sources.allGlobal().filter { it.kind != SourceKind.JELLYFIN }.forEach { services.sources.delete(it.id) }
    }

    @Test
    fun `an artist's name finds the artist, whose page has their albums and songs`() = serverTest { services ->
        libraryOnly(services)
        services.users.create("maddie", password = "correct horse")
        val user = browser()
        user.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }

        val found = user.get("/api/search?q=radiohead").obj()
        val top = found["top"]!!.jsonObject
        assertEquals("artist", top.str("kind"))
        assertEquals("Radiohead", top["artist"]!!.jsonObject.str("name"))

        val page = user.get("/api/artist?id=${top["artist"]!!.jsonObject.str("id")}").obj()
        assertEquals("Radiohead", page["artist"]!!.jsonObject.str("name"))
        assertEquals(listOf("OK Computer"), page["albums"]!!.jsonArray.map { it.jsonObject.str("title") })
        assertEquals(listOf("Paranoid Android", "Airbag"), page["topSongs"]!!.jsonArray.map { it.jsonObject.str("title") })
        // By name too (a song's artist).
        val byName = user.get("/api/artist?name=Radiohead").obj()
        assertEquals("Radiohead", byName["artist"]!!.jsonObject.str("name"))
        assertEquals(HttpStatusCode.NotFound, user.get("/api/artist?name=Nobody").status)
    }

    @Test
    fun `an album's title finds the album, which opens with its songs in order`() = serverTest { services ->
        libraryOnly(services)
        services.users.create("maddie", password = "correct horse")
        val user = browser()
        user.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }

        val found = user.get("/api/search?q=ok+computer").obj()
        val top = found["top"]!!.jsonObject
        assertEquals("album", top.str("kind"))
        val album = top["album"]!!.jsonObject
        assertEquals("Radiohead", album.str("artist"))
        assertEquals("1997", album.str("year"))
        assertTrue(album.str("artworkUrl").startsWith("/api/art?u="))

        val page = user.get("/api/album?id=${album.str("id")}").obj()
        assertEquals(listOf("Airbag", "Paranoid Android"), page["tracks"]!!.jsonArray.map { it.jsonObject.str("title") })
        assertTrue(page["tracks"]!!.jsonArray.all { it.jsonObject.str("via").isNotEmpty() })
        val byTitle = user.get("/api/album?title=${"OK Computer".encodeURLParameter()}&artist=Radiohead").obj()
        assertEquals(2, byTitle["tracks"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.BadRequest, user.get("/api/album?id=not-a-real-id!").status)
    }

    @Test
    fun `a song's title finds the song, and older apps still get each source's songs`() = serverTest { services ->
        libraryOnly(services)
        services.users.create("maddie", password = "correct horse")
        val user = browser()
        user.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }
        val found = user.get("/api/search?q=paranoid+android").obj()
        assertEquals("song", found["top"]!!.jsonObject.str("kind"))
        assertEquals(listOf("Paranoid Android"), found["songs"]!!.jsonArray.map { it.jsonObject.str("title") })
        assertEquals("Paranoid Android", found["groups"]!!.jsonArray.single().jsonObject["tracks"]!!.jsonArray.single().jsonObject.str("title"))
    }

    @Test
    fun `a typed list becomes a playlist of the songs found`() = serverTest { services ->
        libraryOnly(services)
        services.users.create("maddie", password = "correct horse")
        val user = browser()
        user.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }
        val result = user.post("/api/playlists/import") {
            csrf()
            json("""{"text":"1. Radiohead - Paranoid Android\n2. Nobody - Nothing At All","name":"Typed"}""")
        }.obj()
        assertEquals("1", result.str("found"))
        assertEquals(listOf("Nobody – Nothing At All"), result["missing"]!!.jsonArray.map { it.jsonPrimitive().content })
        val playlist = result["playlist"]!!.jsonObject
        assertEquals("Typed", playlist["summary"]!!.jsonObject.str("name"))
        // The song not found is in the playlist anyway, in its place, as a stand-in.
        fun titles(p: kotlinx.serialization.json.JsonObject) = p["entries"]!!.jsonArray.map { it.jsonObject["track"]!!.jsonObject.str("title") }
        fun ids(p: kotlinx.serialization.json.JsonObject) = p["entries"]!!.jsonArray.map { it.jsonObject["track"]!!.jsonObject.str("sourceId") }
        assertEquals(listOf("Paranoid Android", "Nothing At All"), titles(playlist))
        assertTrue(ids(playlist)[1].startsWith("missing:"))
        assertEquals("1", playlist["summary"]!!.jsonObject.str("missing"))
        val id = playlist["summary"]!!.jsonObject.str("id")

        // Looked for again while it's still nowhere: still missing.
        val notYet = user.post("/api/playlists/$id/find-missing") { csrf() }.obj()
        assertEquals("0", notYet.str("found"))
        assertEquals("1", notYet.str("stillMissing"))
        val other = user.post("/api/playlists/import") { csrf(); json("""{"text":"Nobody - Nothing At All","name":"Later"}""") }.obj()
        val otherId = other["playlist"]!!.jsonObject["summary"]!!.jsonObject.str("id")

        // It turns up in the library: the button finds it, in the same place…
        arrived = true
        val again = user.post("/api/playlists/$id/find-missing") { csrf() }.obj()
        assertEquals("1", again.str("found"))
        assertEquals(listOf("s1", "s9"), ids(again["playlist"]!!.jsonObject))
        assertEquals("0", again["playlist"]!!.jsonObject["summary"]!!.jsonObject.str("missing"))
        // …and so does the search every few hours, for the other playlist.
        assertEquals(1, services.missingFinder.lookAgain())
        assertEquals(listOf("s9"), ids(user.get("/api/playlists/$otherId").obj()))

        // Played: the sidebar lists playlists by when they were played.
        assertEquals(null, user.get("/api/playlists").json().jsonArray.first { it.jsonObject.str("id") == id }.jsonObject["playedAt"]?.takeUnless { it is kotlinx.serialization.json.JsonNull })
        user.post("/api/playlists/$id/played") { csrf() }
        assertTrue(user.get("/api/playlists").json().jsonArray.first { it.jsonObject.str("id") == id }.jsonObject.str("playedAt").toLong() > 0)

        val bad = user.post("/api/playlists/import") { csrf(); json("""{"url":"https://example.com/playlist/1"}""") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val qobuz = user.post("/api/playlists/import") { csrf(); json("""{"url":"https://open.qobuz.com/playlist/123"}""") }
        assertEquals(HttpStatusCode.BadRequest, qobuz.status)
        assertTrue(qobuz.obj().str("error").contains("From a list"))
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitive() = this as kotlinx.serialization.json.JsonPrimitive
}
