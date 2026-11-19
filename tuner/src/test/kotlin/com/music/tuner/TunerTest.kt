package com.music.tuner

import com.music.tuner.proc.Child
import com.music.tuner.proc.Children
import com.music.tuner.proc.Feeder
import com.music.tuner.proc.Supervised
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

class PlumbingTest {
    private fun script(body: String): File = Files.createTempFile("child", ".sh").toFile().apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    private fun waitFor(ms: Long = 10_000, what: String = "it", check: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Waited too long for $what")
            Thread.sleep(50)
        }
    }

    @Test
    fun `a child that stops by itself is started again`() {
        val log = Files.createTempFile("starts", ".txt").toFile()
        val crashing = script("echo started >> '${log.path}'; echo 'something broke' >&2; exit 3")
        val stops = mutableListOf<String>()
        val supervised = Supervised("crashing", { listOf(crashing.path) }, body = { it.process.waitFor() }, onStop = { synchronized(stops) { stops += it } })
        supervised.start()
        try {
            waitFor(what = "a restart") { log.readLines().size >= 2 }
        } finally {
            supervised.stop()
        }
        assertTrue(stops.first(), stops.first().contains("exit 3"))
        assertTrue(stops.first(), stops.first().contains("something broke"))
    }

    @Test
    fun `stopping a child stops it even if it ignores being asked, and what it started`() {
        val pidFile = Files.createTempFile("pid", ".txt").toFile()
        val stubborn = script("trap '' TERM; sleep 60 & echo \$! > '${pidFile.path}'; wait; wait")
        val child = Child.start("stubborn", listOf(stubborn.path))
        waitFor(what = "the grandchild") { pidFile.readText().isNotBlank() }
        val grandchild = ProcessHandle.of(pidFile.readText().trim().toLong()).get()
        assertTrue(grandchild.isAlive)
        child.stop(graceMs = 300)
        assertFalse(child.alive)
        waitFor(what = "the grandchild to stop") { !grandchild.isAlive }
    }

    @Test
    fun `a feeder passes what it's offered to its child, and keeps it going with silence`() {
        val out = Files.createTempFile("fed", ".bin").toFile()
        val sink = script("exec cat > '${out.path}'")
        val feeder = Feeder("sink", { listOf(sink.path) }, silence = ByteArray(4), silenceAfterMs = 50)
        feeder.start()
        try {
            feeder.offer("hello".toByteArray())
            waitFor(what = "the bytes") { out.length() >= 9 }
        } finally {
            feeder.stop()
        }
        val bytes = out.readBytes()
        assertEquals("hello", String(bytes, 0, 5))
        assertTrue(bytes.drop(5).all { it == 0.toByte() })
    }
}

/** The tuner as it runs, with stand-ins for the radio tools: scanning, listening, sharing and going idle. */
class TunerApiTest {
    private val fake = FakeRadio()
    private var app: TunerApp? = null
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var base = ""

    private fun start(tuners: Int = 1, token: String? = null): TunerApp {
        val tunerApp = TunerApp(fake.config(token), (0 until tuners).map { it.toString() }).start()
        val s = embeddedServer(Netty, port = 0, host = "127.0.0.1") { tunerModule(tunerApp) }.start(wait = false)
        base = "http://127.0.0.1:${runBlocking { s.engine.resolvedConnectors().first().port }}"
        app = tunerApp
        server = s
        return tunerApp
    }

    @After
    fun stop() {
        app?.close()
        server?.stop(100, 1_000)
        assertEquals("children left running", 0, Children.count())
    }

    private fun open(path: String, method: String = "GET", token: String? = null): HttpURLConnection =
        (URI("$base$path").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            connectTimeout = 5_000
            readTimeout = 10_000
        }

