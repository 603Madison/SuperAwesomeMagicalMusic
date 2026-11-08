package com.music.connect.speakers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a speaker says about itself. */
data class SpeakerInfo(
    /** Yamaha's id for the device (stays the same when its address changes). */
    val deviceId: String,
    /** The name it was given in the MusicCast app ("Living Room"). */
    val name: String,
    val model: String,
    val host: String,
    /** Where its AVTransport service takes commands. */
    val transportUrl: String,
)

/** The speaker's main zone. */
data class ZoneStatus(val on: Boolean, val volume: Int, val maxVolume: Int, val muted: Boolean, val input: String)

/** What the speaker's player is doing (UPnP AVTransport). */
data class Transport(
    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING or NO_MEDIA_PRESENT. */
    val state: String,
    val positionMs: Long,
    val durationMs: Long,
    val uri: String,
)

class SpeakerException(message: String) : IOException(message)

/**
 * A Yamaha MusicCast speaker or receiver on the home network. Yamaha's own API (YXC) turns it on,
 * sets the volume and says which input it's on; its UPnP media renderer plays a song from an
 * address and says how far in it is.
 */
class MusicCastSpeaker(
    override val info: SpeakerInfo,
    http: OkHttpClient,
    /** Where Yamaha's API is (always port 80 on a real speaker). */
    private val yxc: String = "http://${hostForUrl(info.host)}/YamahaExtendedControl/v1",
) : SpeakerControl {
    private val http = http.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()


    override suspend fun status(): ZoneStatus {
        val status = getJson("$yxc/main/getStatus")
        val volume = status["volume"]?.jsonPrimitive?.intOrNull ?: 0
        val max = status["max_volume"]?.jsonPrimitive?.intOrNull ?: maxVolume()
        return ZoneStatus(
            on = status["power"]?.jsonPrimitive?.content == "on",
            volume = volume,
            maxVolume = max.coerceAtLeast(1),
            muted = status["mute"]?.jsonPrimitive?.booleanOrNull == true,
            input = status["input"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    private var knownMax: Int? = null

    /** The top of the main zone's volume range (older firmware leaves it out of the status). */
    private suspend fun maxVolume(): Int = knownMax ?: runCatching {
        val features = getJson("$yxc/system/getFeatures")
        features["zone"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["id"]?.jsonPrimitive?.content == "main" }
            ?.get("range_step")?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["id"]?.jsonPrimitive?.content == "volume" }
            ?.get("max")?.jsonPrimitive?.intOrNull
    }.getOrNull().let { (it ?: 100).also { max -> knownMax = max } }

    override suspend fun setPower(on: Boolean) {
        getJson("$yxc/main/setPower?power=${if (on) "on" else "standby"}")
    }

    override suspend fun setVolume(level: Int) {
        getJson("$yxc/main/setVolume?volume=${level.coerceAtLeast(0)}")
    }

    /** Plays [url] next ([metadata] is its DIDL-Lite description, for the speaker's display). */
    override suspend fun setUri(url: String, metadata: String) {
        soap("SetAVTransportURI", "<CurrentURI>${xmlEscape(url)}</CurrentURI><CurrentURIMetaData>${xmlEscape(metadata)}</CurrentURIMetaData>")
    }

    override suspend fun play() {
        soap("Play", "<Speed>1</Speed>")
    }

    override suspend fun pause() {
        soap("Pause", "")
    }

    override suspend fun stop() {
        soap("Stop", "")
    }

    override suspend fun transport(): Transport {
        val info = soap("GetTransportInfo", "")
        val position = soap("GetPositionInfo", "")
        return Transport(
            state = tag(info, "CurrentTransportState") ?: "STOPPED",
            positionMs = parseTime(tag(position, "RelTime")),
            durationMs = parseTime(tag(position, "TrackDuration")),
            uri = tag(position, "TrackURI")?.let(::xmlUnescape).orEmpty(),
        )
    }

    private suspend fun getJson(url: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SpeakerException("${info.name}: HTTP ${response.code}")
            val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
            val code = json["response_code"]?.jsonPrimitive?.intOrNull ?: 0
            if (code != 0) throw SpeakerException("${info.name} refused (code $code)")
            json
        }
    }

    private suspend fun soap(action: String, arguments: String): String = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$action xmlns:u="$AV_TRANSPORT"><InstanceID>0</InstanceID>$arguments</u:$action></s:Body></s:Envelope>"""
        val request = Request.Builder()
            .url(info.transportUrl)
            .header("SOAPACTION", "\"$AV_TRANSPORT#$action\"")
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = tag(text, "errorDescription") ?: tag(text, "errorCode") ?: "HTTP ${response.code}"
                throw SpeakerException("${info.name}: $action failed ($error)")
            }
            text
        }
    }

    companion object {
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        /** MusicCast's own app, which the speakers know. */
        const val USER_AGENT = "MusicCast/6.00 (Android)"
        const val UPNP_PORT = 49154

        /**
         * Finds out about the speaker at [host]: its name and id from Yamaha's API, and where its
         * player takes commands from its UPnP description ([description], if discovery found it).
         */
        suspend fun identify(host: String, http: OkHttpClient, description: String? = null): SpeakerInfo = withContext(Dispatchers.IO) {
            val quick = http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
            fun json(path: String): JsonObject {
                val request = Request.Builder().url("http://${hostForUrl(host)}/YamahaExtendedControl/v1/$path").header("User-Agent", USER_AGENT).build()
                return quick.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw SpeakerException("No MusicCast speaker at $host")
                    Json.parseToJsonElement(response.body!!.string()).jsonObject
                }
            }
            val device = json("system/getDeviceInfo")
            val id = device["device_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: throw SpeakerException("No MusicCast speaker at $host")
            val model = device["model_name"]?.jsonPrimitive?.content.orEmpty()
            val name = runCatching { json("system/getNetworkStatus")["network_name"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() } ?: model.ifBlank { "MusicCast speaker" }
            val location = description ?: "http://${hostForUrl(host)}:$UPNP_PORT/MediaRenderer/desc.xml"
            val transport = runCatching {
                val xml = quick.newCall(Request.Builder().url(location).header("User-Agent", USER_AGENT).build()).execute().use { it.body?.string().orEmpty() }
                transportUrl(xml, location)
            }.getOrNull() ?: "http://${hostForUrl(host)}:$UPNP_PORT/AVTransport/ctrl"
            SpeakerInfo(deviceId = id, name = name, model = model, host = host, transportUrl = transport)
        }

        /** The AVTransport control address in a UPnP device description fetched from [location]. */
        fun transportUrl(description: String, location: String): String? {
            val services = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(description).map { it.groupValues[1] }
            val service = services.firstOrNull { tag(it, "serviceType")?.startsWith("urn:schemas-upnp-org:service:AVTransport:") == true } ?: return null
            val control = tag(service, "controlURL")?.trim() ?: return null
            val base = tag(description, "URLBase")?.trim()?.takeIf { it.isNotEmpty() } ?: location
            return base.toHttpUrlOrNull()?.resolve(control)?.toString()
        }

        /** Whether a UPnP description is a Yamaha MusicCast device. */
        fun isMusicCast(description: String): Boolean =
            description.contains("X_yxcControlURL") || (tag(description, "manufacturer")?.contains("Yamaha", ignoreCase = true) == true && description.contains("AVTransport"))

        /** The text of the first <name> element (any namespace prefix). */
        fun tag(xml: String, name: String): String? =
            Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

        /** "H:MM:SS(.fff)" as milliseconds; 0 for "NOT_IMPLEMENTED" and the like. */
        fun parseTime(value: String?): Long {
            val parts = value?.trim()?.split(':') ?: return 0
            if (parts.size != 3) return 0
            val hours = parts[0].toLongOrNull() ?: return 0
            val minutes = parts[1].toLongOrNull() ?: return 0
            val seconds = parts[2].toDoubleOrNull() ?: return 0
            return hours * 3_600_000 + minutes * 60_000 + (seconds * 1000).toLong()
        }

        fun formatTime(ms: Long): String {
            val total = ms.coerceAtLeast(0) / 1000
            return "%d:%02d:%02d.000".format(total / 3600, total / 60 % 60, total % 60)
        }

        fun xmlEscape(text: String): String = buildString(text.length) {
            text.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }

        fun xmlUnescape(text: String): String =
            text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        /** An IPv6 address goes in brackets in a URL. */
        fun hostForUrl(host: String): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

        /**
         * A song's DIDL-Lite description: what the speaker (and Yamaha's app) show while it plays,
         * and the kind of audio at [url].
         */
        fun didl(url: String, mime: String, title: String, artist: String, album: String?, artUrl: String?, durationMs: Long?): String = buildString {
            append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")
            append("""<item id="1" parentID="0" restricted="1">""")
            append("<dc:title>").append(xmlEscape(title)).append("</dc:title>")
            append("<dc:creator>").append(xmlEscape(artist)).append("</dc:creator>")
            append("<upnp:artist>").append(xmlEscape(artist)).append("</upnp:artist>")
            album?.takeIf { it.isNotBlank() }?.let { append("<upnp:album>").append(xmlEscape(it)).append("</upnp:album>") }
            artUrl?.let { append("<upnp:albumArtURI>").append(xmlEscape(it)).append("</upnp:albumArtURI>") }
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            append("""<res protocolInfo="http-get:*:$mime:*"""")
            durationMs?.takeIf { it > 0 }?.let { append(""" duration="${formatTime(it)}"""") }
            append(">").append(xmlEscape(url)).append("</res>")
            append("</item></DIDL-Lite>")
        }
    }
}
