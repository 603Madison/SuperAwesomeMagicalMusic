package com.music.server.sources

import com.music.player.lidarr.LidarrClient
import com.music.server.ApiError
import com.music.server.Services
import com.music.server.auth.requireUser
import com.music.server.util.Random
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NewSource(val kind: SourceKind, val name: String = "", val scope: String = "personal", val fields: JsonObject = JsonObject(emptyMap()))

@Serializable
data class SourceChange(val name: String? = null, val enabled: Boolean? = null, val fields: JsonObject? = null, val position: Int? = null)

@Serializable
data class HiddenChange(val hidden: Boolean)

@Serializable
data class KindsResponse(val kinds: List<KindSpec>, val canAddPersonal: Boolean, val canAddGlobal: Boolean)

@Serializable
data class TestResult(val ok: Boolean, val message: String)

@Serializable
data class Choice(val id: String, val name: String)

@Serializable
data class LidarrOptions(val qualityProfiles: List<Choice>, val metadataProfiles: List<Choice>, val rootFolders: List<Choice>)

fun Route.sourceRoutes(services: Services) {
    route("/api/sources") {
        get("/kinds") {
            val user = call.requireUser(services)
            call.respond(KindsResponse(services.setup.kinds, services.settings.get().allowPersonalSources || user.isAdmin, user.isAdmin))
        }

        get {
            val user = call.requireUser(services)
            call.respond(services.sources.visibleTo(user))
        }

        post {
            val user = call.requireUser(services)
            val request = call.receive<NewSource>()
            val global = request.scope == "global"
            when {
                global && !user.isAdmin -> throw ApiError.forbidden("Only admins can add sources for everyone")
                !global && !user.isAdmin && !services.settings.get().allowPersonalSources ->
                    throw ApiError.forbidden("This server doesn't let people add their own sources")
            }
            val id = Random.id()
            val (settings, secrets) = services.setup.configure(request.kind, id, request.fields, previous = null)
            val created = services.sources.create(
                id = id,
                ownerId = if (global) null else user.id,
                kind = request.kind,
                name = request.name.ifBlank { defaultName(request.kind, settings) },
                settings = settings,
                secrets = secrets,
            )
            call.respond(services.sources.visibleTo(user).first { it.id == created.id })
        }

        route("/{id}") {
            patch {
                val user = call.requireUser(services)
                val source = editable(services, user, call.parameters["id"])
                val change = call.receive<SourceChange>()
                val configured = change.fields?.let { services.setup.configure(source.kind, source.id, it, previous = source) }
                services.sources.update(
                    source.id,
                    name = change.name,
                    enabled = change.enabled,
                    settings = configured?.first,
                    secrets = configured?.second,
                    position = change.position,
                )
                services.registry.forget(source.id)
                call.respond(services.sources.visibleTo(user).first { it.id == source.id })
            }

            delete {
                val user = call.requireUser(services)
                val source = editable(services, user, call.parameters["id"])
                services.sources.delete(source.id)
                services.registry.forget(source.id)
                call.respond(mapOf("ok" to true))
            }

            put("/hidden") {
                val user = call.requireUser(services)
                val source = services.sources.accessible(user, call.parameters["id"].orEmpty()) ?: throw ApiError.notFound("No such source")
                services.sources.setHidden(user.id, source.id, call.receive<HiddenChange>().hidden)
                call.respond(services.sources.visibleTo(user).first { it.id == source.id })
            }

            post("/test") {
                val user = call.requireUser(services)
                val source = services.sources.accessible(user, call.parameters["id"].orEmpty()) ?: throw ApiError.notFound("No such source")
                val result = try {
                    TestResult(true, services.setup.test(source, services.registry))
                } catch (e: ApiError) {
                    TestResult(false, e.message ?: "Not working")
                }
                call.respond(result)
            }

            get("/lidarr-options") {
                val user = call.requireUser(services)
                val source = editable(services, user, call.parameters["id"])
                if (source.kind != SourceKind.LIDARR) throw ApiError.badRequest("That isn't a Lidarr source")
                val options = SourceSetup.service("Lidarr") { LidarrClient(services.http).serverOptions(services.registry.lidarrConfig(source)) }
                call.respond(
                    LidarrOptions(
                        qualityProfiles = options.qualityProfiles.map { Choice(it.id.toString(), it.name) },
                        metadataProfiles = options.metadataProfiles.map { Choice(it.id.toString(), it.name) },
                        rootFolders = options.rootFolders.map { Choice(it.path, it.path) },
                    ),
                )
            }
        }
    }
}

private suspend fun editable(services: Services, user: com.music.server.auth.User, id: String?): StoredSource {
    val source = services.sources.accessible(user, id.orEmpty()) ?: throw ApiError.notFound("No such source")
    if (!source.canEdit(user)) throw ApiError.forbidden("Only admins can change sources shared with everyone")
    return source
}

private fun defaultName(kind: SourceKind, settings: JsonObject): String {
    val host = (settings["serverUrl"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?.substringAfter("://")?.substringBefore('/')?.substringBefore(':')
    return if (host != null && kind == SourceKind.JELLYFIN) "Jellyfin ($host)" else kind.label
}
