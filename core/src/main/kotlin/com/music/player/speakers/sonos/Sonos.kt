package com.music.player.speakers.sonos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// Sonos speakers over UPnP (SOAP on port 1400), shared by the server (which plays on them when
// it's on the same network) and the phone app (which plays on them when it's at home).

/** A Sonos speaker, as its household's topology lists it. */
data class SonosMember(
    /** RINCON_…: stays the same for the speaker. */
    val uuid: String,
    /** Its room ("Living Room"). */
    val name: String,
    /** Where it answers UPnP, e.g. http://192.168.1.20:1400. */
    val baseUrl: String,
    /** Part of a stereo pair or home theatre that isn't shown on its own. */
    val invisible: Boolean = false,
    /** A Boost or Bridge: no speaker. */
    val bridge: Boolean = false,
) {
    val host: String get() = baseUrl.toHttpUrlOrNull()?.host ?: baseUrl
}

/** Speakers playing together; commands go to the [coordinator]. */
data class SonosGroup(val coordinator: SonosMember, val members: List<SonosMember>) {
    /** "Living Room", "Living Room + Kitchen", "Living Room + 2". */
    val name: String
        get() {
            val names = (listOf(coordinator) + members).filter { !it.invisible && !it.bridge }.map { it.name }.distinct()
            return when (names.size) {
                0, 1 -> coordinator.name
                2 -> "${names[0]} + ${names[1]}"
                else -> "${names[0]} + ${names.size - 1}"
            }
        }

    /** How many speakers you'd count (a stereo pair is one). */
    val rooms: Int get() = members.filter { !it.invisible && !it.bridge }.map { it.name }.distinct().size

    /** Its volume is the group's (more than one room plays). */
    val grouped: Boolean get() = rooms > 1
}

/** Something for a Sonos to play: an address it can fetch, and what to show while it plays. */
data class SonosMedia(
    val url: String,
    /** The kind of audio at [url] (audio/mpeg, audio/flac…). */
    val mime: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** The cover, at an address the speaker can fetch. */
    val artUrl: String? = null,
    val durationMs: Long? = null,
    /** A station: no end, no seeking. */
    val live: Boolean = false,
)

/** What a Sonos's player is doing (UPnP AVTransport). */
data class SonosTransport(
    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING or NO_MEDIA_PRESENT. */
    val state: String,
    val positionMs: Long,
    val durationMs: Long,
    /** The address it's playing, unescaped (x-rincon-mp3radio://… for a station). */
    val uri: String,
)

/** Sonos said no, or couldn't be reached; the message says which, in plain words. */
class SonosException(message: String) : IOException(message)

/** Sonos's XML, by hand: the few shapes it sends are simple, and a parser library would be more to trust. */
object SonosXml {
    /** The text of the first <name> element (any namespace prefix). */
    fun tag(xml: String, name: String): String? =
        Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    fun attributes(text: String): Map<String, String> =
        Regex("([A-Za-z_][A-Za-z0-9_:.-]*)=\"([^\"]*)\"").findAll(text).associate { it.groupValues[1] to unescape(it.groupValues[2]) }