    private fun json(path: String, method: String = "GET", token: String? = null): JsonElement {
        val connection = open(path, method, token)
        val code = connection.responseCode
        val text = (if (code < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
        if (code >= 400) throw AssertionError("$path: HTTP $code $text")
        return Json.parseToJsonElement(text)
    }

    private fun waitFor(ms: Long = 30_000, what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Waited too long for $what")
            Thread.sleep(100)
        }
    }

    private fun JsonElement.str(key: String) = jsonObject[key]?.jsonPrimitive?.content

    private fun station(id: String) = json("/api/stations/$id").jsonObject

    private fun readAdts(input: InputStream) {
        val header = input.readNBytes(7)
        assertEquals(7, header.size)
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        assertEquals(0xF0, header[1].toInt() and 0xF6)
    }

    @Test
    fun `scan, then listen, share, find every tuner busy, and go quiet when everyone leaves`() {
        val tuner = start(tuners = 1)
        assertEquals(202, open("/api/scan", "POST").responseCode)
        waitFor(60_000, "the scan") { json("/api/scan").str("running") == "false" }
        assertEquals("done", json("/api/scan").str("phase"))

        val stations = json("/api/stations").jsonArray.map { it.jsonObject }
        assertEquals(listOf("97.1", "97.1-hd1", "97.1-hd2", "101.5"), stations.map { it.str("id") })
        val wxyz = stations[0]
        assertEquals("WXYZ FM", wxyz.str("name"))
        assertEquals("WXYZ", wxyz.str("callLetters"))
        assertEquals("ROCK 101", stations[3].str("name"))
        assertEquals("KROC", stations[3].str("callLetters"))
        assertEquals("WXYZ HD2", stations[2].str("name"))
        assertEquals("Jazz", stations[2].str("genre"))
        assertNotNull("signal measured at the scan", wxyz["signal"])
        assertTrue(fake.config().lineupFile.readText().contains("WXYZ FM"))

        // Two listeners share one tuner.
        val first = open("/api/stations/97.1/stream")
        assertEquals("audio/aac", first.contentType)
        readAdts(first.inputStream)
        val second = open("/api/stations/97.1/stream")
        readAdts(second.inputStream)
        waitFor(what = "RDS while playing") { station("97.1")["now"]?.jsonObject?.get("title")?.jsonPrimitive?.content == "Heroes" }
        val playing = station("97.1")
        assertEquals("true", playing.str("playing"))
        assertEquals("2", playing.str("listeners"))
        assertEquals("David Bowie", playing["now"]!!.str("artist"))
        waitFor(what = "the signal") { station("97.1")["signal"]?.jsonObject?.get("detail")?.jsonPrimitive?.content?.startsWith("Stereo") == true }

        // The only tuner is on 97.1: anything else has to wait.
        val busy = open("/api/stations/101.5/stream")
        assertEquals(503, busy.responseCode)
        assertTrue(busy.errorStream.bufferedReader().readText().contains("busy"))
        val status = json("/api/status").jsonObject["tuners"]!!.jsonArray[0]
        assertEquals("playing", status.str("state"))

        // Everyone leaves: after the grace period the tuner stops, and its programs with it.
        first.disconnect()
        second.disconnect()
        waitFor(what = "the tuner to go idle") { json("/api/status").jsonObject["tuners"]!!.jsonArray[0].str("state") == "idle" }
        waitFor(what = "the programs to stop") { Children.count() == 0 }
        assertEquals("0", station("97.1").str("listeners"))

        // HD2, with its own logo, from the same frequency's HD Radio.
        val hd2 = open("/api/stations/97.1-hd2/stream")
        readAdts(hd2.inputStream)
        waitFor(what = "HD2's logo") { station("97.1-hd2").str("logoUrl") != null }
        val view = station("97.1-hd2")
        assertEquals("So What", view["now"]!!.str("title"))
        val logo = open(view.str("logoUrl")!!)
        assertEquals("image/png", logo.contentType)
        assertEquals("PNG5678", String(logo.inputStream.readBytes()).drop(1))
        // HD1 joins the same tuner: one dongle plays both.
        val hd1 = open("/api/stations/97.1-hd1/stream")
        readAdts(hd1.inputStream)
        assertEquals(listOf("97.1-hd2", "97.1-hd1"), json("/api/status").jsonObject["tuners"]!!.jsonArray[0].jsonObject["stations"]!!.jsonArray.map { it.jsonPrimitive.content })
        hd1.disconnect()
        hd2.disconnect()
        // The logo stays known after the station stops.
        waitFor(what = "the tuner to go idle") { json("/api/status").jsonObject["tuners"]!!.jsonArray[0].str("state") == "idle" }
        assertNotNull(station("97.1-hd2").str("logoUrl"))
        tuner.close()
    }

    @Test
    fun `HDHomeRun-style discovery, adding a station by hand, and the token`() {
        start(tuners = 2, token = "s3cret")
        assertEquals(401, open("/api/stations").responseCode)
        assertEquals(401, open("/discover.json").responseCode)
        assertEquals(200, open("/").responseCode)
        assertEquals(200, open("/api/stations?token=s3cret").responseCode)

        val discover = json("/discover.json", token = "s3cret").jsonObject
        assertEquals("2", discover.str("TunerCount"))
        assertTrue(discover.str("LineupURL")!!.endsWith("/lineup.json?token=s3cret"))
        assertEquals(8, discover.str("DeviceID")!!.length)

        val add = open("/api/stations", "POST", "s3cret").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.write("""{"frequency":"88.5","name":"WXPN"}""".toByteArray())
        }
        assertEquals(201, add.responseCode)
        val lineup = json("/lineup.json", token = "s3cret") as JsonArray
        assertEquals("88.5", lineup[0].str("GuideNumber"))
        assertEquals("WXPN", lineup[0].str("GuideName"))
        assertTrue(lineup[0].str("URL")!!.endsWith("/api/stations/88.5/stream?token=s3cret"))
        assertEquals(204, open("/api/stations/88.5", "DELETE", "s3cret").responseCode)
        assertEquals(404, open("/api/stations/88.5/stream?token=s3cret").responseCode)
    }
}
