package com.music.server.speakers

import com.music.player.net.Http
import com.music.server.speakers.sonos.SonosClient
import com.music.server.speakers.sonos.SonosDiscovery
import com.music.server.speakers.sonos.SonosNetwork
import com.music.server.speakers.sonos.SonosRenderer
import com.music.server.speakers.sonos.SonosXml
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class SonosTest {
    private val http = Http.createClient()

    /** An SSDP answer as a Sonos One sends it. */
    private val ssdpAnswer = "HTTP/1.1 200 OK\r\n" +
        "CACHE-CONTROL: max-age = 1800\r\n" +
        "EXT:\r\n" +
        "LOCATION: http://192.168.1.20:1400/xml/device_description.xml\r\n" +
        "SERVER: Linux UPnP/1.0 Sonos/83.1-61240 (ZPS27)\r\n" +
        "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
        "USN: uuid:RINCON_000E58A0B1C201400::urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
        "X-RINCON-HOUSEHOLD: Sonos_abcdefghijklmnopqrstuvwx\r\n" +
        "X-RINCON-BOOTSEQ: 80\r\n" +
        "BOOTID.UPNP.ORG: 80\r\n" +
        "X-RINCON-WIFIMODE: 0\r\n" +
        "X-RINCON-VARIANT: 2\r\n" +
        "HOUSEHOLD.SMARTSPEAKER.AUDIO: Sonos_abcdefghijklmnopqrstuvwx.abcdefghijklmnopqrst\r\n\r\n"

    @Test
    fun `an SSDP answer from a Sonos gives where it is, and other devices' answers are left out`() {
        assertEquals("http://192.168.1.20:1400/xml/device_description.xml", SonosDiscovery.locationOf(ssdpAnswer))
        val router = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1:5000/rootDesc.xml\r\nSERVER: Linux/5.4 UPnP/1.1 MiniUPnPd/2.2\r\nST: upnp:rootdevice\r\n\r\n"
        assertNull(SonosDiscovery.locationOf(router))
        val search = SonosDiscovery.searchMessage()
        assertTrue(search.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue("ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" in search)
        assertTrue(search.endsWith("\r\n\r\n"))
    }

    @Test
    fun `discovery asks over UDP and collects the speakers that answer`() = runBlocking {
        // A pretend speaker listening for searches (on this machine rather than the multicast group).
        val speaker = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val heard = mutableListOf<String>()
        val thread = Thread {
            val buffer = ByteArray(2048)
            runCatching {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    speaker.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    synchronized(heard) { heard += text }
                    if ("ZonePlayer" in text) {
                        val reply = ssdpAnswer.toByteArray()
                        speaker.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val found = SonosDiscovery.search(timeoutMs = 800, address = "127.0.0.1", port = speaker.localPort)
        speaker.close()
        thread.join(1_000)
        assertEquals(setOf("http://192.168.1.20:1400"), found)
        assertTrue(synchronized(heard) { heard.first() }.startsWith("M-SEARCH"))
    }

    @Test
    fun `the household's topology becomes groups named for their rooms, without hidden halves and Boosts`() {
        FakeSonos().use { sonos ->
            val groups = runBlocking { SonosClient(http, sonos.baseUrl).topology() }
            assertEquals(1, groups.size)
            val group = groups.single()
            assertEquals("Living Room", group.name)
            assertEquals(sonos.uuid, group.coordinator.uuid)
            assertEquals(sonos.baseUrl, group.coordinator.baseUrl)
            assertEquals(1, group.rooms)
            assertEquals("GetZoneGroupState", sonos.actions().single())

            // Grouped with the kitchen and a bedroom, on older firmware (no outer ZoneGroupState).
            sonos.householdXml = """<ZoneGroups><ZoneGroup Coordinator="RINCON_K" ID="RINCON_K:9">""" +
                """<ZoneGroupMember UUID="RINCON_K" Location="http://192.0.2.5:1400/xml/device_description.xml" ZoneName="Kitchen &amp; Diner"/>""" +
                """<ZoneGroupMember UUID="${sonos.uuid}" Location="${sonos.baseUrl}/xml/device_description.xml" ZoneName="Living Room"/>""" +
                """<ZoneGroupMember UUID="RINCON_B" Location="http://192.0.2.6:1400/xml/device_description.xml" ZoneName="Bedroom"/>""" +
                """</ZoneGroup></ZoneGroups>"""
            val grouped = runBlocking { SonosClient(http, sonos.baseUrl).topology() }.single()
            assertEquals("Kitchen & Diner + 2", grouped.name)
            assertEquals("http://192.0.2.5:1400", grouped.coordinator.baseUrl)
            assertEquals(3, grouped.rooms)
        }
    }

    @Test
    fun `addresses people type become where speakers answer`() {
        assertEquals("http://192.168.1.20:1400", SonosClient.baseOf("192.168.1.20"))
        assertEquals("http://sonos-kitchen.lan:1400", SonosClient.baseOf(" sonos-kitchen.lan "))
        assertEquals("http://192.168.1.20:1401", SonosClient.baseOf("192.168.1.20:1401"))
        assertEquals("http://192.168.1.20:1400", SonosClient.baseOf("http://192.168.1.20:1400/"))
        assertEquals("http://[fd00::20]:1400", SonosClient.baseOf("[fd00::20]"))
        assertNull(SonosClient.baseOf(""))
        assertEquals(listOf("192.168.1.20", "10.0.0.5:1400"), SpeakerSetup.addresses("192.168.1.20,  10.0.0.5:1400\n"))
    }

    @Test
    fun `a song goes to the speaker with its details escaped, so the Sonos app shows them as they are`() = runBlocking {
        FakeSonos().use { sonos ->
            val renderer = SonosRenderer(SonosClient(http, sonos.baseUrl), grouped = false)
            val url = "http://192.168.1.10:8080/api/speakers/stream/eyJ1IjoiYSJ9/SIGNATURE_abc-123.flac"
            val media = SpeakerMedia(
                url = url, mime = "audio/flac", title = "Rock & Roll <Live> \"Encore\"", artist = "AC/DC & Friends", album = "Who's <Next>",
                artUrl = "http://192.168.1.10:8080/api/art?u=https%3A%2F%2Fimg&s=x&px=600", durationMs = 245_000, seekable = true, marker = "SIGNATURE_abc-123",
            )
            renderer.load(media)
            assertEquals(listOf("Stop", "SetAVTransportURI", "Play"), sonos.actions())
            val body = sonos.last("SetAVTransportURI")!!.body
            // The DIDL-Lite is itself XML inside the SOAP XML: escaped once inside, and again around it.
            assertTrue(body, "Rock &amp;amp; Roll &amp;lt;Live&amp;gt; &amp;quot;Encore&amp;quot;" in body)
            assertFalse("<dc:title>" in body)
            assertTrue("<CurrentURI>$url</CurrentURI>" in body)
            // The speaker reads back exactly what was sent.
            val didl = sonos.metadata
            assertEquals("Rock & Roll <Live> \"Encore\"", SonosXml.unescape(SonosXml.tag(didl, "title")!!))
            assertEquals("AC/DC & Friends", SonosXml.unescape(SonosXml.tag(didl, "creator")!!))
            assertEquals("Who's <Next>", SonosXml.unescape(SonosXml.tag(didl, "album")!!))
            assertEquals("object.item.audioItem.musicTrack", SonosXml.tag(didl, "class"))
            assertEquals(media.artUrl, SonosXml.unescape(SonosXml.tag(didl, "albumArtURI")!!))
            assertTrue("""protocolInfo="http-get:*:audio/flac:*" duration="0:04:05"""" in didl)
            assertEquals(url, SonosXml.unescape(SonosXml.tag(didl, "res")!!))
            assertEquals(url, sonos.uri)

            sonos.relTimeMs = 61_500
            val status = renderer.status()
            assertEquals(Transport.PLAYING, status.transport)
            assertEquals(61_000, status.positionMs)
            assertTrue(media.marker in status.playing)
            assertEquals(0.25, status.volume!!, 0.001)

            renderer.seek(90_000)
            assertTrue("<Unit>REL_TIME</Unit><Target>0:01:30</Target>" in sonos.last("Seek")!!.body)
            renderer.pause()
            assertEquals(Transport.PAUSED, renderer.status().transport)
            renderer.setVolume(0.42)
            assertTrue("<Channel>Master</Channel><DesiredVolume>42</DesiredVolume>" in sonos.last("SetVolume")!!.body)
            // Every action goes to its own service, with UPnP's InstanceID.
            assertTrue("""<u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID>""" in sonos.last("SetVolume")!!.body)

            // A group's volume is the group's.
            SonosRenderer(SonosClient(http, sonos.baseUrl), grouped = true).setVolume(0.7)
            assertEquals(70, sonos.groupVolume)
        }
    }

    @Test
    fun `a station plays as Sonos radio, which plays streams without an end`() = runBlocking {
        FakeSonos().use { sonos ->
            val url = "http://192.168.1.10:8080/api/speakers/stream/abc/SIG.aac"
            SonosRenderer(SonosClient(http, sonos.baseUrl), grouped = false).load(
                SpeakerMedia(url, "audio/aac", "Maddie's Radio", "Radio", live = true, marker = "SIG"),
            )
            assertEquals("x-rincon-mp3radio://192.168.1.10:8080/api/speakers/stream/abc/SIG.aac", sonos.uri)
            assertEquals("object.item.audioItem.audioBroadcast", SonosXml.tag(sonos.metadata, "class"))
            assertEquals("Maddie&apos;s Radio", SonosXml.tag(sonos.metadata, "title"))
        }
    }

    @Test
    fun `Sonos's refusals come back as plain words`() = runBlocking {
        FakeSonos().use { sonos ->
            sonos.uri = "x-rincon-mp3radio://somewhere"
            val error = runCatching { SonosRenderer(SonosClient(http, sonos.baseUrl), grouped = false).pause() }.exceptionOrNull()
            assertTrue(error is SpeakerException)
            assertEquals("Sonos refused Pause (error 701: it can't do that now)", error!!.message)
        }
        val gone = runCatching { SonosClient(http, "http://127.0.0.1:9").topology() }.exceptionOrNull()
        assertTrue(gone!!.message!!.startsWith("Couldn't reach the Sonos at 127.0.0.1:9"))
    }

    @Test
    fun `a network finds the household through any speaker that answers`() = runBlocking {
        FakeSonos().use { sonos ->
            var searched = 0
            val network = SonosNetwork(http, addresses = listOf("127.0.0.1:9"), discover = true, discovery = { searched++; setOf(sonos.baseUrl) })
            val groups = network.refresh()
            assertEquals(listOf("Living Room"), groups.map { it.name })
            assertEquals(1, searched)
            assertNull(network.problem)
            // Without discovery and with no speaker answering, it says so.
            val nothing = SonosNetwork(http, addresses = listOf("127.0.0.1:9"), discover = false)
            assertTrue(nothing.refresh().isEmpty())
            assertTrue(nothing.problem!!.startsWith("Couldn't reach the Sonos"))
        }
    }
}
