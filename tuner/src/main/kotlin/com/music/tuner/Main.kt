package com.music.tuner

import com.music.tuner.radio.Devices
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null, "serve" -> serve(TunerConfig.fromEnvironment())
        "version", "--version" -> println(TunerApp.version)
        "devices" -> {
            // What rtl_test finds, for checking the dongles are passed through.
            val found = Devices.detect(TunerConfig.fromEnvironment().tools)
            if (found.isEmpty()) println("No dongles found") else found.forEach { println("${it.index}: ${it.name}${it.serial?.let { s -> " (serial $s)" }.orEmpty()}") }
        }
        else -> {
            System.err.println("Usage: samm-tuner [serve | devices | version]")
            exitProcess(2)
        }
    }
}

private fun serve(config: TunerConfig) {
    val log = LoggerFactory.getLogger("Tuner")
    val devices = Devices.choose(config.devices) { Devices.detect(config.tools) }
    val app = TunerApp(config, devices).start()
    log.info(
        "SuperAwesomeMagicalMusic tuner {} on {}:{}, {} tuner{} (dongle{} {}), {}, {} stations{}",
        TunerApp.version, config.host, config.port, devices.size, if (devices.size == 1) "" else "s", if (devices.size == 1) "" else "s",
        devices.joinToString(", "), config.region.label, app.lineup.get().stations.size,
        if (config.token == null) ", open to everyone on the network (set TUNER_TOKEN to require a token)" else "",
    )
    val server = embeddedServer(Netty, port = config.port, host = config.host) { tunerModule(app) }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            // Children first: nothing is left holding a dongle.
            app.close()
            server.stop(500, 2_000)
        },
    )
    server.start(wait = true)
}
