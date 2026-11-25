package com.music.player.speakers.sonos

import com.music.player.net.Http
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Sonos's UPnP, as the server and the phone both speak it. */
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
        assertEquals("http://192.168.1.20:1400", SonosDiscovery.baseOf(ssdpAnswer))
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
            assertFalse(group.grouped)
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
            assertTrue(grouped.grouped)
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
    }

    @Test
    fun `a song's details are escaped twice, once in the DIDL-Lite and again around it`() = runBlocking {
        FakeSonos().use { sonos ->
            val player = SonosPlayer(SonosClient(http, sonos.baseUrl), grouped = false)
            val url = "http://192.168.1.10:4000/s/abc?x=1&y=2"
            player.load(SonosMedia(url, "audio/flac", "Rock & Roll <Live>", "AC/DC & Friends", "Who's <Next>", "http://192.168.1.10:4000/s/art", 245_000))
            assertEquals(listOf("Stop", "SetAVTransportURI"), sonos.actions())
            val body = sonos.last("SetAVTransportURI")!!.body
            assertTrue(body, "<CurrentURI>http://192.168.1.10:4000/s/abc?x=1&amp;y=2</CurrentURI>" in body)
            assertTrue(body, "&lt;dc:title&gt;Rock &amp;amp; Roll &amp;lt;Live&amp;gt;&lt;/dc:title&gt;" in body)
            assertEquals(url, sonos.uri)
            assertEquals("Who's <Next>", SonosXml.unescape(SonosXml.tag(sonos.metadata, "album")!!))
            assertTrue("""duration="0:04:05"""" in sonos.metadata)

            player.play()
            sonos.relTimeMs = 61_500
            val transport = player.transport()
            assertEquals("PLAYING", transport.state)
            assertEquals(61_000, transport.positionMs)
            assertEquals(240_000, transport.durationMs)
            assertEquals(url, transport.uri)
            assertEquals(0.25, player.volume()!!, 0.001)
        }
    }

    @Test
    fun `a group's volume is the group's, kept in proportion between its rooms`() = runBlocking {
        FakeSonos().use { sonos ->
            val group = SonosPlayer(SonosClient(http, sonos.baseUrl), grouped = true)
            group.setVolume(0.7)
            assertEquals(listOf("SnapshotGroupVolume", "SetGroupVolume"), sonos.actions())
            assertEquals(70, sonos.groupVolume)
            assertEquals(25, sonos.volume)
            assertEquals(0.7, group.volume()!!, 0.001)
            assertTrue("""<u:GetGroupVolume xmlns:u="urn:schemas-upnp-org:service:GroupRenderingControl:1"><InstanceID>0</InstanceID>""" in sonos.last("GetGroupVolume")!!.body)
        }
    }

    @Test
    fun `Sonos's refusals come back as plain words`() = runBlocking {
        FakeSonos().use { sonos ->
            sonos.uri = "x-rincon-mp3radio://somewhere"
            val error = runCatching { SonosPlayer(SonosClient(http, sonos.baseUrl), grouped = false).pause() }.exceptionOrNull()
            assertTrue(error is SonosException)
            assertEquals("Sonos refused Pause (error 701: it can't do that now)", error!!.message)
        }
        val gone = runCatching { SonosClient(http, "http://127.0.0.1:9").topology() }.exceptionOrNull()
        assertTrue(gone!!.message!!.startsWith("Couldn't reach the Sonos at 127.0.0.1:9"))
    }

    @Test
    fun `a network finds the household through any speaker that answers`() = runBlocking {
        FakeSonos().use { sonos ->
            var searched = 0
            var now = 1_000L
            val network = SonosNetwork(
                http, addresses = listOf("127.0.0.1:9"), discover = true, clock = { now },
                discovery = { searched++; setOf(sonos.baseUrl) }, rediscoverMs = 5_000, retryDiscoveryMs = 5_000,
            )
            val groups = network.refresh()
            assertEquals(listOf("Living Room"), groups.map { it.name })
            assertEquals(1, searched)
            assertNull(network.problem)
            // It looks on the network again only now and then, or when asked to.
            now += 1_000
            network.refresh()
            assertEquals(1, searched)
            now += 5_000
            network.refresh()
            assertEquals(2, searched)
            network.rediscover()
            network.refresh()
            assertEquals(3, searched)
            // Without discovery and with no speaker answering, it says so.
            val nothing = SonosNetwork(http, addresses = listOf("127.0.0.1:9"), discover = false)
            assertTrue(nothing.refresh().isEmpty())
            assertTrue(nothing.problem!!.startsWith("Couldn't reach the Sonos"))
        }
    }
}
