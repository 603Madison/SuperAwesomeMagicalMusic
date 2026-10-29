package com.music.player.nextcloud

import com.music.player.net.AppJson
import com.music.player.net.Http
import com.music.player.net.HttpException
import com.music.player.net.execute
import com.music.player.settings.NextcloudConfig
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

data class RemoteFile(val name: String, val etag: String?)

/** WebDAV access to a folder in the user's Nextcloud files (remote.php/dav/files/<user>/<folder>). */
class NextcloudClient(private val http: OkHttpClient) {

    /**
     * Checks the credentials and returns the account's user id, which is what WebDAV paths use
     * (it differs from the login name for e-mail or LDAP logins).
     */
    suspend fun fetchUserId(config: NextcloudConfig): String {
        val url = "${Http.normalizeBaseUrl(config.serverUrl)}/ocs/v2.php/cloud/user".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .build()
        val body = request(config, url, "GET", null).use {
            if (!it.isSuccessful) throw HttpException(it.code, describe(it.code))
            it.body?.string().orEmpty()
        }
        val data = AppJson.parseToJsonElement(body).jsonObject["ocs"]?.jsonObject?.get("data")?.jsonObject
        return data?.get("id")?.jsonPrimitive?.content ?: config.username
    }

    /** Creates the playlist folder (and its parents) if needed. */
    suspend fun ensureFolder(config: NextcloudConfig) {
        var url = userRoot(config)
        for (segment in folderSegments(config)) {
            url = url.newBuilder().addPathSegment(segment).addPathSegment("").build()
            request(config, url, "MKCOL", null).use {
                // 201 = created, 405 = already exists.
                if (it.code != 201 && it.code != 405 && !it.isSuccessful) throw HttpException(it.code, describe(it.code))
            }
        }
    }

    suspend fun list(config: NextcloudConfig): List<RemoteFile> =
        request(config, folder(config), "PROPFIND", PROPFIND_BODY.toRequestBody(XML), "Depth" to "1").use {
            if (it.code == 404) return emptyList()
            if (!it.isSuccessful) throw HttpException(it.code, describe(it.code))
            parseMultiStatus(it.body?.string().orEmpty())
        }

    suspend fun download(config: NextcloudConfig, name: String): Pair<String, String?> =
        request(config, file(config, name), "GET", null).use {
            if (!it.isSuccessful) throw HttpException(it.code, describe(it.code))
            it.body?.string().orEmpty() to normalizeEtag(it.header("ETag") ?: it.header("OC-ETag"))
        }

    /** Uploads [content] and returns the new ETag when the server reports one. */
    suspend fun upload(config: NextcloudConfig, name: String, content: String): String? =
        request(config, file(config, name), "PUT", content.toRequestBody(JSON)).use {
            if (!it.isSuccessful) throw HttpException(it.code, describe(it.code))
            normalizeEtag(it.header("OC-ETag") ?: it.header("ETag"))
        }

    suspend fun delete(config: NextcloudConfig, name: String) {
        request(config, file(config, name), "DELETE", null).use {
            if (!it.isSuccessful && it.code != 404) throw HttpException(it.code, describe(it.code))
        }
    }

    private suspend fun request(
        config: NextcloudConfig,
        url: HttpUrl,
        method: String,
        body: RequestBody?,
        vararg headers: Pair<String, String>,
    ) = http.execute(
        Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(config.username, config.appPassword))
            .header("OCS-APIRequest", "true")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .method(method, body)
            .build(),
    )

    private fun userRoot(config: NextcloudConfig): HttpUrl =
        "${Http.normalizeBaseUrl(config.serverUrl)}/remote.php/dav/files/".toHttpUrl().newBuilder()
            .addPathSegment(config.davUser)
            .addPathSegment("")
            .build()

    private fun folder(config: NextcloudConfig): HttpUrl = userRoot(config).newBuilder().apply {
        folderSegments(config).forEach { addPathSegment(it) }
        addPathSegment("")
    }.build()

    private fun file(config: NextcloudConfig, name: String): HttpUrl =
        userRoot(config).newBuilder().apply {
            folderSegments(config).forEach { addPathSegment(it) }
            addPathSegment(name)
        }.build()

    private fun folderSegments(config: NextcloudConfig) = config.folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    private fun describe(code: Int) = when (code) {
        401 -> "Nextcloud rejected the username or app password"
        403 -> "Nextcloud denied access to the folder"
        404 -> "Nextcloud folder not found"
        else -> "Nextcloud returned HTTP $code"
    }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val DAV_NS = "DAV:"
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:resourcetype/></d:prop></d:propfind>
        """.trimIndent()

        fun normalizeEtag(etag: String?): String? = etag?.trim()?.removePrefix("W/")?.trim('"')?.takeIf { it.isNotEmpty() }

        /** Extracts the files (not folders) listed in a Depth: 1 PROPFIND response. */
        fun parseMultiStatus(xml: String): List<RemoteFile> {
            if (xml.isBlank()) return emptyList()
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
            val responses = document.getElementsByTagNameNS(DAV_NS, "response")
            return (0 until responses.length).mapNotNull { index ->
                val response = responses.item(index) as Element
                val href = response.firstText("href") ?: return@mapNotNull null
                val isFolder = response.getElementsByTagNameNS(DAV_NS, "collection").length > 0
                if (isFolder) return@mapNotNull null
                val name = URLDecoder.decode(href.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
                RemoteFile(name = name, etag = normalizeEtag(response.firstText("getetag")))
            }
        }

        private fun Element.firstText(localName: String): String? =
            getElementsByTagNameNS(DAV_NS, localName).item(0)?.textContent?.trim()
    }
}
