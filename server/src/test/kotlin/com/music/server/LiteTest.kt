package com.music.server

import com.music.player.model.SourceType
import com.music.server.lite.LiteTracks
import com.music.server.music.ApiTrack
import com.music.server.playlists.Listen
import com.music.server.sources.SourceKind
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The lite web player at /lite: its pages, forms and playing, signed in like the main one. */
class LiteTest {
    /** A pretend Jellyfin with one album of two songs, which it streams. */
    private val jellyfin = liteJellyfin()

    @After
    fun stop() = jellyfin.shutdown()

    private suspend fun ApplicationTestBuilder.signedIn(services: Services): HttpClient {
        libraryOnly(services, jellyfin)
        services.users.create("maddie", "Madison Norton", password = "correct horse")
        val user = liteBrowser()
        val login = user.get("/lite/login")
        assertEquals(HttpStatusCode.OK, login.status)
        val token = csrfIn(login.bodyAsText())
        val signedIn = user.form("/lite/login", parameters {
            append("_csrf", token)
            append("next", "/lite")
            append("username", "maddie")
            append("password", "correct horse")
        })
        assertEquals(HttpStatusCode.OK, signedIn.status)
        assertTrue(signedIn.bodyAsText().contains("Hello, Madison"))
        return user
    }

    @Test
    fun `signed out, every page is the sign-in page, which signs in with a password like the main player`() = serverTest { services ->
        libraryOnly(services, jellyfin)
        services.users.create("maddie", password = "correct horse")
        val visitor = liteBrowser()
        val page = visitor.get("/lite/playlists")
        assertEquals(HttpStatusCode.Unauthorized, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("action=\"/lite/login\""))
        // No script before signing in, and the page asks to come back where it was.
        assertFalse(html.contains("<script"))
        assertTrue(html.contains("value=\"/lite/playlists\""))

        // A wrong password says so; a form without its token (another site's) is refused.
        val wrong = visitor.form("/lite/login", parameters {
            append("_csrf", csrfIn(html)); append("username", "maddie"); append("password", "nope")
        })
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertTrue(wrong.bodyAsText().contains("Wrong username or password"))
        val forged = liteBrowser().form("/lite/login", parameters { append("username", "maddie"); append("password", "correct horse") })
        assertEquals(HttpStatusCode.Forbidden, forged.status)

        // Signed in here, signed in to the main player's API too (one cookie).
        val ok = visitor.form("/lite/login", parameters {
            append("_csrf", csrfIn(html)); append("next", "/lite/playlists"); append("username", "maddie"); append("password", "correct horse")
        })
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("<h1>Playlists</h1>"))
        assertEquals(HttpStatusCode.OK, visitor.get("/api/me").status)
        // Only pages here are gone back to.
        val other = liteBrowser()
        val again = csrfIn(other.get("/lite/login").bodyAsText())
        val away = other.form("/lite/login", parameters {
            append("_csrf", again); append("next", "//evil.example/"); append("username", "maddie"); append("password", "correct horse")
        })
        assertTrue(away.bodyAsText().contains("Hello, maddie"))
    }

    @Test
    fun `signed in on the main player, the lite pages are signed in too, with single sign-on offered`() = serverTest { services ->
        libraryOnly(services, jellyfin)
        services.users.create("maddie", password = "correct horse")
        val user = liteBrowser()
        user.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }
        val home = user.get("/lite")
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.bodyAsText().contains("Hello, maddie"))

        services.settings.update { it.copy(oidc = it.oidc.copy(enabled = true, issuer = "https://auth.example.com/", clientId = "samm", buttonLabel = "Sign in with Authentik")) }
        val login = liteBrowser().get("/lite/login").bodyAsText()
        assertTrue(login.contains("Sign in with Authentik"))
        assertTrue(login.contains("/api/auth/oidc/start?return=%2Flite%2Fwelcome"))
        // Back from the identity provider, the browser is taken on (its cookie then goes with it).
        assertTrue(liteBrowser().get("/lite/welcome").bodyAsText().contains("http-equiv=\"refresh\" content=\"0;url=/lite\""))
    }

    @Test
    fun `pages are small, plain, escaped and secured like the main player's`() = serverTest { services ->
        val user = signedIn(services)
        services.playlists.create(services.users.byUsername("maddie")!!.id, "<script>alert(1)</script> & co")
        for (path in listOf("/lite", "/lite/search?q=radiohead", "/lite/playlists", "/lite/radio", "/lite/history", "/lite/now", "/lite/settings", "/lite/devices", "/lite/wrapped", "/lite/heard")) {
            val response = user.get(path)
            assertEquals(path, HttpStatusCode.OK, response.status)
            val html = response.bodyAsText()
            assertTrue("$path is ${html.length} bytes", html.length < 20_000)
            assertFalse(path, html.contains("type=\"module\""))
            assertFalse(path, html.contains("<script>"))
            assertTrue(path, html.contains("<script src=\"/lite/lite.js?v="))
            assertEquals(path, "DENY", response.headers["X-Frame-Options"])
            assertTrue(path, response.headers["Content-Security-Policy"]!!.contains("script-src 'self'"))
            assertEquals(path, "no-store", response.headers[HttpHeaders.CacheControl])
        }
        val playlists = user.get("/lite/playlists").bodyAsText()
        assertFalse(playlists.contains("<script>alert"))
        assertTrue(playlists.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; co"))

        // The script asks for just the page's content, with its title and address.
        val part = user.get("/lite/playlists") { header("X-Lite", "1") }
        assertFalse(part.bodyAsText().contains("<html"))
        assertEquals("Playlists", part.headers["X-Lite-Title"])
        assertEquals("/lite/playlists", part.headers["X-Lite-Url"])

        // Bigger pages are compressed for browsers that take it.
        val zipped = user.get("/lite/search?q=radiohead") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", zipped.headers[HttpHeaders.ContentEncoding])

        // The stylesheet and script: small, kept by browsers, and the script plain ES5 in a file (no modules).
        val js = user.get("/lite/lite.js?v=x")
        assertEquals(HttpStatusCode.OK, js.status)
        val script = js.bodyAsText()
        assertTrue("${script.length} bytes", script.length < 45_000)
        assertFalse(script.contains("/*"))
        assertFalse(script.contains("=>"))
        assertFalse(Regex("""\b(let|const|class|import|export)\s""").containsMatchIn(script.replace(Regex("""(?s)/\*.*?\*/|//[^\n]*"""), "")))
        val css = user.get("/lite/lite.css")
        assertTrue(css.bodyAsText().contains("prefers-color-scheme: dark"))
    }

    @Test
    fun `search, an artist, an album, and a song that plays from the same stream the main player uses`() = serverTest { services ->
        val user = signedIn(services)
        val search = user.get("/lite/search?q=radiohead").bodyAsText()
        assertTrue(search.contains("Paranoid Android"))
        assertTrue(search.contains("<h2>Artists</h2>"))
        assertTrue(search.contains("data-list=\"queue\""))
        val artistLink = Regex("""href="(/lite/artist\?id=[^"]+)"""").find(search)!!.groupValues[1]
        val artist = user.get(artistLink.replace("&amp;", "&")).bodyAsText()
        assertTrue(artist.contains("<h1>Radiohead</h1>"))
        assertTrue(artist.contains("OK Computer"))
        val albumLink = Regex("""href="(/lite/album\?id=[^"]+)"""").find(artist)!!.groupValues[1]
        val album = user.get(albumLink).bodyAsText()
        assertTrue(album.contains("Airbag"))
        assertTrue(album.contains("data-play=\"all\""))

        // A song's page, and playing it without the script: the browser's own player, on /api/stream.
        val token = Regex("""/lite/song\?t=([A-Za-z0-9_-]+)""").find(album)!!.groupValues[1]
        val song = LiteTracks.decode(token)!!
        assertEquals(SourceType.JELLYFIN, song.source)
        val songPage = user.get("/lite/song?t=$token").bodyAsText()
        assertTrue(songPage.contains("Add to a playlist"))
        val play = user.get("/lite/play?t=$token").bodyAsText()
        val src = Regex("""<audio[^>]* src="([^"]+)"""").find(play)!!.groupValues[1].replace("&amp;", "&")
        assertTrue(src, src.startsWith("/api/stream?via="))
        val audio = user.get(src)
        assertEquals(HttpStatusCode.OK, audio.status)
        assertEquals("audio/mpeg", audio.headers[HttpHeaders.ContentType])
        assertTrue(audio.bodyAsText().startsWith("ID3"))
        // With the script, the same song is resolved through the API, as the main player does.
        val resolved = user.post("/api/play/resolve") { csrf(); json("""{"track":${ApiJson.encodeToString(ApiTrack.serializer(), song)},"kbps":0,"codecs":["mp3"]}""") }.obj()
        assertTrue(resolved.str("streamUrl").startsWith("/api/stream?via="))
    }

    @Test
    fun `a playlist is made, a song added and taken out, all with plain forms`() = serverTest { services ->
        val user = signedIn(services)
        val token = LiteTracks.token(ApiTrack(SourceType.JELLYFIN, "s1", "Paranoid Android", "Radiohead", "OK Computer", 387_000, via = jellyfinSource(services)))
        val add = user.get("/lite/add?t=$token").bodyAsText()
        val csrf = csrfIn(add)
        val made = user.form("/lite/playlists", parameters { append("_csrf", csrf); append("name", "Road trip"); append("t", token) })
        assertEquals(HttpStatusCode.OK, made.status)
        val page = made.bodyAsText()
        assertTrue(page.contains("<h1>Road trip</h1>"))
        assertTrue(page.contains("Added to the playlist."))
        assertTrue(page.contains("Paranoid Android"))
        val maddie = services.users.byUsername("maddie")!!
        val playlist = services.playlists.list(maddie.id).single()
        assertEquals(1, playlist.count)

        // Adding again from the list of playlists; then taking one out from the song's page.
        user.form("/lite/add", parameters { append("_csrf", csrf); append("t", token); append("playlist", playlist.id) })
        val entries = services.playlists.get(maddie.id, playlist.id).entries
        assertEquals(2, entries.size)
        val songPage = user.get("/lite/song?t=$token&pl=${playlist.id}&e=${entries[0].entryId}").bodyAsText()
        assertTrue(songPage.contains("Remove from this playlist"))
        val removed = user.form("/lite/playlist/${playlist.id}/remove", parameters { append("_csrf", csrf); append("e", entries[0].entryId) })
        assertTrue(removed.bodyAsText().contains("Taken out of the playlist."))
        assertEquals(1, services.playlists.get(maddie.id, playlist.id).entries.size)

        // Forms need this sign-in's token: another site's form (or one without it) changes nothing.
        val forged = user.form("/lite/playlist/${playlist.id}/remove", parameters { append("e", entries[1].entryId) })
        assertEquals(HttpStatusCode.Forbidden, forged.status)
        val otherSite = user.form("/lite/playlist/${playlist.id}/remove", parameters { append("_csrf", csrf); append("e", entries[1].entryId) }, "https://evil.example")
        assertEquals(HttpStatusCode.Forbidden, otherSite.status)
        assertEquals(1, services.playlists.get(maddie.id, playlist.id).entries.size)
        // Someone else's sign-in token doesn't work either.
        services.users.create("sam", password = "another one")
        val sam = liteBrowser()
        sam.post("/api/auth/login") { json("""{"username":"sam","password":"another one"}""") }
        val theirs = sam.form("/lite/playlists", parameters { append("_csrf", csrf); append("name", "Sneaky") })
        assertEquals(HttpStatusCode.Forbidden, theirs.status)
    }

    @Test
    fun `radio lists the person's own stations, and history and Wrapped show what was played`() = serverTest { services ->
        val user = signedIn(services)
        val maddie = services.users.byUsername("maddie")!!
        val now = System.currentTimeMillis()
        val songs = listOf("Airbag", "Let Down", "Lucky").map { ApiTrack(SourceType.JELLYFIN, "id-$it", it, "Radiohead", "OK Computer", 240_000, via = "x") }
        services.history.add(maddie.id, songs.flatMap { s -> (1..3).map { Listen(s, now - it * 3_600_000L, 200_000, "web") } })

        val radio = user.get("/lite/radio").bodyAsText()
        assertTrue(radio.contains("Madison’s Radio"))
        assertTrue(radio.contains("Madison’s Discovery"))
        val history = user.get("/lite/history").bodyAsText()
        assertTrue(history.contains("Let Down"))
        val wrapped = user.get("/lite/wrapped").bodyAsText()
        assertTrue(wrapped.contains("Top songs"))
        assertTrue(wrapped.contains("9 plays of 3 songs by 1 artist<"))
        assertTrue(user.get("/lite/settings").bodyAsText().contains("Up to 128 kbps"))

        // A personal station plays from the same address as in the main player.
        val station = Regex("""/lite/song\?t=([A-Za-z0-9_-]+)""").find(radio)!!.groupValues[1]
        val play = user.get("/lite/play?t=$station").bodyAsText()
        assertTrue(play.contains("src=\"/api/live/radio/personal/station/mine/stream"))
        assertTrue(play.contains("action=\"/lite/station/skip\""))
        // Not on the air yet: skipping says so plainly.
        val skip = user.form("/lite/station/skip", parameters { append("_csrf", csrfIn(play)); append("id", "mine"); append("back", "/lite/radio") })
        assertEquals(HttpStatusCode.NotFound, skip.status)
        assertTrue(skip.bodyAsText().contains("That station isn&#39;t on"))
    }

    @Test
    fun `settings keep the quality cap, devices are listed and controlled, and signing out ends the sign-in`() = serverTest { services ->
        val user = signedIn(services)
        val settings = user.get("/lite/settings").bodyAsText()
        val saved = user.form("/lite/settings", parameters { append("_csrf", csrfIn(settings)); append("kbps", "128") })
        assertTrue(saved.bodyAsText().contains("<option value=\"128\" selected>"))

        // Another of the person's devices, playing: it's listed, and can be paused from here.
        val maddie = services.users.byUsername("maddie")!!
        val sent = mutableListOf<String>()
        val phone = services.connect.join(maddie.id, "phone-123456", "Pixel", "phone") { sent += it }
        services.connect.report(phone, com.music.server.connect.PlayState(ApiTrack(SourceType.JELLYFIN, "s1", "Paranoid Android", "Radiohead"), playing = true))
        val devices = user.get("/lite/devices").bodyAsText()
        assertTrue(devices.contains("Pixel"))
        assertTrue(devices.contains("Paranoid Android"))
        user.form("/lite/devices/command", parameters { append("_csrf", csrfIn(devices)); append("target", "phone-123456"); append("action", "pause") })
        assertTrue(sent.any { it.contains("\"action\":\"pause\"") })

        val out = user.form("/lite/signout", parameters { append("_csrf", csrfIn(devices)) })
        assertTrue(out.bodyAsText().contains("action=\"/lite/login\""))
        assertEquals(HttpStatusCode.Unauthorized, user.get("/api/me").status)
    }

    @Test
    fun `the main web player doesn't link to the lite one`() {
        val web = File("../web")
        val sources = listOf(File(web, "src"), File(web, "index.html"), File(web, "dist")).filter { it.exists() }
        assertTrue("the web player's sources are where they were", sources.isNotEmpty())
        sources.flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension in setOf("ts", "tsx", "html", "js", "css") }.toList() }.forEach { file ->
            assertFalse("${file.path} mentions /lite", Regex("""["'`(/]lite\b""").containsMatchIn(file.readText()))
        }
    }

    companion object {
        /** A browser: it keeps cookies, and follows a form's answer to the page after it. */
        fun ApplicationTestBuilder.liteBrowser(): HttpClient = createClient {
            install(HttpCookies)
        }

        /** Posts a form as a browser does, following its answer (See Other) to the page after it. */
        suspend fun HttpClient.form(path: String, fields: io.ktor.http.Parameters, origin: String? = null): HttpResponse {
            val answer = submitForm(path, fields) { if (origin != null) header(HttpHeaders.Origin, origin) }
            if (answer.status != HttpStatusCode.SeeOther) return answer
            return get(answer.headers[HttpHeaders.Location]!!)
        }

        fun csrfIn(html: String): String = Regex("""name="_csrf" value="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: throw AssertionError("No form token in the page")

        suspend fun jellyfinSource(services: Services): String = services.sources.allGlobal().first { it.kind == SourceKind.JELLYFIN }.id

        /** Only the pretend Jellyfin: no YouTube Music or Radio Browser (they'd go to the internet). */
        suspend fun libraryOnly(services: Services, jellyfin: MockWebServer) {
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

        private fun item(id: String, name: String, seconds: Long) =
            """{"Id":"$id","Name":"$name","Artists":["Radiohead"],"Album":"OK Computer","AlbumId":"al1","RunTimeTicks":${seconds * 10_000_000},""" +
                """"ImageTags":{"Primary":"t"},"MediaSources":[{"Id":"m$id","Bitrate":320000,"MediaStreams":[{"Type":"Audio","Codec":"mp3","BitRate":320000}]}]}"""

        private val SONGS = mapOf("s1" to item("s1", "Paranoid Android", 387), "s2" to item("s2", "Airbag", 287), "s3" to item("s3", "Let Down", 299))
        private const val ALBUM = """{"Id":"al1","Name":"OK Computer","AlbumArtist":"Radiohead","ProductionYear":1997,"ChildCount":3,"ImageTags":{"Primary":"t"}}"""

        /** A pretend Jellyfin with Radiohead's OK Computer, whose songs it streams ([audio], else a few bytes of MP3). */
        fun liteJellyfin(audio: ByteArray? = null, image: ByteArray? = null): MockWebServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val term = url.queryParameter("searchTerm")?.lowercase()
                    if (url.encodedPath.startsWith("/Audio/")) {
                        val bytes = audio ?: ("ID3" + "x".repeat(2_000)).toByteArray()
                        return MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(bytes))
                    }
                    if (url.encodedPath.contains("/Images/")) {
                        return if (image != null) MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image)) else MockResponse().setResponseCode(404)
                    }
                    val items = when {
                        url.encodedPath == "/Artists" -> if (term != null && "radiohead".startsWith(term.take(5))) listOf("""{"Id":"ar1","Name":"Radiohead","ImageTags":{"Primary":"t"}}""") else emptyList()
                        url.queryParameter("includeItemTypes") == "MusicAlbum" -> when {
                            url.queryParameter("ids") == "al1" || url.queryParameter("albumArtistIds") == "ar1" -> listOf(ALBUM)
                            term != null && "ok computer".contains(term) -> listOf(ALBUM)
                            else -> emptyList()
                        }
                        url.queryParameter("ids") != null -> url.queryParameter("ids")!!.split(',').mapNotNull { SONGS[it] }
                        url.queryParameter("parentId") == "al1" || url.queryParameter("artistIds") == "ar1" -> SONGS.values.toList()
                        term != null && term.length > 2 && ("radiohead".contains(term) || SONGS.values.any { it.lowercase().contains(term) }) -> SONGS.values.toList()
                        else -> emptyList()
                    }
                    return MockResponse().setBody("""{"Items":[${items.joinToString(",")}]}""")
                }
            }
            start()
        }
    }
}