    fun escape(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') append(' ') else append(ch)
            }
        }
    }

    fun unescape(text: String): String =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

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
        return "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    }

    /**
     * The household's groups from GetZoneGroupState's answer (itself XML, escaped inside the
     * SOAP answer; newer firmware wraps it in another ZoneGroupState).
     */
    fun topology(soapAnswer: String): List<SonosGroup> {
        val state = tag(soapAnswer, "ZoneGroupState")?.let(::unescape) ?: soapAnswer
        return Regex("<ZoneGroup\\s([^>]*)>(.*?)</ZoneGroup>", RegexOption.DOT_MATCHES_ALL).findAll(state).mapNotNull { group ->
            val coordinatorId = attributes(group.groupValues[1])["Coordinator"] ?: return@mapNotNull null
            val members = Regex("<ZoneGroupMember\\s([^>]*?)/?>").findAll(group.groupValues[2]).mapNotNull { member ->
                val a = attributes(member.groupValues[1])
                val uuid = a["UUID"] ?: return@mapNotNull null
                val location = a["Location"]?.toHttpUrlOrNull() ?: return@mapNotNull null
                SonosMember(
                    uuid = uuid,
                    name = a["ZoneName"]?.takeIf { it.isNotBlank() } ?: "Sonos",
                    baseUrl = "${location.scheme}://${hostForUrl(location.host)}:${location.port}",
                    invisible = a["Invisible"] == "1",
                    bridge = a["IsZoneBridge"] == "1",
                )
            }.toList()
            val coordinator = members.firstOrNull { it.uuid == coordinatorId } ?: return@mapNotNull null
            SonosGroup(coordinator, members)
        }.filter { !it.coordinator.bridge && it.members.any { m -> !m.invisible && !m.bridge } }.toList()
    }

    /**
     * What a song is (DIDL-Lite), for the Sonos app and the speaker's screen: title, artist,
     * album and cover. Stations are "audio broadcasts", which Sonos shows as radio.
     */
    fun didl(media: SonosMedia): String = buildString {
        append("""<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
        append("""xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""")
        if (media.live) {
            append("""<item id="R:0/0/0" parentID="R:0/0" restricted="true">""")
            append("<dc:title>").append(escape(media.title)).append("</dc:title>")
            append("<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>")
            media.artUrl?.let { append("<upnp:albumArtURI>").append(escape(it)).append("</upnp:albumArtURI>") }
            append("""<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">SA_RINCON65031_</desc>""")
        } else {
            append("""<item id="-1" parentID="-1" restricted="true">""")
            append("""<res protocolInfo="http-get:*:${escape(media.mime)}:*"""")
            media.durationMs?.takeIf { it > 0 }?.let { append(""" duration="${formatTime(it)}"""") }
            append(">").append(escape(media.url)).append("</res>")
            media.artUrl?.let { append("<upnp:albumArtURI>").append(escape(it)).append("</upnp:albumArtURI>") }
            append("<dc:title>").append(escape(media.title)).append("</dc:title>")
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            append("<dc:creator>").append(escape(media.artist)).append("</dc:creator>")
            media.album?.takeIf { it.isNotBlank() }?.let { append("<upnp:album>").append(escape(it)).append("</upnp:album>") }
        }
        append("</item></DIDL-Lite>")
    }

    /**
     * The address Sonos is given: an endless stream as `x-rincon-mp3radio:` (Sonos's way of
     * saying "a radio stream", which it plays for MP3 and AAC alike); a song as it is.
     */
    fun uriFor(media: SonosMedia): String =
        if (media.live && media.url.startsWith("http://")) "x-rincon-mp3radio://" + media.url.removePrefix("http://") else media.url

    fun hostForUrl(host: String): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
}

/** Talks to one Sonos speaker over UPnP (SOAP on port 1400). */
class SonosClient(http: OkHttpClient, val baseUrl: String) {
    private val http = http.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    enum class Service(val path: String, val urn: String) {
        AV_TRANSPORT("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1"),
        RENDERING("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1"),
        GROUP_RENDERING("/MediaRenderer/GroupRenderingControl/Control", "urn:schemas-upnp-org:service:GroupRenderingControl:1"),
        TOPOLOGY("/ZoneGroupTopology/Control", "urn:schemas-upnp-org:service:ZoneGroupTopology:1"),
    }

    /** The household's groups, as this speaker knows them. */
    suspend fun topology(): List<SonosGroup> = SonosXml.topology(soap(Service.TOPOLOGY, "GetZoneGroupState", ""))

    /** Calls [action] with [arguments] (already XML) and returns the answer. */
    suspend fun soap(service: Service, action: String, arguments: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + service.path)
            .header("SOAPACTION", "\"${service.urn}#$action\"")
            .post(envelope(service, action, arguments).toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    val code = SonosXml.tag(text, "errorCode")
                    throw SonosException("Sonos refused $action (${code?.let { "error $it${UPNP_ERRORS[it]?.let { e -> ": $e" }.orEmpty()}" } ?: "HTTP ${response.code}"})")
                }
                text
            }
        } catch (e: SonosException) {
            throw e
        } catch (e: IOException) {
            throw SonosException("Couldn't reach the Sonos at ${baseUrl.substringAfter("://")}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        private val UPNP_ERRORS = mapOf(
            "701" to "it can't do that now",
            "711" to "it can't seek in this",
            "714" to "it can't play that kind of audio",
            "716" to "it couldn't fetch the song",
            "800" to "it isn't the group's coordinator",
        )

        /** The SOAP message for [action] (topology has no InstanceID; everything else is instance 0). */
        fun envelope(service: Service, action: String, arguments: String): String {
            val instance = if (service == Service.TOPOLOGY) "" else "<InstanceID>0</InstanceID>"
            return """<?xml version="1.0" encoding="utf-8"?>""" +
                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
                """<s:Body><u:$action xmlns:u="${service.urn}">$instance$arguments</u:$action></s:Body></s:Envelope>"""
        }

        /** "192.168.1.20", "sonos-kitchen:1400" or "http://192.168.1.20:1400" as where it answers UPnP. */
        fun baseOf(address: String): String? {
            val trimmed = address.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            val explicitPort = Regex("^https?://(\\[[^]]+]|[^/:]+):(\\d+)").containsMatchIn(withScheme)
            return "${parsed.scheme}://${SonosXml.hostForUrl(parsed.host)}:${if (explicitPort) parsed.port else PORT}"
        }

        const val PORT = 1400
    }
}

/**
 * A Sonos group's player, through its coordinator: what to play, play/pause/seek, the volume
 * (the group's when rooms play together) and what it's doing.
 */
class SonosPlayer(val client: SonosClient, val grouped: Boolean) {
    /** Puts [media] on, replacing whatever was there (it starts with [play]). */
    suspend fun load(media: SonosMedia) {
        // A stop first, so a speaker busy with something else (or another group's stream) takes it.
        runCatching { client.soap(SonosClient.Service.AV_TRANSPORT, "Stop", "") }
        val uri = SonosXml.uriFor(media)
        client.soap(
            SonosClient.Service.AV_TRANSPORT, "SetAVTransportURI",
            "<CurrentURI>${SonosXml.escape(uri)}</CurrentURI><CurrentURIMetaData>${SonosXml.escape(SonosXml.didl(media))}</CurrentURIMetaData>",
        )
    }

