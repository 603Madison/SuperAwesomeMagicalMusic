package com.music.server.mcp

import com.music.server.ApiJson
import com.music.server.ErrorBody
import com.music.server.Services
import com.music.server.auth.SessionKind
import com.music.server.auth.User
import com.music.server.music.qualityOf
import com.music.server.music.streamTest
import com.music.server.sources.SourceKind
import com.music.server.util.RecentLog
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The server as a Model Context Protocol server (Streamable HTTP, answering each request with
 * plain JSON): Claude, given a connector link from Settings, can look at the server to help
 * troubleshoot it: its sources, its log, SiriusXM's stream log, a stream test of any song, the
 * devices, search and listening history.
 *
 * Everything is read-only and limited to the person the link belongs to (the server's log,
 * which concerns everyone, to admins). An admin's link made to see every account
 * ([SessionKind.MCP_ALL]) sees every account's sources, SiriusXM, devices and history, while its
 * admin still is one. The link is the key: `/mcp/<token>`, or `/mcp` with the token as a bearer
 * token. Links are sign-ins of their own kind, listed and revoked with the account's devices, and
 * open nothing but this.
 */
fun Route.mcpRoutes(services: Services) {
    val mcp = McpServer(services)

    suspend fun ApplicationCall.serve(token: String?) {
        val (user, session) = token?.let { services.sessions.resolve(it) }?.takeIf { it.second.kind.isConnector }
            ?: return respond(HttpStatusCode.NotFound, ErrorBody("That connector link isn't valid (any more): make a new one in Settings"))
        val caller = McpServer.Caller(user, everyone = session.kind == SessionKind.MCP_ALL && user.isAdmin)
        val body = runCatching { ApiJson.parseToJsonElement(receiveText()) }.getOrNull()
            ?: return respondJson(McpServer.error(JsonNull, -32700, "That isn't JSON"))
        when (body) {
            is JsonObject -> mcp.handle(caller, body)?.let { respondJson(it) } ?: respond(HttpStatusCode.Accepted, "")
            is JsonArray -> {
                val replies = body.mapNotNull { (it as? JsonObject)?.let { message -> mcp.handle(caller, message) } }
                if (replies.isEmpty()) respond(HttpStatusCode.Accepted, "") else respondJson(JsonArray(replies))
            }
            else -> respondJson(McpServer.error(JsonNull, -32600, "Not a JSON-RPC request"))
        }
    }

    post("/mcp/{token}") { call.serve(call.parameters["token"]) }
    post("/mcp") {
        call.serve(call.request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim())
    }
    // No event stream to open, and no session to end: each request stands alone.
    for (path in listOf("/mcp", "/mcp/{token}")) {
        get(path) { call.notAllowed() }
        delete(path) { call.notAllowed() }
    }
    // No OAuth: the link is the key. Saying so plainly (not with the web player's page, which
    // answers every other address) keeps clients from trying to sign in.
    get("/.well-known/{...}") { call.respond(HttpStatusCode.NotFound, ErrorBody("Not here")) }
}

private suspend fun ApplicationCall.notAllowed() {
    response.header(HttpHeaders.Allow, "POST")
    respond(HttpStatusCode.MethodNotAllowed, ErrorBody("Send JSON-RPC requests with POST"))
}

private suspend fun ApplicationCall.respondJson(element: JsonElement) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(ApiJson.encodeToString(JsonElement.serializer(), element), ContentType.Application.Json)
}

/** The protocol: initialize, ping, and the tools (see [tools]). */
class McpServer(private val services: Services) {
    /** Who's asking: the link's account, and whether it sees every account (an admin's link). */
    class Caller(val user: User, val everyone: Boolean)

    private class Tool(
        val name: String,
        val title: String,
        val description: String,
        val inputSchema: JsonObject,
        val run: suspend (Caller, JsonObject) -> String,
    )

