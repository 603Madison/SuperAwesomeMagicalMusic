package com.music.server

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.LoggerFactory

/** The Claude connector: a link from Settings, the MCP handshake, its tools, and what the link can't open. */
class McpTest {
    private suspend fun HttpClient.rpc(path: String, method: String, params: String = "{}", id: Int = 1, bearer: String? = null): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            bearer?.let { header("Authorization", "Bearer $it") }
            setBody("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
        }

    private suspend fun HttpClient.tool(path: String, name: String, arguments: String = "{}"): Pair<String, Boolean> {
        val result = rpc(path, "tools/call", """{"name":"$name","arguments":$arguments}""").obj()["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray[0].jsonObject.str("text")
        return text to (result["isError"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun `a connector link answers MCP with read-only tools, and opens nothing else`() = serverTest { services ->
        services.users.create("maddie", password = "correct horse")
        val user = browser()
        user.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }

        // A link from Settings, which shows with the sign-ins.
        val url = user.post("/api/me/mcp-links") { csrf() }.obj().str("url")
        val path = url.substringAfter("://").substringAfter('/').let { "/$it" }
        assertTrue(path, path.startsWith("/mcp/"))
        val token = path.removePrefix("/mcp/")
        val link = user.get("/api/me/sessions").json().jsonArray.map { it.jsonObject }.single { it.str("kind") == "MCP" }
        assertEquals("Claude connector", link.str("name"))

        // The handshake: the client's protocol version, the server's name and its tools.
        val init = client.rpc(path, "initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""").obj()
        val result = init["result"]!!.jsonObject
        assertEquals("2025-06-18", result.str("protocolVersion"))
        assertEquals("superawesomemagicalmusic", result["serverInfo"]!!.jsonObject.str("name"))
        assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"))
        // An older version is answered in kind; an unknown one with the newest.
        assertEquals("2025-03-26", client.rpc(path, "initialize", """{"protocolVersion":"2025-03-26"}""").obj()["result"]!!.jsonObject.str("protocolVersion"))
        assertEquals("2025-06-18", client.rpc(path, "initialize", """{"protocolVersion":"1999-01-01"}""").obj()["result"]!!.jsonObject.str("protocolVersion"))
        val notified = client.post(path) { contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") }
        assertEquals(HttpStatusCode.Accepted, notified.status)
        val tools = client.rpc(path, "tools/list").obj()["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            setOf("server_status", "recent_warnings", "siriusxm_log", "stream_test", "search", "devices", "recent_listens"),
            tools.map { it.str("name") }.toSet(),
        )
        assertTrue(tools.all { it["annotations"]!!.jsonObject.str("readOnlyHint") == "true" })
        assertTrue(client.rpc(path, "ping").obj().containsKey("result"))
        assertEquals(-32601, client.rpc(path, "nope").obj()["error"]!!.jsonObject.str("code").toInt())

        // The tools, as maddie.
        val (status, statusFailed) = client.tool(path, "server_status")
        assertFalse(status, statusFailed)
        assertTrue(status, "SuperAwesomeMagicalMusic server ${Services.version}" in status)
        assertTrue(status, "maddie" in status && "YOUTUBE_MUSIC" in status)
        assertEquals("No listens yet." to false, client.tool(path, "recent_listens"))
        assertEquals("None of this account's devices is connected now." to false, client.tool(path, "devices"))
        assertEquals("This account has no SiriusXM source turned on." to false, client.tool(path, "siriusxm_log"))
        val (warnings, refused) = client.tool(path, "recent_warnings")
        assertTrue(warnings, refused && "admins" in warnings)
        assertTrue(client.tool(path, "stream_test", """{"source":"nope","id":"x"}""").second)

        // The same token as a bearer token, on /mcp.
        val viaHeader = client.rpc("/mcp", "tools/call", """{"name":"devices"}""", bearer = token).obj()
        assertFalse(viaHeader.containsKey("error"))

        // The link opens nothing else, and nothing else opens the connector.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me/sessions") { header("Authorization", "Bearer $token") }.status)
        val app = client.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse","app":true,"deviceName":"Phone"}""") }.obj()
        val appToken = app.str("token")
        assertEquals(HttpStatusCode.NotFound, client.rpc("/mcp/$appToken", "ping").status)
        assertEquals(HttpStatusCode.NotFound, client.rpc("/mcp", "ping", bearer = appToken).status)
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get(path).status)
        // No OAuth to discover: a plain "not here", not the web player's page.
        val discovery = client.get("/.well-known/oauth-protected-resource")
        assertEquals(HttpStatusCode.NotFound, discovery.status)
        assertFalse(discovery.bodyAsText().contains("<html", ignoreCase = true))

        // Removed with the sign-ins: the link stops working.
        user.delete("/api/me/sessions/${link.str("id")}") { csrf() }
        assertEquals(HttpStatusCode.NotFound, client.rpc(path, "ping").status)
    }

    @Test
    fun `an admin's connector reads the server's latest warnings`() = serverTest {
        val admin = browser()
        admin.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }
        val path = "/" + admin.post("/api/me/mcp-links") { csrf() }.obj().str("url").substringAfter("://").substringAfter('/')
        LoggerFactory.getLogger("Streamer").warn("rr1.googlevideo.com answered HTTP 403 for Opus 160 kbps after 120 ms")
        LoggerFactory.getLogger("Other").warn("Something unrelated")
        val (all, failed) = client.tool(path, "recent_warnings")
        assertFalse(all, failed)
        assertTrue(all, "Streamer: rr1.googlevideo.com answered HTTP 403" in all)
        val (only, _) = client.tool(path, "recent_warnings", """{"contains":"GOOGLEVIDEO","limit":5}""")
        assertTrue(only, "403" in only && "unrelated" !in only)
    }

}
