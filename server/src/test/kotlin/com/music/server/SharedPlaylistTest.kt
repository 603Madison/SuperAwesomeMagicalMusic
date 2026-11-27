package com.music.server

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPlaylistTest {
    private fun song(n: Int) = """{"source":"YOUTUBE_MUSIC","sourceId":"id$n","title":"Song $n","artist":"Artist","durationMs":1000}"""

    private suspend fun ApplicationTestBuilder.signedIn(username: String, password: String): HttpClient = browser().also {
        assertEquals(HttpStatusCode.OK, it.post("/api/auth/login") { json("""{"username":"$username","password":"$password"}""") }.status)
    }

    private fun titles(playlist: JsonObject) = playlist["entries"]!!.jsonArray.map { it.jsonObject["track"]!!.jsonObject.str("title") }

    @Test
    fun `a shared playlist is everyone's to add to, and only its owner's to rename, delete or share`() = serverTest { services ->
        services.users.create("maddie", password = "correct horse", displayName = "Maddie")
        services.users.create("sam", password = "correct horse", displayName = "Sam")
        services.users.create("alex", password = "correct horse", displayName = "Alex")
        val maddie = signedIn("maddie", "correct horse")
        val sam = signedIn("sam", "correct horse")
        val alex = signedIn("alex", "correct horse")
        val samId = services.users.byUsername("sam")!!.id

        // The people on the server to share with: everyone but yourself.
        val people = maddie.get("/api/people").json().jsonArray.map { it.jsonObject.str("username") }
        assertEquals(setOf("admin", "sam", "alex"), people.toSet())

        val id = maddie.post("/api/playlists") { csrf(); json("""{"name":"Road trip","tracks":[${song(1)}]}""") }.obj()["summary"]!!.jsonObject.str("id")
        // Not shared yet: no one else sees it.
        assertEquals(HttpStatusCode.NotFound, sam.get("/api/playlists/$id").status)
        assertTrue(sam.get("/api/playlists").json().jsonArray.isEmpty())

        // Shared with Sam, by username.
        val shared = maddie.post("/api/playlists/$id/members") { csrf(); json("""{"username":"sam"}""") }.obj()
        assertEquals(listOf("Sam"), shared["members"]!!.jsonArray.map { it.jsonObject.str("name") })
        // Someone who isn't there can't be shared with.
        assertEquals(HttpStatusCode.NotFound, maddie.post("/api/playlists/$id/members") { csrf(); json("""{"username":"nobody"}""") }.status)

        // Sam sees it with his playlists, as Maddie's.
        val listed = sam.get("/api/playlists").json().jsonArray.single().jsonObject
        assertEquals("Road trip", listed.str("name"))
        assertEquals("false", listed.str("mine"))
        assertEquals("Maddie", listed["owner"]!!.jsonObject.str("name"))

        // Both of them add to it, and each song says who added it.
        val afterSam = sam.post("/api/playlists/$id/tracks") { csrf(); json("""{"tracks":[${song(2)}]}""") }.obj()
        assertEquals(listOf("Song 1", "Song 2"), titles(afterSam))
        val afterMaddie = maddie.post("/api/playlists/$id/tracks") { csrf(); json("""{"tracks":[${song(3)}]}""") }.obj()
        val adders = afterMaddie["entries"]!!.jsonArray.map { it.jsonObject["addedBy"]!!.jsonObject.str("name") }
        assertEquals(listOf("Maddie", "Sam", "Maddie"), adders)

        // Sam reorders and removes songs too.
        val entries = afterMaddie["entries"]!!.jsonArray.map { it.jsonObject.str("entryId") }
        val revision = afterMaddie["summary"]!!.jsonObject.str("revision")
        val reordered = sam.put("/api/playlists/$id/order") { csrf(); json("""{"revision":$revision,"entryIds":["${entries[2]}","${entries[0]}","${entries[1]}"]}""") }.obj()
        assertEquals(listOf("Song 3", "Song 1", "Song 2"), titles(reordered))
        val removed = sam.post("/api/playlists/$id/remove") { csrf(); json("""{"entryIds":["${entries[0]}"]}""") }.obj()
        assertEquals(listOf("Song 3", "Song 2"), titles(removed))
        assertEquals(listOf("Song 3", "Song 2"), titles(maddie.get("/api/playlists/$id").obj()))

        // Only Maddie renames, deletes or shares it.
        assertEquals(HttpStatusCode.Forbidden, sam.patch("/api/playlists/$id") { csrf(); json("""{"name":"Sam's now"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, sam.delete("/api/playlists/$id") { csrf() }.status)
        assertEquals(HttpStatusCode.Forbidden, sam.post("/api/playlists/$id/members") { csrf(); json("""{"username":"alex"}""") }.status)
        assertEquals(HttpStatusCode.OK, maddie.patch("/api/playlists/$id") { csrf(); json("""{"name":"Summer road trip"}""") }.status)
        // Someone it isn't shared with can't see or touch it.
        assertEquals(HttpStatusCode.NotFound, alex.get("/api/playlists/$id").status)
        assertEquals(HttpStatusCode.NotFound, alex.post("/api/playlists/$id/tracks") { csrf(); json("""{"tracks":[${song(9)}]}""") }.status)

        // Each person's "last played" is their own.
        sam.post("/api/playlists/$id/played") { csrf() }
        assertTrue(sam.get("/api/playlists/$id").obj()["summary"]!!.jsonObject["playedAt"] != null)
        assertNull(maddie.get("/api/playlists/$id").obj()["summary"]!!.jsonObject["playedAt"])

        // Sam leaves it: it's gone from his playlists, and his songs stay in it.
        assertEquals(HttpStatusCode.OK, sam.delete("/api/playlists/$id/members/$samId") { csrf() }.status)
        assertTrue(sam.get("/api/playlists").json().jsonArray.isEmpty())
        assertEquals(listOf("Song 3", "Song 2"), titles(maddie.get("/api/playlists/$id").obj()))
        assertTrue(maddie.get("/api/playlists/$id").obj()["summary"]!!.jsonObject["members"]!!.jsonArray.isEmpty())

        // Shared again, then Maddie takes Sam off, and finally deletes it.
        maddie.post("/api/playlists/$id/members") { csrf(); json("""{"userId":"$samId"}""") }
        assertEquals(1, sam.get("/api/playlists").json().jsonArray.size)
        assertEquals(HttpStatusCode.OK, maddie.delete("/api/playlists/$id/members/$samId") { csrf() }.status)
        assertTrue(sam.get("/api/playlists").json().jsonArray.isEmpty())
        assertEquals(HttpStatusCode.OK, maddie.delete("/api/playlists/$id") { csrf() }.status)
        assertFalse(maddie.get("/api/playlists").json().jsonArray.any { it.jsonObject.str("id") == id })
    }
}
