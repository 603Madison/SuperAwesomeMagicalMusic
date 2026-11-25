package com.music.server.speakers

import com.music.player.net.Http
import com.music.player.speakers.sonos.FakeSonos
import com.music.player.speakers.sonos.SonosClient
import com.music.player.speakers.sonos.SonosXml
import com.music.server.speakers.sonos.SonosRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server's Sonos player (the protocol itself is tested in core's SonosTest). */
class SonosTest {
    private val http = Http.createClient()

    @Test
    fun `addresses people type are split up`() {
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
}