    suspend fun play() {
        client.soap(SonosClient.Service.AV_TRANSPORT, "Play", "<Speed>1</Speed>")
    }

    suspend fun pause() {
        client.soap(SonosClient.Service.AV_TRANSPORT, "Pause", "")
    }

    suspend fun stop() {
        client.soap(SonosClient.Service.AV_TRANSPORT, "Stop", "")
    }

    /** Jumps to [positionMs] into what's playing. */
    suspend fun seek(positionMs: Long) {
        client.soap(SonosClient.Service.AV_TRANSPORT, "Seek", "<Unit>REL_TIME</Unit><Target>${SonosXml.formatTime(positionMs)}</Target>")
    }

    /** 0 to 1. */
    suspend fun setVolume(level: Double) {
        val volume = (level.coerceIn(0.0, 1.0) * 100).roundToInt()
        if (grouped) {
            // Sonos keeps the rooms' levels relative to each other from a snapshot of them.
            runCatching { client.soap(SonosClient.Service.GROUP_RENDERING, "SnapshotGroupVolume", "") }
            client.soap(SonosClient.Service.GROUP_RENDERING, "SetGroupVolume", "<DesiredVolume>$volume</DesiredVolume>")
        } else {
            client.soap(SonosClient.Service.RENDERING, "SetVolume", "<Channel>Master</Channel><DesiredVolume>$volume</DesiredVolume>")
        }
    }

    /** 0 to 1, if it says (and an exception if it can't be reached). */
    suspend fun volume(): Double? {
        val answer = if (grouped) {
            client.soap(SonosClient.Service.GROUP_RENDERING, "GetGroupVolume", "")
        } else {
            client.soap(SonosClient.Service.RENDERING, "GetVolume", "<Channel>Master</Channel>")
        }
        return (SonosXml.tag(answer, "CurrentVolume") ?: SonosXml.tag(answer, "CurrentGroupVolume"))?.trim()?.toIntOrNull()?.let { it / 100.0 }
    }

    suspend fun transport(): SonosTransport {
        val info = client.soap(SonosClient.Service.AV_TRANSPORT, "GetTransportInfo", "")
        val position = client.soap(SonosClient.Service.AV_TRANSPORT, "GetPositionInfo", "")
        return SonosTransport(
            state = SonosXml.tag(info, "CurrentTransportState")?.trim() ?: "STOPPED",
            positionMs = SonosXml.parseTime(SonosXml.tag(position, "RelTime")),
            durationMs = SonosXml.parseTime(SonosXml.tag(position, "TrackDuration")),
            uri = SonosXml.tag(position, "TrackURI")?.let(SonosXml::unescape).orEmpty(),
        )
    }
}

/**
 * Finds Sonos speakers on the home network with SSDP: asks everyone listening on the network's
 * multicast address for zone players, and collects where each describes itself. Only works on
 * the same network as the speakers (a server in a container needs host networking; a phone
 * needs Android's multicast lock).
 */
object SonosDiscovery {
    const val ZONE_PLAYER = "urn:schemas-upnp-org:device:ZonePlayer:1"
    private const val MULTICAST = "239.255.255.250"
    private const val SSDP_PORT = 1900

    fun searchMessage(): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $MULTICAST:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $ZONE_PLAYER\r\n" +
            "USER-AGENT: Linux UPnP/1.0 SuperAwesomeMagicalMusic/1\r\n\r\n"

    /** Where a Sonos that answered describes itself (its LOCATION), if the answer is from a Sonos. */
    fun locationOf(response: String): String? {
        val headers = response.lineSequence().drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().uppercase() to line.substring(colon + 1).trim()
        }.toMap()
        val sonos = headers["ST"] == ZONE_PLAYER || headers.containsKey("X-RINCON-HOUSEHOLD") || headers["SERVER"]?.contains("Sonos", ignoreCase = true) == true
        return headers["LOCATION"]?.takeIf { sonos && it.startsWith("http") }
    }

    /** Where the Sonos that sent [response] answers UPnP (http://192.168.1.20:1400), if it's a Sonos. */
    fun baseOf(response: String): String? = locationOf(response)?.toHttpUrlOrNull()?.let { location ->
        "${location.scheme}://${SonosXml.hostForUrl(location.host)}:${location.port}"
    }

    /** The speakers that answered within [timeoutMs], as where they answer UPnP. */
    suspend fun search(timeoutMs: Long = 3_000, address: String = MULTICAST, port: Int = SSDP_PORT): Set<String> = withContext(Dispatchers.IO) {
        val found = mutableSetOf<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 250
            val message = searchMessage().toByteArray()
            val target = InetAddress.getByName(address)
            // UDP can be lost: ask twice.
            repeat(2) { socket.send(DatagramPacket(message, message.size, target, port)) }
            val until = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < until) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                baseOf(String(packet.data, 0, packet.length))?.let(found::add)
            }
        }
        found
    }
}
