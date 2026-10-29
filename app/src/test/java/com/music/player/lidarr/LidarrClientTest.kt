package com.music.player.lidarr

import com.music.player.net.AppJson
import com.music.player.settings.LidarrConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LidarrClientTest {
    private lateinit var server: MockWebServer
    private val client = LidarrClient(OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun config() = LidarrConfig(serverUrl = server.url("/").toString(), apiKey = "key", searchForMissingAlbums = true)

    @Test
    fun `lookup sends the api key and search term`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"artistName":"Daft Punk","foreignArtistId":"056e4f3e"}]"""))
        val results = client.lookup(config(), "Daft Punk")
        val request = server.takeRequest()
        assertEquals("/api/v1/artist/lookup?term=Daft%20Punk", request.path)
        assertEquals("key", request.getHeader("X-Api-Key"))
        assertEquals("Daft Punk", results.single()["artistName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `adding an artist posts the lookup result with monitoring options`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":7,"artistName":"Daft Punk","foreignArtistId":"056e4f3e"}"""))
        val lookup = AppJson.parseToJsonElement("""{"artistName":"Daft Punk","foreignArtistId":"056e4f3e","images":[]}""") as JsonObject
        val added = client.addArtist(config(), lookup, qualityProfileId = 2, metadataProfileId = 3, rootFolderPath = "/music")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/artist", request.path)
        val body = AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("056e4f3e", body["foreignArtistId"]!!.jsonPrimitive.content)
        assertEquals(2, body["qualityProfileId"]!!.jsonPrimitive.int)
        assertEquals(3, body["metadataProfileId"]!!.jsonPrimitive.int)
        assertEquals("/music", body["rootFolderPath"]!!.jsonPrimitive.content)
        assertEquals(true, body["monitored"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["addOptions"]!!.jsonObject["searchForMissingAlbums"]!!.jsonPrimitive.boolean)
        assertEquals(7, added.id)
    }
}
