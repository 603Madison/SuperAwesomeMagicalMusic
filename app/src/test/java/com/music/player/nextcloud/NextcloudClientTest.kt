package com.music.player.nextcloud

import com.music.player.settings.NextcloudConfig
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NextcloudClientTest {
    private lateinit var server: MockWebServer
    private val client = NextcloudClient(OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun config() = NextcloudConfig(
        serverUrl = server.url("/").toString(),
        username = "alice@example.com",
        userId = "alice",
        appPassword = "app-pass",
        folder = "Music/Playlists",
    )

    @Test
    fun `propfind response lists files but not folders`() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:href>/remote.php/dav/files/alice/Music/Playlists/</d:href>
                <d:propstat><d:prop><d:getetag>"folder"</d:getetag><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/alice/Music/Playlists/Road%20Trip.json</d:href>
                <d:propstat><d:prop><d:getetag>"abc123"</d:getetag><d:resourcetype/></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/alice/Music/Playlists/Rock+Roll.json</d:href>
                <d:propstat><d:prop><d:getetag>W/"def"</d:getetag><d:resourcetype/></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        assertEquals(
            listOf(RemoteFile("Road Trip.json", "abc123"), RemoteFile("Rock+Roll.json", "def")),
            NextcloudClient.parseMultiStatus(xml),
        )
    }

    @Test
    fun `creates each folder level under the user's dav root`() = runTest {
        server.enqueue(MockResponse().setResponseCode(405)) // Music exists
        server.enqueue(MockResponse().setResponseCode(201)) // Playlists created
        client.ensureFolder(config())
        val first = server.takeRequest()
        assertEquals("MKCOL", first.method)
        assertEquals("/remote.php/dav/files/alice/Music/", first.path)
        assertEquals("/remote.php/dav/files/alice/Music/Playlists/", server.takeRequest().path)
    }

    @Test
    fun `uploads with basic auth and returns the new etag`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).addHeader("OC-ETag", "\"etag-1\""))
        val etag = client.upload(config(), "Road Trip.json", "{}")
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/remote.php/dav/files/alice/Music/Playlists/Road%20Trip.json", request.path)
        assertEquals(okhttp3.Credentials.basic("alice@example.com", "app-pass"), request.getHeader("Authorization"))
        assertEquals("etag-1", etag)
    }

    @Test
    fun `user id comes from the OCS user endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ocs":{"meta":{"status":"ok"},"data":{"id":"alice","displayname":"Alice"}}}"""))
        assertEquals("alice", client.fetchUserId(config().copy(userId = "")))
        assertEquals("/ocs/v2.php/cloud/user?format=json", server.takeRequest().path)
    }
}
