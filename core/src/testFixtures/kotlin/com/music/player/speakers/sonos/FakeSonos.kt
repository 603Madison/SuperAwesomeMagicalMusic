package com.music.player.speakers.sonos

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A pretend Sonos speaker: answers the UPnP SOAP actions the server and the phone use, in the shapes real
 * speakers send (recorded from an S2 Era 100 and a Play:1), and remembers what it was asked.
 * [householdXml] is what its topology says; by default a group of this speaker, a stereo pair's
 * hidden other half and a Boost.
 */
class FakeSonos(val uuid: String = "RINCON_000E58A0B1C201400", val room: String = "Living Room") : AutoCloseable {
    data class Call(val action: String, val body: String)

    val calls = CopyOnWriteArrayList<Call>()
    @Volatile var state = "STOPPED"
    @Volatile var uri = ""
    @Volatile var metadata = ""
    @Volatile var relTimeMs = 0L
    @Volatile var volume = 25
    @Volatile var groupVolume = 40
    @Volatile var householdXml: String? = null

    private val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.port}"
    val address: String get() = "127.0.0.1:${server.port}"

    fun actions(): List<String> = calls.map { it.action }
    fun last(action: String): Call? = calls.lastOrNull { it.action == action }

    private fun answer(request: RecordedRequest): MockResponse {
        val action = request.getHeader("SOAPACTION")?.trim('"')?.substringAfter('#') ?: return MockResponse().setResponseCode(404)
        val body = request.body.readUtf8()
        calls += Call(action, body)
        fun arg(name: String) = SonosXml.tag(body, name)?.let(SonosXml::unescape)
        val result = when (action) {
            "GetZoneGroupState" -> "<ZoneGroupState>${SonosXml.escape(householdXml ?: household())}</ZoneGroupState>"
            "SetAVTransportURI" -> {
                uri = arg("CurrentURI").orEmpty()
                metadata = arg("CurrentURIMetaData").orEmpty()
                state = "STOPPED"
                relTimeMs = 0
                ""
            }
            "Play" -> { state = "PLAYING"; "" }
            "Pause" -> {
                if (uri.startsWith("x-rincon-mp3radio:")) return fault(701)
                state = "PAUSED_PLAYBACK"; ""
            }
            "Stop" -> { state = "STOPPED"; "" }
            "Seek" -> { relTimeMs = SonosXml.parseTime(arg("Target")); "" }
            "SetVolume" -> { volume = arg("DesiredVolume")?.toIntOrNull() ?: volume; "" }
            "SnapshotGroupVolume" -> ""
            "SetGroupVolume" -> { groupVolume = arg("DesiredVolume")?.toIntOrNull() ?: groupVolume; "" }
            "GetVolume" -> "<CurrentVolume>$volume</CurrentVolume>"
            "GetGroupVolume" -> "<CurrentVolume>$groupVolume</CurrentVolume>"
            "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
            "GetPositionInfo" -> "<Track>1</Track><TrackDuration>0:04:00</TrackDuration>" +
                "<TrackMetaData>${SonosXml.escape(metadata)}</TrackMetaData><TrackURI>${SonosXml.escape(uri)}</TrackURI>" +
                "<RelTime>${SonosXml.formatTime(relTimeMs)}</RelTime><AbsTime>NOT_IMPLEMENTED</AbsTime><RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>"
            else -> return fault(401)
        }
        val service = request.getHeader("SOAPACTION")!!.trim('"').substringBefore('#')
        return MockResponse().setHeader("Content-Type", "text/xml; charset=\"utf-8\"").setBody(
            """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
                """<s:Body><u:${action}Response xmlns:u="$service">$result</u:${action}Response></s:Body></s:Envelope>""",
        )
    }

    private fun fault(code: Int) = MockResponse().setResponseCode(500).setBody(
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode>""" +
            """<faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>""",
    )

    /** One group: this speaker (coordinator), its stereo pair's other half (hidden), and a Boost on its own. */
    fun household(): String =
        """<ZoneGroupState><ZoneGroups>""" +
            """<ZoneGroup Coordinator="$uuid" ID="$uuid:1234">""" +
            """<ZoneGroupMember UUID="$uuid" Location="$baseUrl/xml/device_description.xml" ZoneName="$room" Icon="" Configuration="1" SoftwareVersion="83.1-61240" SWGen="2" MinCompatibleVersion="82.0-00000" LegacyCompatibleVersion="58.0-00000" BootSeq="80" TVConfigurationError="0" HdmiCecAvailable="0" WirelessMode="0" WirelessLeafOnly="0" ChannelFreq="2412" BehindWifiExtender="0" WifiEnabled="1" EthLink="0" Orientation="0" RoomCalibrationState="4" SecureRegState="3" VoiceConfigState="0" MicEnabled="0" AirPlayEnabled="1" IdleState="1" MoreInfo="" SSLPort="1443" HHSSLPort="1843">""" +
            """<Satellite UUID="RINCON_000E58SUB0001400" Location="http://192.0.2.9:1400/xml/device_description.xml" ZoneName="$room" Invisible="1"/>""" +
            """</ZoneGroupMember>""" +
            """<ZoneGroupMember UUID="RINCON_000E58A0B1C301400" Location="http://192.0.2.8:1400/xml/device_description.xml" ZoneName="$room" Invisible="1" ChannelMapSet="$uuid:LF,LF;RINCON_000E58A0B1C301400:RF,RF"/>""" +
            """</ZoneGroup>""" +
            """<ZoneGroup Coordinator="RINCON_000E58BOOST01400" ID="RINCON_000E58BOOST01400:5">""" +
            """<ZoneGroupMember UUID="RINCON_000E58BOOST01400" Location="http://192.0.2.7:1400/xml/device_description.xml" ZoneName="BOOST" IsZoneBridge="1"/>""" +
            """</ZoneGroup>""" +
            """</ZoneGroups><VanishedDevices></VanishedDevices></ZoneGroupState>"""

    override fun close() = server.shutdown()
}