    /** One JSON-RPC message from the client; null for a notification (nothing to answer). */
    suspend fun handle(caller: Caller, message: JsonObject): JsonObject? {
        val id = message["id"]?.takeUnless { it is JsonNull }
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        if (id == null) return null // A notification ("initialized", "cancelled"), or a reply.
        if (method == null) return error(id, -32600, "No method")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(tools.map(::describe))) })
                "tools/call" -> callTool(id, caller, params)
                "resources/list" -> result(id, buildJsonObject { put("resources", JsonArray(emptyList())) })
                "resources/templates/list" -> result(id, buildJsonObject { put("resourceTemplates", JsonArray(emptyList())) })
                "prompts/list" -> result(id, buildJsonObject { put("prompts", JsonArray(emptyList())) })
                else -> error(id, -32601, "No method $method")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(id, -32603, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", asked?.takeIf { it in VERSIONS } ?: VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "superawesomemagicalmusic")
                put("title", "SuperAwesomeMagicalMusic")
                put("version", Services.version)
            }
            put(
                "instructions",
                "A self-hosted music server (SuperAwesomeMagicalMusic): a web player and phone app play the owner's Jellyfin " +
                    "library, Qobuz, YouTube Music, SiriusXM (live, Xtra channels and Pandora stations) and radio through it. " +
                    "These tools only look: start with server_status; for a song that won't play, search then stream_test; for " +
                    "SiriusXM channels that stall, siriusxm_log (its Xtra timeline includes what the web player reported); for " +
                    "errors, recent_warnings, and server_log for everything the server did lately (admins).",
            )
        }
    }

    private suspend fun callTool(id: JsonElement, caller: Caller, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
        val tool = tools.firstOrNull { it.name == name } ?: return error(id, -32602, "No tool named $name")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        log.info("Claude connector of {}{}: {} {}", caller.user.username, if (caller.everyone) " (every account)" else "", name, arguments)
        val (text, failed) = try {
            tool.run(caller, arguments) to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            (e.message ?: e.toString()) to true
        }
        return result(id, buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text.ifBlank { "(nothing)" })
                })
            })
            put("isError", failed)
        })
    }

    private fun describe(tool: Tool) = buildJsonObject {
        put("name", tool.name)
        put("title", tool.title)
        put("description", tool.description)
        put("inputSchema", tool.inputSchema)
        putJsonObject("annotations") {
            put("title", tool.title)
            put("readOnlyHint", true)
            put("openWorldHint", false)
        }
    }

    private val account = "account" to stringProperty(
        "Whose: an account's username (default the link's own; an admin's every-account link can name any account)",
    )

    private val tools = listOf(
        Tool(
            "server_status",
            "Server status",
            "The server's version and uptime, its public address, the sources this account uses (Jellyfin, Qobuz, " +
                "YouTube Music, SiriusXM, radio, Lidarr) with their ids, and the devices connected. An every-account link " +
                "lists every account and everyone's sources.",
            schema(),
        ) { caller, _ -> status(caller) },
        Tool(
            "recent_warnings",
            "Recent warnings",
            "The server's latest warnings and errors (newest last): streams a service refused or was slow to send, " +
                "failed searches, SiriusXM or sign-in trouble. Admins only.",
            schema(
                "limit" to intProperty("How many lines, newest last (default 60, at most 300)"),
                "contains" to stringProperty("Only lines containing this text (any case)"),
            ),
        ) { caller, args ->
            requireAdmin(caller)
            val contains = args.string("contains")?.lowercase()
            val lines = RecentLog.latest(warningsOnly = true).filter { contains == null || contains in it.lowercase() }
            lines.takeLast(args.int("limit")?.coerceIn(1, 300) ?: 60).joinToString("\n").ifBlank { "No warnings since the server started." }
        },
        Tool(
            "server_log",
            "Server log",
            "Everything the server logged lately (newest last): each API request with its status and time (song streams, " +
                "relayed SiriusXM playlists, segments and keys), SiriusXM's timeline, what the Claude connector was asked, " +
                "sign-ins, warnings. Admins only.",
            schema(
                "limit" to intProperty("How many lines, newest last (default 200, at most 2000)"),
                "contains" to stringProperty("Only lines containing this text (any case), e.g. a path, a host or a channel id"),
                "warnings_only" to boolProperty("Only warnings and errors"),
            ),
        ) { caller, args ->
            requireAdmin(caller)
            val contains = args.string("contains")?.lowercase()
            val lines = RecentLog.latest(warningsOnly = args.bool("warnings_only") ?: false).filter { contains == null || contains in it.lowercase() }
            lines.takeLast(args.int("limit")?.coerceIn(1, 2000) ?: 200).joinToString("\n").takeLast(MAX_TEXT).ifBlank { "Nothing logged that matches." }
        },
        Tool(
            "siriusxm_log",
            "SiriusXM stream log",
            "For SiriusXM: the Xtra channel timeline (each tune and peek, what was added, the player's playlist reloads, the " +
                "song heard, when the player ran out of queued tracks, segments or keys the server couldn't relay, and the " +
                "errors and stalls the web player reported) and, with details, what SiriusXM's servers answered. Use it when " +
                "a SiriusXM channel or Pandora station stalls or skips. An every-account link shows everyone's SiriusXM.",
            schema("details" to boolProperty("Also what SiriusXM answered, in full (long; default false)"), account),
        ) { caller, args -> siriusXmLog(caller, args) },
        Tool(
            "stream_test",
            "Stream test",
            "Fetches a song the way the players do: finds it on the source, then reads the start of its audio and says " +
                "how the service answered and how fast it came (for YouTube Music, every copy it offers). Give a source " +
                "and id (from search), or a query to test the first song found.",
            schema(
                "query" to stringProperty("Search for this and test the first song found"),
                "source" to stringProperty("A source id (from server_status or search)"),
                "id" to stringProperty("The song's id on that source (from search)"),
                "kbps" to intProperty("A bitrate cap, as a player would ask (0 or none: original quality)"),
                account,
            ),
        ) { caller, args -> streamTestOf(caller, args) },
        Tool(
            "search",
            "Search songs",
            "Searches every source the account uses for songs; each result names its source id and song id (for stream_test).",
            schema(
                "query" to stringProperty("What to search for"),
                "limit" to intProperty("Songs per source (default 5, at most 25)"),
                account,
                required = listOf("query"),
            ),
        ) { caller, args -> search(caller, args) },
        Tool(
            "devices",
            "Devices",
            "The connected devices (web players, phones, MusicCast speakers) and what each is playing: song, position, " +
                "playing or paused. An every-account link shows everyone's.",
            schema(),
        ) { caller, _ -> devices(caller) },
        Tool(
            "recent_listens",
            "Recent listens",
            "An account's listening history, newest first: when, what, from which source, how long, on which device.",
            schema("limit" to intProperty("How many (default 30, at most 200)"), account),
        ) { caller, args -> listens(accountOf(caller, args), args.int("limit")?.coerceIn(1, 200) ?: 30) },
    )

    private fun requireAdmin(caller: Caller) {
        if (!caller.user.isAdmin) error("The server's log is for admins; this connector belongs to ${caller.user.username}")
    }

    /** The account a tool is asked about: the link's own, or (every-account links) any named one. */
    private suspend fun accountOf(caller: Caller, args: JsonObject): User {
        val name = args.string("account") ?: return caller.user
        if (name.equals(caller.user.username, ignoreCase = true)) return caller.user
        if (!caller.everyone) error("This link sees only ${caller.user.username}'s account (an admin's every-account link sees others)")
        return services.users.byUsername(name) ?: error("No account named $name")
    }

    private suspend fun status(caller: Caller): String {
        val user = caller.user
        val lines = mutableListOf<String>()
        lines += "SuperAwesomeMagicalMusic server ${Services.version}, running for ${span(services.clock() - services.startedAt)}"
        lines += "Public address: ${services.settings.get().publicUrl.ifBlank { "(not set)" }}"
        lines += "This connector belongs to ${user.displayName} (${user.username}${if (user.isAdmin) ", admin" else ""})" +
            if (caller.everyone) " and sees every account" else ""
        lines += ""
        if (caller.everyone) {
            val accounts = services.users.list()
            val names = accounts.associate { it.id to it.username }
            lines += "Accounts:"
            accounts.forEach { lines += "- ${it.username} (${if (it.isAdmin) "admin" else "user"}${if (it.disabled) ", turned off" else ""})" }
            lines += ""
            lines += "Sources (everyone's):"
            for (source in services.sources.all()) {
                val owner = source.ownerId?.let { "${names[it] ?: it}'s" } ?: "shared"
                lines += "- ${source.name} (${source.kind}, id ${source.id}; $owner${if (!source.enabled) ", off" else ""})"
            }
        } else {
            lines += "Sources:"
            for (source in services.sources.visibleTo(user)) {
                val notes = listOfNotNull(
                    if (source.scope == "global") "shared" else "personal",
                    "off".takeIf { !source.enabled },
                    "hidden by this account".takeIf { source.hidden },
                )
                lines += "- ${source.name} (${source.kind}, id ${source.id}; ${notes.joinToString(", ")})"
            }
        }
        lines += ""
        lines += devices(caller)
        return lines.joinToString("\n")
    }

    private suspend fun siriusXmLog(caller: Caller, args: JsonObject): String {
        val details = args.bool("details") ?: false
        val named = args.string("account")?.let { accountOf(caller, args) }
        val sources = when {
            named != null -> services.sources.activeFor(named)
            caller.everyone -> services.sources.all().filter { it.enabled }
            else -> services.sources.activeFor(caller.user)
        }.filter { it.kind == SourceKind.SIRIUSXM }
        if (sources.isEmpty()) return if (caller.everyone || named != null) "No SiriusXM source is turned on." else "This account has no SiriusXM source turned on."
        val names = if (caller.everyone) services.users.list().associate { it.id to it.username } else emptyMap()
        return sources.joinToString("\n\n") { source ->
            val log = services.registry.siriusXm(source)?.diagnostics(details).orEmpty()
            val owner = source.ownerId?.let { names[it] }?.let { ", $it's" }.orEmpty()
            // The newest is what matters: the end is kept.
            "== ${source.name} (id ${source.id}$owner) ==\n" + log.ifBlank { "Nothing yet: nothing has been played from it since the server started." }.takeLast(MAX_TEXT)
        }
    }

    private suspend fun streamTestOf(caller: Caller, args: JsonObject): String {
        val user = accountOf(caller, args)
        val query = args.string("query")
        val (sourceId, songId) = if (query != null) {
            val groups = services.browser.search(user, query, 5).groups
            val song = groups.firstNotNullOfOrNull { it.tracks.firstOrNull() } ?: return "Nothing found for \"$query\"."
            song.via.orEmpty() to song.sourceId
        } else {
            (args.string("source") ?: error("Give a source and id, or a query")) to (args.string("id") ?: error("Give the song's id, or a query"))
        }
        val source = (if (caller.everyone) services.sources.get(sourceId) else services.sources.accessible(user, sourceId))?.takeIf { it.enabled }
            ?: error("No source $sourceId for this account (see server_status)")
        return streamTest(services, source, songId, qualityOf(args.int("kbps") ?: 0)).joinToString("\n")
    }

    private suspend fun search(caller: Caller, args: JsonObject): String {
        val user = accountOf(caller, args)
        val query = args.string("query")?.takeIf { it.length in 1..200 } ?: error("What to search for?")
        val found = services.browser.search(user, query, args.int("limit")?.coerceIn(1, 25) ?: 5)
        return found.groups.joinToString("\n\n") { group ->
            val header = "${group.sourceName} (${group.kind}, source id ${group.sourceId})"
            val body = group.error?.let { "  failed: $it" }
                ?: group.tracks.joinToString("\n") { "  ${it.title} — ${it.artist}${it.album?.let { a -> " · $a" }.orEmpty()} (id ${it.sourceId})" }.ifBlank { "  nothing" }
            "$header\n$body"
        }.ifBlank { "No sources to search." }
    }

    private suspend fun devices(caller: Caller): String {
        val accounts = if (caller.everyone) services.connect.accounts().mapNotNull { services.users.byId(it) } else listOf(caller.user)
        val lines = accounts.flatMap { user ->
            services.connect.devices(user.id).map { device ->
                val state = device.state
                val what = state?.track?.let { track ->
                    val where = if (state.live) "live" else "${span(state.positionMs)} of ${span(state.durationMs)}"
                    "${if (state.playing) "playing" else "paused on"} ${track.title} — ${track.artist} (${track.source}, $where, volume ${(state.volume * 100).toInt()}%)"
                } ?: "nothing loaded"
                "- ${device.name} (${device.kind}${if (caller.everyone) ", ${user.username}'s" else ""}${if (device.active) ", the one in use" else ""}): $what"
            }
        }
        if (lines.isEmpty()) return if (caller.everyone) "No devices are connected now." else "None of this account's devices is connected now."
        return "Devices connected now:\n" + lines.joinToString("\n")
    }

    private suspend fun listens(user: User, limit: Int): String {
        val listens = services.history.list(user.id, limit)
        if (listens.isEmpty()) return "No listens yet for ${user.username}."
        return listens.joinToString("\n") {
            "${TIME.format(Instant.ofEpochMilli(it.playedAt))}  ${it.track.title} — ${it.track.artist} (${it.track.source}, " +
                "listened ${span(it.listenedMs)}${it.client.takeIf { c -> c.isNotBlank() }?.let { c -> ", on $c" }.orEmpty()})"
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger("Connector")

        /** Newest first; an older one is answered when a client asks for it. */
        val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        private const val MAX_TEXT = 80_000
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

        fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }

        private fun schema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema) } }
            if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            put("additionalProperties", false)
        }

        private fun stringProperty(description: String) = buildJsonObject { put("type", "string"); put("description", description) }
        private fun intProperty(description: String) = buildJsonObject { put("type", "integer"); put("description", description) }
        private fun boolProperty(description: String) = buildJsonObject { put("type", "boolean"); put("description", description) }

        private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        private fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
        private fun JsonObject.bool(name: String) = (this[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

        /** "3h 12m", "2:31". */
        fun span(ms: Long): String {
            val seconds = (ms / 1000).coerceAtLeast(0)
            return when {
                seconds >= 86_400 -> "${seconds / 86_400}d ${(seconds % 86_400) / 3600}h"
                seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
                else -> "${seconds / 60}:${String.format(java.util.Locale.ROOT, "%02d", seconds % 60)}"
            }
        }
    }
}
