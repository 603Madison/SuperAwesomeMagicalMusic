package com.music.server

import com.music.server.db.Database
import com.music.server.db.Schema
import com.music.server.util.Passwords
import com.music.server.util.Random
import com.music.server.util.SecretBox
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/** A server with an in-memory database and quick password hashing, for tests. */
fun testServices(
    config: ServerConfig = ServerConfig(dataDir = Files.createTempDirectory("samm-test").toFile(), adminPassword = "break-glass-pw"),
    clock: () -> Long = System::currentTimeMillis,
    selfUrl: () -> String = { "http://127.0.0.1:${config.port}" },
): Services {
    val db = Database.inMemory()
    Schema.migrate(db)
    return Services(config, db, SecretBox(Random.bytes(32)), passwords = Passwords(iterations = 1_000), clock = clock, selfUrl = selfUrl)
}

fun serverTest(services: Services = testServices(), block: suspend ApplicationTestBuilder.(Services) -> Unit) = testApplication {
    services.bootstrap()
    application { module(services) }
    block(services)
}

fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies) }

fun HttpRequestBuilder.json(body: String) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

/** What the web player sends with every change. */
fun HttpRequestBuilder.csrf() = header("X-SAMM", "1")

suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())

suspend fun HttpResponse.obj(): JsonObject = json().jsonObject

fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

suspend fun HttpClient.patchJson(path: String, body: String): HttpResponse = patch(path) { csrf(); json(body) }
